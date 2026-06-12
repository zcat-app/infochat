package app.zcat.infochat.provider.source;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.provider.source.KindResolver.SourceKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotent {@code source} + {@code source_subscription} + {@code tag}
 * vocab union + {@code audit_log} write in a single transaction.
 * Distinguishes the three spec'd branches from
 * {@code docs/spec/commands.md} §Source management:
 *
 * <ul>
 *   <li>{@link Outcome#FRESH_INSERT} — {@code (kind, identifier)} did
 *       not previously exist; the row was just inserted with the
 *       caller's {@code --tags} as {@code bootstrap_tags}; subscription
 *       upserted.</li>
 *   <li>{@link Outcome#SUBSCRIBED_EXISTING} — non-admin caller against
 *       a pre-existing source row; {@code bootstrap_tags} is left
 *       unchanged (supplied {@code --tags} are quietly ignored);
 *       subscription upserted.</li>
 *   <li>{@link Outcome#ADMIN_TAGS_REPLACED} — bot-admin caller against
 *       a pre-existing source row; {@code bootstrap_tags} is REPLACED
 *       with the supplied {@code --tags}; subscription upserted; one
 *       {@code audit_log} row written with the {@code ADD_SOURCE} verb
 *       (V5 §2.1.8 closed catalogue).</li>
 * </ul>
 *
 * <p>The transaction shape uses
 * {@code INSERT ... ON CONFLICT (kind, identifier) DO UPDATE SET ... RETURNING id, (xmax = 0) AS was_inserted}.
 * The {@code xmax = 0} predicate distinguishes an INSERT (xmax = 0) from
 * an UPDATE (xmax = the modifying transaction id) reliably in a single
 * statement — no race window between SELECT-then-INSERT. The conditional
 * SET on {@code bootstrap_tags} uses {@code CASE WHEN ? AND
 * source.deleted_at IS NULL THEN EXCLUDED.bootstrap_tags ELSE
 * source.bootstrap_tags END} so the UPDATE is a no-op for non-admin
 * callers (Branch B) and for soft-deleted rows.</p>
 *
 * <p>The vocab union runs UNCONDITIONALLY on every call: it is an
 * append-only {@code ON CONFLICT (name) DO NOTHING} INSERT against
 * {@code tag} that is idempotent regardless of branch. Always running
 * it avoids a SELECT-then-conditional-INSERT race; the extra rows
 * inserted for Branch B (where the tags were "ignored") are merely
 * vocabulary entries — they make the tag values addressable by
 * {@code /follow-tag} but do NOT change the source row's
 * {@code bootstrap_tags}, which is what Branch B requires.</p>
 *
 * <p><b>GRANT note.</b> The Provider runtime connects as the weak
 * {@code infochat_provider} role. V6 starts it read-only on
 * {@code source} and {@code tag}; V31 widens the matrix to exactly the
 * privileges these writes need — {@code INSERT} on {@code source} and
 * {@code tag} plus column-scoped {@code UPDATE} on {@code source}
 * ({@code bootstrap_tags} among the listed columns — the only column
 * this upsert's {@code ON CONFLICT DO UPDATE} touches) — while
 * {@code DELETE} stays revoked (Invariant 4: soft-delete only). V7
 * grants the Provider full {@code source_subscription} DML. So every
 * write here succeeds under the weak role, with no superuser and no
 * SECURITY DEFINER handoff.</p>
 */
@ApplicationScoped
public class SourceUpsertService {

    private static final String UPSERT_TAG_SQL =
            "INSERT INTO tag (name, display, source_origin) "
                    + "VALUES (?, ?, 'user') "
                    + "ON CONFLICT (name) DO NOTHING";

    private static final String UPSERT_SOURCE_SQL =
            "INSERT INTO source "
                    + "(kind, identifier, display_name, category, bootstrap_tags, status, added_by) "
                    + "VALUES (?, ?, ?, ?, ?, 'active', ?) "
                    + "ON CONFLICT (kind, identifier) DO UPDATE "
                    + "SET bootstrap_tags = CASE "
                    + "      WHEN ? AND source.deleted_at IS NULL THEN EXCLUDED.bootstrap_tags "
                    + "      ELSE source.bootstrap_tags "
                    + "    END "
                    + "RETURNING id, display_name, (xmax = 0) AS was_inserted";

    private static final String UPSERT_SUBSCRIPTION_SQL =
            "INSERT INTO source_subscription (scope_kind, scope_id, source_id, added_by) "
                    + "VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (scope_kind, scope_id, source_id) DO NOTHING";

    @Inject
    DataSource dataSource;

    @Inject
    AuditLogWriter auditLogWriter;

    /**
     * Run the single transaction. Returns the resolved outcome +
     * source id + display name the caller uses to build the reply.
     *
     * @param actorUserId  the caller's {@code users.id}.
     * @param actorIsBotAdmin {@code true} iff the caller has
     *                     {@code is_admin=TRUE} — drives the Branch
     *                     B vs Branch C split on an existing row.
     * @param scopeKind    {@code "dm"} or {@code "group"}.
     * @param scopeId      the per-scope identifier (for DM, the
     *                     caller's {@code users.id}; for group, the
     *                     {@code groups.id}).
     * @param kind         resolved {@link SourceKind} for the URL.
     * @param identifier   the canonical URL string.
     * @param displayName  display name (caller {@code --name}
     *                     override or a host-derived fallback).
     * @param category     closed-set category ({@code news|blog|social}).
     * @param tags         normalized tag list (≥1 element by the
     *                     parser's contract).
     */
    public UpsertResult upsert(UUID actorUserId,
                               boolean actorIsBotAdmin,
                               String scopeKind,
                               UUID scopeId,
                               SourceKind kind,
                               String identifier,
                               String displayName,
                               String category,
                               List<String> tags) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                upsertTagVocab(conn, tags);
                SourceUpsertResultRow row = upsertSource(
                        conn, actorUserId, actorIsBotAdmin, kind, identifier,
                        displayName, category, tags);
                upsertSubscription(conn, scopeKind, scopeId, row.id(), actorUserId);

                Outcome outcome;
                if (row.wasInserted()) {
                    outcome = Outcome.FRESH_INSERT;
                } else if (actorIsBotAdmin) {
                    outcome = Outcome.ADMIN_TAGS_REPLACED;
                    insertAuditRow(conn, actorUserId, row.id(), scopeId);
                } else {
                    outcome = Outcome.SUBSCRIBED_EXISTING;
                }
                conn.commit();
                return new UpsertResult(outcome, row.id(), row.displayName());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            // Redact the source identifier in the wrapping message: the
            // URL is bot-admin-visible via /list-sources --all per §Source
            // URL visibility, but defense-in-depth keeps the full URL out
            // of any exception message that reaches stdout / structured
            // logs. The redaction reuses ContactIds.redact (treating the
            // URL as an opaque string) rather than introducing a separate
            // URL-shape helper; the SQLException cause is preserved for
            // ops debugging.
            throw new IllegalStateException(
                    "SourceUpsertService.upsert failed for kind=" + kind.wire()
                            + " identifier=" + ContactIds.redact(identifier), e);
        }
    }

    private void upsertTagVocab(Connection conn, List<String> tags) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_TAG_SQL)) {
            for (String tag : tags) {
                ps.setString(1, tag);
                ps.setString(2, tag);
                ps.executeUpdate();
            }
        }
    }

    private SourceUpsertResultRow upsertSource(Connection conn,
                                               UUID actorUserId,
                                               boolean actorIsBotAdmin,
                                               SourceKind kind,
                                               String identifier,
                                               String displayName,
                                               String category,
                                               List<String> tags) throws SQLException {
        Array tagArray = conn.createArrayOf("TEXT", tags.toArray(new String[0]));
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_SOURCE_SQL)) {
            ps.setString(1, kind.wire());
            ps.setString(2, identifier);
            ps.setString(3, displayName);
            ps.setString(4, category);
            ps.setArray(5, tagArray);
            ps.setObject(6, actorUserId);
            ps.setBoolean(7, actorIsBotAdmin);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Reachable only if the conditional UPDATE-WHERE
                    // suppressed the UPDATE; SourceUpsertService's CASE
                    // form keeps the UPDATE itself unconditional, so a
                    // missing row here would indicate a deeper schema
                    // change. Surface loudly.
                    throw new IllegalStateException(
                            "source upsert returned no rows for (kind, identifier)="
                                    + "(" + kind.wire() + ", "
                                    + ContactIds.redact(identifier) + ")");
                }
                return new SourceUpsertResultRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("display_name"),
                        rs.getBoolean("was_inserted"));
            }
        }
    }

    private void upsertSubscription(Connection conn,
                                    String scopeKind,
                                    UUID scopeId,
                                    UUID sourceId,
                                    UUID actorUserId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_SUBSCRIPTION_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.setObject(4, actorUserId);
            ps.executeUpdate();
        }
    }

    private void insertAuditRow(Connection conn,
                                UUID actorUserId,
                                UUID sourceId,
                                UUID scopeId) throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actorUserId)
                .action(AuditAction.ADD_SOURCE)
                .targetKind(TargetKind.SOURCE)
                .targetId(sourceId.toString())
                .scopeId(scopeId)
                .build();
        auditLogWriter.write(conn, row);
    }

    /** Closed set: which of the three spec'd branches the upsert took. */
    public enum Outcome {
        FRESH_INSERT,
        SUBSCRIBED_EXISTING,
        ADMIN_TAGS_REPLACED
    }

    /** Result the handler uses to build the outbound reply. */
    public record UpsertResult(Outcome outcome, UUID sourceId, String displayName) {}

    /** Internal row-level result of the {@code source} UPSERT. */
    private record SourceUpsertResultRow(UUID id, String displayName, boolean wasInserted) {}

    /** Optional convenience: derive a default display name from the URL host. */
    public static String defaultDisplayName(String identifier, Optional<String> override) {
        return override.orElseGet(() -> {
            try {
                String host = java.net.URI.create(identifier).getHost();
                return host == null ? identifier : host;
            } catch (IllegalArgumentException e) {
                return identifier;
            }
        });
    }
}
