package io.privatekb.platform.localai;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class LocalAiEndpointPolicy {

    private static final int OLLAMA_PORT = 11434;
    private static final Set<String> ALLOWED_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    public URI requireLocalOllama(URI endpoint) {
        Objects.requireNonNull(endpoint, "Local AI endpoint must be configured");

        if (!"http".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("Local AI endpoint must use http");
        }
        if (endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Local AI endpoint must not include credentials, query, or fragment");
        }
        if (endpoint.getPort() != OLLAMA_PORT) {
            throw new IllegalArgumentException("Local AI endpoint must use Ollama port 11434");
        }
        if (endpoint.getPath() != null && !endpoint.getPath().isBlank() && !"/".equals(endpoint.getPath())) {
            throw new IllegalArgumentException("Local AI endpoint must not include an API path");
        }
        if (!isLoopback(endpoint.getHost())) {
            throw new IllegalArgumentException("External AI endpoints are forbidden; use localhost only");
        }
        return endpoint;
    }

    private boolean isLoopback(String host) {
        return host != null && ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }
}
