package io.privatekb.ingestion.internal.persistence;

import io.privatekb.ingestion.internal.domain.IngestionErrorCode;
import io.privatekb.ingestion.internal.application.port.IngestionJobStore;
import io.privatekb.ingestion.internal.domain.IngestionStatus;
import io.privatekb.ingestion.internal.application.view.IngestionView;
import io.privatekb.knowledge.DocumentCatalog.DocumentVersionRef;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcIngestionJobStore implements IngestionJobStore {

    private final JdbcClient jdbc;

    JdbcIngestionJobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void create(UUID ingestionJobId, DocumentVersionRef documentVersion) {
        jdbc.sql("""
                        INSERT INTO privatekb.ingestion_job (
                            ingestion_job_id, document_version_id, workspace_id, status
                        ) VALUES (
                            :jobId, :versionId, :workspaceId, 'RECEIVED'
                        )
                        """)
                .param("jobId", ingestionJobId)
                .param("versionId", documentVersion.documentVersionId())
                .param("workspaceId", documentVersion.workspaceId())
                .update();
        insertTransition(ingestionJobId, null, IngestionStatus.RECEIVED, null);

        jdbc.sql("""
                        UPDATE privatekb.ingestion_job
                           SET status = 'STORED', updated_at = now()
                         WHERE ingestion_job_id = :jobId
                           AND status = 'RECEIVED'
                        """)
                .param("jobId", ingestionJobId)
                .update();
        insertTransition(ingestionJobId, IngestionStatus.RECEIVED, IngestionStatus.STORED, null);
    }

    @Override
    public Optional<IngestionView> find(UUID ingestionJobId) {
        return jdbc.sql("""
                        SELECT j.ingestion_job_id, j.workspace_id, v.document_id,
                               j.document_version_id, v.original_filename, j.status,
                               j.attempt_count, j.error_code, j.created_at, j.updated_at
                          FROM privatekb.ingestion_job j
                          JOIN privatekb.document_version v
                            ON v.document_version_id = j.document_version_id
                           AND v.workspace_id = j.workspace_id
                         WHERE j.ingestion_job_id = :jobId
                        """)
                .param("jobId", ingestionJobId)
                .query((resultSet, rowNumber) -> new IngestionView(
                        resultSet.getObject("ingestion_job_id", UUID.class),
                        resultSet.getObject("workspace_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("document_version_id", UUID.class),
                        resultSet.getString("original_filename"),
                        IngestionStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("attempt_count"),
                        toErrorCode(resultSet.getString("error_code")),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()
                ))
                .optional();
    }

    @Override
    public Optional<UUID> findJobIdByVersion(UUID documentVersionId) {
        return jdbc.sql("""
                        SELECT ingestion_job_id
                          FROM privatekb.ingestion_job
                         WHERE document_version_id = :versionId
                        """)
                .param("versionId", documentVersionId)
                .query(UUID.class)
                .optional();
    }

    @Override
    @Transactional
    public Optional<IngestionWorkItem> startAttempt(UUID ingestionJobId, int maximumAttempts) {
        Optional<AttemptCandidate> candidate = loadAttemptCandidate(ingestionJobId);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        AttemptCandidate value = candidate.orElseThrow();
        if ((value.status() != IngestionStatus.STORED && value.status() != IngestionStatus.FAILED)
                || value.attemptCount() >= maximumAttempts) {
            return Optional.empty();
        }

        int updated = jdbc.sql("""
                        UPDATE privatekb.ingestion_job
                           SET status = 'PARSING',
                               attempt_count = attempt_count + 1,
                               error_code = NULL,
                               updated_at = now()
                         WHERE ingestion_job_id = :jobId
                           AND status = :expectedStatus
                           AND attempt_count = :attemptCount
                        """)
                .param("jobId", ingestionJobId)
                .param("expectedStatus", value.status().name())
                .param("attemptCount", value.attemptCount())
                .update();
        if (updated != 1) {
            return Optional.empty();
        }

        insertTransition(ingestionJobId, value.status(), IngestionStatus.PARSING, null);
        return Optional.of(new IngestionWorkItem(
                value.ingestionJobId(),
                value.workspaceId(),
                value.documentVersionId(),
                value.originalFilename(),
                value.detectedMediaType(),
                value.sourceStorageKey(),
                value.attemptCount() + 1
        ));
    }

    @Override
    @Transactional
    public boolean queueOcr(UUID ingestionJobId) {
        int updated = jdbc.sql("""
                        UPDATE privatekb.ingestion_job
                           SET status = 'OCR_PENDING', updated_at = now()
                         WHERE ingestion_job_id = :jobId
                           AND status = 'PARSING'
                        """)
                .param("jobId", ingestionJobId)
                .update();
        if (updated == 1) {
            insertTransition(
                    ingestionJobId,
                    IngestionStatus.PARSING,
                    IngestionStatus.OCR_PENDING,
                    null
            );
            return true;
        }
        return false;
    }

    @Override
    @Transactional
    public Optional<IngestionWorkItem> startOcr(UUID ingestionJobId) {
        Optional<AttemptCandidate> candidate = loadAttemptCandidate(ingestionJobId);
        if (candidate.isEmpty() || candidate.orElseThrow().status() != IngestionStatus.OCR_PENDING) {
            return Optional.empty();
        }
        AttemptCandidate value = candidate.orElseThrow();
        int updated = jdbc.sql("""
                        UPDATE privatekb.ingestion_job
                           SET status = 'OCR_RUNNING', error_code = NULL, updated_at = now()
                         WHERE ingestion_job_id = :jobId
                           AND status = 'OCR_PENDING'
                        """)
                .param("jobId", ingestionJobId)
                .update();
        if (updated != 1) {
            return Optional.empty();
        }
        insertTransition(
                ingestionJobId,
                IngestionStatus.OCR_PENDING,
                IngestionStatus.OCR_RUNNING,
                null
        );
        return Optional.of(toWorkItem(value));
    }

    @Override
    @Transactional
    public List<UUID> recoverOcrJobs(int limit) {
        List<UUID> interrupted = jdbc.sql("""
                        SELECT ingestion_job_id
                          FROM privatekb.ingestion_job
                         WHERE status = 'OCR_RUNNING'
                         ORDER BY updated_at, ingestion_job_id
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query(UUID.class)
                .list();
        for (UUID jobId : interrupted) {
            int updated = jdbc.sql("""
                            UPDATE privatekb.ingestion_job
                               SET status = 'OCR_PENDING', updated_at = now()
                             WHERE ingestion_job_id = :jobId
                               AND status = 'OCR_RUNNING'
                            """)
                    .param("jobId", jobId)
                    .update();
            if (updated == 1) {
                insertTransition(
                        jobId,
                        IngestionStatus.OCR_RUNNING,
                        IngestionStatus.OCR_PENDING,
                        "APPLICATION_RESTARTED"
                );
            }
        }
        return jdbc.sql("""
                        SELECT ingestion_job_id
                          FROM privatekb.ingestion_job
                         WHERE status = 'OCR_PENDING'
                         ORDER BY updated_at, ingestion_job_id
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    @Override
    @Transactional
    public void complete(UUID ingestionJobId, ExtractedContentMetadata extracted) {
        IngestionStatus fromStatus = currentStatus(ingestionJobId)
                .orElseThrow(() -> new IllegalStateException("Ingestion job was not found"));
        if (fromStatus != IngestionStatus.PARSING
                && fromStatus != IngestionStatus.OCR_RUNNING) {
            throw new IllegalStateException("Ingestion job is not processing content");
        }
        jdbc.sql("""
                        INSERT INTO privatekb.extracted_content (
                            document_version_id, workspace_id, storage_key, media_type,
                            character_count, page_count, parser_name
                        ) VALUES (
                            :versionId, :workspaceId, :storageKey, :mediaType,
                            :characterCount, :pageCount, :parserName
                        )
                        ON CONFLICT (document_version_id) DO UPDATE SET
                            storage_key = excluded.storage_key,
                            media_type = excluded.media_type,
                            character_count = excluded.character_count,
                            page_count = excluded.page_count,
                            parser_name = excluded.parser_name,
                            updated_at = now()
                        """)
                .param("versionId", extracted.documentVersionId())
                .param("workspaceId", extracted.workspaceId())
                .param("storageKey", extracted.storageKey())
                .param("mediaType", extracted.mediaType())
                .param("characterCount", extracted.characterCount())
                .param("pageCount", extracted.pageCount())
                .param("parserName", extracted.parserName())
                .update();

        int updated = jdbc.sql("""
                        UPDATE privatekb.ingestion_job
                           SET status = 'PARSED', error_code = NULL, updated_at = now()
                         WHERE ingestion_job_id = :jobId
                           AND status = :expectedStatus
                        """)
                .param("jobId", ingestionJobId)
                .param("expectedStatus", fromStatus.name())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Ingestion job state changed while completing");
        }
        insertTransition(ingestionJobId, fromStatus, IngestionStatus.PARSED, null);
    }

    @Override
    @Transactional
    public void fail(UUID ingestionJobId, IngestionErrorCode errorCode) {
        Optional<IngestionStatus> current = currentStatus(ingestionJobId);
        if (current.isEmpty()) {
            return;
        }
        IngestionStatus fromStatus = current.orElseThrow();
        if (fromStatus != IngestionStatus.PARSING
                && fromStatus != IngestionStatus.OCR_RUNNING) {
            return;
        }
        int updated = jdbc.sql("""
                        UPDATE privatekb.ingestion_job
                           SET status = 'FAILED', error_code = :errorCode, updated_at = now()
                         WHERE ingestion_job_id = :jobId
                           AND status = :expectedStatus
                        """)
                .param("jobId", ingestionJobId)
                .param("errorCode", errorCode.name())
                .param("expectedStatus", fromStatus.name())
                .update();
        if (updated == 1) {
            insertTransition(
                    ingestionJobId,
                    fromStatus,
                    IngestionStatus.FAILED,
                    errorCode.name()
            );
        }
    }

    private Optional<AttemptCandidate> loadAttemptCandidate(UUID ingestionJobId) {
        return jdbc.sql("""
                        SELECT j.ingestion_job_id, j.workspace_id, j.document_version_id,
                               j.status, j.attempt_count, v.original_filename,
                               v.detected_media_type, v.source_storage_key
                          FROM privatekb.ingestion_job j
                          JOIN privatekb.document_version v
                            ON v.document_version_id = j.document_version_id
                           AND v.workspace_id = j.workspace_id
                         WHERE j.ingestion_job_id = :jobId
                        """)
                .param("jobId", ingestionJobId)
                .query((resultSet, rowNumber) -> new AttemptCandidate(
                        resultSet.getObject("ingestion_job_id", UUID.class),
                        resultSet.getObject("workspace_id", UUID.class),
                        resultSet.getObject("document_version_id", UUID.class),
                        IngestionStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("attempt_count"),
                        resultSet.getString("original_filename"),
                        resultSet.getString("detected_media_type"),
                        resultSet.getString("source_storage_key")
                ))
                .optional();
    }

    private Optional<IngestionStatus> currentStatus(UUID ingestionJobId) {
        return jdbc.sql("""
                        SELECT status
                          FROM privatekb.ingestion_job
                         WHERE ingestion_job_id = :jobId
                        """)
                .param("jobId", ingestionJobId)
                .query(String.class)
                .optional()
                .map(IngestionStatus::valueOf);
    }

    private IngestionWorkItem toWorkItem(AttemptCandidate value) {
        return new IngestionWorkItem(
                value.ingestionJobId(),
                value.workspaceId(),
                value.documentVersionId(),
                value.originalFilename(),
                value.detectedMediaType(),
                value.sourceStorageKey(),
                value.attemptCount()
        );
    }

    private void insertTransition(
            UUID ingestionJobId,
            IngestionStatus from,
            IngestionStatus to,
            String reasonCode
    ) {
        jdbc.sql("""
                        INSERT INTO privatekb.ingestion_job_transition (
                            ingestion_job_id, from_status, to_status, reason_code
                        ) VALUES (
                            :jobId, :fromStatus, :toStatus, :reasonCode
                        )
                        """)
                .param("jobId", ingestionJobId)
                .param("fromStatus", from == null ? null : from.name())
                .param("toStatus", to.name())
                .param("reasonCode", reasonCode)
                .update();
    }

    private IngestionErrorCode toErrorCode(String value) {
        return value == null ? null : IngestionErrorCode.valueOf(value);
    }

    private record AttemptCandidate(
            UUID ingestionJobId,
            UUID workspaceId,
            UUID documentVersionId,
            IngestionStatus status,
            int attemptCount,
            String originalFilename,
            String detectedMediaType,
            String sourceStorageKey
    ) {
    }
}
