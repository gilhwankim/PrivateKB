package io.privatekb.platform.localai;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class HttpOllamaStatusClient implements OllamaStatusClient {

    private static final String NOT_RUNNING = "OLLAMA_NOT_RUNNING";
    private static final String INCOMPATIBLE = "OLLAMA_RESPONSE_INCOMPATIBLE";
    private static final String CHECK_FAILED = "OLLAMA_CHECK_FAILED";

    private final URI baseUri;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    HttpOllamaStatusClient(
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
    public OllamaProbeResult probe() {
        try {
            JsonNode versionResponse = get("/api/version");
            JsonNode tagsResponse = get("/api/tags");
            String version = requiredText(versionResponse, "version");
            JsonNode modelsNode = tagsResponse.get("models");
            if (modelsNode == null || !modelsNode.isArray()) {
                throw new IncompatibleResponseException();
            }

            List<OllamaModelInfo> models = new ArrayList<>();
            for (JsonNode modelNode : modelsNode) {
                String name = firstText(modelNode, "name", "model");
                if (name == null) {
                    throw new IncompatibleResponseException();
                }
                models.add(new OllamaModelInfo(
                        name,
                        optionalText(modelNode, "digest"),
                        optionalLong(modelNode, "size")
                ));
            }
            return OllamaProbeResult.connected(version, models);
        } catch (IncompatibleResponseException exception) {
            return OllamaProbeResult.failed(OllamaConnectionStatus.INCOMPATIBLE, INCOMPATIBLE);
        } catch (HttpTimeoutException exception) {
            return OllamaProbeResult.failed(OllamaConnectionStatus.CHECK_FAILED, CHECK_FAILED);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return OllamaProbeResult.failed(OllamaConnectionStatus.CHECK_FAILED, CHECK_FAILED);
        } catch (IOException exception) {
            if (hasConnectCause(exception)) {
                return OllamaProbeResult.failed(OllamaConnectionStatus.NOT_RUNNING, NOT_RUNNING);
            }
            return OllamaProbeResult.failed(OllamaConnectionStatus.CHECK_FAILED, CHECK_FAILED);
        } catch (RuntimeException exception) {
            return OllamaProbeResult.failed(OllamaConnectionStatus.CHECK_FAILED, CHECK_FAILED);
        }
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
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
            throw new IncompatibleResponseException();
        }
        return objectMapper.readTree(response.body());
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IncompatibleResponseException();
        }
        return value;
    }

    private String firstText(JsonNode node, String firstField, String secondField) {
        String first = optionalText(node, firstField);
        return first != null ? first : optionalText(node, secondField);
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            return null;
        }
        return value.stringValue();
    }

    private long optionalLong(JsonNode node, String field) {
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

    private static final class IncompatibleResponseException extends RuntimeException {
    }
}
