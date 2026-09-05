package io.privatekb.ingestion.internal.application;

import io.privatekb.ingestion.internal.application.command.DesktopDocumentImportCommand;
import io.privatekb.ingestion.internal.domain.DocumentEmbeddingStatus;
import io.privatekb.ingestion.internal.application.view.DocumentPageView;
import io.privatekb.ingestion.internal.domain.ExtractedText;
import io.privatekb.ingestion.internal.domain.IndexingStatus;
import io.privatekb.ingestion.internal.application.view.IndexingView;
import io.privatekb.ingestion.internal.domain.IngestionErrorCode;
import io.privatekb.ingestion.internal.config.IngestionProperties;
import io.privatekb.ingestion.internal.domain.IngestionStatus;
import io.privatekb.ingestion.internal.application.view.IngestionView;
import io.privatekb.ingestion.internal.application.port.PdfOcrService;
import io.privatekb.ingestion.internal.domain.TextExtractionException;
import io.privatekb.ingestion.internal.application.command.UploadDocumentCommand;
import io.privatekb.ingestion.internal.application.view.UploadDocumentResult;
import io.privatekb.ingestion.internal.domain.UploadRejectedException;
import io.privatekb.ingestion.internal.domain.UploadRejectionCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.privatekb.PrivateKbApplication;
import io.privatekb.platform.LocalEmbeddingClient;
import io.privatekb.retrieval.SearchResponse;
import io.privatekb.retrieval.SearchResultView;
import io.privatekb.retrieval.SemanticSearchRepository;
import io.privatekb.retrieval.SemanticSearchService;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("container")
@Testcontainers
@SpringBootTest(
        classes = PrivateKbApplication.class,
        properties = "privatekb.local-ai.startup-check-enabled=false"
)
@Import(DocumentIngestionIntegrationTest.EmbeddingTestConfiguration.class)
class DocumentIngestionIntegrationTest {

    private static final UUID DEFAULT_WORKSPACE = UUID.fromString(
            "00000000-0000-0000-0000-000000000001"
    );
    private static final DockerImageName PGVECTOR = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg18-trixie")
            .asCompatibleSubstituteFor("postgres");
    private static final Path STORAGE_ROOT = createTemporaryStorageRoot();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(PGVECTOR)
            .withDatabaseName("privatekb")
            .withUsername("privatekb")
            .withPassword("integration-test-only");

    @DynamicPropertySource
    static void ingestionProperties(DynamicPropertyRegistry registry) {
        registry.add("privatekb.storage.root", STORAGE_ROOT::toString);
        registry.add("privatekb.ingestion.parsing-timeout", () -> "10s");
    }

    @Autowired
    IngestionApplicationService ingestion;

    @Autowired
    DesktopDocumentImportService desktopImports;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    IndexingApplicationService indexing;

    @Autowired
    DocumentStatusApplicationService documentStatuses;

    @Autowired
    SemanticSearchService search;

    @Autowired
    SemanticSearchRepository searchRepository;

    @Test
    void desktopDuplicateContentAddsSourceLocationWithoutCreatingAnotherVersion() throws Exception {
        Path firstDirectory = Files.createTempDirectory("privatekb-source-first-");
        Path secondDirectory = Files.createTempDirectory("privatekb-source-second-");
        byte[] content = "동일한 데스크톱 원본 위치 연결 시험 문서입니다.".getBytes(StandardCharsets.UTF_8);
        Path first = firstDirectory.resolve("원본.txt");
        Path second = secondDirectory.resolve("복사본.txt");
        Files.write(first, content);
        Files.write(second, content);

        UploadDocumentResult firstResult = desktopUpload(first);
        UploadDocumentResult duplicateResult = desktopUpload(second);

        assertThat(duplicateResult.duplicate()).isTrue();
        assertThat(duplicateResult.documentVersionId()).isEqualTo(firstResult.documentVersionId());
        Integer locationCount = jdbc.sql("""
                        SELECT count(*)
                          FROM privatekb.document_source_location
                         WHERE document_version_id = :versionId
                        """)
                .param("versionId", firstResult.documentVersionId())
                .query(Integer.class)
                .single();
        assertThat(locationCount).isEqualTo(2);

        Files.deleteIfExists(first);
        Files.deleteIfExists(second);
        Files.deleteIfExists(firstDirectory);
        Files.deleteIfExists(secondDirectory);
    }

