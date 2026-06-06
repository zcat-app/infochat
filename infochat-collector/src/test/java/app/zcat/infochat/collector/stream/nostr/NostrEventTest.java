package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.core.ingest.NormalizedPost;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Behavioural tests for the {@link NostrEvent#toNormalizedPost}
 * created_at→published_at clamp. created_at is relay-supplied; without the
 * clamp a single future-dated event poisons the per-source
 * {@code MAX(published_at)} reconnect cursor, so the since filter excludes
 * every genuine event and the source goes silent.
 */
class NostrEventTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-06-06T12:00:00Z");

    @Test
    void farFutureCreatedAt_clampedToFetchedAt() {
        long farFutureEpochSeconds = FETCHED_AT.plusSeconds(10L * 365 * 24 * 3600).getEpochSecond();

        NormalizedPost post = event(farFutureEpochSeconds).toNormalizedPost(0L, FETCHED_AT);

        assertEquals(FETCHED_AT, post.publishedAt(), "future created_at clamped to receipt time");
        assertFalse(post.publishedAt().isAfter(Instant.now()),
                "clamped published_at never exceeds wall-clock now");
    }

    @Test
    void pastCreatedAt_preservedUnchanged() {
        Instant past = FETCHED_AT.minusSeconds(3600);

        NormalizedPost post = event(past.getEpochSecond()).toNormalizedPost(0L, FETCHED_AT);

        assertEquals(past, post.publishedAt(), "past created_at preserved verbatim");
    }

    private static NostrEvent event(long createdAt) {
        return new NostrEvent("a".repeat(64), "b".repeat(64), createdAt, 1, List.of(), "content", "c".repeat(128));
    }
}
