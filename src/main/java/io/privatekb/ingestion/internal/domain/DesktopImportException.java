package io.privatekb.ingestion.internal.domain;

public final class DesktopImportException extends RuntimeException {

    private final DesktopImportErrorCode code;

    public DesktopImportException(DesktopImportErrorCode code) {
        super(code.name());
        this.code = code;
    }

    public DesktopImportErrorCode code() {
        return code;
    }
}
