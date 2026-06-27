package app.zcat.infochat.messaging.impl.simplex;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * M1-465: {@link SimpleXAdapter#canonicalizeContactId} extracts the bare
 * queue id from an operator-supplied full SimpleX contact link so the
 * seeded bootstrap-admin row byte-matches inbound messages. Reuses the
 * bot-identity extractor ({@link SimpleXMessageCodec#extractQueueAddressId})
 * — the single source of extraction truth — rather than duplicating the
 * link grammar (the §Context drift argument).
 */
class SimpleXCanonicalizeContactIdTest {

    private final SimpleXAdapter adapter = new SimpleXAdapter();

    /** A bare SimpleX queue address (URL-safe base64, 44 chars ≥ the 43 floor). */
    private static final String BARE_QUEUE_ID = "SimplexBootstrapAdminQueueAddr0000000000000A";

    /**
     * Build a full SimpleX contact link embedding {@code queueId} as the
     * path segment of the percent-encoded {@code smp=} SMP-queue URI — the
     * shape {@link SimpleXMessageCodec#extractQueueAddressId} parses. The
     * whole SMP URI is URL-encoded so the test does not hand-roll the
     * percent-encoding the extractor's {@code URLDecoder} expects.
     */
    private static String contactLink(String queueId) {
        String smpUri = "smp://hQ@smp.example.com/" + queueId + "#/?v=1&dh=AB";
        return "https://simplex.chat/contact#/?v=2-7&smp="
                + URLEncoder.encode(smpUri, StandardCharsets.UTF_8);
    }

    @Test
    void extractsBareQueueIdFromFullContactLink() {
        String link = contactLink(BARE_QUEUE_ID);
        assertEquals(BARE_QUEUE_ID, adapter.canonicalizeContactId(link),
                "a full contact link must canonicalize to its bare queue id");
        // Agrees with the single source of extraction truth it reuses.
        assertEquals(SimpleXMessageCodec.extractQueueAddressId(link),
                adapter.canonicalizeContactId(link),
                "canonicalization must equal the bot-identity extractor's result");
    }

    @Test
    void passesThroughAnAlreadyBareQueueId() {
        assertEquals(BARE_QUEUE_ID, adapter.canonicalizeContactId(BARE_QUEUE_ID),
                "an already-bare queue id must be returned unchanged (idempotent)");
    }

    @Test
    void returnsInputUnchangedWhenLinkHasNoExtractableQueueId() {
        // A link whose smp= SMP URI has an empty path segment: no queue id to
        // extract, so the value passes through unchanged and the downstream
        // well-formed gate is what then rejects it.
        String noQueueId = "https://simplex.chat/contact#/?v=2-7&smp="
                + URLEncoder.encode("smp://hQ@smp.example.com/", StandardCharsets.UTF_8);
        assertEquals(noQueueId, adapter.canonicalizeContactId(noQueueId),
                "a link with no extractable queue id must be returned unchanged");
        assertFalse(adapter.isWellFormedContactId(noQueueId),
                "the unextracted link must then fail the well-formed gate");
    }
}
