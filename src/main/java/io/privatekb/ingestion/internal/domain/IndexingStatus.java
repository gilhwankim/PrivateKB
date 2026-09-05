package io.privatekb.ingestion.internal.domain;

public enum IndexingStatus {
    PENDING,
    MODEL_WAITING,
    REINDEX_REQUIRED,
    INDEXING,
    PAUSED,
    INDEXED,
    FAILED
}
