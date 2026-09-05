package io.privatekb.platform.internal.storage;

import io.privatekb.platform.ContentStorage;
import io.privatekb.platform.ContentStorageException;
import io.privatekb.platform.InsufficientStorageException;
import io.privatekb.platform.StorageProperties;
import io.privatekb.platform.StoredContent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class LocalContentStorage implements ContentStorage {

    private static final int BUFFER_SIZE = 16 * 1024;
    private final Path root;
    private final long minimumFreeBytes;
    private final Object reservationMonitor = new Object();
    private long reservedSourceBytes;

    public LocalContentStorage(StorageProperties properties) {
        Objects.requireNonNull(properties, "storage properties");
        if (properties.root() == null) {
            throw new IllegalArgumentException("Storage root must be configured");
        }
        this.root = properties.root().toAbsolutePath().normalize();
        this.minimumFreeBytes = properties.minimumFreeBytes();
        try {
            Files.createDirectories(root);
            rejectSymbolicLink(root);
        } catch (IOException exception) {
            throw new ContentStorageException("Unable to initialize the local content store", exception);
        }
    }

    @Override
    public StoredContent storeSource(
            UUID workspaceId,
            UUID documentVersionId,
            InputStream source,
            long expectedBytes,
            long maximumBytes
    ) {
        if (expectedBytes <= 0 || expectedBytes > maximumBytes) {
            throw new IllegalArgumentException("Expected source size is outside the configured limit");
        }
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("Maximum source size must be positive");
        }
        try (StorageReservation ignored = reserveSourceBytes(expectedBytes)) {
            return write(
                    key(workspaceId, documentVersionId, "source.bin"),
                    Objects.requireNonNull(source, "source"),
                    maximumBytes,
                    false
            );
        }
    }

    @Override
    public StoredContent storeExtracted(UUID workspaceId, UUID documentVersionId, String text) {
        byte[] bytes = Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8);
        return write(
                key(workspaceId, documentVersionId, "extracted.txt"),
                new java.io.ByteArrayInputStream(bytes),
                Math.max(1L, bytes.length),
                true
        );
    }

    @Override
    public InputStream open(String storageKey) {
        Path target = requireRegularFile(storageKey);
        try {
            return Files.newInputStream(target, StandardOpenOption.READ);
        } catch (IOException exception) {
            throw new ContentStorageException("Unable to open stored content", exception);
        }
    }

    @Override
    public String sha256(String storageKey) {
        MessageDigest digest = sha256();
        try (InputStream input = open(storageKey)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new ContentStorageException("Unable to hash stored content", exception);
        }
    }

    @Override
    public <T> T withLocalFile(String storageKey, Function<Path, T> action) {
        Path target = requireRegularFile(storageKey);
        return Objects.requireNonNull(action, "action").apply(target);
    }

    @Override
    public void delete(String storageKey) {
        Path target = resolveKey(storageKey);
        try {
            rejectSymbolicLinksBetweenRootAnd(target.getParent());
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new ContentStorageException("Unable to delete stored content", exception);
        }
    }

    private StoredContent write(String storageKey, InputStream source, long maximumBytes, boolean replace) {
        Path target = resolveKey(storageKey);
        Path directory = target.getParent();
        Path temporary = directory.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        MessageDigest digest = sha256();
        long total = 0;

        try {
            Files.createDirectories(directory);
            rejectSymbolicLinksBetweenRootAnd(directory);

            try (InputStream input = source;
                 OutputStream output = Files.newOutputStream(
                         temporary,
                         StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE
                 )) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maximumBytes) {
                        throw new ContentStorageException("Stored content exceeds the configured byte limit");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }

            moveAtomically(temporary, target, replace);
            return new StoredContent(storageKey, total, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException exception) {
            if (usableBytes() < minimumFreeBytes) {
                throw new InsufficientStorageException("Insufficient disk space for managed content", exception);
            }
            throw new ContentStorageException("Unable to write stored content", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The server-generated temporary path remains inside the configured storage root.
            }
        }
    }

    private StorageReservation reserveSourceBytes(long expectedBytes) {
        synchronized (reservationMonitor) {
            long usable = usableBytes();
            long availableAfterReserve = usable > minimumFreeBytes
                    ? usable - minimumFreeBytes
                    : 0;
            if (reservedSourceBytes > availableAfterReserve
                    || expectedBytes > availableAfterReserve - reservedSourceBytes) {
                throw new InsufficientStorageException("Insufficient disk space for managed content");
            }
            reservedSourceBytes = Math.addExact(reservedSourceBytes, expectedBytes);
            return new StorageReservation(expectedBytes);
        }
    }

    private long usableBytes() {
        try {
            return Files.getFileStore(root).getUsableSpace();
        } catch (IOException exception) {
            throw new ContentStorageException("Unable to inspect storage capacity", exception);
        }
    }

    private final class StorageReservation implements AutoCloseable {

        private final long bytes;
        private boolean closed;

        private StorageReservation(long bytes) {
            this.bytes = bytes;
        }

        @Override
        public void close() {
            synchronized (reservationMonitor) {
                if (!closed) {
                    reservedSourceBytes -= bytes;
                    closed = true;
                }
            }
        }
    }

    private void moveAtomically(Path source, Path target, boolean replace) throws IOException {
        StandardCopyOption[] options = replace
                ? new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE};
        try {
            Files.move(source, target, options);
        } catch (AtomicMoveNotSupportedException exception) {
            if (replace) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    private Path resolveKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new ContentStorageException("Storage key must not be blank");
        }
        Path relative = Path.of(storageKey).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new ContentStorageException("Storage key escapes the configured root");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new ContentStorageException("Storage key escapes the configured root");
        }
        return resolved;
    }

    private Path requireRegularFile(String storageKey) {
        Path target = resolveKey(storageKey);
        try {
            rejectSymbolicLinksBetweenRootAnd(target.getParent());
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ContentStorageException("Stored content is missing or is not a regular file");
            }
            return target;
        } catch (IOException exception) {
            throw new ContentStorageException("Unable to validate stored content", exception);
        }
    }

    private String key(UUID workspaceId, UUID documentVersionId, String filename) {
        return Objects.requireNonNull(workspaceId, "workspaceId") + "/"
                + Objects.requireNonNull(documentVersionId, "documentVersionId") + "/" + filename;
    }

    private void rejectSymbolicLinksBetweenRootAnd(Path targetDirectory) throws IOException {
        rejectSymbolicLink(root);
        Path current = root;
        for (Path segment : root.relativize(targetDirectory)) {
            current = current.resolve(segment);
            rejectSymbolicLink(current);
        }
    }

    private void rejectSymbolicLink(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new ContentStorageException("Symbolic links are forbidden in the content store");
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
