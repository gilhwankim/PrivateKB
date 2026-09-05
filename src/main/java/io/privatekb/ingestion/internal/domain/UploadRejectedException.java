package io.privatekb.ingestion.internal.domain;

public final class UploadRejectedException extends RuntimeException {

    private final UploadRejectionCode code;

    public UploadRejectedException(UploadRejectionCode code) {
        super(code.name());
        this.code = code;
    }

    public UploadRejectionCode code() {
        return code;
    }
}
