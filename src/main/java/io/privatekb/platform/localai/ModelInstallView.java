package io.privatekb.platform.localai;

import java.time.Instant;
import java.util.UUID;

record ModelInstallView(
        UUID jobId,
        LocalAiModelRole role,
        String model,
        ModelInstallStatus status,
        int progressPercent,
        long completedBytes,
        long totalBytes,
        String errorCode,
        Instant createdAt,
        Instant updatedAt
) {
}
