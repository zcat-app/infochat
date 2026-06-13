package app.zcat.infochat.provider.llm;

import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the per-occurrence LLM_OUTPUT_SANITIZED audit
 * row commitment from {@code docs/spec/security.md} §LLM output
 * sanitizer ("Every match is audit-logged (per-occurrence, not
 * throttled)").
 *
 * <p>Drives {@link LlmOutputSanitizer#sanitize} directly with a
 * seeded LLM-output string carrying two privileged-tier command
 * tokens; asserts EXACTLY two {@code audit_log} rows with
 * {@code action = 'LLM_OUTPUT_SANITIZED'} land in the database.
 * Acceptance item 13 — NOT one coalesced row per call.</p>
 *
 * <p>Per the outline risk #5 reasoning, the test invokes the
 * sanitizer directly rather than driving a full {@code /summary}
 * dispatch; the sanitizer's audit emission is independent of the
 * summary plumbing and the simpler shape isolates the
 * per-occurrence promise from unrelated test failures.</p>
 */
@QuarkusTest
class LlmOutputSanitizerAuditRowIT {

    /**
     * The closed-list token chosen to seed the test. Both
     * {@code /grant-admin} and {@code /ban} are explicit entries in
     * {@link LlmOutputSanitizer#CLOSED_LIST}. The same token appears
     * twice in the seeded input so the per-occurrence behavior
     * (two hits → two rows, not one) is the load-bearing assertion.
     */
    private static final String SEEDED_INPUT =
            "Admin would /grant-admin to ops; meanwhile /ban offender first.";

    @Inject
    LlmOutputSanitizer sanitizer;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    AuditLogWriter auditLogWriter;

    // V5 audit_log is append-only (trg_audit_log_no_delete trigger
    // raises on any DELETE), so cross-test isolation is via
    // before/after delta counts rather than table truncation. The
    // shared Quarkus test container accumulates rows across tests.

    @Test
    void twoHitsProduceExactlyTwoLlmOutputSanitizedRows() throws SQLException {
        long before = countLlmOutputSanitizedRows();
        String result = sanitizer.sanitize(SEEDED_INPUT);
        long after = countLlmOutputSanitizedRows();

        // Sanity check the rewrite (the test is primarily about the
        // audit count, but a broken sanitize() would also break this).
        assertTrue(result.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "sanitizer must replace both tokens; got: " + result);

        assertEquals(2L, after - before,
                "spec promises per-occurrence audit rows: two hits -> exactly two rows, not one coalesced row");
    }

    @Test
    void cdiInjectedSanitizerAlwaysAuditsWithoutManualWiring() throws SQLException {
        // U-64: the @Inject sanitizer is built by CDI with no manual
        // field-setting. A single closed-list hit must still land one
        // audit row — there is no no-audit seam reachable in the CDI
        // path (that seam is the no-arg test constructor only).
        long before = countLlmOutputSanitizedRows();
        sanitizer.sanitize("Run /ban now");
        long after = countLlmOutputSanitizedRows();
        assertEquals(1L, after - before,
                "a CDI-built sanitizer always emits the per-occurrence audit row; "
                        + "sanitization without audit wiring is impossible in the CDI path");
    }

    @Test
    void noHitsProduceNoAuditRow() throws SQLException {
        long before = countLlmOutputSanitizedRows();
        sanitizer.sanitize("No privileged tokens in this benign LLM output.");
        long after = countLlmOutputSanitizedRows();
        assertEquals(0L, after - before,
                "a sanitize() call with zero closed-list matches must write no audit row");
    }

    @Test
    void detailsJsonCarriesMatchKindNotLlmOutputText() throws SQLException {
        sanitizer.sanitize("/ban first");
        // ORDER BY id DESC LIMIT 1 picks the most recent
        // LLM_OUTPUT_SANITIZED row, which is the one this test just
        // wrote — robust against other tests in the same container
        // leaving prior rows behind.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT details_json::text FROM audit_log "
                             + "WHERE action = 'LLM_OUTPUT_SANITIZED' "
                             + "ORDER BY id DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "exactly one audit row expected");
            String detailsJson = rs.getString(1);
            assertTrue(detailsJson.contains("\"match_kind\""),
                    "details_json must carry match_kind: " + detailsJson);
            assertTrue(detailsJson.contains("/ban"),
                    "details_json must include the matched token: " + detailsJson);
            // The spec promise: "details_json carries the match-count +
            // match-kind enumeration WITHOUT the user-visible LLM
            // output text" — the sanitized prefix ("first") must NOT
            // appear in the audit row.
            assertTrue(!detailsJson.contains("first"),
                    "details_json must not carry surrounding LLM output text: " + detailsJson);
        }
    }

    @Test
    void auditWriteFailureAbortsSanitizeFailLoud() throws SQLException {
        // Spec §LLM output sanitizer: "Every match is audit-logged
        // (per-occurrence, not throttled)." The promise is durability;
        // a fail-soft branch that silently drops the row would leave
        // the user-visible reply emitted without the audit trail.
        // This test wires the sanitizer with a DataSource that throws
        // SQLException on getConnection() and asserts sanitize() bubbles
        // an IllegalStateException — the caller's response build aborts.
        LlmOutputSanitizer brokenSanitizer =
                new LlmOutputSanitizer(auditLogWriter, new ThrowingDataSource());

        long before = countLlmOutputSanitizedRows();
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> brokenSanitizer.sanitize("/grant-admin"),
                "audit-write failure must abort sanitize() per the spec's per-occurrence durability commitment");
        assertTrue(ex.getCause() instanceof SQLException,
                "IllegalStateException must wrap the underlying SQLException; got cause: " + ex.getCause());
        long after = countLlmOutputSanitizedRows();
        assertEquals(0L, after - before,
                "no audit row may persist when the audit-write path failed");
    }

    private long countLlmOutputSanitizedRows() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = 'LLM_OUTPUT_SANITIZED'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** DataSource whose getConnection() throws — simulates a degraded
     *  audit_log INSERT path (pool exhaustion, network blip, etc.). */
    private static final class ThrowingDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("simulated audit-write outage");
        }

        @Override
        public Connection getConnection(String u, String p) throws SQLException {
            return getConnection();
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger("test"); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
