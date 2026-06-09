package app.zcat.infochat.llm.impl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins HTTP-client construction across all provider beans: every
 * production constructor must build its shared
 * {@link java.net.http.HttpClient} with an explicit connect-timeout.
 * The per-call request timeout caps the full exchange, but on HTTP/1.1
 * it cannot bound a hanging TCP connect to an unroutable endpoint —
 * without the explicit value the call would wait on the OS connect
 * default. Asserted through the JDK's public
 * {@code HttpClient.connectTimeout()} accessor on the live client.
 */
class ProviderConnectTimeoutTest {

    @Test
    void anthropicProviderBuildsHttpClientWithExplicitConnectTimeout() {
        AnthropicProvider provider = new AnthropicProvider(new StubConfig(Map.of()));
        assertTrue(provider.httpClient().connectTimeout().isPresent(),
            "AnthropicProvider's production constructor must set an explicit connect-timeout");
    }

    @Test
    void openAiCompatibleProviderBuildsHttpClientWithExplicitConnectTimeout() {
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of()));
        assertTrue(provider.httpClient().connectTimeout().isPresent(),
            "OpenAiCompatibleProvider's production constructor must set an explicit connect-timeout");
    }

    @Test
    void embeddingProviderBuildsHttpClientWithExplicitConnectTimeout() {
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider();
        assertTrue(provider.httpClient().connectTimeout().isPresent(),
            "OpenAiCompatibleEmbeddingProvider's constructor must set an explicit connect-timeout");
    }
}
