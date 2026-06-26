package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link SavedCommandHandler} {@code /saved -w <window>}
 * {@code saved_at > cutoff} boundary against an injected {@link Clock}
 * (M1-454, engineering-rules §9). The predicate is strict {@code >}: with the
 * Clock fixed at {@code pinnedNow}, a save one second after the
 * {@code pinnedNow - window} cutoff is listed, one ON the cutoff is excluded.
 * Both fixtures sit weeks before any 7-day wall-clock cutoff, so the listed
 * save can only come from the pinned Clock — proving {@code bindFilters} reads
 * {@code clock.instant()}.
 */
@QuarkusTest
class SavedCommandHandlerClockTest {

    private static final String PREFIX = "m1-454-saved-clock-";
    private static final String ADAPTER = "inmemory";
    private static final Instant PINNED_NOW = Instant.parse("2026-05-22T12:00:00Z");

    @Inject SavedCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                "DELETE FROM saved_post WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void savedWindowBoundaryDecidedByInjectedClock() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);

        String contactId = PREFIX + "actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "src");

        Instant cutoff = PINNED_NOW.minus(Duration.ofDays(7));
        // saved_at > cutoff is strict: a save one second after the cutoff is
        // listed, one ON the cutoff is excluded.
        seedSavedPost(userId, sourceId, PREFIX + "after-cutoff", cutoff.plusSeconds(1));
        seedSavedPost(userId, sourceId, PREFIX + "on-cutoff", cutoff);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved -w 7d");

        assertTrue(reply.text().contains(PREFIX + "after-cutoff"),
            "a save one second after the injected-clock cutoff is inside the > window and "
                + "must be listed; got: " + reply.text());
        assertFalse(reply.text().contains(PREFIX + "on-cutoff"),
            "a save ON the injected-clock cutoff is outside the strict > window and must be "
                + "excluded; got: " + reply.text());
    }

    // ----- helpers --------------------------------------------------------

    private UUID seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state) VALUES (?, ?, FALSE, FALSE, 'vouched') "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags) VALUES ('rss', ?, ?, 'news', '{}') "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, "Test Source " + identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedSavedPost(UUID userId, UUID sourceId, String postUid, Instant savedAt)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO saved_post (user_id, post_uid, source_id, title, "
                             + "snapshot_tags, personal_tags, saved_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            ps.setObject(3, sourceId);
            ps.setString(4, "Title for " + postUid);
            ps.setArray(5, conn.createArrayOf("TEXT", new String[] {}));
            ps.setArray(6, conn.createArrayOf("TEXT", new String[] {}));
            ps.setObject(7, OffsetDateTime.ofInstant(savedAt, ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
