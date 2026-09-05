package io.privatekb.retrieval;

import io.privatekb.knowledge.DocumentCatalog;
import io.privatekb.platform.LocalEmbeddingClient;
import io.privatekb.platform.LocalEmbeddingException;
import io.privatekb.platform.LocalEmbeddingClient.EmbeddingModelInfo;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class SemanticSearchService {

    private final DocumentCatalog documents;
    private final LocalEmbeddingClient embeddings;
    private final SemanticSearchRepository repository;
    private final SearchProperties properties;

    SemanticSearchService(
            DocumentCatalog documents,
            LocalEmbeddingClient embeddings,
            SemanticSearchRepository repository,
            SearchProperties properties
    ) {
        this.documents = documents;
        this.embeddings = embeddings;
        this.repository = repository;
        this.properties = properties;
    }

    public SearchResponse search(UUID workspaceId, String rawQuery, Integer requestedLimit) {
        if (workspaceId == null || !documents.workspaceExists(workspaceId)) {
            throw new SearchRejectedException("WORKSPACE_NOT_FOUND");
        }
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.length() < properties.minimumQueryCharacters()
                || query.length() > properties.maximumQueryCharacters()) {
            throw new SearchRejectedException("INVALID_QUERY_LENGTH");
        }
        int limit = requestedLimit == null ? 5 : requestedLimit;
        if (limit < 1 || limit > properties.maximumResults()) {
            throw new SearchRejectedException("INVALID_RESULT_LIMIT");
        }

        try {
            EmbeddingModelInfo model = embeddings.verifyModel();
            List<float[]> vectors = embeddings.embed(List.of(RetrievalQuery.format(query)));
            return new SearchResponse(repository.search(
                    workspaceId,
                    query,
                    RetrievalQuery.filenameHint(query),
                    RetrievalQuery.extensionHint(query),
                    vectors.getFirst(),
                    model,
                    properties.candidateLimit(limit),
                    properties.minimumResultScore(),
                    1,
                    limit
            ));
        } catch (LocalEmbeddingException exception) {
            throw new SearchUnavailableException(codeFor(exception.reason()), exception);
        }
    }

    private String codeFor(LocalEmbeddingException.Reason reason) {
        return switch (reason) {
            case OLLAMA_NOT_RUNNING -> "OLLAMA_NOT_RUNNING";
            case MODEL_NOT_AVAILABLE -> "EMBEDDING_MODEL_NOT_AVAILABLE";
            case MODEL_INCOMPATIBLE -> "EMBEDDING_MODEL_INCOMPATIBLE";
            case REQUEST_FAILED -> "EMBEDDING_REQUEST_FAILED";
        };
    }
}
