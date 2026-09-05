package io.privatekb.retrieval;

final class AnswerRejectedException extends RuntimeException {

    private final String code;

    AnswerRejectedException(String code) {
        super(code);
        this.code = code;
    }

    String code() {
        return code;
    }
}
