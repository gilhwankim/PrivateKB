package io.privatekb.ingestion.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.privatekb.ingestion.internal.domain.ExtractedText;
import io.privatekb.ingestion.internal.config.IngestionProperties;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("fixture-runtime")
@EnabledIfEnvironmentVariable(named = "PRIVATEKB_BUSINESS_DATASET_MANIFEST", matches = ".+")
class BusinessDatasetGeneratorRuntimeTest {

    @Test
    void generatesLegacyOfficeDocumentsForBusinessDataset() throws Exception {
        Path manifestPath = Path.of(System.getenv("PRIVATEKB_BUSINESS_DATASET_MANIFEST"));
        Path docTemplate = Path.of(System.getenv("PRIVATEKB_DOC_TEMPLATE"));
        DatasetManifest manifest = new ObjectMapper().readValue(manifestPath.toFile(), DatasetManifest.class);
        Path outputRoot = Path.of(manifest.outputRoot()).toAbsolutePath().normalize();
        assertThat(docTemplate).exists().isRegularFile();

        int generated = 0;
        for (DatasetRecord record : manifest.records()) {
            if (!List.of("doc", "xls", "ppt").contains(record.extension())) {
                continue;
            }
            Path target = outputRoot.resolve(record.relativePath()).normalize();
            assertThat(target).isNotEqualTo(outputRoot);
            assertThat(target.startsWith(outputRoot)).isTrue();
            Files.createDirectories(target.getParent());
            switch (record.extension()) {
                case "doc" -> createLegacyWord(docTemplate, target, record);
                case "xls" -> createLegacyExcel(target, record);
                case "ppt" -> createLegacyPowerPoint(target, record);
                default -> throw new IllegalStateException("지원하지 않는 생성 형식");
            }
            generated++;
        }

        assertThat(generated).isEqualTo(30);
    }

    @Test
    void extractsEveryGeneratedBusinessDocument() throws Exception {
        Path manifestPath = Path.of(System.getenv("PRIVATEKB_BUSINESS_DATASET_MANIFEST"));
        DatasetManifest manifest = new ObjectMapper().readValue(manifestPath.toFile(), DatasetManifest.class);
        Path outputRoot = Path.of(manifest.outputRoot()).toAbsolutePath().normalize();
        TikaContentTypeDetector detector = new TikaContentTypeDetector();
        Map<String, Integer> verifiedByExtension = new LinkedHashMap<>();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            TikaTextExtractionService service = new TikaTextExtractionService(
                    executor,
                    new IngestionProperties(100L * 1024 * 1024, 5_000_000, Duration.ofSeconds(60), 3)
            );
            for (DatasetRecord record : manifest.records()) {
                Path source = outputRoot.resolve(record.relativePath()).normalize();
                assertThat(source).exists().isRegularFile();
                String detected = detector.detect(() -> Files.newInputStream(source), record.filename());
                try (InputStream input = Files.newInputStream(source)) {
                    ExtractedText extracted = service.extract(input, record.filename(), detected);
                    assertThat(extracted.text())
                            .as("본문 식별자: %s", record.relativePath())
                            .contains(record.marker());
                }
                verifiedByExtension.merge(record.extension(), 1, Integer::sum);
            }
        }

