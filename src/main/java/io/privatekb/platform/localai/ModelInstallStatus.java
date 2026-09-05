package io.privatekb.platform.localai;

enum ModelInstallStatus {
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    CANCEL_REQUESTED,
    CANCELLED,
    COMPLETED,
    FAILED;

    boolean terminal() {
        return this == CANCELLED || this == COMPLETED || this == FAILED;
    }
}
