package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.messaging.InboundRouter;
import app.zcat.infochat.provider.source.UrlProbe;
import app.zcat.infochat.provider.source.UrlProbe.ProbeResult;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-tier tests for {@link AddSourceCommandHandler}. Boots
 * {@link io.quarkus.test.junit.QuarkusTest @QuarkusTest} with the
 * {@link MvpProfile} so the {@code inmemory} adapter activates and
 * the router → handler chain is wired against the production CDI
 * graph. The {@link MockUrlProbe} {@link Alternative} replaces
 * {@link UrlProbe} so handler-tier tests can pin the probe outcome
 * per URL without a real HttpServer fixture (the URL-probe path is
 * covered by {@code UrlProbeTest}).
 *
 * <p>Asserted invariants (one {@code @Test} per acceptance bullet):
 * <ul>
 *   <li>CDI discovery: the handler is reachable through the
 *       {@code InboundRouter.onMessage(/add-source ...)} path exactly
 *       once — confirms the router's
 *       {@code Instance<CommandHandler>} lookup binds the handler by
 *       its {@code "add-source"} name.</li>
 *   <li>Permission gate: DM non-banned proceeds, DM banned rejects,
 *       group non-admin rejects, group admin proceeds.</li>
 *   <li>Ambiguous probe outcome: {@code /about} URL with
 *       {@code text/html} probe response surfaces the AMBIGUOUS
 *       friendly error.</li>
 *   <li>URL-visibility disclosure: present on Branch A reply,
 *       absent on Branch B / Branch C replies.</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(AddSourceCommandHandlerTest.MvpProfile.class)
class AddSourceCommandHandlerTest {

    @Inject InMemoryAdapter adapter;

    @Inject InboundRouter inboundRouter;

    @Inject AddSourceCommandHandler handler;

    @Inject MockUrlProbe mockProbe;

