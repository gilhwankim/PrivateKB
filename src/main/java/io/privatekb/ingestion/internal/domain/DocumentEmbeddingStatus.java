package io.privatekb.ingestion.internal.domain;

public enum DocumentEmbeddingStatus {
    PROCESSING,
    WAITING_FOR_MODEL,
    REINDEX_REQUIRED,
    COMPLETED,
    FAILED
}
