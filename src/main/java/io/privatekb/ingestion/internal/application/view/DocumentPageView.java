package io.privatekb.ingestion.internal.application.view;

import java.util.List;

public record DocumentPageView(
        List<DocumentStatusItem> documents,
        long totalElements,
        int page,
        int size,
        boolean hasNext
) {
    public DocumentPageView {
        documents = List.copyOf(documents);
    }
}
