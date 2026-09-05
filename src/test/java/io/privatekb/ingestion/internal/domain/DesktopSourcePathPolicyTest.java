package io.privatekb.ingestion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSourcePathPolicyTest {

    private final DesktopSourcePathPolicy policy = new DesktopSourcePathPolicy();

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsLocalFileAndMatchingFolderRelativePath() throws Exception {
        Path root = temporaryDirectory.resolve("고객 자료");
        Path source = root.resolve("회의").resolve("회의록.txt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "회의 결과");

        DesktopSourcePathPolicy.PreparedSource prepared = policy.prepare(
                "회의록.txt",
                source.toString(),
                root.toString(),
                Path.of("회의", "회의록.txt").toString(),
                Files.size(source),
                Files.getLastModifiedTime(source).toMillis()
        );

        assertThat(prepared.sourcePath()).endsWith("회의록.txt");
        assertThat(prepared.relativePath()).isEqualTo("회의\\회의록.txt");
        assertThat(prepared.sourcePathHash()).hasSize(64);
        assertThat(prepared.sourceRootPathHash()).hasSize(64);
    }

    @Test
    void rejectsChangedFileMetadata() throws Exception {
        Path source = temporaryDirectory.resolve("변경.txt");
        Files.writeString(source, "변경 전");

        assertThatThrownBy(() -> policy.prepare(
                "변경.txt",
                source.toString(),
                null,
                null,
                Files.size(source) + 1,
                Files.getLastModifiedTime(source).toMillis()
        ))
                .isInstanceOf(DesktopImportException.class)
                .extracting(exception -> ((DesktopImportException) exception).code())
                .isEqualTo(DesktopImportErrorCode.SOURCE_FILE_CHANGED);
    }

    @Test
    void rejectsMismatchedRelativePath() throws Exception {
        Path source = temporaryDirectory.resolve("폴더").resolve("문서.txt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "본문");

        assertThatThrownBy(() -> policy.prepare(
                "문서.txt",
                source.toString(),
                source.getParent().toString(),
                "다른문서.txt",
                Files.size(source),
                Files.getLastModifiedTime(source).toMillis()
        ))
                .isInstanceOf(DesktopImportException.class)
                .extracting(exception -> ((DesktopImportException) exception).code())
                .isEqualTo(DesktopImportErrorCode.INVALID_SOURCE_LOCATION);
    }
}
