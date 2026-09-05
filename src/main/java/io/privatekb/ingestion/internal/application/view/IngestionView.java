package io.privatekb.ingestion.internal.application.view;

import io.privatekb.ingestion.internal.domain.IngestionErrorCode;
import io.privatekb.ingestion.internal.domain.IngestionStatus;

import java.time.Instant;
import java.util.UUID;

public record IngestionView(
        UUID ingestionJobId,
        UUID workspaceId,
        UUID documentId,
        UUID documentVersionId,
        String originalFilename,
        IngestionStatus status,
        int attemptCount,
        IngestionErrorCode errorCode,
        Instant createdAt,
        Instant updatedAt
) {
}
