package app.zcat.infochat.provider.llm;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.ingest.IngestTextNormalizer;
import app.zcat.infochat.core.util.JsonEscaper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizer applied to LLM-authored output before it lands in an
 * outbound reply. Enforces two invariants from docs/spec/security.md
 * §LLM output sanitizer and docs/spec/commands.md §Surface conventions:
 *
 * <ol>
 *   <li><b>Plain-text only.</b> Markdown link syntax {@code [text](url)}
 *       is rewritten to {@code text (url)} so the rendered prose carries
 *       both the visible label and the bare URL. Runs FIRST so a
 *       hostile {@code [Click for admin](/grant-admin)} flattens to
 *       {@code Click for admin (/grant-admin)} BEFORE the closed-list
 *       pass sees it, and runs AGAIN inside the closed-list pass over
 *       the canonical form, which NFKC can fold fullwidth brackets
 *       into. Delivered output never contains {@code ](}: what the
 *       flatten regex cannot parse is
 *       {@linkplain #neutralizeResidualLinkSyntax neutralized} instead,
 *       so the guarantee does not inherit the regex's limits.</li>
 *   <li><b>Closed-list strip.</b> Every privileged-tier command token
 *       from {@link #CLOSED_LIST} is replaced with the literal
 *       {@value #REDACTED_COMMAND_REPLACEMENT}. Replacement is
 *       uniform: every CLOSED_LIST entry is treated identically; the
 *       reader gets the surrounding prose minus the matched string,
 *       not an empty/failed reply. The match runs on the
 *       {@linkplain #canonicalizeForMatching canonical} form of the
 *       output, not its raw bytes — see that method for why.</li>
 * </ol>
 *
 * <p>Every match emits one structured log line at level WARN AND
 * one {@code audit_log} row with action {@code LLM_OUTPUT_SANITIZED} —
 * the docs/spec/security.md per-occurrence (not throttled) commitment.
 * Two hits in one {@code sanitize()} call land two audit rows, not
 * one coalesced row. The audit row's {@code details_json} carries the
 * matched token under {@code match_kind} (the closed-list entry, e.g.
 * {@code /ban}) plus {@code match_count = 1} per row; the user-visible
 * LLM output text is never copied into the row.
 *
 * <p>{@link #CLOSED_LIST} is hand-maintained in code to mirror
 * docs/spec/commands.md §Permission model §Closed list of
 * privileged-tier commands. The CI completeness {@code @Test}
 * {@code LlmOutputSanitizerTest.matchSetEqualsSpecClosedList} reads the
 * spec markdown at TEST tier and asserts equality; a spec-side
 * addition without a corresponding CLOSED_LIST update fails CI, and a
 * CLOSED_LIST entry that no longer corresponds to a listed command
 * also fails CI.
 */
@ApplicationScoped
public class LlmOutputSanitizer {

    private static final Logger LOG = Logger.getLogger(LlmOutputSanitizer.class);

    // The audit-log target_id for sanitizer rows. Sanitizer hits are not
    // tied to a user/post/group entity, so the target_id is a fixed
    // marker; the audit-row identity is the (action, created_at, details
    // _json) triple plus the row's BIGSERIAL id.
    private static final String AUDIT_TARGET_ID = "sanitizer-output";

    private final AuditLogWriter auditLogWriter;
    private final DataSource dataSource;

    /**
     * Sole constructor. Both audit collaborators are non-null and final, so
     * {@link #sanitize(String)} ALWAYS emits the per-occurrence
     * {@code LLM_OUTPUT_SANITIZED} rows the spec commits to — there is no
     * constructor that can build an audit-bypassing instance, so the
     * durability commitment is structural rather than discipline-enforced.
     */
    @Inject
    public LlmOutputSanitizer(AuditLogWriter auditLogWriter, DataSource dataSource) {
        this.auditLogWriter = auditLogWriter;
        this.dataSource = dataSource;
    }

    /** Literal that replaces every {@link #CLOSED_LIST} match in the output. */
    public static final String REDACTED_COMMAND_REPLACEMENT = "[redacted command]";

    /**
     * The closed set of privileged-tier command tokens that must be
     * stripped from LLM output. Mirrors docs/spec/commands.md §Closed
     * list of privileged-tier commands verbatim. Order is the spec's
     * order; the {@link #applyClosedListStrip(String)} pass iterates
     * the list and replaces every occurrence per entry — a single
     * matcher pass over the union via alternation would be
     * indistinguishable from this loop semantically, but a per-entry
     * loop keeps the per-entry observability promise (one log line
     * per matched token).
     */
    static final List<String> CLOSED_LIST = List.of(
            // Bot-admin only:
            "/grant-admin",
            "/revoke-admin",
            "/ban",
            "/unban",
            "/promote",
            "/demote",
            "/vouch",
            "/invite create",
            "/invite list",
            "/invite revoke",
            "/invite bot-contact",
            "/invite pending-contacts",
            "/quarantine list",
            "/quarantine approve",
            "/quarantine reject",
            "/audit",
            "/pending",
            "/remove-source",
            "/source-enable",
            "/source-disable",
            "/list-sources --all",
            "/list-sources --include-deleted",
            "/approve-group",
            "/reject-group",
            "/list-groups",
            "/recover-pool",
            // Group-admin (or bot admin acting in the group):
            "/add-source",
            "/unfollow-source",
            "/follow-all-sources",
            "/lang",
            "/group-timezone",
            "/digest",
            "/follow-tag",
            "/unfollow-tag"
    );

    /**
     * Precompiled match patterns for {@link #CLOSED_LIST}, index-aligned
     * (pattern {@code i} matches entry {@code i}). Compiled once at class
     * load so the closed-list pass does not re-compile every entry's
     * pattern on each {@link #sanitize(String)} call — the markdown pass's
     * {@link #MARKDOWN_LINK} is already static. Each word is quoted
     * literally; in multi-word entries the separating space matches as
     * {@code \s+} so extra internal whitespace ({@code /invite  create})
     * cannot evade the strip. The trailing lookahead is the word-boundary
     * contract: a match must end the string or be followed by a
     * non-token character.
     */
    static final List<Pattern> CLOSED_LIST_PATTERNS = CLOSED_LIST.stream()
            .map(LlmOutputSanitizer::compileClosedListPattern)
            .toList();

    /**
     * Compile one entry's pattern. Case sensitivity is decided PER TOKEN,
     * mirroring what the parser does with that token — the same
     * match-what-the-consumer-sees rule {@link #canonicalizeForMatching}
     * applies to representation:
     *
     * <ul>
     *   <li><b>Command name (first word) — exact.</b>
     *       {@code InboundRouter.handleSlash} resolves it with
     *       {@code handler.name().equals(commandName)}, so
     *       {@code /Invite create} never dispatches and folding it would
     *       redact legitimate prose for no gain.</li>
     *   <li><b>Subcommand (a later word not starting with {@code --}) —
     *       folded.</b> The handlers lower-case that token before
     *       switching on it ({@code InviteCommandHandler} and
     *       {@code QuarantineCommandHandler} both do
     *       {@code split[1].toLowerCase(Locale.ROOT)}), so
     *       {@code /invite CREATE} DOES dispatch. Matching it
     *       case-sensitively left 8 of the closed list's entries evadable
     *       by changing one word's case — silently, since a non-match
     *       emits no WARN and no audit row. (M1-676 red-team finding,
     *       docs/plan/m1/redteam/M1-676-2026-07-23.md.)</li>
     *   <li><b>Flag (a later word starting with {@code --}) — exact.</b>
     *       {@code ListSourcesArgs.parse} compares flags with
     *       {@code equals}, so {@code --ALL} never dispatches.</li>
     * </ul>
     *
     * <p>ASCII-only folding is deliberate and sufficient: the pattern is
     * matched against the canonical form, where NFKC has already folded
     * fullwidth letters ({@code ＣＲＥＡＴＥ}) down to ASCII.
     */
    private static Pattern compileClosedListPattern(String token) {
        String[] words = token.split(" ");
        StringBuilder pattern = new StringBuilder(Pattern.quote(words[0]));
        for (int i = 1; i < words.length; i++) {
            pattern.append("\\s+").append(words[i].startsWith("--")
                    ? Pattern.quote(words[i])
                    : "(?i:" + Pattern.quote(words[i]) + ")");
        }
        return Pattern.compile(pattern.append("(?=$|[^a-zA-Z0-9\\-])").toString());
    }

    /** {@code [text](url)} → {@code text (url)} per acceptance item 14. */
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");

    /**
     * Run both passes in order. The output is plain text, with
     * privileged commands replaced by {@value #REDACTED_COMMAND_REPLACEMENT}
     * and markdown links flattened to {@code text (url)}. Every
     * closed-list match emits one {@code audit_log} row with action
     * {@code LLM_OUTPUT_SANITIZED}.
     *
     * @throws IllegalStateException if the audit-row INSERT fails
     *         (DB outage, lock contention, role grant revoked, etc.).
     *         The spec's per-occurrence durability commitment requires
     *         the caller to NOT emit the sanitized reply when the
     *         audit trail cannot be written.
     */
    public String sanitize(String llmOutput) {
        if (llmOutput == null || llmOutput.isEmpty()) {
            return "";
        }
        String afterMarkdown = applyMarkdownLinkStrip(llmOutput);
        ClosedListStripResult result = applyClosedListStripWithMatches(afterMarkdown);
        emitAuditRows(result.matches());
        return result.rewritten();
    }

    /**
     * Markdown-link strip pass. The regex preserves both the link text
     * AND the bare URL ({@code $1 ($2)}) so the user-visible content is
     * not lost. Runs FIRST so a hostile {@code [Click](/grant-admin)}
     * cannot hide an admin command inside link syntax.
     *
     * <p>Parsing alone cannot carry the no-link guarantee, so
     * {@link #neutralizeResidualLinkSyntax} finishes the job on whatever
     * the regex could not match — see that method.
     */
    static String applyMarkdownLinkStrip(String input) {
        Matcher m = MARKDOWN_LINK.matcher(input);
        return neutralizeResidualLinkSyntax(m.replaceAll(matchResult -> Matcher.quoteReplacement(
                matchResult.group(1) + " (" + matchResult.group(2) + ")")));
    }

    /**
     * Break the {@code ](} adjacency on any link syntax the flatten pass
     * could not parse, by inserting one space. Both characters survive, so
     * the label and the bare URL stay visible to the reader — the same
     * outcome the flatten aims for, reached without parsing.
     *
     * <p><b>Why parsing alone is not enough.</b> {@link #MARKDOWN_LINK}'s
     * label group {@code [^\]]+} cannot span a nested {@code ]}, but
     * CommonMark permits balanced brackets in a label, so
     * {@code [Read [the] report](url)} is a real link the regex will never
     * match. Balanced-bracket matching is not expressible as a regular
     * expression at all, so no amount of tightening the pattern closes
     * this; only a scanner or this adjacency break does. The break is
     * chosen because the property that matters is about TWO ADJACENT
     * CHARACTERS, not about parsing markdown — which is what lets the
     * guarantee be stated absolutely without inheriting the regex's
     * limits.
     *
     * <p><b>Why it must also run after canonicalization.</b> NFKC folds
     * the fullwidth brackets U+FF3B/U+FF3D/U+FF08/U+FF09 down to
     * {@code []()}. Text that arrived as {@code [Read [the] report ］（url）}
     * — not link syntax, and not rendered as a link by any client — has a
     * real {@code ](} after canonicalization. Delivering the canonical
     * form on a match would therefore MANUFACTURE a working link out of
     * text that was not one, which is the inverse of this pass's purpose.
     *
     * <p>Also covers the {@value #REDACTED_COMMAND_REPLACEMENT} marker
     * landing against a following {@code (}: the marker carries its own
     * brackets and the closed-list match's word-boundary lookahead
     * deliberately admits {@code (}, so replacing the token in
     * {@code /ban(url)} would otherwise emit {@code [redacted command](url)}
     * — link syntax the sanitizer built itself, after the last flatten had
     * already run. (M1-676 red-team rounds 1–3,
     * docs/plan/m1/redteam/M1-676-2026-07-23-r3.md.)
     */
    private static String neutralizeResidualLinkSyntax(String text) {
        return text.replace("](", "] (");
    }

    /**
     * Closed-list strip pass. Each {@link #CLOSED_LIST} entry is
     * matched via its precompiled {@link #CLOSED_LIST_PATTERNS} pattern
     * (literal words, internal whitespace as {@code \s+}) and every
     * occurrence is replaced with {@value #REDACTED_COMMAND_REPLACEMENT}. Emits one
     * WARN log line per match. Used by tests that want the rewrite
     * without driving the audit emission; the production path is
     * {@link #applyClosedListStripWithMatches(String)} which also
     * captures the match list for per-occurrence audit rows.
     */
    static String applyClosedListStrip(String input) {
        return applyClosedListStripWithMatches(input).rewritten();
    }

    /**
     * Carrier for the closed-list pass: the rewritten text plus the
     * list of matched tokens (one entry per occurrence, in input
     * order). The instance {@link #sanitize(String)} writes one
     * {@code audit_log} row per element of {@link #matches()} —
     * the spec's per-occurrence promise.
     */
    record ClosedListStripResult(String rewritten, List<String> matches) {}

    /**
     * Closed-list strip pass that ALSO records the matched tokens for
     * downstream per-occurrence audit emission. Emits the WARN log
     * line per match here (so the WARN-vs-row count stays 1:1).
     *
     * <p>Matching runs on the {@linkplain #canonicalizeForMatching
     * canonical} form. On zero matches the caller's ORIGINAL bytes are
     * returned, so a canonicalization that changed nothing security-
     * relevant never reaches the user: only a match may change the
     * output's representation, and a match means the text carried a
     * token that canonicalizes into a privileged command.
     *
     * <p>The markdown pass is re-applied to the canonical form before
     * matching. NFKC folds the fullwidth brackets U+FF3B, U+FF3D, U+FF08
     * and U+FF09 down to {@code []()}, so canonicalization can
     * SYNTHESIZE markdown link syntax that {@link #MARKDOWN_LINK} —
     * ASCII-bracket-only, and running on the raw bytes — could not have
     * seen. Without this the sanitizer would manufacture, on the match
     * path, exactly the label-hiding link syntax its first pass exists to
     * remove. Running it BEFORE the replacement is what keeps the
     * {@value #REDACTED_COMMAND_REPLACEMENT} marker's own brackets from
     * being treated as link text; the post-replacement neutralization
     * below then covers the marker itself, which is created after both
     * invocations have run. (M1-676 red-team rounds 1–3,
     * docs/plan/m1/redteam/M1-676-2026-07-23-r3.md.)
     */
    static ClosedListStripResult applyClosedListStripWithMatches(String input) {
        String current = applyMarkdownLinkStrip(canonicalizeForMatching(input));
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < CLOSED_LIST.size(); i++) {
            String token = CLOSED_LIST.get(i);
            Matcher m = CLOSED_LIST_PATTERNS.get(i).matcher(current);
            StringBuilder rewritten = null;
            while (m.find()) {
                if (rewritten == null) {
                    rewritten = new StringBuilder();
                }
                // Emit one WARN per match — per-occurrence, not throttled.
                LOG.warnf("LLM_OUTPUT_SANITIZED token=%s position=%d", token, m.start());
                matches.add(token);
                m.appendReplacement(rewritten, Matcher.quoteReplacement(REDACTED_COMMAND_REPLACEMENT));
            }
            if (rewritten != null) {
                m.appendTail(rewritten);
                current = rewritten.toString();
            }
        }
        if (matches.isEmpty()) {
            return new ClosedListStripResult(input, matches);
        }
        // Re-run after the replacement, not just before it: the marker is
        // created here, so it is the one piece of bracketed text neither
        // flatten invocation could have seen.
        return new ClosedListStripResult(neutralizeResidualLinkSyntax(current), matches);
    }

    /**
     * The representation the closed-list pass matches against: NFKC,
     * then the bidi-control and zero-width strip. This MUST stay the
     * same transformation the chat intake applies per non-fenced line
     * (docs/spec/security.md §Message intake step 1.7,
     * {@code InboundRouter.appendNormalized}), because that is the
     * representation the command dispatcher actually sees. Matching raw
     * bytes instead leaves a representation asymmetry the sanitizer is
     * blind to: {@code ／grant-admin} (U+FF0F), an all-fullwidth token,
     * a ZWSP- or bidi-embedded token, and a U+3000-joined multi-word
     * entry all survive a raw-byte match verbatim, yet each parses as a
     * privileged command once a reader copy-pastes the bot's line back
     * in. (M1-676; the strip half is
     * {@link IngestTextNormalizer#stripBidiAndZeroWidth}, the single
     * declaration of that codepoint set.)
     *
     * <p>Case is deliberately NOT folded here, because it is not a
     * property of the representation — the parser folds some tokens and
     * not others, so the decision belongs per token, in
     * {@link #compileClosedListPattern}, not in a blanket pass over the
     * whole string.
     */
    static String canonicalizeForMatching(String input) {
        return IngestTextNormalizer.stripBidiAndZeroWidth(
                Normalizer.normalize(input, Normalizer.Form.NFKC));
    }

    /**
     * Write one {@code audit_log} row per matched token via
     * {@link AuditLogWriter}, all in a single transaction. The spec
     * §LLM output sanitizer commits to durability — "Every match is
     * audit-logged (per-occurrence, not throttled)" — so a partial
     * write must NOT leave the caller free to send the sanitized
     * reply. Either every row commits or none do and the method
     * throws; the caller's response build aborts.
     *
     * @throws IllegalStateException if any audit-row INSERT fails;
     *         the underlying {@link SQLException} is the cause.
     */
    private void emitAuditRows(List<String> matches) {
        if (matches.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            for (String token : matches) {
                String detailsJson = "{\"match_count\":1,\"match_kind\":\""
                        + JsonEscaper.escape(token) + "\"}";
                RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                        .action(AuditAction.LLM_OUTPUT_SANITIZED)
                        .targetKind(TargetKind.SYSTEM)
                        .targetId(AUDIT_TARGET_ID)
                        .detailsJson(detailsJson)
                        .build();
                auditLogWriter.write(conn, row);
            }
            conn.commit();
        } catch (SQLException e) {
            // Spec §LLM output sanitizer is a durability commitment:
            // "Every match is audit-logged (per-occurrence, not
            // throttled)." A partial write or a failed INSERT means
            // the user-visible reply must NOT be emitted with the
            // closed-list tokens stripped — the audit trail is the
            // load-bearing operator signal for those events.
            // try-with-resources closes the connection; PgConnection
            // rolls back an active transaction on close, so the
            // partial-write case leaves audit_log unchanged.
            throw new IllegalStateException(
                    "LlmOutputSanitizer: failed to durably audit-log sanitizer hits", e);
        }
    }

}
