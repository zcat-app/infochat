package app.zcat.infochat.collector.stream.nostr;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Test double: delegates to a real {@link DataSource} but every
 * connection it hands out throws {@link SQLException} when asked to
 * prepare an {@code INSERT INTO post_reference} statement — simulating
 * an edge-write failure in the middle of Kind6Handler's
 * post-plus-edge transaction. All other JDBC traffic (including the
 * post INSERT, rollback, and close) passes through untouched, so
 * TransactionHelper's rollback path runs against the live database.
 *
 * <p>The connection wrapper is a dynamic proxy rather than a hand-rolled
 * ~50-method delegating Connection: only {@code prepareStatement(String)}
 * carries test behavior.
 */
final class EdgeWriteFailingDataSource implements DataSource {

    private final DataSource delegate;

    EdgeWriteFailingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection real = delegate.getConnection();
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement")
                        && args != null && args.length > 0
                        && args[0] instanceof String sql
                        && sql.startsWith("INSERT INTO post_reference")) {
                    throw new SQLException("injected edge-write failure");
                }
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    throw cause != null ? cause : e;
                }
            });
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("test double");
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("test double");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
