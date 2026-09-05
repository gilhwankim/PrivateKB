package io.privatekb.ingestion.internal.web;

import io.privatekb.ingestion.internal.domain.IndexingNotFoundException;
import io.privatekb.ingestion.internal.domain.IndexingRetryRejectedException;

import io.privatekb.platform.LocalEmbeddingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = IndexingController.class)
class IndexingExceptionHandler {

    @ExceptionHandler(LocalEmbeddingException.class)
    ResponseEntity<ProblemDetail> embeddingUnavailable() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "로컬 임베딩 모델을 사용할 수 없습니다. AI 상태를 다시 확인해 주세요."
        );
        problem.setTitle("색인을 재개할 수 없습니다");
        problem.setProperty("code", "EMBEDDING_MODEL_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(IndexingNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "지정한 색인 작업을 찾을 수 없습니다."
        );
        problem.setTitle("색인 상태를 확인할 수 없습니다");
        problem.setProperty("code", "INDEXING_JOB_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(IndexingRetryRejectedException.class)
    ResponseEntity<ProblemDetail> retryRejected() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "현재 상태에서는 색인을 다시 실행할 수 없습니다."
        );
        problem.setTitle("색인을 다시 실행할 수 없습니다");
        problem.setProperty("code", "INDEXING_RETRY_NOT_ALLOWED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
