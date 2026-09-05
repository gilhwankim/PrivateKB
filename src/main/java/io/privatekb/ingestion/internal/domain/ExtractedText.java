package io.privatekb.ingestion.internal.domain;

public record ExtractedText(
        String text,
        String mediaType,
        Integer pageCount,
        String parserName
) {
}
