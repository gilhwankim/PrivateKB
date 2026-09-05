package io.privatekb.retrieval;

import java.time.Instant;
import java.util.UUID;

public record GroundedCitation(
        int sourceNumber,
        UUID chunkId,
        UUID documentId,
        UUID documentVersionId,
        String originalFilename,
        int versionNumber,
        Instant uploadedAt,
        int chunkIndex,
        int startOffset,
        int endOffset,
        String sourceFolderPath,
        String excerpt,
        double score
) {
}
