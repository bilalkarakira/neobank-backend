package com.bilal.neobank.card.clearing;

import com.bilal.neobank.card.dispute.DisputeCloser;
import com.bilal.neobank.card.dispute.DisputeFileWriter;
import com.bilal.neobank.card.dispute.DisputeIngest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Looks for new clearing files from the card network every few seconds (only when {@code app.cards.clearing.enabled}). */
@Component
@ConditionalOnProperty(name = "app.cards.clearing.enabled", havingValue = "true")
public class ClearingScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClearingScheduler.class);

    private final ClearingIngestService service;
    private final SettlementIngestService settlement;
    private final DisputeFileWriter disputeWriter;
    private final DisputeIngest disputeIngest;
    private final DisputeCloser disputeCloser;

    public ClearingScheduler(ClearingIngestService service, SettlementIngestService settlement, DisputeFileWriter disputeWriter,
            DisputeIngest disputeIngest, DisputeCloser disputeCloser) {
        this.service = service;
        this.settlement = settlement;
        this.disputeWriter = disputeWriter;
        this.disputeIngest = disputeIngest;
        this.disputeCloser = disputeCloser;
    }

    @Scheduled(fixedDelayString = "${app.cards.clearing.poll:5s}", initialDelayString = "${app.cards.clearing.poll:5s}")
    public void ingest() {
        try {
            ClearingIngestService.Summary summary = service.ingest();
            if (summary.filesProcessed() + summary.filesRefused() + summary.acknowledgementsWritten() > 0) {
                log.info("Clearing: {} file(s) processed, {} refused, {} item(s) posted, {} acknowledgement(s) written",
                        summary.filesProcessed(), summary.filesRefused(), summary.itemsPosted(), summary.acknowledgementsWritten());
            }
        } catch (RuntimeException e) {
            log.error("Clearing pass failed ({}); trying again at the next beat", e.getClass().getSimpleName());
        }
        try {
            SettlementIngestService.Summary summary = settlement.ingest();
            if (summary.matched() + summary.disputed() + summary.refused() > 0) {
                log.info("Settlement: {} statement(s) matched, {} disputed, {} refused", summary.matched(), summary.disputed(), summary.refused());
            }
        } catch (RuntimeException e) {
            log.error("Settlement pass failed ({}); trying again at the next beat", e.getClass().getSimpleName());
        }
        try {
            DisputeFileWriter.Summary sent = disputeWriter.run();
            if (sent.filesCreated() + sent.filesWritten() + sent.filesAcknowledged() > 0) {
                log.info("Disputes: {} file(s) made, {} written, {} acknowledged", sent.filesCreated(), sent.filesWritten(), sent.filesAcknowledged());
            }
            DisputeIngest.Summary answers = disputeIngest.ingest();
            if (answers.filesProcessed() + answers.filesRefused() + answers.acknowledgementsWritten() > 0) {
                log.info("Representments: {} file(s) processed, {} refused, {} item(s) posted", answers.filesProcessed(), answers.filesRefused(), answers.itemsPosted());
            }
            int won = disputeCloser.closeDue();
            if (won > 0) {
                log.info("Disputes: {} closed as won", won);
            }
        } catch (RuntimeException e) {
            log.error("Dispute pass failed ({}); trying again at the next beat", e.getClass().getSimpleName());
        }
    }
}
