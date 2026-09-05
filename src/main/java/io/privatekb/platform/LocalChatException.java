package io.privatekb.platform;

public final class LocalChatException extends RuntimeException {

    private final Reason reason;

    public LocalChatException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public LocalChatException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        OLLAMA_NOT_RUNNING,
        MODEL_NOT_AVAILABLE,
        REQUEST_FAILED,
        STREAM_INVALID
    }
}
