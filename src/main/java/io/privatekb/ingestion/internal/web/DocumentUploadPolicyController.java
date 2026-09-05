package io.privatekb.ingestion.internal.web;

import io.privatekb.ingestion.internal.domain.UploadPolicy;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 본문·경로나 모델 탐지 없이 현재 입력 상한만 반환한다. */
@RestController
final class DocumentUploadPolicyController {
    private final UploadPolicy policy;

    DocumentUploadPolicyController(UploadPolicy policy) {
        this.policy = policy;
    }

    @GetMapping("/api/document-upload-policy")
    ResponseEntity<UploadLimits> limits() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new UploadLimits(policy.maximumUploadBytes()));
    }

    record UploadLimits(long maxFileBytes) {}
}
