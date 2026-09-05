package io.privatekb.ingestion.internal.web;

import io.privatekb.ingestion.internal.domain.DocumentEmbeddingStatus;
import io.privatekb.ingestion.internal.application.view.DocumentPageView;
import io.privatekb.ingestion.internal.application.DocumentStatusApplicationService;
import io.privatekb.ingestion.internal.application.view.DocumentStatusItem;
import io.privatekb.ingestion.internal.domain.IndexingStatus;
import io.privatekb.ingestion.internal.domain.IngestionStatus;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DocumentStatusControllerTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID VERSION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    private DocumentStatusApplicationService documents;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentStatusApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentStatusController(documents)).build();
    }

    @Test
    void returnsPagedDocumentEmbeddingStatusesWithoutCaching() throws Exception {
        when(documents.find(WORKSPACE_ID, 0, 10)).thenReturn(new DocumentPageView(
                List.of(new DocumentStatusItem(
                        DOCUMENT_ID,
                        VERSION_ID,
                        1,
                        "회의록.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        2048,
                        DocumentEmbeddingStatus.COMPLETED,
                        IngestionStatus.PARSED,
                        IndexingStatus.INDEXED,
                        null,
                        Instant.parse("2026-08-29T01:00:00Z"),
                        Instant.parse("2026-08-29T01:00:02Z")
                )),
                12,
                0,
                10,
                true
        ));

        mvc.perform(get("/api/workspaces/{workspaceId}/documents", WORKSPACE_ID)
                        .queryParam("page", "0")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.documents[0].originalFilename").value("회의록.docx"))
                .andExpect(jsonPath("$.documents[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.documents[0].ingestionStatus").value("PARSED"))
                .andExpect(jsonPath("$.documents[0].indexingStatus").value("INDEXED"))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.hasNext").value(true));
        verify(documents).find(WORKSPACE_ID, 0, 10);
    }
}
