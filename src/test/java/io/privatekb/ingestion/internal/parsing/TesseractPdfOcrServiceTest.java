package io.privatekb.ingestion.internal.parsing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.privatekb.ingestion.internal.domain.IngestionErrorCode;
import io.privatekb.ingestion.internal.config.IngestionProperties;
import io.privatekb.ingestion.internal.config.OcrProperties;
import io.privatekb.ingestion.internal.domain.TextExtractionException;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TesseractPdfOcrServiceTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void reportsUnavailableRuntimeBeforeStartingOcr(@TempDir Path temporaryDirectory) {
        TesseractPdfOcrService service = service(
                temporaryDirectory.resolve("missing"),
                temporaryDirectory.resolve("missing-data"),
                20
        );

        assertThatThrownBy(() -> service.extract(
                temporaryDirectory.resolve("missing.pdf"),
                "스캔.pdf",
                "application/pdf"
        )).isInstanceOfSatisfying(TextExtractionException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                        .isEqualTo(IngestionErrorCode.OCR_NOT_AVAILABLE)
        );
    }

    @Test
    void rejectsPdfOverPageLimitBeforeLaunchingTesseract(@TempDir Path temporaryDirectory)
            throws Exception {
        Path executableDirectory = Files.createDirectories(temporaryDirectory.resolve("ocr"));
        Path dataPath = Files.createDirectories(executableDirectory.resolve("tessdata"));
        Files.write(executableDirectory.resolve("tesseract.exe"), new byte[]{1});
        Files.write(dataPath.resolve("kor.traineddata"), new byte[]{1});
        Files.write(dataPath.resolve("eng.traineddata"), new byte[]{1});
        TesseractPdfOcrService service = service(executableDirectory, dataPath, 1);

        Path pdf = temporaryDirectory.resolve("two-pages.pdf");
        Files.write(pdf, blankPdf(2));
        assertThatThrownBy(() -> service.extract(
                pdf,
                "두쪽스캔.pdf",
                "application/pdf"
        )).isInstanceOfSatisfying(TextExtractionException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                        .isEqualTo(IngestionErrorCode.OCR_PAGE_LIMIT_EXCEEDED)
        );
    }

    @Test
    void rejectsPdfOverPixelBudgetBeforeRendering(@TempDir Path temporaryDirectory)
            throws Exception {
        Path executableDirectory = Files.createDirectories(temporaryDirectory.resolve("ocr"));
        Path dataPath = Files.createDirectories(executableDirectory.resolve("tessdata"));
        Files.write(executableDirectory.resolve("tesseract.exe"), new byte[]{1});
        Files.write(dataPath.resolve("kor.traineddata"), new byte[]{1});
        Files.write(dataPath.resolve("eng.traineddata"), new byte[]{1});
        Path pdf = temporaryDirectory.resolve("oversized-page.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(10_000, 10_000)));
            document.save(pdf.toFile());
        }

        assertThatThrownBy(() -> service(executableDirectory, dataPath, 20).extract(
                pdf,
                "\uac70\ub300\ud55c쪽.pdf",
                "application/pdf"
        )).isInstanceOfSatisfying(TextExtractionException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                        .isEqualTo(IngestionErrorCode.OCR_PIXEL_LIMIT_EXCEEDED)
        );
    }

    private TesseractPdfOcrService service(Path executableDirectory, Path dataPath, int maximumPages) {
        return new TesseractPdfOcrService(
                executor,
                new IngestionProperties(
                        25 * 1024 * 1024,
                        100_000,
                        Duration.ofSeconds(10),
                        3
                ),
                new OcrProperties(
                        true,
                        executableDirectory,
                        dataPath,
                        "kor+eng",
                        300,
                        maximumPages,
                        25_000_000,
                        250_000_000,
                        1,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20),
                        100
                )
        );
    }

    private byte[] blankPdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pages; index++) {
                document.addPage(new PDPage());
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
