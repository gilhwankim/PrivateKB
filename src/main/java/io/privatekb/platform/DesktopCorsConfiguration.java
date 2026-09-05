package io.privatekb.platform;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods = false)
class DesktopCorsConfiguration {

    private static final List<String> DESKTOP_ORIGINS = List.of(
            "http://tauri.localhost",
            "https://tauri.localhost",
            "tauri://localhost"
    );

    @Bean
    UrlBasedCorsConfigurationSource desktopCorsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(DESKTOP_ORIGINS);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.ACCEPT, HttpHeaders.CONTENT_TYPE));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    CorsFilter desktopCorsFilter(UrlBasedCorsConfigurationSource desktopCorsConfigurationSource) {
        return new CorsFilter(desktopCorsConfigurationSource);
    }
}
