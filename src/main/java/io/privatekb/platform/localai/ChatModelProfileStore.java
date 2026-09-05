package io.privatekb.platform.localai;

import java.util.Optional;

interface ChatModelProfileStore {

    Optional<ChatModelProfile> load();

    void save(ChatModelProfile profile);
}
