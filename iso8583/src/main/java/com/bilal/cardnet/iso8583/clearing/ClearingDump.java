package com.bilal.cardnet.iso8583.clearing;

import com.bilal.cardnet.iso8583.Inspector;
import com.bilal.cardnet.iso8583.Message;

import java.nio.file.Path;

/**
 * Prints a clearing file with every card number masked.
 *
 * <pre>
 *   java -cp iso8583.jar com.bilal.cardnet.iso8583.clearing.ClearingDump FILE
 * </pre>
 * The key is the text in the environment variable CARDNET_FILE_KEY (the development default when it is not set).
 * The file NAME matters: it is part of what is checked, so do not rename a file before dumping it.
 */
public final class ClearingDump {

    private ClearingDump() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: ClearingDump FILE   (key: environment variable CARDNET_FILE_KEY)");
            System.exit(2);
        }
        String secret = System.getenv("CARDNET_FILE_KEY");
        ClearingFileCodec codec = ClearingFileCodec.withSecret(secret == null || secret.isBlank()
                ? ClearingFileCodec.DEFAULT_SECRET : secret);
        try {
            System.out.print(describe(codec.read(Path.of(args[0]))));
        } catch (ClearingFileException e) {
            System.err.println("REFUSED (" + e.reason() + "): " + e.getMessage());
            System.exit(1);
        }
    }

    /** The printout, PAN masked. */
    public static String describe(ClearingFile file) {
        StringBuilder out = new StringBuilder();
        out.append("File ").append(file.fileId()).append("  business date ").append(file.businessDate())
                .append("  ").append(file.sender()).append(" -> ").append(file.receiver()).append("  ").append(file.currency())
                .append('\n');
        out.append(file.presentments().size()).append(" presentment(s), control total ").append(file.controlTotal())
                .append(" minor units\n");
        int number = 1;
        for (Message m : file.presentments()) {
            out.append("\n#").append(number++).append('\n').append(Inspector.format(m));
        }
        return out.toString();
    }
}
