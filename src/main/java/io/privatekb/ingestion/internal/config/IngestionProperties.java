package io.privatekb.ingestion.internal.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("privatekb.ingestion")
public record IngestionProperties(
        @Min(1) long maxUploadBytes,
        @Min(1) int maxExtractedCharacters,
        @NotNull Duration parsingTimeout,
        @Min(1) int maxAttempts
) {
}
