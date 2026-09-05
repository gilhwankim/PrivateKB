package io.privatekb.ingestion.internal.domain;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface UploadSource {

    InputStream openStream() throws IOException;
}
