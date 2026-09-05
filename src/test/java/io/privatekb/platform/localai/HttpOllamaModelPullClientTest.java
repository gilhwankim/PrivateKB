package io.privatekb.platform.localai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.privatekb.platform.LocalModelPullClient.ModelPullProgress;
import io.privatekb.platform.LocalModelPullException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class HttpOllamaModelPullClientTest {

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
    void sendsOnlyFixedModelPullPayloadAndReportsProgress() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/pull", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            respond(exchange, 200, """
                    {"status":"downloading","completed":25,"total":100}
                    {"status":"downloading","completed":100,"total":100}
                    {"status":"success"}
                    """);
        });
        List<ModelPullProgress> progress = new ArrayList<>();

        client().pull("qwen3.5:9b", progress::add, () -> false);

        assertThat(requestBody.get())
                .contains("\"model\":\"qwen3.5:9b\"")
                .contains("\"stream\":true")
                .doesNotContain("question", "content", "prompt");
        assertThat(progress).containsExactly(
                new ModelPullProgress(25, 100),
                new ModelPullProgress(100, 100)
        );
    }

    @Test
    void rejectsPullErrorWithoutExposingRemoteMessage() {
        server.createContext("/api/pull", exchange -> respond(
                exchange,
                200,
                "{\"error\":\"remote detail must not escape\"}\n"
        ));

        assertThatThrownBy(() -> client().pull(
                "qwen3.5:9b",
                ignored -> { },
                () -> false
        )).isInstanceOfSatisfying(LocalModelPullException.class, exception -> {
            assertThat(exception.reason()).isEqualTo(
                    LocalModelPullException.Reason.REQUEST_FAILED
            );
            assertThat(exception.getMessage()).doesNotContain("remote detail");
        });
    }

    private HttpOllamaModelPullClient client() {
        return new HttpOllamaModelPullClient(
                baseUri,
                Duration.ofSeconds(2),
                HttpClient.newHttpClient(),
                new ObjectMapper()
        );
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
