package io.privatekb.ingestion.internal.application.port;

import io.privatekb.ingestion.internal.domain.IndexingErrorCode;
import io.privatekb.ingestion.internal.application.view.IndexingView;

import io.privatekb.platform.LocalEmbeddingClient.EmbeddingModelInfo;

import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.util.UUID;

public interface IndexingJobStore {

    void ensureJob(UUID workspaceId, UUID documentVersionId);

    Optional<IndexingView> findByVersion(UUID documentVersionId);

    List<UUID> findResumableVersions();

    void markStaleIndexes(EmbeddingModelInfo model);

    Optional<IndexingWorkItem> start(UUID documentVersionId, int maximumAttempts);

    void markModelWaiting(UUID indexingJobId, IndexingErrorCode errorCode);

    IndexingCheckpoint prepare(
            IndexingWorkItem work,
            EmbeddingModelInfo model,
            String extractedSha256,
            String chunkingVersion,
            int chunkSize,
            int chunkOverlap
    );

    void appendBatch(
            IndexingWorkItem work,
            EmbeddingModelInfo model,
            List<IndexedChunk> chunks,
            int maximumRetries
    );

    void complete(
            IndexingWorkItem work,
            EmbeddingModelInfo model,
            int totalChunkCount
    );

    void fail(UUID indexingJobId, IndexingErrorCode errorCode);

    int recoverInterruptedJobs();

    int purgeExpiredStaging(Duration retention);

    List<SourceCopyCleanup> findCompletedSourceCopiesPendingCleanup(int limit);

    void markSourceCopyDeleted(UUID documentVersionId);

    record IndexingWorkItem(
            UUID indexingJobId,
            UUID workspaceId,
            UUID documentId,
            UUID documentVersionId,
            String originalFilename,
            String sourceStorageKey,
            String extractedStorageKey
    ) {
    }

    record SourceCopyCleanup(
            UUID documentVersionId,
            String sourceStorageKey
    ) {
    }

    record IndexedChunk(
            UUID chunkId,
            int index,
            int startOffset,
            int endOffset,
            String content,
            float[] embedding
    ) {
    }

    record IndexingCheckpoint(int nextChunkIndex) {
    }
}
