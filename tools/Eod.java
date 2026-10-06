import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End of day for the demo: closes the day on the POS console (capture), waits for the network to accept the clearing file,
 * then runs the settlement on the network and prints the net movement per participant. It never talks to NeoBank: NeoBank
 * picks up its clearing file and its settlement statement by its own poll (or the admin button), as in a real day.
 *
 * <pre>
 *   java tools/Eod.java [--pos http://127.0.0.1:9000] [--net http://127.0.0.1:9100] [--date 2026-10-06]
 * </pre>
 * Defaults come from POS_URL and NET_URL when set. Without --date the oldest unsettled business date is settled first, and
 * the run repeats until nothing is left.
 */
public class Eod {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public static void main(String[] args) throws Exception {
        String pos = env("POS_URL", "http://127.0.0.1:9000");
        String net = env("NET_URL", "http://127.0.0.1:9100");
        String date = null;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--pos" -> pos = args[i + 1];
                case "--net" -> net = args[i + 1];
                case "--date" -> date = args[i + 1];
                default -> { }
            }
        }

        System.out.println("1. Closing the day on the POS console (" + pos + ")");
        String close = send("POST", pos + "/api/close-day", "the POS console");
        System.out.println("   " + field(close, "message"));
        String fileName = field(close, "fileName");
        if (fileName != null) {
            System.out.println("2. Waiting for the network to answer for " + fileName);
            String status = "SUBMITTED";
            for (int i = 0; i < 90 && (status.equals("SUBMITTED") || status.equals("CREATED")); i++) {
                String batches = send("GET", pos + "/api/batches", "the POS console");
                Matcher m = Pattern.compile("\"fileName\":\"" + Pattern.quote(fileName) + "\"[^}]*?\"status\":\"([A-Z]+)\"").matcher(batches);
                status = m.find() ? m.group(1) : "UNKNOWN";
                if (status.equals("SUBMITTED") || status.equals("CREATED")) {
                    Thread.sleep(1000);
                }
            }
            System.out.println("   the network " + switch (status) {
                case "ACCEPTED" -> "accepted the file";
                case "REJECTED" -> "REFUSED the file (see the POS console for why); nothing was cleared";
                default -> "has not answered yet (" + status + "): settlement runs on what is cleared so far";
            });
        } else {
            System.out.println("2. Nothing was sent, so there is nothing to wait for.");
        }

        System.out.println("3. Settling on the network (" + net + ")");
        int runs = 0;
        for (int i = 0; i < 10; i++) {
            String run = send("POST", net + "/settlement/run" + (date == null ? "" : "?date=" + date), "the network control API");
            String message = field(run, "message");
            System.out.println("   " + message);
            if (!"true".equals(field(run, "settled"))) {
                break;
            }
            runs++;
            Matcher lines = Pattern.compile("\"participant\":\"([^\"]+)\",\"role\":\"([A-Z]+)\",\"purchases\":([-0-9.]+),\"refunds\":([-0-9.]+),\"interchange\":([-0-9.]+),\"schemeFee\":([-0-9.]+),\"net\":([-0-9.]+)").matcher(run);
            while (lines.find()) {
                System.out.printf("   %-9s %-8s purchases %10s  refunds %9s  interchange %8s  scheme fee %7s  NET %10s%n", lines.group(2), lines.group(1),
                        lines.group(3), lines.group(4), lines.group(5), lines.group(6), lines.group(7));
            }
            if (date != null) {
                break;
            }
        }
        System.out.println(runs == 0 ? "Done: nothing new to settle." : "Done: " + runs + " settlement run(s). The statements are in the settlement-out folder of the clearing directory.");
    }

    private static String send(String method, String url, String what) throws Exception {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
            HttpResponse<String> response = HTTP.send(method.equals("GET") ? request.GET().build() : request.POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("ERROR: " + what + " answered " + response.statusCode() + ": " + response.body());
                System.exit(1);
            }
            return response.body();
        } catch (java.io.IOException e) {
            System.err.println("ERROR: " + what + " is not reachable at " + url + " (" + e.getClass().getSimpleName() + "). Is it running?");
            System.exit(1);
            return null;
        }
    }

    /** A string or boolean value of a JSON key (first occurrence), or null. A tiny reader: good enough for these flat answers. */
    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":(\"([^\"]*)\"|true|false|null|[-0-9.]+)").matcher(json);
        if (!m.find()) {
            return null;
        }
        return m.group(2) != null ? m.group(2) : m.group(1).equals("null") ? null : m.group(1);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
