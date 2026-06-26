package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
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
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape B (Thin-SQL) tests for {@link SourceEnableCommandHandler}. The
 * handler consumes the {@link UrlProbe} collaborator; the test
 * injects {@link StubUrlProbe} via a {@link QuarkusTestProfile}
 * {@code @Alternative} so probe outcomes are deterministic without
 * outbound network I/O.
 *
 * <p>Test isolation: every fixture row carries the
 * {@code m1-053-enable-} prefix; {@link #cleanup()} deletes only rows
 * matching that prefix.</p>
 */
@QuarkusTest
@TestProfile(SourceEnableCommandHandlerTest.StubProbeProfile.class)
class SourceEnableCommandHandlerTest {

    private static final String PREFIX = "m1-053-enable-";
    private static final String ADAPTER = "inmemory";

    @Inject SourceEnableCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject ConfirmStateService confirmStateService;
    @Inject UrlProbe urlProbe;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        // Default the stub probe to SUCCESS; per-test overrides via setNext().
        ((StubUrlProbe) urlProbe).setNext(ProbeResult.success(200, Optional.of("application/rss+xml")));
        ((StubUrlProbe) urlProbe).resetCallCount();
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin so the V5 last-admin-protection
            // trigger does not refuse the per-test DELETE on admin
            // rows. This @TestProfile-scoped test class boots its own
            // Quarkus instance (StubProbeProfile) and therefore gets
            // its own DevServices Postgres container — no prior-test
            // guardians carry over.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-053-enable-permanent");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_kind = 'source' AND target_id IN ("
                                + "  SELECT id::TEXT FROM source WHERE identifier LIKE ?)",
                        "https://example.com/" + PREFIX + "%");
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN ("
                                + "  SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            exec(conn,
                    "DELETE FROM source_subscription WHERE source_id IN ("
                            + "  SELECT id FROM source WHERE identifier LIKE ?)",
                    "https://example.com/" + PREFIX + "%");
            exec(conn, "DELETE FROM source WHERE identifier LIKE ?",
                    "https://example.com/" + PREFIX + "%");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
        }
    }

    @Test
    void sourceEnableNonAdminReturnsAdminOnlyError() throws Exception {
        String actor = PREFIX + "nonAdmin-actor";
        seedUser(actor, false);
        UUID sourceId = seedSource("nonAdmin", "rss", "failed", false);
        long auditBefore = countAuditForTarget(sourceId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable " + sourceId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /source-enable must surface error.admin_only");
        assertEquals("failed", readStatus(sourceId),
                "non-admin /source-enable must not change source.status");
        assertEquals(auditBefore, countAuditForTarget(sourceId),
                "non-admin /source-enable must not write any audit row");
        assertEquals(0, ((StubUrlProbe) urlProbe).callCount(),
                "non-admin /source-enable must short-circuit BEFORE the probe");
    }

    @Test
    void sourceEnableAgainstNostrKindReturnsKindNotSupportedError() throws Exception {
        String actor = PREFIX + "nostr-actor";
        seedUser(actor, true);
        UUID sourceId = seedSource("nostr", "nostr", "failed", false);
        long auditBefore = countAuditForTarget(sourceId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable " + sourceId);

        assertEquals(
                bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_KIND_NOT_SUPPORTED_IN_V1),
                reply.text(),
                "/source-enable against kind=nostr must surface kind_not_supported_in_v1");
        assertEquals("failed", readStatus(sourceId),
                "/source-enable against kind=nostr must not change source.status");
        assertEquals(auditBefore, countAuditForTarget(sourceId),
                "/source-enable against kind=nostr must not write any audit row");
        assertEquals(0, ((StubUrlProbe) urlProbe).callCount(),
                "/source-enable against kind=nostr must short-circuit BEFORE the probe");
    }

    @Test
    void reEnableResetsFailureCounter() throws Exception {
        // M1-094 (D42): /source-enable on a failed source must reset
        // consecutive_failures to 0 so the re-enabled row does not
        // immediately re-trip the FetchScheduler's failure ladder on
        // the next tick. Behaviour is pre-existing in the handler's
        // UPDATE statements (UPDATE_SOURCE_REACTIVATE_SQL +
        // UPDATE_SOURCE_REVIVE_SQL both set consecutive_failures = 0);
        // this test pins the contract.
        String actor = PREFIX + "reEnableResetsCounter-actor";
        seedUser(actor, true);
        UUID sourceId = seedSource("reEnableResetsCounter", "rss", "failed", false);

        // Sanity-check: the fixture seeder pre-sets
        // consecutive_failures=3 so the reset is observable. If the
        // seeder ever stops pre-setting this, the assertion below
        // catches the silent fixture drift before it masks a real
        // counter-reset regression.
        assertEquals(3, readConsecutiveFailures(sourceId),
                "seed precondition: fixture must start with non-zero consecutive_failures "
                        + "so the reset on /source-enable is observable");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable " + sourceId);

        assertTrue(reply.text().contains(PREFIX + "reEnableResetsCounter-name"),
                "/source-enable success must name the source — got: " + reply.text());
        assertEquals("active", readStatus(sourceId),
                "/source-enable from failed must transition status to 'active'");
        assertEquals(0, readConsecutiveFailures(sourceId),
                "/source-enable from failed must reset consecutive_failures to 0 "
                        + "(pre-existing UPDATE_SOURCE_REACTIVATE_SQL behaviour; this test "
                        + "documents the contract M1-094's failure ladder relies on)");
    }

    @Test
    void sourceEnableFromFailedRunsProbeNoConfirm() throws Exception {
        String actor = PREFIX + "failed-actor";
        seedUser(actor, true);
        UUID sourceId = seedSource("failed", "rss", "failed", false);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable " + sourceId);

        assertTrue(reply.text().contains(PREFIX + "failed-name"),
                "/source-enable success must name the source — got: " + reply.text());
        assertFalse(reply.text().contains("No subscriptions were restored"),
                "/source-enable from failed (non-soft-deleted) must NOT include the "
                        + "no-subscriptions-restored disclosure — got: " + reply.text());
        assertEquals("active", readStatus(sourceId),
                "/source-enable from failed must transition status to 'active'");
        assertEquals(1, ((StubUrlProbe) urlProbe).callCount(),
                "/source-enable from failed must invoke the probe exactly once");
        assertEquals(1L, countAuditByActionForTarget("SOURCE_ENABLE", sourceId),
                "/source-enable from failed must write one SOURCE_ENABLE audit row");
        assertEquals(0L, countAuditByActionForTarget("SOURCE_ENABLE_INTENT", sourceId),
                "/source-enable from failed must NOT write SOURCE_ENABLE_INTENT (no confirm)");
    }

    @Test
    void sourceEnableFromDisabledRunsProbeNoConfirm() throws Exception {
        String actor = PREFIX + "disabled-actor";
        seedUser(actor, true);
        UUID sourceId = seedSource("disabled", "rss", "disabled", false);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable " + sourceId);

        assertTrue(reply.text().contains(PREFIX + "disabled-name"),
                "/source-enable from disabled must reply with success naming the source — got: "
                        + reply.text());
        assertEquals("active", readStatus(sourceId),
                "/source-enable from disabled must transition status to 'active'");
        assertEquals(1, ((StubUrlProbe) urlProbe).callCount(),
                "/source-enable from disabled must invoke the probe exactly once");
    }

    @Test
    void sourceEnableFromFailedReEnablesNonRssHttpKinds() throws Exception {
        // M1-436: every HTTP-shaped kind (not just rss) must re-enable
        // from 'failed' after a passing probe, mirroring the rss path
        // in sourceEnableFromFailedRunsProbeNoConfirm. Exercises bluesky
        // (named in acceptance) plus reddit (a second non-rss kind).
        for (String kind : new String[] {"bluesky", "reddit"}) {
            String actor = PREFIX + kind + "-actor";
            seedUser(actor, true);
            UUID sourceId = seedSource(kind, kind, "failed", false);
            ((StubUrlProbe) urlProbe).resetCallCount();

            OutboundMessage reply = handler.handle(
                    new ScopeRef.Dm(actor),
                    "/source-enable " + sourceId);

            assertTrue(reply.text().contains(PREFIX + kind + "-name"),
                    "/source-enable success must name the kind=" + kind
                            + " source — got: " + reply.text());
            assertEquals("active", readStatus(sourceId),
                    "/source-enable from failed must transition kind=" + kind
                            + " to 'active'");
            assertEquals(1, ((StubUrlProbe) urlProbe).callCount(),
                    "/source-enable from failed must invoke the probe exactly once for kind="
                            + kind);
            assertEquals(1L, countAuditByActionForTarget("SOURCE_ENABLE", sourceId),
                    "/source-enable from failed must write one SOURCE_ENABLE audit row for kind="
                            + kind);
        }
    }

    @Test
    void sourceEnableFromFailedWithFailingProbeLeavesRowFailed() throws Exception {
        String actor = PREFIX + "probeFail-actor";
        seedUser(actor, true);
        UUID sourceId = seedSource("probeFail", "rss", "failed", false);
        long auditBefore = countAuditForTarget(sourceId);
        ((StubUrlProbe) urlProbe).setNext(
                ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_UNREACHABLE, 500));

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable " + sourceId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_PROBE_FAILED), reply.text(),
                "failing probe must surface probe_failed");
        assertEquals("failed", readStatus(sourceId),
                "failing probe must leave the row in its prior 'failed' state");
        assertEquals(auditBefore, countAuditForTarget(sourceId),
                "failing probe must not write any audit row");
    }

    @Test
    void sourceEnableFromSoftDeletedFirstCallReturnsPromptAndWritesIntentAuditRowOnly()
            throws Exception {
        String actor = PREFIX + "softFirst-actor";
        UUID actorId = seedUser(actor, true);
        UUID sourceId = seedSource("softFirst", "rss", "active", true);
        long auditBefore = countAuditForTarget(sourceId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable " + sourceId);

        assertTrue(reply.text().contains(PREFIX + "softFirst-name"),
                "first /source-enable against soft-deleted must reply with the revival prompt "
                        + "naming the source — got: " + reply.text());
        assertTrue(reply.text().contains("No subscriptions will be restored"),
                "revival prompt must include the no-subscriptions-restored notice — got: "
                        + reply.text());
        assertTrue(reply.text().contains("/source-enable confirm"),
                "revival prompt must instruct the admin to send /source-enable confirm — got: "
                        + reply.text());

        // No probe runs on the first-call leg (the probe is gated to
        // the confirm leg for the revival path; running the probe on
        // first call would waste an HTTP roundtrip the admin may
        // abandon).
        assertEquals(0, ((StubUrlProbe) urlProbe).callCount(),
                "first-call revival must NOT invoke the probe (probe runs on confirm only)");

        assertEquals(auditBefore + 1, countAuditForTarget(sourceId),
                "first-call revival must write exactly ONE audit row (the intent)");
        assertEquals(1L, countAuditByActionForTarget("SOURCE_ENABLE_INTENT", sourceId),
                "the single audit row must be SOURCE_ENABLE_INTENT");
        assertEquals(0L, countAuditByActionForTarget("SOURCE_ENABLE", sourceId),
                "first-call revival must NOT write SOURCE_ENABLE completion row");
        assertTrue(readDeletedAtIsNotNull(sourceId),
                "first-call revival must NOT clear deleted_at");

        Optional<ConfirmStateService.PendingConfirm> peeked =
                confirmStateService.peek(actorId, new ScopeRef.Dm(actor));
        assertTrue(peeked.isPresent(),
                "ConfirmStateService.peek must show a pending source-enable entry");
        assertEquals("source-enable", peeked.get().commandName());
    }

    @Test
    void sourceEnableSoftDeletedConfirmWithinWindowRunsProbeAndRevives() throws Exception {
        String actor = PREFIX + "softConfirm-actor";
        UUID actorId = seedUser(actor, true);
        UUID sourceId = seedSource("softConfirm", "rss", "active", true);

        // Prompt then confirm.
        handler.handle(new ScopeRef.Dm(actor), "/source-enable " + sourceId);
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable confirm");

        assertTrue(reply.text().contains(PREFIX + "softConfirm-name"),
                "revival success reply must name the source — got: " + reply.text());
        assertTrue(reply.text().contains("No subscriptions were restored"),
                "revival success reply must include the no-subscriptions-restored "
                        + "disclosure — got: " + reply.text());

        assertFalse(readDeletedAtIsNotNull(sourceId),
                "confirmed revival must clear deleted_at");
        assertEquals("active", readStatus(sourceId),
                "confirmed revival must set status='active'");

        assertEquals(1L, countAuditByActionForTarget("SOURCE_ENABLE_INTENT", sourceId),
                "SOURCE_ENABLE_INTENT row from first call must persist");
        assertEquals(1L, countAuditByActionForTarget("SOURCE_ENABLE", sourceId),
                "SOURCE_ENABLE completion row from confirm must exist");
        assertEquals(0L, countSubscriptions(sourceId),
                "confirmed revival must NOT recreate any subscription rows");

        Optional<ConfirmStateService.PendingConfirm> peeked =
                confirmStateService.peek(actorId, new ScopeRef.Dm(actor));
        assertFalse(peeked.isPresent(),
                "ConfirmStateService.peek must be empty after the confirm consumes pending");
    }

    @Test
    void sourceEnableRevivesSoftDeletedHttpNonRssKind() throws Exception {
        // M1-457: the soft-delete revive path must accept every
        // HTTP-shaped kind, not just rss — matching the main kind gate
        // (STREAM_KINDS) and the failed/disabled re-enable path. Before
        // the fix, executeRevive's !"rss" check rejected a soft-deleted
        // reddit/youtube/odysee/nitter/bluesky source as
        // kind_not_supported_in_v1. Exercises reddit (a non-rss HTTP
        // kind) end to end: prompt -> confirm -> full state transition +
        // SOURCE_ENABLE audit row + no-subscriptions-restored disclosure.
        String actor = PREFIX + "softRevReddit-actor";
        UUID actorId = seedUser(actor, true);
        UUID sourceId = seedSource("softRevReddit", "reddit", "active", true);

        // Prompt then confirm.
        handler.handle(new ScopeRef.Dm(actor), "/source-enable " + sourceId);
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable confirm");

        assertTrue(reply.text().contains(PREFIX + "softRevReddit-name"),
                "revival success reply must name the kind=reddit source — got: " + reply.text());
        assertTrue(reply.text().contains("No subscriptions were restored"),
                "revival success reply must include the no-subscriptions-restored "
                        + "disclosure — got: " + reply.text());

        assertFalse(readDeletedAtIsNotNull(sourceId),
                "confirmed revival of a reddit source must clear deleted_at");
        assertEquals("active", readStatus(sourceId),
                "confirmed revival of a reddit source must set status='active'");
        assertEquals(0, readConsecutiveFailures(sourceId),
                "confirmed revival must reset consecutive_failures to 0");

        assertEquals(1L, countAuditByActionForTarget("SOURCE_ENABLE", sourceId),
                "confirmed revival of a reddit source must write one SOURCE_ENABLE audit row");
        assertEquals(0L, countSubscriptions(sourceId),
                "confirmed revival must NOT recreate any subscription rows");

        Optional<ConfirmStateService.PendingConfirm> peeked =
                confirmStateService.peek(actorId, new ScopeRef.Dm(actor));
        assertFalse(peeked.isPresent(),
                "ConfirmStateService.peek must be empty after the confirm consumes pending");
    }

    @Test
    void sourceEnableSoftDeletedNostrStillReturnsKindNotSupported() throws Exception {
        // M1-457: a soft-deleted stream-shaped kind (nostr) is still
        // rejected at the main kind gate before any probe or state
        // change — stream revive remains a documented v1 deferral.
        String actor = PREFIX + "softNostr-actor";
        seedUser(actor, true);
        UUID sourceId = seedSource("softNostr", "nostr", "active", true);
        long auditBefore = countAuditForTarget(sourceId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable " + sourceId);

        assertEquals(
                bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_KIND_NOT_SUPPORTED_IN_V1),
                reply.text(),
                "/source-enable against a soft-deleted kind=nostr source must surface "
                        + "kind_not_supported_in_v1");
        assertTrue(readDeletedAtIsNotNull(sourceId),
                "rejected nostr revive must leave the row soft-deleted");
        assertEquals(auditBefore, countAuditForTarget(sourceId),
                "rejected nostr revive must not write any audit row");
        assertEquals(0, ((StubUrlProbe) urlProbe).callCount(),
                "rejected nostr revive must short-circuit BEFORE the probe");
    }

    @Test
    void sourceEnableSoftDeletedConfirmWithFailingProbeLeavesRowSoftDeleted() throws Exception {
        String actor = PREFIX + "softProbeFail-actor";
        seedUser(actor, true);
        UUID sourceId = seedSource("softProbeFail", "rss", "active", true);

        // Prompt establishes the pending; intent row written.
        handler.handle(new ScopeRef.Dm(actor), "/source-enable " + sourceId);
        // Probe fails on the confirm leg.
        ((StubUrlProbe) urlProbe).setNext(
                ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_TIMEOUT, 0));

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable confirm");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_PROBE_FAILED), reply.text(),
                "confirm-leg probe failure must surface probe_failed");
        assertTrue(readDeletedAtIsNotNull(sourceId),
                "confirm-leg probe failure must leave the row soft-deleted");
        assertEquals(1L, countAuditByActionForTarget("SOURCE_ENABLE_INTENT", sourceId),
                "the intent row from the first call must persist");
        assertEquals(0L, countAuditByActionForTarget("SOURCE_ENABLE", sourceId),
                "confirm-leg probe failure must NOT write a SOURCE_ENABLE completion row");
    }

    @Test
    void sourceEnableConfirmWithoutPendingReturnsNoPending() throws Exception {
        String actor = PREFIX + "noPending-actor";
        seedUser(actor, true);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable confirm");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING), reply.text(),
                "/source-enable confirm without pending must surface error.confirm.no_pending");
    }

    @Test
    void sourceEnableFromActiveReturnsAlreadyActive() throws Exception {
        String actor = PREFIX + "active-actor";
        seedUser(actor, true);
        UUID sourceId = seedSource("active", "rss", "active", false);
        long auditBefore = countAuditForTarget(sourceId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-enable " + sourceId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_ALREADY_ACTIVE), reply.text(),
                "/source-enable against an already-active source must surface already_active");
        assertEquals("active", readStatus(sourceId),
                "/source-enable against an already-active source must not change status");
        assertEquals(auditBefore, countAuditForTarget(sourceId),
                "/source-enable against an already-active source must not write any audit row");
        assertEquals(0, ((StubUrlProbe) urlProbe).callCount(),
                "/source-enable against an already-active source must NOT invoke the probe");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String contactId, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES (?, ?, ?, 'vouched') RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedSource(String slug, String kind, String status, boolean softDeleted)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "  bootstrap_tags, status, deleted_at, consecutive_failures) "
                             + "VALUES (?, ?, ?, 'news', '{}', ?, ?, 3) RETURNING id")) {
            ps.setString(1, kind);
            ps.setString(2, "https://example.com/" + PREFIX + slug);
            ps.setString(3, PREFIX + slug + "-name");
            ps.setString(4, status);
            if (softDeleted) {
                ps.setObject(5, OffsetDateTime.now());
            } else {
                ps.setObject(5, null);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private String readStatus(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString("status");
            }
        }
    }

    private int readConsecutiveFailures(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT consecutive_failures FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private boolean readDeletedAtIsNotNull(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT deleted_at FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp("deleted_at") != null;
            }
        }
    }

    private long countSubscriptions(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM source_subscription WHERE source_id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditForTarget(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE target_kind = 'source' "
                             + "AND target_id = ?")) {
            ps.setString(1, sourceId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditByActionForTarget(String action, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? "
                             + "AND target_kind = 'source' AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, sourceId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * CDI {@link Alternative} that replaces the production
     * {@link UrlProbe} with a stub returning a per-test settable
     * {@link ProbeResult}. Scoped to this test class via
     * {@link StubProbeProfile}; other tests use the production probe.
     */
    @Alternative
    @ApplicationScoped
    public static class StubUrlProbe extends UrlProbe {

        private final AtomicReference<ProbeResult> next =
                new AtomicReference<>(ProbeResult.success(200, Optional.empty()));
        private final java.util.concurrent.atomic.AtomicInteger callCount =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public ProbeResult probe(URI url) {
            callCount.incrementAndGet();
            return next.get();
        }

        public void setNext(ProbeResult result) {
            next.set(result);
        }

        public int callCount() {
            return callCount.get();
        }

        public void resetCallCount() {
            callCount.set(0);
        }
    }

    public static class StubProbeProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(StubUrlProbe.class);
        }
    }
}
