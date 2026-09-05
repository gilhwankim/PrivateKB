package io.privatekb.ingestion.internal.domain;

public record DocumentChunk(
        int index,
        int startOffset,
        int endOffset,
        String content
) {
}
