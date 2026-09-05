package io.privatekb.platform.localai;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/local-ai")
class LocalAiModelInstallController {

    private final LocalAiModelInstallService installs;

    LocalAiModelInstallController(LocalAiModelInstallService installs) {
        this.installs = installs;
    }

    @PostMapping("/models/embedding/install")
    ResponseEntity<ModelInstallView> installEmbedding(
            @RequestBody ModelInstallRequest request
    ) {
        return accepted(installs.start(LocalAiModelRole.EMBEDDING, request.confirmed()));
    }

    @PostMapping("/models/chat/install")
    ResponseEntity<ModelInstallView> installChat(
            @RequestBody ModelInstallRequest request
    ) {
        return accepted(installs.start(LocalAiModelRole.CHAT, request.confirmed()));
    }

    @GetMapping("/model-installs/{jobId}")
    ResponseEntity<ModelInstallView> status(@PathVariable UUID jobId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(installs.find(jobId));
    }

    @PostMapping("/model-installs/{jobId}/cancel")
    ResponseEntity<ModelInstallView> cancel(@PathVariable UUID jobId) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(installs.cancel(jobId));
    }

    private ResponseEntity<ModelInstallView> accepted(ModelInstallView view) {
        return ResponseEntity.accepted()
                .location(URI.create("/api/local-ai/model-installs/" + view.jobId()))
                .cacheControl(CacheControl.noStore())
                .body(view);
    }
}
