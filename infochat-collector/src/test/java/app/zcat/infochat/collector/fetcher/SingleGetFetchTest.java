package app.zcat.infochat.collector.fetcher;

import app.zcat.infochat.core.ingest.NormalizedPost;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit test for {@link SingleGetFetch} — the shared transport-and-parse
 * path the four single-GET fetchers delegate to. The per-fetcher tests only
 * exercise this logic through one kind's copy; this test pins the helper's
 * redaction, interrupt, and status-code behaviour once, at the seam.
 *
 * <p>The {@link SingleGetFetch.GuardedGet} functional seam is what makes the
 * interrupt and I/O branches testable without a live socket or a flaky timing
 * race: each test supplies a lambda that returns or throws exactly the
 * condition under test.
 */
class SingleGetFetchTest {

    private static final Path RSS_FIXTURE =
        Paths.get("src/test/resources/fixtures/rss/rss20-sample.xml");

    // An identifier carrying a userinfo credential and a query token — both
    // must be stripped by UrlRedactor before reaching any failure message.
    private static final String CREDENTIAL_URL =
        "https://user:secret@feeds.example.com/rss?token=abc123";

    private static final SingleGetFetch.FetchExceptionFactory FACTORY =
        (message, cause) -> new IllegalStateException(message, cause);

    @Test
    void twoHundredBodyIsParsedAndShareOneFetchedAt() throws IOException {
        byte[] body = Files.readAllBytes(RSS_FIXTURE);
        SingleGetFetch.GuardedGet ok = uri -> new StubResponse(200, body);

        List<NormalizedPost> posts = SingleGetFetch.fetchAndParse(
            ok, "RSS", 5L, "https://feeds.example.com/rss", FACTORY);

        assertEquals(3, posts.size(),
            "the RSS 2.0 fixture has three <item> elements; the helper returns one post each");
        Instant shared = posts.get(0).fetchedAt();
        for (NormalizedPost post : posts) {
            assertEquals(5L, post.dispatchKey(),
                "every post carries the caller-supplied dispatch/source id");
            assertEquals(shared, post.fetchedAt(),
                "every post from one fetchAndParse invocation shares one fetchedAt");
        }
    }

    @Test
    void nonTwoxxRaisesLabeledExceptionWithNoCause() {
        SingleGetFetch.GuardedGet notFound = uri -> new StubResponse(404, new byte[0]);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> SingleGetFetch.fetchAndParse(
                notFound, "Odysee", 1L, "https://odysee.com/$/rss/@chan", FACTORY));

        assertTrue(ex.getMessage().contains("Odysee fetch got HTTP 404 for"),
            "status-branch message must carry the kind label and the upstream status: " + ex.getMessage());
        assertNull(ex.getCause(),
            "the status branch has no underlying throwable — the factory is handed a null cause");
    }

    @Test
    void ioFailureWrapsCauseAndRedactsCredentialUrl() {
        // The cause message deliberately carries no URL: the helper redacts only
        // the *identifier* it interpolates, not the arbitrary cause message it
        // appends — scrubbing the cause text is the logging layer's job
        // (FetchScheduler log redaction), out of scope for this transport helper.
        IOException rootCause = new IOException("connection reset by peer");
        SingleGetFetch.GuardedGet failing = uri -> {
            throw rootCause;
        };

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> SingleGetFetch.fetchAndParse(failing, "Nitter", 1L, CREDENTIAL_URL, FACTORY));

        assertTrue(ex.getMessage().contains("Nitter fetch I/O failure for"),
            "I/O-branch message must carry the kind label: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("https://feeds.example.com/[REDACTED]"),
            "the identifier must be redacted (userinfo dropped, path+query collapsed): " + ex.getMessage());
        assertFalse(ex.getMessage().contains("secret"),
            "the userinfo credential from the identifier must not leak into the message: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("abc123"),
            "the query token from the identifier must not leak into the message: " + ex.getMessage());
        assertSame(rootCause, ex.getCause(),
            "the I/O branch must preserve the original IOException as the cause");
    }

    @Test
    void interruptReSetsFlagWrapsCauseAndRedacts() {
        InterruptedException rootCause = new InterruptedException("simulated interrupt");
        SingleGetFetch.GuardedGet interrupting = uri -> {
            throw rootCause;
        };

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> SingleGetFetch.fetchAndParse(interrupting, "YouTube", 1L, CREDENTIAL_URL, FACTORY));

        // Thread.interrupted() both asserts the helper re-set the flag and
        // clears it so the interrupt does not leak into sibling tests.
        assertTrue(Thread.interrupted(),
            "the helper must restore the interrupt flag the JDK clears when InterruptedException is thrown");
        assertTrue(ex.getMessage().contains("YouTube fetch interrupted for"),
            "interrupt-branch message must carry the kind label: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("https://feeds.example.com/[REDACTED]"),
            "the identifier must be redacted on the interrupt branch too: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("secret"),
            "the userinfo credential must not leak into the message: " + ex.getMessage());
        assertSame(rootCause, ex.getCause(),
            "the interrupt branch must preserve the original InterruptedException as the cause");
    }

    /**
     * Minimal {@link HttpResponse} stub returning a fixed status and body; the
     * helper reads only {@code statusCode()} and {@code body()}. The remaining
     * accessors return inert, non-null values so the test compiles under the
     * module's null-marking.
     */
    private static final class StubResponse implements HttpResponse<byte[]> {

        private static final URI STUB_URI = URI.create("https://stub.invalid/");

        private final int status;

        private final byte[] body;

        StubResponse(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public byte[] body() {
            return body;
        }

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(STUB_URI).build();
        }

        @Override
        public Optional<HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return STUB_URI;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
