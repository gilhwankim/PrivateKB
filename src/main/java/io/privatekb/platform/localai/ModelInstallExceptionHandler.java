package io.privatekb.platform.localai;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LocalAiModelInstallController.class)
class ModelInstallExceptionHandler {

    @ExceptionHandler(ModelInstallException.class)
    ResponseEntity<ProblemDetail> installError(ModelInstallException exception) {
        HttpStatus status = switch (exception.code()) {
            case "MODEL_INSTALL_CONFIRMATION_REQUIRED", "MODEL_ROLE_INVALID", "CHAT_MODEL_DISABLED" ->
                    HttpStatus.BAD_REQUEST;
            case "MODEL_INSTALL_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "MODEL_INSTALL_ALREADY_RUNNING", "MODEL_INSTALL_QUEUE_UNAVAILABLE" ->
                    HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                detailFor(exception.code())
        );
        problem.setTitle("모델 설치 요청을 처리할 수 없습니다");
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadableRequest() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "모델 설치 확인 정보가 올바르지 않습니다."
        );
        problem.setTitle("모델 설치 요청을 처리할 수 없습니다");
        problem.setProperty("code", "MODEL_INSTALL_REQUEST_INVALID");
        return ResponseEntity.badRequest().body(problem);
    }

    private String detailFor(String code) {
        return switch (code) {
            case "MODEL_INSTALL_CONFIRMATION_REQUIRED" ->
                    "다운로드 용량과 외부 통신 안내를 확인한 뒤 명시적으로 승인해야 합니다.";
            case "MODEL_INSTALL_NOT_FOUND" -> "모델 설치 작업을 찾을 수 없습니다.";
            case "MODEL_INSTALL_ALREADY_RUNNING" ->
                    "다른 모델 설치 작업이 진행 중입니다.";
            case "MODEL_INSTALL_QUEUE_UNAVAILABLE" ->
                    "모델 설치 작업을 시작할 수 없습니다.";
            case "CHAT_MODEL_DISABLED" ->
                    "설정에서 저사양, 일반 또는 고사양 대화 모델을 먼저 선택해 주세요.";
            default -> "모델 설치 요청이 올바르지 않습니다.";
        };
    }
}
