package io.privatekb.retrieval;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AnswerController.class)
class AnswerExceptionHandler {

    @ExceptionHandler(AnswerRejectedException.class)
    ResponseEntity<ProblemDetail> rejected(AnswerRejectedException exception) {
        HttpStatus status = "WORKSPACE_NOT_FOUND".equals(exception.code())
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                "WORKSPACE_NOT_FOUND".equals(exception.code())
                        ? "지정한 작업공간을 찾을 수 없습니다."
                        : "질문 길이가 허용 범위를 벗어났습니다."
        );
        problem.setTitle("질문을 처리할 수 없습니다");
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(AnswerUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(AnswerUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "로컬 임베딩과 대화 모델 상태를 다시 확인해 주세요."
        );
        problem.setTitle("근거 기반 답변을 사용할 수 없습니다");
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(AnswerBusyException.class)
    ResponseEntity<ProblemDetail> busy() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "다른 답변을 생성하고 있습니다. 잠시 후 다시 시도해 주세요."
        );
        problem.setTitle("답변 작업이 혼잡합니다");
        problem.setProperty("code", "ANSWER_QUEUE_FULL");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadableRequest() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "질문 요청 형식이 올바르지 않습니다."
        );
        problem.setTitle("질문을 처리할 수 없습니다");
        problem.setProperty("code", "INVALID_REQUEST_BODY");
        return ResponseEntity.badRequest().body(problem);
    }
}
