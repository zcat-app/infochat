package io.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Invariant 3 — at most one group admin per group. Encoded as a
 * partial unique index on {@code group_membership(group_id) WHERE
 * is_group_admin = TRUE}. Attempting to INSERT a second
 * {@code is_group_admin = TRUE} row for the same group raises a
 * unique-violation (Postgres SQLState 23505).
 */
class GroupAdminUniqueIndexTest extends PostgresSchemaTestBase {

    @Test
    void secondGroupAdminRaisesUniqueViolation() throws SQLException {
        try (Connection c = newConnection()) {
            String groupId;
            try (PreparedStatement insertGroup = c.prepareStatement(
                    "INSERT INTO groups (adapter, upstream_group_id) VALUES (?, ?) RETURNING id")) {
                insertGroup.setString(1, "inmemory");
                insertGroup.setString(2, "group-1");
                try (var rs = insertGroup.executeQuery()) {
                    rs.next();
                    groupId = rs.getString(1);
                }
            }

            String userA = insertUser(c, "alice@example");
            String userB = insertUser(c, "bob@example");

            try (PreparedStatement insertMembership = c.prepareStatement(
                    "INSERT INTO group_membership (group_id, user_id, is_group_admin) "
                            + "VALUES (?::uuid, ?::uuid, TRUE)")) {
                insertMembership.setString(1, groupId);
                insertMembership.setString(2, userA);
                insertMembership.executeUpdate();
            }

            SQLException ex;
            try (PreparedStatement insertSecondAdmin = c.prepareStatement(
                    "INSERT INTO group_membership (group_id, user_id, is_group_admin) "
                            + "VALUES (?::uuid, ?::uuid, TRUE)")) {
                insertSecondAdmin.setString(1, groupId);
                insertSecondAdmin.setString(2, userB);
                ex = assertThrows(SQLException.class, insertSecondAdmin::executeUpdate);
            }
            assertEquals("23505", ex.getSQLState(),
                    "expected unique_violation (23505), got: " + ex.getSQLState()
                            + " message: " + ex.getMessage());
        }
    }

    private static String insertUser(Connection c, String contactId) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO users (adapter, contact_id, registration_state) "
                        + "VALUES (?, ?, 'vouched') RETURNING id")) {
            insert.setString(1, "inmemory");
            insert.setString(2, contactId);
            try (var rs = insert.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
