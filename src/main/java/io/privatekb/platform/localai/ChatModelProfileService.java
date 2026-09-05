package io.privatekb.platform.localai;

import io.privatekb.platform.LocalChatRuntimePolicy;
import io.privatekb.platform.LocalDocumentUploadPolicy;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
final class ChatModelProfileService implements ChatModelProfileProvider, LocalChatRuntimePolicy, LocalDocumentUploadPolicy {

    private final ChatModelProfileStore store;
    private final AtomicReference<ChatModelProfile> current;
    private final long uploadCeiling;

    ChatModelProfileService(ChatModelProfileStore store,
            @Value("${privatekb.ingestion.max-upload-bytes:100000000}") long uploadCeiling) {
        this.store = store;
        this.current = new AtomicReference<>();
        this.uploadCeiling = uploadCeiling;
    }

    @Override
    public ChatModelProfile current() {
        ChatModelProfile selected = current.get();
        if (selected != null) {
            return selected;
        }
        ChatModelProfile stored = store.load().orElse(ChatModelProfile.DISABLED);
        current.compareAndSet(null, stored);
        return current.get();
    }

    ChatModelProfile select(ChatModelProfile profile) {
        ChatModelProfile selected = Objects.requireNonNull(profile, "Chat profile is required");
        store.save(selected);
        current.set(selected);
        return selected;
    }

    @Override
    public int maximumSourceDocuments() {
        return current().maximumSourceDocuments();
    }

    @Override
    public int maximumContextCharacters() {
        return current().maximumContextCharacters();
    }

    @Override
    public long maximumUploadBytes() {
        return maximumUploadBytes(current());
    }

    @Override
    public long maximumUploadBytes(ChatModelProfile profile) {
        return Math.min(uploadCeiling, profile.maximumUploadBytes());
    }
}
