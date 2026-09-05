package io.privatekb.platform.localai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LocalAiModelInstallControllerTest {

    private static final UUID JOB_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");

    private LocalAiModelInstallService installs;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        installs = mock(LocalAiModelInstallService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new LocalAiModelInstallController(installs))
                .setControllerAdvice(new ModelInstallExceptionHandler())
                .build();
    }

    @Test
    void acceptsOnlyConfirmedFixedChatInstallRequest() throws Exception {
        when(installs.start(LocalAiModelRole.CHAT, true)).thenReturn(view());

        mvc.perform(post("/api/local-ai/models/chat/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(
                        "Location",
                        "/api/local-ai/model-installs/" + JOB_ID
                ))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.model").value("qwen3.5:4b"))
                .andExpect(jsonPath("$.role").value("CHAT"));
    }

    @Test
    void returnsContentFreeProblemWhenConfirmationIsMissing() throws Exception {
        when(installs.start(LocalAiModelRole.CHAT, false)).thenThrow(
                new ModelInstallException("MODEL_INSTALL_CONFIRMATION_REQUIRED")
        );

        mvc.perform(post("/api/local-ai/models/chat/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "MODEL_INSTALL_CONFIRMATION_REQUIRED"
                ))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("qwen")
                )));
    }

    @Test
    void doesNotExposeArbitraryModelInstallRoute() throws Exception {
        mvc.perform(post("/api/local-ai/models/arbitrary/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":true}"))
                .andExpect(status().isNotFound());
    }

    private ModelInstallView view() {
        Instant now = Instant.parse("2026-08-27T01:00:00Z");
        return new ModelInstallView(
                JOB_ID,
                LocalAiModelRole.CHAT,
                "qwen3.5:4b",
                ModelInstallStatus.QUEUED,
                0,
                0,
                0,
                null,
                now,
                now
        );
    }
}
