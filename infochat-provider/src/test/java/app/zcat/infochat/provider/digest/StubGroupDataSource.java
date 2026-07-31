package app.zcat.infochat.provider.digest;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * JDBC stub for the group-metadata query ({@code DigestWorker.readGroupMetadata}).
 */
final class StubGroupDataSource implements DataSource {
    private final String adapter;
    private final String upstreamGroupId;
    private final String language;
    private final String digestMode;

    /** Defaults digest_mode to {@code normal} (M1-732), so pre-mode callers need no edit. */
    StubGroupDataSource(String adapter, String upstreamGroupId, String language) {
        this(adapter, upstreamGroupId, language, "normal");
    }

    StubGroupDataSource(String adapter, String upstreamGroupId, String language, String digestMode) {
        this.adapter = adapter;
        this.upstreamGroupId = upstreamGroupId;
        this.language = language;
        this.digestMode = digestMode;
    }

    @Override
    public Connection getConnection() {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> newPs();
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "Connection." + method.getName());
                });
    }

    private PreparedStatement newPs() {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setObject", "setString" -> null;
                    case "executeQuery" -> groupResultSet();
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "PreparedStatement." + method.getName());
                });
    }

    private ResultSet groupResultSet() {
        boolean[] consumed = {false};
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        if (consumed[0]) yield false;
                        consumed[0] = true;
                        yield true;
                    }
                    case "getString" -> {
                        String col = (String) args[0];
                        yield switch (col) {
                            case "adapter" -> adapter;
                            case "upstream_group_id" -> upstreamGroupId;
                            case "language" -> language;
                            case "digest_mode" -> digestMode;
                            default -> null;
                        };
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "ResultSet." + method.getName());
                });
    }

    @Override public Connection getConnection(String u, String p) { return getConnection(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) {}
    @Override public void setLoginTimeout(int s) {}
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
    @Override public <T> T unwrap(Class<T> i) { throw new UnsupportedOperationException(); }
    @Override public boolean isWrapperFor(Class<?> i) { return false; }
}