    @Test
    void searchesStoredFolderNamesAndScopesSemanticCandidatesWithoutReindexing() throws Exception {
        Path root = Files.createTempDirectory("privatekb-folder-search-");
        Path companyFolder = Files.createDirectories(root.resolve("OO회사").resolve("회의록"));
        Path otherFolder = Files.createDirectories(root.resolve("다른회사"));
        Path companyFile = companyFolder.resolve("비용회의록.txt");
        Path otherFile = otherFolder.resolve("비용회의록.txt");
        Files.writeString(companyFile, "OO 범위의 비용 정산 회의 결과입니다.", StandardCharsets.UTF_8);
        Files.writeString(otherFile, "다른 범위의 비용 정산 회의 결과입니다.", StandardCharsets.UTF_8);

        try {
            UploadDocumentResult company = desktopUpload(companyFile);
            UploadDocumentResult other = desktopUpload(otherFile);
            assertThat(awaitTerminal(company.ingestionJobId()).status()).isEqualTo(IngestionStatus.PARSED);
            assertThat(awaitTerminal(other.ingestionJobId()).status()).isEqualTo(IngestionStatus.PARSED);
            assertThat(awaitIndexed(company.documentVersionId()).status()).isEqualTo(IndexingStatus.INDEXED);
            assertThat(awaitIndexed(other.documentVersionId()).status()).isEqualTo(IndexingStatus.INDEXED);

            List<SearchResultView> folderMatches = searchRepository.searchByFolder(
                    DEFAULT_WORKSPACE,
                    "OO회사",
                    10
            );
            assertThat(folderMatches)
                    .extracting(SearchResultView::documentVersionId)
                    .contains(company.documentVersionId())
                    .doesNotContain(other.documentVersionId());
            assertThat(folderMatches.getFirst().sourceFolderPath()).contains("OO회사");

            float[] queryEmbedding = new float[1024];
            queryEmbedding[0] = 1.0f;
            List<SearchResultView> scoped = searchRepository.search(
                    DEFAULT_WORKSPACE,
                    "비용 회의록",
                    "회의록",
                    "",
                    "OO회사",
                    null,
                    null,
                    false,
                    queryEmbedding,
                    new LocalEmbeddingClient.EmbeddingModelInfo(
                            "test-embedding",
                            "test-digest",
                            1024
                    ),
                    20,
                    0.0,
                    1,
                    10
            );
            assertThat(scoped)
                    .extracting(SearchResultView::documentVersionId)
                    .contains(company.documentVersionId())
                    .doesNotContain(other.documentVersionId());
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void parsesTxtMarkdownAndPdfAndReturnsExistingVersionForDuplicateBytes() throws Exception {
        Instant uploadWindowStart = Instant.now().minusSeconds(1);
        byte[] textBytes = ("""
                PrivateKB 합성 운영 메모입니다. 장애 대응 회의에서는 재발 방지 조치와 담당자를 확정했습니다.
                """).repeat(40).getBytes(StandardCharsets.UTF_8);
        UploadDocumentResult text = uploadBytes(
                "운영메모.txt",
                "text/plain",
                textBytes
        );
        UploadDocumentResult markdown = uploadBytes(
                "운영정책.md",
                "text/markdown",
                "# 운영 정책\n\n외부 전송을 금지합니다.".getBytes(StandardCharsets.UTF_8)
        );
        byte[] pdfBytes;
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("PrivateKB synthetic service agreement for document ingestion testing.");
                content.endText();
            }
            document.save(output);
            pdfBytes = output.toByteArray();
        }
        UploadDocumentResult pdf = uploadBytes("합성계약서.pdf", "application/pdf", pdfBytes);

        assertThat(awaitTerminal(text.ingestionJobId()).status()).isEqualTo(IngestionStatus.PARSED);
        assertThat(awaitTerminal(markdown.ingestionJobId()).status()).isEqualTo(IngestionStatus.PARSED);
        assertThat(awaitTerminal(pdf.ingestionJobId()).status()).isEqualTo(IngestionStatus.PARSED);

        UploadDocumentResult duplicate = uploadBytes(
                "다른이름.txt",
                "text/plain",
                textBytes
        );
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.documentVersionId()).isEqualTo(text.documentVersionId());
        assertThat(duplicate.ingestionJobId()).isEqualTo(text.ingestionJobId());

