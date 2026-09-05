package io.privatekb.platform;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DesktopLifecycleControllerTest {

    private static final String TOKEN = "a".repeat(96);

    private ConfigurableApplicationContext applicationContext;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        applicationContext = org.mockito.Mockito.mock(ConfigurableApplicationContext.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new DesktopLifecycleController(applicationContext, TOKEN))
                .build();
    }

    @Test
    void acceptsAuthenticatedLoopbackShutdown() throws Exception {
        mvc.perform(post("/api/desktop/shutdown")
                        .header(DesktopLifecycleController.SHUTDOWN_TOKEN_HEADER, TOKEN)
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isAccepted());

        verify(applicationContext, timeout(2_000)).close();
    }

    @Test
    void rejectsInvalidToken() throws Exception {
        mvc.perform(post("/api/desktop/shutdown")
                        .header(DesktopLifecycleController.SHUTDOWN_TOKEN_HEADER, "잘못된-토큰")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isForbidden());

        verify(applicationContext, never()).close();
    }

    @Test
    void hidesEndpointFromNonLoopbackRequest() throws Exception {
        mvc.perform(post("/api/desktop/shutdown")
                        .header(DesktopLifecycleController.SHUTDOWN_TOKEN_HEADER, TOKEN)
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        }))
                .andExpect(status().isNotFound());

        verify(applicationContext, never()).close();
    }
}
