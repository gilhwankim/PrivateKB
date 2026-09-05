package io.privatekb.platform.localai;

import io.privatekb.platform.LocalModelPullClient;
import io.privatekb.platform.LocalModelPullException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class HttpOllamaModelPullClient implements LocalModelPullClient {

    private final URI baseUri;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    HttpOllamaModelPullClient(
            URI baseUri,
            Duration requestTimeout,
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.baseUri = baseUri;
        this.requestTimeout = requestTimeout;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void pull(
            String model,
            Consumer<ModelPullProgress> progressConsumer,
            BooleanSupplier cancelled
    ) {
        if (model == null || model.isBlank() || progressConsumer == null || cancelled == null) {
            throw new IllegalArgumentException("Model pull request is invalid");
        }
        if (cancelled.getAsBoolean()) {
            return;
        }

        HttpResponse<Stream<String>> response = send(model);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new LocalModelPullException(LocalModelPullException.Reason.REQUEST_FAILED);
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
                    throw new LocalModelPullException(
                            LocalModelPullException.Reason.REQUEST_FAILED
                    );
                }
                long total = nonNegativeLong(event, "total");
                long downloaded = nonNegativeLong(event, "completed");
                if (total > 0) {
                    progressConsumer.accept(new ModelPullProgress(
                            Math.min(downloaded, total),
                            total
                    ));
                }
                if ("success".equals(text(event, "status"))) {
                    completed = true;
                    break;
                }
            }
        } catch (LocalModelPullException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (hasIoCause(exception)) {
                throw mapIoFailure(exception);
            }
            throw new LocalModelPullException(
                    LocalModelPullException.Reason.STREAM_INVALID,
                    exception
            );
        }

        if (!cancelled.getAsBoolean() && !completed) {
            throw new LocalModelPullException(LocalModelPullException.Reason.STREAM_INVALID);
        }
    }

    private HttpResponse<Stream<String>> send(String model) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/pull"))
                    .timeout(requestTimeout)
                    .header("Accept", "application/x-ndjson")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of(
                                    "model", model,
                                    "stream", true
                            ))
                    ))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (HttpTimeoutException exception) {
            throw new LocalModelPullException(
                    LocalModelPullException.Reason.REQUEST_FAILED,
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LocalModelPullException(
                    LocalModelPullException.Reason.REQUEST_FAILED,
                    exception
            );
        } catch (IOException exception) {
            throw mapIoFailure(exception);
        }
    }

    private long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? Math.max(0L, value.longValue()) : 0L;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private LocalModelPullException mapIoFailure(Throwable exception) {
        return new LocalModelPullException(
                hasConnectCause(exception)
                        ? LocalModelPullException.Reason.OLLAMA_NOT_RUNNING
                        : LocalModelPullException.Reason.REQUEST_FAILED,
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
