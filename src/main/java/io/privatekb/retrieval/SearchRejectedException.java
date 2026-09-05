package io.privatekb.retrieval;

final class SearchRejectedException extends RuntimeException {

    private final String code;

    SearchRejectedException(String code) {
        super(code);
        this.code = code;
    }

    String code() {
        return code;
    }
}
