package app.zcat.infochat.collector.eval;

import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Shared raw-JDBC transaction wrapper for pipeline stages that need
 * a multi-statement unit of work without JTA overhead. Follows the
 * {@code autoCommit=false} + explicit commit/rollback shape
 * established by the bootstrap loader.
 */
public final class TransactionHelper {

    @FunctionalInterface
    public interface TxBody {
        void run(@NonNull Connection conn) throws SQLException;
    }

    public static void inTransaction(@NonNull DataSource dataSource,
                                     @NonNull String context,
                                     @NonNull TxBody body) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                body.run(conn);
                conn.commit();
            } catch (RuntimeException | SQLException e) {
                conn.rollback();
                throw (e instanceof RuntimeException re)
                    ? re
                    : new IllegalStateException(context + ": transactional write failed", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                context + ": failed to acquire connection or rollback", e);
        }
    }

    private TransactionHelper() {}
}
