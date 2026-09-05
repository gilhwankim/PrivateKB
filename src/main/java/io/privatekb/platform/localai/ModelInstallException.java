package io.privatekb.platform.localai;

final class ModelInstallException extends RuntimeException {

    private final String code;

    ModelInstallException(String code) {
        super(code);
        this.code = code;
    }

    String code() {
        return code;
    }
}
