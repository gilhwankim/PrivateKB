package io.privatekb.knowledge.internal;

import io.privatekb.knowledge.DocumentSourceCatalog;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcDocumentSourceCatalog implements DocumentSourceCatalog {

    private final JdbcClient jdbc;

    JdbcDocumentSourceCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DocumentSourceLocation> findLocations(UUID workspaceId, UUID documentVersionId) {
        // 검색 경로에서는 실행하지 않는다. 클릭한 버전의 최근 위치만 인덱스로 조회한다.
        return jdbc.sql("""
                        SELECT location.source_path, location.byte_size, location.last_modified_at
                          FROM privatekb.document_source_location location
                          JOIN privatekb.document_version version
                            ON version.workspace_id = location.workspace_id
                           AND version.document_version_id = location.document_version_id
                         WHERE location.workspace_id = :workspaceId
                           AND location.document_version_id = :documentVersionId
                         ORDER BY location.updated_at DESC, location.source_location_id
                         LIMIT 8
                        """)
                .param("workspaceId", workspaceId)
                .param("documentVersionId", documentVersionId)
                .query((row, number) -> new DocumentSourceLocation(
                        row.getString("source_path"), row.getLong("byte_size"),
                        row.getTimestamp("last_modified_at").toInstant().toEpochMilli()))
                .list();
    }

    @Override
    @Transactional
    public void register(DocumentSourceRegistration registration) {
        UUID sourceFolderId = registerSourceFolder(registration);
        jdbc.sql("""
                        INSERT INTO privatekb.document_source_location (
                            source_location_id, workspace_id, document_version_id,
                            source_folder_id, source_path, source_path_hash, relative_path,
                            byte_size, last_modified_at, status, last_verified_at
                        ) VALUES (
                            :sourceLocationId, :workspaceId, :documentVersionId,
                            :sourceFolderId, :sourcePath, :sourcePathHash, :relativePath,
                            :byteSize, :lastModifiedAt, 'AVAILABLE', now()
                        )
                        ON CONFLICT (workspace_id, source_path_hash) DO UPDATE SET
                            document_version_id = EXCLUDED.document_version_id,
                            source_folder_id = EXCLUDED.source_folder_id,
                            source_path = EXCLUDED.source_path,
                            relative_path = EXCLUDED.relative_path,
                            byte_size = EXCLUDED.byte_size,
                            last_modified_at = EXCLUDED.last_modified_at,
                            status = 'AVAILABLE',
                            last_verified_at = now(),
                            updated_at = now()
                        """)
                .param("sourceLocationId", UUID.randomUUID())
                .param("workspaceId", registration.workspaceId())
                .param("documentVersionId", registration.documentVersionId())
                .param("sourceFolderId", sourceFolderId)
                .param("sourcePath", registration.sourcePath())
                .param("sourcePathHash", registration.sourcePathHash())
                .param("relativePath", registration.relativePath())
                .param("byteSize", registration.byteSize())
                .param("lastModifiedAt", Timestamp.from(registration.lastModifiedAt()))
                .update();
    }

    private UUID registerSourceFolder(DocumentSourceRegistration registration) {
        if (registration.sourceRootPath() == null) {
            return null;
        }
        return jdbc.sql("""
                        INSERT INTO privatekb.source_folder (
                            source_folder_id, workspace_id, root_path, root_path_hash
                        ) VALUES (
                            :sourceFolderId, :workspaceId, :rootPath, :rootPathHash
                        )
                        ON CONFLICT (workspace_id, root_path_hash) DO UPDATE SET
                            root_path = EXCLUDED.root_path,
                            updated_at = now()
                        RETURNING source_folder_id
                        """)
                .param("sourceFolderId", UUID.randomUUID())
                .param("workspaceId", registration.workspaceId())
                .param("rootPath", registration.sourceRootPath())
                .param("rootPathHash", registration.sourceRootPathHash())
                .query(UUID.class)
                .single();
    }
}
