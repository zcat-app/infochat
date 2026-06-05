package app.zcat.infochat.provider.command;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Base implementation of the {@link DataSource} surface that
 * throws {@link UnsupportedOperationException} for everything
 * except {@link #getConnection()}. Test subclasses override
 * {@code getConnection()} to return a stubbed {@link Connection}.
 * Mockito is intentionally absent from the Provider classpath —
 * this base keeps the JDBC-surface boilerplate localized.
 */
class UnsupportedDataSource implements DataSource {
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
