package io.privatekb.ingestion.internal.application.port;

import io.privatekb.ingestion.internal.application.view.DocumentStatusItem;

import java.util.List;
import java.util.UUID;

public interface DocumentStatusQuery {

    long count(UUID workspaceId);

    List<DocumentStatusItem> findLatest(UUID workspaceId, int offset, int limit);
}
