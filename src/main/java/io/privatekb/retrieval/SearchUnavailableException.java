package io.privatekb.retrieval;

final class SearchUnavailableException extends RuntimeException {

    private final String code;

    SearchUnavailableException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    String code() {
        return code;
    }
}
