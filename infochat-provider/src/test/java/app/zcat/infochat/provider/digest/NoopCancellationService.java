package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.chat.CancellationService;

import java.sql.Connection;

/**
 * No-op {@link CancellationService} for JDBC-proxy unit tests: skips the
 * {@code SET statement_timeout} round-trip the real service issues (the
 * proxy-stubbed connections have no real session to configure).
 */
final class NoopCancellationService extends CancellationService {
    @Override
    public void applyStatementTimeout(Connection conn) {
        // intentionally empty
    }
}
