package io.privatekb.ingestion.internal.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("privatekb.indexing")
public record IndexingProperties(
        @Min(200) @Max(4000) int chunkSize,
        @Min(0) int chunkOverlap,
        @Min(1) @Max(32) int embeddingBatchSize,
        @Min(1) @Max(256) int databaseBatchSize,
        @Min(0) @Max(5) int databaseBatchRetries,
        @NotNull Duration stagingRetention,
        @Min(1) int maxAttempts
) {
    public IndexingProperties {
        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("Chunk overlap must be smaller than chunk size");
        }
    }
}
