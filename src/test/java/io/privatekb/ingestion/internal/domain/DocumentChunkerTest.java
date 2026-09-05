package io.privatekb.ingestion.internal.domain;

import io.privatekb.ingestion.internal.config.IndexingProperties;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.io.StringReader;
import java.time.Duration;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker(
            new IndexingProperties(200, 40, 8, 64, 3, Duration.ofHours(72), 3)
    );

    @Test
    void splitsAtNaturalBoundaryAndKeepsBoundedOverlap() {
        String first = "첫 번째 회의 문장입니다. ".repeat(7);
        String second = "두 번째 장애 대응 문장입니다. ".repeat(8);
        List<DocumentChunk> chunks = chunker.split(first + "\n" + second);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content()).isNotBlank();
            assertThat(chunk.content().length()).isLessThanOrEqualTo(200);
            assertThat(chunk.startOffset()).isLessThan(chunk.endOffset());
        });
        assertThat(chunks).extracting(DocumentChunk::index)
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks.get(1).startOffset()).isLessThan(chunks.get(0).endOffset());
    }

    @Test
    void ignoresBlankContent() {
        assertThat(chunker.split(" \n\t ")).isEmpty();
    }

    @Test
    void streamingSplitMatchesInMemorySplitAndCanResumeAtCheckpoint() throws Exception {
        String text = ("\uc81c\ud488 \ud68c\uc758\ub85d\uc785\ub2c8\ub2e4. \uc7a5\uc560 \ub300\uc751\uacfc \ud6c4\uc18d \uc870치를 확인합니다.\n").repeat(40);
        List<DocumentChunk> expected = chunker.split(text);
        java.util.ArrayList<DocumentChunk> streamed = new java.util.ArrayList<>();
        int count = chunker.forEach(new StringReader(text), 0, streamed::add);

        assertThat(count).isEqualTo(expected.size());
        assertThat(streamed).containsExactlyElementsOf(expected);

        java.util.ArrayList<DocumentChunk> resumed = new java.util.ArrayList<>();
        chunker.forEach(new StringReader(text), 2, resumed::add);
        assertThat(resumed).containsExactlyElementsOf(expected.subList(2, expected.size()));
    }
}
