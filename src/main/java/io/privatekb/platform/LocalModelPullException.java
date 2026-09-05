package io.privatekb.platform;

public final class LocalModelPullException extends RuntimeException {

    private final Reason reason;

    public LocalModelPullException(Reason reason) {
        this(reason, null);
    }

    public LocalModelPullException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        OLLAMA_NOT_RUNNING,
        REQUEST_FAILED,
        STREAM_INVALID
    }
}
