package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.source.KindResolver;
import app.zcat.infochat.provider.source.SourceUpsertService;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-tier (plain JUnit) test for {@link AddSourceCommandHandler}'s
 * ban-check ordering invariant per the test-pyramid convention at
 * {@code docs/process/test-pyramid.md} §Handler unit tests.
 *
 * <p>Pins the in-handler ban-check ordering: actor lookup AND the
 * {@code is_banned} check run BEFORE the scope discriminator (the
 * {@code if (scope instanceof ScopeRef.Group)} branch). Implements
 * the M1-039 remediation of M1-036's red-team finding 2 (medium
 * INFO-LEAK — group-scope banned-user reply leak).
 *
 * <p><b>Coverage at the current SPI shape.</b> Two scenarios are
 * testable here:
 * <ul>
 *   <li>{@link #bannedDmUserReceivesFixedBanReply()} — banned DM
 *       caller receives the {@code error.add_source.banned} bundle
 *       literal; the URL probe is never invoked.</li>
 *   <li>{@link #groupScopeNonAdminReceivesGroupAdminOnly()} —
 *       non-banned non-group-admin caller in group scope receives
 *       the {@code error.add_source.group_admin_only} bundle
 *       literal; the discriminator is preserved after the reorder.</li>
 * </ul>
 *
 * <p>Scenarios (b) banned-user-in-group and (d) group-admin-proceeds
 * are NOT covered here because {@link ScopeRef.Group} carries only
 * {@code adapterGroupId} (no contact id); {@code lookupActor}
 * returns {@link Optional#empty()} for group scope, so the
 * in-handler ban check is observably a no-op there and the
 * group-admin proceed path cannot be exercised without the
 * actor-seam SPI widening that T2-F lands.
 *
 * <p><b>After M1-044b lands the intake-step splice</b>, the ban
 * check moves into {@code InboundRouter} and this class becomes
 * redundant; the ordering moves to
 * {@code InboundRouterIntakeOrderingTest} scenario (f).
 */
class AddSourceBanCheckOrderingTest {

    private AddSourceCommandHandler handler;
    private RecordingUrlProbe urlProbe;
    private RecordingSourceUpsertService sourceUpsertService;
    private BundleLoader bundleLoader;

    @BeforeEach
    void buildHandlerWithStubs() throws Exception {
        bundleLoader = newRealBundleLoader();
        urlProbe = new RecordingUrlProbe();
        sourceUpsertService = new RecordingSourceUpsertService();
        handler = new AddSourceCommandHandler();
        handler.bundleLoader = bundleLoader;
        handler.kindResolver = new KindResolver();
        handler.urlProbe = urlProbe;
        handler.sourceUpsertService = sourceUpsertService;
        InboundContext context = new InboundContext();
        context.setAdapterName("inmemory");
        handler.inboundContext = context;
    }

    @Test
    void bannedDmUserReceivesFixedBanReply() {
        handler.dataSource = StubUserDataSource.bannedUser(UUID.randomUUID());

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("m1-039-banned-dm"),
                "/add-source https://example.com/m1-039-banned.xml --tags m1-039-tag");

        assertTrue(reply.text().contains("not permitted"),
                "banned DM caller must see the error.add_source.banned bundle literal "
                        + "(\"You are not permitted to add sources.\") — got: " + reply.text());
        assertEquals(0, urlProbe.callCount(),
                "the in-handler ban check must short-circuit BEFORE UrlProbe is invoked");
        assertEquals(0, sourceUpsertService.callCount(),
                "the in-handler ban check must short-circuit BEFORE SourceUpsertService runs");
    }

    @Test
    void groupScopeNonAdminReceivesGroupAdminOnly() {
        // Group scope: contactIdOf(scope) returns null so lookupActor
        // never touches dataSource. Wire a NEVER stub so an accidental
        // SQL call would surface loudly.
        handler.dataSource = StubUserDataSource.neverCalled();

        UUID groupId = UUID.randomUUID();
        OutboundMessage reply = handler.handle(
                new ScopeRef.Group(groupId.toString()),
                "/add-source https://example.com/m1-039-group.xml --tags m1-039-tag");

        assertTrue(reply.text().contains("Only group admins"),
                "non-admin caller in group scope must see the "
                        + "error.add_source.group_admin_only bundle literal — got: "
                        + reply.text());
        assertEquals(0, urlProbe.callCount(),
                "the discriminator must short-circuit BEFORE UrlProbe is invoked");
        assertEquals(0, sourceUpsertService.callCount(),
                "the discriminator must short-circuit BEFORE SourceUpsertService runs");
    }

    // ----- collaborator stubs -------------------------------------------

    /**
     * Real {@link BundleLoader} constructed by hand. Without CDI the
     * {@code @PostConstruct load()} does not fire automatically, so the
     * test invokes it via reflection.
     */
    private static BundleLoader newRealBundleLoader() throws Exception {
        BundleLoader loader = new BundleLoader();
        Method load = BundleLoader.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(loader);
        return loader;
    }

    private static final class RecordingUrlProbe extends UrlProbe {
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public ProbeResult probe(URI url) {
            callCount.incrementAndGet();
            return ProbeResult.success(200, Optional.of("application/rss+xml"));
        }

        int callCount() {
            return callCount.get();
        }
    }

    private static final class RecordingSourceUpsertService extends SourceUpsertService {
        private final AtomicInteger callCount = new AtomicInteger();

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
            callCount.incrementAndGet();
            return new UpsertResult(Outcome.FRESH_INSERT, UUID.randomUUID(), displayName);
        }

        int callCount() {
            return callCount.get();
        }
    }

    /**
     * Base implementation of the {@link DataSource} surface that
     * throws {@link UnsupportedOperationException} for everything
     * except {@link #getConnection()}. Test subclasses override
     * {@code getConnection()} to return a stubbed {@link Connection}.
     * Mockito is intentionally absent from the Provider classpath —
     * this base keeps the JDBC-surface boilerplate localized.
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

    /**
     * Hand-rolled JDBC stub: returns the seeded {@code users} row
     * (or no rows) for {@code AddSourceCommandHandler.lookupActor}'s
     * single SELECT. Uses {@link Proxy} for the inner JDBC types so
     * the unused surface (~200 interface methods) does not leak into
     * the test file. Mockito is intentionally absent from the
     * Provider classpath — this stub keeps the per-class boilerplate
     * inline rather than introducing a new dependency.
     */
    private static class StubUserDataSource extends UnsupportedDataSource {
        private final boolean hasRow;
        private final UUID id;
        private final boolean isAdmin;
        private final boolean isBanned;

        static StubUserDataSource bannedUser(UUID id) {
            return new StubUserDataSource(true, id, false, true);
        }

        static StubUserDataSource neverCalled() {
            // hasRow doesn't matter; the handler should never call
            // getConnection for group scope. Any actual call surfaces
            // loudly because UnsupportedDataSource.getConnection() is
            // overridden here to throw.
            return new StubUserDataSource(false, null, false, false) {
                @Override
                public Connection getConnection() {
                    throw new AssertionError(
                            "DataSource.getConnection() called on neverCalled() stub");
                }
            };
        }

        private StubUserDataSource(boolean hasRow, UUID id, boolean isAdmin, boolean isBanned) {
            this.hasRow = hasRow;
            this.id = id;
            this.isAdmin = isAdmin;
            this.isBanned = isBanned;
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
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] { PreparedStatement.class },
                    (proxy, method, methodArgs) -> switch (method.getName()) {
                        case "setString" -> null;
                        case "executeQuery" -> newResultSet();
                        case "close" -> null;
                        case "toString" -> "StubPreparedStatement";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> throw new UnsupportedOperationException(
                                "PreparedStatement." + method.getName() + " not stubbed");
                    });
        }

        private ResultSet newResultSet() {
            // One-shot iterator: first next() yields the row, second
            // next() returns false. Boxed in an array so the lambda
            // captures a mutable reference.
            boolean[] consumed = { !hasRow };
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[] { ResultSet.class },
                    (proxy, method, methodArgs) -> switch (method.getName()) {
                        case "next" -> {
                            if (consumed[0]) yield false;
                            consumed[0] = true;
                            yield true;
                        }
                        case "getObject" -> id;
                        case "getBoolean" -> {
                            String col = (String) methodArgs[0];
                            yield switch (col) {
                                case "is_admin" -> isAdmin;
                                case "is_banned" -> isBanned;
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
}
