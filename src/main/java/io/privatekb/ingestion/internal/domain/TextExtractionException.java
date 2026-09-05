package io.privatekb.ingestion.internal.domain;

public final class TextExtractionException extends RuntimeException {

    private final IngestionErrorCode errorCode;

    public TextExtractionException(IngestionErrorCode errorCode, Throwable cause) {
        super(errorCode.name(), cause);
        this.errorCode = errorCode;
    }

    public IngestionErrorCode errorCode() {
        return errorCode;
    }
}
