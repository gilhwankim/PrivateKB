package io.privatekb.ingestion.internal.web;

import io.privatekb.ingestion.internal.domain.DesktopImportException;
import io.privatekb.ingestion.internal.domain.UploadRejectedException;
import io.privatekb.ingestion.internal.domain.UploadRejectionCode;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice(assignableTypes = {
        IngestionController.class,
        DesktopDocumentImportController.class
})
class IngestionExceptionHandler {

    @ExceptionHandler(DesktopImportException.class)
    ResponseEntity<ProblemDetail> desktopImportRejected(DesktopImportException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                exception.code().detail()
        );
        problem.setTitle("데스크톱 문서 추가 거부");
        problem.setProperty("code", exception.code().name());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(UploadRejectedException.class)
    ResponseEntity<ProblemDetail> rejected(UploadRejectedException exception) {
        HttpStatus status = statusFor(exception.code());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                detailFor(exception.code())
        );
        problem.setTitle("문서 업로드를 처리할 수 없습니다");
        problem.setProperty("code", exception.code().name());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> multipartTooLarge() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONTENT_TOO_LARGE,
                "업로드 파일이 허용 크기를 초과했습니다."
        );
        problem.setTitle("문서 업로드를 처리할 수 없습니다");
        problem.setProperty("code", UploadRejectionCode.FILE_TOO_LARGE.name());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem);
    }

    private HttpStatus statusFor(UploadRejectionCode code) {
        return switch (code) {
            case FILE_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case INSUFFICIENT_STORAGE -> HttpStatus.INSUFFICIENT_STORAGE;
            case UNSUPPORTED_EXTENSION, UNSUPPORTED_MEDIA_TYPE, MEDIA_TYPE_MISMATCH ->
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case WORKSPACE_NOT_FOUND, JOB_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RETRY_NOT_ALLOWED -> HttpStatus.CONFLICT;
            case EMPTY_FILE, SIZE_MISMATCH, INVALID_FILENAME -> HttpStatus.BAD_REQUEST;
        };
    }

    private String detailFor(UploadRejectionCode code) {
        return switch (code) {
            case EMPTY_FILE -> "빈 파일은 업로드할 수 없습니다.";
            case SIZE_MISMATCH -> "전송된 파일 크기와 실제 읽은 크기가 일치하지 않습니다.";
            case FILE_TOO_LARGE -> "업로드 파일이 허용 크기를 초과했습니다.";
            case INSUFFICIENT_STORAGE -> "저장 공간이 부족하여 문서 등록을 중단했습니다.";
            case INVALID_FILENAME -> "파일 이름이 안전한 단일 이름 형식이 아닙니다.";
            case UNSUPPORTED_EXTENSION ->
                    "PDF, Word, PowerPoint, Excel, HWP 5.x, Markdown, TXT 파일만 업로드할 수 있습니다.";
            case UNSUPPORTED_MEDIA_TYPE -> "선언된 콘텐츠 형식을 지원하지 않습니다.";
            case MEDIA_TYPE_MISMATCH -> "파일 내용과 확장자 또는 콘텐츠 형식이 일치하지 않습니다.";
            case WORKSPACE_NOT_FOUND -> "지정한 작업공간을 찾을 수 없습니다.";
            case JOB_NOT_FOUND -> "지정한 수집 작업을 찾을 수 없습니다.";
            case RETRY_NOT_ALLOWED -> "현재 상태에서는 작업을 재시도할 수 없습니다.";
        };
    }
}
