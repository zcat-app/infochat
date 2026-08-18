package app.zcat.infochat.collector.bootstrap;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.core.util.Sha256;
import app.zcat.infochat.core.util.TagNormalizer;
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
import java.nio.file.Paths;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads {@code bootstrap-sources.json} at Collector startup and idempotently
 * merges it into the {@code source} and {@code tag} catalogues. Runs after
 * Flyway ({@code @Priority(100)}, Quarkus default) and before the
 * {@code OutboxRehydrator} ({@code @Priority(300)}), {@code FetchScheduler}
 * ({@code @Priority(400)}), and {@code StreamSourceSupervisor}
 * ({@code @Priority(450)}) per
 * {@code docs/design/01-architecture.md} §1.4.3. The ordering is
 * load-bearing — the scheduler cannot poll sources the loader has not
 * yet written.
 *
 * <p>Transactional shape (single JDBC connection, {@code autoCommit=false}):
 * <ol>
 *   <li>Read the file bytes; compute SHA-256 of the raw bytes BEFORE
 *       parsing. The digest is the cross-host convergence key the admin
 *       {@code /status} read path consults from {@code bootstrap_meta}.</li>
 *   <li>Parse and validate via {@link BootstrapSourcesParser}. Any parse
 *       or semantic-validation failure propagates as a startup failure —
 *       Quarkus refuses to start, per §1.4.3.</li>
 *   <li>Open the transactional body: upsert each source row
 *       ({@code ON CONFLICT (kind, identifier) DO UPDATE … WHERE
 *       source.deleted_at IS NULL} keeps soft-deleted rows untouched —
 *       admin uses {@code /remove-source} / {@code /add-source} for that
 *       lifecycle, not the bootstrap path).</li>
 *   <li>Union {@code tags} across every entry, upsert into {@code tag}
 *       with {@code source_origin = 'bootstrap'} ({@code ON CONFLICT
 *       (name) DO NOTHING}). Tag normalization via the shared
 *       {@code TagNormalizer}.</li>
 *   <li>Insert one {@code audit_log} row with action
 *       {@code BOOTSTRAP_SOURCE_LOAD}; {@code target_id} is the SHA-256
 *       hex digest so the audit trail is keyed by file-content version.
 *       Each run writes a new row (idempotent re-runs still log).</li>
 *   <li>Upsert the single {@code bootstrap_meta} row
 *       ({@code ON CONFLICT (id) DO UPDATE}). The {@code last_loaded_at}
 *       moves forward on every run; the SHA / entry count are unchanged
 *       across no-op re-runs.</li>
 *   <li>{@code commit()}. On any {@link SQLException} in the
 *       transactional body, {@code rollback()} and re-throw — the
 *       {@code @Startup} bean's failure aborts service start
 *       (Quarkus default, §1.4.3).</li>
 * </ol>
 *
 * <p>The loader is the ONLY writer to {@code source} in v1; the matching
 * admin-side {@code /add-source} / {@code /remove-source} handlers land
 * with the T1-F Provider commands. The Provider role carries
 * {@code SELECT} only on {@code source} per the M1-008b grant matrix —
 * Provider-initiated writes route through a future handoff path that
 * is not in scope here.
 */
@Startup
@Priority(200)
@ApplicationScoped
public class BootstrapLoader {

    private static final Logger LOG = Logger.getLogger(BootstrapLoader.class);

    static final String AUDIT_VERB = AuditAction.BOOTSTRAP_SOURCE_LOAD.name();

    @Inject
    DataSource dataSource;

    @Inject
    AuditLogWriter auditLogWriter;

