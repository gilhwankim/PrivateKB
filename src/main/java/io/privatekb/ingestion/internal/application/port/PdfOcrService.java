package io.privatekb.ingestion.internal.application.port;

import io.privatekb.ingestion.internal.domain.ExtractedText;

import java.nio.file.Path;

public interface PdfOcrService {

    ExtractedText extract(Path source, String filename, String detectedMediaType);
}
