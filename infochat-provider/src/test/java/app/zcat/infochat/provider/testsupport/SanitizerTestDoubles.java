package app.zcat.infochat.provider.testsupport;

import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.logging.Logger;

/**
 * Test doubles for building an {@link LlmOutputSanitizer} in plain unit
 * tests that use it as a pass-through collaborator (digest/summary/cluster
 * renderers, command handlers, chat agent).
 *
 * <p>M1-363 removed the sanitizer's no-arg test-seam constructor: audit
 * emission is now structural, so {@code sanitize()} always emits a row per
 * closed-list hit and needs both an {@link AuditLogWriter} and a
 * {@link DataSource}. These doubles make that emission a no-op — the writer
 * discards the row and the DataSource yields a Connection whose transaction
 * methods do nothing — so {@code sanitize()} returns the rewritten text
 * without touching a database, regardless of whether the input contains a
 * closed-list token. The real per-occurrence audit emission is covered
 * against a live DataSource by {@code LlmOutputSanitizerAuditRowIT}.
 */
public final class SanitizerTestDoubles {

    private SanitizerTestDoubles() {}

    /** A sanitizer whose audit emission is a no-op (no database required). */
    public static LlmOutputSanitizer noAuditSanitizer() {
        return new LlmOutputSanitizer(noOpAuditLogWriter(), noOpDataSource());
    }

    /** An {@link AuditLogWriter} whose {@code write} discards the row. */
    public static AuditLogWriter noOpAuditLogWriter() {
        return new AuditLogWriter(row -> row) {
            @Override
            public void write(Connection conn, RedactionHook.AuditRow row) {
                // no-op: these unit tests assert on the rewritten reply text,
                // not on the audit trail (which AuditRowIT covers end-to-end).
            }
        };
    }

    /**
     * A {@link DataSource} yielding a Connection whose transaction methods
     * ({@code setAutoCommit}/{@code commit}/{@code rollback}/{@code close})
     * are no-ops, so the sanitizer's audit transaction completes without a
     * database. Any other Connection method is unexpected on this path and
     * throws, which keeps the double honest if the emit path ever changes.
     */
    public static DataSource noOpDataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] { Connection.class },
                        (proxy, method, args) -> switch (method.getName()) {
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
            @Override public Logger getParentLogger() { throw new UnsupportedOperationException(); }
            @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}
