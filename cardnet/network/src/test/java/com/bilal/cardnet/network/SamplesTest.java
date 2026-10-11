package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingDump;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.iso8583.clearing.ClearingFileException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checked-in samples (samples/ at the root of this repository) are read by people, so they must stay true: every one is decoded
 * here and, where it can be, written again byte for byte. A change to the codec or the statements that breaks a sample fails the build.
 */
class SamplesTest {

    private static final Path SAMPLES = Path.of("..", "samples");
    private static final Packager PACKAGER = new Packager();

    private static List<Path> files(String folder, String suffix) throws Exception {
        try (Stream<Path> list = Files.list(SAMPLES.resolve(folder))) {
            return list.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    // ---- ISO 8583 messages ----

    @Test
    void everyMessageSampleDecodesAndIsWrittenBackIdenticallyAndMatchesItsHexAndHasNoPin() throws Exception {
        List<Path> texts = files("iso8583", ".txt").stream().filter(p -> !p.getFileName().toString().endsWith(".inspect.txt")).toList();
        assertThat(texts).extracting(p -> p.getFileName().toString().substring(0, 4)).contains("0100", "0110", "0120", "0400", "0800", "0810");
        for (Path text : texts) {
            String name = text.getFileName().toString().replace(".txt", "");
            byte[] bytes = Files.readString(text, StandardCharsets.US_ASCII).strip().getBytes(StandardCharsets.US_ASCII);
            Message message = PACKAGER.unpack(bytes);
            assertThat(PACKAGER.pack(message)).as(name).isEqualTo(bytes);
            assertThat(message.mti()).as(name).isEqualTo(name.substring(0, 4));
            assertThat(message.has(52)).as(name + " carries no PIN data").isFalse();

            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            Frame.write(framed, bytes);
            assertThat(Files.readString(SAMPLES.resolve("iso8583").resolve(name + ".hex")).strip()).as(name + " hex")
                    .isEqualTo(HexFormat.of().withUpperCase().formatHex(framed.toByteArray()));

            String inspect = Files.readString(SAMPLES.resolve("iso8583").resolve(name + ".inspect.txt"));
            assertThat(inspect).as(name + " inspector output shows the number masked").doesNotContain("9999000000000012");
            assertThat(inspect).startsWith("MTI " + name.substring(0, 4));
        }
    }

    // ---- clearing ----

    private final ClearingFileCodec codec = ClearingFileCodec.withSecret(ClearingFileCodec.DEFAULT_SECRET);

    @Test
    void theClearingSampleDecodesWithTheDevelopmentKeyAndItsDumpIsWhatTheToolPrints() throws Exception {
        ClearingFile file = codec.read(SAMPLES.resolve("clearing").resolve("B0001.clr"));

        assertThat(file.fileId()).isEqualTo("B0001");
        assertThat(file.sender()).isEqualTo("123456");
        assertThat(file.receiver()).isEqualTo("NET");
        assertThat(file.presentments()).extracting(m -> m.get(3)).containsExactly("000000", "200000");
        assertThat(file.presentments()).extracting(m -> m.get(4)).containsExactly("000000002875", "000000000500");
        assertThat(Files.readString(SAMPLES.resolve("clearing").resolve("B0001.dump.txt"))).isEqualTo(ClearingDump.describe(file));
        assertThat(Files.readString(SAMPLES.resolve("clearing").resolve("B0001.dump.txt"))).doesNotContain("9999000000000012");
    }

    @Test
    void aDamagedOrRenamedClearingSampleIsRefused() throws Exception {
        byte[] bytes = Files.readAllBytes(SAMPLES.resolve("clearing").resolve("B0001.clr"));
        byte[] damaged = bytes.clone();
        damaged[damaged.length / 2] ^= 1;

        assertThat(codec.decode(bytes, "B0001.clr")).isNotNull();
        assertThatThrownBy(() -> codec.decode(damaged, "B0001.clr")).isInstanceOf(ClearingFileException.class);
        assertThatThrownBy(() -> codec.decode(bytes, "B0002.clr")).isInstanceOf(ClearingFileException.class);
        assertThatThrownBy(() -> ClearingFileCodec.withSecret("another key").decode(bytes, "B0001.clr")).isInstanceOf(ClearingFileException.class);
    }

    @Test
    void theAcknowledgementSampleDecodesAndItsPlainTextMatches() throws Exception {
        ClearingAck ack = codec.readAck(SAMPLES.resolve("clearing").resolve("B0001.ack"));

        assertThat(ack.status()).isEqualTo(ClearingAck.Status.ACCEPTED);
        assertThat(ack.items()).extracting(ClearingAck.Item::result).containsExactly("POSTED", "REFUND_POSTED");
        assertThat(Files.readString(SAMPLES.resolve("clearing").resolve("B0001.ack.plain.txt")))
                .isEqualTo("ACKHDR|B0001|ACCEPTED|\nITEM|1|POSTED|\nITEM|2|REFUND_POSTED|\nACKEND|2\n");
    }

    // ---- settlement ----

    @Test
    void theSettlementSamplesAddUpAndAreWhatTheNetworkWritesAndTheXmlAgreesWithTheCsv() throws Exception {
        BigDecimal total = BigDecimal.ZERO;
        for (Path csv : files("settlement", ".csv")) {
            String text = Files.readString(csv);
            String[] lines = text.split("\n");
            String[] header = lines[0].split(",");
            String[] day = lines[1].split(",");
            BigDecimal purchases = new BigDecimal(day[2]);
            BigDecimal refunds = new BigDecimal(day[3]);
            BigDecimal interchange = new BigDecimal(day[4]);
            BigDecimal scheme = new BigDecimal(day[5]);
            BigDecimal net = new BigDecimal(day[6]);
            String role = header[5];
            BigDecimal expected = switch (role) {
                case "ISSUER" -> purchases.subtract(refunds).subtract(interchange).add(scheme).negate();
                case "ACQUIRER" -> purchases.subtract(refunds).subtract(interchange).subtract(scheme);
                default -> scheme;
            };
            assertThat(net).as(csv.getFileName() + " net").isEqualByComparingTo(expected);
            total = total.add(net);

            // the trailer's hash is the hash of the first two lines, and the whole file is what the network writes for those figures
            assertThat(lines[2]).isEqualTo("TRAILER," + lines[2].split(",")[1] + "," + SettlementFiles.sha256(lines[0] + "\n" + lines[1] + "\n"));
            SettlementFiles.Line line = new SettlementFiles.Line(header[4], role, purchases, refunds, interchange, scheme, net, Integer.parseInt(lines[2].split(",")[1]));
            LocalDate business = LocalDate.parse(header[2]);
            LocalDate value = LocalDate.parse(header[3]);
            assertThat(text).isEqualTo(SettlementFiles.csv(header[1], business, value, line));

            Path xmlPath = csv.resolveSibling(csv.getFileName().toString().replace(".csv", ".xml"));
            String xml = Files.readString(xmlPath);
            assertThat(xml).isEqualTo(SettlementFiles.xml(header[1], business, value, line, Instant.parse("2026-10-06T21:00:00Z")));
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element closing = (Element) document.getElementsByTagName("Bal").item(1);
            BigDecimal closingAmount = new BigDecimal(closing.getElementsByTagName("Amt").item(0).getTextContent());
            assertThat(closingAmount).as(xmlPath.getFileName() + " closing balance").isEqualByComparingTo(net.abs());
        }
        assertThat(total).as("the three statements add up to zero").isEqualByComparingTo("0");
        assertThat(files("settlement", ".csv")).hasSize(3);
    }
}
