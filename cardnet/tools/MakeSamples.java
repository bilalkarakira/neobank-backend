import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Inspector;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingDump;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.network.SettlementFiles;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/**
 * Writes the sample files of samples/ (test card number, development key, fixed times): ISO 8583 messages as text, as framed hex
 * and as the inspector shows them; a clearing file and its acknowledgement; the settlement statements. Run it from the cardnet
 * folder after building (samples are checked in, and SamplesTest decodes every one of them, so they cannot rot):
 *
 * <pre>
 *   java -cp network/target/network-0.1.0-SNAPSHOT.jar tools/MakeSamples.java
 * </pre>
 * The clearing file is encrypted with the development key under the file name B0001.clr: look inside it with ClearingDump.
 */
public class MakeSamples {

    static final Packager PACKAGER = new Packager();

    public static void main(String[] args) throws Exception {
        Path root = Path.of("samples");
        Path iso = root.resolve("iso8583");
        Files.createDirectories(iso);

        Message request = Message.of("0100").set(2, "9999000000000012").set(3, "000000").set(4, "000000002500").set(7, "1006120000")
                .set(11, "000101").set(12, "120000").set(13, "1006").set(14, "2910").set(18, "5814").set(22, "051").set(25, "00")
                .set(32, "123456").set(37, "000000000101").set(41, "TERM0001").set(42, "MERCHANT0000001")
                .set(43, "%-40s".formatted("Corner Cafe")).set(49, "840");
        Message response = Message.of("0110").set(2, request.get(2)).set(3, "000000").set(4, request.get(4)).set(7, request.get(7))
                .set(11, request.get(11)).set(12, request.get(12)).set(13, request.get(13)).set(37, request.get(37)).set(38, "A1B2C3")
                .set(39, "00").set(41, request.get(41)).set(42, request.get(42)).set(49, "840");
        Message declined = response.copyWithMti("0110").set(39, "51").remove(38);
        Message reversal = Message.of("0400").set(2, request.get(2)).set(3, "000000").set(4, request.get(4)).set(7, "1006120500").set(11, "000102")
                .set(12, "120500").set(13, "1006").set(37, request.get(37)).set(41, request.get(41)).set(42, request.get(42)).set(49, "840")
                .set(90, "0100" + "000101" + "1006120000" + "00000123456" + "00000000000");
        Message advice = request.copyWithMti("0120").set(38, "SI4F2K").set(39, "00");
        Message signOn = Message.of("0800").set(7, "1006115900").set(11, "000100").set(70, "001");
        Message signOnAnswer = Message.of("0810").set(7, "1006115900").set(11, "000100").set(39, "00").set(70, "001");
        iso("0100-authorization-request", request, iso);
        iso("0110-authorization-approved", response, iso);
        iso("0110-authorization-declined-51", declined, iso);
        iso("0400-reversal", reversal, iso);
        iso("0120-standin-advice", advice, iso);
        iso("0800-sign-on", signOn, iso);
        iso("0810-sign-on-answer", signOnAnswer, iso);

        // clearing: one purchase with a tip (authorized 25.00, cleared 28.75) and one refund
        Path clearing = root.resolve("clearing");
        Files.createDirectories(clearing);
        Message purchase = Message.of("1240").set(2, "9999000000000012").set(3, "000000").set(4, "000000002875").set(7, "1006120000")
                .set(11, "000101").set(12, "120000").set(13, "1006").set(14, "2910").set(18, "5814").set(32, "123456").set(37, "000000000101")
                .set(38, "A1B2C3").set(41, "TERM0001").set(42, "MERCHANT0000001").set(43, "%-40s".formatted("Corner Cafe")).set(49, "840");
        Message refund = Message.of("1240").set(2, "9999000000000012").set(3, "200000").set(4, "000000000500").set(7, "1006180000")
                .set(11, "000110").set(12, "180000").set(13, "1006").set(14, "2910").set(18, "5814").set(32, "123456").set(37, "000000000110")
                .set(41, "TERM0001").set(42, "MERCHANT0000001").set(43, "%-40s".formatted("Corner Cafe")).set(49, "840");
        ClearingFileCodec codec = ClearingFileCodec.withSecret(ClearingFileCodec.DEFAULT_SECRET);
        ClearingFile file = new ClearingFile("B0001", LocalDate.of(2026, 10, 6), "123456", "NET", "USD", List.of(purchase, refund));
        codec.write(clearing, "B0001.clr", file);
        Files.writeString(clearing.resolve("B0001.dump.txt"), ClearingDump.describe(file), StandardCharsets.UTF_8);
        ClearingAck ack = ClearingAck.accepted("B0001", List.of(new ClearingAck.Item(1, "POSTED", ""), new ClearingAck.Item(2, "REFUND_POSTED", "")));
        codec.writeAck(clearing, "B0001.ack", ack);
        StringBuilder plain = new StringBuilder("ACKHDR|B0001|ACCEPTED|\n");
        for (ClearingAck.Item item : ack.items()) {
            plain.append("ITEM|").append(item.record()).append('|').append(item.result()).append('|').append(item.reason()).append('\n');
        }
        plain.append("ACKEND|").append(ack.items().size()).append('\n');
        Files.writeString(clearing.resolve("B0001.ack.plain.txt"), plain.toString(), StandardCharsets.US_ASCII);

        // settlement of that day (one purchase 28.75 and one refund 5.00: interchange 1.2%, scheme fee 0.05% each side)
        Path settlement = root.resolve("settlement");
        Files.createDirectories(settlement);
        LocalDate business = LocalDate.of(2026, 10, 6);
        LocalDate value = LocalDate.of(2026, 10, 7);
        List<SettlementFiles.Line> lines = List.of(
                new SettlementFiles.Line("999900", "ISSUER", new BigDecimal("28.75"), new BigDecimal("5.00"), new BigDecimal("0.29"), new BigDecimal("0.01"),
                        new BigDecimal("-23.47"), 2),
                new SettlementFiles.Line("123456", "ACQUIRER", new BigDecimal("28.75"), new BigDecimal("5.00"), new BigDecimal("0.29"), new BigDecimal("0.01"),
                        new BigDecimal("23.45"), 2),
                new SettlementFiles.Line("NET", "NETWORK", BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        new BigDecimal("0.02"), new BigDecimal("0.02"), 2));
        for (SettlementFiles.Line line : lines) {
            Files.writeString(settlement.resolve(SettlementFiles.name("S0000001", line.participant(), "csv")),
                    SettlementFiles.csv("S0000001", business, value, line), StandardCharsets.US_ASCII);
            Files.writeString(settlement.resolve(SettlementFiles.name("S0000001", line.participant(), "xml")),
                    SettlementFiles.xml("S0000001", business, value, line, Instant.parse("2026-10-06T21:00:00Z")), StandardCharsets.UTF_8);
        }
        System.out.println("samples written to " + root.toAbsolutePath());
    }

    static void iso(String name, Message message, Path dir) throws Exception {
        byte[] bytes = PACKAGER.pack(message);
        Files.writeString(dir.resolve(name + ".txt"), new String(bytes, StandardCharsets.US_ASCII) + "\n", StandardCharsets.US_ASCII);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        Frame.write(framed, bytes);
        Files.writeString(dir.resolve(name + ".hex"), HexFormat.of().withUpperCase().formatHex(framed.toByteArray()) + "\n", StandardCharsets.US_ASCII);
        Files.writeString(dir.resolve(name + ".inspect.txt"), Inspector.format(message), StandardCharsets.UTF_8);
    }
}
