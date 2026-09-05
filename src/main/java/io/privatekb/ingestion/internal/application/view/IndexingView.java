package io.privatekb.ingestion.internal.application.view;

import io.privatekb.ingestion.internal.domain.IndexingErrorCode;
import io.privatekb.ingestion.internal.domain.IndexingStatus;

import java.time.Instant;
import java.util.UUID;

public record IndexingView(
        UUID indexingJobId,
        UUID workspaceId,
        UUID documentId,
        UUID documentVersionId,
        String originalFilename,
        IndexingStatus status,
        int attemptCount,
        IndexingErrorCode errorCode,
        int chunkCount,
        String embeddingModel,
        String embeddingDigest,
        Integer embeddingDimensions,
        Instant createdAt,
        Instant updatedAt
) {
}
