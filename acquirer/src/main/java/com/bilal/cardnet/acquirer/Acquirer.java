package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.shared.PanVault;
import com.bilal.cardnet.shared.PendingReversalStore;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Clock;

/** The running acquirer: the payment logic, its reversal worker and the POS console, wired together. */
public final class Acquirer implements AutoCloseable {

    private final AcquirerService service;
    private final AcquirerClearing clearing;
    private final com.bilal.cardnet.shared.PeriodicTask clearingTask;
    private final AcquirerDisputes disputes;
    private final com.bilal.cardnet.shared.PeriodicTask disputeTask;
    private final PosConsole console;

    private Acquirer(AcquirerService service, AcquirerClearing clearing, AcquirerDisputes disputes, PosConsole console) {
        this.service = service;
        this.clearing = clearing;
        this.disputes = disputes;
        this.disputeTask = new com.bilal.cardnet.shared.PeriodicTask("acquirer-disputes", disputes::tick);
        this.clearingTask = new com.bilal.cardnet.shared.PeriodicTask("acquirer-clearing", clearing::processAcknowledgements);
        this.console = console;
    }

    /** Starts everything. The database must already be migrated. */
    public static Acquirer start(AcquirerConfig config, DataSource ds, PanVault vault, Clock clock) throws IOException {
        AcquirerStore store = new AcquirerStore(ds, vault);
        AcquirerService service = new AcquirerService(config, ds, store, new PendingReversalStore(ds, vault),
                new NetworkClient(config.networkHost, config.networkPort, clock), clock);
        AcquirerClearing clearing = new AcquirerClearing(config, ds, store, clock);
        clearing.repairUnwrittenBatches();
        AcquirerDisputes disputes = new AcquirerDisputes(config, ds, store, clock);
        PosConsole console = new PosConsole(config, service, clearing, disputes);
        service.start();
        console.start();
        Acquirer acquirer = new Acquirer(service, clearing, disputes, console);
        if (clearing.enabled()) {
            acquirer.clearingTask.start(config.clearingPoll);
            acquirer.disputeTask.start(config.clearingPoll);
        }
        return acquirer;
    }

    public AcquirerService service() {
        return service;
    }

    public AcquirerClearing clearing() {
        return clearing;
    }

    public AcquirerDisputes disputes() {
        return disputes;
    }

    /** The address the POS console is bound to. */
    public java.net.InetAddress consoleBoundAddress() {
        return console.boundAddress();
    }

    public int consolePort() {
        return console.port();
    }

    @Override
    public void close() {
        clearingTask.stop();
        disputeTask.stop();
        console.stop();
        service.stop();
    }
}
