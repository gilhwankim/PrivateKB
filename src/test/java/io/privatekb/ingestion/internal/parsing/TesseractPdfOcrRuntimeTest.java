package io.privatekb.ingestion.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.privatekb.ingestion.internal.domain.ExtractedText;
import io.privatekb.ingestion.internal.config.IngestionProperties;
import io.privatekb.ingestion.internal.config.OcrProperties;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@Tag("ocr-runtime")
@EnabledOnOs(OS.WINDOWS)
class TesseractPdfOcrRuntimeTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void recognizesKoreanAndEnglishFromImageOnlyPdf() throws Exception {
        TesseractPdfOcrService service = service();

        byte[] pdfBytes = imageOnlyPdf();
        Path pdf = Files.createTempFile("privatekb-ocr-runtime-", ".pdf");
        Files.write(pdf, pdfBytes);
        ExtractedText extracted = service.extract(
                pdf,
                "OCR검증.pdf",
                "application/pdf"
        );

        assertThat(extracted.text()).isNotBlank();
        assertThat(extracted.text()).containsAnyOf("회의록", "장애", "대응", "완료");
        assertThat(extracted.pageCount()).isEqualTo(1);
        assertThat(extracted.parserName()).contains("Tesseract OCR");
        Files.deleteIfExists(pdf);
    }

    @Test
    void recognizesTheSupportedFormatOcrFixture() {
        String fixtureDirectory = System.getenv("PRIVATEKB_FIXTURE_DIRECTORY");
        assumeTrue(fixtureDirectory != null && !fixtureDirectory.isBlank());

        Path pdf = Path.of(fixtureDirectory).resolve("04_장애대응_OCR.pdf");
        assumeTrue(Files.isRegularFile(pdf));

        ExtractedText extracted = service().extract(
                pdf,
                pdf.getFileName().toString(),
                "application/pdf"
        );

        assertThat(extracted.text()).containsAnyOf("장애 대응", "복구훈련", "OCR", "핵심 조치");
        assertThat(extracted.pageCount()).isEqualTo(1);
        assertThat(extracted.parserName()).contains("Tesseract OCR");
    }

    private TesseractPdfOcrService service() {
        Path stagedRuntime = Path.of("frontend", "src-tauri", "runtime", "ocr")
                .toAbsolutePath()
                .normalize();
        Path executableDirectory = Files.isRegularFile(stagedRuntime.resolve("tesseract.exe"))
                ? stagedRuntime
                : Path.of("C:\\Program Files\\Tesseract-OCR");
        Path stagedDataPath = stagedRuntime.resolve("tessdata");
        Path dataPath = Files.isRegularFile(stagedDataPath.resolve("kor.traineddata"))
                ? stagedDataPath
                : Path.of("build", "ocr-tessdata").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(executableDirectory.resolve("tesseract.exe")));
        assumeTrue(Files.isRegularFile(dataPath.resolve("kor.traineddata")));
        assumeTrue(Files.isRegularFile(dataPath.resolve("eng.traineddata")));

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
                        20,
                        25_000_000,
                        250_000_000,
                        1,
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(3),
                        100
                )
        );
    }

    private byte[] imageOnlyPdf() throws Exception {
        BufferedImage image = new BufferedImage(1800, 600, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font("Malgun Gothic", Font.BOLD, 72));
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );
            graphics.drawString("OCR 검증 회의록", 110, 230);
            graphics.drawString("장애 대응 조치 완료", 110, 380);
        } finally {
            graphics.dispose();
        }

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(
                    PDRectangle.LETTER.getHeight(),
                    PDRectangle.LETTER.getWidth()
            ));
            document.addPage(page);
            PDImageXObject pdfImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(pdfImage, 36, 170, 720, 240);
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
