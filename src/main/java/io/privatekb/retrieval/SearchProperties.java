package io.privatekb.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("privatekb.search")
record SearchProperties(
        @Min(2) int minimumQueryCharacters,
        @Min(10) int maximumQueryCharacters,
        @Min(1) @Max(20) int maximumResults,
        @Min(2) @Max(50) int candidateMultiplier,
        @Min(5) @Max(100) int minimumCandidates,
        @Min(20) @Max(500) int maximumCandidates,
        @DecimalMin("0.0") @DecimalMax("1.0") double minimumResultScore
) {
    int candidateLimit(int resultLimit) {
        return Math.min(
                maximumCandidates,
                Math.max(minimumCandidates, resultLimit * candidateMultiplier)
        );
    }
}
