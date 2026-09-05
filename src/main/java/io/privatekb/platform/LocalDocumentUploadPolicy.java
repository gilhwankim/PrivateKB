package io.privatekb.platform;

/** AI 모델 설치 여부와 무관하게 선택한 사양의 문서 업로드 상한을 제공한다. */
public interface LocalDocumentUploadPolicy {
    long maximumUploadBytes();
}
