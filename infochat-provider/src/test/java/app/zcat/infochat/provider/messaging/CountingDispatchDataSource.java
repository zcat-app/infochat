package app.zcat.infochat.provider.messaging;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Fully fake JDBC stack for {@link InboundRouterAcquisitionCountTest}:
 * counts pool acquisitions and tracks the open-connection balance while
 * serving canned rows for the router-owned dispatch statements —
 * the users-row snapshot (a vouched, non-banned actor), the
 * scope-language read (no row → en), and the membership upsert. The
 * group's id is no longer read here: it is carried from the step-3.5
 * approval outcome, so the router issues no {@code FROM groups} query.
 */
final class CountingDispatchDataSource implements DataSource {

    private final UUID actorId;
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicInteger openConnections = new AtomicInteger();
    private final List<String> executedSql = new ArrayList<>();

    CountingDispatchDataSource(UUID actorId) {
        this.actorId = actorId;
    }

    int connectionCount() {
        return connectionCount.get();
    }

    int openConnections() {
        return openConnections.get();
    }

    List<String> executedSql() {
        return executedSql;
    }

    @Override
    public Connection getConnection() {
        connectionCount.incrementAndGet();
        openConnections.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> preparedStatement((String) args[0]);
                    case "close" -> {
                        openConnections.decrementAndGet();
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(
                            "Connection." + method.getName());
                });
    }

    private PreparedStatement preparedStatement(String sql) {
        executedSql.add(sql);
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setObject" -> null;
                    case "executeQuery" -> resultSetFor(sql);
                    case "executeUpdate" -> 1;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "PreparedStatement." + method.getName());
                });
    }

    private ResultSet resultSetFor(String sql) {
        if (sql.contains("FROM users")) {
            return userSnapshotRow();
        }
        if (sql.contains("FROM scope_preferences")) {
            // No preferences row — InboundRouter.lookupScopeLanguage
            // maps the empty result to "en".
            return emptyResultSet();
        }
        throw new UnsupportedOperationException("query: " + sql);
    }

    // Columns read by InboundRouter.lookupUser: id, registration_state, is_banned.
    private ResultSet userSnapshotRow() {
        return singleRowResultSet(name -> switch (name) {
            case "getObject" -> actorId;
            case "getString" -> "vouched";
            case "getBoolean" -> false;
            default -> throw new UnsupportedOperationException("ResultSet." + name);
        });
    }

    private static ResultSet emptyResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> false;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "ResultSet." + method.getName());
                });
    }

    private static ResultSet singleRowResultSet(ColumnReader reader) {
        boolean[] advanced = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        if (advanced[0]) yield false;
                        advanced[0] = true;
                        yield true;
                    }
                    case "close" -> null;
                    default -> reader.read(method.getName());
                });
    }

    @FunctionalInterface
    private interface ColumnReader {
        Object read(String methodName);
    }

    @Override public Connection getConnection(String u, String p) { return getConnection(); }
    @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
    @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
    @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
    @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