        List<String> transitions = jdbc.sql("""
                        SELECT to_status
                          FROM privatekb.ingestion_job_transition
                         WHERE ingestion_job_id = :jobId
                         ORDER BY transition_id
                        """)
                .param("jobId", text.ingestionJobId())
                .query(String.class)
                .list();
        assertThat(transitions).containsExactly("RECEIVED", "STORED", "PARSING", "PARSED");

        Integer extractedCount = jdbc.sql("""
                        SELECT count(*)
                          FROM privatekb.extracted_content
                         WHERE document_version_id IN (:textId, :markdownId, :pdfId)
                        """)
                .param("textId", text.documentVersionId())
                .param("markdownId", markdown.documentVersionId())
                .param("pdfId", pdf.documentVersionId())
                .query(Integer.class)
                .single();
        assertThat(extractedCount).isEqualTo(3);

        Integer pdfPageCount = jdbc.sql("""
                        SELECT page_count
                          FROM privatekb.extracted_content
                         WHERE document_version_id = :pdfId
                        """)
                .param("pdfId", pdf.documentVersionId())
                .query(Integer.class)
                .single();
        assertThat(pdfPageCount).isEqualTo(2);

        IndexingView indexed = awaitIndexed(text.documentVersionId());
        assertThat(indexed.status()).isEqualTo(IndexingStatus.INDEXED);
        assertThat(indexed.chunkCount()).isGreaterThan(1);
        assertThat(indexed.embeddingModel()).isEqualTo("test-embedding");
        assertThat(indexed.embeddingDimensions()).isEqualTo(1024);

        DocumentPageView documentPage = documentStatuses.find(DEFAULT_WORKSPACE, 0, 100);
        assertThat(documentPage.documents())
                .filteredOn(item -> item.documentVersionId().equals(text.documentVersionId()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo(DocumentEmbeddingStatus.COMPLETED);
                    assertThat(item.ingestionStatus()).isEqualTo(IngestionStatus.PARSED);
                    assertThat(item.indexingStatus()).isEqualTo(IndexingStatus.INDEXED);
                });

        SearchResponse response = search.search(DEFAULT_WORKSPACE, "운영 메모", 5);
        assertThat(response.results()).isNotEmpty();
        assertThat(response.results())
                .allSatisfy(result -> assertThat(result.content()).isNotBlank());
        assertThat(response.results())
                .extracting(result -> result.documentId())
                .doesNotHaveDuplicates();
        assertThat(response.results())
                .filteredOn(result -> result.documentVersionId().equals(text.documentVersionId()))
                .hasSize(1);

        Instant uploadWindowEnd = Instant.now().plusSeconds(1);
        List<SearchResultView> uploadedInWindow = searchRepository.searchByUploadTime(
                DEFAULT_WORKSPACE,
                "",
                uploadWindowStart,
                uploadWindowEnd,
                10
        );
        assertThat(uploadedInWindow)
                .extracting(SearchResultView::documentVersionId)
                .contains(text.documentVersionId());
        assertThat(uploadedInWindow)
                .extracting(SearchResultView::uploadedAt)
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(searchRepository.searchByUploadTime(
                DEFAULT_WORKSPACE,
                "",
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                10
        )).isEmpty();

