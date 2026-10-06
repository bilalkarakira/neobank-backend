package com.bilal.neobank.card.clearing;

import com.bilal.neobank.card.clearing.SettlementPoster.Outcome;
import com.bilal.neobank.card.clearing.SettlementPoster.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the settlement statements the network leaves in {@code settlement-out/} for this bank (the CSV for its own participant id)
 * and has {@link SettlementPoster} decide each one. The statements are readable on purpose (totals only, no card data), so
 * they are NOT encrypted: a SHA-256 line protects against mix-ups, not against tampering, and NeoBank therefore checks the
 * figures against its own items instead of trusting them.
 *
 * <p>Deliberately not transactional: each statement is its own transaction. A file this bank cannot understand is REFUSED and
 * never looked at again; a file whose figures differ is DISPUTED and left for a person.
 */
@Service
public class SettlementIngestService {

    private static final Logger log = LoggerFactory.getLogger(SettlementIngestService.class);
    private static final long MAX_FILE_BYTES = 64 * 1024;
    private static final Pattern RUN = Pattern.compile("S\\d{7}");
    private static final Pattern AMOUNT = Pattern.compile("-?\\d{1,15}\\.\\d{2}");

    /** What one pass did. */
    public record Summary(int matched, int disputed, int refused) {
    }

    private final ClearingProperties properties;
    private final JdbcClient jdbc;
    private final SettlementPoster poster;

    public SettlementIngestService(ClearingProperties properties, JdbcClient jdbc, SettlementPoster poster) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.poster = poster;
    }

    public synchronized Summary ingest() {
        Path dir = Path.of(properties.dir(), "settlement-out");
        if (!Files.isDirectory(dir)) {
            return new Summary(0, 0, 0);
        }
        List<Path> files = new ArrayList<>();
        String suffix = "-" + properties.issuerId() + ".csv";
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(p -> p.getFileName().toString().startsWith("SETL-") && p.getFileName().toString().endsWith(suffix)).sorted().forEach(files::add);
        } catch (IOException e) {
            log.warn("cannot list the settlement folder ({})", e.getClass().getSimpleName());
            return new Summary(0, 0, 0);
        }
        int matched = 0;
        int disputed = 0;
        int refused = 0;
        for (Path path : files) {
            String name = path.getFileName().toString();
            Boolean known = jdbc.sql("select exists (select 1 from settlement_runs where file_name = :n)").param("n", name).query(Boolean.class).single();
            if (known) {
                continue;
            }
            Outcome outcome = handle(path, name);
            switch (outcome.status()) {
                case "MATCHED" -> matched++;
                case "RETRY" -> { }
                case "DISPUTED" -> disputed++;
                default -> refused++;
            }
        }
        return new Summary(matched, disputed, refused);
    }

    private Outcome handle(Path path, String name) {
        String text;
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                return refuse(name, "the file is larger than " + MAX_FILE_BYTES + " bytes");
            }
            text = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            log.warn("cannot read a settlement file ({}); will retry", e.getClass().getSimpleName());
            return new Outcome("RETRY", null);
        }
        Statement statement;
        try {
            statement = parse(name, text);
        } catch (IllegalArgumentException e) {
            return refuse(name, e.getMessage());
        }
        return poster.post(statement);
    }

    private Outcome refuse(String name, String reason) {
        poster.refuse(name, reason);
        return new Outcome("REFUSED", reason);
    }

    /** Reads and checks the three lines of a statement; throws {@link IllegalArgumentException} with the reason when it is not one. */
    Statement parse(String fileName, String text) {
        if (!text.endsWith("\n") || text.chars().anyMatch(c -> c != '\n' && (c < 0x20 || c > 0x7E))) {
            throw new IllegalArgumentException("not a settlement statement (it must be printable text ending with a newline)");
        }
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length != 3) {
            throw new IllegalArgumentException("a statement has exactly three lines, this has " + lines.length);
        }
        String[] header = lines[0].split(",", -1);
        String[] day = lines[1].split(",", -1);
        String[] trailer = lines[2].split(",", -1);
        if (header.length != 6 || !header[0].equals("SETTLEMENT")) {
            throw new IllegalArgumentException("the first line is not a SETTLEMENT header");
        }
        if (day.length != 7 || !day[0].equals("DAY")) {
            throw new IllegalArgumentException("the second line is not a DAY line");
        }
        if (trailer.length != 3 || !trailer[0].equals("TRAILER")) {
            throw new IllegalArgumentException("the third line is not a TRAILER");
        }
        if (!sha256(lines[0] + "\n" + lines[1] + "\n").equals(trailer[2])) {
            throw new IllegalArgumentException("the hash in the trailer does not match the lines (the file was changed or damaged)");
        }
        if (!RUN.matcher(header[1]).matches()) {
            throw new IllegalArgumentException("the run number " + header[1] + " is not valid");
        }
        if (!header[4].equals(properties.issuerId()) || !header[5].equals("ISSUER")) {
            throw new IllegalArgumentException("the statement is for " + header[5] + " " + header[4] + ", not for this bank");
        }
        if (!fileName.equals("SETL-" + header[1] + "-" + header[4] + ".csv")) {
            throw new IllegalArgumentException("the file name does not match the run and participant in the header");
        }
        try {
            LocalDate businessDate = LocalDate.parse(header[2]);
            LocalDate valueDate = LocalDate.parse(header[3]);
            if (!day[1].equals(header[2])) {
                throw new IllegalArgumentException("the DAY line is for " + day[1] + ", the header says " + header[2]);
            }
            int items = Integer.parseInt(trailer[1]);
            if (items < 0 || !Integer.toString(items).equals(trailer[1])) {
                throw new IllegalArgumentException("the item count is not valid");
            }
            return new Statement(fileName, header[1], businessDate, valueDate, amount(day[2]), amount(day[3]), amount(day[4]), amount(day[5]),
                    amount(day[6]), items);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new IllegalArgumentException("a date or a number in the statement is not valid");
        }
    }

    private static BigDecimal amount(String text) {
        if (!AMOUNT.matcher(text).matches()) {
            throw new NumberFormatException(text);
        }
        return new BigDecimal(text);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
