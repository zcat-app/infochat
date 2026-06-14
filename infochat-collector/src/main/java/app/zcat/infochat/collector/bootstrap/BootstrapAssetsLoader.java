package app.zcat.infochat.collector.bootstrap;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.core.util.Sha256;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@code bootstrap-assets.json} at Collector startup and
 * idempotently merges it into {@code asset_config}. Runs after
 * {@link BootstrapLoader} ({@code @Priority(200)}) and before the
 * fetch scheduler ({@code @Priority(400)}) so the scheduler sees the
 * asset rows when it iterates {@code asset_config}.
 *
 * <p>The loader is OPTIONAL — absent {@code infochat.bootstrap.assets-file}
 * property or a configured path pointing at a non-existent file is a
 * no-op (INFO log, no INSERT, no audit). Asset commands are
 * operator-opt-in per spec §Asset commands — Enable/disable lifecycle.
 * {@code /help} only surfaces asset commands when at least one row in
 * {@code asset_config} has {@code enabled = true} (Provider-side
 * dispatch concern, not this loader's responsibility).
 *
 * <h2>Transactional shape</h2>
 * <p>The load runs in one transaction. The order is load-bearing:
 * <ol>
 *   <li>Read bytes, SHA-256, parse — outside the transaction.</li>
 *   <li>Open transaction. Run the <b>default-row consistency
 *       pre-check</b>: for each entry, the future {@code (asset,
 *       default_sub_verb)} row's {@code enabled} value (preserved on
 *       conflict, or {@code true} on insert) must not be {@code false}
 *       — operator intent gone wrong. Reject before any INSERT runs
 *       (spec §Operational — Asset config, Default-row consistency).</li>
 *   <li>Clear the prior default flag on every asset in the bootstrap
 *       so the partial unique index ({@code uq_asset_config_default})
 *       does not fire when a new default lands on a different
 *       sub_verb.</li>
 *   <li>Upsert each {@code (asset, sub_verb)} row. The
 *       {@code ON CONFLICT} branch preserves the operator-managed
 *       {@code enabled} flag (mirrors the source-loader's
 *       soft-delete-skip discipline) and the fetcher-managed columns
 *       ({@code consecutive_failures}, {@code last_success_at},
 *       {@code last_failure_at}, {@code status}). The INSERT branch
 *       sets {@code enabled = true} via the column DEFAULT.</li>
 *   <li>Soft-disable rows present in the DB but absent from this
 *       bootstrap: {@code UPDATE … SET enabled = false} for rows
 *       scoped to the same asset ids the bootstrap is touching.
 *       Rows under unrelated assets (operator-managed outside the
 *       file) are left alone entirely.</li>
 *   <li>Write the {@code BOOTSTRAP_ASSET_LOAD} audit row inside the
 *       same transaction (Invariant 7 — audit-before-effect; commit
 *       is the single atomic point).</li>
 *   <li>{@code commit()}. Any {@link SQLException} or the pre-check
 *       exception rolls back the transaction; the {@code @Startup}
 *       bean propagates the failure and Collector boot aborts
 *       (Quarkus default).</li>
 * </ol>
 *
 * <h2>Attribution URL templates</h2>
 * <p>The {@code attribution_url} per row follows
 * {@code docs/design/10-asset-commands.md} §10.7 — one template per
 * sub_verb id ({@code coingecko}, {@code kraken}, {@code bitfinex}).
 * The Provider reads the column at command-reply time and emits the
 * URL bare per D30 (no markdown link syntax).
 */
@Startup
@Priority(250)
@ApplicationScoped
public class BootstrapAssetsLoader {

    private static final Logger LOG = Logger.getLogger(BootstrapAssetsLoader.class);

    @Inject
    DataSource dataSource;

    @Inject
    AuditLogWriter auditLogWriter;

    @ConfigProperty(name = "infochat.bootstrap.assets-file")
    Optional<Path> assetsFilePath;

    @PostConstruct
    void onStartup() {
        runLoad();
    }

    /**
     * Production entry point — resolves the configured path (or skips
     * if absent) and delegates to {@link #runLoad(Path)}. Exposed
     * (non-private) so the integration test can re-invoke the loader
     * to assert idempotency.
     */
    public void runLoad() {
        if (assetsFilePath.isEmpty()) {
            LOG.infof("BootstrapAssetsLoader: infochat.bootstrap.assets-file not set; "
                + "asset commands disabled (no asset_config rows written)");
            return;
        }
        Path path = assetsFilePath.get();
        if (!Files.isRegularFile(path)) {
            LOG.infof("BootstrapAssetsLoader: %s does not exist; "
                + "asset commands disabled (no asset_config rows written)", path);
            return;
        }
        runLoad(path);
    }

