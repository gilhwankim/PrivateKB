package io.privatekb.platform.internal.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.privatekb.platform.ContentStorageException;
import io.privatekb.platform.StorageProperties;
import io.privatekb.platform.StoredContent;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalContentStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesContentUnderServerGeneratedPathAndCalculatesSha256() throws Exception {
        LocalContentStorage storage = new LocalContentStorage(new StorageProperties(temporaryDirectory, 0));
        UUID workspaceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID versionId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        byte[] content = "합성 문서 본문".getBytes(StandardCharsets.UTF_8);

        StoredContent stored = storage.storeSource(
                workspaceId,
                versionId,
                new ByteArrayInputStream(content),
                content.length,
                1024
        );

        assertThat(stored.storageKey()).isEqualTo(workspaceId + "/" + versionId + "/source.bin");
        assertThat(stored.byteSize()).isEqualTo(content.length);
        assertThat(stored.sha256()).hasSize(64);
        assertThat(storage.sha256(stored.storageKey())).isEqualTo(stored.sha256());
        Boolean localFileExists = storage.withLocalFile(
                stored.storageKey(),
                path -> java.nio.file.Files.isRegularFile(path)
        );
        assertThat(localFileExists).isTrue();
        try (InputStream input = storage.open(stored.storageKey())) {
            assertThat(input.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void rejectsStorageKeysThatEscapeTheConfiguredRoot() {
        LocalContentStorage storage = new LocalContentStorage(new StorageProperties(temporaryDirectory, 0));

        assertThatThrownBy(() -> storage.open("../outside.txt"))
                .isInstanceOf(ContentStorageException.class);
    }

    @Test
    void enforcesTheByteLimitWhileStreaming() {
        LocalContentStorage storage = new LocalContentStorage(new StorageProperties(temporaryDirectory, 0));

        assertThatThrownBy(() -> storage.storeSource(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ByteArrayInputStream(new byte[11]),
                10,
                10
        )).isInstanceOf(ContentStorageException.class);
    }

    @Test
    void rejectsSourceBeforeCopyWhenConfiguredFreeSpaceCannotBePreserved() {
        long unavailableReserve = temporaryDirectory.toFile().getUsableSpace() + 1;
        LocalContentStorage storage = new LocalContentStorage(
                new StorageProperties(temporaryDirectory, unavailableReserve)
        );

        assertThatThrownBy(() -> storage.storeSource(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ByteArrayInputStream(new byte[1]),
                1,
                10
        )).isInstanceOf(io.privatekb.platform.InsufficientStorageException.class);
    }
}
