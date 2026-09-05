package io.privatekb.platform.localai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class ChatModelProfileServiceTest {

    @Test
    void startsDisabledAndPersistsOnlyAllowedProfileValues() {
        AtomicReference<ChatModelProfile> stored = new AtomicReference<>();
        ChatModelProfileService service = new ChatModelProfileService(new ChatModelProfileStore() {
            @Override
            public Optional<ChatModelProfile> load() {
                return Optional.ofNullable(stored.get());
            }

            @Override
            public void save(ChatModelProfile profile) {
                stored.set(profile);
            }
        }, 100_000_000L);

        assertThat(service.current()).isEqualTo(ChatModelProfile.DISABLED);
        assertThat(service.maximumUploadBytes()).isEqualTo(25_000_000L);

        service.select(ChatModelProfile.LOW_SPEC);

        assertThat(stored.get()).isEqualTo(ChatModelProfile.LOW_SPEC);
        assertThat(service.current()).isEqualTo(ChatModelProfile.LOW_SPEC);
        assertThat(service.maximumSourceDocuments()).isEqualTo(3);
        assertThat(service.maximumContextCharacters()).isEqualTo(7_000);
        assertThat(service.maximumUploadBytes()).isEqualTo(25_000_000L);

        service.select(ChatModelProfile.STANDARD);
        assertThat(service.maximumUploadBytes()).isEqualTo(50_000_000L);

        service.select(ChatModelProfile.HIGH_SPEC);

        assertThat(stored.get()).isEqualTo(ChatModelProfile.HIGH_SPEC);
        assertThat(service.current()).isEqualTo(ChatModelProfile.HIGH_SPEC);
        assertThat(service.maximumSourceDocuments()).isEqualTo(7);
        assertThat(service.maximumContextCharacters()).isEqualTo(18_000);
        assertThat(service.maximumUploadBytes()).isEqualTo(100_000_000L);

        service.select(ChatModelProfile.DISABLED);
        assertThat(service.maximumUploadBytes()).isEqualTo(25_000_000L);
    }
}
