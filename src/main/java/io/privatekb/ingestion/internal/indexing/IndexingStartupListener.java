package io.privatekb.ingestion.internal.indexing;

import io.privatekb.ingestion.internal.application.IndexingApplicationService;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore;
import io.privatekb.ingestion.internal.config.IndexingProperties;
import io.privatekb.platform.ContentStorage;
import io.privatekb.platform.LocalEmbeddingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class IndexingStartupListener {

    private static final Logger log = LoggerFactory.getLogger(IndexingStartupListener.class);
    private static final int SOURCE_CLEANUP_BATCH_SIZE = 500;
    private static final int MAX_SOURCE_CLEANUP_BATCHES = 20;

    private final IndexingApplicationService indexing;
    private final IndexingJobStore jobs;
    private final IndexingProperties properties;
    private final ContentStorage storage;

    IndexingStartupListener(
            IndexingApplicationService indexing,
            IndexingJobStore jobs,
            IndexingProperties properties,
            ContentStorage storage
    ) {
        this.indexing = indexing;
        this.jobs = jobs;
        this.properties = properties;
        this.storage = storage;
    }

    @EventListener(ApplicationReadyEvent.class)
    void resumePendingJobs() {
        cleanupCompletedSourceCopies();
        jobs.purgeExpiredStaging(properties.stagingRetention());
        jobs.recoverInterruptedJobs();
        try {
            indexing.resumeAvailable();
        } catch (LocalEmbeddingException ignored) {
            // Ollama나 모델이 없을 때도 애플리케이션과 문서 파싱은 계속 사용할 수 있다.
        }
    }

    private void cleanupCompletedSourceCopies() {
        for (int batch = 0; batch < MAX_SOURCE_CLEANUP_BATCHES; batch++) {
            var pending = jobs.findCompletedSourceCopiesPendingCleanup(SOURCE_CLEANUP_BATCH_SIZE);
            if (pending.isEmpty()) {
                return;
            }
            int cleaned = 0;
            for (var source : pending) {
                try {
                    storage.delete(source.sourceStorageKey());
                    jobs.markSourceCopyDeleted(source.documentVersionId());
                    cleaned++;
                } catch (RuntimeException exception) {
                    log.warn(
                            "완료 문서 원본 사본 시작 정리 지연: documentVersionId={}, exceptionType={}",
                            source.documentVersionId(),
                            exception.getClass().getSimpleName()
                    );
                }
            }
            if (cleaned == 0) {
                return;
            }
        }
    }
}
