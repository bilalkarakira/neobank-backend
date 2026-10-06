package com.bilal.neobank.card.clearing;

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

    public ClearingScheduler(ClearingIngestService service) {
        this.service = service;
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
    }
}
