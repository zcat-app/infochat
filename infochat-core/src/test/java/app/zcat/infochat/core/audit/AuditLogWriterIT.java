package app.zcat.infochat.core.audit;

import app.zcat.infochat.core.schema.PostgresSchemaTestBase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link AuditLogWriter} against a real
 * Postgres via the shared Testcontainers fixture. Exercises
 * acceptance item 12: (a) happy-path INSERT through the redaction
 * hook, (b) redaction hook is applied, (c) the writer commits in
 * the caller's transaction (caller rollback discards the audit
 * row).
 *
 * <p>The writer is constructed directly (no CDI runtime) with a
 * {@link DefaultRedactionHook} instance — the IT is a schema-tier
 * test, not a Quarkus test.</p>
 */
class AuditLogWriterIT extends PostgresSchemaTestBase {

    private final AuditLogWriter writer = new AuditLogWriter(new DefaultRedactionHook());

    @Test
    void happyPathRoundTripsRowThroughRedactionHook() throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .action(AuditAction.BOOTSTRAP_SOURCE_LOAD)
                .targetKind("system")
                .targetId("round-trip-target")
                .requestId("req-happy-1")
                .detailsJson("{\"path\":\"/tmp/bootstrap.json\"}")
                .build();

        try (Connection c = newConnection()) {
            c.setAutoCommit(true);
            writer.write(c, row);
        }

        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT action, target_kind, target_id, request_id, details_json::text "
                             + "FROM audit_log WHERE target_id = 'round-trip-target'")) {
            assertTrue(rs.next(), "audit row missing");
            assertEquals("BOOTSTRAP_SOURCE_LOAD", rs.getString("action"));
            assertEquals("system", rs.getString("target_kind"));
            assertEquals("round-trip-target", rs.getString("target_id"));
            assertEquals("req-happy-1", rs.getString("request_id"));
            assertTrue(rs.getString("details_json").contains("/tmp/bootstrap.json"));
        }
    }

    @Test
    void apiKeyShapeInDetailsJsonIsRedacted() throws SQLException {
        String openaiKey = "sk-AbCdEf0123456789AbCdEf0123456789";
        String detailsJson = "{\"api_key\":\"" + openaiKey + "\"}";

        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .action(AuditAction.LLM_OUTPUT_SANITIZED)
                .targetKind("system")
                .targetId("redaction-target")
                .requestId("req-redact-1")
                .detailsJson(detailsJson)
                .build();

        try (Connection c = newConnection()) {
            c.setAutoCommit(true);
            writer.write(c, row);
        }

        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT details_json::text FROM audit_log WHERE target_id = 'redaction-target'")) {
            assertTrue(rs.next(), "audit row missing");
            String stored = rs.getString(1);
            assertFalse(stored.contains(openaiKey),
                    "raw API key reached the database: " + stored);
            assertTrue(stored.contains(DefaultRedactionHook.REDACTED_PLACEHOLDER),
                    "redaction placeholder missing: " + stored);
        }
    }

    @Test
    void writerSharesCallerTransaction() throws SQLException {
        UUID actorId = seedOneActorUser();
        String targetId = "tx-rollback-target-" + UUID.randomUUID();

        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actorId)
                .actorContactId("actor-contact-id-1234567890")
                .actorAdapter("test-adapter")
                .action(AuditAction.BAN)
                .targetKind("user")
                .targetId(targetId)
                .targetContactId("target-contact-id-1234567890")
                .requestId("req-tx-1")
                .detailsJson("{}")
                .build();

        // Open a Connection with autoCommit=false, write the audit row,
        // then ROLLBACK before commit(). The audit row must NOT survive.
        try (Connection c = newConnection()) {
            c.setAutoCommit(false);
            writer.write(c, row);
            c.rollback();
        }

        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM audit_log WHERE target_id = '" + targetId + "'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1),
                    "rolled-back audit row leaked past the caller's transaction");
        }
    }

    @Test
    void writerCommitsWhenCallerCommits() throws SQLException {
        UUID actorId = seedOneActorUser();
        String targetId = "tx-commit-target-" + UUID.randomUUID();

        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actorId)
                .actorContactId("actor-contact-id-1234567890")
                .actorAdapter("test-adapter")
                .action(AuditAction.BAN)
                .targetKind("user")
                .targetId(targetId)
                .targetContactId("target-contact-id-1234567890")
                .requestId("req-tx-commit-1")
                .detailsJson("{}")
                .build();

        try (Connection c = newConnection()) {
            c.setAutoCommit(false);
            writer.write(c, row);
            c.commit();
        }

        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM audit_log WHERE target_id = '" + targetId + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void watchdogFallbackIsValidJsonb() throws SQLException {
        // Pins that DefaultRedactionHook.REDACTED_FIELD_JSONB — what the
        // hook returns when the regex watchdog fires — is accepted by
        // Postgres' ?::jsonb cast in AuditLogWriter. If the fallback
        // were a bare string like "[REDACTED]" the cast would fail and
        // the surrounding dispatch transaction would roll back, taking
        // the admin action with it.
        String targetId = "watchdog-jsonb-target-" + UUID.randomUUID();
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .action(AuditAction.LLM_OUTPUT_SANITIZED)
                .targetKind("system")
                .targetId(targetId)
                .requestId("req-watchdog-1")
                .detailsJson(DefaultRedactionHook.REDACTED_FIELD_JSONB)
                .build();

        try (Connection c = newConnection()) {
            c.setAutoCommit(true);
            writer.write(c, row);
        }

        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT details_json::text FROM audit_log WHERE target_id = '" + targetId + "'")) {
            assertTrue(rs.next(), "watchdog-fallback row missing — JSONB cast failed");
            String stored = rs.getString(1);
            // Postgres JSONB normalization may reorder keys; assert
            // structural content, not literal byte equality.
            assertTrue(stored.contains("\"_redacted\""),
                    "_redacted key missing after JSONB round-trip: " + stored);
            assertTrue(stored.contains("regex_watchdog_timeout"),
                    "reason value missing after JSONB round-trip: " + stored);
        }
    }

    @Test
    void redactedJsonCastsSuccessfully() throws SQLException {
        String value = "A".repeat(64);
        String detailsJson = "{\"token\":\"" + value + "\"}";
        String targetId = "generic-redact-target-" + UUID.randomUUID();

        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .action(AuditAction.LLM_OUTPUT_SANITIZED)
                .targetKind("system")
                .targetId(targetId)
                .requestId("req-generic-redact-1")
                .detailsJson(detailsJson)
                .build();

        try (Connection c = newConnection()) {
            c.setAutoCommit(true);
            writer.write(c, row);
        }

        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT details_json::text FROM audit_log WHERE target_id = '" + targetId + "'")) {
            assertTrue(rs.next(), "audit row missing — INSERT or JSONB cast failed");
            String stored = rs.getString(1);
            assertFalse(stored.contains(value),
                    "raw secret value reached the database: " + stored);
            assertTrue(stored.contains("\"token\""),
                    "keyword was consumed by redaction: " + stored);
            assertTrue(stored.contains(DefaultRedactionHook.REDACTED_PLACEHOLDER),
                    "redaction placeholder missing: " + stored);
        }
    }

    private UUID seedOneActorUser() throws SQLException {
        try (Connection c = newConnection();
             var ps = c.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES ('test-adapter', ?, TRUE, 'vouched') RETURNING id")) {
            ps.setString(1, "actor-" + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }
}
