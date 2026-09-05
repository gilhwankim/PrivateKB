package io.privatekb.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

class DesktopCorsConfigurationTest {

    private final UrlBasedCorsConfigurationSource source =
            new DesktopCorsConfiguration().desktopCorsConfigurationSource();

    @Test
    void allowsOnlyKnownTauriOriginsForApiRequests() {
        CorsConfiguration configuration = configurationFor("/api/local-ai/status");

        assertThat(configuration.checkOrigin("http://tauri.localhost"))
                .isEqualTo("http://tauri.localhost");
        assertThat(configuration.checkOrigin("https://tauri.localhost"))
                .isEqualTo("https://tauri.localhost");
        assertThat(configuration.checkOrigin("tauri://localhost"))
                .isEqualTo("tauri://localhost");
        assertThat(configuration.checkOrigin("https://example.com")).isNull();
    }

    @Test
    void allowsChatProfileUpdatesFromTheDesktopApp() {
        CorsConfiguration configuration = configurationFor("/api/local-ai/chat-profile");

        assertThat(configuration.getAllowedMethods()).contains("PUT");
    }

    @Test
    void doesNotApplyDesktopCorsOutsideApiPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        assertThat(source.getCorsConfiguration(request)).isNull();
    }

    private CorsConfiguration configurationFor(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        CorsConfiguration configuration = source.getCorsConfiguration(request);
        assertThat(configuration).isNotNull();
        return configuration;
    }
}
