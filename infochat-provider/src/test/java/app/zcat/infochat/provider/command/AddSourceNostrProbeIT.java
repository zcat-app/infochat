package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.source.FakeRelayServer;
import app.zcat.infochat.provider.source.UrlProbe;
import app.zcat.infochat.provider.source.UrlProbe.ProbeResult;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import app.zcat.infochat.ssrf.LoopbackPermittingBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.ServerSocket;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for the StreamSource-shaped
 * {@code /add-source} probe per {@code docs/spec/commands.md} §Source
 * management: "For StreamSource-shaped kinds (Nostr in v1) the
 * equivalent check is a single connection attempt against the first
 * relay in the supplied {@code config}; failure produces the same
 * friendly error." Mirrors the {@link AddSourceIT} fixture shape with
 * a {@link FakeRelayServer} WebSocket endpoint in place of the
 * HttpServer fixture.
 *
 * <p>The relay URLs use {@code ws://} against the local fixture (the
 * fixture carries no TLS; the SSRF scheme allowlist treats {@code ws}
 * and {@code wss} identically) except the blocked-range case, which is
 * rejected before any dial and so can exercise {@code wss://}
 * verbatim.</p>
 *
 * <p>Test isolation: contact id and URLs carry the
 * {@code m1-203-} prefix; {@link #cleanup()} deletes only those before
 * each run so the IT does not race other tests or the bootstrap-admin
 * row.</p>
 */
@QuarkusTest
@TestProfile(AddSourceNostrProbeIT.NostrProfile.class)
class AddSourceNostrProbeIT {

    private static FakeRelayServer relay;

    @Inject InMemoryAdapter adapter;

    @Inject @SeedDataSource DataSource dataSource;

    @BeforeAll
    static void startRelay() {
        relay = new FakeRelayServer();
    }

    @AfterAll
    static void stopRelay() {
        if (relay != null) {
            relay.close();
        }
    }

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "DELETE FROM audit_log "
                            + "WHERE target_kind = 'source' AND target_id IN ("
                            + "  SELECT id::TEXT FROM source "
                            + "   WHERE identifier LIKE '%m1-203-%')");
            exec(conn,
                    "DELETE FROM source_subscription "
                            + "WHERE source_id IN ("
                            + "  SELECT id FROM source WHERE identifier LIKE '%m1-203-%')");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '%m1-203-%'");
            exec(conn, "DELETE FROM users WHERE contact_id = 'm1-203-user-1'");
            // Pre-seed the caller past registration (invited) and past
            // probation (NULL) so the dispatch reaches the handler —
            // same shape as AddSourceIT's seed.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, "
                            + "registration_state, probation_until) "
                            + "VALUES ('inmemory', 'm1-203-user-1', FALSE, 'invited', NULL) "
                            + "ON CONFLICT (adapter, contact_id) DO NOTHING");
        }
    }

    // Acceptance item 1: a reachable, policy-allowed relay yields a
    // created source row (and no SSRF-blocked reply text).
    @Test
    void reachablePolicyAllowedRelayCreatesNostrSourceRow() throws Exception {
        String url = "ws://127.0.0.1:" + relay.port() + "/m1-203-relay";

        adapter.deliverDm("m1-203-user-1", "/add-source " + url + " --tags ai");

        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(), "exactly one outbound reply must be produced");
        String body = sent.get(0).text();
        assertTrue(body.contains("added"),
                "reachable relay must produce the fresh-insert reply — got: " + body);
        assertFalse(body.contains("non-public address"),
                "a policy-allowed, reachable relay must NOT yield the SSRF-blocked "
                        + "reply text — got: " + body);
        assertEquals(1L, countSourceRows(url),
                "exactly one source row must exist for the probed relay URL");
    }

    // Acceptance item 2: a relay resolving to a blocked address range
    // is rejected and no source row is written.
    @Test
    void blockedAddressRangeRelayIsRejectedWithoutSourceRow() throws Exception {
        // Cloud-metadata range — blocked even under the loopback-
        // permitting test blocklist, and rejected before any dial, so
        // the wss:// form is exercised verbatim.
        String url = "wss://169.254.169.254/m1-203-blocked";

        adapter.deliverDm("m1-203-user-1", "/add-source " + url + " --tags ai");

        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(), "exactly one outbound reply must be produced");
        assertTrue(sent.get(0).text().contains("non-public address"),
                "blocked-range relay must surface the SSRF-blocked friendly error — got: "
                        + sent.get(0).text());
        assertEquals(0L, countSourceRows(url),
                "no source row may be written when the SSRF gate rejects the relay");
    }

    // Acceptance item 3: an unreachable relay produces the friendly
    // error and no source row.
    @Test
    void unreachableRelayProducesFriendlyErrorWithoutSourceRow() throws Exception {
        String url = "ws://127.0.0.1:" + closedLoopbackPort() + "/m1-203-unreachable";

        adapter.deliverDm("m1-203-user-1", "/add-source " + url + " --tags ai");

        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(), "exactly one outbound reply must be produced");
        assertTrue(sent.get(0).text().contains("Couldn't reach"),
                "unreachable relay must surface the unreachable friendly error — got: "
                        + sent.get(0).text());
        assertEquals(0L, countSourceRows(url),
                "no source row may be written when the relay connection attempt fails");
    }

    // Acceptance item 4: the reply distinguishes genuine SSRF rejection
    // from ordinary unreachability.
    @Test
    void ssrfRejectionAndUnreachabilityProduceDistinctReplies() throws Exception {
        adapter.deliverDm("m1-203-user-1",
                "/add-source wss://169.254.169.254/m1-203-blocked --tags ai");
        String blockedReply = adapter.sentMessages().get(0).text();
        adapter.reset();
        adapter.deliverDm("m1-203-user-1",
                "/add-source ws://127.0.0.1:" + closedLoopbackPort()
                        + "/m1-203-unreachable --tags ai");
        String unreachableReply = adapter.sentMessages().get(0).text();

        assertTrue(blockedReply.contains("non-public address"),
                "policy rejection must carry the SSRF-blocked text — got: " + blockedReply);
        assertTrue(unreachableReply.contains("Couldn't reach"),
                "ordinary unreachability must carry the unreachable text — got: "
                        + unreachableReply);
        assertNotEquals(blockedReply, unreachableReply,
                "SSRF rejection and ordinary unreachability must NOT share a reply");
        assertFalse(unreachableReply.contains("non-public address"),
                "an unreachable relay must not be blamed on SSRF policy — got: "
                        + unreachableReply);
    }

    // --- helpers ---------------------------------------------------------

    /** Bind-then-close yields a loopback port with no listener. */
    private static int closedLoopbackPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private long countSourceRows(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM source WHERE kind = 'nostr' AND identifier = ?")) {
            ps.setString(1, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /**
     * CDI {@link Alternative} that supplies a {@link UrlProbe} backed
     * by a loopback-permitting {@link SsrfGuardedHttpClient} so the
     * in-process {@link FakeRelayServer} can be dialed — same shape as
     * {@link AddSourceIT.LoopbackProbe}. Every non-loopback range
     * (incl. the blocked-range test's metadata address) stays strict.
     */
    @Alternative
    @ApplicationScoped
    public static class LoopbackRelayProbe extends UrlProbe {

        public LoopbackRelayProbe() {
            super(new SsrfGuardedHttpClient(
                    LoopbackPermittingBlocklist.create(),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5),
                    Duration.ofMinutes(2),
                    10L * 1024,
                    3));
        }

        @Override
        public ProbeResult probeRelay(URI relayUri) {
            // Trivial: defer to the real implementation. The override
            // exists so the @Alternative is picked up over the
            // production no-arg UrlProbe (mirrors AddSourceIT.LoopbackProbe).
            return super.probeRelay(relayUri);
        }
    }

    /**
     * Same shape as {@link AddSourceIT.MvpProfile}, with
     * {@link QuarkusTestProfile#getEnabledAlternatives()} scoping the
     * {@link LoopbackRelayProbe} to this profile only.
     */
    public static final class NostrProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }

        @Override
        public java.util.Set<Class<?>> getEnabledAlternatives() {
            return java.util.Set.of(LoopbackRelayProbe.class);
        }
    }
}
