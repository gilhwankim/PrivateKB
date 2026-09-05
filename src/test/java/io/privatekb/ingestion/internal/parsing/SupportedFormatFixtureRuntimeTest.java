package io.privatekb.ingestion.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import io.privatekb.ingestion.internal.domain.ExtractedText;
import io.privatekb.ingestion.internal.config.IngestionProperties;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.poi.hpsf.PropertySetFactory;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.LittleEndian;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("fixture-runtime")
@EnabledIfEnvironmentVariable(named = "PRIVATEKB_FIXTURE_DIRECTORY", matches = ".+")
class SupportedFormatFixtureRuntimeTest {

    @Test
    void generatesLegacyFixturesAndExtractsEverySupportedExtension() throws Exception {
        Path directory = Path.of(System.getenv("PRIVATEKB_FIXTURE_DIRECTORY"));
        Files.createDirectories(directory);
        createLegacyWord(
                Path.of(System.getenv("PRIVATEKB_DOC_TEMPLATE")),
                directory.resolve("06_고객미팅_결과.doc")
        );
        createLegacyPowerPoint(directory.resolve("08_제품로드맵.ppt"));
        createLegacyExcel(directory.resolve("10_문서처리_현황.xls"));
        createHwpV5(directory.resolve("11_운영회의록.hwp"));

        Map<String, String> expectedMarkers = new LinkedHashMap<>();
        expectedMarkers.put("00_지원형식_검증안내.md", "PKB-MD-20260902");
        expectedMarkers.put("01_장애대응_업무메모.txt", "PKB-TXT-20260902");
        expectedMarkers.put("02_제품회의_운영가이드.markdown", "PKB-MARKDOWN-20260902");
        expectedMarkers.put("03_제품회의_요약.pdf", "PKB-PDF-20260902");
        expectedMarkers.put("05_고객미팅_결과.docx", "PKB-DOCX-20260902");
        expectedMarkers.put("06_고객미팅_결과.doc", "PKB-DOC-20260902");
        expectedMarkers.put("07_제품로드맵.pptx", "PKB-PPTX-20260902");
        expectedMarkers.put("08_제품로드맵.ppt", "PKB-PPT-20260902");
        expectedMarkers.put("09_문서처리_현황.xlsx", "PKB-XLSX-20260902");
        expectedMarkers.put("10_문서처리_현황.xls", "PKB-XLS-20260902");
        expectedMarkers.put("11_운영회의록.hwp", "PKB-HWP-20260902");

        TikaContentTypeDetector detector = new TikaContentTypeDetector();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            TikaTextExtractionService service = new TikaTextExtractionService(
                    executor,
                    new IngestionProperties(100L * 1024 * 1024, 5_000_000, Duration.ofSeconds(60), 3)
            );
            for (Map.Entry<String, String> fixture : expectedMarkers.entrySet()) {
                Path source = directory.resolve(fixture.getKey());
                assertThat(source).exists().isRegularFile();
                String detected = detector.detect(() -> Files.newInputStream(source), source.getFileName().toString());
                try (InputStream input = Files.newInputStream(source)) {
                    ExtractedText extracted = service.extract(input, source.getFileName().toString(), detected);
                    assertThat(extracted.text()).contains(fixture.getValue());
                    System.out.printf("지원 형식 검증: 파일=%s, 형식=%s, 글자수=%d%n",
                            source.getFileName(), detected, extracted.text().length());
                }
            }
        }
    }

    private static void createLegacyWord(Path template, Path output) throws Exception {
        assertThat(template).exists().isRegularFile();
        try (InputStream input = Files.newInputStream(template);
             HWPFDocument document = new HWPFDocument(input)) {
            document.getRange().insertBefore(
                    "PrivateKB 고객 미팅 결과 PKB-DOC-20260902\r"
                            + "가상 고객사 새봄물류와 문서 검색, 중복 제거, 원본 폴더 열기 안전성을 논의했습니다.\r"
                            + "실제 고객 정보가 아닌 구형 Word 파싱 검증용 합성 문서입니다.\r\r"
            );
            document.write(output.toFile());
        }
    }

    private static void createLegacyPowerPoint(Path output) throws Exception {
        try (HSLFSlideShow presentation = new HSLFSlideShow();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            presentation.setPageSize(new Dimension(1280, 720));
            addSlide(presentation, "PrivateKB 제품 로드맵", "검증 식별자 PKB-PPT-20260902");
            addSlide(presentation, "점진 색인", "청크 묶음 저장과 중단 후 이어서 처리를 확인합니다.");
            addSlide(presentation, "검색 품질", "중복 제거와 원본 폴더 열기 안전성을 확인합니다.");
            presentation.write(bytes);
            Files.write(output, bytes.toByteArray());
        }
    }

    private static void addSlide(HSLFSlideShow presentation, String title, String body) {
        HSLFSlide slide = presentation.createSlide();
        HSLFTextBox titleBox = new HSLFTextBox();
        titleBox.setAnchor(new Rectangle(70, 70, 1140, 110));
        titleBox.setText(title);
        titleBox.getTextParagraphs().get(0).getTextRuns().get(0).setFontSize(34d);
        slide.addShape(titleBox);
        HSLFTextBox bodyBox = new HSLFTextBox();
        bodyBox.setAnchor(new Rectangle(90, 240, 1100, 260));
        bodyBox.setText(body);
        bodyBox.getTextParagraphs().get(0).getTextRuns().get(0).setFontSize(24d);
        slide.addShape(bodyBox);
    }

    private static void createLegacyExcel(Path output) throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet("처리현황");
            HSSFFont titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            HSSFCellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setFont(titleFont);
            sheet.createRow(0).createCell(0).setCellValue("PrivateKB 문서 처리 현황");
            sheet.getRow(0).getCell(0).setCellStyle(titleStyle);
            sheet.createRow(1).createCell(0).setCellValue("검증 식별자: PKB-XLS-20260902");
            String[][] rows = {
                    {"문서 유형", "파일 수", "완료", "실패"},
                    {"회의록", "18", "18", "0"},
                    {"장애 보고서", "12", "11", "1"},
                    {"운영 가이드", "9", "9", "0"},
                    {"고객 미팅", "11", "10", "1"}
            };
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                var row = sheet.createRow(rowIndex + 3);
                for (int column = 0; column < rows[rowIndex].length; column++) {
                    row.createCell(column).setCellValue(rows[rowIndex][column]);
                }
            }
            sheet.setColumnWidth(0, 24 * 256);
            for (int column = 1; column < 4; column++) {
                sheet.setColumnWidth(column, 12 * 256);
            }
            try (var stream = Files.newOutputStream(output)) {
                workbook.write(stream);
            }
        }
    }

    private static void createHwpV5(Path output) throws Exception {
        byte[] header = new byte[256];
        byte[] signature = "HWP Document File".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(signature, 0, header, 0, signature.length);
        LittleEndian.putUInt(header, 32, 0x05010000L);
        LittleEndian.putUInt(header, 36, 0L);

        String text = "PrivateKB 운영 회의록 PKB-HWP-20260902. 점진 색인과 장애 복구 검증을 진행했습니다. "
                + "중단된 문서는 체크포인트에서 이어서 처리하고 완료 전 데이터는 검색에서 제외합니다.";
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] record = new byte[4 + textBytes.length];
        long recordHeader = 0x43L | ((long) textBytes.length << 20);
        LittleEndian.putUInt(record, 0, recordHeader);
        System.arraycopy(textBytes, 0, record, 4, textBytes.length);

        SummaryInformation summary = PropertySetFactory.newSummaryInformation();
        summary.setTitle("PrivateKB 운영 회의록");
        summary.setAuthor("PrivateKB 합성 테스트");
        summary.setComments("실제 업무 데이터가 아닌 지원 형식 검증 자료");
        ByteArrayOutputStream summaryBytes = new ByteArrayOutputStream();
        summary.write(summaryBytes);

        try (POIFSFileSystem filesystem = new POIFSFileSystem();
             var stream = Files.newOutputStream(output)) {
            DirectoryEntry root = filesystem.getRoot();
            root.createDocument("FileHeader", new ByteArrayInputStream(header));
            root.createDocument("\u0005HwpSummaryInformation", new ByteArrayInputStream(summaryBytes.toByteArray()));
            DirectoryEntry bodyText = root.createDirectory("BodyText");
            bodyText.createDocument("Section0", new ByteArrayInputStream(record));
            filesystem.writeFilesystem(stream);
        }
    }
}
