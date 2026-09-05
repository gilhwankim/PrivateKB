package io.privatekb.ingestion.internal.parsing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
class OcrTemporaryFileCleaner {

    private static final Logger log = LoggerFactory.getLogger(OcrTemporaryFileCleaner.class);

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void removeFilesLeftByInterruptedRuns() {
        Path root = Path.of(
                        System.getProperty("java.io.tmpdir"),
                        TesseractPdfOcrService.TEMPORARY_DIRECTORY_NAME
                )
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            return;
        }
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                deleteTree(child);
            }
        } catch (IOException exception) {
            log.warn("이전 OCR 임시 파일 정리 실패: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void deleteTree(Path target) throws IOException {
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
