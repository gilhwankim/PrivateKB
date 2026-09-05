package io.privatekb.platform;

public final class ContentStorageException extends RuntimeException {

    public ContentStorageException(String message) {
        super(message);
    }

    public ContentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
