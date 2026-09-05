package io.privatekb.ingestion.internal.parsing;

import io.privatekb.ingestion.internal.application.port.OcrProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class OcrStartupListener {

    private static final Logger log = LoggerFactory.getLogger(OcrStartupListener.class);

    private final OcrProcessor processor;

    OcrStartupListener(OcrProcessor processor) {
        this.processor = processor;
    }

    @EventListener(ApplicationReadyEvent.class)
    void resumeOcrJobs() {
        int scheduled = processor.resumeAvailable();
        if (scheduled > 0) {
            log.info("중단된 PDF OCR 작업 재개: scheduledJobs={}", scheduled);
        }
    }
}
