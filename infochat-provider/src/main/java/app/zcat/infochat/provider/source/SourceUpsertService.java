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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Idempotent {@code source} + {@code source_subscription} + {@code tag}
 * vocab union + {@code audit_log} write in a single transaction.
 * Distinguishes the spec'd branches from
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
 *       a pre-existing, NON-deleted source row; {@code bootstrap_tags}
 *       is REPLACED with the supplied {@code --tags}; subscription
 *       upserted; one {@code audit_log} row written with the
 *       {@code ADD_SOURCE} verb (V5 §2.1.8 closed catalogue).</li>
 *   <li>{@link Outcome#ADMIN_EXISTING_REMOVED} — bot-admin caller
 *       against a SOFT-DELETED source row; the SQL leaves
 *       {@code bootstrap_tags} alone, so this branch writes no audit
 *       row and the reply must not claim a replacement (M1-669).
 *       Reviving the row is {@code /source-enable}'s job, behind its
 *       own confirmation gate.</li>
 *   <li>{@link Outcome#SUBSCRIBED_EXISTING_REMOVED} — non-admin caller
 *       against a SOFT-DELETED source row; subscription upserted (inert
 *       until the row is revived), tags ignored, no audit row. Distinct
 *       from {@link Outcome#SUBSCRIBED_EXISTING} because a plain
 *       "subscribed" reply would promise a feed that delivers nothing
 *       and is hidden from {@code /list-sources}, and because
 *       {@code /source-enable} is bot-admin-only — this tier's remedy
 *       is to ask a bot admin, not to run it (M1-669).</li>
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
 * callers (Branch B) and for soft-deleted rows. BOTH halves of that
 * condition are returned to the outcome selector — the admin flag is
 * already in hand, and {@code was_removed} carries the deleted-row
 * half — so the reported outcome cannot disagree with what the SQL
 * actually did. Reporting a replacement the CASE had skipped was the
 * M1-669 defect.</p>
 *
 * <p>The vocab union still runs on every call (the M1-365 one-round-trip
 * shape) but the node gate below admits only existing tree nodes, so it
 * is a guaranteed zero-row no-op; vocabulary entry is the V84 seed.</p>
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

    /** Thrown before any write when caller-supplied tags name no tag-tree
     * node (the M1-866 growth gate — decision 5 / P11); the handler turns it
     * into the friendly fuzzy-suggestion error, nothing persisted. */
    public static final class UnknownTagsException extends RuntimeException {

        private final List<String> unknownNames;

        public UnknownTagsException(List<String> unknownNames) {
            super("SourceUpsertService: tag(s) not tag-tree nodes: "
                + String.join(", ", unknownNames));
            this.unknownNames = List.copyOf(unknownNames);
        }
        /** The normalized tag names that are not existing tree nodes, in input order. */
        public List<String> unknownNames() {
            return unknownNames;
        }
    }

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
    // language: INSERT-only, same grant constraint — V31's UPDATE column
    // list (status, consecutive_failures, deleted_at, deleted_by,
    // bootstrap_tags) excludes language, so the DO UPDATE branch cannot
    // (and per D29 must not) overwrite an existing row's declared
    // language; changing it is an operator action (bootstrap re-list,
    // operator SQL), never a chat command (M1-750).
    private static final String UPSERT_SOURCE_SQL =
            "INSERT INTO source "
                    + "(kind, identifier, display_name, category, bootstrap_tags, status, added_by, source_origin, language) "
                    + "VALUES (?, ?, ?, ?, ?, 'active', ?, 'user', ?) "
                    + "ON CONFLICT (kind, identifier) DO UPDATE "
                    + "SET bootstrap_tags = CASE "
                    + "      WHEN ? AND source.deleted_at IS NULL THEN EXCLUDED.bootstrap_tags "
                    + "      ELSE source.bootstrap_tags "
                    + "    END "
                    // was_removed mirrors the CASE guard above: the DO UPDATE arm
                    // never writes deleted_at, so on the conflict branch this is
                    // the pre-existing value and tells the outcome selector whether
                    // the tag replacement it is about to report actually happened.
                    // On the INSERT branch the fresh row has deleted_at NULL, so it
                    // is false — and was_inserted is checked first anyway.
                    + "RETURNING id, display_name, (xmax = 0) AS was_inserted, "
                    + "          (source.deleted_at IS NOT NULL) AS was_removed";

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
     * @param language     declared source language, validated by
     *                     {@code AddSourceArgs} against the reviewed
     *                     {@code SourceLanguageRegistry} set (D29).
     * @param tags         normalized tag list (≥1 element by the
     *                     parser's contract).
     */
    public UpsertResult upsert(UUID actorUserId,
                               boolean actorIsBotAdmin,
                               String scopeKind,
                               UUID scopeId,
                               KindResolver.SourceKind kind,
                               String identifier,
                               String displayName,
                               String category,
                               String language,
                               List<String> tags) {
        // Node gate (M1-866): only existing tag-tree nodes may be unioned —
        // the free-form union is how the vendor tail grew (analysis P11).
        // Runs before the write transaction so a rejection writes nothing.
        List<String> unknown = unknownNodeTags(tags);
        if (!unknown.isEmpty()) {
            throw new UnknownTagsException(unknown);
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                upsertTagVocab(conn, tags);
                SourceUpsertResultRow row = upsertSource(
                        conn, actorUserId, actorIsBotAdmin, kind, identifier,
                        displayName, category, language, tags);
                upsertSubscription(conn, scopeKind, scopeId, row.id(), actorUserId);

                Outcome outcome;
                if (row.wasInserted()) {
                    outcome = Outcome.FRESH_INSERT;
                } else if (row.wasRemoved()) {
                    // Neither tier gets an audit row here: the CASE guard skipped
                    // the tag replacement, and audit_log records privileged
                    // actions that OCCURRED — a row would attribute a replacement
                    // that never happened (M1-669). The tiers split only on which
                    // remedy their reply can name, because /source-enable is
                    // bot-admin-only.
                    outcome = actorIsBotAdmin
                            ? Outcome.ADMIN_EXISTING_REMOVED
                            : Outcome.SUBSCRIBED_EXISTING_REMOVED;
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

    /** The supplied tags that name no tag-tree node, in input order (empty = all are nodes). */
    private List<String> unknownNodeTags(List<String> tags) {
        if (tags.isEmpty()) {
            return List.of();
        }
        Set<String> nodes = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name FROM tag WHERE name = ANY(?)")) {
            ps.setArray(1, conn.createArrayOf("TEXT", tags.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    nodes.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "SourceUpsertService: tag-node lookup failed", e);
        }
        List<String> unknown = new ArrayList<>();
        for (String tag : tags) {
            if (!nodes.contains(tag)) {
                unknown.add(tag);
            }
        }
        return unknown;
    }

    private SourceUpsertResultRow upsertSource(Connection conn,
                                               UUID actorUserId,
                                               boolean actorIsBotAdmin,
                                               SourceKind kind,
                                               String identifier,
                                               String displayName,
                                               String category,
                                               String language,
                                               List<String> tags) throws SQLException {
        Array tagArray = conn.createArrayOf("TEXT", tags.toArray(new String[0]));
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_SOURCE_SQL)) {
            ps.setString(1, kind.wire());
            ps.setString(2, identifier);
            ps.setString(3, displayName);
            ps.setString(4, category);
            ps.setArray(5, tagArray);
            ps.setObject(6, actorUserId);
            ps.setString(7, language);
            ps.setBoolean(8, actorIsBotAdmin);
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
                        rs.getBoolean("was_inserted"),
                        rs.getBoolean("was_removed"));
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

    /** Closed set: which of the spec'd branches the upsert took. */
    public enum Outcome {
        FRESH_INSERT,
        SUBSCRIBED_EXISTING,
        ADMIN_TAGS_REPLACED,
        ADMIN_EXISTING_REMOVED,
        SUBSCRIBED_EXISTING_REMOVED
    }

    /** Result the handler uses to build the outbound reply. */
    public record UpsertResult(Outcome outcome, UUID sourceId, String displayName) {}

    /** Internal row-level result of the {@code source} UPSERT. */
    private record SourceUpsertResultRow(UUID id, String displayName, boolean wasInserted,
                                         boolean wasRemoved) {}

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
                || containsFlagToken(stripped)
                || stripped.length() > MAX_DISPLAY_NAME_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(stripped);
    }

    /**
     * Does the name carry a token shaped like a command FLAG?
     *
     * <p>The slash test above removes the command-WORD half of a
     * copy-pasteable line. It does not remove the FLAG half, and that half
     * needs no slash: the closed-list entries that matter most are
     * flag-bearing ({@code /list-sources --all},
     * {@code /list-sources --include-deleted}), and
     * {@code ListSourcesCommandHandler.ListSourcesArgs.parse} sets its
     * flag from a bare {@code --all} token at ANY position in the argument
     * run, ignoring tokens it does not recognise. So a display name of
     * {@code Acme --all News} rendered beside a post title of
     * {@code /list-sources} composes a complete, dispatchable line —
     * assembled from two fields that never share one
     * {@code LlmOutputSanitizer} input, so neither is redacted and no
     * {@code LLM_OUTPUT_SANITIZED} row is written for an operator to
     * correlate. (Redteam 2026-08-05, medium/INJECTION.)
     *
     * <p>Rejecting here rather than at the render sites is what
     * {@code docs/spec/security.md} §"LLM output sanitizer" already
     * commits to for this value — the echo "is closed at the write
     * boundary alone ... constraining the single produced value covers
     * every surface that later renders it" — and it is why this sits
     * beside {@link #containsSlash} rather than in a renderer. Reject,
     * never rewrite, for the reason the enclosing javadoc gives: stripping
     * the dashes leaves the word intact.
     *
     * <p>Deliberately blunt, like the slash rule: ANY whitespace-delimited
     * token that opens with two ASCII hyphens and carries at least one
     * more character disqualifies the whole override. A display name has
     * no legitimate need for one, and a test that tried to judge which
     * flags are "real" would have to track the argument parsers it is
     * defending — the coupling the slash rule was made absolute to avoid.
     * An em-dash or a bare {@code --} between words is untouched.
     */
    private static boolean containsFlagToken(String name) {
        for (String token : name.split("\\s+")) {
            if (token.length() > 2 && token.startsWith("--")) {
                return true;
            }
        }
        return false;
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
