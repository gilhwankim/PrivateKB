package io.privatekb.ingestion.internal.web;

import io.privatekb.ingestion.internal.application.IngestionApplicationService;
import io.privatekb.ingestion.internal.domain.IngestionErrorCode;
import io.privatekb.ingestion.internal.domain.IngestionStatus;
import io.privatekb.ingestion.internal.application.view.IngestionView;
import io.privatekb.ingestion.internal.application.view.UploadDocumentResult;
import io.privatekb.ingestion.internal.domain.UploadRejectedException;
import io.privatekb.ingestion.internal.domain.UploadRejectionCode;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IngestionControllerTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID VERSION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

    private IngestionApplicationService ingestion;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ingestion = mock(IngestionApplicationService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new IngestionController(ingestion))
                .setControllerAdvice(new IngestionExceptionHandler())
                .build();
    }

    @Test
    void acceptsNewMultipartUploadAndReturnsStatusLocation() throws Exception {
        when(ingestion.submit(any())).thenReturn(result(false));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "운영메모.txt",
                "text/plain",
                "합성 본문".getBytes(StandardCharsets.UTF_8)
        );

        mvc.perform(multipart("/api/workspaces/{workspaceId}/documents", WORKSPACE_ID).file(file))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/ingestions/" + JOB_ID))
                .andExpect(jsonPath("$.ingestionJobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.status").value("STORED"))
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    void returnsExistingResourceForDuplicateUpload() throws Exception {
        when(ingestion.submit(any())).thenReturn(result(true));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "중복.txt",
                "text/plain",
                "같은 합성 본문".getBytes(StandardCharsets.UTF_8)
        );

        mvc.perform(multipart("/api/workspaces/{workspaceId}/documents", WORKSPACE_ID).file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
    }

    @Test
    void returnsContentFreeProblemDetailsForRejectedUpload() throws Exception {
        when(ingestion.submit(any())).thenThrow(
                new UploadRejectedException(UploadRejectionCode.INVALID_FILENAME)
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "../secret.txt",
                "text/plain",
                "노출하면 안 되는 합성 본문".getBytes(StandardCharsets.UTF_8)
        );

        mvc.perform(multipart("/api/workspaces/{workspaceId}/documents", WORKSPACE_ID).file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILENAME"))
                .andExpect(jsonPath("$.detail").value("파일 이름이 안전한 단일 이름 형식이 아닙니다."));
    }

    @Test
    void reportsInsufficientStorageWithDedicatedHttpStatus() throws Exception {
        when(ingestion.submit(any())).thenThrow(
                new UploadRejectedException(UploadRejectionCode.INSUFFICIENT_STORAGE)
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "용량검증.txt",
                "text/plain",
                "합성 본문".getBytes(StandardCharsets.UTF_8)
        );

        mvc.perform(multipart("/api/workspaces/{workspaceId}/documents", WORKSPACE_ID).file(file))
                .andExpect(status().isInsufficientStorage())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STORAGE"))
                .andExpect(jsonPath("$.detail").value("저장 공간이 부족하여 문서 등록을 중단했습니다."));
    }

    @Test
    void retriesFailedDocumentVersionWithoutExposingStorageDetails() throws Exception {
        IngestionView view = new IngestionView(
                JOB_ID,
                WORKSPACE_ID,
                DOCUMENT_ID,
                VERSION_ID,
                "스캔문서.pdf",
                IngestionStatus.FAILED,
                1,
                IngestionErrorCode.OCR_NO_TEXT,
                java.time.Instant.parse("2026-08-30T01:00:00Z"),
                java.time.Instant.parse("2026-08-30T01:01:00Z")
        );
        when(ingestion.retryDocumentVersion(VERSION_ID)).thenReturn(view);

        mvc.perform(post(
                        "/api/document-versions/{documentVersionId}/ingestion/retry",
                        VERSION_ID
                ))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/ingestions/" + JOB_ID))
                .andExpect(jsonPath("$.errorCode").value("OCR_NO_TEXT"));
    }

    private UploadDocumentResult result(boolean duplicate) {
        return new UploadDocumentResult(
                WORKSPACE_ID,
                DOCUMENT_ID,
                VERSION_ID,
                JOB_ID,
                IngestionStatus.STORED,
                duplicate
        );
    }
}
