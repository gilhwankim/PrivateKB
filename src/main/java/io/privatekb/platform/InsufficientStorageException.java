package io.privatekb.platform;

public final class InsufficientStorageException extends RuntimeException {

    public InsufficientStorageException(String message) {
        super(message);
    }

    public InsufficientStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
