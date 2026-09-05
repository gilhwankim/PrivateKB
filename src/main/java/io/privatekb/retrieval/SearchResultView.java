package io.privatekb.retrieval;

import java.time.Instant;
import java.util.UUID;

public record SearchResultView(
        UUID chunkId,
        UUID documentId,
        UUID documentVersionId,
        String originalFilename,
        int versionNumber,
        Instant uploadedAt,
        int chunkIndex,
        int startOffset,
        int endOffset,
        String content,
        String sourceFolderPath,
        double score
) {
}
