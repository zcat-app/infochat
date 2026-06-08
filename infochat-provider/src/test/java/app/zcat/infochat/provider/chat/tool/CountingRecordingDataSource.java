package app.zcat.infochat.provider.chat.tool;

import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Wraps a real {@link DataSource}, counting {@code getConnection()} calls
 * and recording the SQL executed on each connection (both
 * {@code createStatement().execute/executeQuery} — e.g. the
 * {@code SET statement_timeout} and {@code SELECT pg_backend_pid()} the
 * {@code CancellationService.armToolConnection} arming step issues — and
 * {@code prepareStatement}). Every other call is delegated to the real
 * connection, so the tool's queries run for real.
 *
 * <p>Shared across the read-only chat-tool arming tests (getPost,
 * getReferences, recallMemory, listSaves) so the proxy boilerplate is
 * written once rather than duplicated per test.
 */
final class CountingRecordingDataSource implements DataSource {
    private final DataSource delegate;
    private int connectionCount;
    private final List<String> executedSql = new ArrayList<>();

    CountingRecordingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    int connectionCount() {
        return connectionCount;
    }

    List<String> executedSql() {
        return executedSql;
    }

    @Override
    public Connection getConnection() throws SQLException {
        connectionCount++;
        Connection real = delegate.getConnection();
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("prepareStatement") && args != null
                            && args.length > 0 && args[0] instanceof String sql) {
                        executedSql.add(sql);
                    } else if (name.equals("createStatement")) {
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
                    String name = method.getName();
                    if ((name.equals("execute") || name.equals("executeQuery"))
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
