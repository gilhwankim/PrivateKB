package io.privatekb.platform.localai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LocalAiControllerTest {

    private MockMvc mvc;
    private ChatModelProfileService profiles;

    @BeforeEach
    void setUp() {
        OllamaStatusClient client = () -> OllamaProbeResult.connected(
                "0.12.0",
                List.of(
                        new OllamaModelInfo("qwen3-embedding:0.6b", "abc123", 639_150_858L),
                        new OllamaModelInfo("qwen3.5:4b", "def456", 3_400_000_000L),
                        new OllamaModelInfo("qwen3.5:9b", "ghi789", 6_594_474_711L)
                )
        );
        LocalAiProperties properties = new LocalAiProperties(
                URI.create("http://127.0.0.1:11434"),
                "qwen3-embedding:0.6b",
                1024,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofMinutes(2),
                Duration.ofMinutes(3),
                Duration.ofHours(2),
                true
        );
        AtomicReference<ChatModelProfile> stored = new AtomicReference<>(ChatModelProfile.STANDARD);
        profiles = new ChatModelProfileService(new ChatModelProfileStore() {
            @Override
            public Optional<ChatModelProfile> load() {
                return Optional.of(stored.get());
            }

            @Override
            public void save(ChatModelProfile profile) {
                stored.set(profile);
            }
        }, 100_000_000L);
        LocalAiReadinessService readiness = new LocalAiReadinessService(
                client,
                properties,
                profiles,
                Runnable::run,
                Clock.fixed(Instant.parse("2026-08-24T01:00:00Z"), ZoneOffset.UTC)
        );
        mvc = MockMvcBuilders.standaloneSetup(new LocalAiController(readiness, profiles)).build();
    }

    @Test
    void exposesInitialCheckingStateWithoutCaching() throws Exception {
        mvc.perform(get("/api/local-ai/status"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ollama.status").value("CHECKING"))
                .andExpect(jsonPath("$.capabilities.documentManagement").value(true))
                .andExpect(jsonPath("$.capabilities.semanticSearch").value(false));
    }

    @Test
    void acceptsManualCheckAndReturnsCapabilityState() throws Exception {
        mvc.perform(post("/api/local-ai/checks"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ollama.status").value("CONNECTED"))
                .andExpect(jsonPath("$.embedding.status").value("READY"))
                .andExpect(jsonPath("$.chat.status").value("READY"))
                .andExpect(jsonPath("$.chatProfile.profile").value("STANDARD"))
                .andExpect(jsonPath("$.capabilities.semanticSearch").value(true))
                .andExpect(jsonPath("$.capabilities.groundedAnswer").value(true))
                .andExpect(jsonPath("$.lastCheckedAt").value("2026-08-24T01:00:00Z"));
    }

    @Test
    void persistsAllowedChatProfileAndDisablesOnlyGroundedAnswers() throws Exception {
        mvc.perform(put("/api/local-ai/chat-profile")
                        .contentType("application/json")
                        .content("{\"profile\":\"DISABLED\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.chatProfile.profile").value("DISABLED"))
                .andExpect(jsonPath("$.chat.status").value("DISABLED"))
                .andExpect(jsonPath("$.capabilities.semanticSearch").value(true))
                .andExpect(jsonPath("$.capabilities.groundedAnswer").value(false));

        assertThat(profiles.current()).isEqualTo(ChatModelProfile.DISABLED);
    }

    @Test
    void acceptsInstalledHighSpecChatProfile() throws Exception {
        mvc.perform(put("/api/local-ai/chat-profile")
                        .contentType("application/json")
                        .content("{\"profile\":\"HIGH_SPEC\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.chatProfile.profile").value("HIGH_SPEC"))
                .andExpect(jsonPath("$.chatProfile.displayName").value("고사양"))
                .andExpect(jsonPath("$.chat.model").value("qwen3.5:9b"))
                .andExpect(jsonPath("$.chat.status").value("READY"))
                .andExpect(jsonPath("$.capabilities.groundedAnswer").value(true));

        assertThat(profiles.current()).isEqualTo(ChatModelProfile.HIGH_SPEC);
    }

    @Test
    void rejectsArbitraryChatProfileValue() throws Exception {
        mvc.perform(put("/api/local-ai/chat-profile")
                        .contentType("application/json")
                        .content("{\"profile\":\"ARBITRARY_MODEL\"}"))
                .andExpect(status().isBadRequest());

        assertThat(profiles.current()).isEqualTo(ChatModelProfile.STANDARD);
    }
}
