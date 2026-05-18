package app.zcat.infochat.provider.command;

import com.sun.net.httpserver.HttpServer;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.source.UrlProbe;
import app.zcat.infochat.provider.source.UrlProbe.ProbeResult;
import app.zcat.infochat.ssrf.IpBlocklist;
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
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for MVP exit criterion §4 per
 * {@code docs/design/00-mvp.md} §6: a non-admin user sending
 * {@code /add-source <url> --tags news,tech} via the in-process
 * {@code inmemory} adapter is auto-registered, the URL probe runs
 * through the SSRF-guarded HTTP client against an in-process
 * {@link com.sun.net.httpserver.HttpServer com.sun.net.httpserver.HttpServer}
 * fixture, the source row is written, the source_subscription is
 * upserted, the tag vocabulary is unioned, and exactly one outbound
 * reply containing the fresh-insert + URL-visibility disclosure
 * bundle text is produced.
 *
 * <p>The acceptance text in
 * {@code docs/plan/m1/tickets/M1-036-add-source-command.md} uses
 * {@code https://example.com/feed.xml} as an illustrative URL; this
 * IT uses {@code http://127.0.0.1:<port>/m1-036-mvp-feed.xml} so the
 * range-GET probe reaches the in-process HttpServer through a
 * loopback-permitting {@link SsrfGuardedHttpClient}. The
 * {@link LoopbackProbe} {@link Alternative} below installs that
 * client; the rest of the dispatch path runs against the production
 * CDI graph.</p>
 *
 * <p>Test isolation: the contact id, URL, and tag values all carry
 * the {@code m1-036-mvp-} prefix; {@link #cleanup()} deletes only
 * those before each run so the IT does not race other tests or the
 * bootstrap-admin row.</p>
 */
@QuarkusTest
@TestProfile(AddSourceIT.MvpProfile.class)
class AddSourceIT {

    private static HttpServer server;

    private static int port;

    @Inject InMemoryAdapter adapter;

    @Inject DataSource dataSource;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/m1-036-mvp-feed.xml", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            byte[] body = new byte[] { (byte) 'x' };
            exchange.sendResponseHeaders(206, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        // Expose the port to the LoopbackProbe @Alternative so it can
        // construct the probe URL the handler will dial. Test-only
        // shared state; the surrounding @QuarkusTest profile makes the
        // alternative the active UrlProbe.
        LoopbackProbe.PORT_HOLDER = port;
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
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
                            + "   WHERE identifier LIKE 'http://127.0.0.1:%/m1-036-mvp-feed.xml')");
            exec(conn,
                    "DELETE FROM source_subscription "
                            + "WHERE source_id IN ("
                            + "  SELECT id FROM source "
                            + "   WHERE identifier LIKE 'http://127.0.0.1:%/m1-036-mvp-feed.xml')");
            exec(conn,
                    "DELETE FROM source "
                            + "WHERE identifier LIKE 'http://127.0.0.1:%/m1-036-mvp-feed.xml'");
            exec(conn,
                    "DELETE FROM tag WHERE name IN ('m1-036-mvp-news', 'm1-036-mvp-tech')");
            exec(conn,
                    "DELETE FROM users WHERE contact_id = 'm1-036-mvp-user-1'");
        }
    }

    @Test
    void mvpExitCriterionFourEndToEndAddSourceProducesRowsTagsSubscriptionAndReply() throws Exception {
        String url = "http://127.0.0.1:" + port + "/m1-036-mvp-feed.xml";
        adapter.deliverDm("m1-036-mvp-user-1",
                "/add-source " + url + " --tags m1-036-mvp-news,m1-036-mvp-tech");

        // (a) exactly one outbound message whose body contains the
        // fresh-insert reply + URL-visibility disclosure.
        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(),
                "exactly one outbound reply must be produced for the MVP exit criterion §4 flow");
        String body = sent.get(0).text();
        assertTrue(body.contains("Source"),
                "outbound reply must contain the fresh-insert literal — got: " + body);
        assertTrue(body.contains("added"),
                "outbound reply must contain the fresh-insert verb — got: " + body);
        assertTrue(body.contains("visible to bot admins"),
                "outbound reply MUST include the URL-visibility disclosure "
                        + "(spec §Source management) — got: " + body);

        // (b) one source row with kind='rss' + identifier=<url>.
        UUID sourceId = readSourceId(url);

        // (c) one source_subscription row for the caller's scope.
        assertEquals(1L, countSubscriptions(sourceId),
                "exactly one source_subscription row must exist for the inserted source");

        // (d) bootstrap_tags = {news, tech} (in order).
        assertEquals(
                List.of("m1-036-mvp-news", "m1-036-mvp-tech"),
                readBootstrapTags(sourceId),
                "bootstrap_tags must match the supplied --tags list (normalized)");

        // (e) both tag rows present in the controlled vocabulary.
        assertEquals(2L, countTags("m1-036-mvp-news", "m1-036-mvp-tech"),
                "both supplied --tags must be unioned into the controlled vocabulary");
    }

    // --- helpers ---------------------------------------------------------

    private UUID readSourceId(String url) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM source WHERE kind = 'rss' AND identifier = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "source row must exist for kind='rss' identifier=" + url);
                UUID id = (UUID) rs.getObject(1);
                assertTrue(!rs.next(), "exactly one source row must match");
                return id;
            }
        }
    }

    private long countSubscriptions(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM source_subscription WHERE source_id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private List<String> readBootstrapTags(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT bootstrap_tags FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Array tagsArray = rs.getArray("bootstrap_tags");
                return Arrays.asList((String[]) tagsArray.getArray());
            }
        }
    }

    private long countTags(String... names) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM tag WHERE name = ANY (?)")) {
            ps.setArray(1, conn.createArrayOf("TEXT", names));
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
     * by a loopback-permitting {@link SsrfGuardedHttpClient}. The
     * production {@link UrlProbe#UrlProbe() no-arg constructor}
     * builds a strict client that refuses 127.0.0.1; this test wants
     * the in-process HttpServer to be reachable. The
     * {@link LoopbackPermitting} blocklist allows loopback while
     * leaving every other range strict.
     *
     * <p>No {@link Priority} annotation — activation is scoped via
     * {@link MvpProfile#getEnabledAlternatives()} so this alternative
     * is active only inside this test's profile.</p>
     */
    @Alternative
    @ApplicationScoped
    public static class LoopbackProbe extends UrlProbe {

        /** Set by {@link #startServer()} so the wrapper has a sensible body cap; not used directly here. */
        static int PORT_HOLDER;

        public LoopbackProbe() {
            super(new SsrfGuardedHttpClient(
                    new LoopbackPermitting(),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5),
                    10L * 1024,
                    3));
        }

        @Override
        public ProbeResult probe(URI url) {
            // Trivial: defer to the real implementation. The override
            // exists so the @Alternative is picked up over the
            // production no-arg UrlProbe.
            return super.probe(url);
        }
    }

    /**
     * Test-only {@link IpBlocklist} subclass — same as the inner
     * classes in {@code SsrfGuardedHttpClientTest} and
     * {@code UrlProbeTest}. Loopback is permitted so the in-process
     * fixture can be dialed; every other range stays strict.
     */
    private static final class LoopbackPermitting extends IpBlocklist {
        @Override
        public boolean isBlocked(InetAddress addr) {
            if (addr.isLoopbackAddress()) {
                return false;
            }
            return super.isBlocked(addr);
        }
    }

    /**
     * Same shape as {@code AdapterRouterIT.MvpProfile}. Plus
     * {@link QuarkusTestProfile#getEnabledAlternatives()} so the
     * {@link LoopbackProbe} is the only active alternative for this
     * profile — Quarkus boot-time CDI would otherwise see BOTH this
     * and {@code AddSourceCommandHandlerTest.MockUrlProbe} as
     * competing {@code @Priority(1)} alternatives and refuse to start.
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
            return java.util.Set.of(LoopbackProbe.class);
        }
    }
}
