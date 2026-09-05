package io.privatekb.platform.localai;

import java.time.Instant;

record LocalAiStatusView(
        OllamaStatusView ollama,
        ModelStatusView embedding,
        ModelStatusView chat,
        ChatProfileView chatProfile,
        CapabilityStatusView capabilities,
        Instant lastCheckedAt,
        boolean checkInProgress
) {
    record OllamaStatusView(
            OllamaConnectionStatus status,
            String version,
            String errorCode
    ) {
    }

    record ModelStatusView(
            String model,
            LocalAiModelStatus status,
            String digest,
            long sizeBytes,
            String errorCode
    ) {
    }

    record ChatProfileView(
            ChatModelProfile profile,
            String displayName,
            String model,
            long estimatedDownloadBytes,
            int contextLength,
            int maximumGeneratedTokens,
            int maximumSourceDocuments,
            long maximumUploadBytes
    ) {
    }

    record CapabilityStatusView(
            boolean documentManagement,
            boolean semanticSearch,
            boolean groundedAnswer
    ) {
    }
}
