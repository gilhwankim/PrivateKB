package io.privatekb.ingestion.internal.application.view;

import io.privatekb.ingestion.internal.domain.DocumentEmbeddingStatus;
import io.privatekb.ingestion.internal.domain.IndexingStatus;
import io.privatekb.ingestion.internal.domain.IngestionStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentStatusItem(
        UUID documentId,
        UUID documentVersionId,
        int versionNumber,
        String originalFilename,
        String detectedMediaType,
        long byteSize,
        DocumentEmbeddingStatus status,
        IngestionStatus ingestionStatus,
        IndexingStatus indexingStatus,
        String errorCode,
        Instant createdAt,
        Instant updatedAt
) {
}
