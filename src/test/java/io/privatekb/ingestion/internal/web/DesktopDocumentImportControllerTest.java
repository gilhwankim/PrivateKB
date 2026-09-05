package io.privatekb.ingestion.internal.web;

import io.privatekb.ingestion.internal.application.DesktopDocumentImportService;
import io.privatekb.ingestion.internal.domain.IngestionStatus;
import io.privatekb.ingestion.internal.application.view.UploadDocumentResult;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DesktopDocumentImportControllerTest {

    private static final String TOKEN = "desktop-bridge-test-token";
    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID VERSION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

    private DesktopDocumentImportService imports;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        imports = mock(DesktopDocumentImportService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new DesktopDocumentImportController(imports, TOKEN))
                .setControllerAdvice(new IngestionExceptionHandler())
                .build();
    }

    @Test
    void rejectsRequestWithoutDesktopSessionToken() throws Exception {
        mvc.perform(baseRequest())
                .andExpect(status().isForbidden());

        verifyNoInteractions(imports);
    }

    @Test
    void acceptsAuthenticatedLoopbackDesktopImport() throws Exception {
        when(imports.submit(any())).thenReturn(new UploadDocumentResult(
                WORKSPACE_ID,
                DOCUMENT_ID,
                VERSION_ID,
                JOB_ID,
                IngestionStatus.STORED,
                false
        ));

        mvc.perform(baseRequest().header(DesktopDocumentImportController.DESKTOP_TOKEN_HEADER, TOKEN))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentVersionId").value(VERSION_ID.toString()))
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    private MockMultipartHttpServletRequestBuilder baseRequest() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "document.bin",
                "application/octet-stream",
                "합성 문서".getBytes(StandardCharsets.UTF_8)
        );
        return multipart("/api/desktop/workspaces/{workspaceId}/documents", WORKSPACE_ID)
                .file(file)
                .param("originalFilename", "회의록.txt")
                .param("declaredMediaType", "application/octet-stream")
                .param("lastModifiedMillis", "1000")
                .param("sourcePath", "C:\\자료\\회의록.txt");
    }
}
