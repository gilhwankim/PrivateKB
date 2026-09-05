package io.privatekb.platform.localai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.privatekb.platform.LocalChatClient.ChatMessage;
import io.privatekb.platform.LocalChatException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class HttpOllamaChatClientTest {

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
    void verifiesExactModelAndStreamsOnlyAnswerContent() {
        server.createContext("/api/tags", exchange -> respond(
                exchange,
                200,
                "application/json",
                "{\"models\":[{\"name\":\"qwen3.5:4b\",\"digest\":\"chat-digest\"}]}"
        ));
        server.createContext("/api/chat", exchange -> respond(
                exchange,
                200,
                "application/x-ndjson",
                """
                        {"message":{"content":"근거에 따르면 "},"done":false}
                        {"message":{"content":"조치가 완료됐습니다. [1]"},"done":false}
                        {"done":true}
                        """
        ));

        HttpOllamaChatClient client = client();
        StringBuilder answer = new StringBuilder();

        assertThat(client.verifyModel().digest()).isEqualTo("chat-digest");
        client.stream(
                List.of(new ChatMessage("user", "무엇을 조치했나요?")),
                answer::append,
                () -> false
        );

        assertThat(answer).hasToString("근거에 따르면 조치가 완료됐습니다. [1]");
    }

    @Test
    void completesShortStructuredPlanningResponseWithoutStreaming() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/chat", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            respond(
                    exchange,
                    200,
                    "application/json",
                    "{\"message\":{\"content\":\"{\\\"searchQuery\\\":\\\"회의록\\\"}\"},\"done\":true}"
            );
        });

        String response = client().complete(
                List.of(new ChatMessage("user", "어제 회의록을 찾아줘")),
                256
        );

        assertThat(response).isEqualTo("{\"searchQuery\":\"회의록\"}");
        assertThat(requestBody.get())
                .contains("\"stream\":false", "\"format\":\"json\"", "\"num_predict\":256");
    }

    @Test
    void appliesLowSpecContextAndOutputLimitsWithoutFallingBackToAnotherModel() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/chat", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            respond(
                    exchange,
                    200,
                    "application/json",
                    "{\"message\":{\"content\":\"{\\\"searchQuery\\\":\\\"회의록\\\"}\"},\"done\":true}"
            );
        });
        HttpOllamaChatClient client = client(ChatModelProfile.LOW_SPEC);

        client.complete(List.of(new ChatMessage("user", "회의록")), 512);

        assertThat(requestBody.get()).contains(
                "\"model\":\"qwen3.5:2b-q4_K_M\"",
                "\"num_ctx\":4096",
                "\"num_predict\":512"
        );
        assertThatThrownBy(() -> client.complete(
                List.of(new ChatMessage("user", "회의록")),
                513
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appliesHighSpecModelAndOutputLimits() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/chat", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            respond(
                    exchange,
                    200,
                    "application/json",
                    "{\"message\":{\"content\":\"{\\\"searchQuery\\\":\\\"회의록\\\"}\"},\"done\":true}"
            );
        });
        HttpOllamaChatClient client = client(ChatModelProfile.HIGH_SPEC);

        client.complete(List.of(new ChatMessage("user", "회의록")), 1536);

        assertThat(requestBody.get()).contains(
                "\"model\":\"qwen3.5:9b\"",
                "\"num_ctx\":8192",
                "\"num_predict\":1536"
        );
        assertThatThrownBy(() -> client.complete(
                List.of(new ChatMessage("user", "회의록")),
                1537
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingExactChatModelBeforeGeneration() {
        AtomicInteger chatRequests = new AtomicInteger();
        server.createContext("/api/tags", exchange -> respond(
                exchange,
                200,
                "application/json",
                "{\"models\":[{\"name\":\"qwen3.5:9b\",\"digest\":\"other\"}]}"
        ));
        server.createContext("/api/chat", exchange -> {
            chatRequests.incrementAndGet();
            respond(exchange, 200, "application/x-ndjson", "{\"done\":true}\n");
        });

        assertThatThrownBy(() -> client().verifyModel())
                .isInstanceOfSatisfying(LocalChatException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(
                                LocalChatException.Reason.MODEL_NOT_AVAILABLE
                        )
                );
        assertThat(chatRequests).hasValue(0);
    }

    @Test
    void rejectsStreamThatEndsWithoutDoneMarker() {
        server.createContext("/api/chat", exchange -> respond(
                exchange,
                200,
                "application/x-ndjson",
                "{\"message\":{\"content\":\"미완성\"},\"done\":false}\n"
        ));

        assertThatThrownBy(() -> client().stream(
                List.of(new ChatMessage("user", "질문")),
                ignored -> { },
                () -> false
        )).isInstanceOfSatisfying(LocalChatException.class, exception ->
                assertThat(exception.reason()).isEqualTo(
                        LocalChatException.Reason.STREAM_INVALID
                )
        );
    }

    private HttpOllamaChatClient client() {
        return client(ChatModelProfile.STANDARD);
    }

    private HttpOllamaChatClient client(ChatModelProfile profile) {
        return new HttpOllamaChatClient(
                baseUri,
                () -> profile,
                Duration.ofSeconds(2),
                HttpClient.newHttpClient(),
                new ObjectMapper()
        );
    }

    private void respond(
            HttpExchange exchange,
            int status,
            String contentType,
            String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
