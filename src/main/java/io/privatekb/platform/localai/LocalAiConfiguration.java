package io.privatekb.platform.localai;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.privatekb.platform.LocalEmbeddingClient;
import io.privatekb.platform.LocalChatClient;
import io.privatekb.platform.LocalModelPullClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalAiProperties.class)
class LocalAiConfiguration {

    @Bean
    LocalAiEndpointPolicy localAiEndpointPolicy(LocalAiProperties properties) {
        LocalAiEndpointPolicy policy = new LocalAiEndpointPolicy();
        policy.requireLocalOllama(properties.baseUrl());
        return policy;
    }

    @Bean
    OllamaStatusClient ollamaStatusClient(
            LocalAiProperties properties,
            LocalAiEndpointPolicy endpointPolicy,
            ObjectMapper objectMapper
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        return new HttpOllamaStatusClient(
                endpointPolicy.requireLocalOllama(properties.baseUrl()),
                properties.requestTimeout(),
                httpClient,
                objectMapper
        );
    }

    @Bean
    LocalEmbeddingClient localEmbeddingClient(
            LocalAiProperties properties,
            LocalAiEndpointPolicy endpointPolicy,
            ObjectMapper objectMapper
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        return new HttpOllamaEmbeddingClient(
                endpointPolicy.requireLocalOllama(properties.baseUrl()),
                properties.embeddingModel(),
                properties.embeddingDimensions(),
                properties.embeddingRequestTimeout(),
                httpClient,
                objectMapper
        );
    }

    @Bean
    LocalChatClient localChatClient(
            LocalAiProperties properties,
            ChatModelProfileProvider profiles,
            LocalAiEndpointPolicy endpointPolicy,
            ObjectMapper objectMapper
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        return new HttpOllamaChatClient(
                endpointPolicy.requireLocalOllama(properties.baseUrl()),
                profiles,
                properties.chatRequestTimeout(),
                httpClient,
                objectMapper
        );
    }

    @Bean
    LocalModelPullClient localModelPullClient(
            LocalAiProperties properties,
            LocalAiEndpointPolicy endpointPolicy,
            ObjectMapper objectMapper
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        return new HttpOllamaModelPullClient(
                endpointPolicy.requireLocalOllama(properties.baseUrl()),
                properties.modelInstallTimeout(),
                httpClient,
                objectMapper
        );
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService localAiCheckExecutor() {
        return Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("local-ai-check-", 0).factory()
        );
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService localAiModelInstallExecutor() {
        return Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("local-ai-model-install-", 0).factory()
        );
    }

    @Bean
    Clock localAiClock() {
        return Clock.systemUTC();
    }

    @Bean
    LocalAiReadinessService localAiReadinessService(
            OllamaStatusClient client,
            LocalAiProperties properties,
            ChatModelProfileProvider profiles,
            ExecutorService localAiCheckExecutor,
            Clock localAiClock
    ) {
        return new LocalAiReadinessService(
                client,
                properties,
                profiles,
                localAiCheckExecutor,
                localAiClock
        );
    }
}
