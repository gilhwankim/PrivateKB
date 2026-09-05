package io.privatekb.ingestion.internal.web;

import io.privatekb.ingestion.internal.application.IndexingApplicationService;
import io.privatekb.ingestion.internal.application.view.IndexingView;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/indexing")
class IndexingController {

    private final IndexingApplicationService indexing;

    IndexingController(IndexingApplicationService indexing) {
        this.indexing = indexing;
    }

    @GetMapping("/{documentVersionId}")
    IndexingView status(@PathVariable UUID documentVersionId) {
        return indexing.find(documentVersionId);
    }

    @PostMapping("/{documentVersionId}/retry")
    ResponseEntity<IndexingView> retry(@PathVariable UUID documentVersionId) {
        return ResponseEntity.accepted().body(indexing.retry(documentVersionId));
    }

    @PostMapping("/resume")
    ResponseEntity<IndexingResumeResponse> resume() {
        return ResponseEntity.accepted().body(
                new IndexingResumeResponse(indexing.resumeAvailable())
        );
    }

    record IndexingResumeResponse(int scheduledJobs) {
    }
}
