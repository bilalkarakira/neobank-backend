package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.shared.PanVault;
import com.bilal.cardnet.shared.PendingReversalStore;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Clock;

/** The running acquirer: the payment logic, its reversal worker and the POS console, wired together. */
public final class Acquirer implements AutoCloseable {

    private final AcquirerService service;
    private final PosConsole console;

    private Acquirer(AcquirerService service, PosConsole console) {
        this.service = service;
        this.console = console;
    }

    /** Starts everything. The database must already be migrated. */
    public static Acquirer start(AcquirerConfig config, DataSource ds, PanVault vault, Clock clock) throws IOException {
        AcquirerService service = new AcquirerService(config, ds, new AcquirerStore(ds, vault), new PendingReversalStore(ds, vault),
                new NetworkClient(config.networkHost, config.networkPort, clock), clock);
        PosConsole console = new PosConsole(config, service);
        service.start();
        console.start();
        return new Acquirer(service, console);
    }

    public AcquirerService service() {
        return service;
    }

    public int consolePort() {
        return console.port();
    }

    @Override
    public void close() {
        console.stop();
        service.stop();
    }
}
