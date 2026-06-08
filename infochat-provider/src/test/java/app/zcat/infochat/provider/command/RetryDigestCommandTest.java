package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.digest.DigestRetryService;
import app.zcat.infochat.provider.digest.DigestRetryService.RetryResult;
import app.zcat.infochat.provider.group.GroupMembershipRepository;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.RateCapBucket;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // M1-222 cap gates in handleDigestRetry: generous defaults so
        // the pre-existing routing tests pass both gates untouched; the
        // cap tests below swap in drained instances.
        handler.llmRateCap = new LlmRateCap(10);
        handler.rateCapBucket = new CountingLlmBucket(10);
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

    @Test
    void retryDigest_rejectedWhenDigestPaused() {
        // M1-227 (F1): /retry --digest runs through DigestRetryService, NOT the
        // scheduler, so the digest_enabled gate must be re-checked here or a
        // paused group could regenerate and re-send its stale cached digest.
        DataSource pausedStub = stubDigestDataSource(USER_ID, true, GROUP_ID, false);
        handler.dataSource = pausedStub;
        handler.userRepository = new UserRepository(pausedStub);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("group-1"), "/retry --digest");

        assertEquals(handler.bundleLoader.get(BundleKeys.ERROR_RETRY_DIGEST_PAUSED), reply.text(),
                "a paused group's digest retry must be rejected. Got: " + reply.text());
        assertEquals(0, digestRetryService.callCount,
                "retryDigest must NOT be called when the group's digest is paused");
    }

    // ----- M1-222: per-group LLM cap on the --digest re-roll ---------------
    //
    // Per docs/spec/security.md §Rate limiting, the per-group LLM
    // sub-bucket (D47) bounds "chat replies + on-demand /summary +
    // /retry re-rolls"; the per-user LlmRateCap fires first. Redteam
    // M1-222 finding 1 (DOS-medium): pre-fix, /retry --digest reached
    // DigestRetryService without consulting either cap.

    @Test
    void retryDigest_rejectedWhenGroupLlmBucketExhausted() {
        handler.rateCapBucket = new CountingLlmBucket(0);
        // Single-token per-user cap: the post-rejection acquire below
        // only succeeds if the group-cap rejection refunded the token
        // the gate consumed (redteam finding 3, digest call site).
        handler.llmRateCap = new LlmRateCap(1);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("group-1"), "/retry --digest");

        assertEquals(handler.bundleLoader.get(BundleKeys.GROUP_LLM_RATE_LIMIT), reply.text(),
                "group-cap overflow must send the fixed group.llm_rate_limit reply");
        assertEquals(0, digestRetryService.callCount,
                "retryDigest must NOT be called when the per-group LLM bucket is exhausted");
        assertTrue(handler.llmRateCap.tryAcquire(USER_ID),
                "the group-cap rejection must refund the per-user token it consumed");
    }

    @Test
    void retryDigest_rejectedWhenPerUserLlmBucketExhausted() {
        LlmRateCap exhausted = new LlmRateCap(1);
        assertTrue(exhausted.tryAcquire(USER_ID), "drain the bucket's only token");
        handler.llmRateCap = exhausted;
        CountingLlmBucket groupBucket = new CountingLlmBucket(10);
        handler.rateCapBucket = groupBucket;

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("group-1"), "/retry --digest");

        assertEquals(handler.bundleLoader.get(BundleKeys.ERROR_CHAT_LLM_RATE_CAP), reply.text(),
                "per-user overflow must send the existing chat-LLM rate-cap reply");
        assertEquals(0, digestRetryService.callCount,
                "retryDigest must NOT be called when the per-user LLM cap is exhausted");
        assertEquals(10, groupBucket.tokensLeft,
                "the per-user cap fires first — a per-user rejection must not touch the group bucket");
    }

    // ----- stubs -------------------------------------------------------------

    static class StubDigestRetryService extends DigestRetryService {
        RetryResult nextResult = RetryResult.SUCCESS;
        int callCount = 0;

        @Override
        public RetryResult retryDigest(UUID groupId) {
            callCount++;
            return nextResult;
        }
    }

    /**
     * Deterministic token-draining stub for the per-group LLM bucket,
     * mirroring {@code GroupApprovalCheckTest.CountingBucket}: the
     * command-test package cannot see {@link RateCapBucket}'s
     * package-private test-seam constructors, so the override replaces
     * the real refill arithmetic with a plain counter.
     */
    static final class CountingLlmBucket extends RateCapBucket {
        int tokensLeft;

        CountingLlmBucket(int cap) {
            this.tokensLeft = cap;
        }

        @Override
        public boolean tryAcquireGroupLlm(UUID groupId) {
            if (tokensLeft > 0) {
                tokensLeft--;
                return true;
            }
            return false;
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

    private static DataSource stubDigestDataSource(UUID userId, boolean isAdmin, UUID groupId) {
        return stubDigestDataSource(userId, isAdmin, groupId, true);
    }

    /**
     * Stub DataSource for the --digest path. Handles:
     * 1. SELECT id, is_admin FROM users WHERE adapter = ? AND contact_id = ?
     * 2. SELECT id, digest_enabled FROM groups WHERE adapter = ? AND upstream_group_id = ?
     */
    private static DataSource stubDigestDataSource(UUID userId, boolean isAdmin, UUID groupId,
                                                  boolean digestEnabled) {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] { Connection.class },
                        (proxy, method, args) -> switch (method.getName()) {
                            case "prepareStatement" -> {
                                String sql = (String) args[0];
                                yield stubPreparedStatement(sql, userId, isAdmin, groupId, digestEnabled);
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
            String sql, UUID userId, boolean isAdmin, UUID groupId, boolean digestEnabled) {
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
                        if (isGroupQuery) yield groupResultSet(groupId, digestEnabled);
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

    private static ResultSet groupResultSet(UUID groupId, boolean digestEnabled) {
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
                    case "getBoolean" -> digestEnabled;
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
