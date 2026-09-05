package io.privatekb.ingestion.internal.application;

import io.privatekb.ingestion.internal.domain.ExtractedText;
import io.privatekb.ingestion.internal.domain.IngestionErrorCode;
import io.privatekb.ingestion.internal.application.port.IngestionJobStore;
import io.privatekb.ingestion.internal.application.port.IngestionJobStore.ExtractedContentMetadata;
import io.privatekb.ingestion.internal.application.port.IngestionJobStore.IngestionWorkItem;
import io.privatekb.ingestion.internal.application.port.IngestionProcessor;
import io.privatekb.ingestion.internal.config.IngestionProperties;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore;
import io.privatekb.ingestion.internal.application.port.IndexingProcessor;
import io.privatekb.ingestion.internal.application.port.OcrProcessor;
import io.privatekb.ingestion.internal.domain.OcrRequiredException;
import io.privatekb.ingestion.internal.domain.TextExtractionException;
import io.privatekb.ingestion.internal.application.port.TextExtractionService;
import io.privatekb.platform.ContentStorage;
import io.privatekb.platform.ContentStorageException;
import io.privatekb.platform.StoredContent;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
class AsyncIngestionProcessor implements IngestionProcessor {

    private static final Logger log = LoggerFactory.getLogger(AsyncIngestionProcessor.class);

    private final IngestionJobStore jobs;
    private final TextExtractionService extractor;
    private final ContentStorage storage;
    private final IngestionProperties properties;
    private final IndexingJobStore indexingJobs;
    private final IndexingProcessor indexingProcessor;
    private final OcrProcessor ocrProcessor;

    AsyncIngestionProcessor(
            IngestionJobStore jobs,
            TextExtractionService extractor,
            ContentStorage storage,
            IngestionProperties properties,
            IndexingJobStore indexingJobs,
            IndexingProcessor indexingProcessor,
            OcrProcessor ocrProcessor
    ) {
        this.jobs = jobs;
        this.extractor = extractor;
        this.storage = storage;
        this.properties = properties;
        this.indexingJobs = indexingJobs;
        this.indexingProcessor = indexingProcessor;
        this.ocrProcessor = ocrProcessor;
    }

    @Override
    @Async("ingestionTaskExecutor")
    public void process(UUID ingestionJobId) {
        Optional<IngestionWorkItem> started = jobs.startAttempt(
                ingestionJobId,
                properties.maxAttempts()
        );
        if (started.isEmpty()) {
            return;
        }

        IngestionWorkItem work = started.orElseThrow();
        try (InputStream source = storage.open(work.sourceStorageKey())) {
            ExtractedText extracted = extractor.extract(
                    source,
                    work.originalFilename(),
                    work.detectedMediaType()
            );
            StoredContent stored = storage.storeExtracted(
                    work.workspaceId(),
                    work.documentVersionId(),
                    extracted.text()
            );
            try {
                jobs.complete(
                        ingestionJobId,
                        new ExtractedContentMetadata(
                                work.workspaceId(),
                                work.documentVersionId(),
                                stored.storageKey(),
                                extracted.mediaType(),
                                extracted.text().length(),
                                extracted.pageCount(),
                                extracted.parserName()
                        )
                );
            } catch (RuntimeException exception) {
                storage.delete(stored.storageKey());
                throw exception;
            }
            log.info("문서 파싱 완료: ingestionJobId={}, attempt={}", ingestionJobId, work.attemptCount());
            scheduleIndexing(work);
        } catch (OcrRequiredException exception) {
            if (jobs.queueOcr(ingestionJobId)) {
                log.info("PDF OCR 예약: ingestionJobId={}", ingestionJobId);
                ocrProcessor.process(ingestionJobId);
            }
        } catch (TextExtractionException exception) {
            fail(ingestionJobId, exception.errorCode(), exception);
        } catch (ContentStorageException exception) {
            fail(ingestionJobId, IngestionErrorCode.STORAGE_FAILURE, exception);
        } catch (Exception exception) {
            fail(ingestionJobId, IngestionErrorCode.INTERNAL_ERROR, exception);
        }
    }

    private void scheduleIndexing(IngestionWorkItem work) {
        try {
            indexingJobs.ensureJob(work.workspaceId(), work.documentVersionId());
            indexingProcessor.process(work.documentVersionId());
        } catch (RuntimeException exception) {
            log.warn(
                    "문서 색인 예약 실패: ingestionJobId={}, exceptionType={}",
                    work.ingestionJobId(),
                    exception.getClass().getSimpleName()
            );
        }
    }

    private void fail(UUID ingestionJobId, IngestionErrorCode code, Exception exception) {
        jobs.fail(ingestionJobId, code);
        log.warn(
                "문서 파싱 실패: ingestionJobId={}, errorCode={}, exceptionType={}",
                ingestionJobId,
                code,
                exception.getClass().getSimpleName()
        );
    }
}
