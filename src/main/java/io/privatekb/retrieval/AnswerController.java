package io.privatekb.retrieval;

import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/answers")
class AnswerController {

    private final GroundedAnswerApplicationService answers;

    AnswerController(GroundedAnswerApplicationService answers) {
        this.answers = answers;
    }

    @PostMapping(
            value = "/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8"
    )
    SseEmitter answer(
            @PathVariable UUID workspaceId,
            @RequestBody AnswerRequest request,
            HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return answers.answer(workspaceId, request.question());
    }
}
