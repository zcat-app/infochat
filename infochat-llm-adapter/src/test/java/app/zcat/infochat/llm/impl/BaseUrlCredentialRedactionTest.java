package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.ModelTask;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the M1-330 credential-redaction contract for operator-supplied
 * LLM base-urls, on both surfaces the deep-review flagged:
 *
 * <ol>
 *   <li>The config boundary ({@link LlmHttpSupport#requireHttpBaseUrl})
 *       rejects a base-url that embeds userinfo
 *       ({@code https://user:pass@host}) so a credential-bearing URL cannot
 *       enter the system at all, and the rejection message does NOT echo the
 *       userinfo it rejects.</li>
 *   <li>A 2xx-but-malformed parse failure surfaces only the host — not the
 *       full request URI/path — matching the deliberate U-13 host-only
 *       posture of the non-2xx path the parse paths sit beside.</li>
 * </ol>
 */
class BaseUrlCredentialRedactionTest {

    private HttpServer mockServer;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://localhost:" + mockServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    void requireHttpBaseUrlRejectsUserinfoWithoutEchoingTheCredential() {
        String property = "infochat.llm.security.base-url";
        String secret = "s3cr3t-token";
        String user = "apiuser";
        String credentialBearingUrl = "https://" + user + ":" + secret + "@llm.example.com/v1";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> LlmHttpSupport.requireHttpBaseUrl(credentialBearingUrl, property),
            "a base-url embedding userinfo must be rejected at the config boundary");

        assertTrue(ex.getMessage().contains(property),
            "the rejection must name the offending property; got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(secret),
            "the rejection must NOT echo the userinfo credential; got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(user),
            "the rejection must NOT echo the userinfo user; got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(credentialBearingUrl),
            "the rejection must NOT echo the credential-bearing base-url; got: " + ex.getMessage());
    }

    @Test
    void parseFailureSurfacesHostNotFullPath() {
        // A 2xx reply that is not valid JSON drives the failed-to-parse path.
        // The thrown message must name the host (triage) but must NOT carry the
        // full request URI — the "/chat/completions" path proves a bare-uri
        // concatenation, which U-13 narrowed away on the sibling non-2xx path.
        mockServer.createContext("/chat/completions", exchange -> {
            byte[] resp = "not-json".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        String seg = ModelTask.SUMMARIZER.keySegment();
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm." + seg + ".base-url", baseUrl,
            "infochat.llm." + seg + ".api-key", "",
            "infochat.llm." + seg + ".model", "test-model",
            "infochat.llm." + seg + ".timeout-ms", "5000")));

        LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
            () -> provider.generate(ModelTask.SUMMARIZER, "sys", "usr"),
            "a 2xx-but-unparseable reply must surface as LlmCallFailedException");

        assertTrue(ex.getMessage().contains("localhost"),
            "the parse-failure message must name the host for triage; got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("/chat/completions"),
            "the parse-failure message must NOT carry the full request path; got: " + ex.getMessage());
    }
}
