package io.privatekb.ingestion.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.privatekb.ingestion.internal.domain.ExtractedText;
import io.privatekb.ingestion.internal.config.IngestionProperties;
import io.privatekb.ingestion.internal.domain.OcrRequiredException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TikaDocumentFormatSupportTest {

    private static final String EXPECTED_TEXT = "PrivateKB 문서 형식 추출 검증";

    private final ExecutorService parserExecutor = Executors.newSingleThreadExecutor();
    private final TikaTextExtractionService extractionService = new TikaTextExtractionService(
            parserExecutor,
            new IngestionProperties(25 * 1024 * 1024, 100_000, Duration.ofSeconds(10), 3)
    );

    @AfterEach
    void stopParserExecutor() {
        parserExecutor.shutdownNow();
    }

    @Test
    void registersOfficeAndHwpParsersInTheRuntime() {
        Set<MediaType> supported = new AutoDetectParser().getSupportedTypes(new ParseContext());

        assertThat(supported).contains(
                MediaType.parse("application/msword"),
                MediaType.parse("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                MediaType.parse("application/vnd.ms-powerpoint"),
                MediaType.parse("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                MediaType.parse("application/vnd.ms-excel"),
                MediaType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                MediaType.parse("application/x-hwp-v5")
        );
    }

    @Test
    void recognizesHwpV5ByItsOle2FileHeaderSignature() throws Exception {
        byte[] hwpContainer;
        try (POIFSFileSystem filesystem = new POIFSFileSystem();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            filesystem.createDocument(
                    new ByteArrayInputStream("HWP Document File".getBytes(StandardCharsets.US_ASCII)),
                    "FileHeader"
            );
            filesystem.writeFilesystem(output);
            hwpContainer = output.toByteArray();
        }

        String detected = new TikaContentTypeDetector().detect(
                () -> new ByteArrayInputStream(hwpContainer),
                "회의록.hwp"
        );

        assertThat(detected).isEqualTo("application/x-hwp-v5");
    }

    @Test
    void routesOnlyImageOnlyPdfToTheOcrQueue() throws Exception {
        byte[] blankPdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            blankPdf = output.toByteArray();
        }

        assertThatThrownBy(() -> extractionService.extract(
                new ByteArrayInputStream(blankPdf),
                "스캔.pdf",
                "application/pdf"
        )).isInstanceOf(OcrRequiredException.class);
    }

    @ParameterizedTest
    @MethodSource("generatedOfficeDocuments")
    void detectsAndExtractsGeneratedOfficeDocuments(
            String filename,
            String expectedMediaType,
            byte[] bytes
    ) {
        TikaContentTypeDetector detector = new TikaContentTypeDetector();
        String detected = detector.detect(() -> new ByteArrayInputStream(bytes), filename);
        ExtractedText extracted = extractionService.extract(
                new ByteArrayInputStream(bytes),
                filename,
                detected
        );

        assertThat(detected).isEqualTo(expectedMediaType);
        assertThat(extracted.text()).contains(EXPECTED_TEXT);
    }

    private static Stream<Arguments> generatedOfficeDocuments() throws Exception {
        return Stream.of(
                Arguments.of(
                        "검증.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        docx()
                ),
                Arguments.of(
                        "검증.pptx",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        pptx()
                ),
                Arguments.of(
                        "검증.ppt",
                        "application/vnd.ms-powerpoint",
                        ppt()
                ),
                Arguments.of(
                        "검증.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        xlsx()
                ),
                Arguments.of("검증.xls", "application/vnd.ms-excel", xls())
        );
    }

    private static byte[] docx() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(EXPECTED_TEXT);
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pptx() throws Exception {
        try (XMLSlideShow presentation = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = presentation.createSlide();
            XSLFTextBox textBox = slide.createTextBox();
            textBox.setText(EXPECTED_TEXT);
            presentation.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] ppt() throws Exception {
        try (HSLFSlideShow presentation = new HSLFSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            HSLFSlide slide = presentation.createSlide();
            HSLFTextBox textBox = new HSLFTextBox();
            textBox.setText(EXPECTED_TEXT);
            slide.addShape(textBox);
            presentation.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("검증").createRow(0).createCell(0).setCellValue(EXPECTED_TEXT);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xls() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("검증").createRow(0).createCell(0).setCellValue(EXPECTED_TEXT);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
