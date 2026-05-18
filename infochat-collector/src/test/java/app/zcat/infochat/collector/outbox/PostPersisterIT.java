package app.zcat.infochat.collector.outbox;

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
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * <p>Method order is fixed because the cases share DB state and each
 * builds on the previous.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostPersisterIT {

    @Inject
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
                 + "       stage1_flagged, stage2_failed, tagger_fallback, tags "
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
            "second persist must be a no-op (ON CONFLICT (source_id, "
            + "upstream_identifier, fetched_at) DO NOTHING)");

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
