package io.privatekb.platform;

public record StoredContent(String storageKey, long byteSize, String sha256) {
}
