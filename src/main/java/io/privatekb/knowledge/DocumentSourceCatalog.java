package io.privatekb.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DocumentSourceCatalog {

    void register(DocumentSourceRegistration registration);

    List<DocumentSourceLocation> findLocations(UUID workspaceId, UUID documentVersionId);

    record DocumentSourceLocation(String path, long byteSize, long lastModifiedMillis) {
    }

    record DocumentSourceRegistration(
            UUID workspaceId,
            UUID documentVersionId,
            String sourcePath,
            String sourcePathHash,
            String sourceRootPath,
            String sourceRootPathHash,
            String relativePath,
            long byteSize,
            Instant lastModifiedAt
    ) {
    }
}
