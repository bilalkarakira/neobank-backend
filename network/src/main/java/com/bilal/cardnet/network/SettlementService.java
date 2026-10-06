package com.bilal.cardnet.network;

import com.bilal.cardnet.network.SettlementFiles.Line;
import com.bilal.cardnet.shared.ClearingDirs;
import com.bilal.cardnet.shared.DataAccessException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The network's settlement (SPEC-P5-settlement.md sections 1 and 2): turns the cleared items of one business date into one net
 * movement per participant, labels it with a value date (T+1 business day) and writes a CSV and a camt.053-style XML statement for
 * each participant. For one cleared purchase X with interchange I and scheme fees S (acquirer) and T (issuer):
 * the issuer pays X - I + T, the acquirer receives X - I - S, the network receives S + T; the three add up to zero. A refund is
 * the same with every sign reversed (the fees of a refund are stored negative). A run that does not add up to zero is refused.
 *
 * <p>Only items the issuer posted count (POSTED, FORCE_POSTED, REFUND_POSTED); items already settled by an earlier run, duplicates,
 * rejected and refused items do not. The run row, its lines and the claim on the items are ONE database transaction; the files
 * are written after it, from the stored lines, so a crash in between is repaired by {@link #writePending()}.
 */
public final class SettlementService {

    /** The participant name the network goes by in its own line. */
    public static final String NETWORK = "NET";

    /** A finished run, with its lines. */
    public record Run(UUID id, String runNumber, LocalDate businessDate, LocalDate valueDate, int itemCount, List<Line> lines) {
    }

    private final NetworkConfig config;
    private final DataSource ds;
    private final Clock clock;
    private final ClearingDirs dirs;

    public SettlementService(NetworkConfig config, DataSource ds, Clock clock) {
        this.config = config;
        this.ds = ds;
        this.clock = clock;
        this.dirs = new ClearingDirs(config.clearingDir);
    }

    private static final String ELIGIBLE = "i.settlement_id is null and i.result in ('POSTED', 'FORCE_POSTED', 'REFUND_POSTED')";

    /** The oldest business date that still has cleared items nobody has settled, if any. */
    public Optional<LocalDate> oldestUnsettled() {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select min(f.business_date) from net_clearing_items i join net_clearing_files f on f.id = i.file_row where " + ELIGIBLE);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            java.sql.Date date = rs.getDate(1);
            return Optional.ofNullable(date == null ? null : date.toLocalDate());
        } catch (SQLException e) {
            throw new DataAccessException("could not look for unsettled items", e);
        }
    }

    /** How many cleared items are waiting to be settled (for the status page). */
    public int unsettledItems() {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select count(*) from net_clearing_items i where " + ELIGIBLE);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new DataAccessException("could not count unsettled items", e);
        }
    }

    /**
     * Settles the cleared items of {@code date} (the oldest unsettled date when null). Empty when there is nothing to settle.
     * Safe to repeat: a second call only finds what the first one did not settle.
     */
    public synchronized Optional<Run> run(LocalDate date) {
        LocalDate businessDate = date != null ? date : oldestUnsettled().orElse(null);
        if (businessDate == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        UUID runId = UUID.randomUUID();
        Run run;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                record Item(UUID id, boolean refund, BigDecimal amount, BigDecimal interchange, BigDecimal schemeAcquirer,
                        BigDecimal schemeIssuer, String acquirer, String issuer) {
                }
                List<Item> items = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("""
                        select i.id, i.kind, i.amount, i.interchange, i.scheme_fee_acquirer, i.scheme_fee_issuer, f.sender, o.issuer_prefix
                        from net_clearing_items i join net_clearing_files f on f.id = i.file_row
                        join net_clearing_outfiles o on o.id = i.outfile
                        where %s and f.business_date = ? order by i.id for update of i
                        """.formatted(ELIGIBLE))) {
                    ps.setObject(1, businessDate);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            items.add(new Item(rs.getObject(1, UUID.class), "REFUND".equals(rs.getString(2)), rs.getBigDecimal(3),
                                    rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8)));
                        }
                    }
                }
                if (items.isEmpty()) {
                    c.rollback();
                    return Optional.empty();
                }
                // per participant: purchases, refunds, signed interchange, signed own scheme fee, item count
                Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
                Map<String, Integer> counts = new LinkedHashMap<>();
                BigDecimal networkFees = BigDecimal.ZERO;
                for (Item item : items) {
                    add(sums, counts, "ISSUER|" + item.issuer, item.refund, item.amount, item.interchange, item.schemeIssuer);
                    add(sums, counts, "ACQUIRER|" + item.acquirer, item.refund, item.amount, item.interchange, item.schemeAcquirer);
                    networkFees = networkFees.add(item.schemeAcquirer).add(item.schemeIssuer);
                }
                List<Line> lines = new ArrayList<>();
                BigDecimal total = BigDecimal.ZERO;
                for (Map.Entry<String, BigDecimal[]> e : sums.entrySet()) {
                    String[] key = e.getKey().split("\\|");
                    BigDecimal[] s = e.getValue(); // purchases, refunds, interchange, scheme fee
                    BigDecimal base = s[0].subtract(s[1]).subtract(s[2]);
                    BigDecimal net = key[0].equals("ISSUER") ? base.add(s[3]).negate() : base.subtract(s[3]);
                    lines.add(new Line(key[1], key[0], scaled(s[0]), scaled(s[1]), scaled(s[2]), scaled(s[3]), scaled(net), counts.get(e.getKey())));
                    total = total.add(net);
                }
                lines.add(new Line(NETWORK, "NETWORK", scaled(BigDecimal.ZERO), scaled(BigDecimal.ZERO), scaled(BigDecimal.ZERO), scaled(networkFees),
                        scaled(networkFees), items.size()));
                total = total.add(networkFees);
                // the order the lines are read back in: issuers, then acquirers, then the network, each by name
                lines.sort(java.util.Comparator.comparingInt((Line l) -> l.role().equals("ISSUER") ? 1 : l.role().equals("ACQUIRER") ? 2 : 3)
                        .thenComparing(Line::participant));
                if (total.signum() != 0) {
                    throw new IllegalStateException("the settlement does not add up to zero (off by " + total.toPlainString() + ")");
                }

                long sequence;
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select nextval('settlement_seq')")) {
                    rs.next();
                    sequence = rs.getLong(1);
                }
                String runNumber = "S%07d".formatted(sequence);
                LocalDate valueDate = ValueDates.next(businessDate, config.holidays);
                exec(c, "insert into net_settlements (id, run_number, business_date, value_date, item_count, created_at) values (?, ?, ?, ?, ?, ?)",
                        runId, runNumber, businessDate, valueDate, items.size(), Timestamp.from(now));
                for (Line line : lines) {
                    exec(c, "insert into net_settlement_lines (id, settlement_id, participant, role, purchases, refunds, interchange, scheme_fee, net, item_count) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), runId, line.participant(), line.role(), line.purchases(),
                            line.refunds(), line.interchange(), line.schemeFee(), line.net(), line.items());
                }
                for (Item item : items) {
                    exec(c, "update net_clearing_items set settlement_id = ? where id = ?", runId, item.id);
                }
                c.commit();
                run = new Run(runId, runNumber, businessDate, valueDate, items.size(), lines);
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not settle", e);
        }
        writeFiles(run, now);
        return Optional.of(run);
    }

    private static void add(Map<String, BigDecimal[]> sums, Map<String, Integer> counts, String key, boolean refund, BigDecimal amount,
            BigDecimal interchange, BigDecimal scheme) {
        BigDecimal[] s = sums.computeIfAbsent(key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        if (refund) {
            s[1] = s[1].add(amount);
        } else {
            s[0] = s[0].add(amount);
        }
        s[2] = s[2].add(interchange);
        s[3] = s[3].add(scheme);
        counts.merge(key, 1, Integer::sum);
    }

    private static BigDecimal scaled(BigDecimal value) {
        BigDecimal v = value.setScale(2, java.math.RoundingMode.UNNECESSARY);
        return v.signum() == 0 ? BigDecimal.ZERO.setScale(2) : v;
    }

    // ---- files ----

    private void writeFiles(Run run, Instant createdAt) {
        for (Line line : run.lines()) {
            ClearingDirs.writeAtomically(dirs.settlementOut(), SettlementFiles.name(run.runNumber(), line.participant(), "csv"),
                    SettlementFiles.csv(run.runNumber(), run.businessDate(), run.valueDate(), line).getBytes(StandardCharsets.US_ASCII));
            ClearingDirs.writeAtomically(dirs.settlementOut(), SettlementFiles.name(run.runNumber(), line.participant(), "xml"),
                    SettlementFiles.xml(run.runNumber(), run.businessDate(), run.valueDate(), line, createdAt).getBytes(StandardCharsets.UTF_8));
        }
        try (Connection c = ds.getConnection()) {
            exec(c, "update net_settlements set files_written = true where id = ?", run.id());
        } catch (SQLException e) {
            throw new DataAccessException("could not mark the settlement files written", e);
        }
    }

    /** Writes the files of runs a crash left without them (same names, same content). Returns how many runs it repaired. */
    public synchronized int writePending() {
        List<Run> pending = runs(true);
        for (Run run : pending) {
            writeFiles(run, clock.instant());
        }
        return pending.size();
    }

    // ---- reading ----

    public List<Run> recentRuns(int limit) {
        List<Run> all = runs(false);
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    private List<Run> runs(boolean onlyWithoutFiles) {
        List<Run> runs = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select id, run_number, business_date, value_date, item_count from net_settlements "
                        + (onlyWithoutFiles ? "where not files_written " : "") + "order by created_at desc, run_number desc");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID id = rs.getObject(1, UUID.class);
                runs.add(new Run(id, rs.getString(2), rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class), rs.getInt(5), lines(c, id)));
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read settlement runs", e);
        }
        return runs;
    }

    private static List<Line> lines(Connection c, UUID settlementId) throws SQLException {
        List<Line> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select participant, role, purchases, refunds, interchange, scheme_fee, net, item_count "
                + "from net_settlement_lines where settlement_id = ? order by case role when 'ISSUER' then 1 when 'ACQUIRER' then 2 else 3 end, participant")) {
            ps.setObject(1, settlementId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new Line(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6),
                            rs.getBigDecimal(7), rs.getInt(8)));
                }
            }
        }
        return lines;
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
