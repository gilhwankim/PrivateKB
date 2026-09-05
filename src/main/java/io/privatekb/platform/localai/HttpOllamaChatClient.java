package io.privatekb.platform.localai;

import io.privatekb.platform.LocalChatClient;
import io.privatekb.platform.LocalChatException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class HttpOllamaChatClient implements LocalChatClient {

    private static final int MAX_MESSAGES = 16;
    private static final int MAX_TOTAL_CHARACTERS = 30_000;

    private final URI baseUri;
    private final ChatModelProfileProvider profiles;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    HttpOllamaChatClient(
            URI baseUri,
            ChatModelProfileProvider profiles,
            Duration requestTimeout,
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.baseUri = baseUri;
        this.profiles = profiles;
        this.requestTimeout = requestTimeout;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatModelInfo verifyModel() {
        ChatModelProfile profile = requireEnabledProfile();
        JsonNode response = sendJson("/api/tags");
        JsonNode models = response.get("models");
        if (models == null || !models.isArray()) {
            throw new LocalChatException(LocalChatException.Reason.REQUEST_FAILED);
        }
        for (JsonNode candidate : models) {
            String name = text(candidate, "name");
            if (name == null) {
                name = text(candidate, "model");
            }
            if (profile.model().equals(name)) {
                return new ChatModelInfo(profile.model(), text(candidate, "digest"));
            }
        }
        throw new LocalChatException(LocalChatException.Reason.MODEL_NOT_AVAILABLE);
    }

    @Override
    public String complete(List<ChatMessage> messages, int maximumTokens) {
        ChatModelProfile profile = requireEnabledProfile();
        validateMessages(messages);
        if (maximumTokens < 1 || maximumTokens > profile.maximumGeneratedTokens()) {
            throw new IllegalArgumentException("Maximum tokens are invalid");
        }
        Map<String, Object> request = chatRequest(profile, messages, maximumTokens, 0.0);
        request.put("stream", false);
        request.put("format", "json");

        JsonNode response = sendChatJson(request);
        JsonNode message = response.get("message");
        String content = message == null ? null : text(message, "content");
        if (content == null || content.isBlank() || !response.path("done").asBoolean(false)) {
            throw new LocalChatException(LocalChatException.Reason.STREAM_INVALID);
        }
        return content;
    }

    @Override
    public void stream(
            List<ChatMessage> messages,
            Consumer<String> tokenConsumer,
            BooleanSupplier cancelled
    ) {
        ChatModelProfile profile = requireEnabledProfile();
        validate(messages, tokenConsumer, cancelled);
        Map<String, Object> request = chatRequest(
                profile,
                messages,
                profile.maximumGeneratedTokens(),
                0.2
        );
        request.put("stream", true);

        HttpResponse<Stream<String>> response = sendStream(request);
        if (response.statusCode() == 404) {
            response.body().close();
            throw new LocalChatException(LocalChatException.Reason.MODEL_NOT_AVAILABLE);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new LocalChatException(LocalChatException.Reason.REQUEST_FAILED);
        }

        boolean completed = false;
        try (Stream<String> lines = response.body()) {
            var iterator = lines.iterator();
            while (iterator.hasNext() && !cancelled.getAsBoolean()) {
                String line = iterator.next();
                if (line.isBlank()) {
                    continue;
                }
                JsonNode event = objectMapper.readTree(line);
                if (event.has("error")) {
                    throw new LocalChatException(LocalChatException.Reason.REQUEST_FAILED);
                }
                JsonNode message = event.get("message");
                String content = message == null ? null : text(message, "content");
                if (content != null && !content.isEmpty()) {
                    tokenConsumer.accept(content);
                }
                if (event.path("done").asBoolean(false)) {
                    completed = true;
                    break;
                }
            }
        } catch (LocalChatException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (hasIoCause(exception)) {
                throw mapIoFailure(exception);
            }
            throw new LocalChatException(LocalChatException.Reason.STREAM_INVALID, exception);
        }
        if (!cancelled.getAsBoolean() && !completed) {
            throw new LocalChatException(LocalChatException.Reason.STREAM_INVALID);
        }
    }

    private JsonNode sendJson(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LocalChatException(LocalChatException.Reason.REQUEST_FAILED);
            }
            return objectMapper.readTree(response.body());
        } catch (HttpTimeoutException exception) {
            throw new LocalChatException(LocalChatException.Reason.REQUEST_FAILED, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LocalChatException(LocalChatException.Reason.REQUEST_FAILED, exception);
        } catch (IOException exception) {
            throw mapIoFailure(exception);
        }
    }

    private HttpResponse<Stream<String>> sendStream(Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/chat"))
                    .timeout(requestTimeout)
                    .header("Accept", "application/x-ndjson")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)
                    ))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (HttpTimeoutException exception) {
            throw new LocalChatException(LocalChatException.Reason.REQUEST_FAILED, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LocalChatException(LocalChatException.Reason.REQUEST_FAILED, exception);
        } catch (IOException exception) {
            throw mapIoFailure(exception);
        }
    }

    private JsonNode sendChatJson(Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/chat"))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)
                    ))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() == 404) {
                throw new LocalChatException(LocalChatException.Reason.MODEL_NOT_AVAILABLE);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LocalChatException(LocalChatException.Reason.REQUEST_FAILED);
            }
            return objectMapper.readTree(response.body());
        } catch (HttpTimeoutException exception) {
            throw new LocalChatException(LocalChatException.Reason.REQUEST_FAILED, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LocalChatException(LocalChatException.Reason.REQUEST_FAILED, exception);
        } catch (IOException exception) {
            throw mapIoFailure(exception);
        }
    }

    private Map<String, Object> chatRequest(
            ChatModelProfile profile,
            List<ChatMessage> messages,
            int generatedTokens,
            double temperature
    ) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", profile.model());
        request.put("messages", messages.stream().map(message -> Map.of(
                "role", message.role(),
                "content", message.content()
        )).toList());
        request.put("think", false);
        request.put("keep_alive", "5m");
        request.put("options", Map.of(
                "num_ctx", profile.contextLength(),
                "num_predict", generatedTokens,
                "temperature", temperature
        ));
        return request;
    }

    private ChatModelProfile requireEnabledProfile() {
        ChatModelProfile profile = profiles.current();
        if (!profile.enabled()) {
            throw new LocalChatException(LocalChatException.Reason.MODEL_NOT_AVAILABLE);
        }
        return profile;
    }

    private void validate(
            List<ChatMessage> messages,
            Consumer<String> tokenConsumer,
            BooleanSupplier cancelled
    ) {
        if (tokenConsumer == null || cancelled == null) {
            throw new IllegalArgumentException("Chat request is invalid");
        }
        validateMessages(messages);
    }

    private void validateMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty() || messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("Chat request is invalid");
        }
        int totalCharacters = 0;
        for (ChatMessage message : messages) {
            if (message == null || message.content() == null || message.content().isBlank()
                    || !List.of("system", "user", "assistant").contains(message.role())) {
                throw new IllegalArgumentException("Chat message is invalid");
            }
            totalCharacters += message.content().length();
        }
        if (totalCharacters > MAX_TOTAL_CHARACTERS) {
            throw new IllegalArgumentException("Chat request is too large");
        }
    }

    private LocalChatException mapIoFailure(Throwable exception) {
        return new LocalChatException(
                hasConnectCause(exception)
                        ? LocalChatException.Reason.OLLAMA_NOT_RUNNING
                        : LocalChatException.Reason.REQUEST_FAILED,
                exception
        );
    }

    private boolean hasIoCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private boolean hasConnectCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
