package io.privatekb.retrieval;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
class GroundedAnswerApplicationService {

    private final GroundedAnswerService answers;
    private final AnswerProperties properties;
    private final Executor executor;

    GroundedAnswerApplicationService(
            GroundedAnswerService answers,
            AnswerProperties properties,
            @Qualifier("answerTaskExecutor") Executor executor
    ) {
        this.answers = answers;
        this.properties = properties;
        this.executor = executor;
    }

    SseEmitter answer(UUID workspaceId, String question) {
        answers.validate(workspaceId, question);
        SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());
        AtomicBoolean cancelled = new AtomicBoolean();
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(ignored -> cancelled.set(true));

        try {
            executor.execute(() -> run(workspaceId, question, emitter, cancelled));
        } catch (RejectedExecutionException exception) {
            throw new AnswerBusyException();
        }
        return emitter;
    }

    private void run(
            UUID workspaceId,
            String question,
            SseEmitter emitter,
            AtomicBoolean cancelled
    ) {
        try {
            GroundedAnswerPlan plan = answers.prepare(workspaceId, question);
            if (plan.refused()) {
                send(emitter, "refusal", new RefusalEvent(plan.refusalReason()), cancelled);
                send(emitter, "complete", new CompleteEvent(true), cancelled);
                emitter.complete();
                return;
            }

            send(
                    emitter,
                    "citations",
                    new CitationsEvent(plan.citations()),
                    cancelled
            );
            answers.stream(
                    plan,
                    token -> send(emitter, "token", new TokenEvent(token), cancelled),
                    cancelled::get
            );
            if (!cancelled.get()) {
                send(emitter, "complete", new CompleteEvent(false), cancelled);
                emitter.complete();
            }
        } catch (AnswerUnavailableException exception) {
            if (!cancelled.get()) {
                send(
                        emitter,
                        "error",
                        new ErrorEvent(exception.code(), messageFor(exception.code())),
                        cancelled
                );
                emitter.complete();
            }
        } catch (RuntimeException exception) {
            if (!cancelled.get()) {
                send(
                        emitter,
                        "error",
                        new ErrorEvent(
                                "ANSWER_STREAM_FAILED",
                                "답변 스트림을 완료할 수 없습니다."
                        ),
                        cancelled
                );
                emitter.complete();
            }
        }
    }

    private void send(
            SseEmitter emitter,
            String eventName,
            Object data,
            AtomicBoolean cancelled
    ) {
        if (cancelled.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException exception) {
            cancelled.set(true);
        }
    }

    private String messageFor(String code) {
        return switch (code) {
            case "OLLAMA_NOT_RUNNING" -> "Ollama가 실행 중인지 확인해 주세요.";
            case "CHAT_MODEL_NOT_AVAILABLE" -> "설정에서 선택한 대화 모델을 설치해 주세요.";
            case "EMBEDDING_MODEL_NOT_AVAILABLE", "EMBEDDING_MODEL_INCOMPATIBLE" ->
                    "임베딩 모델 상태를 다시 확인해 주세요.";
            default -> "로컬 대화 모델의 응답을 완료할 수 없습니다.";
        };
    }

    record CitationsEvent(List<GroundedCitation> citations) {
        CitationsEvent {
            citations = List.copyOf(citations);
        }
    }

    record TokenEvent(String text) {
    }

    record RefusalEvent(String reason) {
    }

    record ErrorEvent(String code, String message) {
    }

    record CompleteEvent(boolean refused) {
    }
}
