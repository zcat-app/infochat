package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.source.KindResolver;
import app.zcat.infochat.provider.source.SourceUpsertService;
import app.zcat.infochat.provider.source.SourceUpsertService.Outcome;
import app.zcat.infochat.provider.source.SourceUpsertService.UpsertResult;
import app.zcat.infochat.provider.source.UrlProbe;
import app.zcat.infochat.provider.source.UrlProbe.ProbeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-tier (plain JUnit, no Quarkus boot) tests for
 * {@link AddSourceCommandHandler} per the test-pyramid convention at
 * {@code docs/process/test-pyramid.md} §Handler unit tests.
 *
 * <p>The handler's six {@code @Inject} collaborators are stubbed in
 * {@link #buildHandlerWithStubs()}: real {@link BundleLoader} (loaded
 * by hand), real {@link KindResolver} (pure function), programmable
 * {@link RecordingUrlProbe} per-URL probe outcomes, programmable
 * {@link StubSourceUpsertService} per-scenario outcomes, programmable
 * {@link StubUserDataSource} per-contact-id user rows, and a hand-
 * constructed {@link InboundContext} with {@code adapterName="inmemory"}.
 *
 * <p>Asserted invariants (one {@code @Test} per behavioral branch):
 * <ul>
 *   <li>Dispatch: one /add-source call exercises UrlProbe exactly
 *       once on the dispatch path.</li>
 *   <li>Permission gate: DM non-banned proceeds, DM banned rejects,
 *       group non-admin rejects.</li>
 *   <li>Ambiguous probe: {@code /about} URL → AMBIGUOUS reply
 *       without invoking the probe; an RSS-hinted URL contradicted
 *       by {@code text/html} → AMBIGUOUS reply.</li>
 *   <li>URL-visibility disclosure: present on Branch A reply,
 *       absent on Branch B / Branch C replies.</li>
 * </ul>
 */
class AddSourceCommandHandlerTest {

    private AddSourceCommandHandler handler;
    private RecordingUrlProbe urlProbe;
    private StubSourceUpsertService sourceUpsertService;
    private StubUserDataSource dataSource;
    private BundleLoader bundleLoader;

    @BeforeEach
    void buildHandlerWithStubs() throws Exception {
        bundleLoader = newRealBundleLoader();
        urlProbe = new RecordingUrlProbe();
        sourceUpsertService = new StubSourceUpsertService();
        dataSource = new StubUserDataSource();
        handler = new AddSourceCommandHandler();
        handler.bundleLoader = bundleLoader;
        handler.kindResolver = new KindResolver();
        handler.urlProbe = urlProbe;
        handler.sourceUpsertService = sourceUpsertService;
        handler.dataSource = dataSource;
        InboundContext context = new InboundContext();
        context.setAdapterName("inmemory");
        handler.inboundContext = context;
    }

    @Test
    void inboundRouterDispatchesAddSourceToHandlerExactlyOnce() {
        dataSource.seedUser("m1-036h-disp", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-036h-disp.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-disp"),
                "/add-source https://example.com/m1-036h-disp.xml --tags m1-036h-news");

        assertEquals(1, urlProbe.callCount(),
                "the handler must call UrlProbe.probe exactly once on the dispatch path");
        // One OutboundMessage returned by signature; assert the text is non-empty
        // so this scenario fails loud if the handler short-circuits to nothing.
        assertFalse(reply.text().isEmpty(),
                "dispatch must produce a non-empty reply");
    }

    @Test
    void dmNonBannedNonAdminProceedsAndProducesFreshInsertReply() {
        dataSource.seedUser("m1-036h-fresh-user", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-036h-fresh.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.FRESH_INSERT);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-fresh-user"),
                "/add-source https://example.com/m1-036h-fresh.xml --tags m1-036h-news");

