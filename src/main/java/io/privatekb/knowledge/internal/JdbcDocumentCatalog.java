package io.privatekb.knowledge.internal;

import io.privatekb.knowledge.DocumentCatalog;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDocumentCatalog implements DocumentCatalog {

    private final JdbcClient jdbc;

    JdbcDocumentCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean workspaceExists(UUID workspaceId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM privatekb.workspace WHERE workspace_id = :workspaceId
                        )
                        """)
                .param("workspaceId", workspaceId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Optional<DocumentVersionRef> findVersionByHash(UUID workspaceId, String sha256) {
        return jdbc.sql("""
                        SELECT workspace_id, document_id, document_version_id, version_number,
                               original_filename, declared_media_type, detected_media_type,
                               byte_size, sha256, source_storage_key
                          FROM privatekb.document_version
                         WHERE workspace_id = :workspaceId
                           AND sha256 = :sha256
                        """)
                .param("workspaceId", workspaceId)
                .param("sha256", sha256)
                .query((resultSet, rowNumber) -> new DocumentVersionRef(
                        resultSet.getObject("workspace_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("document_version_id", UUID.class),
                        resultSet.getInt("version_number"),
                        resultSet.getString("original_filename"),
                        resultSet.getString("declared_media_type"),
                        resultSet.getString("detected_media_type"),
                        resultSet.getLong("byte_size"),
                        resultSet.getString("sha256"),
                        resultSet.getString("source_storage_key")
                ))
                .optional();
    }

    @Override
    public DocumentVersionRef createVersion(DocumentVersionRegistration registration) {
        jdbc.sql("""
                        INSERT INTO privatekb.document (document_id, workspace_id)
                        VALUES (:documentId, :workspaceId)
                        """)
                .param("documentId", registration.documentId())
                .param("workspaceId", registration.workspaceId())
                .update();

        jdbc.sql("""
                        INSERT INTO privatekb.document_version (
                            document_version_id, document_id, workspace_id, version_number,
                            original_filename, declared_media_type, detected_media_type,
                            byte_size, sha256, source_storage_key
                        ) VALUES (
                            :documentVersionId, :documentId, :workspaceId, 1,
                            :originalFilename, :declaredMediaType, :detectedMediaType,
                            :byteSize, :sha256, :sourceStorageKey
                        )
                        """)
                .param("documentVersionId", registration.documentVersionId())
                .param("documentId", registration.documentId())
                .param("workspaceId", registration.workspaceId())
                .param("originalFilename", registration.originalFilename())
                .param("declaredMediaType", registration.declaredMediaType())
                .param("detectedMediaType", registration.detectedMediaType())
                .param("byteSize", registration.byteSize())
                .param("sha256", registration.sha256())
                .param("sourceStorageKey", registration.sourceStorageKey())
                .update();

        return new DocumentVersionRef(
                registration.workspaceId(),
                registration.documentId(),
                registration.documentVersionId(),
                1,
                registration.originalFilename(),
                registration.declaredMediaType(),
                registration.detectedMediaType(),
                registration.byteSize(),
                registration.sha256(),
                registration.sourceStorageKey()
        );
    }
}