    /**
     * Test seam: load a specific file path, bypassing the
     * {@code @ConfigProperty}. Package-private so the integration test
     * can swap fixtures across ordered scenarios without re-booting
     * Quarkus. Production code uses {@link #runLoad()} via the
     * {@code @PostConstruct} hook.
     */
    void runLoad(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException(
                "BootstrapAssetsLoader: could not read bootstrap-assets file at "
                    + path.toAbsolutePath(), e);
        }
        String sha256 = Sha256.hex(bytes);

        BootstrapAssetsParser parser = new BootstrapAssetsParser();
        List<BootstrapAssetsEntry> entries = parser.parse(path);

        int upsertedCount = 0;
        int softDisabledCount = 0;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                enforceDefaultRowConsistency(conn, entries);
                clearPriorDefaults(conn, entries);
                upsertedCount = upsertEntries(conn, entries);
                softDisabledCount = softDisableAbsentRows(conn, entries);
                insertAuditRow(conn, sha256, path.toString(), upsertedCount, softDisabledCount);
                conn.commit();
            } catch (RuntimeException | SQLException e) {
                conn.rollback();
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException(
                    "BootstrapAssetsLoader: transactional load failed; rolled back", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "BootstrapAssetsLoader: could not borrow a connection from the pool", e);
        }

        LOG.infof("BootstrapAssetsLoader: loaded %d sub_verbs from %s (sha=%s, soft-disabled=%d)",
            upsertedCount, path, sha256, softDisabledCount);
    }

    /**
     * Pre-check: the future state of {@code (asset, default_sub_verb)}
     * must not be {@code enabled = false}. On conflict the upsert
     * preserves the existing {@code enabled} value (operator's
     * soft-disable survives); a default sub-verb whose row is
     * currently soft-disabled would land as the bare-invocation
     * resolver while disabled — spec §Operational — Asset config
     * Default-row consistency rejects this. The rejection is
     * <b>fatal</b> — log + throw aborts Collector boot.
     */
    private void enforceDefaultRowConsistency(Connection conn, List<BootstrapAssetsEntry> entries)
            throws SQLException {
        // SELECT existing enabled values for every (asset, default_sub_verb) pair.
        // A row that does not exist (INSERT branch) lands with enabled=true via
        // the column DEFAULT, so it cannot trigger the violation; only rows that
        // already exist with enabled=false do.
        final String sql =
            "SELECT enabled FROM asset_config WHERE asset = ? AND sub_verb = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (BootstrapAssetsEntry entry : entries) {
                ps.setString(1, entry.id());
                ps.setString(2, entry.defaultSubVerb());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && !rs.getBoolean("enabled")) {
                        String pair = "(" + entry.id() + ", " + entry.defaultSubVerb() + ")";
                        LOG.errorf("BootstrapAssetsLoader: FATAL — bootstrap entry's "
                            + "default_sub_verb resolves to a soft-disabled row %s; "
                            + "either enable the row or move the default flag (spec "
                            + "§Operational — Asset config, Default-row consistency)", pair);
                        throw new IllegalStateException(
                            "BootstrapAssetsLoader: default-but-disabled row " + pair
                                + " — operator must enable the row or change default_sub_verb");
                    }
                }
            }
        }
    }

    private void clearPriorDefaults(Connection conn, List<BootstrapAssetsEntry> entries)
            throws SQLException {
        // Without this, the partial unique index uq_asset_config_default
        // would fire when the bootstrap moves the default flag from
        // one sub_verb to another on the same asset. We can clear
        // unconditionally (it is a no-op for assets whose default has
        // not moved); the new default is set by the upsert that
        // follows in the same transaction.
        final String sql =
            "UPDATE asset_config SET is_default = false WHERE asset = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            Set<String> assetIds = new LinkedHashSet<>(entries.size());
            for (BootstrapAssetsEntry entry : entries) {
                assetIds.add(entry.id());
            }
            for (String assetId : assetIds) {
                ps.setString(1, assetId);
                ps.executeUpdate();
            }
        }
    }

    private int upsertEntries(Connection conn, List<BootstrapAssetsEntry> entries)
            throws SQLException {
        // The ON CONFLICT branch only updates loader-owned columns
        // (default_quote_currency, attribution_url, is_default). The
        // enabled flag, status, and the fetcher-managed counters are
        // preserved across re-runs — operator soft-disables survive,
        // and the fetcher's consecutive_failures / last_success_at /
        // last_failure_at writes are never clobbered.
        final String sql =
            "INSERT INTO asset_config "
                + "(asset, sub_verb, default_quote_currency, attribution_url, is_default) "
                + "VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (asset, sub_verb) DO UPDATE "
                + "SET default_quote_currency = EXCLUDED.default_quote_currency, "
                + "    attribution_url        = EXCLUDED.attribution_url, "
                + "    is_default             = EXCLUDED.is_default";

        int upserted = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (BootstrapAssetsEntry entry : entries) {
                for (BootstrapAssetsEntry.SubVerb sv : entry.subVerbs()) {
                    boolean isDefault = sv.id().equals(entry.defaultSubVerb());
                    ps.setString(1, entry.id());
                    ps.setString(2, sv.id());
                    ps.setString(3, entry.defaultQuoteCurrency());
                    ps.setString(4, attributionUrl(entry, sv));
                    ps.setBoolean(5, isDefault);
                    ps.executeUpdate();
                    upserted++;
                }
            }
        }
        return upserted;
    }

    /**
     * UPDATE every {@code asset_config} row whose {@code (asset, sub_verb)}
     * is NOT in the new bootstrap: set {@code enabled = false} (soft-
     * disable) and clear {@code is_default} (otherwise an asset entirely
     * dropped from the bootstrap would leave its prior default row as
     * {@code is_default = true AND enabled = false} — the invariant
     * the bootstrap-time pre-check is designed to prevent).
     *
     * <p>Scope is global, not per-asset: an asset dropped entirely
     * from the bootstrap also gets all its rows soft-disabled (the
     * spec lifecycle is enable/disable via the file, never
     * hard-delete; operator-managed rows added outside the file are
     * not a supported v1 surface).
     */
    private int softDisableAbsentRows(Connection conn, List<BootstrapAssetsEntry> entries)
            throws SQLException {
        // Flatten the new bootstrap into a (asset, sub_verb) keep-set
        // for the NOT IN tuple clause.
        List<String> keepAssets = new ArrayList<>();
        List<String> keepSubVerbs = new ArrayList<>();
        for (BootstrapAssetsEntry entry : entries) {
            for (BootstrapAssetsEntry.SubVerb sv : entry.subVerbs()) {
                keepAssets.add(entry.id());
                keepSubVerbs.add(sv.id());
            }
        }
        StringBuilder placeholders = new StringBuilder(keepAssets.size() * 6);
        for (int i = 0; i < keepAssets.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append("(?,?)");
        }

        String sql =
            "UPDATE asset_config SET enabled = false, is_default = false "
                + "WHERE (asset, sub_verb) NOT IN (" + placeholders + ") "
                + "AND (enabled = true OR is_default = true)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int paramIdx = 1;
            for (int i = 0; i < keepAssets.size(); i++) {
                ps.setString(paramIdx++, keepAssets.get(i));
                ps.setString(paramIdx++, keepSubVerbs.get(i));
            }
            return ps.executeUpdate();
        }
    }

    private void insertAuditRow(Connection conn, String sha256, String resolvedPath,
                                int upsertedCount, int softDisabledCount) throws SQLException {
        String detailsJson =
            "{\"path\":\"" + JsonEscaper.escape(resolvedPath)
                + "\",\"sha256\":\"" + sha256
                + "\",\"upserted_count\":" + upsertedCount
                + ",\"soft_disabled_count\":" + softDisabledCount + "}";
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
            .action(AuditAction.BOOTSTRAP_ASSET_LOAD)
            .targetKind(TargetKind.ASSET)
            .targetId(sha256)
            .detailsJson(detailsJson)
            .build();
        auditLogWriter.write(conn, row);
    }

    /**
     * Per-sub_verb attribution URL templates per
     * {@code docs/design/10-asset-commands.md} §10.7. The URL is
     * stored on every {@code asset_config} row and surfaced bare in
     * the Provider's reply (D30, no markdown link syntax).
     */
    private static String attributionUrl(BootstrapAssetsEntry entry, BootstrapAssetsEntry.SubVerb sv) {
        String quote = entry.defaultQuoteCurrency().toUpperCase(Locale.ROOT);
        return switch (sv.id()) {
            case "coingecko" -> "https://www.coingecko.com/en/coins/" + sv.externalId();
            case "kraken"    -> "https://www.kraken.com/prices/" + entry.id()
                                    + "-" + entry.defaultQuoteCurrency().toLowerCase(Locale.ROOT)
                                    + "-" + entry.id() + "-price-chart";
            case "bitfinex"  -> "https://www.bitfinex.com/t/" + entry.ticker() + ":" + quote;
            default -> throw new IllegalStateException(
                "BootstrapAssetsLoader: unsupported sub_verb '" + sv.id()
                    + "' has no attribution-URL template (extend §10.7 first)");
        };
    }

}
