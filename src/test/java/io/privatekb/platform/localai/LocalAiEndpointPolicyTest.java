package io.privatekb.platform.localai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import org.junit.jupiter.api.Test;

class LocalAiEndpointPolicyTest {

    private final LocalAiEndpointPolicy policy = new LocalAiEndpointPolicy();

    @Test
    void acceptsOnlyLoopbackOllamaEndpoints() {
        assertThat(policy.requireLocalOllama(URI.create("http://127.0.0.1:11434")))
                .isEqualTo(URI.create("http://127.0.0.1:11434"));
        assertThat(policy.requireLocalOllama(URI.create("http://localhost:11434")))
                .isEqualTo(URI.create("http://localhost:11434"));
        assertThat(policy.requireLocalOllama(URI.create("http://[::1]:11434")))
                .isEqualTo(URI.create("http://[::1]:11434"));
    }

    @Test
    void rejectsExternalOrCloudEndpoints() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.requireLocalOllama(URI.create("https://api.example.com:11434")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.requireLocalOllama(URI.create("http://192.0.2.10:11434")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.requireLocalOllama(URI.create("http://127.0.0.2:11434")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.requireLocalOllama(URI.create("http://localhost.example:11434")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.requireLocalOllama(URI.create("http://localhost:443")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.requireLocalOllama(URI.create("http://localhost:11434/api/chat")));
    }
}
