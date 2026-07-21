package app.zcat.infochat.provider.digest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC repository for the {@code digest_category_delivery} table (V61) — the
 * per-(slot, category) delivery record that gap-filling {@code /retry --digest}
 * reads to compute which categories still need sending. A row exists iff the
 * adapter accepted that category's message (recorded by the delegating
 * {@link MessagingAdapter} wrapper inside {@link DigestDelivery} on a normal
 * return from {@code send()}); a failed send records nothing, so the existing
 * per-category TRANSIENT/PERMANENT ladder is unchanged (M1-652, decision D65).
 *
 * <p>Recording is idempotent ({@link #recordDelivery} uses {@code ON CONFLICT
 * DO NOTHING}): the scheduled-vs-replay race D64's at-least-once already
 * permits means a category may be delivered twice, and the second recording
 * must not error. The {@code delivered_at} write is record-only — it logs
 * when the adapter accepted, it gates nothing — so it stays on SQL
 * {@code now()} rather than the injected {@link java.time.Clock} the replay
 * decision gates use (CLAUDE.md §Injectable time in decision logic — the
 * display/record exemption).
 *
 * <p>The slot-level prune ({@link DigestSectionRepository#pruneExpiredForGroup})
 * covers both replay tables in one transaction; this repo exposes no separate
 * prune because the two tables MUST be pruned atomically (a divergent
 * section/delivery retention is exactly the half-state the design rules out).
 */
@ApplicationScoped
public class DigestCategoryDeliveryRepository {

    @Inject
    DataSource dataSource;

    /**
     * Record that a category was delivered — called by the delegating
     * {@code MessagingAdapter.send()} wrapper inside {@link DigestDelivery}
     * on a normal return (the adapter accepted the message). A retried-
     * then-successful send throws on failed attempts and returns once, so
     * this records exactly once per delivered message. Idempotent on the
     * (group_id, window_start, category_slug) PK via {@code ON CONFLICT DO
     * NOTHING}: a duplicate record (the scheduled-vs-replay race D64
     * permits) is a silent no-op, not an error.
     *
     * @param windowStart the digest slot's {@code window_start} (joins
     *                    {@code summary_cache.slot_fired_at}); part of the
     *                    PK, so the slug+slot tuple identifies one delivery
     * @param categorySlug the section's slug — identical to the
     *                     {@code correlationId} component {@link DigestDelivery}
     *                     mints, never derived independently here
     */
    public void recordDelivery(UUID groupId,
                               Instant windowStart,
                               String categorySlug) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO digest_category_delivery"
                             + " (group_id, window_start, category_slug, delivered_at)"
                             + " VALUES (?, ?, ?, now())"
                             + " ON CONFLICT (group_id, window_start, category_slug)"
                             + " DO NOTHING")) {
            ps.setObject(1, groupId);
            ps.setTimestamp(2, Timestamp.from(windowStart));
            ps.setString(3, categorySlug);
            ps.executeUpdate();
        }
    }

    /**
     * Read the set of category slugs already delivered for this slot.
     * {@link DigestRetryService} subtracts this from the persisted sections
     * to compute the missing set; an empty set means "replay everything"
     * (the first attempt recorded nothing, e.g. it crashed before any
     * delivery landed).
     */
    public Set<String> findDeliveredSlugs(UUID groupId,
                                          Instant windowStart) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT category_slug FROM digest_category_delivery"
                             + " WHERE group_id = ? AND window_start = ?")) {
            ps.setObject(1, groupId);
            ps.setTimestamp(2, Timestamp.from(windowStart));
            Set<String> slugs = new HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    slugs.add(rs.getString("category_slug"));
                }
            }
            return slugs;
        }
    }
}
