package io.privatekb.ingestion.internal.config;

import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("privatekb.ocr")
public record OcrProperties(
        boolean enabled,
        @NotNull Path executableDirectory,
        @NotNull Path dataPath,
        @NotBlank String language,
        @Min(150) @Max(600) int dpi,
        @Min(1) int maximumPages,
        @Min(1) long maximumPixelsPerPage,
        @Min(1) long maximumTotalPixels,
        @Min(1) long minimumTemporaryFreeBytes,
        @NotNull Duration pageTimeout,
        @NotNull Duration documentTimeout,
        @Min(1) int resumeBatchSize
) {
}
