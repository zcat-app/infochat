package app.zcat.infochat.collector.outbox;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link PostPersister} against the Quarkus
 * DevServices Postgres. Seeds a {@code source} row (kind='rss'),
 * calls the persister with a fixture {@link NormalizedPost}, and
 * asserts the post row's exact shape:
 * <ul>
 *   <li>{@code status='RAW'}; all four {@code *_done} flags FALSE</li>
 *   <li>{@code uid} matches the expected SHA-256 hash</li>
 *   <li>{@code tags} is the empty array</li>
 *   <li>{@code upstream_identifier}, {@code title}, {@code body},
 *       {@code url}, {@code published_at}, {@code fetched_at} match
 *       the NormalizedPost</li>
 * </ul>
 *
 * <p>Also pins the dedup contract: same-tick duplicates (same
 * {@code fetched_at}) and cross-tick re-fetches of the same uid
 * (distinct {@code fetched_at}, including a later partition) each
 * yield exactly one row and an empty persist result.
 *
 * <p>Method order is fixed because the cases share DB state and each
 * builds on the previous.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostPersisterIT {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    PostPersister postPersister;

    private static final Instant FETCHED_AT =
        Instant.parse("2026-05-15T12:00:00Z");
    private static final Instant PUBLISHED_AT =
        Instant.parse("2026-05-15T11:55:00Z");

    @Test
    @Order(1)
    void persistInsertsRawPostWithExpectedColumns() throws Exception {
        // Seed a fresh source row dedicated to this test method. The
        // BootstrapLoader already wrote some rss rows at startup, so
        // we add one with a unique identifier to avoid the UNIQUE
        // (kind, identifier) constraint.
        UUID sourceUuid = seedRssSource(
            "https://persister-it.example.test/feed-1.xml",
            "Persister IT source 1");

        NormalizedPost normalized = new NormalizedPost(
            /* sourceId dispatch key */ 1L,
            /* upstreamIdentifier */ "urn:persister-it:post:1",
            /* title */ "Persister IT post 1 title",
            /* body */ "Persister IT post 1 body",
            /* url */ "https://persister-it.example.test/posts/1",
            /* publishedAt */ PUBLISHED_AT,
            /* fetchedAt */ FETCHED_AT,
            /* rawMetadata */ Map.of()
        );

        Optional<PostPersister.PersistedPostKey> key =
            postPersister.persist(sourceUuid, normalized);
        assertTrue(key.isPresent(), "first persist must INSERT (no ON CONFLICT)");

        // Assert column-by-column against the persisted row.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT uid, source_id, upstream_identifier, url, title, body, "
                 + "       author, published_at, fetched_at, status, "
                 + "       stage1_done, stage2_done, tagger_done, embedding_done, "
                 + "       stage1_flagged, stage2_failed, tagger_fallback, tags, "
                 + "       likes, reposts, social_score "
                 + "FROM post WHERE id = ?")) {
            ps.setObject(1, key.get().id());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "persisted post row must exist");

                String expectedUid = PostPersister.deriveUid(sourceUuid, "urn:persister-it:post:1");
                assertEquals(expectedUid, rs.getString("uid"),
                    "uid must match sha256(source_uuid || '|' || upstream_identifier)");
                assertEquals(sourceUuid, rs.getObject("source_id"));
                assertEquals("urn:persister-it:post:1", rs.getString("upstream_identifier"));
                assertEquals("https://persister-it.example.test/posts/1", rs.getString("url"));
                assertEquals("Persister IT post 1 title", rs.getString("title"));
                assertEquals("Persister IT post 1 body", rs.getString("body"));
                assertEquals(null, rs.getString("author"),
                    "author column must be NULL (NormalizedPost v1 has no author field)");
                assertEquals(PUBLISHED_AT, rs.getTimestamp("published_at").toInstant());
                assertEquals(FETCHED_AT, rs.getTimestamp("fetched_at").toInstant());
                assertEquals("RAW", rs.getString("status"),
                    "status must be the literal 'RAW' per Invariant 5");

                // All four primary *_done flags must be FALSE (post is
                // pre-evaluation in the outbox).
                assertFalse(rs.getBoolean("stage1_done"), "stage1_done default FALSE");
                assertFalse(rs.getBoolean("stage2_done"), "stage2_done default FALSE");
                assertFalse(rs.getBoolean("tagger_done"), "tagger_done default FALSE");
                assertFalse(rs.getBoolean("embedding_done"), "embedding_done default FALSE");

                // Per-stage failure / fallback flags also default FALSE.
                assertFalse(rs.getBoolean("stage1_flagged"));
                assertFalse(rs.getBoolean("stage2_failed"));
                assertFalse(rs.getBoolean("tagger_fallback"));

                // tags is the empty array (TEXT[]).
                java.sql.Array tagsArray = rs.getArray("tags");
                assertNotNull(tagsArray, "tags must be the empty array, not NULL");
                Object[] tags = (Object[]) tagsArray.getArray();
                assertEquals(0, tags.length, "tags default is the empty array");

                // M1-723: this fixture is an RSS-shaped post with no
                // engagement signals. All three social columns must be
                // SQL NULL — "no social signal available" — and NOT 0,
                // which would be indistinguishable from a social post
                // nobody engaged with. getObject, not getInt: getInt
                // maps SQL NULL to 0 and would pass either way.
                assertNull(rs.getObject("likes"), "no-signal source persists NULL likes, not 0");
                assertNull(rs.getObject("reposts"),
                    "no-signal source persists NULL reposts, not 0");
                assertNull(rs.getObject("social_score"),
                    "no-signal source persists NULL social_score, not 0");
            }
        }
    }

    @Test
    @Order(8)
    void persistRoundTripsSocialSignalColumns() throws Exception {
        // M1-723: the three columns were declared in V7, parsed by two
        // fetchers, and never written. Pins that the INSERT's widened
        // column list and its bind-index sequence stay consistent — a
        // mis-numbered bind would surface here as a wrong or missing
        // value rather than as a silent NULL.
        UUID sourceUuid = seedRssSource(
            "https://persister-it.example.test/feed-social.xml",
            "Persister IT social source");

        NormalizedPost social = new NormalizedPost(
            /* sourceId dispatch key */ 1L,
            /* upstreamIdentifier */ "urn:persister-it:post:social",
            /* title */ "Social title",
            /* body */ "Social body",
            /* url */ "https://persister-it.example.test/posts/social",
            /* publishedAt */ PUBLISHED_AT,
            /* fetchedAt */ FETCHED_AT,
            /* rawMetadata */ Map.of(),
            /* likes */ 42,
            /* reposts */ 7
        );
        assertEquals(2 * 7 + 42, social.socialScore(),
            "the record derives the score before the persister ever sees it");

        Optional<PostPersister.PersistedPostKey> key =
            postPersister.persist(sourceUuid, social);
        assertTrue(key.isPresent(), "social-signal persist must INSERT");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT likes, reposts, social_score, title, body, status "
                 + "FROM post WHERE id = ?")) {
            ps.setObject(1, key.get().id());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "persisted social post row must exist");
                assertEquals(42, rs.getInt("likes"));
                assertEquals(7, rs.getInt("reposts"));
                assertEquals(2 * 7 + 42, rs.getInt("social_score"),
                    "social_score round-trips the canonical 2*reposts + likes");
                // The three new binds sit between tags and the uid
                // pre-filter probe; assert neighbours to catch an
                // off-by-one that shifted every later placeholder.
                assertEquals("Social title", rs.getString("title"));
                assertEquals("Social body", rs.getString("body"));
                assertEquals("RAW", rs.getString("status"));
            }
        }
    }

    @Test
    @Order(9)
    void persistStoresNegativeSocialScoreUnclamped() throws Exception {
        // Reddit's score is a NET vote count. A heavily-downvoted post
        // must reach the column negative rather than being floored at 0,
        // which would make it indistinguishable from an unengaged post.
        UUID sourceUuid = seedRssSource(
            "https://persister-it.example.test/feed-negative.xml",
            "Persister IT negative-score source");

        NormalizedPost downvoted = new NormalizedPost(
            1L,
            "urn:persister-it:post:negative",
            "Downvoted title",
            "Downvoted body",
            "https://persister-it.example.test/posts/negative",
            PUBLISHED_AT,
            FETCHED_AT,
            Map.of(),
            /* likes */ -250,
            /* reposts */ null
        );

        Optional<PostPersister.PersistedPostKey> key =
            postPersister.persist(sourceUuid, downvoted);
        assertTrue(key.isPresent(), "negative-score persist must INSERT");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT likes, reposts, social_score FROM post WHERE id = ?")) {
            ps.setObject(1, key.get().id());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(-250, rs.getInt("likes"), "a negative net score survives to the column");
                assertNull(rs.getObject("reposts"),
                    "Reddit reports no repost count; the column stays NULL");
                assertEquals(-250, rs.getInt("social_score"),
                    "the formula is applied unchanged, not clamped at zero");
            }
        }
    }

    @Test
    @Order(2)
    void persistIsNoOpOnDuplicateSourceUpstreamFetchedAt() throws Exception {
        // Re-use the same source identifier as test (1), but a new
        // upstream_identifier so the (source_id, upstream_identifier,
        // fetched_at) tuple is fresh for the first call.
        UUID sourceUuid = seedRssSource(
            "https://persister-it.example.test/feed-2.xml",
            "Persister IT source 2");

        NormalizedPost normalized = new NormalizedPost(
            1L,
            "urn:persister-it:post:dedup",
            "Title for dedup test",
            "Body for dedup test",
            "https://persister-it.example.test/posts/dedup",
            null,
            FETCHED_AT,
            Map.of()
        );

        Optional<PostPersister.PersistedPostKey> first =
            postPersister.persist(sourceUuid, normalized);
        assertTrue(first.isPresent(), "first persist must INSERT");

        Optional<PostPersister.PersistedPostKey> second =
            postPersister.persist(sourceUuid, normalized);
        assertFalse(second.isPresent(),
            "second persist of the same (source_id, upstream_identifier, "
            + "fetched_at) must be a no-op (uid pre-filter; ON CONFLICT "
            + "belt-and-suspenders)");

        // Assert there's exactly one post row for this (source_id,
        // upstream_identifier).
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM post "
                 + "WHERE source_id = ? AND upstream_identifier = ?")) {
            ps.setObject(1, sourceUuid);
            ps.setString(2, "urn:persister-it:post:dedup");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1),
                    "ON CONFLICT branch must NOT multiply rows");
            }
        }
    }

    @Test
    @Order(3)
    void persistRaisesOnNullUpstreamIdentifier() throws Exception {
        // SPI-contract assertion: NormalizedPost.upstreamIdentifier
        // is declared "Never null" (M1-007a). The persister throws
        // on a null arrival rather than implementing the spec's
        // content-hash fallback (per M1-028 §Alternatives considered).
        UUID sourceUuid = seedRssSource(
            "https://persister-it.example.test/feed-3.xml",
            "Persister IT source 3");

        NormalizedPost nullId = new NormalizedPost(
            1L,
            /* upstreamIdentifier */ null,
            "Title",
            "Body",
            "https://persister-it.example.test/posts/null-id",
            null,
            FETCHED_AT,
            Map.of()
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> postPersister.persist(sourceUuid, nullId));
        assertTrue(ex.getMessage().contains("upstreamIdentifier"),
            "exception must mention upstreamIdentifier; got: " + ex.getMessage());

        NormalizedPost emptyId = new NormalizedPost(
            1L,
            /* upstreamIdentifier */ "",
            "Title",
            "Body",
            "https://persister-it.example.test/posts/empty-id",
            null,
            FETCHED_AT,
            Map.of()
        );

        IllegalArgumentException emptyEx = assertThrows(IllegalArgumentException.class,
            () -> postPersister.persist(sourceUuid, emptyId));
        assertTrue(emptyEx.getMessage().contains("upstreamIdentifier"),
            "empty-string upstreamIdentifier must also raise");
    }

    @Test
    @Order(4)
    void persistDedupsSameUidAcrossTicks() throws Exception {
        // Per docs/spec/schema.md §UID derivation: "The post UID is
        // stable globally across Collectors and across re-fetches; it
        // is the dedup key for refetches and cross-relay redelivery
        // (decision D38)." Fetchers stamp a fresh fetched_at per tick,
        // so a re-fetched item arrives with the same uid but a
        // distinct fetched_at — the uid pre-filter must drop it.
        UUID sourceUuid = seedRssSource(
            "https://persister-it.example.test/feed-4.xml",
            "Persister IT source 4");

        NormalizedPost tickOne =
            crossTickPost("urn:persister-it:post:cross-tick", FETCHED_AT);
        NormalizedPost tickTwo =
            crossTickPost("urn:persister-it:post:cross-tick", FETCHED_AT.plusSeconds(60));

        Optional<PostPersister.PersistedPostKey> first =
            postPersister.persist(sourceUuid, tickOne);
        assertTrue(first.isPresent(), "first tick's persist must INSERT");

        Optional<PostPersister.PersistedPostKey> second =
            postPersister.persist(sourceUuid, tickTwo);
        assertFalse(second.isPresent(),
            "re-fetch on a later tick (distinct fetched_at) must report no new "
            + "row — the same signal the same-tick skip produces, so the "
            + "duplicate is never enqueued for Stage 1 a second time");

        assertEquals(1,
            countPosts(sourceUuid, "urn:persister-it:post:cross-tick"),
            "exactly one post row must exist after two ticks of the same uid");
    }

    @Test
    @Order(5)
    void batchMixingPersistedAndNewItemPersistsOnlyTheNewItem() throws Exception {
        UUID sourceUuid = seedRssSource(
            "https://persister-it.example.test/feed-5.xml",
            "Persister IT source 5");

        // Tick 1 persists item A alone.
        Optional<PostPersister.PersistedPostKey> tickOneA = postPersister.persist(
            sourceUuid, crossTickPost("urn:persister-it:post:batch-dup", FETCHED_AT));
        assertTrue(tickOneA.isPresent(), "tick 1 must INSERT item A");

        // Tick 2 re-fetches A alongside a genuinely new item B. The
        // second tick's fetched_at lands in the NEXT month's partition
        // — the cross-window redelivery case the uid key exists for.
        Instant tickTwoFetchedAt = Instant.parse("2026-06-15T12:00:00Z");
        Optional<PostPersister.PersistedPostKey> tickTwoA = postPersister.persist(
            sourceUuid, crossTickPost("urn:persister-it:post:batch-dup", tickTwoFetchedAt));
        Optional<PostPersister.PersistedPostKey> tickTwoB = postPersister.persist(
            sourceUuid, crossTickPost("urn:persister-it:post:batch-new", tickTwoFetchedAt));

        assertFalse(tickTwoA.isPresent(),
            "the already-persisted item must be filtered on tick 2");
        assertTrue(tickTwoB.isPresent(),
            "the genuinely new item in the same tick-2 batch must persist — "
            + "dedup filters per item, not per batch");

        assertEquals(1, countPosts(sourceUuid, "urn:persister-it:post:batch-dup"),
            "the re-fetched item must still have exactly one row");
        assertEquals(1, countPosts(sourceUuid, "urn:persister-it:post:batch-new"),
            "the new item must have exactly one row");
    }

    @Test
    @Order(6)
    void persistClampsFutureDatedPublishedAtToFetchedAt() throws Exception {
        UUID sourceUuid = seedRssSource(
            "https://persister-it.example.test/feed-6.xml",
            "Persister IT source 6");

        // A source claims a publish time 48h after we fetched it. The
        // persistence-boundary clamp must store fetched_at instead, so
        // a future claim cannot dominate searchPosts ordering
        // (security.md §Prompt-injection defenses).
        Instant futurePublishedAt = FETCHED_AT.plus(Duration.ofHours(48));
        NormalizedPost futureDated = new NormalizedPost(
            1L,
            "urn:persister-it:post:future-dated",
            "Future-dated title",
            "Future-dated body",
            "https://persister-it.example.test/posts/future-dated",
            futurePublishedAt,
            FETCHED_AT,
            Map.of()
        );

        Optional<PostPersister.PersistedPostKey> key =
            postPersister.persist(sourceUuid, futureDated);
        assertTrue(key.isPresent(), "future-dated persist must INSERT");

        assertEquals(FETCHED_AT, readPublishedAt(key.get().id()),
            "a published_at after fetched_at must be clamped to fetched_at, "
            + "not stored as the original future instant");
    }

    @Test
    @Order(7)
    void persistStoresNonFuturePublishedAtUnchangedAndNullAsNull() throws Exception {
        UUID sourceUuid = seedRssSource(
            "https://persister-it.example.test/feed-7.xml",
            "Persister IT source 7");

        // A publish time before fetched_at is a normal past post and
        // binds through unchanged (PUBLISHED_AT is 5 minutes before
        // FETCHED_AT).
        NormalizedPost pastDated = new NormalizedPost(
            1L,
            "urn:persister-it:post:past-dated",
            "Past-dated title",
            "Past-dated body",
            "https://persister-it.example.test/posts/past-dated",
            PUBLISHED_AT,
            FETCHED_AT,
            Map.of()
        );
        Optional<PostPersister.PersistedPostKey> pastKey =
            postPersister.persist(sourceUuid, pastDated);
        assertTrue(pastKey.isPresent(), "past-dated persist must INSERT");
        assertEquals(PUBLISHED_AT, readPublishedAt(pastKey.get().id()),
            "a published_at before fetched_at must be stored unchanged");

        // A null publish time binds as SQL NULL.
        NormalizedPost nullPublished = new NormalizedPost(
            1L,
            "urn:persister-it:post:null-published",
            "Null-published title",
            "Null-published body",
            "https://persister-it.example.test/posts/null-published",
            null,
            FETCHED_AT,
            Map.of()
        );
        Optional<PostPersister.PersistedPostKey> nullKey =
            postPersister.persist(sourceUuid, nullPublished);
        assertTrue(nullKey.isPresent(), "null-published persist must INSERT");
        assertEquals(null, readPublishedAt(nullKey.get().id()),
            "a null published_at must be bound as SQL NULL");
    }

    /**
     * Reads the {@code published_at} column for one post id, returning
     * {@code null} when the column is SQL NULL.
     */
    private Instant readPublishedAt(UUID id) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT published_at FROM post WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Timestamp publishedAt = rs.getTimestamp("published_at");
                return publishedAt == null ? null : publishedAt.toInstant();
            }
        }
    }

    /**
     * Builds the fixture NormalizedPost for the cross-tick dedup
     * cases: same upstream identifier ⇒ same uid; the per-tick
     * {@code fetchedAt} is the only varying field.
     */
    private static NormalizedPost crossTickPost(String upstreamIdentifier, Instant fetchedAt) {
        return new NormalizedPost(
            1L,
            upstreamIdentifier,
            "Cross-tick fixture title",
            "Cross-tick fixture body",
            "https://persister-it.example.test/posts/cross-tick",
            PUBLISHED_AT,
            fetchedAt,
            Map.of()
        );
    }

    /** Counts post rows for one (source_id, upstream_identifier). */
    private int countPosts(UUID sourceUuid, String upstreamIdentifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM post "
                 + "WHERE source_id = ? AND upstream_identifier = ?")) {
            ps.setObject(1, sourceUuid);
            ps.setString(2, upstreamIdentifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Insert a fresh source row dedicated to one test method. Returns
     * the generated UUID. The bootstrap loader's startup-time source
     * rows are not used by these tests (the IT's identifiers are
     * disjoint from the fixture's so the bootstrap rows are left
     * untouched).
     */
    private UUID seedRssSource(String identifier, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                 + "VALUES ('rss', ?, ?, 'news', '{}') "
                 + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }
}
