package app.zcat.infochat.provider.summary;

import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies acceptance item 2's /summary leg: the read-only queries
 * {@link EligiblePostQuery#fetch} runs for {@code /summary} execute under the
 * profile-driven {@code statement_timeout} (commands.md §Conversation control
 * — "every interruptible read-only query (chat-mode tool calls, on-demand
 * /summary) runs under a profile-driven statement_timeout"). The timeout is
 * applied inside {@code EligiblePostQuery}, where the connection lives
 * (mirroring {@code RetryCommandHandler}); this test wraps the seed
 * {@link DataSource} to observe the {@code SET LOCAL statement_timeout} on the
 * connections {@code fetch()} opens.
 */
@QuarkusTest
class EligiblePostQueryStatementTimeoutTest {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    CancellationService cancellationService;

    @Test
    void summaryQueryConnectionsRunUnderStatementTimeout() {
        RecordingDataSource recordingDataSource = new RecordingDataSource(dataSource);
        EligiblePostQuery query = new EligiblePostQuery();
        query.dataSource = recordingDataSource;
        query.cancellationService = cancellationService;
        query.clusterCap = 200;
        query.profileLabel = "laptop";

        // A fresh random scope has no subscriptions/posts, so fetch returns
        // an empty result — but it still opens the (timed) connections for
        // countFollowedTags / readTagMode / selectPosts on the way there.
        UUID scopeId = UUID.randomUUID();
        EligiblePostQuery.Result result =
                query.fetch("dm", scopeId, Optional.empty(), Duration.ofDays(7));

        assertTrue(result.posts().isEmpty(),
                "empty scope must yield no eligible posts");
        assertTrue(recordingDataSource.executedSql().stream()
                        .anyMatch(s -> s.contains("SET LOCAL statement_timeout")),
                "EligiblePostQuery's /summary connections must run under "
                        + "statement_timeout. Got: " + recordingDataSource.executedSql());
    }

    /**
     * Wraps a real {@link DataSource} and records the SQL run via
     * {@code createStatement().execute(...)} on each connection (where the
     * {@code SET LOCAL statement_timeout} lands), delegating every other call to the
     * real connection so {@code fetch()}'s queries run against the seed DB.
     */
    static final class RecordingDataSource implements DataSource {
        private final DataSource delegate;
        private final List<String> executedSql = new ArrayList<>();

        RecordingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        List<String> executedSql() {
            return executedSql;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, args) -> {
                        if (method.getName().equals("createStatement")) {
                            return wrapStatement(
                                    (Statement) Objects.requireNonNull(invoke(real, method, args)));
                        }
                        return invoke(real, method, args);
                    });
        }

        private Statement wrapStatement(Statement realStmt) {
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class<?>[] { Statement.class },
                    (proxy, method, args) -> {
                        if (method.getName().equals("execute")
                                && args != null && args.length > 0 && args[0] instanceof String sql) {
                            executedSql.add(sql);
                        }
                        return invoke(realStmt, method, args);
                    });
        }

        private static @Nullable Object invoke(Object target, Method method,
                                               Object @Nullable [] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw cause != null ? cause : e;
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }
}
