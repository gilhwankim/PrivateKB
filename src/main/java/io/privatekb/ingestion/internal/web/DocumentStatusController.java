package io.privatekb.ingestion.internal.web;

import io.privatekb.ingestion.internal.application.view.DocumentPageView;
import io.privatekb.ingestion.internal.application.DocumentStatusApplicationService;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class DocumentStatusController {

    private final DocumentStatusApplicationService documents;

    DocumentStatusController(DocumentStatusApplicationService documents) {
        this.documents = documents;
    }

    @GetMapping("/api/workspaces/{workspaceId}/documents")
    ResponseEntity<DocumentPageView> find(
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(documents.find(workspaceId, page, size));
    }
}
