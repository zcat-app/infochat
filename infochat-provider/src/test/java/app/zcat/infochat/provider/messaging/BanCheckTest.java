package app.zcat.infochat.provider.messaging;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link BanCheck} against the DevServices
 * Postgres container (Flyway-applied V5 users table). Three
 * invariants are pinned, each in its own {@code @Test}:
 *
 * <ol>
 *   <li>A seeded row with {@code is_banned=TRUE} returns
 *       {@code true}.</li>
 *   <li>A seeded row with {@code is_banned=FALSE} returns
 *       {@code false}.</li>
 *   <li>An unknown {@code (adapter, contact_id)} returns
 *       {@code false} (fail-closed: caller falls through to the
 *       invite gate).</li>
 * </ol>
 */
@QuarkusTest
class BanCheckTest {

    @Inject
    BanCheck banCheck;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void cleanTestContacts() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM users WHERE contact_id LIKE 'ban-test-%'")) {
            ps.executeUpdate();
        }
    }

    @Test
    void bannedRow() throws Exception {
        seedUser("inmemory", "ban-test-banned", true);
        assertTrue(banCheck.isBanned("inmemory", "ban-test-banned"),
                "isBanned must return true for a seeded is_banned=TRUE row");
    }

    @Test
    void unbannedRow() throws Exception {
        seedUser("inmemory", "ban-test-unbanned", false);
        assertFalse(banCheck.isBanned("inmemory", "ban-test-unbanned"),
                "isBanned must return false for a seeded is_banned=FALSE row");
    }

    @Test
    void unknownContact() {
        // Absent row → false. The fail-closed shape: the caller falls
        // through to step 2's invite gate; the ban gate does not invent
        // a synthetic users row for an unknown contact.
        assertFalse(banCheck.isBanned("inmemory", "ban-test-nonexistent"),
                "isBanned must return false for an unknown (adapter, contact_id)");
    }

    private void seedUser(String adapter, String contactId, boolean isBanned) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, registration_state) "
                             + "VALUES (?, ?, FALSE, ?, 'invited')")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            ps.setBoolean(3, isBanned);
            ps.executeUpdate();
        }
    }
}
