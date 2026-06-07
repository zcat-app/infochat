package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestRetryService;
import app.zcat.infochat.provider.digest.DigestRetryService.RetryResult;
import app.zcat.infochat.provider.group.GroupMembershipRepository;
import app.zcat.infochat.provider.messaging.InboundContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.zcat.infochat.provider.user.UserRepository;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@code /retry --digest} routing branch added in M1-080c.
 * Existing personal-retry tests remain in {@link RetryCommandHandlerTest}.
 */
class RetryDigestCommandTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID GROUP_ID = UUID.randomUUID();

    private RetryCommandHandler handler;
    private StubDigestRetryService digestRetryService;

    @BeforeEach
    void setUp() throws Exception {
        handler = new RetryCommandHandler();
        handler.bundleLoader = newRealBundleLoader();

        digestRetryService = new StubDigestRetryService();
        handler.digestRetryService = digestRetryService;
        handler.groupMembershipRepository = new StubGroupMembershipRepository(true);
        handler.auditLogWriter = new AuditLogWriter(row -> row);
        DataSource stub = stubDigestDataSource(USER_ID, true, GROUP_ID);
        handler.dataSource = stub;
        handler.userRepository = new UserRepository(stub);

        InboundContext ctx = new InboundContext();
        ctx.setAdapterName("inmemory");
        ctx.setSenderContactId("admin-contact-1");
        handler.inboundContext = ctx;
    }

    @Test
    void retryDigest_succeedsForGroupAdmin() {
        digestRetryService.nextResult = RetryResult.SUCCESS;

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("group-1"), "/retry --digest");

        assertTrue(reply.text().contains("Digest retry complete"),
                "reply must indicate success. Got: " + reply.text());
    }

    @Test
    void retryDigest_rejectsNonAdmin() {
        DataSource nonAdminStub = stubDigestDataSource(USER_ID, false, GROUP_ID);
        handler.dataSource = nonAdminStub;
        handler.userRepository = new UserRepository(nonAdminStub);
        handler.groupMembershipRepository = new StubGroupMembershipRepository(false);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("group-1"), "/retry --digest");

        assertTrue(reply.text().contains("group admins or bot admins"),
                "reply must indicate admin required. Got: " + reply.text());
    }

    @Test
    void retryDigest_rejectsDmScope() {
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("dm-contact"), "/retry --digest");

        assertTrue(reply.text().contains("group scope only"),
                "reply must indicate group-only. Got: " + reply.text());
    }

    @Test
    void retryDigest_rejectsConcurrentRetry() {
        digestRetryService.nextResult = RetryResult.ALREADY_IN_PROGRESS;

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("group-1"), "/retry --digest");

        assertTrue(reply.text().contains("already in progress"),
                "reply must indicate concurrent retry. Got: " + reply.text());
    }

    // ----- stubs -------------------------------------------------------------

    static class StubDigestRetryService extends DigestRetryService {
        RetryResult nextResult = RetryResult.SUCCESS;

        @Override
        public RetryResult retryDigest(UUID groupId) {
            return nextResult;
        }
    }

    static class StubGroupMembershipRepository extends GroupMembershipRepository {
        private final boolean isGroupAdmin;

        StubGroupMembershipRepository(boolean isGroupAdmin) {
            super(NOOP_DATASOURCE);
            this.isGroupAdmin = isGroupAdmin;
        }

        @Override
        public boolean isGroupAdmin(UUID groupId, UUID userId) {
            return isGroupAdmin;
        }
    }

    private static final DataSource NOOP_DATASOURCE = new DataSource() {
        @Override public Connection getConnection() { throw new UnsupportedOperationException(); }
        @Override public Connection getConnection(String u, String p) { throw new UnsupportedOperationException(); }
        @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
        @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
        @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
        @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    };

    private static BundleLoader newRealBundleLoader() throws Exception {
        BundleLoader loader = new BundleLoader();
        Method load = BundleLoader.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(loader);
        return loader;
    }

    /**
     * Stub DataSource for the --digest path. Handles:
     * 1. SELECT id, is_admin FROM users WHERE adapter = ? AND contact_id = ?
     * 2. SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?
     */
    private static DataSource stubDigestDataSource(UUID userId, boolean isAdmin, UUID groupId) {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] { Connection.class },
                        (proxy, method, args) -> switch (method.getName()) {
                            case "prepareStatement" -> {
                                String sql = (String) args[0];
                                yield stubPreparedStatement(sql, userId, isAdmin, groupId);
                            }
                            case "setAutoCommit", "commit", "rollback", "close" -> null;
                            default -> throw new UnsupportedOperationException(
                                    "Conn." + method.getName());
                        });
            }

            @Override public Connection getConnection(String u, String p) { return getConnection(); }
            @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
            @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
            @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
            @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
            @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    private static PreparedStatement stubPreparedStatement(
            String sql, UUID userId, boolean isAdmin, UUID groupId) {
        boolean isActorQuery = sql.contains("is_admin") && sql.contains("FROM users");
        boolean isGroupQuery = sql.contains("FROM groups");
        boolean isAuditInsert = sql.contains("audit_log");
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setObject", "setTimestamp", "setNull" -> null;
                    case "executeQuery" -> {
                        if (isActorQuery) yield actorResultSet(userId, isAdmin);
                        if (isGroupQuery) yield groupResultSet(groupId);
                        yield emptyResultSet();
                    }
                    case "executeUpdate" -> 1;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "PS." + method.getName());
                });
    }

    private static ResultSet actorResultSet(UUID userId, boolean isAdmin) {
        boolean[] consumed = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        if (consumed[0]) yield false;
                        consumed[0] = true;
                        yield true;
                    }
                    case "getObject" -> {
                        String col = (String) args[0];
                        yield switch (col) {
                            case "id" -> userId;
                            default -> throw new UnsupportedOperationException("col: " + col);
                        };
                    }
                    case "getBoolean" -> isAdmin;
                    // UserRepository's canonical projection reads these
                    // columns beyond what the handler's ActorRow consumes.
                    case "getString" -> {
                        String col = (String) args[0];
                        yield switch (col) {
                            case "contact_id" -> "admin-contact-1";
                            case "registration_state" -> "vouched";
                            default -> throw new UnsupportedOperationException("col: " + col);
                        };
                    }
                    case "getInt" -> 0;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }

    private static ResultSet groupResultSet(UUID groupId) {
        boolean[] consumed = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        if (consumed[0]) yield false;
                        consumed[0] = true;
                        yield true;
                    }
                    case "getObject" -> groupId;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }

    private static ResultSet emptyResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> false;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }
}
