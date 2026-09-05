package io.privatekb.platform;

public final class LocalEmbeddingException extends RuntimeException {

    private final Reason reason;

    public LocalEmbeddingException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public LocalEmbeddingException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        OLLAMA_NOT_RUNNING,
        MODEL_NOT_AVAILABLE,
        MODEL_INCOMPATIBLE,
        REQUEST_FAILED
    }
}
