package io.privatekb.ingestion.internal.application.port;

import java.util.UUID;

public interface OcrProcessor {

    void process(UUID ingestionJobId);

    int resumeAvailable();
}
