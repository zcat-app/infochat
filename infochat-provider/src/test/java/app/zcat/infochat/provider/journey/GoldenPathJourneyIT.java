package app.zcat.infochat.provider.journey;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestSlot;
import app.zcat.infochat.provider.digest.DigestWorker;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-415 golden-path end-to-end journey (USER_TEST_PLAN deliverable #4).
 *
 * <p>The slice ITs (InviteIntakeRoundtripIT, GroupAuthorizationRoundtripIT,
 * AssetCommandsRoundtripIT, DigestRoundtripIT, AdminBootstrapIT) each prove
 * one vertical in depth. None walks the whole user journey as one continuous
 * narrative. This breadth test does — proving the deliverable phases (setup →
 * admin config → usage) hang together end to end through the in-memory adapter
 * and TestLlmProvider, asserting one clear observable per hop:
 *
 * <ol>
 *   <li>bootstrap admin present (the {@code @Startup} bean seeded the
 *       configured contact, is_admin=true);</li>
 *   <li>{@code /invite create} mints a PENDING code;</li>
 *   <li>register via the code — probation begins;</li>
 *   <li>a probation-blocked command ({@code /save}) is rejected with the
 *       probation reply;</li>
 *   <li>graduation via {@code /vouch} clears probation;</li>
 *   <li>a DM content command ({@code /summary}) returns the seeded post;</li>
 *   <li>a chat-mode turn returns the stubbed agent reply;</li>
 *   <li>a group @mention is held pending + admin notified;</li>
 *   <li>{@code /approve-group} approves the group;</li>
 *   <li>a group command ({@code /help}) is processed normally;</li>
 *   <li>a digest is produced and delivered to the group;</li>
 *   <li>an asset command ({@code /zcash}) replies;</li>
 *   <li>{@code /ban} — the banned user receives the fixed reply and reaches
 *       no further processing.</li>
 * </ol>
 *
 * <p>Modeled on InviteIntakeRoundtripIT's single-method narrative shape:
 * each hop strictly depends on the prior hop's state, so the steps share one
 * {@code @Test}. The bootstrap admin (configured via
 * {@code infochat.adapters.inmemory.admin}) doubles as the permanent admin
 * actor and is never deleted by the per-test cleanup, so it both anchors hop 1
 * and keeps the global last-admin invariant satisfied across the run.
 */
@QuarkusTest
@TestProfile(GoldenPathJourneyIT.Profile.class)
class GoldenPathJourneyIT {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "gp-journey-";
    /** Bootstrap-admin contact seeded by the @Startup bean (hop 1) and reused as the admin actor. */
    private static final String BOOTSTRAP_ADMIN = PREFIX + "bootstrap";

    private static final BigDecimal ZCASH_PRICE = new BigDecimal("42.18");
    private static final String ZCASH_SOURCE_URL = "coingecko.com/en/coins/zcash";

    /** Matches any RFC-4122 UUID literal in a reply body (case-insensitive). */
    private static final Pattern UUID_IN_REPLY =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject TestLlmProvider testLlmProvider;
    @Inject DigestWorker digestWorker;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        testLlmProvider.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Chat state first (FK → users). Both DM scope (scope_id = user id)
            // and any group scope carried over.
            exec(conn, "DELETE FROM chat_message WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE ?)", PREFIX + "%");
            exec(conn, "DELETE FROM chat_session WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE ?)", PREFIX + "%");

            // Digest cache (FK → groups) before the groups are dropped.
            exec(conn, "DELETE FROM summary_cache WHERE group_id IN "
                    + "(SELECT id FROM groups WHERE upstream_group_id LIKE ?)", PREFIX + "%");

            // group_membership (FK → groups, users).
            exec(conn, "DELETE FROM group_membership WHERE group_id IN "
                    + "(SELECT id FROM groups WHERE upstream_group_id LIKE ?)", PREFIX + "%");

            // Retrieval scaffolding keyed on our prefixed source/post/tag.
            exec(conn, "DELETE FROM source_subscription WHERE source_id IN "
                    + "(SELECT id FROM source WHERE identifier LIKE ?)", PREFIX + "%");
            exec(conn, "DELETE FROM post WHERE uid LIKE ?", PREFIX + "%");
            exec(conn, "DELETE FROM summary_anchor WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE ?)", PREFIX + "%");
            // scope_preferences is polymorphic (no FK); key by our user / group ids
            // while those rows still resolve.
            exec(conn, "DELETE FROM scope_preferences WHERE scope_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE ?) "
                    + "OR scope_id IN (SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%", PREFIX + "%");
            exec(conn, "DELETE FROM tag WHERE name LIKE ?", PREFIX + "%");
            exec(conn, "DELETE FROM source WHERE identifier LIKE ?", PREFIX + "%");

            // Asset price snapshots (re-seeded per run in hop 12). The
            // asset_config row is owned by JourneyAssetConfigSeeder, which
            // seeds it at startup before the registry reads it — left intact
            // here so the registry's cached zcash entry stays valid.
            exec(conn, "DELETE FROM price_snapshot WHERE asset = 'zcash'");

            // invite_code (FK → users via created_by).
            exec(conn, "DELETE FROM invite_code WHERE expected_contact_id LIKE ? "
                    + "OR created_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%", PREFIX + "%");

            // audit_log carries no-update + no-delete triggers (V5); disable
            // them for the prefixed wipe then re-enable.
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn, "DELETE FROM audit_log WHERE target_contact_id LIKE ? "
                        + "OR actor_user_id IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%", PREFIX + "%");
                exec(conn, "DELETE FROM admin_notification_state WHERE notification_key LIKE ?",
                        "group-pending:" + ADAPTER + ":" + PREFIX + "%");
                // groups (FK → users via activated_by) before users.
                exec(conn, "DELETE FROM groups WHERE upstream_group_id LIKE ?", PREFIX + "%");
                exec(conn, "UPDATE users SET banned_by = NULL WHERE contact_id LIKE ?", PREFIX + "%");
                // Delete every prefixed user EXCEPT the bootstrap admin, which
                // the @Startup bean owns (hop 1) and which anchors the global
                // last-admin invariant for the whole run.
                exec(conn, "DELETE FROM users WHERE contact_id LIKE ? AND contact_id <> ?",
                        PREFIX + "%", BOOTSTRAP_ADMIN);
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    @Test
    void fullGoldenPathJourneyThroughInMemoryAdapter() throws Exception {
        String user = PREFIX + "u-1";
        String group = PREFIX + "g-1";

        // ----- Hop 1 — bootstrap admin present -----------------------------
        // The @Startup AdminBootstrap bean seeded the configured contact at
        // boot; the journey relies on it as the admin actor.
        assertTrue(isAdmin(BOOTSTRAP_ADMIN),
                "hop 1: the @Startup bootstrap bean must have seeded "
                        + BOOTSTRAP_ADMIN + " with is_admin=true");

        // ----- Hop 2 — /invite create mints a PENDING code -----------------
        adapter.deliverDm(BOOTSTRAP_ADMIN,
                "/invite create --adapter " + ADAPTER + " --contact " + user);
        UUID code = extractUuid(lastReply().text());
        assertNotNull(code,
                "hop 2: /invite create reply must contain the new code UUID — got: "
                        + lastReply().text());
        assertEquals(1L, countInvites(user, "PENDING"),
                "hop 2: exactly one PENDING invite_code row must exist for " + user);
        adapter.reset();

        // ----- Hop 3 — register via the code; probation begins -------------
        adapter.deliverDm(user, code.toString());
        assertEquals(bundleLoader.get(BundleKeys.REPLY_WELCOME_DM_FRESH), lastReply().text(),
                "hop 3: invite-consume must return the welcome reply");
        assertEquals("invited", registrationStateOf(user),
                "hop 3: consumer must transition to registration_state='invited'");
        assertNotNull(probationUntilOf(user),
                "hop 3: probation_until must be populated per D45 slow-start");
        adapter.reset();

        // ----- Hop 4 — a probation-blocked command is rejected -------------
        // /save is not in the slow-start allowed set (CommandPermissions),
        // so the step-5 probation gate rejects it before any dispatch.
        adapter.deliverDm(user, "/save " + PREFIX + "any-uid");
        assertTrue(lastReply().text().contains("slow-start probation"),
                "hop 4: a non-allowed command during probation must return the "
                        + "probation-blocked reply; got: " + lastReply().text());
        adapter.reset();

        // ----- Hop 5 — graduation via /vouch clears probation --------------
        adapter.deliverDm(BOOTSTRAP_ADMIN, "/vouch " + user);
        assertEquals(bundleLoader.get(BundleKeys.REPLY_VOUCH_SUCCESS), lastReply().text(),
                "hop 5: /vouch must return the success reply");
        assertNull(probationUntilOf(user),
                "hop 5: /vouch must clear probation_until to NULL");
        adapter.reset();

        // Seed one READY post and subscribe the graduated user's DM scope to
        // it, so the content command in hop 6 returns deterministic content.
        UUID userId = userIdOf(user);
        UUID sourceId = insertSource(PREFIX + "src");
        insertSubscription("dm", userId, sourceId);
        String postTitle = "JOURNEY HEADLINE " + PREFIX;
        insertReadyPost(PREFIX + "post-1", sourceId, postTitle, Instant.now().minusSeconds(60));

        // ----- Hop 6 — a DM content command returns the seeded post --------
        // /summary delivers via placeholder send + in-place finalize; the
        // rendered summary lands on the finalized body.
        testLlmProvider.setResponseText("Cluster prose for the journey summary.");
        adapter.deliverDm(user, "/summary");
        assertEquals(1, adapter.finalizedBodies().size(),
                "hop 6: /summary must deliver exactly one finalized summary body");
        assertTrue(adapter.finalizedBodies().get(0).contains(postTitle),
                "hop 6: the summary must surface the seeded READY post; got: "
                        + adapter.finalizedBodies().get(0));
        adapter.reset();

        // ----- Hop 7 — a chat-mode turn returns the stubbed reply ----------
        String chatMarker = "JOURNEY-CHAT-REPLY-" + PREFIX;
        testLlmProvider.reset();
        testLlmProvider.setResponseText(chatMarker);
        adapter.deliverDm(user, "tell me about the news");
        assertTrue(lastReply().text().contains(chatMarker),
                "hop 7: a post-graduation chat turn must return the stubbed agent reply; got: "
                        + lastReply().text());
        assertTrue(testLlmProvider.callCount() > 0,
                "hop 7: the chat agent must have invoked the LLM");
        adapter.reset();

        // ----- Hop 8 — a group @mention is held pending + admin notified ---
        adapter.createGroup(group);
        adapter.addMember(group, user);
        adapter.deliverGroupMention(group, user, "/help");
        assertEquals(bundleLoader.get(BundleKeys.GROUP_PENDING), lastReply().text(),
                "hop 8: first @mention must return the GROUP_PENDING fixed reply");
        assertEquals("pending", approvalStatusOf(group),
                "hop 8: groups row must be created with approval_status='pending'");
        UUID groupId = groupIdOf(group);
        adapter.reset();

        // ----- Hop 9 — /approve-group approves the group -------------------
        adapter.deliverDm(BOOTSTRAP_ADMIN, "/approve-group " + groupId);
        assertEquals("approved", approvalStatusOf(group),
                "hop 9: /approve-group must transition approval_status to 'approved'");
        boolean groupNotified = adapter.sentMessages().stream()
                .anyMatch(m -> bundleLoader.get(BundleKeys.GROUP_APPROVED_MESSAGE).equals(m.text()));
        assertTrue(groupNotified,
                "hop 9: the group must receive the one-time group_approved_message");
        adapter.reset();

        // ----- Hop 10 — a group command is processed normally --------------
        adapter.deliverGroupMention(group, user, "/help");
        assertTrue(lastReply().text().startsWith(bundleLoader.get(BundleKeys.HELP_HEADER_GROUP)),
                "hop 10: /help in the approved group must render the group help header; got: "
                        + lastReply().text());
        adapter.reset();

        // ----- Hop 11 — a digest is produced and delivered to the group ----
        // Subscribe the approved group's scope to the same source + post, then
        // run the worker for a slot whose window is still open (full prose).
        // Pin the group timezone so readGroupMetadata renders deterministically.
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "UPDATE groups SET timezone = 'UTC' WHERE id = ?", groupId);
        }
        insertGroupScopePreferences(groupId);
        insertSubscription("group", groupId, sourceId);
        testLlmProvider.reset();
        testLlmProvider.setResponseText("JOURNEY DIGEST PROSE for the approved group.");
        Instant windowStart = Instant.now().minusSeconds(3600);
        Instant windowEnd = Instant.now().plusSeconds(600);
        digestWorker.execute(new DigestSlot(groupId, "UTC", "morning", windowStart, windowEnd));

        List<OutboundMessage> digestToGroup = sentToGroup(group);
        assertEquals(1, digestToGroup.size(),
                "hop 11: exactly one digest message must be delivered to the group");
        DigestCacheRow digestRow = readDigestCache(groupId);
        assertFalse(digestRow.isDegraded(),
                "hop 11: the digest must be a full (non-degraded) prose generation");
        assertFalse(digestRow.content().isEmpty(),
                "hop 11: the digest content must be non-empty");
        assertEquals(digestRow.content(), digestToGroup.get(0).text(),
                "hop 11: the delivered digest body must equal the cached content");
        adapter.reset();

        // ----- Hop 12 — an asset command replies ---------------------------
        // zcash is already enabled in the registry: JourneyAssetConfigSeeder
        // filled asset_config at startup (standing in for the Collector), so
        // the registry loaded it through its real path. Only the live price
        // snapshot is seeded here.
        try (Connection conn = dataSource.getConnection()) {
            seedPriceSnapshot(conn, "zcash", "coingecko", ZCASH_PRICE,
                    Instant.now().minusSeconds(30));
        }
        adapter.deliverDm(user, "/zcash coingecko");
        String assetReply = lastReply().text();
        assertTrue(assetReply.contains("Zcash") && assetReply.contains(ZCASH_SOURCE_URL),
                "hop 12: /zcash must render the asset reply with display name + bare "
                        + "attribution URL; got: " + assetReply);
        adapter.reset();

        // ----- Hop 13 — /ban: banned user gets the fixed reply, no further -
        // /ban is confirm-gated (M1-051): prompt, then confirm.
        adapter.deliverDm(BOOTSTRAP_ADMIN, "/ban " + user + " --reason \"journey\"");
        adapter.deliverDm(BOOTSTRAP_ADMIN, "/ban confirm");
        assertTrue(isBanned(user), "hop 13: /ban must set is_banned=true on the target");
        adapter.reset();

        testLlmProvider.reset();
        int sentBefore = adapter.sentMessages().size();
        adapter.deliverDm(user, "/help");
        assertEquals(sentBefore + 1, adapter.sentMessages().size(),
                "hop 13: a banned user's message must produce exactly one outbound "
                        + "(the fixed ban reply — no command processing)");
        assertEquals(bundleLoader.get(BundleKeys.ERROR_BAN_FIXED), lastReply().text(),
                "hop 13: the banned user must receive the fixed ban reply");
        assertEquals(0, testLlmProvider.callCount(),
                "hop 13: the ban check must short-circuit before any LLM call");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID insertSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status) "
                             + "VALUES ('rss', ?, 'JourneySrc', 'news', '{}', 'active') "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertSubscription(String scopeKind, UUID scopeId, UUID sourceId)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    private void insertReadyPost(String uid, UUID sourceId, String title, Instant publishedAt)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                             + "fetched_at, ready_at, status, tags, "
                             + "stage1_done, stage2_done, tagger_done, embedding_done) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, "
                             + "TRUE, TRUE, TRUE, TRUE)")) {
            Timestamp pub = Timestamp.from(publishedAt);
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setString(5, "https://example.com/" + uid);
            ps.setTimestamp(6, pub);
            ps.setTimestamp(7, pub);
            ps.setTimestamp(8, pub);
            ps.setArray(9, conn.createArrayOf("TEXT", new String[] { PREFIX + "news" }));
            ps.executeUpdate();
        }
    }

    private void insertGroupScopePreferences(UUID groupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode, "
                             + "tag_subscription_version, source_subscription_version) "
                             + "VALUES ('group', ?, 'ALL', 1, 1) "
                             + "ON CONFLICT (scope_kind, scope_id) DO UPDATE "
                             + "  SET tag_mode = 'ALL'")) {
            ps.setObject(1, groupId);
            ps.executeUpdate();
        }
    }

    private void seedPriceSnapshot(Connection conn, String asset, String subVerb,
                                   BigDecimal price, Instant capturedAt) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO price_snapshot (asset, sub_verb, vs_currency, price, "
                        + "high_24h, low_24h, change_1h_pct, change_24h_pct, "
                        + "captured_at, source_url) "
                        + "VALUES (?, ?, 'usd', ?, 43.91, 41.07, 0.3, -2.4, ?, ?)")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            ps.setBigDecimal(3, price);
            ps.setTimestamp(4, Timestamp.from(capturedAt));
            ps.setString(5, ZCASH_SOURCE_URL);
            ps.executeUpdate();
        }
    }

    private List<OutboundMessage> sentToGroup(String upstreamGroupId) {
        return adapter.sentMessages().stream()
                .filter(m -> m.scope() instanceof ScopeRef.Group g
                        && g.adapterGroupId().equals(upstreamGroupId))
                .toList();
    }

    private DigestCacheRow readDigestCache(UUID groupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT content, is_degraded FROM summary_cache "
                             + "WHERE group_id = ? ORDER BY created_at DESC LIMIT 1")) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "a summary_cache row must exist for group " + groupId);
                return new DigestCacheRow(rs.getString("content"), rs.getBoolean("is_degraded"));
            }
        }
    }

    private record DigestCacheRow(String content, boolean isDegraded) {}

    private long countInvites(String expectedContactId, String status) throws Exception {
        return queryLong("SELECT COUNT(*) FROM invite_code WHERE adapter = ? "
                + "AND expected_contact_id = ? AND status = ?", ADAPTER, expectedContactId, status);
    }

    private String registrationStateOf(String contactId) throws Exception {
        return queryString("SELECT registration_state FROM users "
                + "WHERE adapter = ? AND contact_id = ?", contactId);
    }

    private String approvalStatusOf(String upstreamGroupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT approval_status FROM groups "
                             + "WHERE adapter = ? AND upstream_group_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "groups row must exist for " + upstreamGroupId);
                return rs.getString("approval_status");
            }
        }
    }

    private UUID userIdOf(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "users row must exist for " + contactId);
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID groupIdOf(String upstreamGroupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "groups row must exist for " + upstreamGroupId);
                return (UUID) rs.getObject("id");
            }
        }
    }

    private Timestamp probationUntilOf(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT probation_until FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "users row must exist for " + contactId);
                return rs.getTimestamp("probation_until");
            }
        }
    }

    private boolean isAdmin(String contactId) throws Exception {
        return queryBool("SELECT is_admin FROM users WHERE adapter = ? AND contact_id = ?",
                contactId);
    }

    private boolean isBanned(String contactId) throws Exception {
        return queryBool("SELECT is_banned FROM users WHERE adapter = ? AND contact_id = ?",
                contactId);
    }

    private boolean queryBool(String sql, String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private String queryString(String sql, String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "users row must exist for " + contactId);
                return rs.getString(1);
            }
        }
    }

    private long queryLong(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private OutboundMessage lastReply() {
        var sent = adapter.sentMessages();
        assertFalse(sent.isEmpty(), "Expected at least one reply");
        return sent.getLast();
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static UUID extractUuid(String body) {
        Matcher m = UUID_IN_REPLY.matcher(body);
        return m.find() ? UUID.fromString(m.group()) : null;
    }

    private static void assertNull(Object value, String message) {
        assertTrue(value == null, message);
    }

    /**
     * In-memory adapter only; LOW-trust opt-in; a journey-owned bootstrap
     * admin so hop 1 is self-contained; the {@code seed-assets} flag that
     * activates {@link JourneyAssetConfigSeeder} (the Collector stand-in for
     * hop 12); the asset metadata fixture (display name + supported-vs) the
     * AssetRegistry reads at boot; and the test profile label / cluster cap
     * the /summary path expects.
     */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.adapters.inmemory.admin", BOOTSTRAP_ADMIN,
                    "infochat.journey.seed-assets", "true",
                    "infochat.bootstrap.assets-file",
                    "src/test/resources/bootstrap-assets-it.json",
                    "infochat.summary.cluster-cap", "3",
                    "infochat.profile.label", "test");
        }
    }
}