        assertThat(verifiedByExtension).hasSize(10);
        assertThat(verifiedByExtension.values()).allMatch(count -> count == 10);
        System.out.println("가상 업무 문서 전수 추출 검증: " + verifiedByExtension);
    }

    private static void createLegacyWord(Path template, Path output, DatasetRecord record) throws Exception {
        try (InputStream input = Files.newInputStream(template);
             HWPFDocument document = new HWPFDocument(input)) {
            document.getRange().insertBefore(body(record).replace("\n", "\r") + "\r\r");
            document.write(output.toFile());
        }
    }

    private static void createLegacyPowerPoint(Path output, DatasetRecord record) throws Exception {
        try (HSLFSlideShow presentation = new HSLFSlideShow()) {
            presentation.setPageSize(new Dimension(1280, 720));
            addSlide(presentation, record.title(),
                    record.documentType() + " | " + record.marker() + "\n"
                            + record.date() + " | " + record.client());
            addSlide(presentation, record.subject() + " 검토 결과",
                    record.summary() + "\n\n" + String.join("\n", record.details()));
            addSlide(presentation, "결정 사항과 후속 작업",
                    String.join("\n", record.decisions()) + "\n\n"
                            + record.actions().stream()
                            .map(item -> item.owner() + " | " + item.task() + " | " + item.due())
                            .reduce((left, right) -> left + "\n" + right).orElse(""));
            try (var stream = Files.newOutputStream(output)) {
                presentation.write(stream);
            }
        }
    }

    private static void addSlide(HSLFSlideShow presentation, String title, String body) {
        HSLFSlide slide = presentation.createSlide();
        HSLFTextBox titleBox = new HSLFTextBox();
        titleBox.setAnchor(new Rectangle(70, 60, 1140, 110));
        titleBox.setText(title);
        titleBox.getTextParagraphs().getFirst().getTextRuns().getFirst().setFontSize(36d);
        titleBox.getTextParagraphs().getFirst().getTextRuns().getFirst().setBold(true);
        slide.addShape(titleBox);

        HSLFTextBox bodyBox = new HSLFTextBox();
        bodyBox.setAnchor(new Rectangle(85, 210, 1110, 390));
        bodyBox.setText(body);
        bodyBox.getTextParagraphs().forEach(paragraph ->
                paragraph.getTextRuns().forEach(run -> run.setFontSize(20d)));
        slide.addShape(bodyBox);
    }

    private static void createLegacyExcel(Path output, DatasetRecord record) throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet("업무현황");
            HSSFFont titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            HSSFCellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setFont(titleFont);

            sheet.createRow(0).createCell(0).setCellValue(record.title());
            sheet.getRow(0).getCell(0).setCellStyle(titleStyle);
            sheet.createRow(1).createCell(0).setCellValue("문서 식별자: " + record.marker());
            sheet.createRow(2).createCell(0).setCellValue("작성일: " + record.date() + " | 고객사: " + record.client());

            String[] headers = {"지표", "현재", "목표", "단위", "차이"};
            var header = sheet.createRow(4);
            for (int column = 0; column < headers.length; column++) {
                header.createCell(column).setCellValue(headers[column]);
            }
            for (int rowIndex = 0; rowIndex < record.metrics().size(); rowIndex++) {
                List<Object> metric = record.metrics().get(rowIndex);
                var row = sheet.createRow(rowIndex + 5);
                row.createCell(0).setCellValue(String.valueOf(metric.get(0)));
                row.createCell(1).setCellValue(((Number) metric.get(1)).doubleValue());
                row.createCell(2).setCellValue(((Number) metric.get(2)).doubleValue());
                row.createCell(3).setCellValue(String.valueOf(metric.get(3)));
                row.createCell(4).setCellFormula("C" + (rowIndex + 6) + "-B" + (rowIndex + 6));
            }

            var actionHeader = sheet.createRow(12);
            String[] actionHeaders = {"담당", "작업", "기한", "상태"};
            for (int column = 0; column < actionHeaders.length; column++) {
                actionHeader.createCell(column).setCellValue(actionHeaders[column]);
            }
            for (int rowIndex = 0; rowIndex < record.actions().size(); rowIndex++) {
                Action action = record.actions().get(rowIndex);
                var row = sheet.createRow(rowIndex + 13);
                row.createCell(0).setCellValue(action.owner());
                row.createCell(1).setCellValue(action.task());
                row.createCell(2).setCellValue(action.due());
                row.createCell(3).setCellValue(action.status());
            }

            sheet.setColumnWidth(0, 20 * 256);
            sheet.setColumnWidth(1, 40 * 256);
            sheet.setColumnWidth(2, 16 * 256);
            sheet.setColumnWidth(3, 14 * 256);
            sheet.setColumnWidth(4, 14 * 256);
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            try (var stream = Files.newOutputStream(output)) {
                workbook.write(stream);
            }
        }
    }

    private static String body(DatasetRecord record) {
        StringBuilder value = new StringBuilder()
                .append(record.title()).append('\n')
                .append("문서 식별자: ").append(record.marker()).append('\n')
                .append("작성일: ").append(record.date()).append('\n')
                .append("고객사: ").append(record.client()).append("\n\n")
                .append("개요\n").append(record.summary()).append("\n\n")
                .append("검토 내용\n");
        record.details().forEach(item -> value.append(item).append('\n'));
        value.append("\n결정 사항\n");
        record.decisions().forEach(item -> value.append(item).append('\n'));
        value.append("\n후속 작업\n");
        record.actions().forEach(item -> value.append(item.owner()).append(" | ")
                .append(item.task()).append(" | ").append(item.due()).append(" | ")
                .append(item.status()).append('\n'));
        return value.append("\n실제 회사나 고객 정보를 사용하지 않은 PrivateKB 기능 검증용 가상 자료입니다.").toString();
    }

    private record DatasetManifest(int schemaVersion, String outputRoot, List<DatasetRecord> records) {
    }

    private record DatasetRecord(
            String id,
            String marker,
            String extension,
            String company,
            String client,
            String sector,
            String documentType,
            String subject,
            String date,
            String title,
            String filename,
            String relativePath,
            String summary,
            List<String> details,
            List<String> decisions,
            List<Action> actions,
            List<List<Object>> metrics
    ) {
    }

    private record Action(String owner, String task, String due, String status) {
    }
}
