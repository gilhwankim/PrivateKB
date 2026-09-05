package io.privatekb.ingestion.internal.application;

import io.privatekb.ingestion.internal.application.view.DocumentPageView;
import io.privatekb.ingestion.internal.application.view.DocumentStatusItem;
import io.privatekb.ingestion.internal.application.port.DocumentStatusQuery;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public final class DocumentStatusApplicationService {

    private static final int MAXIMUM_PAGE_SIZE = 100;

    private final DocumentStatusQuery documents;

    public DocumentStatusApplicationService(DocumentStatusQuery documents) {
        this.documents = documents;
    }

    public DocumentPageView find(UUID workspaceId, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        int offset = Math.multiplyExact(page, size);
        long total = documents.count(workspaceId);
        List<DocumentStatusItem> items = documents.findLatest(workspaceId, offset, size);
        return new DocumentPageView(items, total, page, size, offset + items.size() < total);
    }
}
