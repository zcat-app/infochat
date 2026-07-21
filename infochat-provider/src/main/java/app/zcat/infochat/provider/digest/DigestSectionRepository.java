package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC repository for the {@code digest_section} table (V61) — the persisted
 * render output that gap-filling {@code /retry --digest} replays. One row per
 * section in {@link DigestRenderer#renderSections} order; written at render
 * time alongside the {@code summary_cache} upsert, replayed byte-faithfully
 * by {@link DigestRetryService} when the slot has missing categories (M1-652
 * arm (b), decision D65).
 *
 * <p>The slot-replace operation ({@link #replaceSlotSections}) is atomic
 * across BOTH replay tables: it wipes this slot's prior sections AND delivery
 * records in one transaction before inserting the new section list, so a
 * regeneration never leaves stale delivery records that would suppress
 * categories the new render would re-send. "Replay never half-applies" is
 * a load-bearing property of the gap-fill design.
 *
 * <p>Slug derivation ({@link #slugOf}) is the single shared tag→slug rule
 * the delivery path and the persistence path must agree on; {@link DigestDelivery}
 * uses the identical rule, so the persisted {@code (group_id, window_start,
 * category_slug)} key and the per-message {@code correlationId} cannot drift.
 */
@ApplicationScoped
public class DigestSectionRepository {

    @Inject
    DataSource dataSource;

    /**
     * Atomically replace this slot's persisted sections AND delivery records,
     * then opportunistically prune expired rows for the group. A regeneration
     * produces new bytes, so every prior row tied to the old render (sections
     * AND delivery records) is wiped before the new section list is written —
     * all in one transaction so a partial commit cannot leave stale state
     * behind. The opportunistic prune keeps the two tables from growing
     * unboundedly across slots without a separate scheduled prune job.
     *
     * @param now decision-gate "now" from the injected {@link java.time.Clock}
     *            of the caller ({@link DigestWorker}); never an inline
     *            {@code Instant.now()} (CLAUDE.md §Injectable time in decision
     *            logic). Drives the prune comparison only.
     */
    public void replaceSlotSections(UUID groupId,
                                    Instant windowStart,
                                    List<RenderedSection> sections,
                                    Instant now) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                replaceSlotSections(conn, groupId, windowStart, sections);
                pruneExpiredForGroup(conn, groupId, now);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Connection-accepting variant for callers that compose this with other
     * writes in an enclosing transaction. Does not commit or roll back;
     * transaction control stays with the caller.
     */
    public void replaceSlotSections(Connection conn,
                                    UUID groupId,
                                    Instant windowStart,
                                    List<RenderedSection> sections) throws SQLException {
        // Wipe prior state in BOTH replay tables. A regeneration's bytes
        // diverge from the prior render, so the prior delivery records no
        // longer correspond to anything the new render would send — leaving
        // them would suppress categories in the next replay.
        try (PreparedStatement delSections = conn.prepareStatement(
                "DELETE FROM digest_section"
                        + " WHERE group_id = ? AND window_start = ?")) {
            delSections.setObject(1, groupId);
            delSections.setTimestamp(2, Timestamp.from(windowStart));
            delSections.executeUpdate();
        }
        try (PreparedStatement delDeliveries = conn.prepareStatement(
                "DELETE FROM digest_category_delivery"
                        + " WHERE group_id = ? AND window_start = ?")) {
            delDeliveries.setObject(1, groupId);
            delDeliveries.setTimestamp(2, Timestamp.from(windowStart));
            delDeliveries.executeUpdate();
        }
        if (sections.isEmpty()) {
            // A degraded or zero-post render persists no sections; the two
            // deletes above still ran so any prior slot state is gone.
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO digest_section"
                        + " (group_id, window_start, category_slug, position, content)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < sections.size(); i++) {
                RenderedSection s = sections.get(i);
                ps.setObject(1, groupId);
                ps.setTimestamp(2, Timestamp.from(windowStart));
                ps.setString(3, slugOf(s));
                ps.setInt(4, i);
                ps.setString(5, s.text());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Read this slot's persisted sections in {@code renderSections()} order
     * (position ascending). Empty for a slot that never produced sections
     * (degraded, zero-post, pre-V61 row, or crash-stranded cache row) —
     * {@link DigestRetryService} treats that as the fallback signal.
     */
    public List<RenderedSection> findOrderedSections(UUID groupId,
                                                     Instant windowStart) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT category_slug, content FROM digest_section"
                             + " WHERE group_id = ? AND window_start = ?"
                             + " ORDER BY position")) {
            ps.setObject(1, groupId);
            ps.setTimestamp(2, Timestamp.from(windowStart));
            List<RenderedSection> sections = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String slug = rs.getString("category_slug");
                    String tag = "other".equals(slug) ? null : slug;
                    sections.add(new RenderedSection(tag, rs.getString("content")));
                }
            }
            return sections;
        }
    }

    /**
     * Prune expired replay state for a group — rows whose slot's
     * {@code summary_cache.expires_at} is at or before {@code now}. Mirrors
     * the {@code summary_cache} retention rule exactly (a slot's sections
     * and delivery records live exactly as long as its cache row), so the
     * three cannot diverge. Manages its own transaction.
     *
     * @param now decision-gate "now" from the caller's injected Clock; never
     *            SQL {@code now()} (the comparison is a retention decision)
     */
    public int pruneExpiredForGroup(UUID groupId, Instant now) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int deleted = pruneExpiredForGroup(conn, groupId, now);
                conn.commit();
                return deleted;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Connection-accepting variant. Deletes from BOTH replay tables the rows
     * whose slot's {@code summary_cache} row has expired. Uses an INNER join
     * to {@code summary_cache}: a stranded section with no cache row is not
     * pruned here (and is not reachable by replay either, which resolves the
     * slot through the cache row first), mirroring the cache row's own
     * retention exactly.
     */
    public int pruneExpiredForGroup(Connection conn, UUID groupId, Instant now) throws SQLException {
        int deleted = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM digest_section"
                        + " WHERE group_id = ?"
                        + "   AND window_start IN ("
                        + "       SELECT slot_fired_at FROM summary_cache"
                        + "        WHERE group_id = ? AND expires_at <= ?)")) {
            ps.setObject(1, groupId);
            ps.setObject(2, groupId);
            ps.setTimestamp(3, Timestamp.from(now));
            deleted += ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM digest_category_delivery"
                        + " WHERE group_id = ?"
                        + "   AND window_start IN ("
                        + "       SELECT slot_fired_at FROM summary_cache"
                        + "        WHERE group_id = ? AND expires_at <= ?)")) {
            ps.setObject(1, groupId);
            ps.setObject(2, groupId);
            ps.setTimestamp(3, Timestamp.from(now));
            deleted += ps.executeUpdate();
        }
        return deleted;
    }

    /**
     * The shared tag→slug rule. {@code tag} is the section's controlled-
     * vocabulary string as-is; the null (Other) bucket maps to the literal
     * {@code "other"}. Identical to {@code DigestDelivery.java:81} so the
     * persisted {@code category_slug} and the per-message {@code correlationId}
     * cannot drift — this is the one place that rule is named.
     */
    static String slugOf(RenderedSection section) {
        return section.tag() != null ? section.tag() : "other";
    }
}
