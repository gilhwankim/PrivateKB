package io.privatekb.ingestion.internal.application.port;

import io.privatekb.ingestion.internal.domain.IngestionErrorCode;
import io.privatekb.ingestion.internal.application.view.IngestionView;

import io.privatekb.knowledge.DocumentCatalog.DocumentVersionRef;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface IngestionJobStore {

    void create(UUID ingestionJobId, DocumentVersionRef documentVersion);

    Optional<IngestionView> find(UUID ingestionJobId);

    Optional<UUID> findJobIdByVersion(UUID documentVersionId);

    Optional<IngestionWorkItem> startAttempt(UUID ingestionJobId, int maximumAttempts);

    boolean queueOcr(UUID ingestionJobId);

    Optional<IngestionWorkItem> startOcr(UUID ingestionJobId);

    List<UUID> recoverOcrJobs(int limit);

    void complete(UUID ingestionJobId, ExtractedContentMetadata extracted);

    void fail(UUID ingestionJobId, IngestionErrorCode errorCode);

    record IngestionWorkItem(
            UUID ingestionJobId,
            UUID workspaceId,
            UUID documentVersionId,
            String originalFilename,
            String detectedMediaType,
            String sourceStorageKey,
            int attemptCount
    ) {
    }

    record ExtractedContentMetadata(
            UUID workspaceId,
            UUID documentVersionId,
            String storageKey,
            String mediaType,
            int characterCount,
            Integer pageCount,
            String parserName
    ) {
    }
}
