package io.privatekb.ingestion.internal.application.view;

import io.privatekb.ingestion.internal.domain.IngestionStatus;

import java.util.UUID;

public record UploadDocumentResult(
        UUID workspaceId,
        UUID documentId,
        UUID documentVersionId,
        UUID ingestionJobId,
        IngestionStatus status,
        boolean duplicate
) {
}
