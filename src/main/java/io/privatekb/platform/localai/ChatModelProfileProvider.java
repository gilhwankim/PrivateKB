package io.privatekb.platform.localai;

interface ChatModelProfileProvider {

    ChatModelProfile current();

    default long maximumUploadBytes(ChatModelProfile profile) {
        return profile.maximumUploadBytes();
    }
}