        jdbc.sql("""
                        UPDATE privatekb.document_chunk
                           SET embedding_digest = 'previous-digest'
                         WHERE document_version_id = :versionId
                        """)
                .param("versionId", text.documentVersionId())
                .update();
        SearchResponse protectedFromStaleIndex = search.search(
                DEFAULT_WORKSPACE,
                "운영 메모",
                10
        );
        assertThat(protectedFromStaleIndex.results())
                .noneMatch(result -> result.documentVersionId().equals(text.documentVersionId()));

        jdbc.sql("""
                        UPDATE privatekb.indexing_job
                           SET embedding_digest = 'previous-digest'
                         WHERE document_version_id = :versionId
                        """)
                .param("versionId", text.documentVersionId())
                .update();
        assertThat(indexing.resumeAvailable()).isPositive();
        IndexingView reindexed = awaitIndexedWithDigest(
                text.documentVersionId(),
                "test-digest"
        );
        assertThat(reindexed.status()).isEqualTo(IndexingStatus.INDEXED);
        assertThat(reindexed.embeddingDigest()).isEqualTo("test-digest");
    }

    @Test
    void recordsFailureAndAllowsBoundedRetryWithoutPersistingErrorContent() throws Exception {
        byte[] blankPdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(output);
            blankPdf = output.toByteArray();
        }

        UploadDocumentResult result = uploadBytes("빈문서.pdf", "application/pdf", blankPdf);
        IngestionView firstFailure = awaitAttempt(result.ingestionJobId(), 1);

        assertThat(firstFailure.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(firstFailure.errorCode()).isEqualTo(IngestionErrorCode.OCR_NO_TEXT);
        assertThat(firstFailure.attemptCount()).isEqualTo(1);

        ingestion.retry(result.ingestionJobId());
        IngestionView secondFailure = awaitAttempt(result.ingestionJobId(), 2);

        assertThat(secondFailure.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(secondFailure.errorCode()).isEqualTo(IngestionErrorCode.OCR_NO_TEXT);
        assertThat(secondFailure.attemptCount()).isEqualTo(2);

        ingestion.retry(result.ingestionJobId());
        IngestionView thirdFailure = awaitAttempt(result.ingestionJobId(), 3);
        assertThat(thirdFailure.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(thirdFailure.attemptCount()).isEqualTo(3);

        assertThat(documentStatuses.find(DEFAULT_WORKSPACE, 0, 100).documents())
                .filteredOn(item -> item.documentVersionId().equals(result.documentVersionId()))
                .singleElement()
                .satisfies(item -> assertThat(item.status())
                        .isEqualTo(DocumentEmbeddingStatus.FAILED));

        assertThatThrownBy(() -> ingestion.retry(result.ingestionJobId()))
                .isInstanceOfSatisfying(UploadRejectedException.class, exception ->
                        assertThat(exception.code()).isEqualTo(UploadRejectionCode.RETRY_NOT_ALLOWED)
                );

        List<String> reasonCodes = jdbc.sql("""
                        SELECT reason_code
                          FROM privatekb.ingestion_job_transition
                         WHERE ingestion_job_id = :jobId
                           AND to_status = 'FAILED'
                         ORDER BY transition_id
                        """)
                .param("jobId", result.ingestionJobId())
                .query(String.class)
                .list();
        assertThat(reasonCodes).containsExactly("OCR_NO_TEXT", "OCR_NO_TEXT", "OCR_NO_TEXT");
    }

    @Test
    void routesImageOnlyPdfThroughDedicatedOcrStatesBeforeIndexing() throws Exception {
        byte[] imageOnlyPdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(output);
            imageOnlyPdf = output.toByteArray();
        }

        UploadDocumentResult result = uploadBytes(
                "OCR검증.pdf",
                "application/pdf",
                imageOnlyPdf
        );
        assertThat(awaitTerminal(result.ingestionJobId()).status()).isEqualTo(IngestionStatus.PARSED);
        assertThat(awaitIndexed(result.documentVersionId()).status()).isEqualTo(IndexingStatus.INDEXED);

        List<String> transitions = jdbc.sql("""
                        SELECT to_status
                          FROM privatekb.ingestion_job_transition
                         WHERE ingestion_job_id = :jobId
                         ORDER BY transition_id
                        """)
                .param("jobId", result.ingestionJobId())
                .query(String.class)
                .list();
        assertThat(transitions).containsExactly(
                "RECEIVED",
                "STORED",
                "PARSING",
                "OCR_PENDING",
                "OCR_RUNNING",
                "PARSED"
        );
    }

    private UploadDocumentResult uploadBytes(String filename, String mediaType, byte[] bytes) {
        return ingestion.submit(new UploadDocumentCommand(
                DEFAULT_WORKSPACE,
                filename,
                mediaType,
                bytes.length,
                () -> new ByteArrayInputStream(bytes)
        ));
    }

    private UploadDocumentResult desktopUpload(Path source) throws Exception {
        return desktopImports.submit(new DesktopDocumentImportCommand(
                DEFAULT_WORKSPACE,
                source.getFileName().toString(),
                "application/octet-stream",
                Files.size(source),
                Files.getLastModifiedTime(source).toMillis(),
                source.toString(),
                null,
                null,
                () -> Files.newInputStream(source)
        ));
    }

    private IngestionView awaitTerminal(UUID jobId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        IngestionView current;
        do {
            current = ingestion.find(jobId);
            if (current.status() == IngestionStatus.PARSED || current.status() == IngestionStatus.FAILED) {
                return current;
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        return current;
    }

    private IngestionView awaitAttempt(UUID jobId, int expectedAttempt) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        IngestionView current;
        do {
            current = ingestion.find(jobId);
            if (current.attemptCount() >= expectedAttempt
                    && (current.status() == IngestionStatus.PARSED
                    || current.status() == IngestionStatus.FAILED)) {
                return current;
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        return current;
    }

    private IndexingView awaitIndexed(UUID documentVersionId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        IndexingView current;
        do {
            current = indexing.find(documentVersionId);
            if (current.status() == IndexingStatus.INDEXED
                    || current.status() == IndexingStatus.FAILED) {
                return current;
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        return current;
    }

    private IndexingView awaitIndexedWithDigest(
            UUID documentVersionId,
            String expectedDigest
    ) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        IndexingView current;
        do {
            current = indexing.find(documentVersionId);
            if (current.status() == IndexingStatus.INDEXED
                    && expectedDigest.equals(current.embeddingDigest())) {
                return current;
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        return current;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EmbeddingTestConfiguration {

        @Bean
        @Primary
        LocalEmbeddingClient deterministicEmbeddingClient() {
            return new LocalEmbeddingClient() {
                @Override
                public EmbeddingModelInfo verifyModel() {
                    return new EmbeddingModelInfo("test-embedding", "test-digest", 1024);
                }

                @Override
                public List<float[]> embed(List<String> texts) {
                    return texts.stream().map(ignored -> {
                        float[] vector = new float[1024];
                        vector[0] = 1.0f;
                        return vector;
                    }).toList();
                }
            };
        }

        @Bean
        @Primary
        PdfOcrService deterministicPdfOcrService() {
            return (source, filename, detectedMediaType) -> {
                if (filename.startsWith("빈문서")) {
                    throw new TextExtractionException(IngestionErrorCode.OCR_NO_TEXT, null);
                }
                return new ExtractedText(
                        "OCR로 인식한 합성 운영 문서입니다. 장애 대응 담당자와 후속 조치를 확인했습니다.",
                        detectedMediaType,
                        1,
                        "시험 OCR"
                );
            };
        }
    }

    private static Path createTemporaryStorageRoot() {
        try {
            return Files.createTempDirectory("privatekb-ingestion-test-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @AfterAll
    static void removeTemporaryStorage() throws IOException {
        if (!Files.exists(STORAGE_ROOT)) {
            return;
        }
        try (var paths = Files.walk(STORAGE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
