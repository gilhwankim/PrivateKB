package io.privatekb.ingestion.internal.web;

import io.privatekb.ingestion.internal.application.IngestionApplicationService;
import io.privatekb.ingestion.internal.application.view.IngestionView;
import io.privatekb.ingestion.internal.application.command.UploadDocumentCommand;
import io.privatekb.ingestion.internal.application.view.UploadDocumentResult;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
class IngestionController {

    private final IngestionApplicationService ingestion;

    IngestionController(IngestionApplicationService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping(
            path = "/workspaces/{workspaceId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<UploadDocumentResult> upload(
            @PathVariable UUID workspaceId,
            @RequestPart("file") MultipartFile file
    ) {
        UploadDocumentResult result = ingestion.submit(new UploadDocumentCommand(
                workspaceId,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file::getInputStream
        ));
        URI location = URI.create("/api/ingestions/" + result.ingestionJobId());
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).location(location).body(result);
    }

    @GetMapping(
            path = "/ingestions/{ingestionJobId}",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    IngestionView status(@PathVariable UUID ingestionJobId) {
        return ingestion.find(ingestionJobId);
    }

    @PostMapping(
            path = "/ingestions/{ingestionJobId}/retry",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<IngestionView> retry(@PathVariable UUID ingestionJobId) {
        IngestionView view = ingestion.retry(ingestionJobId);
        return ResponseEntity.accepted()
                .location(URI.create("/api/ingestions/" + ingestionJobId))
                .body(view);
    }

    @PostMapping(
            path = "/document-versions/{documentVersionId}/ingestion/retry",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<IngestionView> retryDocumentVersion(@PathVariable UUID documentVersionId) {
        IngestionView view = ingestion.retryDocumentVersion(documentVersionId);
        return ResponseEntity.accepted()
                .location(URI.create("/api/ingestions/" + view.ingestionJobId()))
                .body(view);
    }
}
