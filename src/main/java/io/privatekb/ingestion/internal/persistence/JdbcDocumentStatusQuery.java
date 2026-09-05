package io.privatekb.ingestion.internal.persistence;

import io.privatekb.ingestion.internal.domain.DocumentEmbeddingStatus;
import io.privatekb.ingestion.internal.application.view.DocumentStatusItem;
import io.privatekb.ingestion.internal.application.port.DocumentStatusQuery;
import io.privatekb.ingestion.internal.domain.IndexingStatus;
import io.privatekb.ingestion.internal.domain.IngestionStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDocumentStatusQuery implements DocumentStatusQuery {

    private final JdbcClient jdbc;

    JdbcDocumentStatusQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long count(UUID workspaceId) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM privatekb.document
                         WHERE workspace_id = :workspaceId
                        """)
                .param("workspaceId", workspaceId)
                .query(Long.class)
                .single();
    }

    @Override
    public List<DocumentStatusItem> findLatest(UUID workspaceId, int offset, int limit) {
        return jdbc.sql("""
                        SELECT v.document_id, v.document_version_id, v.version_number,
                               v.original_filename, v.detected_media_type, v.byte_size,
                               ingestion.status AS ingestion_status,
                               ingestion.error_code AS ingestion_error_code,
                               indexing.status AS indexing_status,
                               indexing.error_code AS indexing_error_code,
                               v.created_at,
                               GREATEST(
                                   ingestion.updated_at,
                                   COALESCE(indexing.updated_at, ingestion.updated_at)
                               ) AS updated_at
                          FROM (
                                SELECT DISTINCT ON (document_id)
                                       document_id, document_version_id, workspace_id,
                                       version_number, original_filename, detected_media_type,
                                       byte_size, created_at
                                  FROM privatekb.document_version
                                 WHERE workspace_id = :workspaceId
                                 ORDER BY document_id, version_number DESC
                               ) v
                          JOIN privatekb.ingestion_job ingestion
                            ON ingestion.document_version_id = v.document_version_id
                           AND ingestion.workspace_id = v.workspace_id
                          LEFT JOIN privatekb.indexing_job indexing
                            ON indexing.document_version_id = v.document_version_id
                           AND indexing.workspace_id = v.workspace_id
                         ORDER BY v.created_at DESC, v.document_version_id DESC
                         LIMIT :limit OFFSET :offset
                        """)
                .param("workspaceId", workspaceId)
                .param("limit", limit)
                .param("offset", offset)
                .query((resultSet, rowNumber) -> {
                    IngestionStatus ingestion = IngestionStatus.valueOf(
                            resultSet.getString("ingestion_status")
                    );
                    String rawIndexing = resultSet.getString("indexing_status");
                    IndexingStatus indexing = rawIndexing == null
                            ? null
                            : IndexingStatus.valueOf(rawIndexing);
                    String ingestionError = resultSet.getString("ingestion_error_code");
                    String indexingError = resultSet.getString("indexing_error_code");
                    return new DocumentStatusItem(
                            resultSet.getObject("document_id", UUID.class),
                            resultSet.getObject("document_version_id", UUID.class),
                            resultSet.getInt("version_number"),
                            resultSet.getString("original_filename"),
                            resultSet.getString("detected_media_type"),
                            resultSet.getLong("byte_size"),
                            combinedStatus(ingestion, indexing),
                            ingestion,
                            indexing,
                            indexingError != null ? indexingError : ingestionError,
                            instant(resultSet.getTimestamp("created_at")),
                            instant(resultSet.getTimestamp("updated_at"))
                    );
                })
                .list();
    }

    private DocumentEmbeddingStatus combinedStatus(
            IngestionStatus ingestion,
            IndexingStatus indexing
    ) {
        if (ingestion == IngestionStatus.FAILED || indexing == IndexingStatus.FAILED) {
            return DocumentEmbeddingStatus.FAILED;
        }
        if (ingestion != IngestionStatus.PARSED || indexing == null
                || indexing == IndexingStatus.PENDING || indexing == IndexingStatus.INDEXING
                || indexing == IndexingStatus.PAUSED) {
            return DocumentEmbeddingStatus.PROCESSING;
        }
        return switch (indexing) {
            case MODEL_WAITING -> DocumentEmbeddingStatus.WAITING_FOR_MODEL;
            case REINDEX_REQUIRED -> DocumentEmbeddingStatus.REINDEX_REQUIRED;
            case INDEXED -> DocumentEmbeddingStatus.COMPLETED;
            case FAILED -> DocumentEmbeddingStatus.FAILED;
            case PENDING, INDEXING, PAUSED -> DocumentEmbeddingStatus.PROCESSING;
        };
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp.toInstant();
    }
}
