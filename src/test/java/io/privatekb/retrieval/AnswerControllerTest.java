package io.privatekb.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AnswerControllerTest {

    private static final UUID WORKSPACE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private GroundedAnswerApplicationService answers;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        answers = mock(GroundedAnswerApplicationService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new AnswerController(answers))
                .setControllerAdvice(new AnswerExceptionHandler())
                .build();
    }

    @Test
    void exposesNoStoreSseStream() throws Exception {
        SseEmitter emitter = new SseEmitter();
        when(answers.answer(WORKSPACE_ID, "장애 후속 조치는?"))
                .thenReturn(emitter);

        MvcResult pending = mvc.perform(post(
                        "/api/workspaces/{workspaceId}/answers/stream",
                        WORKSPACE_ID
                )
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"question\":\"장애 후속 조치는?\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(request().asyncStarted())
                .andReturn();

        emitter.send(SseEmitter.event().name("token").data(new Token("근거 답변 [1]")));
        emitter.send(SseEmitter.event().name("complete").data(new Complete(false)));
        emitter.complete();

        MvcResult completed = mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "event:token"
                )))
                .andReturn();
        assertThat(completed.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("근거 답변 [1]");
    }

    private record Token(String text) {
    }

    private record Complete(boolean refused) {
    }
}
