package app.zcat.infochat.provider.source;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.ingest.IngestTextNormalizer;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.provider.source.KindResolver.SourceKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.text.Normalizer;
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

    // A display name is a single-line label rendered inline in a reply
    // sentence; 80 characters is a full terminal line, past which the value
    // is padding rather than a name. Bounds how much attacker-chosen text a
    // group admin can push into a broadcast reply (M1-659). Distinct from
    // the 200-char --name parse guardrail in docs/design/03-commands.md
    // §3.1: that one caps argument length at the parser, this one bounds
    // what an outbound reply will echo, and only the latter is implemented.
    private static final int MAX_DISPLAY_NAME_LENGTH = 80;

    private static final String UPSERT_TAG_SQL =
            "INSERT INTO tag (name, display, source_origin) "
                    + "SELECT t, t, 'user' FROM unnest(?::text[]) AS t "
                    + "ON CONFLICT (name) DO NOTHING";

    // source_origin: explicit 'user' on the INSERT branch (D59 — a custom
    // source is private to its subscribers; stated inline rather than
    // leaning on the V59 column default so the privacy-bearing value is
    // visible at the write site). The DO UPDATE branch must NOT touch
    // source_origin: EXCLUDED.source_origin would demote an existing
    // bootstrap row to 'user' on re-add, and the provider role's
    // column-scoped UPDATE grant (V31) does not include source_origin —
    // the promote direction is collector-side (BootstrapLoader) only.
    private static final String UPSERT_SOURCE_SQL =
            "INSERT INTO source "
                    + "(kind, identifier, display_name, category, bootstrap_tags, status, added_by, source_origin) "
                    + "VALUES (?, ?, ?, ?, ?, 'active', ?, 'user') "
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

    // Package-private (not private) so SourceUpsertServiceIT can drive it with a
    // statement-counting Connection and assert the single round-trip (M1-365). A
    // single array-bind unnest INSERT replaces the prior executeUpdate-per-tag
    // loop: the ON CONFLICT (name) DO NOTHING idempotency and the
    // run-unconditionally-on-every-call append-only union contract (class javadoc)
    // are both preserved — only the round-trip count changes (N → 1).
    void upsertTagVocab(Connection conn, List<String> tags) throws SQLException {
        Array tagArray = conn.createArrayOf("TEXT", tags.toArray(new String[0]));
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_TAG_SQL)) {
            ps.setArray(1, tagArray);
            ps.executeUpdate();
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

    /**
     * Optional convenience: derive a default display name from the URL host.
     * A caller-supplied {@code --name} override is accepted only if it
     * survives {@link #acceptableOverride}; otherwise the host-derived
     * fallback is used as though no override had been supplied.
     */
    public static String defaultDisplayName(String identifier, Optional<String> override) {
        return override.flatMap(SourceUpsertService::acceptableOverride).orElseGet(() -> {
            try {
                String host = java.net.URI.create(identifier).getHost();
                return host == null ? identifier : host;
            } catch (IllegalArgumentException e) {
                return identifier;
            }
        });
    }

    /**
     * The {@code --name} override, or empty if it is not safe to echo.
     *
     * <p>The stored display name is reflected verbatim into deterministic
     * outbound replies — the {@code /add-source} success reply, the
     * {@code /list-sources} listing, the {@code /unfollow-source}
     * confirmation — and in an approved group those replies are broadcast
     * to every member. A group admin (below bot admin) may add a source,
     * so an unconstrained override lets a lower-tier user put attacker-
     * chosen text in front of a bot admin. Text shaped like a bot command
     * is the payload that matters: a bot admin who copy-pastes a
     * plausible {@code /grant-admin <uuid> approved} line executes it.
     * That is the deterministic-reply social-engineering surface
     * {@code docs/spec/security.md} §LLM output sanitizer leaves
     * unfiltered by design (it filters LLM prose, not deterministic
     * templates).
     *
     * <p><b>Reject rather than rewrite.</b> Neutralizing the value in
     * place does not work: stripping the leading slash from
     * {@code /grant-admin …} leaves the command words intact, and a
     * charset filter cannot help because {@code grant-admin} is already
     * plain lowercase ASCII — the output-side filtering M1-647 tried and
     * abandoned. Discarding the whole override and falling back to the
     * host is what actually removes the text from the reply. Constraining
     * here, where the value is produced, also covers the other two
     * surfaces at once, because it is the *stored* value that is
     * constrained rather than one reply's bytes (M1-659).
     */
    private static Optional<String> acceptableOverride(String override) {
        // NFKC first, so the slash test below sees the same representation the
        // command parser would. The router normalizes inbound text, but NOT
        // inside fenced code blocks (InboundRouter.normalize works per line and
        // appends fence content verbatim) while routing is decided on the whole
        // body's first character — so a /add-source on line 1 can carry an
        // un-normalized fenced payload on line 3, in which U+FF0F FULLWIDTH
        // SOLIDUS survives an ASCII-only test and then folds to '/' when the
        // reply is pasted back unfenced. Normalizing here makes this check
        // self-sufficient rather than dependent on how the value arrived.
        // Then strip: control characters, bidi overrides and the Unicode
        // line/paragraph separators would let the name forge extra apparent
        // lines in the rendered reply; stripMetadataField is the project's
        // single declaration of that strip for single-line metadata fields.
        String normalized = Normalizer.normalize(override, Normalizer.Form.NFKC);
        String stripped = IngestTextNormalizer.stripMetadataField(normalized).trim();
        if (stripped.isEmpty()
                || containsSlash(stripped)
                || stripped.length() > MAX_DISPLAY_NAME_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(stripped);
    }

    /**
     * Does the name contain a slash?
     *
     * <p>A slash anywhere disqualifies the whole override — this is
     * deliberately absolute rather than a judgement about whether the
     * slash "opens a word", and the absoluteness is the security
     * property. Two successive M1-659 audits defeated the boundary form,
     * once on each side of the predicate: rejecting when the preceding
     * character is {@code isWhitespace}/{@code isSpaceChar} fell to U+2800
     * BRAILLE PATTERN BLANK (category {@code OTHER_SYMBOL}), and accepting
     * only when it is {@code isLetterOrDigit} fell to the Hangul fillers
     * U+115F/U+1160/U+3164 (category {@code OTHER_LETTER}). Both render as
     * a blank gap; neither is removed by {@code stripMetadataField} or
     * {@code String.trim()}. The lesson is structural, not a matter of
     * picking better predicates: every partition of Unicode has
     * blank-rendering members on both sides, so a character-category test
     * can only move the hole. Since D12 makes slash-prefix the only
     * command surface, a name containing no slash cannot carry a command
     * token regardless of what surrounds it.
     *
     * <p>Testing the ASCII slash alone is sufficient only because
     * {@link #acceptableOverride} NFKC-normalizes the value first, which
     * folds U+FF0F FULLWIDTH SOLIDUS to {@code '/'}. That normalization is
     * done HERE rather than relied upon from the router: the router's own
     * pass exempts fenced code blocks, and a command line can open a fence
     * on a later line, so an inbound value can reach this method unfolded.
     * The homoglyphs that do NOT fold (U+2215, U+2044, U+29F8) equally do
     * not parse as a command when pasted back, so they yield nothing
     * executable.
     *
     * <p>The cost is that an ordinary name carrying a slash
     * ({@code "AC/DC News"}) is also discarded and falls back to the
     * host-derived default. That is accepted: the display name is
     * cosmetic, no spec commits to a character set for it, and the
     * fallback is a sane host string.
     */
    private static boolean containsSlash(String name) {
        return name.indexOf('/') >= 0;
    }
}