    @ConfigProperty(name = "infochat.bootstrap.sources-file", defaultValue = "bootstrap-sources.json")
    String sourcesFilePath;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "0.0.0-dev")
    String loaderVersion;

    @PostConstruct
    void onStartup() {
        runLoad();
    }

    /**
     * Performs one bootstrap load — read file, compute SHA, parse,
     * upsert sources / tags / meta, append audit. Exposed (non-private)
     * so the integration test can re-invoke the loader to assert
     * idempotency and the soft-delete-skip behavior; the production
     * path is via {@link #onStartup()} during Quarkus startup.
     */
    public void runLoad() {
        Path path = Paths.get(sourcesFilePath);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException(
                "BootstrapLoader: could not read bootstrap-sources file at " + path.toAbsolutePath(), e);
        }
        String sha256 = Sha256.hex(bytes);

        BootstrapSourcesParser parser = new BootstrapSourcesParser();
        List<BootstrapSourcesEntry> entries = parser.parse(bytes);
        failFastOnInvalidTags(entries);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                failFastOnNonLeafTags(conn, entries);
                upsertSources(conn, entries, parser);
                upsertTags(conn, entries);
                insertAuditRow(conn, sha256, path.toString(), entries.size());
                upsertBootstrapMeta(conn, sha256, entries.size());
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                    "BootstrapLoader: transactional load failed; rolled back", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "BootstrapLoader: could not borrow a connection from the pool", e);
        }

        LOG.infof("BootstrapLoader: loaded %d sources from %s (sha=%s)",
            entries.size(), path, sha256);
    }

    private void upsertSources(Connection conn, List<BootstrapSourcesEntry> entries, BootstrapSourcesParser parser)
            throws SQLException {
        // The WHERE source.deleted_at IS NULL clause on the UPDATE
        // branch enforces docs/design/02-schema.md §2.2.1's
        // soft-delete-skip rule — a row admin /remove-source'd stays
        // soft-deleted even if the operator re-lists it in the file.
        // The INSERT branch cannot fire when (kind, identifier) already
        // exists (the UNIQUE constraint), so the soft-deleted row's
        // old columns remain intact.
        // source_origin = 'bootstrap' on BOTH branches (D59): the file is
        // operator intent, so listing a previously /add-source'd ('user')
        // source promotes it into the implicit public corpus.
        // language is declared operator intent too: the DO UPDATE branch
        // overwrites it (unlike the provider upsert, whose INSERT-only
        // language is grant-constrained — V31 excludes the column from the
        // provider role's UPDATE) because re-listing an entry with a new
        // language IS the operator's correction path (D29 declared, never
        // inferred).
        final String sql =
            "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags, config, source_origin, language) "
                + "VALUES (?, ?, ?, ?, ?, ?::JSONB, 'bootstrap', ?) "
                + "ON CONFLICT (kind, identifier) DO UPDATE "
                + "SET display_name = EXCLUDED.display_name, "
                + "    category = EXCLUDED.category, "
                + "    bootstrap_tags = EXCLUDED.bootstrap_tags, "
                + "    config = EXCLUDED.config, "
                + "    source_origin = 'bootstrap', "
                + "    language = EXCLUDED.language "
                + "WHERE source.deleted_at IS NULL";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (BootstrapSourcesEntry entry : entries) {
                // Parser-validated entries: tags is non-empty (§7.6.1);
                // requireNonNull re-states that for the type system.
                List<String> tags = Objects.requireNonNull(entry.tags());
                String[] normalizedTags = new String[tags.size()];
                for (int i = 0; i < tags.size(); i++) {
                    normalizedTags[i] = normalizeTag(tags.get(i));
                }
                Array tagArray = conn.createArrayOf("TEXT", normalizedTags);

                ps.setString(1, entry.kind());
                ps.setString(2, entry.identifier());
                ps.setString(3, entry.name());
                ps.setString(4, entry.category());
                ps.setArray(5, tagArray);
                ps.setString(6, parser.configToJsonString(entry.config()));
                ps.setString(7, Objects.requireNonNull(entry.language()));
                ps.executeUpdate();
            }
        }
    }

    private void upsertTags(Connection conn, List<BootstrapSourcesEntry> entries) throws SQLException {
        // Deduplicate by normalized name across all entries; preserve
        // the first-seen display casing so the admin /follow-tag UI
        // shows the operator's original spelling.
        Map<String, String> uniqueTags = new LinkedHashMap<>();
        for (BootstrapSourcesEntry entry : entries) {
            for (String raw : Objects.requireNonNull(entry.tags())) {
                String name = normalizeTag(raw);
                uniqueTags.putIfAbsent(name, raw);
            }
        }

        final String sql =
            "INSERT INTO tag (name, display, source_origin) "
                + "VALUES (?, ?, 'bootstrap') "
                + "ON CONFLICT (name) DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, String> tag : uniqueTags.entrySet()) {
                ps.setString(1, tag.getKey());
                ps.setString(2, tag.getValue());
                ps.executeUpdate();
            }
        }
    }

    private void insertAuditRow(Connection conn, String sha256, String resolvedPath, int entryCount)
            throws SQLException {
        // Append-only INSERT; no actor user — the loader runs at startup
        // before any user has acted. target_id is the file content SHA
        // so the audit trail is keyed by file-content version.
        String detailsJson =
            "{\"path\":\"" + JsonEscaper.escape(resolvedPath)
                + "\",\"sha256\":\"" + sha256
                + "\",\"entry_count\":" + entryCount + "}";
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .action(AuditAction.BOOTSTRAP_SOURCE_LOAD)
                .targetKind(TargetKind.SYSTEM)
                .targetId(sha256)
                .detailsJson(detailsJson)
                .build();
        auditLogWriter.write(conn, row);
    }

    private void upsertBootstrapMeta(Connection conn, String sha256, int entryCount) throws SQLException {
        final String sql =
            "INSERT INTO bootstrap_meta "
                + "(id, last_loaded_sha256, last_loaded_at, last_entry_count, last_loader_version) "
                + "VALUES (1, ?, ?, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE "
                + "SET last_loaded_sha256  = EXCLUDED.last_loaded_sha256, "
                + "    last_loaded_at      = EXCLUDED.last_loaded_at, "
                + "    last_entry_count    = EXCLUDED.last_entry_count, "
                + "    last_loader_version = EXCLUDED.last_loader_version";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sha256);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setInt(3, entryCount);
            ps.setString(4, loaderVersion);
            ps.executeUpdate();
        }
    }

    /**
     * Validates every source tag across all entries and throws ONCE,
     * enumerating each invalid (source identifier, raw tag, reason) —
     * an operator whose file carries several bad tags fixes them all in
     * one pass instead of one reboot per tag (M1-578). Runs before the
     * connection is borrowed, so fail-fast is preserved with nothing
     * partially loaded.
     */
    private static void failFastOnInvalidTags(List<BootstrapSourcesEntry> entries) {
        List<String> invalidTagReports = new ArrayList<>();
        for (BootstrapSourcesEntry entry : entries) {
            for (String raw : Objects.requireNonNull(entry.tags())) {
                if (TagNormalizer.normalize(raw) == null) {
                    invalidTagReports.add(
                        "source '" + entry.identifier() + "': tag '" + raw
                            + "' — must match " + TagNormalizer.TAG_NAME_PATTERN.pattern());
                }
            }
        }
        if (invalidTagReports.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
            "BootstrapLoader: " + invalidTagReports.size()
                + " invalid source tag(s) in bootstrap-sources file — fix all of them, then restart:\n  - "
                + String.join("\n  - ", invalidTagReports));
    }

    /** Leaf-membership gate (M1-882): validate every file tag before the first write so a top, retired v1 name, or any coinage fails startup loudly (M1-077 shape). */
    private void failFastOnNonLeafTags(Connection conn, List<BootstrapSourcesEntry> entries)
            throws SQLException {
        Map<String, String> uniqueTags = new LinkedHashMap<>();
        for (BootstrapSourcesEntry entry : entries) {
            for (String raw : Objects.requireNonNull(entry.tags())) {
                uniqueTags.putIfAbsent(normalizeTag(raw), raw);
            }
        }
        Set<String> existing = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name FROM tag WHERE node_kind = 'leaf' AND name = ANY(?)")) {
            ps.setArray(1, conn.createArrayOf("TEXT",
                uniqueTags.keySet().toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    existing.add(rs.getString(1));
                }
            }
        }
        List<String> reports = new ArrayList<>();
        for (BootstrapSourcesEntry entry : entries) {
            for (String raw : Objects.requireNonNull(entry.tags())) {
                String name = normalizeTag(raw);
                if (!existing.contains(name)) {
                    reports.add("source '" + entry.identifier() + "': tag '" + raw
                        + "' — not an existing source-eligible leaf tag-tree node");
                }
            }
        }
        if (!reports.isEmpty()) {
            throw new IllegalStateException(
                "BootstrapLoader: " + reports.size()
                    + " tag(s) in bootstrap-sources file are not source-eligible leaf tag-tree nodes — fix all of them, then restart:\n  - "
                    + String.join("\n  - ", reports));
        }
    }

    /**
     * Delegates to the shared {@link TagNormalizer#normalize(String)}
     * (NFC + {@code Locale.ROOT} lower-case + character-class
     * validation). Every file-sourced tag has already passed
     * {@link #failFastOnInvalidTags}, so normalize cannot return null
     * here; requireNonNull re-states that for the type system.
     */
    private static String normalizeTag(String raw) {
        return Objects.requireNonNull(TagNormalizer.normalize(raw));
    }
}
