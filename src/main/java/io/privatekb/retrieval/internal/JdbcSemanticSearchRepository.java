package io.privatekb.retrieval.internal;

import io.privatekb.retrieval.SearchResultView;
import io.privatekb.retrieval.SemanticSearchRepository;
import io.privatekb.platform.LocalEmbeddingClient.EmbeddingModelInfo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcSemanticSearchRepository implements SemanticSearchRepository {

    private final JdbcClient jdbc;

    JdbcSemanticSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SearchResultView> search(
            UUID workspaceId,
            String query,
            String filenameHint,
            String extensionHint,
            String folderHint,
            Instant uploadedFrom,
            Instant uploadedToExclusive,
            boolean sortByUploadedAtDescending,
            float[] queryEmbedding,
            EmbeddingModelInfo model,
            int candidateLimit,
            double minimumScore,
            int perDocumentLimit,
            int limit
    ) {
        jdbc.sql("SET LOCAL hnsw.iterative_scan = 'strict_order'").update();
        return jdbc.sql("""
                        WITH folder_scope AS MATERIALIZED (
                            SELECT DISTINCT location.document_version_id
                              FROM privatekb.document_source_location location
                              LEFT JOIN privatekb.source_folder folder
                                ON folder.workspace_id = location.workspace_id
                               AND folder.source_folder_id = location.source_folder_id
                             WHERE location.workspace_id = :workspaceId
                               AND :folderHint <> ''
                               AND (
                                    location.folder_path_search LIKE '%' || :folderHint || '%'
                                    OR folder.root_path_search LIKE '%' || :folderHint || '%'
                               )
                        ), vector_candidates AS MATERIALIZED (
                            SELECT c.chunk_id
                              FROM privatekb.document_chunk c
                              JOIN privatekb.document_version v
                                ON v.document_version_id = c.document_version_id
                               AND v.workspace_id = c.workspace_id
                             WHERE c.workspace_id = :workspaceId
                               AND c.embedding_model = :embeddingModel
                               AND c.embedding_digest IS NOT DISTINCT FROM :embeddingDigest
                               AND (:filterUploadedFrom = false OR v.created_at >= :uploadedFrom)
                               AND (:filterUploadedTo = false OR v.created_at < :uploadedTo)
                               AND (
                                    :folderHint = ''
                                    OR EXISTS (
                                        SELECT 1
                                          FROM folder_scope scope
                                         WHERE scope.document_version_id = c.document_version_id
                                    )
                               )
                             ORDER BY c.embedding <=> CAST(:embedding AS vector)
                             LIMIT :candidateLimit
                        ), keyword_candidates AS MATERIALIZED (
                            SELECT c.chunk_id
                              FROM privatekb.document_chunk c
                              JOIN privatekb.document_version v
                                ON v.document_version_id = c.document_version_id
                               AND v.workspace_id = c.workspace_id
                             WHERE c.workspace_id = :workspaceId
                               AND c.embedding_model = :embeddingModel
                               AND c.embedding_digest IS NOT DISTINCT FROM :embeddingDigest
                               AND (:filterUploadedFrom = false OR v.created_at >= :uploadedFrom)
                               AND (:filterUploadedTo = false OR v.created_at < :uploadedTo)
                               AND (
                                    :folderHint = ''
                                    OR EXISTS (
                                        SELECT 1
                                          FROM folder_scope scope
                                         WHERE scope.document_version_id = c.document_version_id
                                    )
                               )
                               AND c.content_search @@ plainto_tsquery('simple', :query)
                             ORDER BY ts_rank_cd(
                                          c.content_search,
                                          plainto_tsquery('simple', :query)
                                      ) DESC,
                                      c.chunk_id
                             LIMIT :candidateLimit
                        ), filename_candidates AS MATERIALIZED (
                            SELECT candidate_chunk.chunk_id
                              FROM privatekb.document_version v
                              JOIN LATERAL (
                                   SELECT c.chunk_id
                                     FROM privatekb.document_chunk c
                                    WHERE c.workspace_id = v.workspace_id
                                      AND c.document_version_id = v.document_version_id
                                      AND c.embedding_model = :embeddingModel
                                      AND c.embedding_digest IS NOT DISTINCT FROM :embeddingDigest
                                    ORDER BY c.chunk_index
                                    LIMIT 1
                              ) candidate_chunk ON true
                             WHERE v.workspace_id = :workspaceId
                               AND (:filterUploadedFrom = false OR v.created_at >= :uploadedFrom)
                               AND (:filterUploadedTo = false OR v.created_at < :uploadedTo)
                               AND (
                                    :folderHint = ''
                                    OR EXISTS (
                                        SELECT 1
                                          FROM folder_scope scope
                                         WHERE scope.document_version_id = v.document_version_id
                                    )
                               )
                               AND (
                                    (:filenameHint <> '' AND v.filename_search LIKE '%' || :filenameHint || '%')
                                    OR (:extensionHint <> '' AND v.filename_search LIKE '%' || :extensionHint || '%')
                               )
                             ORDER BY
                                   CASE
                                       WHEN :filenameHint <> ''
                                        AND v.filename_search LIKE '%' || :filenameHint || '%'
                                       THEN 0
                                       ELSE 1
                                   END,
                                   v.created_at DESC
                             LIMIT :candidateLimit
                        ), candidate_ids AS MATERIALIZED (
                            SELECT chunk_id FROM vector_candidates
                            UNION
                            SELECT chunk_id FROM keyword_candidates
                            UNION
                            SELECT chunk_id FROM filename_candidates
                        ), scored_chunks AS MATERIALIZED (
                            SELECT c.chunk_id, c.document_id, c.document_version_id,
                                   v.original_filename, v.version_number, c.chunk_index,
                                   v.created_at AS uploaded_at,
                                   c.start_offset, c.end_offset, c.content,
                                   source.source_path,
                                   LEAST(1.0,
                                       0.85 * GREATEST(
                                           0,
                                           1 - (c.embedding <=> CAST(:embedding AS vector))
                                       )
                                       + 0.15 * GREATEST(
                                           LEAST(1.0, ts_rank_cd(
                                               c.content_search,
                                               plainto_tsquery('simple', :query)
                                           )),
                                           CASE
                                               WHEN :filenameHint <> ''
                                                AND v.filename_search LIKE '%' || :filenameHint || '%'
                                               THEN 1.0
                                               WHEN :extensionHint <> ''
                                                AND v.filename_search LIKE '%' || :extensionHint || '%'
                                               THEN 0.70
                                               ELSE 0.0
                                           END
                                       )
                                   ) AS score
                              FROM candidate_ids candidate
                              JOIN privatekb.document_chunk c
                                ON c.chunk_id = candidate.chunk_id
                              JOIN privatekb.document_version v
                                ON v.document_version_id = c.document_version_id
                               AND v.workspace_id = c.workspace_id
                              LEFT JOIN LATERAL (
                                   SELECT location.source_path
                                     FROM privatekb.document_source_location location
                                     LEFT JOIN privatekb.source_folder folder
                                       ON folder.workspace_id = location.workspace_id
                                      AND folder.source_folder_id = location.source_folder_id
                                    WHERE location.workspace_id = v.workspace_id
                                      AND location.document_version_id = v.document_version_id
                                      AND (
                                           :folderHint = ''
                                           OR location.folder_path_search LIKE '%' || :folderHint || '%'
                                           OR folder.root_path_search LIKE '%' || :folderHint || '%'
                                      )
                                    ORDER BY CASE WHEN location.status = 'AVAILABLE' THEN 0 ELSE 1 END,
                                             location.updated_at DESC,
                                             location.source_location_id
                                    LIMIT 1
                              ) source ON true
                             WHERE c.workspace_id = :workspaceId
                               AND c.embedding_model = :embeddingModel
                               AND c.embedding_digest IS NOT DISTINCT FROM :embeddingDigest
                               AND (:filterUploadedFrom = false OR v.created_at >= :uploadedFrom)
                               AND (:filterUploadedTo = false OR v.created_at < :uploadedTo)
                               AND (:folderHint = '' OR source.source_path IS NOT NULL)
                        ), qualified_chunks AS MATERIALIZED (
                            SELECT *
                              FROM scored_chunks
                             WHERE score >= :minimumScore
                        ), ranked_documents AS (
                            SELECT scored_chunks.*,
                                   row_number() OVER (
                                       PARTITION BY document_id
                                       ORDER BY score DESC, chunk_id
                                   ) AS document_rank
                              FROM qualified_chunks scored_chunks
                        )
                        SELECT chunk_id, document_id, document_version_id,
                               original_filename, version_number, uploaded_at, chunk_index,
                               start_offset, end_offset, content, source_path, score
                          FROM ranked_documents
                         WHERE document_rank <= :perDocumentLimit
                         ORDER BY
                               CASE WHEN :sortByUploadedAt THEN uploaded_at END DESC,
                               score DESC,
                               chunk_id
                         LIMIT :resultLimit
                        """)
                .param("embedding", vectorLiteral(queryEmbedding))
                .param("query", query)
                .param("filenameHint", filenameHint)
                .param("extensionHint", extensionHint)
                .param("folderHint", normalizeHint(folderHint))
                .param("filterUploadedFrom", uploadedFrom != null)
                .param("uploadedFrom", uploadParameter(uploadedFrom, Instant.EPOCH))
                .param("filterUploadedTo", uploadedToExclusive != null)
                .param("uploadedTo", uploadParameter(
                        uploadedToExclusive,
                        Instant.parse("9999-12-31T23:59:59Z")
                ))
                .param("sortByUploadedAt", sortByUploadedAtDescending)
                .param("workspaceId", workspaceId)
                .param("embeddingModel", model.model())
                .param("embeddingDigest", model.digest())
                .param("candidateLimit", candidateLimit)
                .param("minimumScore", minimumScore)
                .param("perDocumentLimit", perDocumentLimit)
                .param("resultLimit", limit)
                .query((resultSet, rowNumber) -> new SearchResultView(
                        resultSet.getObject("chunk_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("document_version_id", UUID.class),
                        resultSet.getString("original_filename"),
                        resultSet.getInt("version_number"),
                        resultSet.getObject("uploaded_at", OffsetDateTime.class).toInstant(),
                        resultSet.getInt("chunk_index"),
                        resultSet.getInt("start_offset"),
                        resultSet.getInt("end_offset"),
                        resultSet.getString("content"),
                        sourceFolderPath(resultSet.getString("source_path")),
                        resultSet.getDouble("score")
                ))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SearchResultView> searchByUploadTime(
            UUID workspaceId,
            String extensionHint,
            Instant uploadedFrom,
            Instant uploadedToExclusive,
            int limit
    ) {
        return jdbc.sql("""
                        WITH ranked_versions AS MATERIALIZED (
                            SELECT v.document_id, v.document_version_id,
                                   v.original_filename, v.version_number, v.created_at,
                                   row_number() OVER (
                                       PARTITION BY v.document_id
                                       ORDER BY v.created_at DESC, v.document_version_id
                                   ) AS version_rank
                              FROM privatekb.document_version v
                             WHERE v.workspace_id = :workspaceId
                               AND (:filterUploadedFrom = false OR v.created_at >= :uploadedFrom)
                               AND (:filterUploadedTo = false OR v.created_at < :uploadedTo)
                               AND (:extensionHint = '' OR v.filename_search LIKE '%' || :extensionHint || '%')
                        ), latest_versions AS MATERIALIZED (
                            SELECT *
                              FROM ranked_versions
                             WHERE version_rank = 1
                        )
                        SELECT c.chunk_id, v.document_id, v.document_version_id,
                               v.original_filename, v.version_number,
                               v.created_at AS uploaded_at,
                               c.chunk_index, c.start_offset, c.end_offset, c.content,
                               source.source_path,
                               1.0::double precision AS score
                          FROM latest_versions v
                          JOIN LATERAL (
                               SELECT c.chunk_id, c.chunk_index, c.start_offset,
                                      c.end_offset, c.content
                                 FROM privatekb.document_chunk c
                                WHERE c.workspace_id = :workspaceId
                                  AND c.document_version_id = v.document_version_id
                                ORDER BY c.chunk_index
                                LIMIT 1
                          ) c ON true
                          LEFT JOIN LATERAL (
                               SELECT location.source_path
                                 FROM privatekb.document_source_location location
                                WHERE location.workspace_id = :workspaceId
                                  AND location.document_version_id = v.document_version_id
                                ORDER BY CASE WHEN location.status = 'AVAILABLE' THEN 0 ELSE 1 END,
                                         location.updated_at DESC,
                                         location.source_location_id
                                LIMIT 1
                          ) source ON true
                         ORDER BY v.created_at DESC, v.document_version_id
                         LIMIT :resultLimit
                        """)
                .param("workspaceId", workspaceId)
                .param("extensionHint", extensionHint)
                .param("filterUploadedFrom", uploadedFrom != null)
                .param("uploadedFrom", uploadParameter(uploadedFrom, Instant.EPOCH))
                .param("filterUploadedTo", uploadedToExclusive != null)
                .param("uploadedTo", uploadParameter(
                        uploadedToExclusive,
                        Instant.parse("9999-12-31T23:59:59Z")
                ))
                .param("resultLimit", limit)
                .query((resultSet, rowNumber) -> new SearchResultView(
                        resultSet.getObject("chunk_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("document_version_id", UUID.class),
                        resultSet.getString("original_filename"),
                        resultSet.getInt("version_number"),
                        resultSet.getObject("uploaded_at", OffsetDateTime.class).toInstant(),
                        resultSet.getInt("chunk_index"),
                        resultSet.getInt("start_offset"),
                        resultSet.getInt("end_offset"),
                        resultSet.getString("content"),
                        sourceFolderPath(resultSet.getString("source_path")),
                        resultSet.getDouble("score")
                ))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SearchResultView> searchByFolder(
            UUID workspaceId,
            String folderHint,
            int limit
    ) {
        return jdbc.sql("""
                        WITH matched_locations AS MATERIALIZED (
                            SELECT v.document_id, v.document_version_id,
                                   v.original_filename, v.version_number, v.created_at,
                                   location.source_path,
                                   row_number() OVER (
                                       PARTITION BY v.document_id
                                       ORDER BY v.created_at DESC,
                                                CASE WHEN location.status = 'AVAILABLE' THEN 0 ELSE 1 END,
                                                location.updated_at DESC,
                                                location.source_location_id
                                   ) AS document_rank
                              FROM privatekb.document_source_location location
                              LEFT JOIN privatekb.source_folder folder
                                ON folder.workspace_id = location.workspace_id
                               AND folder.source_folder_id = location.source_folder_id
                              JOIN privatekb.document_version v
                                ON v.workspace_id = location.workspace_id
                               AND v.document_version_id = location.document_version_id
                             WHERE location.workspace_id = :workspaceId
                               AND (
                                    location.folder_path_search LIKE '%' || :folderHint || '%'
                                    OR folder.root_path_search LIKE '%' || :folderHint || '%'
                               )
                        )
                        SELECT c.chunk_id, matched.document_id, matched.document_version_id,
                               matched.original_filename, matched.version_number,
                               matched.created_at AS uploaded_at,
                               c.chunk_index, c.start_offset, c.end_offset, c.content,
                               matched.source_path,
                               1.0::double precision AS score
                          FROM matched_locations matched
                          JOIN LATERAL (
                               SELECT chunk.chunk_id, chunk.chunk_index, chunk.start_offset,
                                      chunk.end_offset, chunk.content
                                 FROM privatekb.document_chunk chunk
                                WHERE chunk.workspace_id = :workspaceId
                                  AND chunk.document_version_id = matched.document_version_id
                                ORDER BY chunk.chunk_index
                                LIMIT 1
                          ) c ON true
                         WHERE matched.document_rank = 1
                         ORDER BY matched.created_at DESC, matched.document_version_id
                         LIMIT :resultLimit
                        """)
                .param("workspaceId", workspaceId)
                .param("folderHint", normalizeHint(folderHint))
                .param("resultLimit", limit)
                .query((resultSet, rowNumber) -> new SearchResultView(
                        resultSet.getObject("chunk_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("document_version_id", UUID.class),
                        resultSet.getString("original_filename"),
                        resultSet.getInt("version_number"),
                        resultSet.getObject("uploaded_at", OffsetDateTime.class).toInstant(),
                        resultSet.getInt("chunk_index"),
                        resultSet.getInt("start_offset"),
                        resultSet.getInt("end_offset"),
                        resultSet.getString("content"),
                        sourceFolderPath(resultSet.getString("source_path")),
                        resultSet.getDouble("score")
                ))
                .list();
    }

    private String normalizeHint(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private String sourceFolderPath(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return null;
        }
        int separator = Math.max(sourcePath.lastIndexOf('\\'), sourcePath.lastIndexOf('/'));
        return separator > 0 ? sourcePath.substring(0, separator) : sourcePath;
    }

    private OffsetDateTime uploadParameter(Instant value, Instant fallback) {
        return OffsetDateTime.ofInstant(value == null ? fallback : value, ZoneOffset.UTC);
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
}
