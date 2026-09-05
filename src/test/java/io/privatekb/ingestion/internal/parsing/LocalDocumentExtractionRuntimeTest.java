package io.privatekb.ingestion.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import io.privatekb.ingestion.internal.domain.ExtractedText;
import io.privatekb.ingestion.internal.config.IngestionProperties;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("document-runtime")
@EnabledIfEnvironmentVariable(named = "PRIVATEKB_TEST_DOCUMENT", matches = ".+")
class LocalDocumentExtractionRuntimeTest {

    @Test
    void extractsLocalWordDocumentWithoutLoggingItsContents() throws Exception {
        Path source = Path.of(System.getenv("PRIVATEKB_TEST_DOCUMENT"));
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             InputStream input = Files.newInputStream(source)) {
            String detected = new TikaContentTypeDetector().detect(
                    () -> Files.newInputStream(source), "검증.doc"
            );
            TikaTextExtractionService service = new TikaTextExtractionService(
                    executor,
                    new IngestionProperties(25 * 1024 * 1024, 5_000_000, Duration.ofSeconds(30), 3)
            );
            long started = System.nanoTime();
            ExtractedText extracted;
            try {
                extracted = service.extract(input, "검증.doc", detected);
            } catch (RuntimeException exception) {
                StringBuilder diagnostic = new StringBuilder("문서 파싱 예외 유형과 위치:");
                for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                    diagnostic.append('\n').append(cause.getClass().getName());
                    for (int index = 0; index < Math.min(cause.getStackTrace().length, 6); index++) {
                        diagnostic.append("\n  ").append(cause.getStackTrace()[index]);
                    }
                }
                throw new AssertionError(diagnostic.toString());
            }
            assertThat(extracted.text().length()).isPositive();
            System.out.printf("본문 추출 검증: 형식=%s, 글자수=%d, 소요시간=%dms%n",
                    extracted.mediaType(), extracted.text().length(),
                    (System.nanoTime() - started) / 1_000_000);
        }
    }
}
