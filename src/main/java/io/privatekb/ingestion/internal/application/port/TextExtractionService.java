package io.privatekb.ingestion.internal.application.port;

import io.privatekb.ingestion.internal.domain.ExtractedText;

import java.io.InputStream;

public interface TextExtractionService {

    ExtractedText extract(InputStream source, String filename, String detectedMediaType);
}
