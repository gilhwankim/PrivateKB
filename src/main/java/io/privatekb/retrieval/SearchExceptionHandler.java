package io.privatekb.retrieval;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SearchController.class)
class SearchExceptionHandler {

    @ExceptionHandler(SearchRejectedException.class)
    ResponseEntity<ProblemDetail> rejected(SearchRejectedException exception) {
        HttpStatus status = "WORKSPACE_NOT_FOUND".equals(exception.code())
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                detailFor(exception.code())
        );
        problem.setTitle("검색 요청을 처리할 수 없습니다");
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(SearchUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(SearchUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "로컬 임베딩 모델을 사용할 수 없습니다. AI 상태를 다시 확인해 주세요."
        );
        problem.setTitle("의미 검색을 사용할 수 없습니다");
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadableRequest() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "검색 요청 형식이 올바르지 않습니다."
        );
        problem.setTitle("검색 요청을 처리할 수 없습니다");
        problem.setProperty("code", "INVALID_REQUEST_BODY");
        return ResponseEntity.badRequest().body(problem);
    }

    private String detailFor(String code) {
        return switch (code) {
            case "WORKSPACE_NOT_FOUND" -> "지정한 작업공간을 찾을 수 없습니다.";
            case "INVALID_QUERY_LENGTH" -> "검색어 길이가 허용 범위를 벗어났습니다.";
            case "INVALID_RESULT_LIMIT" -> "검색 결과 개수가 허용 범위를 벗어났습니다.";
            default -> "검색 요청이 올바르지 않습니다.";
        };
    }
}
