package io.privatekb.ingestion.internal.parsing;

import io.privatekb.ingestion.internal.domain.ExtractedText;
import io.privatekb.ingestion.internal.domain.IngestionErrorCode;
import io.privatekb.ingestion.internal.config.IngestionProperties;
import io.privatekb.ingestion.internal.config.OcrProperties;
import io.privatekb.ingestion.internal.application.port.PdfOcrService;
import io.privatekb.ingestion.internal.domain.TextExtractionException;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
class TesseractPdfOcrService implements PdfOcrService {

    private static final String PARSER_NAME = "PDFBox + Tesseract OCR 5.4";
    static final String TEMPORARY_DIRECTORY_NAME = "PrivateKB-ocr";

    private final ExecutorService executor;
    private final IngestionProperties ingestionProperties;
    private final OcrProperties properties;

    TesseractPdfOcrService(
            @Qualifier("ocrParserExecutor") ExecutorService executor,
            IngestionProperties ingestionProperties,
            OcrProperties properties
    ) {
        this.executor = executor;
        this.ingestionProperties = ingestionProperties;
        this.properties = properties;
    }

    @Override
    public ExtractedText extract(Path source, String filename, String detectedMediaType) {
        OcrRuntime runtime = requireRuntime();
        Future<ExtractedText> future = executor.submit(
                () -> parse(source, detectedMediaType, runtime)
        );
        try {
            return future.get(properties.documentTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new TextExtractionException(IngestionErrorCode.OCR_TIMEOUT, exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new TextExtractionException(IngestionErrorCode.OCR_FAILED, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof TextExtractionException extractionException) {
                throw extractionException;
            }
            throw new TextExtractionException(IngestionErrorCode.OCR_FAILED, cause);
        }
    }

    private ExtractedText parse(
            Path source,
            String detectedMediaType,
            OcrRuntime runtime
    ) {
        Path temporaryDirectory = null;
        try {
            try (PDDocument document = Loader.loadPDF(source.toFile())) {
                int pageCount = document.getNumberOfPages();
                if (pageCount > properties.maximumPages()) {
                    throw new TextExtractionException(
                            IngestionErrorCode.OCR_PAGE_LIMIT_EXCEEDED,
                            null
                    );
                }
                validatePixelBudget(document);

                temporaryDirectory = createTemporaryDirectory();
                PDFRenderer renderer = new PDFRenderer(document);
                renderer.setSubsamplingAllowed(true);
                StringBuilder extracted = new StringBuilder();
                for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                    requireNotInterrupted();
                    String pageText = recognizePage(
                            renderer,
                            pageIndex,
                            temporaryDirectory,
                            runtime
                    );
                    if (!pageText.isBlank()) {
                        if (!extracted.isEmpty()) {
                            extracted.append("\n\n");
                        }
                        extracted.append(pageText);
                        requireWithinCharacterLimit(extracted.length());
                    }
                }
                String text = extracted.toString().strip();
                if (text.isBlank()) {
                    throw new TextExtractionException(IngestionErrorCode.OCR_NO_TEXT, null);
                }
                return new ExtractedText(text, detectedMediaType, pageCount, PARSER_NAME);
            }
        } catch (InvalidPasswordException exception) {
            throw new TextExtractionException(IngestionErrorCode.PDF_ENCRYPTED, exception);
        } catch (TextExtractionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new TextExtractionException(IngestionErrorCode.PARSE_IO, exception);
        } catch (RuntimeException exception) {
            throw new TextExtractionException(IngestionErrorCode.OCR_FAILED, exception);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    private void validatePixelBudget(PDDocument document) {
        long totalPixels = 0;
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            var box = document.getPage(pageIndex).getCropBox();
            long width = Math.max(1L, (long) Math.ceil(box.getWidth() * properties.dpi() / 72.0));
            long height = Math.max(1L, (long) Math.ceil(box.getHeight() * properties.dpi() / 72.0));
            long pagePixels;
            try {
                pagePixels = Math.multiplyExact(width, height);
                totalPixels = Math.addExact(totalPixels, pagePixels);
            } catch (ArithmeticException exception) {
                throw new TextExtractionException(
                        IngestionErrorCode.OCR_PIXEL_LIMIT_EXCEEDED,
                        exception
                );
            }
            if (pagePixels > properties.maximumPixelsPerPage()
                    || totalPixels > properties.maximumTotalPixels()) {
                throw new TextExtractionException(
                        IngestionErrorCode.OCR_PIXEL_LIMIT_EXCEEDED,
                        null
                );
            }
        }
    }

    private Path createTemporaryDirectory() throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), TEMPORARY_DIRECTORY_NAME)
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(root);
        if (Files.getFileStore(root).getUsableSpace() < properties.minimumTemporaryFreeBytes()) {
            throw new TextExtractionException(
                    IngestionErrorCode.OCR_TEMP_STORAGE_INSUFFICIENT,
                    null
            );
        }
        return Files.createTempDirectory(root, "job-");
    }

    private String recognizePage(
            PDFRenderer renderer,
            int pageIndex,
            Path temporaryDirectory,
            OcrRuntime runtime
    ) throws IOException {
        Path imagePath = temporaryDirectory.resolve("page-%04d.png".formatted(pageIndex + 1));
        Path outputPath = temporaryDirectory.resolve("page-%04d.txt".formatted(pageIndex + 1));
        Path errorPath = temporaryDirectory.resolve("page-%04d.err".formatted(pageIndex + 1));
        BufferedImage image = renderer.renderImageWithDPI(
                pageIndex,
                properties.dpi(),
                ImageType.GRAY
        );
        try {
            if (!ImageIO.write(image, "png", imagePath.toFile())) {
                throw new TextExtractionException(IngestionErrorCode.OCR_FAILED, null);
            }
        } finally {
            image.flush();
        }

        String text = runTesseract(
                imagePath,
                outputPath,
                errorPath,
                runtime,
                "3"
        );
        if (text.isBlank()) {
            text = runTesseract(
                    imagePath,
                    outputPath,
                    errorPath,
                    runtime,
                    "6"
            );
        }
        return text;
    }

    private String runTesseract(
            Path imagePath,
            Path outputPath,
            Path errorPath,
            OcrRuntime runtime,
            String pageSegmentationMode
    ) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(List.of(
                runtime.executable().toString(),
                imagePath.toString(),
                "stdout",
                "--tessdata-dir",
                runtime.dataPath().toString(),
                "-l",
                properties.language(),
                "--psm",
                pageSegmentationMode,
                "--dpi",
                Integer.toString(properties.dpi()),
                "-c",
                "preserve_interword_spaces=1"
        ));
        builder.directory(runtime.executableDirectory().toFile());
        builder.environment().put("OMP_THREAD_LIMIT", "1");
        builder.redirectOutput(outputPath.toFile());
        builder.redirectError(errorPath.toFile());

        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new TextExtractionException(IngestionErrorCode.OCR_NOT_AVAILABLE, exception);
        }
        try {
            boolean completed = process.waitFor(
                    properties.pageTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            if (!completed) {
                process.destroyForcibly();
                throw new TextExtractionException(IngestionErrorCode.OCR_TIMEOUT, null);
            }
            if (process.exitValue() != 0) {
                throw new TextExtractionException(IngestionErrorCode.OCR_FAILED, null);
            }
            return Files.readString(outputPath, StandardCharsets.UTF_8)
                    .replace("\f", "")
                    .strip();
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new TextExtractionException(IngestionErrorCode.OCR_FAILED, exception);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private OcrRuntime requireRuntime() {
        if (!properties.enabled()) {
            throw new TextExtractionException(IngestionErrorCode.OCR_NOT_AVAILABLE, null);
        }
        Path executableDirectory = properties.executableDirectory().toAbsolutePath().normalize();
        Path dataPath = properties.dataPath().toAbsolutePath().normalize();
        String executableName = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win") ? "tesseract.exe" : "tesseract";
        Path executable = executableDirectory.resolve(executableName);
        if (!Files.isRegularFile(executable)
                || !Files.isRegularFile(dataPath.resolve("kor.traineddata"))
                || !Files.isRegularFile(dataPath.resolve("eng.traineddata"))) {
            throw new TextExtractionException(IngestionErrorCode.OCR_NOT_AVAILABLE, null);
        }
        return new OcrRuntime(executableDirectory, executable, dataPath);
    }

    private void requireWithinCharacterLimit(int characterCount) {
        if (characterCount > ingestionProperties.maxExtractedCharacters()) {
            throw new TextExtractionException(IngestionErrorCode.PARSE_LIMIT_EXCEEDED, null);
        }
    }

    private void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new TextExtractionException(IngestionErrorCode.OCR_FAILED, null);
        }
    }

    private void deleteTemporaryDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // OCR 임시 파일은 운영 데이터가 아니며 다음 OS 임시 디렉터리 정리 때 제거된다.
        }
    }

    private record OcrRuntime(
            Path executableDirectory,
            Path executable,
            Path dataPath
    ) {
    }
}
