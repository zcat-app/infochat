package app.zcat.infochat.collector.assets.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;

import app.zcat.infochat.collector.assets.PriceSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Persists one {@link PriceSnapshot} into {@code price_snapshot}
 * inside a {@code @Transactional} boundary. A duplicate
 * {@code (asset, sub_verb, vs_currency, captured_at)} is dropped by
 * {@code ON CONFLICT DO NOTHING} (the {@code price_snapshot_dedup_uq}
 * UNIQUE, widened by V51 to include {@code vs_currency} so the write
 * key matches the read key; spec schema.md §Operational "one row
 * per"). The table read is the sole
 * correctness path for the Provider's asset commands (spec
 * commands.md §Asset commands — Provider/Collector contract): the
 * Provider reads the latest row directly on every invocation, so
 * the write side does not signal it.
 *
 * Asset snapshots bypass the post outbox / Stage 1/2 / tagging /
 * embedding pipeline entirely (spec §Asset commands — "Data is not
 * posts"). This class is the only writer to {@code price_snapshot}.
 */
@ApplicationScoped
public class PriceSnapshotStore {

    // ON CONFLICT DO NOTHING enforces the spec's one-row-per-
    // (asset, sub_verb, vs_currency, captured_at) invariant against the
    // price_snapshot_dedup_uq UNIQUE (V51-widened to include vs_currency,
    // matching the read key): the table is INSERT-only (spec: "no
    // updates"), so a duplicate write is dropped, never updated.
    private static final String INSERT_SQL =
        "INSERT INTO price_snapshot ("
        + "  asset, sub_verb, vs_currency, price,"
        + "  volume_24h, high_24h, low_24h,"
        + "  change_1h_pct, change_24h_pct, change_7d_pct,"
        + "  captured_at, source_url, raw_payload"
        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::JSONB)"
        + " ON CONFLICT (asset, sub_verb, vs_currency, captured_at) DO NOTHING";

    @Inject
    DataSource dataSource;

    /**
     * Test-only seam: a Runnable invoked AFTER the INSERT succeeds.
     * Production code never sets this — the default no-op runs in
     * every production write. {@code PriceSnapshotStoreTest} uses it
     * to throw a RuntimeException inside the {@code @Transactional}
     * boundary to assert that the INSERT rolls back (mirrors
     * {@code ReadyPromoter.afterUpdateHook}). Package-private so
     * cross-package tests cannot reach in.
     */
    Runnable afterInsertHook = () -> {};

    @Transactional
    public void store(PriceSnapshot snapshot) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                ps.setString(1, snapshot.asset());
                ps.setString(2, snapshot.subVerb());
                ps.setString(3, snapshot.vsCurrency());
                ps.setBigDecimal(4, snapshot.price());
                setNullableBigDecimal(ps, 5, snapshot.volume24h());
                setNullableBigDecimal(ps, 6, snapshot.high24h());
                setNullableBigDecimal(ps, 7, snapshot.low24h());
                setNullableBigDecimal(ps, 8, snapshot.change1hPct());
                setNullableBigDecimal(ps, 9, snapshot.change24hPct());
                setNullableBigDecimal(ps, 10, snapshot.change7dPct());
                ps.setTimestamp(11, Timestamp.from(snapshot.capturedAt()));
                ps.setString(12, snapshot.sourceUrl());
                if (snapshot.rawPayload() == null) {
                    ps.setNull(13, Types.OTHER);
                } else {
                    ps.setString(13, snapshot.rawPayload());
                }
                ps.executeUpdate();
            }

            afterInsertHook.run();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "PriceSnapshotStore: INSERT failed for asset=" + snapshot.asset()
                + " sub_verb=" + snapshot.subVerb(), e);
        }
    }

    private static void setNullableBigDecimal(PreparedStatement ps, int idx, java.math.@Nullable BigDecimal v)
            throws SQLException {
        if (v == null) {
            ps.setNull(idx, Types.NUMERIC);
        } else {
            ps.setBigDecimal(idx, v);
        }
    }

}
