package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.shared.Json;
import com.bilal.cardnet.shared.PanVault;
import com.bilal.cardnet.shared.PendingReversalStore;
import com.bilal.cardnet.shared.RetrySchedule;
import com.bilal.cardnet.shared.Sequences;
import com.bilal.cardnet.shared.ReversalWorker;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/** The running network: everything wired together. Start it, ask it for its ports, close it. */
public final class Network implements AutoCloseable {

    private final NetworkConfig config;
    private final Chaos chaos = new Chaos();
    private final IssuerLink link;
    private final AdviceStore advices;
    private final PendingReversalStore reversals;
    private final PaymentRouter router;
    private final AdviceDelivery delivery;
    private final ReversalWorker reversalWorker;
    private final NetworkServer server;
    private final NetworkControl control;
    private final ClearingProcessor clearing;
    private final SettlementService settlement;
    private final com.bilal.cardnet.shared.PeriodicTask clearingTask;

    private Network(NetworkConfig config, DataSource ds, PanVault vault, Clock clock) {
        this.config = config;
        BinTable bins = new BinTable(config.routes);
        // one issuer in this phase: its route is the first one (more issuers would each get their own link)
        this.link = new IssuerLink(config.routes.get(0), config, chaos, clock, () -> Sequences.nextStan(ds));
        this.advices = new AdviceStore(ds, vault);
        this.reversals = new PendingReversalStore(ds, vault);
        this.reversalWorker = new ReversalWorker("NETWORK", reversals, this::sendReversal, () -> Sequences.nextStan(ds), clock,
                new RetrySchedule(config.retryScale), id -> { }, (id, why) -> { });
        this.router = new PaymentRouter(config, bins, link, advices, reversals, new NetTransactionLog(ds, clock), vault, clock,
                reversalWorker::wake);
        this.delivery = new AdviceDelivery(advices, link, config.issuerTimeout, config.adviceInterval, clock);
        this.server = new NetworkServer(config, router, chaos);
        this.clearing = config.clearingDir == null ? null : new ClearingProcessor(config, ds, vault, clock);
        this.settlement = config.clearingDir == null ? null : new SettlementService(config, ds, clock);
        this.control = new NetworkControl(config.controlPort, this::status, chaos, settlement);
        this.clearingTask = clearing == null ? null : new com.bilal.cardnet.shared.PeriodicTask("network-clearing", () -> {
            clearing.tick();
            settlement.writePending();
        });
    }

    /** Starts every part. The database must already be migrated. */
    public static Network start(NetworkConfig config, DataSource ds, PanVault vault, Clock clock) throws IOException {
        Network network = new Network(config, ds, vault, clock);
        network.advices.recoverSending();
        network.server.start();
        network.control.start();
        network.link.start();
        network.delivery.start();
        network.reversalWorker.start(config.reversalPoll);
        if (network.clearingTask != null) {
            network.clearingTask.start(config.clearingPoll);
        }
        return network;
    }

    private Optional<Message> sendReversal(Message reversal) {
        try {
            return Optional.of(link.request(reversal, config.issuerTimeout));
        } catch (TimeoutException | IssuerLink.UnavailableException e) {
            return Optional.empty();
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> map = Json.object();
        map.put("issuerLink", link.state().name());
        map.put("inFlight", link.inFlight());
        map.put("advicesWaiting", advices.countPending());
        map.put("reversalsWaiting", reversals.countPending("NETWORK"));
        map.put("counters", router.counters());
        if (clearing != null) {
            map.put("clearingFilesWaiting", clearing.waiting());
            map.put("unsettledItems", settlement.unsettledItems());
        }
        map.put("chaos", chaos.toMap());
        return map;
    }

    public int acquirerPort() {
        return server.port();
    }

    public int controlPort() {
        return control.port();
    }

    public IssuerLink link() {
        return link;
    }

    public Chaos chaos() {
        return chaos;
    }

    public AdviceStore advices() {
        return advices;
    }

    public PaymentRouter router() {
        return router;
    }

    public AdviceDelivery delivery() {
        return delivery;
    }

    /** The clearing processor, or null when clearing is switched off (no clearing folder configured). */
    public ClearingProcessor clearing() {
        return clearing;
    }

    /** The settlement service, or null when clearing is switched off. */
    public SettlementService settlement() {
        return settlement;
    }

    public NetworkServer server() {
        return server;
    }

    @Override
    public void close() {
        if (clearingTask != null) {
            clearingTask.stop();
        }
        delivery.stop();
        reversalWorker.stop();
        link.stop();
        control.stop();
        server.stop();
    }
}
