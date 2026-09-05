package io.privatekb.ingestion.internal.domain;

public final class OcrRequiredException extends RuntimeException {

    public OcrRequiredException() {
        super("PDF OCR is required", null, false, false);
    }
}
