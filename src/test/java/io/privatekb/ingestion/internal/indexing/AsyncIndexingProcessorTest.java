package io.privatekb.ingestion.internal.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;

import io.privatekb.ingestion.internal.domain.DocumentChunker;
import io.privatekb.ingestion.internal.domain.IndexingErrorCode;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore.IndexingWorkItem;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore.IndexingCheckpoint;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore.IndexedChunk;
import io.privatekb.ingestion.internal.config.IndexingProperties;
import io.privatekb.platform.ContentStorage;
import io.privatekb.platform.LocalEmbeddingClient;
import io.privatekb.platform.LocalEmbeddingException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AsyncIndexingProcessorTest {

    @Test
    void leavesJobWaitingWithoutReadingProtectedContentWhenModelIsMissing() {
        IndexingJobStore jobs = mock(IndexingJobStore.class);
        ContentStorage storage = mock(ContentStorage.class);
        LocalEmbeddingClient embeddings = mock(LocalEmbeddingClient.class);
        UUID documentVersionId = UUID.randomUUID();
        UUID indexingJobId = UUID.randomUUID();
        IndexingWorkItem work = new IndexingWorkItem(
                indexingJobId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                documentVersionId,
                "합성문서.txt",
                "source/synthetic.bin",
                "extracted/synthetic.txt"
        );
        when(jobs.start(documentVersionId, 3)).thenReturn(Optional.of(work));
        when(embeddings.verifyModel()).thenThrow(new LocalEmbeddingException(
                LocalEmbeddingException.Reason.MODEL_NOT_AVAILABLE
        ));
        AsyncIndexingProcessor processor = new AsyncIndexingProcessor(
                jobs,
                new DocumentChunker(new IndexingProperties(
                        200, 40, 8, 64, 3, Duration.ofHours(72), 3
                )),
                storage,
                embeddings,
                new IndexingProperties(200, 40, 8, 64, 3, Duration.ofHours(72), 3)
        );

        processor.process(documentVersionId);

        verify(jobs).markModelWaiting(
                indexingJobId,
                IndexingErrorCode.EMBEDDING_MODEL_NOT_AVAILABLE
        );
        verifyNoInteractions(storage);
    }

    @Test
    void embedsAndStoresBoundedBatchesBeforeAtomicallyCompletingDocument() {
        IndexingJobStore jobs = mock(IndexingJobStore.class);
        ContentStorage storage = mock(ContentStorage.class);
        LocalEmbeddingClient embeddings = mock(LocalEmbeddingClient.class);
        IndexingProperties properties = new IndexingProperties(
                200, 40, 16, 64, 3, Duration.ofHours(72), 3
        );
        DocumentChunker chunker = new DocumentChunker(properties);
        String text = ("\uc81c\ud488 회의와 장애 대응 후속 조치를 기록합니다.\n").repeat(700);
        int expectedChunks = chunker.split(text).size();
        UUID documentVersionId = UUID.randomUUID();
        IndexingWorkItem work = new IndexingWorkItem(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                documentVersionId, "합성문서.txt", "source/synthetic.bin", "extracted/synthetic.txt"
        );
        LocalEmbeddingClient.EmbeddingModelInfo model =
                new LocalEmbeddingClient.EmbeddingModelInfo("test", "digest", 1024);

        when(jobs.start(documentVersionId, 3)).thenReturn(Optional.of(work));
        when(embeddings.verifyModel()).thenReturn(model);
        when(storage.sha256(work.extractedStorageKey())).thenReturn("a".repeat(64));
        when(storage.open(work.extractedStorageKey())).thenReturn(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))
        );
        when(jobs.prepare(work, model, "a".repeat(64), "boundary-v1", 200, 40))
                .thenReturn(new IndexingCheckpoint(0));
        when(embeddings.embed(anyList())).thenAnswer(invocation -> {
            List<?> requested = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(requested.size());
            requested.forEach(ignored -> vectors.add(new float[1024]));
            return vectors;
        });

        new AsyncIndexingProcessor(jobs, chunker, storage, embeddings, properties)
                .process(documentVersionId);

        int expectedDatabaseCalls = (expectedChunks + 63) / 64;
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IndexedChunk>> batches = ArgumentCaptor.forClass(List.class);
        verify(jobs, times(expectedDatabaseCalls)).appendBatch(
                eq(work), eq(model), batches.capture(), eq(3)
        );
        assertThat(batches.getAllValues())
                .allSatisfy(batch -> org.assertj.core.api.Assertions.assertThat(batch).hasSizeLessThanOrEqualTo(64));
        assertThat(
                batches.getAllValues().stream().mapToInt(List::size).sum()
        ).isEqualTo(expectedChunks);
        verify(jobs).complete(work, model, expectedChunks);
        verify(storage).delete(work.sourceStorageKey());
        verify(jobs).markSourceCopyDeleted(work.documentVersionId());
    }
}
