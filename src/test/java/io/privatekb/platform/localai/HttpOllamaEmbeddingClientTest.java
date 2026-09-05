package io.privatekb.platform.localai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.privatekb.platform.LocalEmbeddingClient.EmbeddingModelInfo;
import io.privatekb.platform.LocalEmbeddingException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class HttpOllamaEmbeddingClientTest {

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
    void verifiesDimensionsAndCachesProbeForSameDigest() {
        AtomicInteger embeddingRequests = new AtomicInteger();
        server.createContext("/api/tags", exchange -> respond(
                exchange, 200,
                "{\"models\":[{\"name\":\"qwen3-embedding:0.6b\",\"digest\":\"same-digest\"}]}"
        ));
        server.createContext("/api/embed", exchange -> {
            embeddingRequests.incrementAndGet();
            respond(exchange, 200, embeddings(1, 1024));
        });

        HttpOllamaEmbeddingClient client = client();
        EmbeddingModelInfo first = client.verifyModel();
        EmbeddingModelInfo second = client.verifyModel();

        assertThat(first.dimensions()).isEqualTo(1024);
        assertThat(second.digest()).isEqualTo("same-digest");
        assertThat(embeddingRequests).hasValue(1);
    }

    @Test
    void rejectsModelWithUnexpectedDimensions() {
        server.createContext("/api/tags", exchange -> respond(
                exchange, 200,
                "{\"models\":[{\"name\":\"qwen3-embedding:0.6b\",\"digest\":\"wrong\"}]}"
        ));
        server.createContext("/api/embed", exchange -> respond(
                exchange, 200, embeddings(1, 8)
        ));

        assertThatThrownBy(() -> client().verifyModel())
                .isInstanceOfSatisfying(LocalEmbeddingException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(
                                LocalEmbeddingException.Reason.MODEL_INCOMPATIBLE
                        )
                );
    }

    private HttpOllamaEmbeddingClient client() {
        return new HttpOllamaEmbeddingClient(
                baseUri,
                "qwen3-embedding:0.6b",
                1024,
                Duration.ofSeconds(2),
                HttpClient.newHttpClient(),
                new ObjectMapper()
        );
    }

    private String embeddings(int rows, int dimensions) {
        String vector = "[" + "0.001,".repeat(dimensions - 1) + "0.001]";
        return "{\"embeddings\":[" + (vector + ",").repeat(rows - 1) + vector + "]}";
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
