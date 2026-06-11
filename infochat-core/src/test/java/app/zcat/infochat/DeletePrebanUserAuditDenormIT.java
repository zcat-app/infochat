package app.zcat.infochat;

import app.zcat.infochat.core.schema.PostgresSchemaTestBase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-235 / deep-review v2.5 finding F1 — {@code delete_preban_user}
 * must denormalize the acting admin's contact id and adapter into its
 * {@code UNBAN_PREBAN_DELETE} audit row (docs/spec/schema.md
 * §Entities). Because the procedure DELETEs the target {@code users}
 * row, read-time derivation cannot recover the actor's identity as it
 * stood at write time, so the columns must be populated at write time
 * — matching the quarantine procedures restored in V32.
 *
 * <p>Actor and target are given deliberately distinct adapters and
 * contact ids: the assertion that the audit row carries the actor's
 * values (not the target's) would fail if the restored INSERT joined
 * the wrong row.
 */
class DeletePrebanUserAuditDenormIT extends PostgresSchemaTestBase {

    @Test
    void auditRowCarriesDenormalizedActorContactAndAdapter() throws SQLException {
        try (Connection c = newConnection()) {
            String actorId = insertUser(c, "signal", "admin-aci-123", true, "vouched");
            String prebanId = insertUser(c, "simplex", "preban-queue-9", false, "preban");

            try (PreparedStatement call = c.prepareStatement(
                    "CALL delete_preban_user(?::uuid, ?::uuid)")) {
                call.setString(1, prebanId);
                call.setString(2, actorId);
                call.execute();
            }

            try (PreparedStatement check = c.prepareStatement(
                    "SELECT actor_user_id, actor_contact_id, actor_adapter "
                            + "FROM audit_log "
                            + "WHERE action = 'UNBAN_PREBAN_DELETE' AND target_id = ?")) {
                check.setString(1, prebanId);
                try (var rs = check.executeQuery()) {
                    assertTrue(rs.next(), "expected one UNBAN_PREBAN_DELETE audit row");
                    assertEquals(actorId, rs.getString("actor_user_id"));
                    assertEquals("admin-aci-123", rs.getString("actor_contact_id"),
                            "actor_contact_id must be denormalized from the acting admin");
                    assertEquals("signal", rs.getString("actor_adapter"),
                            "actor_adapter must be denormalized from the acting admin");
                    assertFalse(rs.next(), "expected exactly one audit row");
                }
            }
        }
    }

    @Test
    void bannedAdminActorRejectedWithNonAdminErrorShape() throws SQLException {
        try (Connection c = newConnection()) {
            String bannedAdminId = insertBannedAdmin(c, "signal", "banned-admin-aci");
            String prebanId = insertUser(c, "simplex", "preban-queue-9", false, "preban");

            SQLException ex = assertThrows(SQLException.class, () -> {
                try (PreparedStatement call = c.prepareStatement(
                        "CALL delete_preban_user(?::uuid, ?::uuid)")) {
                    call.setString(1, prebanId);
                    call.setString(2, bannedAdminId);
                    call.execute();
                }
            });
            assertTrue(ex.getMessage().contains("actor is not a bot admin"),
                    "banned admin must be rejected with the same error a non-admin gets: "
                            + ex.getMessage());
        }
    }

    private static String insertBannedAdmin(Connection c, String adapter,
                                            String contactId) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO users (adapter, contact_id, is_admin, is_banned, registration_state) "
                        + "VALUES (?, ?, TRUE, TRUE, 'vouched') RETURNING id")) {
            insert.setString(1, adapter);
            insert.setString(2, contactId);
            try (var rs = insert.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static String insertUser(Connection c, String adapter, String contactId,
                                     boolean isAdmin, String registrationState) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                        + "VALUES (?, ?, ?, ?) RETURNING id")) {
            insert.setString(1, adapter);
            insert.setString(2, contactId);
            insert.setBoolean(3, isAdmin);
            insert.setString(4, registrationState);
            try (var rs = insert.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
