package io.privatekb.ingestion.internal.web;

import io.privatekb.ingestion.internal.config.IngestionProperties;
import io.privatekb.ingestion.internal.domain.UploadPolicy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentUploadPolicyControllerTest {
    @Test
    void returnsCurrentLimitWithoutCachingAndHonorsConfiguredCeiling() throws Exception {
        AtomicLong selected = new AtomicLong(25_000_000L);
        UploadPolicy policy = new UploadPolicy(new IngestionProperties(
                75_000_000L, 5_000_000, Duration.ofSeconds(30), 3), selected::get);
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentUploadPolicyController(policy)).build();
        mvc.perform(get("/api/document-upload-policy"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.maxFileBytes").value(25_000_000));
        selected.set(50_000_000L);
        mvc.perform(get("/api/document-upload-policy")).andExpect(jsonPath("$.maxFileBytes").value(50_000_000));
        selected.set(100_000_000L);
        mvc.perform(get("/api/document-upload-policy")).andExpect(jsonPath("$.maxFileBytes").value(75_000_000));
    }
}
