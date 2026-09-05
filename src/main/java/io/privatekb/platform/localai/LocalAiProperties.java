package io.privatekb.platform.localai;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("privatekb.local-ai")
public record LocalAiProperties(
        URI baseUrl,
        String embeddingModel,
        int embeddingDimensions,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration embeddingRequestTimeout,
        Duration chatRequestTimeout,
        Duration modelInstallTimeout,
        boolean startupCheckEnabled
) {
}
