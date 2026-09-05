package io.privatekb.ingestion.internal.application.command;

import io.privatekb.ingestion.internal.domain.UploadSource;

import java.util.UUID;

public record UploadDocumentCommand(
        UUID workspaceId,
        String originalFilename,
        String declaredMediaType,
        long byteSize,
        UploadSource source
) {
}
