package io.privatekb.platform.localai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.privatekb.platform.LocalModelPullClient;
import io.privatekb.platform.LocalModelPullClient.ModelPullProgress;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalAiModelInstallServiceTest {

    private LocalModelPullClient pullClient;
    private OllamaStatusClient statusClient;
    private LocalAiReadinessService readiness;
    private LocalAiModelInstallService service;

    @BeforeEach
    void setUp() {
        pullClient = mock(LocalModelPullClient.class);
        statusClient = mock(OllamaStatusClient.class);
        readiness = mock(LocalAiReadinessService.class);
        service = new LocalAiModelInstallService(
                pullClient,
                statusClient,
                readiness,
                properties(),
                () -> ChatModelProfile.STANDARD,
                new DirectExecutorService(),
                Clock.fixed(Instant.parse("2026-08-27T01:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void requiresExplicitConfirmationWithoutStartingDownload() {
        assertThatThrownBy(() -> service.start(LocalAiModelRole.CHAT, false))
                .isInstanceOfSatisfying(ModelInstallException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                "MODEL_INSTALL_CONFIRMATION_REQUIRED"
                        )
                );
        verify(pullClient, never()).pull(any(), any(), any());
    }

    @Test
    void downloadsOnlyConfiguredChatModelAndVerifiesInstallation() {
        when(statusClient.probe()).thenReturn(
                OllamaProbeResult.connected("0.12.0", List.of()),
                OllamaProbeResult.connected("0.12.0", List.of(
                        new OllamaModelInfo("qwen3.5:4b", "digest", 3_400_000_000L)
                ))
        );
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<ModelPullProgress> progress = invocation.getArgument(1);
            progress.accept(new ModelPullProgress(50, 100));
            return null;
        }).when(pullClient).pull(eq("qwen3.5:4b"), any(), any());

        ModelInstallView result = service.start(LocalAiModelRole.CHAT, true);

        assertThat(result.model()).isEqualTo("qwen3.5:4b");
        assertThat(result.role()).isEqualTo(LocalAiModelRole.CHAT);
        assertThat(result.status()).isEqualTo(ModelInstallStatus.COMPLETED);
        assertThat(result.progressPercent()).isEqualTo(100);
        verify(readiness).triggerCheck();
    }

    @Test
    void completesWithoutPullingWhenConfiguredModelAlreadyExists() {
        when(statusClient.probe()).thenReturn(OllamaProbeResult.connected(
                "0.12.0",
                List.of(new OllamaModelInfo(
                        "qwen3-embedding:0.6b",
                        "digest",
                        639_150_858L
                ))
        ));

        ModelInstallView result = service.start(LocalAiModelRole.EMBEDDING, true);

        assertThat(result.status()).isEqualTo(ModelInstallStatus.COMPLETED);
        verify(pullClient, never()).pull(any(), any(), any());
    }

    @Test
    void rejectsChatInstallWhenChatIsDisabledWithoutOccupyingInstallSlot() {
        LocalAiModelInstallService disabledService = new LocalAiModelInstallService(
                pullClient,
                statusClient,
                readiness,
                properties(),
                () -> ChatModelProfile.DISABLED,
                new DirectExecutorService(),
                Clock.fixed(Instant.parse("2026-08-27T01:00:00Z"), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> disabledService.start(LocalAiModelRole.CHAT, true))
                .isInstanceOfSatisfying(ModelInstallException.class, exception ->
                        assertThat(exception.code()).isEqualTo("CHAT_MODEL_DISABLED")
                );

        when(statusClient.probe()).thenReturn(OllamaProbeResult.connected(
                "0.12.0",
                List.of(new OllamaModelInfo(
                        "qwen3-embedding:0.6b",
                        "digest",
                        639_150_858L
                ))
        ));
        assertThat(disabledService.start(LocalAiModelRole.EMBEDDING, true).status())
                .isEqualTo(ModelInstallStatus.COMPLETED);
    }

    private LocalAiProperties properties() {
        return new LocalAiProperties(
                URI.create("http://127.0.0.1:11434"),
                "qwen3-embedding:0.6b",
                1024,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofMinutes(2),
                Duration.ofMinutes(3),
                Duration.ofHours(2),
                true
        );
    }

    private static final class DirectExecutorService extends AbstractExecutorService {

        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
