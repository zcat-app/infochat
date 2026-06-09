package app.zcat.infochat.provider.outbox;

import app.zcat.infochat.provider.outbox.QuarantineReviewListener.Payload;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Handler-tier (plain JUnit) test for
 * {@link QuarantineReviewListener#parsePayload(String)}'s wire-boundary
 * discriminator validation.
 *
 * <p>Pins that {@code target_kind} is validated against the enumerated
 * base-table set {@code {"quarantine","post"}}: an out-of-set value is
 * rejected with an {@link IllegalArgumentException} (a
 * {@link RuntimeException}, so {@code dispatch} drops it with a log line
 * that omits the payload) rather than falling through to the {@code post} base-table
 * lookup in {@code lookupRowState} — the silent mis-route the validation
 * closes.
 */
class QuarantineReviewListenerDiscriminatorTest {

    private static final UUID TARGET_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static String payload(String targetKind) {
        return "{\"target_kind\":\"" + targetKind
                + "\",\"target_id\":\"" + TARGET_ID
                + "\",\"new_status\":\"PENDING\"}";
    }

    @Test
    void outOfSetDiscriminatorIsDroppedNotMisRouted() {
        // "comment" is the quarantine_comment base table that does not
        // exist for this channel; without validation it would silently
        // route to the post lookup. parsePayload must throw so dispatch
        // drops-with-log instead.
        assertThrows(IllegalArgumentException.class,
                () -> QuarantineReviewListener.parsePayload(payload("comment")),
                "an out-of-set target_kind must be rejected at the wire "
                        + "boundary, not routed to the post base-table lookup");
    }

    @Test
    void quarantineDiscriminatorParses() {
        Payload parsed = QuarantineReviewListener.parsePayload(payload("quarantine"));
        assertEquals("quarantine", parsed.targetKind());
        assertEquals(TARGET_ID, parsed.targetId());
    }

    @Test
    void postDiscriminatorParses() {
        Payload parsed = QuarantineReviewListener.parsePayload(payload("post"));
        assertEquals("post", parsed.targetKind());
        assertEquals(TARGET_ID, parsed.targetId());
    }
}
