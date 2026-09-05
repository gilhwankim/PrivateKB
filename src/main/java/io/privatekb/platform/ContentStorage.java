package io.privatekb.platform;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.UUID;

public interface ContentStorage {

    StoredContent storeSource(
            UUID workspaceId,
            UUID documentVersionId,
            InputStream source,
            long expectedBytes,
            long maximumBytes
    );

    StoredContent storeExtracted(UUID workspaceId, UUID documentVersionId, String text);

    InputStream open(String storageKey);

    String sha256(String storageKey);

    <T> T withLocalFile(String storageKey, Function<Path, T> action);

    void delete(String storageKey);
}
