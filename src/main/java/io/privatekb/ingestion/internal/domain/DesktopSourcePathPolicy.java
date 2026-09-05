package io.privatekb.ingestion.internal.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
public final class DesktopSourcePathPolicy {

    public PreparedSource prepare(
            String originalFilename,
            String sourcePath,
            String sourceRootPath,
            String relativePath,
            long expectedByteSize,
            long expectedLastModifiedMillis
    ) {
        try {
            Path source = requireLocalAbsolutePath(sourcePath);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(source)
                    || source.getFileName() == null
                    || !normalizedFilename(source).equals(normalizeText(originalFilename))) {
                throw new DesktopImportException(DesktopImportErrorCode.INVALID_SOURCE_LOCATION);
            }
            long currentSize = Files.size(source);
            long currentModified = Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS).toMillis();
            if (currentSize != expectedByteSize || currentModified != expectedLastModifiedMillis) {
                throw new DesktopImportException(DesktopImportErrorCode.SOURCE_FILE_CHANGED);
            }

            Path root = null;
            String normalizedRelativePath = null;
            if (sourceRootPath != null && !sourceRootPath.isBlank()) {
                root = requireLocalAbsolutePath(sourceRootPath);
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(root)
                        || !source.startsWith(root)) {
                    throw new DesktopImportException(DesktopImportErrorCode.INVALID_SOURCE_LOCATION);
                }
                Path actualRelative = root.relativize(source).normalize();
                if (actualRelative.isAbsolute()
                        || actualRelative.startsWith("..")
                        || relativePath == null
                        || !normalizeSeparators(actualRelative.toString())
                        .equals(normalizeSeparators(relativePath))) {
                    throw new DesktopImportException(DesktopImportErrorCode.INVALID_SOURCE_LOCATION);
                }
                normalizedRelativePath = normalizeSeparators(actualRelative.toString());
            } else if (relativePath != null && !relativePath.isBlank()) {
                throw new DesktopImportException(DesktopImportErrorCode.INVALID_SOURCE_LOCATION);
            }

            String normalizedSource = normalizeSeparators(source.toString());
            String normalizedRoot = root == null ? null : normalizeSeparators(root.toString());
            return new PreparedSource(
                    normalizedSource,
                    hashPath(normalizedSource),
                    normalizedRoot,
                    normalizedRoot == null ? null : hashPath(normalizedRoot),
                    normalizedRelativePath,
                    currentSize,
                    Instant.ofEpochMilli(currentModified)
            );
        } catch (InvalidPathException | IOException exception) {
            throw new DesktopImportException(DesktopImportErrorCode.INVALID_SOURCE_LOCATION);
        }
    }

    private Path requireLocalAbsolutePath(String value) {
        if (value == null || value.isBlank()) {
            throw new DesktopImportException(DesktopImportErrorCode.INVALID_SOURCE_LOCATION);
        }
        String normalized = normalizeSeparators(value.trim());
        if (normalized.startsWith("\\\\")
                || normalized.startsWith("\\\\?\\")
                || normalized.startsWith("\\\\.\\")) {
            throw new DesktopImportException(DesktopImportErrorCode.INVALID_SOURCE_LOCATION);
        }
        Path path = Path.of(normalized).normalize();
        if (!path.isAbsolute() || path.toString().contains("\u0000")) {
            throw new DesktopImportException(DesktopImportErrorCode.INVALID_SOURCE_LOCATION);
        }
        return path;
    }

    private String normalizedFilename(Path source) {
        return normalizeText(source.getFileName().toString());
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC);
    }

    private String normalizeSeparators(String value) {
        return Normalizer.normalize(value.replace('/', '\\'), Normalizer.Form.NFKC);
    }

    private String hashPath(String path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = path.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    public record PreparedSource(
            String sourcePath,
            String sourcePathHash,
            String sourceRootPath,
            String sourceRootPathHash,
            String relativePath,
            long byteSize,
            Instant lastModifiedAt
    ) {
    }
}
