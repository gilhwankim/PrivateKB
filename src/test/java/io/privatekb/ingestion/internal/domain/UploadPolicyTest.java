package io.privatekb.ingestion.internal.domain;

import io.privatekb.ingestion.internal.config.IngestionProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.atomic.AtomicLong;

class UploadPolicyTest {

    private final UploadPolicy policy = new UploadPolicy(new IngestionProperties(
            1024,
            10_000,
            Duration.ofSeconds(1),
            3
    ), () -> 25_000_000L);

    @ParameterizedTest
    @MethodSource("supportedFiles")
    void acceptsSupportedExtensionAndDetectedContent(
            String filename,
            String declared,
            String detected
    ) {
        UploadPolicy.PreparedUpload prepared = policy.validateRequest(filename, declared, 20);

        policy.requireCompatibleDetection(prepared, detected);

        assertThat(prepared.normalizedFilename()).isEqualTo(filename);
    }

    @ParameterizedTest
    @MethodSource("unsafeNames")
    void rejectsUnsafeOrTraversingFileNames(String filename) {
        assertThatThrownBy(() -> policy.validateRequest(filename, "text/plain", 20))
                .isInstanceOfSatisfying(UploadRejectedException.class, exception ->
                        assertThat(exception.code()).isEqualTo(UploadRejectionCode.INVALID_FILENAME)
                );
    }

    @Test
    void rejectsFileThatExceedsTheConfiguredLimit() {
        assertThatThrownBy(() -> policy.validateRequest("large.txt", "text/plain", 1025))
                .isInstanceOfSatisfying(UploadRejectedException.class, exception ->
                        assertThat(exception.code()).isEqualTo(UploadRejectionCode.FILE_TOO_LARGE)
                );
    }

    @Test
    void rejectsMismatchedDetectedType() {
        UploadPolicy.PreparedUpload prepared = policy.validateRequest(
                "disguised.pdf",
                "application/pdf",
                20
        );

        assertThatThrownBy(() -> policy.requireCompatibleDetection(prepared, "text/plain"))
                .isInstanceOfSatisfying(UploadRejectedException.class, exception ->
                        assertThat(exception.code()).isEqualTo(UploadRejectionCode.MEDIA_TYPE_MISMATCH)
                );
    }

    @ParameterizedTest
    @ValueSource(longs = {25_000_000L, 50_000_000L, 100_000_000L})
    void acceptsExactProfileBoundaryAndRejectsOneByteMore(long maximum) {
        UploadPolicy selected = new UploadPolicy(new IngestionProperties(
                100_000_000L, 5_000_000, Duration.ofSeconds(30), 3), () -> maximum);
        assertThat(selected.validateRequest("경계.pdf", "application/pdf", maximum).maximumUploadBytes())
                .isEqualTo(maximum);
        assertThatThrownBy(() -> selected.validateRequest("초과.pdf", "application/pdf", maximum + 1))
                .isInstanceOfSatisfying(UploadRejectedException.class, exception ->
                        assertThat(exception.code()).isEqualTo(UploadRejectionCode.FILE_TOO_LARGE));
    }

    @Test
    void capturesLimitForTheAcceptedStreamAndAppliesNewLimitToNextUpload() {
        AtomicLong current = new AtomicLong(100_000_000L);
        UploadPolicy selected = new UploadPolicy(new IngestionProperties(
                100_000_000L, 5_000_000, Duration.ofSeconds(30), 3), current::get);
        var prepared = selected.validateRequest("보고서.pdf", "application/pdf", 50_000_000);
        current.set(25_000_000);
        assertThat(prepared.maximumUploadBytes()).isEqualTo(100_000_000L);
        assertThatThrownBy(() -> selected.validateRequest("보고서.pdf", "application/pdf", 50_000_000))
                .isInstanceOf(UploadRejectedException.class);
    }

    private static Stream<Arguments> supportedFiles() {
        return Stream.of(
                Arguments.of("계약서.pdf", "application/pdf", "application/pdf"),
                Arguments.of("운영정책.md", "text/markdown; charset=UTF-8", "text/x-web-markdown"),
                Arguments.of("메모.txt", "text/plain", "text/plain"),
                Arguments.of("제안서.doc", "application/msword", "application/msword"),
                Arguments.of("제안서.docx", "application/octet-stream",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                Arguments.of("발표자료.ppt", "application/vnd.ms-powerpoint",
                        "application/vnd.ms-powerpoint"),
                Arguments.of("발표자료.pptx",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                Arguments.of("예산표.xls", "application/vnd.ms-excel", "application/vnd.ms-excel"),
                Arguments.of("예산표.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                Arguments.of("회의록.hwp", "application/x-hwp", "application/x-hwp-v5"),
                Arguments.of("브라우저형식없음.hwp", "", "application/x-hwp-v5")
        );
    }

    private static Stream<String> unsafeNames() {
        return Stream.of(
                "../secret.txt",
                "..\\secret.txt",
                "C:\\secret.txt",
                "CON.txt",
                "report..txt",
                "trailing.txt."
        );
    }
}
