package io.privatekb.ingestion.internal.indexing;

import io.privatekb.ingestion.internal.domain.DocumentChunk;
import io.privatekb.ingestion.internal.domain.DocumentChunker;
import io.privatekb.ingestion.internal.domain.IndexingErrorCode;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore.IndexedChunk;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore.IndexingWorkItem;
import io.privatekb.ingestion.internal.application.port.IndexingProcessor;
import io.privatekb.ingestion.internal.config.IndexingProperties;
import io.privatekb.platform.ContentStorage;
import io.privatekb.platform.LocalEmbeddingClient;
import io.privatekb.platform.LocalEmbeddingClient.EmbeddingModelInfo;
import io.privatekb.platform.LocalEmbeddingException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
class AsyncIndexingProcessor implements IndexingProcessor {

    private static final Logger log = LoggerFactory.getLogger(AsyncIndexingProcessor.class);
    private static final String CHUNKING_VERSION = "boundary-v1";

    private final IndexingJobStore jobs;
    private final DocumentChunker chunker;
    private final ContentStorage storage;
    private final LocalEmbeddingClient embeddings;
    private final IndexingProperties properties;

    AsyncIndexingProcessor(
            IndexingJobStore jobs,
            DocumentChunker chunker,
            ContentStorage storage,
            LocalEmbeddingClient embeddings,
            IndexingProperties properties
    ) {
        this.jobs = jobs;
        this.chunker = chunker;
        this.storage = storage;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    @Override
    @Async("indexingTaskExecutor")
    public void process(UUID documentVersionId) {
        Optional<IndexingWorkItem> claimed = jobs.start(
                documentVersionId,
                properties.maxAttempts()
        );
        if (claimed.isEmpty()) {
            return;
        }
        IndexingWorkItem work = claimed.orElseThrow();

        try {
            EmbeddingModelInfo model = embeddings.verifyModel();
            String extractedSha256 = storage.sha256(work.extractedStorageKey());
            var checkpoint = jobs.prepare(
                    work,
                    model,
                    extractedSha256,
                    CHUNKING_VERSION,
                    properties.chunkSize(),
                    properties.chunkOverlap()
            );
            int chunkCount = indexProgressively(work, model, checkpoint.nextChunkIndex());
            if (chunkCount == 0) {
                jobs.fail(work.indexingJobId(), IndexingErrorCode.EXTRACTED_CONTENT_MISSING);
                return;
            }
            discardManagedSourceCopy(work);
            jobs.complete(work, model, chunkCount);
            log.info(
                    "문서 색인 완료: indexingJobId={}, chunkCount={}",
                    work.indexingJobId(),
                    chunkCount
            );
        } catch (LocalEmbeddingException exception) {
            handleEmbeddingFailure(work.indexingJobId(), exception);
        } catch (IOException exception) {
            fail(work.indexingJobId(), IndexingErrorCode.EXTRACTED_CONTENT_MISSING, exception);
        } catch (RuntimeException exception) {
            fail(work.indexingJobId(), IndexingErrorCode.INDEXING_INTERNAL_ERROR, exception);
        }
    }

    private void discardManagedSourceCopy(IndexingWorkItem work) {
        try {
            storage.delete(work.sourceStorageKey());
            jobs.markSourceCopyDeleted(work.documentVersionId());
            log.info("처리 완료 원본 사본 삭제: documentVersionId={}", work.documentVersionId());
        } catch (RuntimeException exception) {
            // 벡터 결과는 사용할 수 있으므로 정리 실패로 색인을 실패시키지 않는다.
            // source_copy_deleted_at을 비워 두어 다음 실행에서 다시 정리한다.
            log.warn(
                    "처리 완료 원본 사본 삭제 지연: documentVersionId={}, exceptionType={}",
                    work.documentVersionId(),
                    exception.getClass().getSimpleName()
            );
        }
    }

    private int indexProgressively(
            IndexingWorkItem work,
            EmbeddingModelInfo model,
            int checkpoint
    ) throws IOException {
        ProgressiveBatch batch = new ProgressiveBatch(work, model);
        try (InputStream input = storage.open(work.extractedStorageKey());
             Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            int totalChunks = chunker.forEach(reader, checkpoint, batch::add);
            batch.finish();
            return totalChunks;
        }
    }

    private final class ProgressiveBatch {

        private final IndexingWorkItem work;
        private final EmbeddingModelInfo model;
        private final List<DocumentChunk> pendingEmbedding;
        private final List<IndexedChunk> pendingDatabase;

        private ProgressiveBatch(IndexingWorkItem work, EmbeddingModelInfo model) {
            this.work = work;
            this.model = model;
            this.pendingEmbedding = new ArrayList<>(properties.embeddingBatchSize());
            this.pendingDatabase = new ArrayList<>(properties.databaseBatchSize());
        }

        private void add(DocumentChunk chunk) {
            pendingEmbedding.add(chunk);
            if (pendingEmbedding.size() >= properties.embeddingBatchSize()) {
                embedPending();
            }
        }

        private void finish() {
            embedPending();
            storePendingDatabase();
        }

        private void embedPending() {
            if (pendingEmbedding.isEmpty()) {
                return;
            }
            List<float[]> vectors = embeddings.embed(
                    pendingEmbedding.stream().map(DocumentChunk::content).toList()
            );
            if (vectors.size() != pendingEmbedding.size()) {
                throw new IllegalStateException("Embedding response size does not match the request");
            }
            for (int index = 0; index < pendingEmbedding.size(); index++) {
                DocumentChunk chunk = pendingEmbedding.get(index);
                pendingDatabase.add(new IndexedChunk(
                        deterministicChunkId(work.indexingJobId(), chunk.index()),
                        chunk.index(),
                        chunk.startOffset(),
                        chunk.endOffset(),
                        chunk.content(),
                        vectors.get(index)
                ));
            }
            pendingEmbedding.clear();
            while (pendingDatabase.size() >= properties.databaseBatchSize()) {
                storeDatabaseBatch(properties.databaseBatchSize());
            }
        }

        private void storePendingDatabase() {
            if (!pendingDatabase.isEmpty()) {
                storeDatabaseBatch(pendingDatabase.size());
            }
        }

        private void storeDatabaseBatch(int size) {
            List<IndexedChunk> batch = List.copyOf(pendingDatabase.subList(0, size));
            jobs.appendBatch(work, model, batch, properties.databaseBatchRetries());
            pendingDatabase.subList(0, size).clear();
        }
    }

    private UUID deterministicChunkId(UUID indexingJobId, int chunkIndex) {
        return UUID.nameUUIDFromBytes(
                (indexingJobId + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8)
        );
    }

    private void handleEmbeddingFailure(UUID jobId, LocalEmbeddingException exception) {
        switch (exception.reason()) {
            case OLLAMA_NOT_RUNNING -> jobs.markModelWaiting(
                    jobId,
                    IndexingErrorCode.OLLAMA_NOT_RUNNING
            );
            case MODEL_NOT_AVAILABLE -> jobs.markModelWaiting(
                    jobId,
                    IndexingErrorCode.EMBEDDING_MODEL_NOT_AVAILABLE
            );
            case MODEL_INCOMPATIBLE -> fail(
                    jobId,
                    IndexingErrorCode.EMBEDDING_MODEL_INCOMPATIBLE,
                    exception
            );
            case REQUEST_FAILED -> fail(
                    jobId,
                    IndexingErrorCode.EMBEDDING_REQUEST_FAILED,
                    exception
            );
        }
    }

    private void fail(UUID jobId, IndexingErrorCode code, Exception exception) {
        jobs.fail(jobId, code);
        log.warn(
                "문서 색인 실패: indexingJobId={}, errorCode={}, exceptionType={}",
                jobId,
                code,
                exception.getClass().getSimpleName()
        );
    }
}