        String body = reply.text();
        // Branch A reply MUST contain the URL-visibility disclosure literal.
        assertTrue(body.contains("visible to bot admins"),
                "Branch A reply MUST include the URL-visibility disclosure literal "
                        + "(per spec §Source management) — got: " + body);
    }

    @Test
    void dmBannedUserRejectsBeforeProbe() {
        // No mock probe setup needed — the ban check fires BEFORE probe.
        dataSource.seedUser("m1-036h-banned", /* isAdmin */ false, /* isBanned */ true);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-banned"),
                "/add-source https://example.com/m1-036h-banned.xml --tags m1-036h-news");

        String body = reply.text();
        // The bundle value for error.add_source.banned should be present.
        assertTrue(body.contains("not permitted"),
                "banned user must see the banned-friendly-error literal — got: " + body);
        assertEquals(0, urlProbe.callCount(),
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
        assertEquals(0, urlProbe.callCount(),
                "group-scope rejection must short-circuit BEFORE UrlProbe is invoked");
    }

    // The "GROUP scope, group admin → handler proceeds" branch is NOT
    // covered here: the frozen CommandHandler SPI does not carry the
    // inbound actor's identity in group scope (ScopeRef.Group holds
    // only the adapter-side group id; the actor's contact id is not on
    // the SPI), so the handler cannot consult group_membership for the
    // caller. T2-F wires the actor seam + the group-admin proceed
    // path; the corresponding acceptance test lands then.

    @Test
    void ambiguousUrlWithHtmlContentTypeSurfacesAmbiguousFriendlyError() {
        // /about URL has no RSS path-hint; the resolver returns
        // AMBIGUOUS directly. The handler short-circuits to the
        // ambiguous_url bundle key BEFORE the probe is invoked (the
        // probe runs after kind resolution).
        dataSource.seedUser("m1-036h-amb", /* isAdmin */ false, /* isBanned */ false);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-amb"),
                "/add-source https://example.com/m1-036h-about --tags m1-036h-news");

        String body = reply.text();
        assertTrue(body.contains("Couldn't infer the source type"),
                "ambiguous-URL reply must surface the ambiguous_url friendly error literal — got: "
                        + body);
        assertEquals(0, urlProbe.callCount(),
                "ambiguous-URL short-circuit must run BEFORE UrlProbe is invoked");
    }

    @Test
    void rssPathUrlContradictedByHtmlContentTypeSurfacesAmbiguous() {
        // Path ends in /feed → resolver chooses RSS via the path
        // hint. The probe returns text/html — the confirm-or-contradict
        // check fires and the handler returns the ambiguous_url friendly
        // error.
        dataSource.seedUser("m1-036h-contradict-user", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-036h-news/feed",
                ProbeResult.success(200, Optional.of("text/html")));

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-contradict-user"),
                "/add-source https://example.com/m1-036h-news/feed --tags m1-036h-news");

        String body = reply.text();
        assertTrue(body.contains("Couldn't infer the source type"),
                "RSS-hinted URL contradicted by text/html Content-Type must surface "
                        + "the ambiguous_url friendly error — got: " + body);
    }

    @Test
    void branchBSubscribedExistingReplyOmitsUrlVisibilityDisclosure() {
        dataSource.seedUser("m1-036h-non-admin-b", /* isAdmin */ false, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-036h-shared.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.SUBSCRIBED_EXISTING);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-non-admin-b"),
                "/add-source https://example.com/m1-036h-shared.xml --tags m1-036h-other");

        String body = reply.text();
        assertFalse(body.contains("visible to bot admins"),
                "Branch B (subscribed-existing) reply MUST NOT include the URL-visibility "
                        + "disclosure — got: " + body);
        assertTrue(body.contains("Subscribed"),
                "Branch B reply must include the subscribed-existing bundle literal — got: "
                        + body);
    }

    @Test
    void branchCBotAdminTagReplacementReplyOmitsUrlVisibilityDisclosure() {
        // Bot-admin caller against a pre-existing source row routes to
        // the ADMIN_TAGS_REPLACED arm of buildReply, which emits the
        // admin_tags_replaced bundle value WITHOUT the URL-visibility
        // disclosure (gated to FRESH_INSERT only).
        dataSource.seedUser("m1-036h-bot-admin-c", /* isAdmin */ true, /* isBanned */ false);
        urlProbe.setProbe("https://example.com/m1-036h-admin.xml",
                ProbeResult.success(200, Optional.of("application/rss+xml")));
        sourceUpsertService.setOutcome(Outcome.ADMIN_TAGS_REPLACED);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-036h-bot-admin-c"),
                "/add-source https://example.com/m1-036h-admin.xml --tags m1-036h-new");

        String body = reply.text();
        assertFalse(body.contains("visible to bot admins"),
                "Branch C (admin-tag-replacement) reply MUST NOT include the "
                        + "URL-visibility disclosure — got: " + body);
        assertTrue(body.contains("bootstrap tags replaced"),
                "Branch C reply must include the admin-tags-replaced bundle literal "
                        + "— got: " + body);
    }

    // ----- fixtures + collaborator stubs --------------------------------

    private static BundleLoader newRealBundleLoader() throws Exception {
        BundleLoader loader = new BundleLoader();
        Method load = BundleLoader.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(loader);
        return loader;
    }

    /**
     * Recording {@link UrlProbe} subclass with per-URL canned probe
     * outcomes. An unmapped URL falls through to a SUCCESS with no
     * content-type so the dispatch test's probe doesn't need to be
     * seeded with the dispatch URL twice.
     */
    private static final class RecordingUrlProbe extends UrlProbe {

        private final Map<String, ProbeResult> canned = new ConcurrentHashMap<>();
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public ProbeResult probe(URI url) {
            callCount.incrementAndGet();
            return canned.getOrDefault(
                    url.toString(),
                    ProbeResult.success(200, Optional.empty()));
        }

        void setProbe(String url, ProbeResult result) {
            canned.put(url, result);
        }

        int callCount() {
            return callCount.get();
        }
    }

    /**
     * Programmable {@link SourceUpsertService} stub: returns a canned
     * {@link UpsertResult} on every {@link #upsert} call. Tests
     * configure the outcome per scenario via {@link #setOutcome}.
     * The display name is echoed back verbatim so Branch A's
     * fresh-insert reply gets a usable interpolation argument.
     */
    private static final class StubSourceUpsertService extends SourceUpsertService {
        private Outcome outcome = Outcome.FRESH_INSERT;

        void setOutcome(Outcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public UpsertResult upsert(UUID actorUserId,
                                   boolean actorIsBotAdmin,
                                   String scopeKind,
                                   UUID scopeId,
                                   KindResolver.SourceKind kind,
                                   String identifier,
                                   String displayName,
                                   String category,
                                   List<String> tags) {
            return new UpsertResult(outcome, UUID.randomUUID(), displayName);
        }
    }

    /**
     * Hand-rolled JDBC stub: returns the seeded {@code users} row for
     * the contact_id passed as the second parameter to
     * {@code AddSourceCommandHandler.lookupActor}'s SELECT (the first
     * parameter is the adapter name, which is asserted in
     * {@code InboundContext} setup and not used as a lookup key here).
     * Mockito is intentionally absent from the Provider classpath.
     */
    private static final class StubUserDataSource extends UnsupportedDataSource {

        private record UserRow(UUID id, boolean isAdmin, boolean isBanned) {}

        private final Map<String, UserRow> rowsByContactId = new ConcurrentHashMap<>();

        void seedUser(String contactId, boolean isAdmin, boolean isBanned) {
            rowsByContactId.put(contactId, new UserRow(UUID.randomUUID(), isAdmin, isBanned));
        }

        @Override
        public Connection getConnection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, methodArgs) -> switch (method.getName()) {
                        case "prepareStatement" -> newPreparedStatement();
                        case "close" -> null;
                        case "toString" -> "StubConnection";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> throw new UnsupportedOperationException(
                                "Connection." + method.getName() + " not stubbed");
                    });
        }

        private PreparedStatement newPreparedStatement() {
            // Capture per-statement setString parameters so executeQuery
            // can resolve the row by contact_id (parameter index 2).
            Map<Integer, String> params = new HashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] { PreparedStatement.class },
                    (proxy, method, methodArgs) -> switch (method.getName()) {
                        case "setString" -> {
                            params.put((Integer) methodArgs[0], (String) methodArgs[1]);
                            yield null;
                        }
                        case "executeQuery" -> {
                            String contactId = params.get(2);
                            UserRow row = rowsByContactId.get(contactId);
                            yield newResultSet(row);
                        }
                        case "close" -> null;
                        case "toString" -> "StubPreparedStatement";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> throw new UnsupportedOperationException(
                                "PreparedStatement." + method.getName() + " not stubbed");
                    });
        }

        private ResultSet newResultSet(UserRow row) {
            boolean[] consumed = { row == null };
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[] { ResultSet.class },
                    (proxy, method, methodArgs) -> switch (method.getName()) {
                        case "next" -> {
                            if (consumed[0]) yield false;
                            consumed[0] = true;
                            yield true;
                        }
                        case "getObject" -> row.id();
                        case "getBoolean" -> {
                            String col = (String) methodArgs[0];
                            yield switch (col) {
                                case "is_admin" -> row.isAdmin();
                                case "is_banned" -> row.isBanned();
                                default -> throw new UnsupportedOperationException(
                                        "ResultSet.getBoolean unknown column: " + col);
                            };
                        }
                        case "close" -> null;
                        case "toString" -> "StubResultSet";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> throw new UnsupportedOperationException(
                                "ResultSet." + method.getName() + " not stubbed");
                    });
        }
    }

    /**
     * Base implementation of {@link DataSource} that throws
     * {@link UnsupportedOperationException} for everything except
     * {@link #getConnection()}.
     */
    private static class UnsupportedDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new UnsupportedOperationException("getConnection() not stubbed");
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("getConnection(String,String) not stubbed");
        }

        @Override
        public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException("getLogWriter not stubbed");
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException("setLogWriter not stubbed");
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException("setLoginTimeout not stubbed");
        }

        @Override
        public int getLoginTimeout() {
            throw new UnsupportedOperationException("getLoginTimeout not stubbed");
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("unwrap not stubbed");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
