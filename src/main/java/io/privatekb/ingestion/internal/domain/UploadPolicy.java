package io.privatekb.ingestion.internal.domain;

import io.privatekb.ingestion.internal.config.IngestionProperties;

import io.privatekb.platform.LocalDocumentUploadPolicy;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public final class UploadPolicy {

    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cntrl}]");
    private static final Pattern WINDOWS_RESERVED_NAME = Pattern.compile(
            "(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$"
    );
    private static final Set<String> WEAK_DECLARED_TYPES = Set.of("application/octet-stream");
    private static final Map<String, UploadFormat> FORMATS = Map.ofEntries(
            Map.entry("pdf", new UploadFormat(
                    Set.of("application/pdf"),
                    Set.of("application/pdf")
            )),
            Map.entry("md", new UploadFormat(
                    Set.of("text/markdown", "text/x-markdown", "text/plain"),
                    Set.of("text/markdown", "text/x-markdown", "text/x-web-markdown", "text/plain")
            )),
            Map.entry("markdown", new UploadFormat(
                    Set.of("text/markdown", "text/x-markdown", "text/plain"),
                    Set.of("text/markdown", "text/x-markdown", "text/x-web-markdown", "text/plain")
            )),
            Map.entry("txt", new UploadFormat(
                    Set.of("text/plain"),
                    Set.of("text/plain")
            )),
            Map.entry("doc", new UploadFormat(
                    Set.of("application/msword"),
                    Set.of("application/msword")
            )),
            Map.entry("docx", new UploadFormat(
                    Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            )),
            Map.entry("ppt", new UploadFormat(
                    Set.of("application/vnd.ms-powerpoint"),
                    Set.of("application/vnd.ms-powerpoint")
            )),
            Map.entry("pptx", new UploadFormat(
                    Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                    Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation")
            )),
            Map.entry("xls", new UploadFormat(
                    Set.of("application/vnd.ms-excel"),
                    Set.of("application/vnd.ms-excel")
            )),
            Map.entry("xlsx", new UploadFormat(
                    Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                    Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            )),
            Map.entry("hwp", new UploadFormat(
                    Set.of(
                            "application/x-hwp",
                            "application/x-hwp-v5",
                            "application/haansofthwp",
                            "application/vnd.hancom.hwp"
                    ),
                    Set.of("application/x-hwp-v5")
            ))
    );

    private final IngestionProperties properties;
    private final LocalDocumentUploadPolicy runtimePolicy;

    public UploadPolicy(IngestionProperties properties, LocalDocumentUploadPolicy runtimePolicy) {
        this.properties = properties;
        this.runtimePolicy = runtimePolicy;
    }

    public long maximumUploadBytes() {
        return Math.min(properties.maxUploadBytes(), runtimePolicy.maximumUploadBytes());
    }

    public PreparedUpload validateRequest(String filename, String declaredMediaType, long byteSize) {
        if (byteSize <= 0) {
            throw new UploadRejectedException(UploadRejectionCode.EMPTY_FILE);
        }
        long maximumBytes = maximumUploadBytes();
        if (byteSize > maximumBytes) {
            throw new UploadRejectedException(UploadRejectionCode.FILE_TOO_LARGE);
        }

        String normalized = normalizeFilename(filename);
        String extension = extensionOf(normalized);
        UploadFormat format = FORMATS.get(extension);
        if (format == null) {
            throw new UploadRejectedException(UploadRejectionCode.UNSUPPORTED_EXTENSION);
        }

        String declared = normalizeDeclaredMediaType(declaredMediaType);
        if (!WEAK_DECLARED_TYPES.contains(declared) && !format.declaredTypes().contains(declared)) {
            throw new UploadRejectedException(UploadRejectionCode.UNSUPPORTED_MEDIA_TYPE);
        }
        return new PreparedUpload(normalized, extension, declared, format, maximumBytes);
    }

    public void requireCompatibleDetection(PreparedUpload upload, String detectedMediaType) {
        String detected = normalizeMediaType(detectedMediaType);
        if (!upload.format().detectedTypes().contains(detected)) {
            throw new UploadRejectedException(UploadRejectionCode.MEDIA_TYPE_MISMATCH);
        }
    }

    private String normalizeFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255) {
            throw new UploadRejectedException(UploadRejectionCode.INVALID_FILENAME);
        }
        String normalized = Normalizer.normalize(filename, Normalizer.Form.NFKC);
        if (normalized.contains("/") || normalized.contains("\\") || normalized.contains(":")) {
            throw new UploadRejectedException(UploadRejectionCode.INVALID_FILENAME);
        }
        if (normalized.equals(".") || normalized.equals("..") || normalized.contains("..")
                || normalized.endsWith(".") || normalized.endsWith(" ")
                || CONTROL_CHARACTER.matcher(normalized).find()
                || WINDOWS_RESERVED_NAME.matcher(normalized).matches()) {
            throw new UploadRejectedException(UploadRejectionCode.INVALID_FILENAME);
        }
        return normalized;
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            throw new UploadRejectedException(UploadRejectionCode.UNSUPPORTED_EXTENSION);
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            throw new UploadRejectedException(UploadRejectionCode.UNSUPPORTED_MEDIA_TYPE);
        }
        int parameter = mediaType.indexOf(';');
        String value = parameter >= 0 ? mediaType.substring(0, parameter) : mediaType;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDeclaredMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return "application/octet-stream";
        }
        return normalizeMediaType(mediaType);
    }

    public record PreparedUpload(
            String normalizedFilename,
            String extension,
            String declaredMediaType,
            UploadFormat format,
            long maximumUploadBytes
    ) {
    }

    public record UploadFormat(Set<String> declaredTypes, Set<String> detectedTypes) {
    }
}
