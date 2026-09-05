package io.privatekb.ingestion.internal.parsing;

import io.privatekb.ingestion.internal.domain.ExtractedText;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore;
import io.privatekb.ingestion.internal.application.port.IndexingProcessor;
import io.privatekb.ingestion.internal.domain.IngestionErrorCode;
import io.privatekb.ingestion.internal.application.port.IngestionJobStore;
import io.privatekb.ingestion.internal.application.port.IngestionJobStore.ExtractedContentMetadata;
import io.privatekb.ingestion.internal.application.port.IngestionJobStore.IngestionWorkItem;
import io.privatekb.ingestion.internal.application.port.OcrProcessor;
import io.privatekb.ingestion.internal.config.OcrProperties;
import io.privatekb.ingestion.internal.application.port.PdfOcrService;
import io.privatekb.ingestion.internal.domain.TextExtractionException;
import io.privatekb.platform.ContentStorage;
import io.privatekb.platform.ContentStorageException;
import io.privatekb.platform.StoredContent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
class AsyncOcrProcessor implements OcrProcessor {

    private static final Logger log = LoggerFactory.getLogger(AsyncOcrProcessor.class);

    private final IngestionJobStore jobs;
    private final PdfOcrService ocr;
    private final ContentStorage storage;
    private final IndexingJobStore indexingJobs;
    private final IndexingProcessor indexingProcessor;
    private final OcrProperties properties;
    private final Executor ocrTaskExecutor;

    AsyncOcrProcessor(
            IngestionJobStore jobs,
            PdfOcrService ocr,
            ContentStorage storage,
            IndexingJobStore indexingJobs,
            IndexingProcessor indexingProcessor,
            OcrProperties properties,
            @Qualifier("ocrTaskExecutor") Executor ocrTaskExecutor
    ) {
        this.jobs = jobs;
        this.ocr = ocr;
        this.storage = storage;
        this.indexingJobs = indexingJobs;
        this.indexingProcessor = indexingProcessor;
        this.properties = properties;
        this.ocrTaskExecutor = ocrTaskExecutor;
    }

    @Override
    @Async("ocrTaskExecutor")
    public void process(UUID ingestionJobId) {
        Optional<IngestionWorkItem> started = jobs.startOcr(ingestionJobId);
        if (started.isEmpty()) {
            return;
        }
        IngestionWorkItem work = started.orElseThrow();
        try {
            ExtractedText extracted = storage.withLocalFile(
                    work.sourceStorageKey(),
                    source -> ocr.extract(
                            source,
                            work.originalFilename(),
                            work.detectedMediaType()
                    )
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
            log.info(
                    "PDF OCR 완료: ingestionJobId={}, documentVersionId={}",
                    ingestionJobId,
                    work.documentVersionId()
            );
            scheduleIndexing(work);
        } catch (TextExtractionException exception) {
            fail(ingestionJobId, exception.errorCode(), exception);
        } catch (ContentStorageException exception) {
            fail(ingestionJobId, IngestionErrorCode.STORAGE_FAILURE, exception);
        } catch (Exception exception) {
            fail(ingestionJobId, IngestionErrorCode.INTERNAL_ERROR, exception);
        }
    }

    @Override
    public int resumeAvailable() {
        List<UUID> jobIds = jobs.recoverOcrJobs(properties.resumeBatchSize());
        jobIds.forEach(jobId -> ocrTaskExecutor.execute(() -> process(jobId)));
        return jobIds.size();
    }

    private void scheduleIndexing(IngestionWorkItem work) {
        try {
            indexingJobs.ensureJob(work.workspaceId(), work.documentVersionId());
            indexingProcessor.process(work.documentVersionId());
        } catch (RuntimeException exception) {
            log.warn(
                    "OCR 문서 색인 예약 실패: ingestionJobId={}, exceptionType={}",
                    work.ingestionJobId(),
                    exception.getClass().getSimpleName()
            );
        }
    }

    private void fail(UUID ingestionJobId, IngestionErrorCode code, Exception exception) {
        jobs.fail(ingestionJobId, code);
        log.warn(
                "PDF OCR 실패: ingestionJobId={}, errorCode={}, exceptionType={}",
                ingestionJobId,
                code,
                exception.getClass().getSimpleName()
        );
    }
}
