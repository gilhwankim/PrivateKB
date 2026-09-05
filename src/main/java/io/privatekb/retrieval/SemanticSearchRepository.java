package io.privatekb.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.privatekb.platform.LocalEmbeddingClient.EmbeddingModelInfo;

public interface SemanticSearchRepository {

    List<SearchResultView> search(
            UUID workspaceId,
            String query,
            String filenameHint,
            String extensionHint,
            String folderHint,
            Instant uploadedFrom,
            Instant uploadedToExclusive,
            boolean sortByUploadedAtDescending,
            float[] queryEmbedding,
            EmbeddingModelInfo model,
            int candidateLimit,
            double minimumScore,
            int perDocumentLimit,
            int limit
    );

    default List<SearchResultView> search(
            UUID workspaceId,
            String query,
            String filenameHint,
            String extensionHint,
            float[] queryEmbedding,
            EmbeddingModelInfo model,
            int candidateLimit,
            double minimumScore,
            int perDocumentLimit,
            int limit
    ) {
        return search(
                workspaceId,
                query,
                filenameHint,
                extensionHint,
                "",
                null,
                null,
                false,
                queryEmbedding,
                model,
                candidateLimit,
                minimumScore,
                perDocumentLimit,
                limit
        );
    }

    List<SearchResultView> searchByUploadTime(
            UUID workspaceId,
            String extensionHint,
            Instant uploadedFrom,
            Instant uploadedToExclusive,
            int limit
    );

    List<SearchResultView> searchByFolder(
            UUID workspaceId,
            String folderHint,
            int limit
    );
}
