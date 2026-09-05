package io.privatekb.ingestion.internal.application;

import io.privatekb.ingestion.internal.application.command.DesktopDocumentImportCommand;
import io.privatekb.ingestion.internal.domain.DesktopSourcePathPolicy;
import io.privatekb.ingestion.internal.application.command.UploadDocumentCommand;
import io.privatekb.ingestion.internal.application.view.UploadDocumentResult;

import io.privatekb.ingestion.internal.domain.DesktopSourcePathPolicy.PreparedSource;
import io.privatekb.knowledge.DocumentSourceCatalog;
import io.privatekb.knowledge.DocumentSourceCatalog.DocumentSourceRegistration;

import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public final class DesktopDocumentImportService {

    private final IngestionApplicationService ingestion;
    private final DesktopSourcePathPolicy sourcePathPolicy;
    private final DocumentSourceCatalog sourceLocations;

    public DesktopDocumentImportService(
            IngestionApplicationService ingestion,
            DesktopSourcePathPolicy sourcePathPolicy,
            DocumentSourceCatalog sourceLocations
    ) {
        this.ingestion = ingestion;
        this.sourcePathPolicy = sourcePathPolicy;
        this.sourceLocations = sourceLocations;
    }

    public UploadDocumentResult submit(DesktopDocumentImportCommand command) {
        PreparedSource source = sourcePathPolicy.prepare(
                command.originalFilename(),
                command.sourcePath(),
                command.sourceRootPath(),
                command.relativePath(),
                command.byteSize(),
                command.lastModifiedMillis()
        );
        UploadDocumentResult result = ingestion.submit(new UploadDocumentCommand(
                command.workspaceId(),
                command.originalFilename(),
                command.declaredMediaType(),
                command.byteSize(),
                command.source()
        ));
        sourceLocations.register(new DocumentSourceRegistration(
                command.workspaceId(),
                result.documentVersionId(),
                source.sourcePath(),
                source.sourcePathHash(),
                source.sourceRootPath(),
                source.sourceRootPathHash(),
                source.relativePath(),
                source.byteSize(),
                source.lastModifiedAt()
        ));
        return result;
    }
}
