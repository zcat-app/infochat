package app.zcat.infochat.collector.assets.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;

import app.zcat.infochat.collector.assets.PriceSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Persists one {@link PriceSnapshot} into {@code price_snapshot} and
 * emits {@code NOTIFY new_price_snapshot} on the same JDBC connection
 * inside the same {@code @Transactional} boundary. The INSERT and
 * the NOTIFY commit together; a rollback suppresses the NOTIFY
 * (Postgres semantics: NOTIFY fires at COMMIT, not at statement
 * execution). Mirrors {@code ReadyPromoter.promoteOne} for the
 * INSERT-then-NOTIFY pattern.
 *
 * The NOTIFY payload is the spec-committed
 * {@code {"asset":"<asset>","source":"<sub_verb>"}} JSON shape per
 * docs/spec/commands.md §Asset commands — Provider/Collector contract.
 * The key {@code source} (NOT {@code sub_verb}) is load-bearing —
 * {@code AssetSnapshotReader} (M1-055c) deserialises by that key.
 *
 * Asset snapshots bypass the post outbox / Stage 1/2 / tagging /
 * embedding pipeline entirely (spec §Asset commands — "Data is not
 * posts"). This class is the only writer to {@code price_snapshot}.
 */
@ApplicationScoped
public class PriceSnapshotStore {

    /** NOTIFY channel name; M1-055c's listener subscribes to this. */
    public static final String NEW_PRICE_SNAPSHOT_CHANNEL = "new_price_snapshot";

    private static final String INSERT_SQL =
        "INSERT INTO price_snapshot ("
        + "  asset, sub_verb, vs_currency, price,"
        + "  volume_24h, high_24h, low_24h,"
        + "  change_1h_pct, change_24h_pct, change_7d_pct,"
        + "  captured_at, source_url, raw_payload"
        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::JSONB)";

    @Inject
    DataSource dataSource;

    /**
     * Test-only seam: a Runnable invoked AFTER the INSERT succeeds
     * but BEFORE the {@code pg_notify} statement. Production code
     * never sets this — the default no-op runs in every production
     * write. {@code PriceSnapshotStoreTest} uses it to throw a
     * RuntimeException inside the {@code @Transactional} boundary
     * to assert that the INSERT rolls back AND no NOTIFY is delivered
     * (mirrors {@code ReadyPromoter.afterUpdateHook}). Package-private
     * so cross-package tests cannot reach in.
     */
    Runnable afterInsertHook = () -> {};

    @Transactional
    public void store(@NonNull PriceSnapshot snapshot) {
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

            // NOTIFY payload — literal key "source" per spec
            // commands.md §Asset commands. The value is the sub_verb
            // (e.g. "coingecko"); the key name reconciles spec wording
            // ("(asset, source)") with architecture wording
            // ("(asset, sub_verb)"). M1-055c deserialises by key
            // "source".
            String payload = "{\"asset\":\"" + jsonEscape(snapshot.asset())
                + "\",\"source\":\"" + jsonEscape(snapshot.subVerb()) + "\"}";
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
                ps.setString(1, NEW_PRICE_SNAPSHOT_CHANNEL);
                ps.setString(2, payload);
                try (ResultSet rs = ps.executeQuery()) {
                    // pg_notify returns void but JDBC requires the
                    // cursor be consumed.
                    rs.next();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "PriceSnapshotStore: INSERT failed for asset=" + snapshot.asset()
                + " sub_verb=" + snapshot.subVerb(), e);
        }
    }

    private static void setNullableBigDecimal(PreparedStatement ps, int idx, java.math.BigDecimal v)
            throws SQLException {
        if (v == null) {
            ps.setNull(idx, Types.NUMERIC);
        } else {
            ps.setBigDecimal(idx, v);
        }
    }

    // The two payload fields are sub_verb (lowercase alpha) and asset
    // (lowercase alpha). The escape covers the defensive case where a
    // future asset id contains characters that JSON requires escaping;
    // production asset ids in v1 are pure [a-z], so this is a guard
    // rather than a hot path. Backslash MUST be escaped before the
    // double quote so the second pass does not also escape the
    // backslashes the first pass introduced.
    private static String jsonEscape(String in) {
        return in.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
