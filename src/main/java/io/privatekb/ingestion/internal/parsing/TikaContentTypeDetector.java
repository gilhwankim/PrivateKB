package io.privatekb.ingestion.internal.parsing;

import io.privatekb.ingestion.internal.application.port.ContentTypeDetector;
import io.privatekb.ingestion.internal.domain.UploadSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import org.apache.tika.Tika;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.stereotype.Component;

@Component
class TikaContentTypeDetector implements ContentTypeDetector {

    private static final String GENERIC_OLE2 = "application/x-tika-msoffice";
    private static final String HWP_V5 = "application/x-hwp-v5";
    private static final byte[] HWP_V5_SIGNATURE = "HWP Document File".getBytes(StandardCharsets.US_ASCII);

    @Override
    public String detect(UploadSource source, String filename) {
        try (InputStream input = source.openStream()) {
            String detected = new Tika().detect(input, filename);
            if (isHwpFilename(filename) && GENERIC_OLE2.equals(detected) && hasHwpV5Signature(source)) {
                return HWP_V5;
            }
            return detected;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect uploaded content", exception);
        }
    }

    private boolean isHwpFilename(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".hwp");
    }

    private boolean hasHwpV5Signature(UploadSource source) {
        try (InputStream input = source.openStream();
             POIFSFileSystem filesystem = new POIFSFileSystem(input)) {
            if (!filesystem.getRoot().hasEntry("FileHeader")) {
                return false;
            }
            try (DocumentInputStream header = filesystem.createDocumentInputStream("FileHeader")) {
                return Arrays.equals(header.readNBytes(HWP_V5_SIGNATURE.length), HWP_V5_SIGNATURE);
            }
        } catch (IOException exception) {
            return false;
        }
    }
}
