package io.privatekb.knowledge;

import java.util.Optional;
import java.util.UUID;

public interface DocumentCatalog {

    boolean workspaceExists(UUID workspaceId);

    Optional<DocumentVersionRef> findVersionByHash(UUID workspaceId, String sha256);

    DocumentVersionRef createVersion(DocumentVersionRegistration registration);

    record DocumentVersionRegistration(
            UUID workspaceId,
            UUID documentId,
            UUID documentVersionId,
            String originalFilename,
            String declaredMediaType,
            String detectedMediaType,
            long byteSize,
            String sha256,
            String sourceStorageKey
    ) {
    }

    record DocumentVersionRef(
            UUID workspaceId,
            UUID documentId,
            UUID documentVersionId,
            int versionNumber,
            String originalFilename,
            String declaredMediaType,
            String detectedMediaType,
            long byteSize,
            String sha256,
            String sourceStorageKey
    ) {
    }
}
