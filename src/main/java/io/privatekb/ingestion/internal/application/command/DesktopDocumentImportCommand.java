package io.privatekb.ingestion.internal.application.command;

import io.privatekb.ingestion.internal.domain.UploadSource;

import java.util.UUID;

public record DesktopDocumentImportCommand(
        UUID workspaceId,
        String originalFilename,
        String declaredMediaType,
        long byteSize,
        long lastModifiedMillis,
        String sourcePath,
        String sourceRootPath,
        String relativePath,
        UploadSource source
) {
}
