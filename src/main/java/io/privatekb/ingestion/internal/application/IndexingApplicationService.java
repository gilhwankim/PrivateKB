package io.privatekb.ingestion.internal.application;

import io.privatekb.ingestion.internal.application.port.IndexingJobStore;
import io.privatekb.ingestion.internal.domain.IndexingNotFoundException;
import io.privatekb.ingestion.internal.application.port.IndexingProcessor;
import io.privatekb.ingestion.internal.domain.IndexingRetryRejectedException;
import io.privatekb.ingestion.internal.domain.IndexingStatus;
import io.privatekb.ingestion.internal.application.view.IndexingView;

import java.util.UUID;

import io.privatekb.platform.LocalEmbeddingClient;
import io.privatekb.platform.LocalEmbeddingClient.EmbeddingModelInfo;
import io.privatekb.platform.LocalEmbeddingException;

import org.springframework.stereotype.Service;

@Service
public class IndexingApplicationService {

    private final IndexingJobStore jobs;
    private final IndexingProcessor processor;
    private final LocalEmbeddingClient embeddings;

    public IndexingApplicationService(
            IndexingJobStore jobs,
            IndexingProcessor processor,
            LocalEmbeddingClient embeddings
    ) {
        this.jobs = jobs;
        this.processor = processor;
        this.embeddings = embeddings;
    }

    public IndexingView find(UUID documentVersionId) {
        return jobs.findByVersion(documentVersionId)
                .orElseThrow(() -> new IndexingNotFoundException());
    }

    public IndexingView retry(UUID documentVersionId) {
        IndexingView current = find(documentVersionId);
        if (current.status() != IndexingStatus.FAILED
                && current.status() != IndexingStatus.MODEL_WAITING
                && current.status() != IndexingStatus.REINDEX_REQUIRED
                && current.status() != IndexingStatus.PAUSED) {
            throw new IndexingRetryRejectedException();
        }
        processor.process(documentVersionId);
        return current;
    }

    public int resumeAvailable() {
        try {
            EmbeddingModelInfo model = embeddings.verifyModel();
            jobs.markStaleIndexes(model);
        } catch (LocalEmbeddingException exception) {
            var waiting = jobs.findResumableVersions();
            waiting.forEach(processor::process);
            throw exception;
        }
        var resumable = jobs.findResumableVersions();
        resumable.forEach(processor::process);
        return resumable.size();
    }
}
