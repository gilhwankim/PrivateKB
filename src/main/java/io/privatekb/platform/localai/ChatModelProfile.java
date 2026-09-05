package io.privatekb.platform.localai;

public enum ChatModelProfile {
    DISABLED("미사용", null, 0L, 0, 0, 0, 0),
    LOW_SPEC("저사양", "qwen3.5:2b-q4_K_M", 1_900_000_000L, 4096, 512, 3, 7_000),
    STANDARD("일반", "qwen3.5:4b", 3_400_000_000L, 8192, 1024, 5, 12_000),
    HIGH_SPEC("고사양", "qwen3.5:9b", 6_600_000_000L, 8192, 1536, 7, 18_000);

    private final String displayName;
    private final String model;
    private final long estimatedDownloadBytes;
    private final int contextLength;
    private final int maximumGeneratedTokens;
    private final int maximumSourceDocuments;
    private final int maximumContextCharacters;

    ChatModelProfile(
            String displayName,
            String model,
            long estimatedDownloadBytes,
            int contextLength,
            int maximumGeneratedTokens,
            int maximumSourceDocuments,
            int maximumContextCharacters
    ) {
        this.displayName = displayName;
        this.model = model;
        this.estimatedDownloadBytes = estimatedDownloadBytes;
        this.contextLength = contextLength;
        this.maximumGeneratedTokens = maximumGeneratedTokens;
        this.maximumSourceDocuments = maximumSourceDocuments;
        this.maximumContextCharacters = maximumContextCharacters;
    }

    String displayName() {
        return displayName;
    }

    String model() {
        return model;
    }

    long estimatedDownloadBytes() {
        return estimatedDownloadBytes;
    }

    int contextLength() {
        return contextLength;
    }

    int maximumGeneratedTokens() {
        return maximumGeneratedTokens;
    }

    int maximumSourceDocuments() {
        return maximumSourceDocuments;
    }

    int maximumContextCharacters() {
        return maximumContextCharacters;
    }

    boolean enabled() {
        return this != DISABLED;
    }

    long maximumUploadBytes() {
        return switch (this) {
            case DISABLED, LOW_SPEC -> 25_000_000L;
            case STANDARD -> 50_000_000L;
            case HIGH_SPEC -> 100_000_000L;
        };
    }
}
