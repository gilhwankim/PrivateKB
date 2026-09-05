package io.privatekb.platform;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface LocalChatClient {

    ChatModelInfo verifyModel();

    String complete(List<ChatMessage> messages, int maximumTokens);

    void stream(
            List<ChatMessage> messages,
            Consumer<String> tokenConsumer,
            BooleanSupplier cancelled
    );

    record ChatModelInfo(String model, String digest) {
    }

    record ChatMessage(String role, String content) {
    }
}
