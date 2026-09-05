package io.privatekb.retrieval;

final class AnswerUnavailableException extends RuntimeException {

    private final String code;

    AnswerUnavailableException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    String code() {
        return code;
    }
}
