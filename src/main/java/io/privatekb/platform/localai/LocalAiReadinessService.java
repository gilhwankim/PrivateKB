package io.privatekb.platform.localai;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

final class LocalAiReadinessService {

    private static final String MODEL_NOT_INSTALLED = "MODEL_NOT_INSTALLED";
    private static final String MODEL_STATUS_UNAVAILABLE = "MODEL_STATUS_UNAVAILABLE";

    private final OllamaStatusClient client;
    private final LocalAiProperties properties;
    private final ChatModelProfileProvider profiles;
    private final Executor executor;
    private final Clock clock;
    private final AtomicBoolean checkInProgress = new AtomicBoolean();
    private final AtomicReference<LocalAiStatusView> current;

    LocalAiReadinessService(
            OllamaStatusClient client,
            LocalAiProperties properties,
            ChatModelProfileProvider profiles,
            Executor executor,
            Clock clock
    ) {
        this.client = client;
        this.properties = properties;
        this.profiles = profiles;
        this.executor = executor;
        this.clock = clock;
        this.current = new AtomicReference<>(checkingStatus(null, false));
    }

    @EventListener(ApplicationReadyEvent.class)
    void checkWhenApplicationIsReady() {
        if (properties.startupCheckEnabled()) {
            triggerCheck();
        }
    }

    LocalAiStatusView status() {
        return current.get();
    }

    LocalAiStatusView triggerCheck() {
        if (!checkInProgress.compareAndSet(false, true)) {
            return current.get();
        }

        Instant previousCheck = current.get().lastCheckedAt();
        current.set(checkingStatus(previousCheck, true));
        try {
            executor.execute(this::performCheck);
        } catch (RuntimeException exception) {
            checkInProgress.set(false);
            current.set(failedStatus("OLLAMA_CHECK_FAILED"));
        }
        return current.get();
    }

    LocalAiStatusView configurationChanged() {
        LocalAiStatusView previous = current.get();
        current.set(checkingStatus(previous.lastCheckedAt(), checkInProgress.get()));
        return triggerCheck();
    }

    private void performCheck() {
        try {
            current.set(toStatus(client.probe()));
        } catch (RuntimeException exception) {
            current.set(failedStatus("OLLAMA_CHECK_FAILED"));
        } finally {
            checkInProgress.set(false);
        }
    }

    private LocalAiStatusView toStatus(OllamaProbeResult probe) {
        if (probe.status() != OllamaConnectionStatus.CONNECTED) {
            return unavailableStatus(probe);
        }

        LocalAiStatusView.ModelStatusView embedding = modelStatus(
                properties.embeddingModel(),
                probe
        );
        ChatModelProfile profile = profiles.current();
        LocalAiStatusView.ModelStatusView chat = profile.enabled()
                ? modelStatus(profile.model(), probe)
                : disabledModel();
        boolean embeddingReady = embedding.status() == LocalAiModelStatus.READY;
        boolean chatReady = chat.status() == LocalAiModelStatus.READY;
        return new LocalAiStatusView(
                new LocalAiStatusView.OllamaStatusView(
                        OllamaConnectionStatus.CONNECTED,
                        probe.version(),
                        null
                ),
                embedding,
                chat,
                profileView(profile),
                new LocalAiStatusView.CapabilityStatusView(
                        true,
                        embeddingReady,
                        embeddingReady && profile.enabled() && chatReady
                ),
                Instant.now(clock),
                false
        );
    }

    private LocalAiStatusView.ModelStatusView modelStatus(
            String expectedModel,
            OllamaProbeResult probe
    ) {
        return probe.models().stream()
                .filter(model -> Objects.equals(model.name(), expectedModel))
                .findFirst()
                .map(model -> new LocalAiStatusView.ModelStatusView(
                        expectedModel,
                        LocalAiModelStatus.READY,
                        model.digest(),
                        model.sizeBytes(),
                        null
                ))
                .orElseGet(() -> new LocalAiStatusView.ModelStatusView(
                        expectedModel,
                        LocalAiModelStatus.NOT_INSTALLED,
                        null,
                        0L,
                        MODEL_NOT_INSTALLED
                ));
    }

    private LocalAiStatusView checkingStatus(Instant lastCheckedAt, boolean inProgress) {
        ChatModelProfile profile = profiles.current();
        return new LocalAiStatusView(
                new LocalAiStatusView.OllamaStatusView(
                        OllamaConnectionStatus.CHECKING,
                        null,
                        null
                ),
                checkingModel(properties.embeddingModel()),
                profile.enabled() ? checkingModel(profile.model()) : disabledModel(),
                profileView(profile),
                new LocalAiStatusView.CapabilityStatusView(true, false, false),
                lastCheckedAt,
                inProgress
        );
    }

    private LocalAiStatusView.ModelStatusView checkingModel(String model) {
        return new LocalAiStatusView.ModelStatusView(
                model,
                LocalAiModelStatus.CHECKING,
                null,
                0L,
                null
        );
    }

    private LocalAiStatusView unavailableStatus(OllamaProbeResult probe) {
        ChatModelProfile profile = profiles.current();
        return new LocalAiStatusView(
                new LocalAiStatusView.OllamaStatusView(
                        probe.status(),
                        null,
                        probe.errorCode()
                ),
                unavailableModel(properties.embeddingModel()),
                profile.enabled() ? unavailableModel(profile.model()) : disabledModel(),
                profileView(profile),
                new LocalAiStatusView.CapabilityStatusView(true, false, false),
                Instant.now(clock),
                false
        );
    }

    private LocalAiStatusView failedStatus(String errorCode) {
        return unavailableStatus(OllamaProbeResult.failed(
                OllamaConnectionStatus.CHECK_FAILED,
                errorCode
        ));
    }

    private LocalAiStatusView.ModelStatusView unavailableModel(String model) {
        return new LocalAiStatusView.ModelStatusView(
                model,
                LocalAiModelStatus.ERROR,
                null,
                0L,
                MODEL_STATUS_UNAVAILABLE
        );
    }

    private LocalAiStatusView.ModelStatusView disabledModel() {
        return new LocalAiStatusView.ModelStatusView(
                null,
                LocalAiModelStatus.DISABLED,
                null,
                0L,
                null
        );
    }

    private LocalAiStatusView.ChatProfileView profileView(ChatModelProfile profile) {
        return new LocalAiStatusView.ChatProfileView(
                profile,
                profile.displayName(),
                profile.model(),
                profile.estimatedDownloadBytes(),
                profile.contextLength(),
                profile.maximumGeneratedTokens(),
                profile.maximumSourceDocuments(),
                profiles.maximumUploadBytes(profile)
        );
    }
}
