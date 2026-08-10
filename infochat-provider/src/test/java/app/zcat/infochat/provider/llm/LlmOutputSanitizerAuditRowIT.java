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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the aggregated LLM_OUTPUT_SANITIZED audit
 * row commitment from {@code docs/spec/security.md} §LLM output
 * sanitizer ("Every match is audit-logged; rows aggregate per
 * distinct token per sanitize call and carry the exact occurrence
 * count — counted, never throttled").
 *
 * <p>Drives {@link LlmOutputSanitizer#sanitize} directly with a
 * seeded LLM-output string carrying two DISTINCT privileged-tier
 * command tokens; asserts EXACTLY two {@code audit_log} rows with
 * {@code action = 'LLM_OUTPUT_SANITIZED'} land in the database —
 * one per distinct token (aggregation coalesces only same-token
 * repeats). Acceptance item 13 — NOT one coalesced row per call.</p>
 *
 * <p>Per the outline risk #5 reasoning, the test invokes the
 * sanitizer directly rather than driving a full {@code /summary}
 * dispatch; the sanitizer's audit emission is independent of the
 * summary plumbing and the simpler shape isolates the
 * per-token promise from unrelated test failures.</p>
 */
@QuarkusTest
class LlmOutputSanitizerAuditRowIT {

    /**
     * The closed-list tokens chosen to seed the test. Both
     * {@code /grant-admin} and {@code /ban} are explicit entries in
     * {@link LlmOutputSanitizer#CLOSED_LIST}. Two DISTINCT tokens
     * appear in the seeded input so the one-row-per-distinct-token
     * behavior (two tokens → two rows, not one coalesced row) is the
     * load-bearing assertion.
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
                "spec promises one audit row per distinct token: two distinct tokens -> exactly two rows, not one coalesced row");
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
                "a CDI-built sanitizer always emits the audit row; "
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
        // ... counted, never throttled." The promise is durability;
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
                "audit-write failure must abort sanitize() per the spec's durability commitment");
        assertTrue(ex.getCause() instanceof SQLException,
                "IllegalStateException must wrap the underlying SQLException; got cause: " + ex.getCause());
        long after = countLlmOutputSanitizedRows();
        assertEquals(0L, after - before,
                "no audit row may persist when the audit-write path failed");
    }

    @Test
    void auditRowStillLandsWhenTheCallingVirtualThreadIsInterrupted() throws Exception {
        // M1-763. DigestWorker cancels a digest render that overruns its slot
        // window by interrupting the render's virtual thread; the render then
        // goes on to sanitize prose it had already generated before the
        // interrupt landed (DigestRenderer batches every prose call, then
        // sanitizes in a later loop). On a virtual thread an armed interrupt
        // flag makes JDBC socket I/O fail with "Closed by interrupt", so
        // without the park-and-restore in emitAuditRows this sanitize() throws
        // and the LLM_OUTPUT_SANITIZED row is silently lost — exactly the
        // detection signal the spec's "counted, never throttled" promise
        // exists to guarantee.
        //
        // The thread MUST be virtual. An interrupted PLATFORM thread completes
        // socket I/O normally, so this same test run on the JUnit thread
        // passes against the unfixed sanitizer and proves nothing.
        long before = countLlmOutputSanitizedRows();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptSurvived = new AtomicBoolean();

        Thread cancelledRender = Thread.ofVirtual().unstarted(() -> {
            Thread.currentThread().interrupt();   // the state DigestWorker leaves behind
            try {
                sanitizer.sanitize("Run /ban now");
                interruptSurvived.set(Thread.currentThread().isInterrupted());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        cancelledRender.start();
        cancelledRender.join(30_000);

        assertNull(failure.get(),
                "the audit write must survive an already-interrupted caller; instead: "
                        + failure.get());
        assertEquals(1L, countLlmOutputSanitizedRows() - before,
                "a closed-list hit found by a cancelled render must still be audited — "
                        + "the durability promise is not conditional on the caller's "
                        + "interrupt state");
        assertTrue(interruptSurvived.get(),
                "the interrupt must still be armed when sanitize() returns, or the "
                        + "cancelled render resumes full-speed LLM calls and the cancel "
                        + "stops costing anything");
    }

    @Test
    void aCommandInsideAMarkerIdStillProducesARow() throws SQLException {
        // The marker id class excludes '/', so this line is not a marker:
        // it falls through to the closed-list pass and is redacted AND
        // rowed, not silently deleted with the marker.
        long before = countLlmOutputSanitizedRows();
        String result = sanitizer.sanitize("<<<END id=\"/grant-admin\">>>");
        long after = countLlmOutputSanitizedRows();

        assertFalse(result.contains("/grant-admin"),
                "the command word must not survive; got: " + result);
        assertTrue(result.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "the token must be redacted, not deleted with the marker; got: " + result);
        assertEquals(1L, after - before,
                "a command inside a marker id still produces exactly one audit row");
    }

    @Test
    void aConfigKeyMatchRowsWithItsExactCountAlongsideClosedListMatches() throws SQLException {
        // M1-815: config-key matches join the ONE audit pipeline — one
        // row per distinct token, exact count, aggregated with the
        // closed-list matches of the same call.
        long before = countLlmOutputSanitizedRows();
        String result = sanitizer.sanitize(
                "The infochat.probation.duration window applies — "
                        + "infochat.probation.duration again. Run /ban now.");
        long after = countLlmOutputSanitizedRows();

        assertFalse(result.contains("infochat."),
                "the config token must not survive; got: " + result);
        assertTrue(result.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "the closed-list token still redacts; got: " + result);
        assertEquals(2L, after - before,
                "one row per distinct token — the config token and /ban — in one call");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT details_json::text FROM audit_log "
                             + "WHERE action = 'LLM_OUTPUT_SANITIZED' "
                             + "ORDER BY id DESC LIMIT 2");
             ResultSet rs = ps.executeQuery()) {
            List<String> jsons = new ArrayList<>();
            while (rs.next()) {
                jsons.add(rs.getString(1));
            }
            assertEquals(2, jsons.size(), "the two most recent rows are this call's");
            // details_json is JSONB: Postgres re-renders it (key order,
            // spacing), so match the fields format-tolerantly.
            String configJson = jsons.stream()
                    .filter(json -> json.contains("infochat.probation.duration"))
                    .findFirst().orElse(null);
            assertNotNull(configJson, "one row must name the config token; got: " + jsons);
            assertTrue(configJson.matches(".*\"match_count\":\\s*2.*"),
                    "the config row must carry the exact occurrence count 2; got: " + configJson);
            String banJson = jsons.stream()
                    .filter(json -> json.contains("/ban"))
                    .findFirst().orElse(null);
            assertNotNull(banJson, "one row must name /ban; got: " + jsons);
            assertTrue(banJson.matches(".*\"match_count\":\\s*1.*"),
                    "the /ban row must carry the exact occurrence count 1; got: " + banJson);
        }
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
