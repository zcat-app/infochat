package app.zcat.infochat.provider.chat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-shape verification for the V42 migration: the duplicate
 * idx_chat_message_session_seq (V18, column-for-column identical to the
 * chat_message PRIMARY KEY index) no longer exists after Flyway has
 * migrated the test database, while the primary key itself remains.
 * Same pattern as D47MigrationIT: assertions run against the
 * boot-migrated test datasource, no programmatic Flyway reconfiguration.
 */
@QuarkusTest
class ChatMessageSeqIndexDropIT {

    @Inject @SeedDataSource DataSource dataSource;

    @Test
    void duplicateSeqIndexNoLongerExistsAfterMigration() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM pg_indexes "
                   + "WHERE tablename = 'chat_message' "
                   + "AND indexname = 'idx_chat_message_session_seq'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(0, rs.getInt(1),
                    "V42 must drop idx_chat_message_session_seq");
        }
    }

    @Test
    void chatMessagePrimaryKeyRemains() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT indexdef FROM pg_indexes "
                   + "WHERE tablename = 'chat_message' "
                   + "AND indexname = 'chat_message_pkey'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "chat_message primary key index must remain");
            String indexdef = rs.getString("indexdef");
            assertTrue(indexdef.contains("(user_id, scope_kind, scope_id, seq)"),
                    "PK must keep its column list, got: " + indexdef);
        }
    }
}
