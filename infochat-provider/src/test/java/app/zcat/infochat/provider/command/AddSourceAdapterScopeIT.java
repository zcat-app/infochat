package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.source.UrlProbe;
import app.zcat.infochat.provider.source.UrlProbe.ProbeResult;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the multi-adapter identity-bleed regression for
 * {@code /add-source} per {@code docs/spec/security.md}
 * §Per-(user, scope) isolation + the V5
 * {@code UNIQUE (adapter, contact_id)} constraint on {@code users}.
 *
 * <p>Two users share the SAME {@code contact_id} literal across two
 * adapters: the {@code inmemory} adapter holds a non-banned, non-admin
 * row; a synthetic {@code simplex-fake-m1-040ai} adapter holds a
 * BANNED row with the same {@code contact_id}. When the test delivers
 * {@code /add-source} through {@link InMemoryAdapter}, the handler's
 * {@code lookupActor} MUST resolve the inmemory row — letting the
 * dispatch proceed past the ban check. The pre-M1-040 SELECT (without
 * the {@code adapter = ?} predicate) could cross-resolve to the
 * banned row and reject a legitimate inmemory caller, leaking the
 * existence of the foreign-adapter ban state into the inmemory
 * caller's session.
 */
@QuarkusTest
@TestProfile(AddSourceAdapterScopeIT.MvpProfile.class)
class AddSourceAdapterScopeIT {

    private static final String PREFIX = "m1-040ai-";
    private static final String SHARED_CONTACT_ID = PREFIX + "shared";
    private static final String FOREIGN_ADAPTER = "simplex-fake-m1-040ai";
    private static final String FEED_URL =
            "https://example.com/" + PREFIX + "shared.xml";

    @Inject InMemoryAdapter adapter;

    @Inject MockUrlProbe mockProbe;

    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        mockProbe.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Guardian admin to keep the last-admin-protection trigger
            // happy across delete-then-insert cycles (V5
            // trg_last_admin_protection_delete).
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, "
                            + "registration_state) "
                            + "VALUES ('inmemory', 'm1-040ai-guardian', TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE");
            exec(conn, "DELETE FROM source_subscription "
                    + "WHERE source_id IN (SELECT id FROM source "
                    + "                     WHERE identifier LIKE 'https://example.com/"
                    + PREFIX + "%')");
            exec(conn, "DELETE FROM source "
                    + "WHERE identifier LIKE 'https://example.com/" + PREFIX + "%'");
            exec(conn,
                    "DELETE FROM users "
                            + "WHERE contact_id LIKE '" + PREFIX + "%' "
                            + "  AND contact_id <> 'm1-040ai-guardian'");
        }
    }

    @Test
    void inmemoryDeliveryResolvesInmemoryUserNotForeignBannedUser() throws Exception {
        // Seed both rows BEFORE the dispatch. AutoRegisterService's
        // upsert on (inmemory, SHARED_CONTACT_ID) is a no-op against
        // the pre-existing inmemory row.
        UUID inmemoryUserId = insertUser("inmemory", SHARED_CONTACT_ID,
                /*admin=*/false, /*banned=*/false);
        UUID foreignUserId = insertUser(FOREIGN_ADAPTER, SHARED_CONTACT_ID,
                /*admin=*/false, /*banned=*/true);
        mockProbe.setOk(FEED_URL,
                ProbeResult.success(200, Optional.of("application/rss+xml")));

        adapter.deliverDm(SHARED_CONTACT_ID,
                "/add-source " + FEED_URL + " --tags ai");

        assertEquals(1, adapter.sentMessages().size(),
                "exactly one outbound reply must be produced");
        String body = adapter.sentMessages().get(0).text();
        // A contact_id-only SELECT could resolve to the foreign-banned
        // row and surface the banned literal; the (adapter, contact_id)
        // SELECT MUST pick the inmemory row and let the dispatch
        // proceed past the ban check.
        assertFalse(body.contains("not permitted"),
                "(adapter, contact_id) lookup MUST NOT pick the foreign-adapter "
                        + "banned row when delivering through inmemory. The banned "
                        + "literal indicates the cross-adapter row was selected. "
                        + "Got: " + body);
        // Successful dispatch lands at Branch A (FRESH_INSERT) with the
        // URL-visibility disclosure literal.
        assertTrue(body.contains("visible to bot admins"),
                "the inmemory user's dispatch must reach Branch A and include "
                        + "the URL-visibility disclosure literal. Got: " + body);
        assertFalse(inmemoryUserId.equals(foreignUserId),
                "test fixture sanity: the two seeded users have distinct ids");
    }

    // --- helpers ---------------------------------------------------------

    private UUID insertUser(String adapterName, String contactId,
                             boolean isAdmin, boolean isBanned) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state) "
                             + "VALUES (?, ?, ?, ?, 'invited') "
                             + "RETURNING id")) {
            ps.setString(1, adapterName);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /**
     * CDI {@link Alternative} replacing {@link UrlProbe} for this IT
     * — mirrors the {@code AddSourceCommandHandlerTest.MockUrlProbe}
     * shape (per-URL canned results; default success with no
     * content-type). Activation is scoped to {@link MvpProfile} via
     * {@link QuarkusTestProfile#getEnabledAlternatives()} so it does
     * not clash with {@code AddSourceIT.LoopbackProbe}.
     */
    @Alternative
    @ApplicationScoped
    public static class MockUrlProbe extends UrlProbe {

        private final Map<String, ProbeResult> canned = new ConcurrentHashMap<>();

        @Override
        public ProbeResult probe(URI url) {
            return canned.getOrDefault(
                    url.toString(),
                    ProbeResult.success(200, Optional.empty()));
        }

        void setOk(String url, ProbeResult result) {
            canned.put(url, result);
        }

        void reset() {
            canned.clear();
        }
    }

    public static final class MvpProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(MockUrlProbe.class);
        }
    }
}
