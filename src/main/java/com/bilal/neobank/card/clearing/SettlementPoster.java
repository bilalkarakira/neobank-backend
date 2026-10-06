package com.bilal.neobank.card.clearing;

import com.bilal.neobank.ledger.Fingerprint;
import com.bilal.neobank.ledger.LedgerService;
import com.bilal.neobank.ledger.Posting;
import com.bilal.neobank.ledger.SystemAccounts;
import com.bilal.neobank.ledger.TransactionType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Decides and posts ONE settlement statement of the network, in ONE database transaction (SPEC-P5-settlement.md, section 3).
 * The statement's row is inserted first under a unique file name, so however many ingests run at once a statement is decided once.
 *
 * <p>NeoBank does not trust the figures: it adds up the items it posted for that business date itself (amounts as cleared, the
 * interchange it earns and the scheme fee it pays at the agreed rates, each rounded half up to the cent per item, exactly as the
 * network rounds them) and compares. All equal: MATCHED, the items are marked settled and the three ledger moves are made.
 * Anything different: DISPUTED, nothing is posted, the differences are written down for a person.
 */
@Service
public class SettlementPoster {

    /** A statement as read from its CSV file. {@code net} is signed: positive = NeoBank receives money. */
    public record Statement(String fileName, String runNumber, LocalDate businessDate, LocalDate valueDate, BigDecimal purchases,
            BigDecimal refunds, BigDecimal interchange, BigDecimal schemeFee, BigDecimal net, int itemCount) {
    }

    /** What happened to a statement. */
    public record Outcome(String status, String reason) {
    }

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final ClearingProperties properties;
    private final Clock clock;

    public SettlementPoster(JdbcClient jdbc, LedgerService ledger, ClearingProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.properties = properties;
        this.clock = clock;
    }

    /** Records a file that could not be understood at all (nothing is posted). Safe to repeat. */
    @Transactional
    public void refuse(String fileName, String reason) {
        jdbc.sql("""
                insert into settlement_runs (id, file_name, status, reason, received_at) values (:id, :name, 'REFUSED', :reason, :now)
                on conflict (file_name) do nothing
                """).param("id", UUID.randomUUID()).param("name", fileName).param("reason", truncate(reason, 400))
                .param("now", Timestamp.from(clock.instant())).update();
    }

