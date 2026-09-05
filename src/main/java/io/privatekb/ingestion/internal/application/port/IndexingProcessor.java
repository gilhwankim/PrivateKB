package io.privatekb.ingestion.internal.application.port;

import java.util.UUID;

public interface IndexingProcessor {

    void process(UUID documentVersionId);
}
