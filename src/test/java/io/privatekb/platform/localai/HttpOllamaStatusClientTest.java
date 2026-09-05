package io.privatekb.platform.localai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class HttpOllamaStatusClientTest {

    private HttpServer server;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsVersionAndInstalledModelMetadata() {
        server.createContext("/api/version", exchange -> respond(
                exchange,
                200,
                "{\"version\":\"0.12.0\"}"
        ));
        server.createContext("/api/tags", exchange -> respond(
                exchange,
                200,
                """
                        {"models":[
                          {"name":"qwen3-embedding:0.6b","digest":"abc123","size":639150858},
                          {"model":"qwen3.5:9b","digest":"def456","size":6594474711}
                        ]}
                        """
        ));

        OllamaProbeResult result = client().probe();

        assertThat(result.status()).isEqualTo(OllamaConnectionStatus.CONNECTED);
        assertThat(result.version()).isEqualTo("0.12.0");
        assertThat(result.models()).containsExactly(
                new OllamaModelInfo("qwen3-embedding:0.6b", "abc123", 639_150_858L),
                new OllamaModelInfo("qwen3.5:9b", "def456", 6_594_474_711L)
        );
    }

    @Test
    void rejectsResponsesWithoutRequiredVersionMetadata() {
        server.createContext("/api/version", exchange -> respond(exchange, 200, "{}"));
        server.createContext("/api/tags", exchange -> respond(exchange, 200, "{\"models\":[]}"));

        OllamaProbeResult result = client().probe();

        assertThat(result.status()).isEqualTo(OllamaConnectionStatus.INCOMPATIBLE);
        assertThat(result.errorCode()).isEqualTo("OLLAMA_RESPONSE_INCOMPATIBLE");
    }

    private HttpOllamaStatusClient client() {
        return new HttpOllamaStatusClient(
                baseUri,
                Duration.ofSeconds(2),
                HttpClient.newHttpClient(),
                new ObjectMapper()
        );
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
