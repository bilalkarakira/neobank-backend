import com.bilal.cardnet.shared.testing.FakeIssuerClearing;

import java.nio.file.Path;

/**
 * A stand-in issuer for clearing files, for trying the acquirer and the network without NeoBank: it answers every file in
 * issuer-in/ with POSTED (purchases) or REFUND_POSTED (refunds). Not a real issuer: it posts nothing anywhere.
 *
 * <pre>
 *   java -cp "shared/target/shared-0.1.0-SNAPSHOT.jar;iso8583/target/iso8583-0.1.0-SNAPSHOT.jar" tools/ClearingStub.java DATA_DIR
 * </pre>
 * The key is CARDNET_FILE_KEY (the development default when it is not set), like everywhere else.
 */
public class ClearingStub {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: ClearingStub DATA_DIR");
            System.exit(2);
        }
        String secret = System.getenv("CARDNET_FILE_KEY");
        FakeIssuerClearing stub = new FakeIssuerClearing(Path.of(args[0]),
                secret == null || secret.isBlank() ? com.bilal.cardnet.iso8583.clearing.ClearingFileCodec.DEFAULT_SECRET : secret);
        System.out.println("Clearing stub issuer watching " + args[0]);
        while (true) {
            int answered = stub.tick();
            if (answered > 0) {
                System.out.println("answered " + answered + " file(s)");
            }
            Thread.sleep(1000);
        }
    }
}