    @Transactional
    public Outcome post(Statement s) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                insert into settlement_runs (id, file_name, run_number, business_date, value_date, status, purchases, refunds, interchange,
                                             scheme_fee, net, item_count, reason, received_at)
                values (:id, :name, :run, :bdate, :vdate, 'REFUSED', :p, :r, :i, :s, :n, :count, 'being decided', :now)
                on conflict (file_name) do nothing
                """).param("id", id).param("name", s.fileName()).param("run", s.runNumber()).param("bdate", s.businessDate())
                .param("vdate", s.valueDate()).param("p", s.purchases()).param("r", s.refunds()).param("i", s.interchange())
                .param("s", s.schemeFee()).param("n", s.net()).param("count", s.itemCount()).param("now", Timestamp.from(clock.instant())).update();
        if (inserted == 0) {
            return jdbc.sql("select status, reason from settlement_runs where file_name = :n").param("n", s.fileName())
                    .query((rs, i) -> new Outcome(rs.getString(1), rs.getString(2))).single();
        }
        if (jdbc.sql("select exists (select 1 from settlement_runs where run_number = :run and status = 'MATCHED')").param("run", s.runNumber())
                .query(Boolean.class).single()) {
            return finish(id, "REFUSED", "run " + s.runNumber() + " was already posted", null);
        }

        record Item(UUID id, boolean refund, BigDecimal amount) {
        }
        List<Item> items = jdbc.sql("""
                select i.id, i.kind, i.amount from clearing_items i join clearing_files f on f.id = i.file_id
                where i.settlement_run_id is null and i.result in ('POSTED', 'FORCE_POSTED', 'REFUND_POSTED') and f.business_date = :d
                order by i.id for update of i
                """).param("d", s.businessDate()).query((rs, i) -> new Item(rs.getObject(1, UUID.class), "REFUND".equals(rs.getString(2)),
                rs.getBigDecimal(3))).list();
        BigDecimal purchases = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
        BigDecimal interchange = BigDecimal.ZERO;
        BigDecimal schemeFee = BigDecimal.ZERO;
        for (Item item : items) {
            BigDecimal ic = item.amount.multiply(properties.interchangeRate()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal sf = item.amount.multiply(properties.schemeFeeRate()).setScale(2, RoundingMode.HALF_UP);
            if (item.refund) {
                refunds = refunds.add(item.amount);
                interchange = interchange.subtract(ic);
                schemeFee = schemeFee.subtract(sf);
            } else {
                purchases = purchases.add(item.amount);
                interchange = interchange.add(ic);
                schemeFee = schemeFee.add(sf);
            }
        }
        BigDecimal net = purchases.subtract(refunds).subtract(interchange).add(schemeFee).negate();

        List<String> differences = new ArrayList<>();
        compare(differences, "purchases", s.purchases(), purchases);
        compare(differences, "refunds", s.refunds(), refunds);
        compare(differences, "interchange", s.interchange(), interchange);
        compare(differences, "scheme fee", s.schemeFee(), schemeFee);
        compare(differences, "net", s.net(), net);
        if (s.itemCount() != items.size()) {
            differences.add("items: file " + s.itemCount() + ", NeoBank " + items.size());
        }
        Own own = new Own(purchases, refunds, interchange, schemeFee, net, items.size());
        if (!differences.isEmpty()) {
            return finish(id, "DISPUTED", "the figures differ: " + String.join("; ", differences), own);
        }

        for (Item item : items) {
            jdbc.sql("update clearing_items set settlement_run_id = :run where id = :id").param("run", id).param("id", item.id).update();
        }
        BigDecimal gross = purchases.subtract(refunds);
        // 1. what leaves for the settlement bank: the gross less the interchange this bank earns
        move(id, 1, "bank", SystemAccounts.CARD_SETTLEMENT, SystemAccounts.SETTLEMENT_BANK, gross.subtract(interchange), s.runNumber());
        // 2. the interchange earned
        move(id, 2, "interchange", SystemAccounts.CARD_SETTLEMENT, SystemAccounts.INTERCHANGE_INCOME, interchange, s.runNumber());
        // 3. the scheme fee paid, out of that income
        move(id, 3, "scheme fee", SystemAccounts.INTERCHANGE_INCOME, SystemAccounts.SETTLEMENT_BANK, schemeFee, s.runNumber());
        return finish(id, "MATCHED", null, own);
    }

    private record Own(BigDecimal purchases, BigDecimal refunds, BigDecimal interchange, BigDecimal schemeFee, BigDecimal net, int count) {
    }

    private Outcome finish(UUID id, String status, String reason, Own own) {
        jdbc.sql("""
                update settlement_runs set status = :status, reason = cast(:reason as varchar), own_purchases = cast(:p as numeric), own_refunds = cast(:r as numeric),
                       own_interchange = cast(:i as numeric), own_scheme_fee = cast(:s as numeric), own_net = cast(:n as numeric),
                       own_item_count = cast(:c as integer) where id = :id
                """).param("status", status).param("reason", truncate(reason, 400)).param("p", own == null ? null : own.purchases)
                .param("r", own == null ? null : own.refunds).param("i", own == null ? null : own.interchange)
                .param("s", own == null ? null : own.schemeFee).param("n", own == null ? null : own.net)
                .param("c", own == null ? null : own.count).param("id", id).update();
        return new Outcome(status, reason);
    }

    /** One two-leg ledger transaction of {@code amount} from one account to another; a negative amount goes the other way. */
    private void move(UUID runId, int part, String what, UUID from, UUID to, BigDecimal amount, String runNumber) {
        if (amount.signum() == 0) {
            return;
        }
        UUID source = amount.signum() > 0 ? from : to;
        UUID target = amount.signum() > 0 ? to : from;
        BigDecimal size = amount.abs();
        String key = "stl-" + runId + "-" + part;
        ledger.post(TransactionType.CARD_SETTLEMENT, null, key, Fingerprint.sha256(key + "|" + amount.toPlainString()),
                "Card settlement " + runNumber + " (" + what + ")", List.of(new Posting(source, size.negate()), new Posting(target, size)));
    }

    private static void compare(List<String> differences, String what, BigDecimal file, BigDecimal own) {
        if (file == null || file.compareTo(own) != 0) {
            differences.add(what + ": file " + (file == null ? "none" : file.toPlainString()) + ", NeoBank " + own.toPlainString());
        }
    }

    private static String truncate(String text, int max) {
        return text == null ? null : text.length() <= max ? text : text.substring(0, max);
    }
}
