package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the recurring parked-set signal (M1-754): the summary
 * enumerates EVERY {@code status='failed'} source — all three park
 * reasons, NULL-reason pre-discriminator rows, and terminally
 * re-probe-capped rows — by UUID, reason and parked-since, never the
 * identifier URL (M1-023 INFO-LEAK), and stays silent when the set is
 * empty.
 */
@QuarkusTest
class ParkedSetSummaryIT {

    private static final String PREFIX = "m1-754-summary-";

    @Inject
    ParkedSetSummaryJob parkedSetSummaryJob;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @ConfigProperty(name = "infochat.fetch.reprobe.cap")
    int reprobeCap;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM admin_notification_state WHERE notification_key = ?",
                ParkedSetSummaryJob.ERROR_CLASS_PARKED_SET);
            exec(conn, "DELETE FROM source WHERE identifier LIKE ?",
                "https://example.com/" + PREFIX + "%");
        }
    }

    @Test
    void summaryCoversEveryReasonAndNeverTheIdentifier() throws Exception {
        UUID fetchFailure = seedParked("fetchFail", "fetch-failure", 1);
        UUID unknownRate = seedParked("unknownRate", "unknown-rate", 0);
        UUID cycleCap = seedParked("cycleCap", "stream-cycle-cap", 0);
        UUID nullReason = seedParked("nullReason", null, 0);
        UUID terminal = seedParked("terminal", "fetch-failure", reprobeCap);

        String summary = parkedSetSummaryJob.composeSummary();

        assertNotNull(summary, "a non-empty parked set must produce a summary");
        for (UUID id : new UUID[] {fetchFailure, unknownRate, cycleCap, nullReason, terminal}) {
            assertTrue(summary.contains(id.toString()),
                "the summary must enumerate every parked source by UUID — missing " + id);
        }
        assertTrue(summary.contains("fetch-failure"), "the summary must state the reason");
        assertTrue(summary.contains("unknown-rate"),
            "manual-only security parks must appear — they are recoverable only "
                + "by an operator who can see them");
        assertTrue(summary.contains("stream-cycle-cap"),
            "cycle-cap parks must appear");
        assertTrue(summary.contains("unrecorded"),
            "NULL-reason (pre-discriminator) rows must appear — they are precisely "
                + "the silently-dark corpus this signal exists for");
        assertFalse(summary.contains("example.com"),
            "the summary must NEVER carry the identifier URL (M1-023 INFO-LEAK)");
        assertFalse(summary.contains(PREFIX),
            "no fixture identifier fragment may leak into the summary");

        parkedSetSummaryJob.runOnce();
        assertTrue(throttledAdminNotifier.getState(
                ParkedSetSummaryJob.ERROR_CLASS_PARKED_SET).isPresent(),
            "runOnce must emit the summary through the throttled admin notifier");
    }

    @Test
    void silentWhenParkedSetIsEmpty() throws Exception {
        // Neutralize any parked rows other test classes left behind:
        // status='disabled' empties the parked set without violating the
        // post→source FK a DELETE would trip. Every test that needs a
        // failed row seeds its own in @BeforeEach.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE source SET status = 'disabled' WHERE status = 'failed'")) {
            ps.executeUpdate();
        }

        assertNull(parkedSetSummaryJob.composeSummary(),
            "an empty parked set must produce no summary");

        parkedSetSummaryJob.runOnce();
        assertTrue(throttledAdminNotifier.getState(
                ParkedSetSummaryJob.ERROR_CLASS_PARKED_SET).isEmpty(),
            "runOnce must stay silent when the parked set is empty");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedParked(String slug, String parkReason, int reprobeCount) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, park_reason, parked_at, reprobe_count) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'failed', ?, now(), ?) "
                     + "RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setString(3, parkReason);
            ps.setInt(4, reprobeCount);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private static void exec(Connection conn, String sql, String arg) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.executeUpdate();
        }
    }
}
