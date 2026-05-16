package io.infochat.provider.outbox;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the M1-030 advisory #1 hardening on
 * {@link NewPostHandler}: a poisoned NOTIFY whose payload does not
 * correspond to a real READY {@code post} row must NOT advance the
 * cursor. Exercises:
 *
 * <ul>
 *   <li>handle(non-existent UUID, future-ready_at) — returns false;
 *       cursor stays at its prior position.</li>
 *   <li>handle(existing post id, mismatched ready_at) — returns false;
 *       cursor stays at its prior position. The mismatch path is the
 *       interesting one: an attacker who knew real post IDs could
 *       still poison the cursor with a fabricated ready_at without
 *       this check.</li>
 *   <li>handle(existing post id, matching ready_at, status=READY) —
 *       advances the cursor (the happy path; proves the check did
 *       not over-reject legitimate input).</li>
 * </ul>
 */
@QuarkusTest
class NewPostHandlerHardeningIT {

    private static final String TEST_UID_PREFIX = "handler-hardening-it/";
    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T12:00:00Z");
    private static final Instant READY_AT_BASE = Instant.parse("2026-05-15T18:00:00Z");

    @Inject
    DataSource dataSource;

    @Inject
    NewPostHandler newPostHandler;

    @Inject
    ProviderStateDao providerStateDao;

    private UUID testSourceId;

    @BeforeEach
    void setUp() throws Exception {
        // Broad cleanup so cursor + post state is deterministic regardless
        // of which IT in this module ran before us; see NewPostReconcilerPagingIT
        // for the rationale (shared fixture-prefix convention).
        clearAllItPosts();
        resetNewPostCursor();
        testSourceId = ensureTestSource();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearTestPosts();
    }

    @Test
    void handleRejectsNonExistentPostIdWithoutAdvancingCursor() throws Exception {
        UUID fakePostId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        Instant futureReadyAt = READY_AT_BASE.plus(1, ChronoUnit.HOURS);

        ProviderStateDao.Cursor before = providerStateDao
            .readCursor(NewPostHandler.CHANNEL_NEW_POST).orElseThrow();

        boolean advanced = newPostHandler.handle(fakePostId, futureReadyAt);

        assertFalse(advanced,
            "handle must return false for a non-existent post_id — the existence check rejects the payload");
        ProviderStateDao.Cursor after = providerStateDao
            .readCursor(NewPostHandler.CHANNEL_NEW_POST).orElseThrow();
        assertEquals(before.cursorHigh(), after.cursorHigh(),
            "cursor_high must NOT advance when the post row does not exist");
        assertEquals(before.cursorLowId(), after.cursorLowId(),
            "cursor_low_id must NOT advance when the post row does not exist");
        assertEquals(before.cursorLowKind(), after.cursorLowKind(),
            "cursor_low_kind must NOT change on a rejected payload");
    }

    @Test
    void handleRejectsMismatchedReadyAtForExistingPostWithoutAdvancingCursor() throws Exception {
        SeededRow seeded = seedReadyRow(0);
        Instant wrongReadyAt = seeded.readyAt().plus(1, ChronoUnit.HOURS);

        ProviderStateDao.Cursor before = providerStateDao
            .readCursor(NewPostHandler.CHANNEL_NEW_POST).orElseThrow();

        boolean advanced = newPostHandler.handle(seeded.id(), wrongReadyAt);

        assertFalse(advanced,
            "handle must return false when the supplied ready_at does not match the stored row's ready_at");
        ProviderStateDao.Cursor after = providerStateDao
            .readCursor(NewPostHandler.CHANNEL_NEW_POST).orElseThrow();
        assertEquals(before.cursorHigh(), after.cursorHigh(),
            "cursor_high must NOT advance on a ready_at mismatch — the (id, ready_at) tuple is the check key");
        assertEquals(before.cursorLowId(), after.cursorLowId(),
            "cursor_low_id must NOT advance on a ready_at mismatch");
    }

    @Test
    void handleAdvancesCursorWhenPayloadMatchesAReadyRow() throws Exception {
        SeededRow seeded = seedReadyRow(0);

        boolean advanced = newPostHandler.handle(seeded.id(), seeded.readyAt());

        assertTrue(advanced,
            "handle must return true when the payload matches a real READY row — the existence check is over-tight if this fails");
        ProviderStateDao.Cursor after = providerStateDao
            .readCursor(NewPostHandler.CHANNEL_NEW_POST).orElseThrow();
        assertEquals(seeded.readyAt(), after.cursorHigh(),
            "cursor_high must advance to the seeded row's ready_at");
        assertEquals(seeded.id().toString(), after.cursorLowId(),
            "cursor_low_id must advance to the seeded row's id");
        assertEquals(NewPostHandler.CURSOR_LOW_KIND_POST, after.cursorLowKind(),
            "cursor_low_kind must upgrade from '' to 'post' on the first real event");
    }

    private SeededRow seedReadyRow(int i) throws Exception {
        Instant readyAt = READY_AT_BASE.plus(i, ChronoUnit.SECONDS);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, status, fetched_at, ready_at) "
                     + "VALUES (?, ?, ?, 'READY', ?, ?) RETURNING id")) {
            ps.setString(1, TEST_UID_PREFIX + i + "-" + UUID.randomUUID());
            ps.setObject(2, testSourceId);
            ps.setString(3, "handler-hardening-it post " + i);
            ps.setTimestamp(4, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(5, Timestamp.from(readyAt));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "INSERT … RETURNING must yield the new id");
                UUID id = rs.getObject("id", UUID.class);
                return new SeededRow(id, readyAt);
            }
        }
    }

    private void clearTestPosts() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE ?")) {
            ps.setString(1, TEST_UID_PREFIX + "%");
            ps.executeUpdate();
        }
    }

    private void clearAllItPosts() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE '%-it/%'")) {
            ps.executeUpdate();
        }
    }

    private void resetNewPostCursor() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE provider_state "
                     + "   SET cursor_high = 'epoch'::TIMESTAMPTZ, "
                     + "       cursor_low_kind = '', "
                     + "       cursor_low_id = '', "
                     + "       updated_at = now() "
                     + " WHERE channel = 'new_post'")) {
            ps.executeUpdate();
        }
    }

    private UUID ensureTestSource() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category) "
                     + "VALUES ('rss', 'handler-hardening-it://test', 'handler-hardening-it', 'news') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE "
                     + "SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "test source upsert must yield an id");
                return rs.getObject("id", UUID.class);
            }
        }
    }

    private record SeededRow(UUID id, Instant readyAt) {}
}
