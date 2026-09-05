package io.privatekb.ingestion.internal.domain;

public enum IngestionStatus {
    RECEIVED,
    STORED,
    PARSING,
    OCR_PENDING,
    OCR_RUNNING,
    PARSED,
    FAILED
}
