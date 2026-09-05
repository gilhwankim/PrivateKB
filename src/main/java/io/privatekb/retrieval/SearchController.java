package io.privatekb.retrieval;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/search")
class SearchController {

    private final SemanticSearchService search;

    SearchController(SemanticSearchService search) {
        this.search = search;
    }

    @PostMapping
    SearchResponse search(
            @PathVariable UUID workspaceId,
            @RequestBody SearchRequest request
    ) {
        return search.search(workspaceId, request.query(), request.limit());
    }
}
