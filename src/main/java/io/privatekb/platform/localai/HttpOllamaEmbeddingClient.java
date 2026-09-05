package io.privatekb.platform.localai;

import io.privatekb.platform.LocalEmbeddingClient;
import io.privatekb.platform.LocalEmbeddingException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class HttpOllamaEmbeddingClient implements LocalEmbeddingClient {

    private static final String COMPATIBILITY_PROBE = "PrivateKB 로컬 임베딩 호환성 검사";
    private static final int MAX_BATCH_SIZE = 32;
    private static final int MAX_TEXT_CHARACTERS = 4_000;

    private final URI baseUri;
    private final String model;
    private final int expectedDimensions;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicReference<EmbeddingModelInfo> verified = new AtomicReference<>();

    HttpOllamaEmbeddingClient(
            URI baseUri,
            String model,
            int expectedDimensions,
            Duration requestTimeout,
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.baseUri = baseUri;
        this.model = model;
        this.expectedDimensions = expectedDimensions;
        this.requestTimeout = requestTimeout;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public EmbeddingModelInfo verifyModel() {
        OllamaModelInfo installed = findInstalledModel();
        EmbeddingModelInfo cached = verified.get();
        if (cached != null && installed.digest() != null
                && installed.digest().equals(cached.digest())) {
            return cached;
        }

        List<float[]> probe = requestEmbeddings(List.of(COMPATIBILITY_PROBE));
        if (probe.size() != 1 || probe.getFirst().length != expectedDimensions) {
            throw new LocalEmbeddingException(LocalEmbeddingException.Reason.MODEL_INCOMPATIBLE);
        }
        EmbeddingModelInfo result = new EmbeddingModelInfo(
                model,
                installed.digest(),
                probe.getFirst().length
        );
        verified.set(result);
        return result;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty() || texts.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Embedding batch size is invalid");
        }
        for (String text : texts) {
            if (text == null || text.isBlank() || text.length() > MAX_TEXT_CHARACTERS) {
                throw new IllegalArgumentException("Embedding input size is invalid");
            }
        }
        List<float[]> embeddings = requestEmbeddings(texts);
        if (embeddings.size() != texts.size()) {
            throw new LocalEmbeddingException(LocalEmbeddingException.Reason.REQUEST_FAILED);
        }
        for (float[] embedding : embeddings) {
            if (embedding.length != expectedDimensions) {
                throw new LocalEmbeddingException(LocalEmbeddingException.Reason.MODEL_INCOMPATIBLE);
            }
        }
        return embeddings;
    }

    private OllamaModelInfo findInstalledModel() {
        JsonNode response = send("/api/tags", null);
        JsonNode models = response.get("models");
        if (models == null || !models.isArray()) {
            throw new LocalEmbeddingException(LocalEmbeddingException.Reason.REQUEST_FAILED);
        }
        for (JsonNode candidate : models) {
            String name = text(candidate, "name");
            if (name == null) {
                name = text(candidate, "model");
            }
            if (model.equals(name)) {
                return new OllamaModelInfo(
                        name,
                        text(candidate, "digest"),
                        longValue(candidate, "size")
                );
            }
        }
        throw new LocalEmbeddingException(LocalEmbeddingException.Reason.MODEL_NOT_AVAILABLE);
    }

    private List<float[]> requestEmbeddings(List<String> texts) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("input", texts);
        request.put("truncate", true);
        request.put("keep_alive", "5m");
        JsonNode response = send("/api/embed", request);
        JsonNode rows = response.get("embeddings");
        if (rows == null || !rows.isArray()) {
            throw new LocalEmbeddingException(LocalEmbeddingException.Reason.REQUEST_FAILED);
        }

        List<float[]> result = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isArray()) {
                throw new LocalEmbeddingException(LocalEmbeddingException.Reason.REQUEST_FAILED);
            }
            float[] vector = new float[row.size()];
            for (int index = 0; index < row.size(); index++) {
                float value = row.get(index).floatValue();
                if (!Float.isFinite(value)) {
                    throw new LocalEmbeddingException(LocalEmbeddingException.Reason.REQUEST_FAILED);
                }
                vector[index] = value;
            }
            result.add(vector);
        }
        return List.copyOf(result);
    }

    private JsonNode send(String path, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json");
            if (body == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(body)
                        ));
            }
            HttpResponse<String> response = httpClient.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() == 404) {
                throw new LocalEmbeddingException(
                        LocalEmbeddingException.Reason.MODEL_NOT_AVAILABLE
                );
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LocalEmbeddingException(LocalEmbeddingException.Reason.REQUEST_FAILED);
            }
            return objectMapper.readTree(response.body());
        } catch (HttpTimeoutException exception) {
            throw new LocalEmbeddingException(
                    LocalEmbeddingException.Reason.REQUEST_FAILED,
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LocalEmbeddingException(
                    LocalEmbeddingException.Reason.REQUEST_FAILED,
                    exception
            );
        } catch (IOException exception) {
            LocalEmbeddingException.Reason reason = hasConnectCause(exception)
                    ? LocalEmbeddingException.Reason.OLLAMA_NOT_RUNNING
                    : LocalEmbeddingException.Reason.REQUEST_FAILED;
            throw new LocalEmbeddingException(reason, exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.stringValue().isBlank()
                ? value.stringValue()
                : null;
    }

    private long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToLong() ? value.asLong() : 0L;
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
