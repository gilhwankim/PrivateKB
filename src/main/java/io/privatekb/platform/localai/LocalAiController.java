package io.privatekb.platform.localai;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/local-ai")
class LocalAiController {

    private final LocalAiReadinessService readiness;
    private final ChatModelProfileService profiles;

    LocalAiController(LocalAiReadinessService readiness, ChatModelProfileService profiles) {
        this.readiness = readiness;
        this.profiles = profiles;
    }

    @GetMapping("/status")
    ResponseEntity<LocalAiStatusView> status() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(readiness.status());
    }

    @PostMapping("/checks")
    ResponseEntity<LocalAiStatusView> check() {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(readiness.triggerCheck());
    }

    @PutMapping("/chat-profile")
    ResponseEntity<LocalAiStatusView> selectChatProfile(
            @RequestBody ChatProfileSelectionRequest request
    ) {
        if (request == null || request.profile() == null) {
            return ResponseEntity.badRequest().build();
        }
        profiles.select(request.profile());
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(readiness.configurationChanged());
    }

    record ChatProfileSelectionRequest(ChatModelProfile profile) {
    }
}
