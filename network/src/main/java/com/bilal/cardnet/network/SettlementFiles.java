package com.bilal.cardnet.network;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/**
 * The two statements written for each participant of a settlement run (totals only, no card data; readable on purpose, see
 * SPEC-P5-settlement.md section 2): a plain CSV with a SHA-256 over its lines, and a camt.053-STYLE XML statement. The XML is a
 * simplified subset of ISO 20022 camt.053 (well formed, same totals as the CSV), NOT validated against the ISO schema.
 */
public final class SettlementFiles {

    /** One participant's line of a run, as stored. {@code net} is signed: positive means the participant receives money. */
    public record Line(String participant, String role, BigDecimal purchases, BigDecimal refunds, BigDecimal interchange,
            BigDecimal schemeFee, BigDecimal net, int items) {
    }

    private SettlementFiles() {
    }

    public static String name(String runNumber, String participant, String extension) {
        return "SETL-" + runNumber + "-" + participant + "." + extension;
    }

    // ---- CSV ----

    public static String csv(String runNumber, LocalDate businessDate, LocalDate valueDate, Line line) {
        String body = "SETTLEMENT," + runNumber + "," + businessDate + "," + valueDate + "," + line.participant() + "," + line.role() + "\n"
                + "DAY," + businessDate + "," + money(line.purchases()) + "," + money(line.refunds()) + "," + money(line.interchange())
                + "," + money(line.schemeFee()) + "," + money(line.net()) + "\n";
        return body + "TRAILER," + line.items() + "," + sha256(body) + "\n";
    }

    // ---- camt.053-style XML ----

    /** Which way each kind of movement goes for a participant: true = a positive amount is a credit (money in). */
    private record Movement(String text, BigDecimal signed) {
    }

    public static String xml(String runNumber, LocalDate businessDate, LocalDate valueDate, Line line, Instant createdAt) {
        List<Movement> movements = switch (line.role()) {
            case "ISSUER" -> List.of(new Movement("Purchases cleared", line.purchases().negate()),
                    new Movement("Refunds cleared", line.refunds()), new Movement("Interchange earned", line.interchange()),
                    new Movement("Scheme fee", line.schemeFee().negate()));
            case "ACQUIRER" -> List.of(new Movement("Purchases cleared", line.purchases()),
                    new Movement("Refunds cleared", line.refunds().negate()), new Movement("Interchange paid", line.interchange().negate()),
                    new Movement("Scheme fee", line.schemeFee().negate()));
            default -> List.of(new Movement("Scheme fees", line.schemeFee()));
        };
        String id = "SETL-" + runNumber + "-" + line.participant();
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<Document xmlns=\"urn:neobank:simulation:camt.053.001.08-subset\">\n");
        out.append(" <BkToCstmrStmt>\n");
        out.append("  <GrpHdr><MsgId>").append(esc(id)).append("</MsgId><CreDtTm>").append(createdAt).append("</CreDtTm></GrpHdr>\n");
        out.append("  <Stmt>\n");
        out.append("   <Id>").append(esc(id)).append("</Id>\n");
        out.append("   <Acct><Id><Othr><Id>").append(esc(line.participant())).append("</Id></Othr></Id><Ownr><Nm>")
                .append(esc(line.role() + " " + line.participant())).append("</Nm></Ownr></Acct>\n");
        out.append("   <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"USD\">0.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>")
                .append(valueDate).append("</Dt></Dt></Bal>\n");
        out.append("   <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"USD\">").append(money(line.net().abs()))
                .append("</Amt><CdtDbtInd>").append(line.net().signum() >= 0 ? "CRDT" : "DBIT").append("</CdtDbtInd><Dt><Dt>")
                .append(valueDate).append("</Dt></Dt></Bal>\n");
        for (Movement m : movements) {
            if (m.signed().signum() == 0) {
                continue;
            }
            out.append("   <Ntry><Amt Ccy=\"USD\">").append(money(m.signed().abs())).append("</Amt><CdtDbtInd>")
                    .append(m.signed().signum() > 0 ? "CRDT" : "DBIT").append("</CdtDbtInd><Sts>BOOK</Sts><BookgDt><Dt>").append(businessDate)
                    .append("</Dt></BookgDt><ValDt><Dt>").append(valueDate).append("</Dt></ValDt><AcctSvcrRef>").append(esc(runNumber))
                    .append("</AcctSvcrRef><AddtlNtryInf>").append(esc(m.text())).append("</AddtlNtryInf></Ntry>\n");
        }
        out.append("  </Stmt>\n </BkToCstmrStmt>\n</Document>\n");
        return out.toString();
    }

    // ---- helpers ----

    private static String money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** SHA-256 (lower case hex) of the text as US-ASCII bytes. */
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
