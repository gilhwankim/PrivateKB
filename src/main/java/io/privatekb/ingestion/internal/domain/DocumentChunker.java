package io.privatekb.ingestion.internal.domain;

import io.privatekb.ingestion.internal.config.IndexingProperties;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

@Component
public final class DocumentChunker {

    private final IndexingProperties properties;

    public DocumentChunker(IndexingProperties properties) {
        this.properties = properties;
    }

    public List<DocumentChunk> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<DocumentChunk> chunks = new ArrayList<>();
        try {
            forEach(new StringReader(text), 0, chunks::add);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return List.copyOf(chunks);
    }

    public int forEach(
            Reader reader,
            int skipChunkCount,
            Consumer<DocumentChunk> consumer
    ) throws IOException {
        if (skipChunkCount < 0) {
            throw new IllegalArgumentException("Skip chunk count must not be negative");
        }
        StringBuilder buffer = new StringBuilder(properties.chunkSize() * 2);
        char[] readBuffer = new char[Math.max(1024, properties.chunkSize())];
        int absoluteOffset = 0;
        int chunkIndex = 0;
        boolean endOfInput = false;

        while (true) {
            while (!endOfInput && buffer.length() < properties.chunkSize() + 1) {
                int read = reader.read(readBuffer);
                if (read < 0) {
                    endOfInput = true;
                } else if (read > 0) {
                    buffer.append(readBuffer, 0, read);
                }
            }

            int leadingWhitespace = skipWhitespace(buffer, 0);
            if (leadingWhitespace > 0) {
                buffer.delete(0, leadingWhitespace);
                absoluteOffset += leadingWhitespace;
                continue;
            }
            if (buffer.isEmpty()) {
                if (endOfInput) {
                    return chunkIndex;
                }
                continue;
            }

            int proposedEnd = Math.min(buffer.length(), properties.chunkSize());
            boolean finalChunk = endOfInput && proposedEnd == buffer.length();
            int end = finalChunk
                    ? proposedEnd
                    : findBoundary(buffer, 0, proposedEnd);
            int trimmedEnd = trimTrailingWhitespace(buffer, 0, end);
            if (trimmedEnd <= 0) {
                return chunkIndex;
            }

            if (chunkIndex >= skipChunkCount) {
                consumer.accept(new DocumentChunk(
                        chunkIndex,
                        absoluteOffset,
                        absoluteOffset + trimmedEnd,
                        buffer.substring(0, trimmedEnd)
                ));
            }
            chunkIndex++;
            if (finalChunk) {
                return chunkIndex;
            }

            int next = Math.max(1, end - properties.chunkOverlap());
            buffer.delete(0, next);
            absoluteOffset += next;
        }
    }

    private int findBoundary(CharSequence text, int start, int proposedEnd) {
        int minimum = Math.min(proposedEnd, start + properties.chunkSize() / 2);
        for (int index = proposedEnd; index > minimum; index--) {
            char previous = text.charAt(index - 1);
            if (previous == '\n') {
                return index;
            }
        }
        for (int index = proposedEnd; index > minimum; index--) {
            if (Character.isWhitespace(text.charAt(index - 1))) {
                return index;
            }
        }
        return proposedEnd;
    }

    private int skipWhitespace(CharSequence text, int index) {
        int result = index;
        while (result < text.length() && Character.isWhitespace(text.charAt(result))) {
            result++;
        }
        return result;
    }

    private int trimTrailingWhitespace(CharSequence text, int start, int end) {
        int result = end;
        while (result > start && Character.isWhitespace(text.charAt(result - 1))) {
            result--;
        }
        return result;
    }
}
