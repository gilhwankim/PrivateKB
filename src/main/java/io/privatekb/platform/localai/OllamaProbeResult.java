package io.privatekb.platform.localai;

import java.util.List;

record OllamaProbeResult(
        OllamaConnectionStatus status,
        String version,
        List<OllamaModelInfo> models,
        String errorCode
) {
    OllamaProbeResult {
        models = List.copyOf(models);
    }

    static OllamaProbeResult connected(String version, List<OllamaModelInfo> models) {
        return new OllamaProbeResult(
                OllamaConnectionStatus.CONNECTED,
                version,
                models,
                null
        );
    }

    static OllamaProbeResult failed(OllamaConnectionStatus status, String errorCode) {
        return new OllamaProbeResult(status, null, List.of(), errorCode);
    }
}