    @Inject DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        mockProbe.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Insert (or re-affirm) a permanent guardian admin BEFORE
            // deleting test users; the last-admin-protection trigger
            // (V5 trg_last_admin_protection_delete) would otherwise
            // refuse to delete the final is_admin=TRUE row if our
            // test set is the only admin population in this DB.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES ('inmemory', 'm1-036h-guardian-permanent', TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE");
            exec(conn,
                    "DELETE FROM audit_log "
                            + "WHERE target_kind = 'source' AND target_id IN ("
                            + "  SELECT id::TEXT FROM source "
                            + "   WHERE identifier LIKE 'https://example.com/m1-036h-%')");
            exec(conn,
                    "DELETE FROM source_subscription "
                            + "WHERE source_id IN ("
                            + "  SELECT id FROM source "
                            + "   WHERE identifier LIKE 'https://example.com/m1-036h-%')");
            exec(conn,
                    "DELETE FROM source WHERE identifier LIKE 'https://example.com/m1-036h-%'");
            exec(conn,
                    "DELETE FROM tag WHERE name LIKE 'm1-036h-%'");
            exec(conn,
                    "DELETE FROM users "
                            + "WHERE contact_id LIKE 'm1-036h-%' "
                            + "  AND contact_id <> 'm1-036h-guardian-permanent'");
        }
    }

    @Test
    void inboundRouterDispatchesAddSourceToHandlerExactlyOnce() {
        mockProbe.setOk("https://example.com/m1-036h-disp.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));

        adapter.deliverDm("m1-036h-disp",
                "/add-source https://example.com/m1-036h-disp.xml --tags m1-036h-news");

        assertEquals(1, adapter.sentMessages().size(),
                "exactly one outbound reply must be produced via the router → handler chain");
        assertEquals(1, mockProbe.callCount(),
                "the handler must call UrlProbe.probe exactly once on the dispatch path");
    }

    @Test
    void dmNonBannedNonAdminProceedsAndProducesFreshInsertReply() {
        mockProbe.setOk("https://example.com/m1-036h-fresh.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));

        adapter.deliverDm("m1-036h-fresh-user",
                "/add-source https://example.com/m1-036h-fresh.xml --tags m1-036h-news");

        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size());
        String body = sent.get(0).text();
        // Branch A reply MUST contain the URL-visibility disclosure literal.
        assertTrue(body.contains("visible to bot admins"),
                "Branch A reply MUST include the URL-visibility disclosure literal "
                        + "(per spec §Source management) — got: " + body);
    }

    @Test
    void dmBannedUserRejectsBeforeProbe() throws Exception {
        // Pre-create the user as banned so AutoRegisterService's
        // upstream UPSERT finds an existing row.
        insertBannedUser("m1-036h-banned");
        // No mock probe setup needed — the ban check fires BEFORE probe.

        adapter.deliverDm("m1-036h-banned",
                "/add-source https://example.com/m1-036h-banned.xml --tags m1-036h-news");

        assertEquals(1, adapter.sentMessages().size());
        String body = adapter.sentMessages().get(0).text();
        // The bundle value for error.add_source.banned should be present.
        assertTrue(body.contains("not permitted"),
                "banned user must see the banned-friendly-error literal — got: " + body);
        assertEquals(0, mockProbe.callCount(),
                "ban check must short-circuit BEFORE UrlProbe is invoked");
    }

    @Test
    void groupScopeNonAdminCallerIsRejected() {
        UUID groupId = UUID.randomUUID();
        OutboundMessage reply = handler.handle(
                new ScopeRef.Group(groupId.toString()),
                "/add-source https://example.com/m1-036h-group.xml --tags m1-036h-news");

        assertTrue(reply.text().contains("Only group admins"),
                "non-admin in group scope must see the group-admin-only friendly error — got: "
                        + reply.text());
    }

    // The "GROUP scope, group admin → handler proceeds" branch from
    // the acceptance's item 10 is NOT covered here: the frozen
    // CommandHandler SPI does not carry the inbound actor's identity
    // in group scope (ScopeRef.Group holds only the adapter-side
    // group id; the actor's contact id is not on the SPI), so the
    // handler cannot consult group_membership for the caller. The
    // ticket's out_of_scope explicitly accepts this gap: "the
    // rejection branch falls through the auth check WITHOUT
    // requiring group-membership infrastructure; no group-membership
    // lookup is needed in MVP." T2-F wires the actor seam + the
    // group-admin proceed path; the corresponding acceptance test
    // lands then.

    @Test
    void ambiguousUrlWithHtmlContentTypeSurfacesAmbiguousFriendlyError() {
        // /about URL has no RSS path-hint; the resolver returns
        // AMBIGUOUS directly. The handler short-circuits to the
        // ambiguous_url bundle key BEFORE the probe is invoked (the
        // probe runs after kind resolution).
        adapter.deliverDm("m1-036h-amb",
                "/add-source https://example.com/m1-036h-about --tags m1-036h-news");

        assertEquals(1, adapter.sentMessages().size());
        String body = adapter.sentMessages().get(0).text();
        assertTrue(body.contains("Couldn't infer the source type"),
                "ambiguous-URL reply must surface the ambiguous_url friendly error literal — got: "
                        + body);
    }

    @Test
    void rssPathUrlContradictedByHtmlContentTypeSurfacesAmbiguous() {
        // Path ends in /feed → resolver chooses RSS via the path
        // hint. The probe returns text/html — the
        // confirm-or-contradict check fires and the handler returns
        // the ambiguous_url friendly error.
        mockProbe.setOk("https://example.com/m1-036h-news/feed",
                ProbeResult.success(200, Optional.of("text/html")));

        adapter.deliverDm("m1-036h-contradict-user",
                "/add-source https://example.com/m1-036h-news/feed --tags m1-036h-news");

        String body = adapter.sentMessages().get(0).text();
        assertTrue(body.contains("Couldn't infer the source type"),
                "RSS-hinted URL contradicted by text/html Content-Type must surface "
                        + "the ambiguous_url friendly error — got: " + body);
    }

    @Test
    void branchBSubscribedExistingReplyOmitsUrlVisibilityDisclosure() throws Exception {
        // Seed an existing source via another contact, then call as a
        // non-admin from a different contact id.
        UUID seedUser = insertUser("m1-036h-seed-b", true);
        insertSourceRow(seedUser, "https://example.com/m1-036h-shared.xml",
                "Shared", "news", List.of("m1-036h-tag"));

        mockProbe.setOk("https://example.com/m1-036h-shared.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));

        adapter.deliverDm("m1-036h-non-admin-b",
                "/add-source https://example.com/m1-036h-shared.xml --tags m1-036h-other");

        String body = adapter.sentMessages().get(0).text();
        assertFalse(body.contains("visible to bot admins"),
                "Branch B (subscribed-existing) reply MUST NOT include the URL-visibility "
                        + "disclosure — got: " + body);
        assertTrue(body.contains("Subscribed"),
                "Branch B reply must include the subscribed-existing bundle literal — got: "
                        + body);
    }

    @Test
    void branchCBotAdminTagReplacementReplyOmitsUrlVisibilityDisclosure() throws Exception {
        // Seed an existing source via a different contact, then call as
        // a bot-admin caller with new --tags. The handler routes to the
        // ADMIN_TAGS_REPLACED arm of buildReply, which emits the
        // admin_tags_replaced bundle value WITHOUT the URL-visibility
        // disclosure (gated to FRESH_INSERT only).
        UUID seedUser = insertUser("m1-036h-seed-c", true);
        insertSourceRow(seedUser, "https://example.com/m1-036h-admin.xml",
                "AdminShared", "news", List.of("m1-036h-old"));
        insertUser("m1-036h-bot-admin-c", true);

        mockProbe.setOk("https://example.com/m1-036h-admin.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));

        adapter.deliverDm("m1-036h-bot-admin-c",
                "/add-source https://example.com/m1-036h-admin.xml --tags m1-036h-new");

        String body = adapter.sentMessages().get(0).text();
        assertFalse(body.contains("visible to bot admins"),
                "Branch C (admin-tag-replacement) reply MUST NOT include the "
                        + "URL-visibility disclosure — got: " + body);
        assertTrue(body.contains("bootstrap tags replaced"),
                "Branch C reply must include the admin-tags-replaced bundle literal "
                        + "— got: " + body);
    }

    // --- helpers ---------------------------------------------------------

    private UUID insertUser(String contactId, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES ('inmemory', ?, ?, 'invited') RETURNING id")) {
            ps.setString(1, contactId);
            ps.setBoolean(2, isAdmin);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertBannedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state) "
                             + "VALUES ('inmemory', ?, FALSE, TRUE, 'invited')")) {
            ps.setString(1, contactId);
            ps.executeUpdate();
        }
    }

    private void insertSourceRow(UUID inserter, String url, String name,
                                  String category, List<String> tags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status, added_by) "
                             + "VALUES ('rss', ?, ?, ?, ?, 'active', ?)")) {
            ps.setString(1, url);
            ps.setString(2, name);
            ps.setString(3, category);
            ps.setArray(4, conn.createArrayOf("TEXT", tags.toArray(new String[0])));
            ps.setObject(5, inserter);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /**
     * CDI {@link Alternative} that replaces the real {@link UrlProbe}
     * for handler-tier tests. The test populates per-URL canned
     * results via {@link #setOk(String, ProbeResult)}; an unmapped
     * URL falls through to a SUCCESS with no content-type so tests
     * that exercise unrelated paths don't have to repeat the
     * boilerplate.
     *
     * <p>No {@link Priority} annotation — activation is scoped via
     * {@link MvpProfile#getEnabledAlternatives()} so this mock is
     * active only inside this test's profile. A global
     * {@code @Priority(1)} would conflict with
     * {@code AddSourceIT.LoopbackProbe}'s alternative.</p>
     */
    @Alternative
    @ApplicationScoped
    public static class MockUrlProbe extends UrlProbe {

        private final Map<String, ProbeResult> canned = new ConcurrentHashMap<>();

        private int callCount;

        @Override
        public ProbeResult probe(URI url) {
            callCount++;
            return canned.getOrDefault(
                    url.toString(),
                    ProbeResult.success(200, Optional.empty()));
        }

        void setOk(String url, ProbeResult result) {
            canned.put(url, result);
        }

        int callCount() {
            return callCount;
        }

        void reset() {
            canned.clear();
            callCount = 0;
        }
    }

    /**
     * Same shape as {@code AdapterRouterIT.MvpProfile}: inmemory +
     * low-trust opt-in. Plus
     * {@link QuarkusTestProfile#getEnabledAlternatives()} so the
     * {@link MockUrlProbe} above is the only active alternative for
     * this profile — Quarkus boot-time CDI would otherwise see BOTH
     * this mock and {@code AddSourceIT.LoopbackProbe} as competing
     * {@code @Priority(1)} alternatives and refuse to start with an
     * AmbiguousResolutionException.
     */
    public static final class MvpProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }

        @Override
        public java.util.Set<Class<?>> getEnabledAlternatives() {
            return java.util.Set.of(MockUrlProbe.class);
        }
    }
}
