package io.privatekb.knowledge;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DesktopDocumentSourceControllerTest {
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID VERSION = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String URL = "/api/desktop/workspaces/" + WORKSPACE + "/document-versions/" + VERSION + "/source-locations";
    private DocumentSourceCatalog sources;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        sources = mock(DocumentSourceCatalog.class);
        mvc = MockMvcBuilders.standaloneSetup(new DesktopDocumentSourceController(sources, "test-secret")).build();
    }

    @Test
    void onlyAuthenticatedNativeCallerCanReadSourceLocation() throws Exception {
        when(sources.findLocations(WORKSPACE, VERSION)).thenReturn(List.of(
                new DocumentSourceCatalog.DocumentSourceLocation("C:\\카나리\\합성 문서.txt", 10, 1000)));
        mvc.perform(post(URL).header("X-PrivateKB-Desktop-Token", "test-secret"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.locations[0].path").value("C:\\카나리\\합성 문서.txt"))
                .andExpect(jsonPath("$.locations[0].lastModifiedMillis").value(1000));
        verify(sources).findLocations(WORKSPACE, VERSION);
    }

    @Test
    void rejectsMissingOrWrongTokenWithoutQueryingLocations() throws Exception {
        mvc.perform(post(URL)).andExpect(status().isForbidden()).andExpect(content().string(""));
        mvc.perform(post(URL).header("X-PrivateKB-Desktop-Token", "wrong"))
                .andExpect(status().isForbidden()).andExpect(content().string(""));
        verifyNoInteractions(sources);
    }

    @Test
    void rejectsBrowserOriginsEvenWithToken() throws Exception {
        for (String origin : List.of("http://localhost:5173", "tauri://localhost", "null")) {
            mvc.perform(post(URL).header("X-PrivateKB-Desktop-Token", "test-secret").header("Origin", origin))
                    .andExpect(status().isForbidden()).andExpect(content().string(""));
        }
        verifyNoInteractions(sources);
    }

    @Test
    void rejectsRemoteClientsAndOtherWorkspaces() throws Exception {
        mvc.perform(post(URL).header("X-PrivateKB-Desktop-Token", "test-secret")
                .with(request -> { request.setRemoteAddr("192.0.2.20"); return request; }))
                .andExpect(status().isNotFound());
        mvc.perform(post(URL.replace(WORKSPACE.toString(), "90000000-0000-0000-0000-000000000001"))
                .header("X-PrivateKB-Desktop-Token", "test-secret")).andExpect(status().isNotFound());
        verifyNoInteractions(sources);
    }

    @Test
    void emptyConfigurationDoesNotEnableAuthentication() throws Exception {
        mvc = MockMvcBuilders.standaloneSetup(new DesktopDocumentSourceController(sources, "")).build();
        mvc.perform(post(URL).header("X-PrivateKB-Desktop-Token", ""))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sources);
    }

    @Test
    void unrecordedDocumentReturnsNoPaths() throws Exception {
        when(sources.findLocations(WORKSPACE, VERSION)).thenReturn(List.of());
        mvc.perform(post(URL).header("X-PrivateKB-Desktop-Token", "test-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locations").isEmpty());
    }
}
