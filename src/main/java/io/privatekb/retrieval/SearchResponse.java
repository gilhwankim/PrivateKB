package io.privatekb.retrieval;

import java.util.List;

public record SearchResponse(List<SearchResultView> results) {

    public SearchResponse {
        results = List.copyOf(results);
    }
}
