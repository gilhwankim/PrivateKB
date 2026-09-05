package io.privatekb.platform;

import java.util.List;

public interface LocalEmbeddingClient {

    EmbeddingModelInfo verifyModel();

    List<float[]> embed(List<String> texts);

    record EmbeddingModelInfo(
            String model,
            String digest,
            int dimensions
    ) {
    }
}
