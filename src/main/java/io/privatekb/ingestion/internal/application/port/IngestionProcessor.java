package io.privatekb.ingestion.internal.application.port;

import java.util.UUID;

public interface IngestionProcessor {

    void process(UUID ingestionJobId);
}
