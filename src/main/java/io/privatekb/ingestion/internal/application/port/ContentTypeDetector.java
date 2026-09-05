package io.privatekb.ingestion.internal.application.port;

import io.privatekb.ingestion.internal.domain.UploadSource;

public interface ContentTypeDetector {

    String detect(UploadSource source, String filename);
}
