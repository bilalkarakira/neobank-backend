import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Inspector;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.iso8583.PinBlock;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * A one-shot terminal for poking the issuer by hand, until the virtual POS of a later phase exists. It signs on, sends ONE
 * authorization (0100) or, with --reverse, the authorization and then its reversal (0400), and prints both messages
 * decoded (card number masked, PIN hidden).
 *
 * <pre>
 * java -cp iso8583/target/iso8583-0.1.0-SNAPSHOT.jar tools/SmokeClient.java \
 *   --pan 9999000000000012 --expiry 2910 --amount 12.50 [--pin 4321] [--merchant "Corner Cafe"] [--mcc 5814] \
 *   [--stan 000123] [--port 8583] [--reverse] [--resend]
 * </pre>
 * --resend sends the same authorization twice (a duplicate). --expiry is YYMM (the card shows MM/YY).
 */
public class SmokeClient {

    public static void main(String[] args) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
                o.put(args[i].substring(2), flag ? "true" : args[++i]);
            }
        }
        String pan = require(o, "pan");
        String expiry = require(o, "expiry");
        BigDecimal amount = new BigDecimal(require(o, "amount"));
        String stan = o.getOrDefault("stan", "%06d".formatted(System.currentTimeMillis() % 900000 + 100000));
        String rrn = "%012d".formatted(Long.parseLong(stan));
        String merchant = o.getOrDefault("merchant", "Smoke Test Cafe");
        Packager packager = new Packager();

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(o.getOrDefault("port", "8583")))) {
            socket.setSoTimeout(10_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            Message signOn = Message.of("0800").set(7, "1005120000").set(11, "000001").set(70, "001");
            System.out.println(">>> sign-on\n" + Inspector.format(exchange(packager, in, out, signOn, false)));

            Message request = Message.of("0100").set(2, pan).set(3, "000000")
                    .set(4, "%012d".formatted(amount.movePointRight(2).longValueExact())).set(7, "1005120000").set(11, stan)
                    .set(12, "120000").set(13, "1005").set(14, expiry).set(18, o.getOrDefault("mcc", "5814")).set(22, "051")
                    .set(25, "00").set(32, "123456").set(37, rrn).set(41, "TERM0001").set(42, "MERCHANT0000001")
                    .set(43, "%-40s".formatted(merchant)).set(49, "840");
            if (o.containsKey("pin")) {
                request.set(52, PinBlock.encode(o.get("pin"), pan));
            }
            System.out.println(">>> authorization request\n" + Inspector.format(request));
            Message response = exchange(packager, in, out, request, true);
            System.out.println("<<< authorization response\n" + Inspector.format(response));

            if (o.containsKey("resend")) {
                System.out.println(">>> the same request again (a duplicate)");
                System.out.println("<<< " + Inspector.format(exchange(packager, in, out, request, true)));
            }
            if (o.containsKey("reverse")) {
                String de90 = "0100" + stan + "1005120000" + "00000123456" + "00000000000";
                Message reversal = Message.of("0400").set(2, pan).set(3, "000000").set(4, request.get(4)).set(7, "1005120500")
                        .set(11, "%06d".formatted(Integer.parseInt(stan) + 1)).set(12, "120500").set(13, "1005").set(37, rrn)
                        .set(41, "TERM0001").set(42, "MERCHANT0000001").set(49, "840").set(90, de90);
                System.out.println(">>> reversal request\n" + Inspector.format(reversal));
                System.out.println("<<< reversal response\n" + Inspector.format(exchange(packager, in, out, reversal, true)));
            }
            System.out.println("response code: " + response.get(39));
        }
    }

    private static Message exchange(Packager packager, InputStream in, OutputStream out, Message request, boolean quiet) throws Exception {
        Frame.write(out, packager.pack(request));
        byte[] frame = Frame.read(in);
        if (frame == null) {
            throw new IllegalStateException("the issuer closed the connection");
        }
        return packager.unpack(frame);
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return value;
    }
}
