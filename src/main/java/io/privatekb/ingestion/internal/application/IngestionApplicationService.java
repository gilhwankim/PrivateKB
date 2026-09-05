package io.privatekb.ingestion.internal.application;

import io.privatekb.ingestion.internal.application.port.ContentTypeDetector;
import io.privatekb.ingestion.internal.application.port.IngestionJobStore;
import io.privatekb.ingestion.internal.application.port.IngestionProcessor;
import io.privatekb.ingestion.internal.config.IngestionProperties;
import io.privatekb.ingestion.internal.domain.IngestionStatus;
import io.privatekb.ingestion.internal.application.view.IngestionView;
import io.privatekb.ingestion.internal.application.command.UploadDocumentCommand;
import io.privatekb.ingestion.internal.application.view.UploadDocumentResult;
import io.privatekb.ingestion.internal.domain.UploadPolicy;
import io.privatekb.ingestion.internal.domain.UploadRejectedException;
import io.privatekb.ingestion.internal.domain.UploadRejectionCode;

import io.privatekb.knowledge.DocumentCatalog;
import io.privatekb.knowledge.DocumentCatalog.DocumentVersionRef;
import io.privatekb.knowledge.DocumentCatalog.DocumentVersionRegistration;
import io.privatekb.platform.ContentStorage;
import io.privatekb.platform.InsufficientStorageException;
import io.privatekb.platform.StoredContent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IngestionApplicationService {

    private final UploadPolicy uploadPolicy;
    private final ContentTypeDetector contentTypeDetector;
    private final ContentStorage storage;
    private final DocumentCatalog documents;
    private final IngestionJobStore jobs;
    private final IngestionProcessor processor;
    private final IngestionProperties properties;
    private final TransactionTemplate transactions;

    public IngestionApplicationService(
            UploadPolicy uploadPolicy,
            ContentTypeDetector contentTypeDetector,
            ContentStorage storage,
            DocumentCatalog documents,
            IngestionJobStore jobs,
            IngestionProcessor processor,
            IngestionProperties properties,
            TransactionTemplate transactions
    ) {
        this.uploadPolicy = uploadPolicy;
        this.contentTypeDetector = contentTypeDetector;
        this.storage = storage;
        this.documents = documents;
        this.jobs = jobs;
        this.processor = processor;
        this.properties = properties;
        this.transactions = transactions;
    }

    public UploadDocumentResult submit(UploadDocumentCommand command) {
        if (command == null || command.workspaceId() == null || command.source() == null) {
            throw new UploadRejectedException(UploadRejectionCode.INVALID_FILENAME);
        }
        if (!documents.workspaceExists(command.workspaceId())) {
            throw new UploadRejectedException(UploadRejectionCode.WORKSPACE_NOT_FOUND);
        }

        UploadPolicy.PreparedUpload prepared = uploadPolicy.validateRequest(
                command.originalFilename(),
                command.declaredMediaType(),
                command.byteSize()
        );
        String detected = contentTypeDetector.detect(command.source(), prepared.normalizedFilename());
        uploadPolicy.requireCompatibleDetection(prepared, detected);

        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        StoredContent source = storeSource(command, versionId, prepared.maximumUploadBytes());
        if (source.byteSize() != command.byteSize()) {
            storage.delete(source.storageKey());
            throw new UploadRejectedException(UploadRejectionCode.SIZE_MISMATCH);
        }

        Optional<DocumentVersionRef> existing = documents.findVersionByHash(
                command.workspaceId(),
                source.sha256()
        );
        if (existing.isPresent()) {
            storage.delete(source.storageKey());
            return duplicateResult(existing.orElseThrow());
        }

        DocumentVersionRef registered;
        try {
            registered = transactions.execute(status -> {
                DocumentVersionRef version = documents.createVersion(new DocumentVersionRegistration(
                        command.workspaceId(),
                        documentId,
                        versionId,
                        prepared.normalizedFilename(),
                        prepared.declaredMediaType(),
                        detected,
                        source.byteSize(),
                        source.sha256(),
                        source.storageKey()
                ));
                jobs.create(jobId, version);
                return version;
            });
        } catch (DuplicateKeyException exception) {
            storage.delete(source.storageKey());
            DocumentVersionRef duplicate = documents.findVersionByHash(
                            command.workspaceId(),
                            source.sha256()
                    )
                    .orElseThrow(() -> exception);
            return duplicateResult(duplicate);
        } catch (RuntimeException exception) {
            storage.delete(source.storageKey());
            throw exception;
        }

        if (registered == null) {
            storage.delete(source.storageKey());
            throw new IllegalStateException("Document registration returned no result");
        }
        processor.process(jobId);
        return new UploadDocumentResult(
                command.workspaceId(),
                documentId,
                versionId,
                jobId,
                IngestionStatus.STORED,
                false
        );
    }

    public IngestionView find(UUID ingestionJobId) {
        return jobs.find(ingestionJobId)
                .orElseThrow(() -> new UploadRejectedException(UploadRejectionCode.JOB_NOT_FOUND));
    }

    public IngestionView retry(UUID ingestionJobId) {
        IngestionView current = find(ingestionJobId);
        if ((current.status() != IngestionStatus.FAILED && current.status() != IngestionStatus.STORED)
                || current.attemptCount() >= properties.maxAttempts()) {
            throw new UploadRejectedException(UploadRejectionCode.RETRY_NOT_ALLOWED);
        }
        processor.process(ingestionJobId);
        return current;
    }

    public IngestionView retryDocumentVersion(UUID documentVersionId) {
        UUID ingestionJobId = jobs.findJobIdByVersion(documentVersionId)
                .orElseThrow(() -> new UploadRejectedException(UploadRejectionCode.JOB_NOT_FOUND));
        return retry(ingestionJobId);
    }

    private StoredContent storeSource(UploadDocumentCommand command, UUID versionId, long maximumBytes) {
        try {
            InputStream input = command.source().openStream();
            return storage.storeSource(
                    command.workspaceId(),
                    versionId,
                    input,
                    command.byteSize(),
                    maximumBytes
            );
        } catch (InsufficientStorageException exception) {
            throw new UploadRejectedException(UploadRejectionCode.INSUFFICIENT_STORAGE);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read uploaded content", exception);
        }
    }

    private UploadDocumentResult duplicateResult(DocumentVersionRef duplicate) {
        UUID existingJob = jobs.findJobIdByVersion(duplicate.documentVersionId())
                .orElseThrow(() -> new IllegalStateException("Duplicate version has no ingestion job"));
        IngestionView view = find(existingJob);
        return new UploadDocumentResult(
                duplicate.workspaceId(),
                duplicate.documentId(),
                duplicate.documentVersionId(),
                existingJob,
                view.status(),
                true
        );
    }
}
