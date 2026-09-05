package io.privatekb.retrieval;

import java.time.Duration;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("privatekb.answer")
record AnswerProperties(
        @Min(2) int minimumQuestionCharacters,
        @Min(10) int maximumQuestionCharacters,
        @Min(1) @Max(12) int maximumSourceChunks,
        @DecimalMin("0.0") @DecimalMax("1.0") double minimumGroundingScore,
        @Min(1000) @Max(30000) int maximumContextCharacters,
        Duration streamTimeout
) {
}
