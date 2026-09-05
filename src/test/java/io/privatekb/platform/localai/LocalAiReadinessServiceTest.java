package io.privatekb.platform.localai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class LocalAiReadinessServiceTest {

    private static final Instant CHECK_TIME = Instant.parse("2026-08-24T01:00:00Z");

    @Test
    void enablesAllCapabilitiesWhenBothModelsAreInstalled() {
        OllamaStatusClient client = () -> OllamaProbeResult.connected(
                "0.12.0",
                List.of(
                        new OllamaModelInfo("qwen3-embedding:0.6b", "embedding-digest", 639_150_858L),
                        new OllamaModelInfo("qwen3.5:4b", "chat-digest", 3_400_000_000L)
                )
        );
        LocalAiReadinessService service = service(client, Runnable::run);

        LocalAiStatusView status = service.triggerCheck();

        assertThat(status.ollama().status()).isEqualTo(OllamaConnectionStatus.CONNECTED);
        assertThat(status.embedding().status()).isEqualTo(LocalAiModelStatus.READY);
        assertThat(status.chat().status()).isEqualTo(LocalAiModelStatus.READY);
        assertThat(status.capabilities().documentManagement()).isTrue();
        assertThat(status.capabilities().semanticSearch()).isTrue();
        assertThat(status.capabilities().groundedAnswer()).isTrue();
        assertThat(status.lastCheckedAt()).isEqualTo(CHECK_TIME);
        assertThat(status.checkInProgress()).isFalse();
    }

    @Test
    void keepsSearchEnabledButDisablesAnswersWhenChatModelIsMissing() {
        OllamaStatusClient client = () -> OllamaProbeResult.connected(
                "0.12.0",
                List.of(new OllamaModelInfo(
                        "qwen3-embedding:0.6b",
                        "embedding-digest",
                        639_150_858L
                ))
        );
        LocalAiReadinessService service = service(client, Runnable::run);

        LocalAiStatusView status = service.triggerCheck();

        assertThat(status.embedding().status()).isEqualTo(LocalAiModelStatus.READY);
        assertThat(status.chat().status()).isEqualTo(LocalAiModelStatus.NOT_INSTALLED);
        assertThat(status.capabilities().semanticSearch()).isTrue();
        assertThat(status.capabilities().groundedAnswer()).isFalse();
    }

    @Test
    void keepsSearchEnabledWhenChatIsExplicitlyDisabled() {
        OllamaStatusClient client = () -> OllamaProbeResult.connected(
                "0.12.0",
                List.of(new OllamaModelInfo(
                        "qwen3-embedding:0.6b",
                        "embedding-digest",
                        639_150_858L
                ))
        );
        LocalAiReadinessService service = service(
                client,
                Runnable::run,
                ChatModelProfile.DISABLED
        );

        LocalAiStatusView status = service.triggerCheck();

        assertThat(status.chat().status()).isEqualTo(LocalAiModelStatus.DISABLED);
        assertThat(status.chatProfile().profile()).isEqualTo(ChatModelProfile.DISABLED);
        assertThat(status.capabilities().semanticSearch()).isTrue();
        assertThat(status.capabilities().groundedAnswer()).isFalse();
    }

    @Test
    void recognizesInstalledHighSpecModelByItsExactTag() {
        OllamaStatusClient client = () -> OllamaProbeResult.connected(
                "0.33.1",
                List.of(
                        new OllamaModelInfo("qwen3-embedding:0.6b", "embedding-digest", 639_150_858L),
                        new OllamaModelInfo("qwen3.5:9b", "chat-digest", 6_594_474_711L)
                )
        );
        LocalAiReadinessService service = service(
                client,
                Runnable::run,
                ChatModelProfile.HIGH_SPEC
        );

        LocalAiStatusView status = service.triggerCheck();

        assertThat(status.chatProfile().profile()).isEqualTo(ChatModelProfile.HIGH_SPEC);
        assertThat(status.chatProfile().displayName()).isEqualTo("고사양");
        assertThat(status.chat().model()).isEqualTo("qwen3.5:9b");
        assertThat(status.chat().status()).isEqualTo(LocalAiModelStatus.READY);
        assertThat(status.capabilities().groundedAnswer()).isTrue();
    }

    @Test
    void keepsOnlyDocumentManagementWhenOllamaIsNotRunning() {
        OllamaStatusClient client = () -> OllamaProbeResult.failed(
                OllamaConnectionStatus.NOT_RUNNING,
                "OLLAMA_NOT_RUNNING"
        );
        LocalAiReadinessService service = service(client, Runnable::run);

        LocalAiStatusView status = service.triggerCheck();

        assertThat(status.ollama().status()).isEqualTo(OllamaConnectionStatus.NOT_RUNNING);
        assertThat(status.embedding().status()).isEqualTo(LocalAiModelStatus.ERROR);
        assertThat(status.chat().status()).isEqualTo(LocalAiModelStatus.ERROR);
        assertThat(status.capabilities().documentManagement()).isTrue();
        assertThat(status.capabilities().semanticSearch()).isFalse();
        assertThat(status.capabilities().groundedAnswer()).isFalse();
    }

    @Test
    void coalescesRepeatedChecksWhileOneIsRunning() {
        QueueingExecutor executor = new QueueingExecutor();
        AtomicInteger calls = new AtomicInteger();
        OllamaStatusClient client = () -> {
            calls.incrementAndGet();
            return OllamaProbeResult.connected("0.12.0", List.of());
        };
        LocalAiReadinessService service = service(client, executor);

        LocalAiStatusView first = service.triggerCheck();
        LocalAiStatusView second = service.triggerCheck();

        assertThat(first.checkInProgress()).isTrue();
        assertThat(second.checkInProgress()).isTrue();
        assertThat(executor.size()).isOne();
        assertThat(calls).hasValue(0);

        executor.runNext();

        assertThat(calls).hasValue(1);
        assertThat(service.status().ollama().status()).isEqualTo(OllamaConnectionStatus.CONNECTED);
    }

    private LocalAiReadinessService service(OllamaStatusClient client, Executor executor) {
        return service(client, executor, ChatModelProfile.STANDARD);
    }

    private LocalAiReadinessService service(
            OllamaStatusClient client,
            Executor executor,
            ChatModelProfile profile
    ) {
        return new LocalAiReadinessService(
                client,
                properties(),
                () -> profile,
                executor,
                Clock.fixed(CHECK_TIME, ZoneOffset.UTC)
        );
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

    private static final class QueueingExecutor implements Executor {

        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
