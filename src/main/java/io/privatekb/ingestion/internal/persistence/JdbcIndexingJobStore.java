package io.privatekb.ingestion.internal.persistence;

import io.privatekb.ingestion.internal.domain.IndexingErrorCode;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore;
import io.privatekb.ingestion.internal.domain.IndexingStatus;
import io.privatekb.ingestion.internal.application.view.IndexingView;
import io.privatekb.platform.LocalEmbeddingClient.EmbeddingModelInfo;

import java.sql.Types;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class JdbcIndexingJobStore implements IndexingJobStore {

    private final JdbcClient jdbc;
    private final JdbcTemplate batchJdbc;
    private final TransactionTemplate transactions;

    JdbcIndexingJobStore(
            JdbcClient jdbc,
            JdbcTemplate batchJdbc,
            org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.batchJdbc = batchJdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional
    public void ensureJob(UUID workspaceId, UUID documentVersionId) {
        UUID indexingJobId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        INSERT INTO privatekb.indexing_job (
                            indexing_job_id, document_version_id, workspace_id, status
                        ) VALUES (
                            :jobId, :versionId, :workspaceId, 'PENDING'
                        )
                        ON CONFLICT (document_version_id) DO NOTHING
                        """)
                .param("jobId", indexingJobId)
                .param("versionId", documentVersionId)
                .param("workspaceId", workspaceId)
                .update();
        if (inserted == 1) {
            insertTransition(indexingJobId, null, IndexingStatus.PENDING, null);
        }
    }

    @Override
    public Optional<IndexingView> findByVersion(UUID documentVersionId) {
        return jdbc.sql("""
                        SELECT j.indexing_job_id, j.workspace_id, v.document_id,
                               j.document_version_id, v.original_filename, j.status,
                               j.attempt_count, j.error_code, j.embedding_model,
                               j.embedding_digest, j.embedding_dimensions,
                               j.created_at, j.updated_at,
                               (SELECT count(*) FROM privatekb.document_chunk c
                                 WHERE c.document_version_id = j.document_version_id) AS chunk_count
                          FROM privatekb.indexing_job j
                          JOIN privatekb.document_version v
                            ON v.document_version_id = j.document_version_id
                           AND v.workspace_id = j.workspace_id
                         WHERE j.document_version_id = :versionId
                        """)
                .param("versionId", documentVersionId)
                .query((resultSet, rowNumber) -> new IndexingView(
                        resultSet.getObject("indexing_job_id", UUID.class),
                        resultSet.getObject("workspace_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("document_version_id", UUID.class),
                        resultSet.getString("original_filename"),
                        IndexingStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("attempt_count"),
                        errorCode(resultSet.getString("error_code")),
                        resultSet.getInt("chunk_count"),
                        resultSet.getString("embedding_model"),
                        resultSet.getString("embedding_digest"),
                        nullableInteger(resultSet.getObject("embedding_dimensions")),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()
                ))
                .optional();
    }

    @Override
    public List<UUID> findResumableVersions() {
        return jdbc.sql("""
                        SELECT document_version_id
                          FROM privatekb.indexing_job
                         WHERE status IN ('PENDING', 'MODEL_WAITING', 'REINDEX_REQUIRED', 'PAUSED')
                         ORDER BY created_at
                         LIMIT 100
                        """)
                .query(UUID.class)
                .list();
    }

    @Override
    @Transactional
    public void markStaleIndexes(EmbeddingModelInfo model) {
        List<UUID> staleJobs = jdbc.sql("""
                        SELECT indexing_job_id
                          FROM privatekb.indexing_job
                         WHERE status = 'INDEXED'
                           AND (
                               embedding_model IS DISTINCT FROM :model
                               OR embedding_digest IS DISTINCT FROM :digest
                               OR embedding_dimensions IS DISTINCT FROM :dimensions
                           )
                         ORDER BY created_at
                        """)
                .param("model", model.model())
                .param("digest", model.digest())
                .param("dimensions", model.dimensions())
                .query(UUID.class)
                .list();
        for (UUID jobId : staleJobs) {
            int updated = jdbc.sql("""
                            UPDATE privatekb.indexing_job
                               SET status = 'REINDEX_REQUIRED', updated_at = now()
                             WHERE indexing_job_id = :jobId
                               AND status = 'INDEXED'
                            """)
                    .param("jobId", jobId)
                    .update();
            if (updated == 1) {
                insertTransition(
                        jobId,
                        IndexingStatus.INDEXED,
                        IndexingStatus.REINDEX_REQUIRED,
                        "EMBEDDING_MODEL_CHANGED"
                );
            }
        }
    }

    @Override
    @Transactional
    public Optional<IndexingWorkItem> start(UUID documentVersionId, int maximumAttempts) {
        Optional<Candidate> candidate = jdbc.sql("""
                        SELECT j.indexing_job_id, j.workspace_id, v.document_id,
                               j.document_version_id, v.original_filename,
                               v.source_storage_key, e.storage_key,
                               j.status, j.attempt_count
                          FROM privatekb.indexing_job j
                          JOIN privatekb.document_version v
                            ON v.document_version_id = j.document_version_id
                           AND v.workspace_id = j.workspace_id
                          JOIN privatekb.extracted_content e
                            ON e.document_version_id = j.document_version_id
                           AND e.workspace_id = j.workspace_id
                         WHERE j.document_version_id = :versionId
                        """)
                .param("versionId", documentVersionId)
                .query((resultSet, rowNumber) -> new Candidate(
                        resultSet.getObject("indexing_job_id", UUID.class),
                        resultSet.getObject("workspace_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("document_version_id", UUID.class),
                        resultSet.getString("original_filename"),
                        resultSet.getString("source_storage_key"),
                        resultSet.getString("storage_key"),
                        IndexingStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("attempt_count")
                ))
                .optional();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Candidate value = candidate.orElseThrow();
        if (!resumable(value.status())
                || (value.status() == IndexingStatus.FAILED
                && value.attemptCount() >= maximumAttempts)) {
            return Optional.empty();
        }

        int updated = jdbc.sql("""
                        UPDATE privatekb.indexing_job
                           SET status = 'INDEXING', error_code = NULL, updated_at = now()
                         WHERE indexing_job_id = :jobId
                           AND status = :expectedStatus
                        """)
                .param("jobId", value.indexingJobId())
                .param("expectedStatus", value.status().name())
                .update();
        if (updated != 1) {
            return Optional.empty();
        }
        insertTransition(value.indexingJobId(), value.status(), IndexingStatus.INDEXING, null);
        return Optional.of(new IndexingWorkItem(
                value.indexingJobId(),
                value.workspaceId(),
                value.documentId(),
                value.documentVersionId(),
                value.originalFilename(),
                value.sourceStorageKey(),
                value.extractedStorageKey()
        ));
    }

    @Override
    public List<SourceCopyCleanup> findCompletedSourceCopiesPendingCleanup(int limit) {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("Cleanup limit must be between 1 and 1000");
        }
        return jdbc.sql("""
                        SELECT v.document_version_id, v.source_storage_key
                          FROM privatekb.document_version v
                          JOIN privatekb.indexing_job j
                            ON j.document_version_id = v.document_version_id
                           AND j.workspace_id = v.workspace_id
                         WHERE j.status = 'INDEXED'
                           AND v.source_copy_deleted_at IS NULL
                         ORDER BY j.updated_at, v.document_version_id
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new SourceCopyCleanup(
                        resultSet.getObject("document_version_id", UUID.class),
                        resultSet.getString("source_storage_key")
                ))
                .list();
    }

    @Override
    public void markSourceCopyDeleted(UUID documentVersionId) {
        jdbc.sql("""
                        UPDATE privatekb.document_version
                           SET source_copy_deleted_at = COALESCE(source_copy_deleted_at, now())
                         WHERE document_version_id = :versionId
                        """)
                .param("versionId", documentVersionId)
                .update();
    }

    @Override
    @Transactional
    public void markModelWaiting(UUID indexingJobId, IndexingErrorCode errorCode) {
        int updated = jdbc.sql("""
                        UPDATE privatekb.indexing_job
                           SET status = 'MODEL_WAITING', error_code = :errorCode,
                               updated_at = now(), checkpoint_updated_at = now()
                         WHERE indexing_job_id = :jobId
                           AND status = 'INDEXING'
                        """)
                .param("jobId", indexingJobId)
                .param("errorCode", errorCode.name())
                .update();
        if (updated == 1) {
            insertTransition(
                    indexingJobId,
                    IndexingStatus.INDEXING,
                    IndexingStatus.MODEL_WAITING,
                    errorCode.name()
            );
        }
    }

    @Override
    @Transactional
    public IndexingCheckpoint prepare(
            IndexingWorkItem work,
            EmbeddingModelInfo model,
            String extractedSha256,
            String chunkingVersion,
            int chunkSize,
            int chunkOverlap
    ) {
        Progress current = jdbc.sql("""
                        SELECT status, checkpoint_chunk_index, extracted_sha256,
                               chunking_version, chunk_size, chunk_overlap,
                               embedding_model, embedding_digest, embedding_dimensions
                          FROM privatekb.indexing_job
                         WHERE indexing_job_id = :jobId
                         FOR UPDATE
                        """)
                .param("jobId", work.indexingJobId())
                .query((resultSet, rowNumber) -> new Progress(
                        IndexingStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("checkpoint_chunk_index"),
                        resultSet.getString("extracted_sha256"),
                        resultSet.getString("chunking_version"),
                        nullableInteger(resultSet.getObject("chunk_size")),
                        nullableInteger(resultSet.getObject("chunk_overlap")),
                        resultSet.getString("embedding_model"),
                        resultSet.getString("embedding_digest"),
                        nullableInteger(resultSet.getObject("embedding_dimensions"))
                ))
                .single();
        if (current.status() != IndexingStatus.INDEXING) {
            throw new IllegalStateException("Indexing job is not in INDEXING state");
        }

        StagingProgress staging = jdbc.sql("""
                        SELECT count(*) AS chunk_count,
                               min(chunk_index) AS minimum_index,
                               max(chunk_index) AS maximum_index
                          FROM privatekb.document_chunk_staging
                         WHERE indexing_job_id = :jobId
                        """)
                .param("jobId", work.indexingJobId())
                .query((resultSet, rowNumber) -> new StagingProgress(
                        resultSet.getInt("chunk_count"),
                        nullableInteger(resultSet.getObject("minimum_index")),
                        nullableInteger(resultSet.getObject("maximum_index"))
                ))
                .single();

        boolean reusable = Objects.equals(current.extractedSha256(), extractedSha256)
                && Objects.equals(current.chunkingVersion(), chunkingVersion)
                && Objects.equals(current.chunkSize(), chunkSize)
                && Objects.equals(current.chunkOverlap(), chunkOverlap)
                && Objects.equals(current.embeddingModel(), model.model())
                && Objects.equals(current.embeddingDigest(), model.digest())
                && Objects.equals(current.embeddingDimensions(), model.dimensions())
                && staging.matchesCheckpoint(current.nextChunkIndex());
        int checkpoint = reusable ? current.nextChunkIndex() : 0;
        if (!reusable) {
            jdbc.sql("""
                            DELETE FROM privatekb.document_chunk_staging
                             WHERE indexing_job_id = :jobId
                            """)
                    .param("jobId", work.indexingJobId())
                    .update();
        }
        jdbc.sql("""
                        UPDATE privatekb.indexing_job
                           SET checkpoint_chunk_index = :checkpoint,
                               checkpoint_updated_at = now(),
                               extracted_sha256 = :extractedSha256,
                               chunking_version = :chunkingVersion,
                               chunk_size = :chunkSize,
                               chunk_overlap = :chunkOverlap,
                               embedding_model = :embeddingModel,
                               embedding_digest = :embeddingDigest,
                               embedding_dimensions = :embeddingDimensions,
                               updated_at = now()
                         WHERE indexing_job_id = :jobId
                        """)
                .param("checkpoint", checkpoint)
                .param("extractedSha256", extractedSha256)
                .param("chunkingVersion", chunkingVersion)
                .param("chunkSize", chunkSize)
                .param("chunkOverlap", chunkOverlap)
                .param("embeddingModel", model.model())
                .param("embeddingDigest", model.digest())
                .param("embeddingDimensions", model.dimensions())
                .param("jobId", work.indexingJobId())
                .update();
        return new IndexingCheckpoint(checkpoint);
    }

    @Override
    public void appendBatch(
            IndexingWorkItem work,
            EmbeddingModelInfo model,
            List<IndexedChunk> chunks,
            int maximumRetries
    ) {
        if (chunks.isEmpty()) {
            return;
        }
        List<IndexedChunk> immutableChunks = List.copyOf(chunks);
        long[] retryDelays = {200L, 1_000L, 3_000L};
        int attempt = 0;
        while (true) {
            try {
                transactions.executeWithoutResult(status ->
                        appendBatchTransaction(work, model, immutableChunks));
                return;
            } catch (TransientDataAccessException exception) {
                if (attempt >= maximumRetries) {
                    throw exception;
                }
                long delay = retryDelays[Math.min(attempt, retryDelays.length - 1)];
                attempt++;
                sleepBeforeRetry(delay);
            }
        }
    }

    @Override
    @Transactional
    public void complete(
            IndexingWorkItem work,
            EmbeddingModelInfo model,
            int totalChunkCount
    ) {
        Integer checkpoint = jdbc.sql("""
                        SELECT checkpoint_chunk_index
                          FROM privatekb.indexing_job
                         WHERE indexing_job_id = :jobId
                           AND status = 'INDEXING'
                         FOR UPDATE
                        """)
                .param("jobId", work.indexingJobId())
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Indexing job is not in INDEXING state"));
        Integer staged = jdbc.sql("""
                        SELECT count(*)
                          FROM privatekb.document_chunk_staging
                         WHERE indexing_job_id = :jobId
                        """)
                .param("jobId", work.indexingJobId())
                .query(Integer.class)
                .single();
        if (checkpoint != totalChunkCount || staged != totalChunkCount) {
            throw new IllegalStateException("Indexing checkpoint does not match staged chunks");
        }

        jdbc.sql("DELETE FROM privatekb.document_chunk WHERE document_version_id = :versionId")
                .param("versionId", work.documentVersionId())
                .update();
        int inserted = jdbc.sql("""
                        INSERT INTO privatekb.document_chunk (
                            chunk_id, workspace_id, document_id, document_version_id,
                            chunk_index, start_offset, end_offset, content, embedding,
                            embedding_model, embedding_digest
                        )
                        SELECT chunk_id, workspace_id, document_id, document_version_id,
                               chunk_index, start_offset, end_offset, content, embedding,
                               embedding_model, embedding_digest
                          FROM privatekb.document_chunk_staging
                         WHERE indexing_job_id = :jobId
                         ORDER BY chunk_index
                        """)
                .param("jobId", work.indexingJobId())
                .update();
        if (inserted != totalChunkCount) {
            throw new IllegalStateException("Not all staged chunks were activated");
        }

        int updated = jdbc.sql("""
                        UPDATE privatekb.indexing_job
                           SET status = 'INDEXED', error_code = NULL,
                               embedding_model = :embeddingModel,
                               embedding_digest = :embeddingDigest,
                               embedding_dimensions = :embeddingDimensions,
                               updated_at = now()
                         WHERE indexing_job_id = :jobId
                           AND status = 'INDEXING'
                        """)
                .param("jobId", work.indexingJobId())
                .param("embeddingModel", model.model())
                .param("embeddingDigest", model.digest())
                .param("embeddingDimensions", model.dimensions())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Indexing job is not in INDEXING state");
        }
        jdbc.sql("DELETE FROM privatekb.document_chunk_staging WHERE indexing_job_id = :jobId")
                .param("jobId", work.indexingJobId())
                .update();
        insertTransition(
                work.indexingJobId(),
                IndexingStatus.INDEXING,
                IndexingStatus.INDEXED,
                null
        );
    }

    @Override
    @Transactional
    public void fail(UUID indexingJobId, IndexingErrorCode errorCode) {
        int updated = jdbc.sql("""
                        UPDATE privatekb.indexing_job
                           SET status = 'FAILED', error_code = :errorCode,
                               attempt_count = attempt_count + 1, updated_at = now(),
                               checkpoint_updated_at = now()
                         WHERE indexing_job_id = :jobId
                           AND status = 'INDEXING'
                        """)
                .param("jobId", indexingJobId)
                .param("errorCode", errorCode.name())
                .update();
        if (updated == 1) {
            insertTransition(
                    indexingJobId,
                    IndexingStatus.INDEXING,
                    IndexingStatus.FAILED,
                    errorCode.name()
            );
        }
    }

    @Override
    @Transactional
    public int recoverInterruptedJobs() {
        List<UUID> interrupted = jdbc.sql("""
                        SELECT indexing_job_id
                          FROM privatekb.indexing_job
                         WHERE status = 'INDEXING'
                         ORDER BY updated_at
                         FOR UPDATE
                        """)
                .query(UUID.class)
                .list();
        int recovered = 0;
        for (UUID jobId : interrupted) {
            int updated = jdbc.sql("""
                            UPDATE privatekb.indexing_job
                               SET status = 'PAUSED', error_code = 'PROCESS_INTERRUPTED',
                                   updated_at = now(), checkpoint_updated_at = now()
                             WHERE indexing_job_id = :jobId
                               AND status = 'INDEXING'
                            """)
                    .param("jobId", jobId)
                    .update();
            if (updated == 1) {
                insertTransition(
                        jobId,
                        IndexingStatus.INDEXING,
                        IndexingStatus.PAUSED,
                        IndexingErrorCode.PROCESS_INTERRUPTED.name()
                );
                recovered++;
            }
        }
        return recovered;
    }

    @Override
    public int purgeExpiredStaging(Duration retention) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Staging retention must be positive");
        }
        Instant cutoff = Instant.now().minus(retention);
        List<UUID> candidates = jdbc.sql("""
                        SELECT j.indexing_job_id
                          FROM privatekb.indexing_job j
                         WHERE j.status IN ('PAUSED', 'FAILED')
                           AND COALESCE(j.checkpoint_updated_at, j.updated_at) < :cutoff
                           AND EXISTS (
                               SELECT 1
                                 FROM privatekb.document_chunk_staging s
                                WHERE s.indexing_job_id = j.indexing_job_id
                           )
                         ORDER BY COALESCE(j.checkpoint_updated_at, j.updated_at)
                         LIMIT 100
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .query(UUID.class)
                .list();
        int purged = 0;
        for (UUID jobId : candidates) {
            Boolean removed = transactions.execute(status -> purgeExpiredJob(jobId, cutoff));
            if (Boolean.TRUE.equals(removed)) {
                purged++;
            }
        }
        return purged;
    }

    private void appendBatchTransaction(
            IndexingWorkItem work,
            EmbeddingModelInfo model,
            List<IndexedChunk> chunks
    ) {
        int checkpoint = jdbc.sql("""
                        SELECT checkpoint_chunk_index
                          FROM privatekb.indexing_job
                         WHERE indexing_job_id = :jobId
                           AND status = 'INDEXING'
                         FOR UPDATE
                        """)
                .param("jobId", work.indexingJobId())
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Indexing job is not in INDEXING state"));
        int first = chunks.getFirst().index();
        int next = chunks.getLast().index() + 1;
        if (next <= checkpoint) {
            return;
        }
        if (first != checkpoint) {
            throw new IllegalStateException("Indexing batch does not start at the checkpoint");
        }
        for (int index = 0; index < chunks.size(); index++) {
            if (chunks.get(index).index() != first + index) {
                throw new IllegalArgumentException("Indexing batch is not sequential");
            }
        }

        batchJdbc.batchUpdate(
                """
                INSERT INTO privatekb.document_chunk_staging (
                    chunk_id, indexing_job_id, workspace_id, document_id,
                    document_version_id, chunk_index, start_offset, end_offset,
                    content, embedding, embedding_model, embedding_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, ?)
                ON CONFLICT (indexing_job_id, chunk_index) DO UPDATE SET
                    chunk_id = EXCLUDED.chunk_id,
                    start_offset = EXCLUDED.start_offset,
                    end_offset = EXCLUDED.end_offset,
                    content = EXCLUDED.content,
                    embedding = EXCLUDED.embedding,
                    embedding_model = EXCLUDED.embedding_model,
                    embedding_digest = EXCLUDED.embedding_digest
                """,
                chunks,
                chunks.size(),
                (statement, chunk) -> {
                    statement.setObject(1, chunk.chunkId());
                    statement.setObject(2, work.indexingJobId());
                    statement.setObject(3, work.workspaceId());
                    statement.setObject(4, work.documentId());
                    statement.setObject(5, work.documentVersionId());
                    statement.setInt(6, chunk.index());
                    statement.setInt(7, chunk.startOffset());
                    statement.setInt(8, chunk.endOffset());
                    statement.setString(9, chunk.content());
                    statement.setString(10, vectorLiteral(chunk.embedding()));
                    statement.setString(11, model.model());
                    if (model.digest() == null) {
                        statement.setNull(12, Types.VARCHAR);
                    } else {
                        statement.setString(12, model.digest());
                    }
                }
        );
        int updated = jdbc.sql("""
                        UPDATE privatekb.indexing_job
                           SET checkpoint_chunk_index = :next,
                               checkpoint_updated_at = now(), updated_at = now()
                         WHERE indexing_job_id = :jobId
                           AND status = 'INDEXING'
                           AND checkpoint_chunk_index = :expected
                        """)
                .param("next", next)
                .param("jobId", work.indexingJobId())
                .param("expected", first)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Indexing checkpoint changed while storing a batch");
        }
    }

    private boolean purgeExpiredJob(UUID jobId, Instant cutoff) {
        Optional<IndexingStatus> locked = jdbc.sql("""
                        SELECT status
                          FROM privatekb.indexing_job
                         WHERE indexing_job_id = :jobId
                           AND status IN ('PAUSED', 'FAILED')
                           AND COALESCE(checkpoint_updated_at, updated_at) < :cutoff
                         FOR UPDATE
                        """)
                .param("jobId", jobId)
                .param("cutoff", Timestamp.from(cutoff))
                .query(String.class)
                .optional()
                .map(IndexingStatus::valueOf);
        if (locked.isEmpty()) {
            return false;
        }
        jdbc.sql("DELETE FROM privatekb.document_chunk_staging WHERE indexing_job_id = :jobId")
                .param("jobId", jobId)
                .update();
        jdbc.sql("""
                        UPDATE privatekb.indexing_job
                           SET checkpoint_chunk_index = 0,
                               checkpoint_updated_at = NULL,
                               error_code = 'CHECKPOINT_EXPIRED',
                               updated_at = now()
                         WHERE indexing_job_id = :jobId
                        """)
                .param("jobId", jobId)
                .update();
        return true;
    }

    private void sleepBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying an indexing batch", exception);
        }
    }

    private boolean resumable(IndexingStatus status) {
        return status == IndexingStatus.PENDING
                || status == IndexingStatus.MODEL_WAITING
                || status == IndexingStatus.REINDEX_REQUIRED
                || status == IndexingStatus.PAUSED
                || status == IndexingStatus.FAILED;
    }

    private void insertTransition(
            UUID jobId,
            IndexingStatus from,
            IndexingStatus to,
            String reasonCode
    ) {
        jdbc.sql("""
                        INSERT INTO privatekb.indexing_job_transition (
                            indexing_job_id, from_status, to_status, reason_code
                        ) VALUES (
                            :jobId, :fromStatus, :toStatus, :reasonCode
                        )
                        """)
                .param("jobId", jobId)
                .param("fromStatus", from == null ? null : from.name())
                .param("toStatus", to.name())
                .param("reasonCode", reasonCode)
                .update();
    }

    private String vectorLiteral(float[] values) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding contains a non-finite value");
            }
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }

    private IndexingErrorCode errorCode(String value) {
        return value == null ? null : IndexingErrorCode.valueOf(value);
    }

    private Integer nullableInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private record Candidate(
            UUID indexingJobId,
            UUID workspaceId,
            UUID documentId,
            UUID documentVersionId,
            String originalFilename,
            String sourceStorageKey,
            String extractedStorageKey,
            IndexingStatus status,
            int attemptCount
    ) {
    }

    private record Progress(
            IndexingStatus status,
            int nextChunkIndex,
            String extractedSha256,
            String chunkingVersion,
            Integer chunkSize,
            Integer chunkOverlap,
            String embeddingModel,
            String embeddingDigest,
            Integer embeddingDimensions
    ) {
    }

    private record StagingProgress(int count, Integer minimumIndex, Integer maximumIndex) {

        private boolean matchesCheckpoint(int checkpoint) {
            if (checkpoint == 0) {
                return count == 0;
            }
            return count == checkpoint
                    && Objects.equals(minimumIndex, 0)
                    && Objects.equals(maximumIndex, checkpoint - 1);
        }
    }
}
