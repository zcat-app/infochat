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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void requireHttpBaseUrlRedactsCredentialOnMalformedUrl() {
        // A base-url that BOTH embeds userinfo AND fails new URI(...): a literal
        // space in the password makes URISyntaxException fire on the parse branch
        // BEFORE the userinfo-rejection branch can run. That branch echoes the
        // value, and URISyntaxException.getMessage() re-quotes the raw input
        // verbatim, so without redaction the credential lands in the thrown
        // message and the boot log — the M1-330 leak class, on a sibling branch.
        String property = "infochat.llm.security.base-url";
        String secret = "s3c r3t";
        String user = "apiuser";
        String credentialBearingUrl = "https://" + user + ":" + secret + "@llm.example.com/v1";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> LlmHttpSupport.requireHttpBaseUrl(credentialBearingUrl, property),
            "a malformed credential-bearing base-url must be rejected at the config boundary");

        assertTrue(ex.getMessage().contains(property),
            "the rejection must name the offending property; got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(secret),
            "the rejection must NOT echo the userinfo credential; got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(user),
            "the rejection must NOT echo the userinfo user; got: " + ex.getMessage());
        assertNull(ex.getCause(),
            "the URISyntaxException cause is dropped — its getMessage() re-quotes the "
                + "unredacted input, which Quarkus would print in the boot log's cause chain");
    }

    @Test
    void requireHttpBaseUrlRedactsCredentialWhenUserinfoContainsPathDelimiter() {
        // Hardening for the redaction edge case: a userinfo that contains a raw
        // '/', '?', or '#' BEFORE the '@' (illegal in userinfo, so it only
        // occurs on malformed input) must still be masked. Here a space forces
        // the URISyntaxException parse-failure branch to fire, and the '/' in
        // the password would truncate a first-delimiter authority scan before
        // the real '@' — so masking must run to the LAST '@', not a bounded
        // authority, or the credential echoes verbatim into the boot log.
        String property = "infochat.llm.security.base-url";
        String secret = "pa/ss";
        String user = "us er";
        String credentialBearingUrl = "https://" + user + ":" + secret + "@llm.example.com/v1";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> LlmHttpSupport.requireHttpBaseUrl(credentialBearingUrl, property),
            "a malformed credential-bearing base-url must be rejected at the config boundary");

        assertTrue(ex.getMessage().contains(property),
            "the rejection must name the offending property; got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(secret),
            "the rejection must NOT echo the userinfo credential even when it "
                + "contains a path delimiter; got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(user),
            "the rejection must NOT echo the userinfo user; got: " + ex.getMessage());
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

    @Test
    void shapeFailureDoesNotEchoResponseBody() {
        // A 2xx reply that is valid JSON but the wrong shape (no choices[])
        // drives the shape-failure path that previously appended a body
        // preview. A hostile/buggy endpoint can reflect prompt or user
        // content in those JSON fields, so the body must never reach the
        // exception message — only the host and the named shape failure.
        String bodyEcho = "REFLECTED-PROMPT-CONTENT-9f3a";
        mockServer.createContext("/chat/completions", exchange -> {
            byte[] resp = ("{\"note\":\"" + bodyEcho + "\"}").getBytes(StandardCharsets.UTF_8);
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
            "a 2xx reply missing choices[] must surface as LlmCallFailedException");

        assertTrue(ex.getMessage().contains("localhost"),
            "the shape-failure message must name the host for triage; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("choices[]"),
            "the shape-failure message must name the specific shape failure; got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(bodyEcho),
            "the shape-failure message must NOT echo the provider response body; got: " + ex.getMessage());
    }
}
