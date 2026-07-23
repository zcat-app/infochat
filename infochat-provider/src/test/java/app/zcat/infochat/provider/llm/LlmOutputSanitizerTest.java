package app.zcat.infochat.provider.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit + CI-completeness tests for {@link LlmOutputSanitizer}. Covers:
 * <ul>
 *   <li>One {@code @Test} per CLOSED_LIST entry asserting the token is
 *       stripped and replaced with {@code [redacted command]}.</li>
 *   <li>The markdown-link strip pass.</li>
 *   <li>The both-passes ordering (markdown FIRST, closed-list SECOND).</li>
 *   <li>The per-occurrence WARN logging captured via JUL.</li>
 *   <li>The {@code matchSetEqualsSpecClosedList} CI completeness
 *       {@code @Test} that parses {@code docs/spec/commands.md} at
 *       test tier and compares to {@link LlmOutputSanitizer#CLOSED_LIST}.</li>
 * </ul>
 */
class LlmOutputSanitizerTest {

    // These unit tests exercise the rewrite + WARN-logging passes only, so
    // they drive the package-private static helpers (applyMarkdownLinkStrip /
    // applyClosedListStrip) directly rather than the @Inject sanitizer, whose
    // sanitize() now always emits audit rows and needs a DataSource. The
    // end-to-end audit emission is covered by LlmOutputSanitizerAuditRowIT.
    private CapturingHandler logCapture;

    @BeforeEach
    void attachLogHandler() {
        logCapture = new CapturingHandler();
        Logger.getLogger(LlmOutputSanitizer.class.getName()).addHandler(logCapture);
    }

    @AfterEach
    void detachLogHandler() {
        Logger.getLogger(LlmOutputSanitizer.class.getName()).removeHandler(logCapture);
    }

    // ----- one @Test per CLOSED_LIST entry ------------------------------

    @Test
    void grantAdminTokenIsStripped() {
        assertStripped("/grant-admin");
    }

    @Test
    void revokeAdminTokenIsStripped() {
        assertStripped("/revoke-admin");
    }

    @Test
    void banTokenIsStripped() {
        assertStripped("/ban");
    }

    @Test
    void unbanTokenIsStripped() {
        assertStripped("/unban");
    }

    @Test
    void promoteTokenIsStripped() {
        assertStripped("/promote");
    }

    @Test
    void demoteTokenIsStripped() {
        assertStripped("/demote");
    }

    @Test
    void vouchTokenIsStripped() {
        assertStripped("/vouch");
    }

    @Test
    void inviteCreateTokenIsStripped() {
        assertStripped("/invite create");
    }

    @Test
    void inviteListTokenIsStripped() {
        assertStripped("/invite list");
    }

    @Test
    void inviteRevokeTokenIsStripped() {
        assertStripped("/invite revoke");
    }

    @Test
    void invitePendingContactsTokenIsStripped() {
        assertStripped("/invite pending-contacts");
    }

    @Test
    void quarantineListTokenIsStripped() {
        assertStripped("/quarantine list");
    }

    @Test
    void quarantineApproveTokenIsStripped() {
        assertStripped("/quarantine approve");
    }

    @Test
    void quarantineRejectTokenIsStripped() {
        assertStripped("/quarantine reject");
    }

    @Test
    void auditTokenIsStripped() {
        assertStripped("/audit");
    }

    @Test
    void removeSourceTokenIsStripped() {
        assertStripped("/remove-source");
    }

    @Test
    void sourceEnableTokenIsStripped() {
        assertStripped("/source-enable");
    }

    @Test
    void sourceDisableTokenIsStripped() {
        assertStripped("/source-disable");
    }

    @Test
    void listSourcesAllTokenIsStripped() {
        assertStripped("/list-sources --all");
    }

    @Test
    void listSourcesIncludeDeletedTokenIsStripped() {
        assertStripped("/list-sources --include-deleted");
    }

    @Test
    void approveGroupTokenIsStripped() {
        assertStripped("/approve-group");
    }

    @Test
    void rejectGroupTokenIsStripped() {
        assertStripped("/reject-group");
    }

    @Test
    void listGroupsTokenIsStripped() {
        assertStripped("/list-groups");
    }

    @Test
    void addSourceTokenIsStripped() {
        assertStripped("/add-source");
    }

    @Test
    void unfollowSourceTokenIsStripped() {
        assertStripped("/unfollow-source");
    }

    @Test
    void langTokenIsStripped() {
        assertStripped("/lang");
    }

    @Test
    void groupTimezoneTokenIsStripped() {
        assertStripped("/group-timezone");
    }

    @Test
    void digestTokenIsStripped() {
        assertStripped("/digest");
    }

    @Test
    void followTagTokenIsStripped() {
        assertStripped("/follow-tag");
    }

    @Test
    void unfollowTagTokenIsStripped() {
        assertStripped("/unfollow-tag");
    }

    // ----- word-boundary matching ----------------------------------------

    @Test
    void matchesBanFollowedBySpace() {
        String output = LlmOutputSanitizer.applyClosedListStrip("Try /ban user for violations.");
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "/ban followed by space must be redacted. Got: " + output);
        assertFalse(output.contains("/ban"),
                "/ban must be absent from output. Got: " + output);
    }

    @Test
    void doesNotMatchBanInsideLongerWord() {
        String output = LlmOutputSanitizer.applyClosedListStrip(
                "Check /bandwidth and /banning policies.");
        assertFalse(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "/ban must not match inside /bandwidth or /banning. Got: " + output);
        assertTrue(output.contains("/bandwidth"));
        assertTrue(output.contains("/banning"));
    }

    @Test
    void noSubstringFalsePositives() {
        String output = LlmOutputSanitizer.applyClosedListStrip(
                "The /language setting and /auditing module are unrelated.");
        assertFalse(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "/lang must not match /language, /audit must not match /auditing. Got: " + output);
        assertTrue(output.contains("/language"));
        assertTrue(output.contains("/auditing"));
    }

    @Test
    void matchesTokenAtEndOfString() {
        String output = LlmOutputSanitizer.applyClosedListStrip("Run /ban");
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "/ban at end of string must be redacted. Got: " + output);
        assertFalse(output.contains("/ban"),
                "/ban must be absent from output. Got: " + output);
    }

    // ----- multi-word internal-whitespace matching -----------------------

    @Test
    void multiWordTokenWithDoubleSpaceIsStripped() {
        String output = LlmOutputSanitizer.applyClosedListStrip("Try /invite  create now.");
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "/invite  create (two spaces) must be redacted. Got: " + output);
        assertFalse(output.contains("/invite"),
                "/invite  create must be absent from output. Got: " + output);
    }

    @Test
    void multiWordTokenWithTabIsStripped() {
        String output = LlmOutputSanitizer.applyClosedListStrip("Run /quarantine\tapprove please.");
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "/quarantine<TAB>approve must be redacted. Got: " + output);
        assertFalse(output.contains("/quarantine"),
                "/quarantine\\tapprove must be absent from output. Got: " + output);
    }

    @Test
    void multiWordTokenDoesNotMatchAcrossInterveningWords() {
        String output = LlmOutputSanitizer.applyClosedListStrip(
                "an /invite to create things");
        assertFalse(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "intervening non-whitespace words must not bridge a multi-word token. Got: "
                        + output);
        assertTrue(output.contains("/invite to create"));
    }

    @Test
    void closedListPatternsIndexAlignedWithClosedList() {
        assertEquals(LlmOutputSanitizer.CLOSED_LIST.size(),
                LlmOutputSanitizer.CLOSED_LIST_PATTERNS.size(),
                "precompiled pattern list must carry exactly one pattern per CLOSED_LIST entry");
    }

    // ----- canonical-form matching (Unicode evasion) ---------------------

    // The closed-list pass matches the canonical (NFKC + bidi/zero-width
    // stripped) form, because that is what the command dispatcher
    // consumes (security.md §Message intake step 1.7). Each variant below
    // was runtime-probed against the pre-M1-676 sanitizer: it passed
    // through verbatim yet parsed as a privileged command at intake.
    // Invisible codepoints are written as unicode escapes so the source
    // carries no unreadable characters; visible fullwidth forms are
    // written literally.

    @Test
    void fullwidthSolidusGrantAdminIsRedacted() {
        assertCanonicalEvasionRedacted("Please run ／grant-admin to fix it.", "/grant-admin");
    }

    @Test
    void allFullwidthGrantAdminIsRedacted() {
        assertCanonicalEvasionRedacted("Please run ／ｇｒａｎｔ－ａｄｍｉｎ to fix it.", "/grant-admin");
    }

    @Test
    void zeroWidthSpaceSplitGrantAdminIsRedacted() {
        // U+200B ZERO WIDTH SPACE inside the token — invisible to the
        // reader, stripped at intake.
        assertCanonicalEvasionRedacted(
                "Please run /g\u200Brant-admin to fix it.", "/grant-admin");
    }

    @Test
    void bidiIsolateSplitGrantAdminIsRedacted() {
        // U+2066 LRI ... U+2069 PDI wrapped around the token's tail.
        assertCanonicalEvasionRedacted(
                "Please run /grant-ad\u2066min\u2069 to fix it.", "/grant-admin");
    }

    @Test
    void ideographicSpaceJoinedMultiWordTokenIsRedacted() {
        // U+3000 IDEOGRAPHIC SPACE is not matched by Java's `\s` (no
        // UNICODE_CHARACTER_CLASS), but NFKC folds it to U+0020 — so the
        // multi-word pattern only sees it on the canonical form.
        assertCanonicalEvasionRedacted("Try /invite\u3000create now.", "/invite create");
    }

    @Test
    void nonMatchingUnicodeProseIsReturnedByteIdentical() {
        // The no-match fast path returns the ORIGINAL bytes: canonicalization
        // is a matching-time representation, never an output rewrite. A
        // ligature, Czech diacritics and fullwidth prose all survive NFKC
        // untouched because nothing here folds into a closed-list token.
        String input = "Čeština headlines: the ﬁrst ＮＥＷＳ digest is ready.";
        assertEquals(input, LlmOutputSanitizer.applyClosedListStrip(input),
                "output with no canonical-form closed-list token must be byte-identical to the input");
    }

    @Test
    void canonicalMatchingKeepsAsciiSpacingAndNameCaseSensitivity() {
        // Canonicalization must not regress the existing `\s+` spacing
        // match, and must not widen the pass into case-insensitivity of
        // the command NAME: handleSlash resolves it with
        // handler.name().equals(...), so /Grant-Admin never becomes a
        // command and redacting it would corrupt prose for no gain.
        String spaced = LlmOutputSanitizer.applyClosedListStrip("Try /invite  create now.");
        assertTrue(spaced.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "/invite  create (doubled ASCII space) must still be redacted. Got: " + spaced);

        String cased = LlmOutputSanitizer.applyClosedListStrip("Ask an admin to /Grant-Admin you.");
        assertEquals("Ask an admin to /Grant-Admin you.", cased,
                "a NAME case variant is not a dispatchable command and must pass through untouched");

        String casedMultiWord =
                LlmOutputSanitizer.applyClosedListStrip("Ask an admin to /Invite create one.");
        assertEquals("Ask an admin to /Invite create one.", casedMultiWord,
                "a NAME case variant does not dispatch even when the subcommand is exact");
    }

    // ----- per-token case parity with the parser -------------------------

    // The parser folds SOME tokens and not others, so the sanitizer decides
    // case per token rather than in a blanket pass:
    //   name       — handler.name().equals(...)                  → exact
    //   subcommand — split[1].toLowerCase(Locale.ROOT)           → folded
    //   flag       — tok.equals("--all")                         → exact
    // Matching the subcommand case-sensitively left 8 of the 34 closed-list
    // entries evadable by changing one word's case (2026-07-23 red-team
    // medium finding), silently: a non-match emits no WARN and no audit row.

    @Test
    void upperCaseInviteSubcommandIsRedacted() {
        assertCanonicalEvasionRedacted("An admin should run /invite CREATE --open.", "/invite create");
    }

    @Test
    void upperCaseQuarantineSubcommandIsRedacted() {
        assertCanonicalEvasionRedacted("An admin should run /quarantine APPROVE 12.", "/quarantine approve");
    }

    @Test
    void mixedCaseSubcommandIsRedacted() {
        assertCanonicalEvasionRedacted("An admin should run /invite Revoke abc.", "/invite revoke");
    }

    @Test
    void fullwidthUpperCaseSubcommandIsRedacted() {
        // NFKC folds ＣＲＥＡＴＥ to the ASCII capitals CREATE, which the
        // ASCII-only fold then matches — the two mechanisms compose.
        assertCanonicalEvasionRedacted("An admin should run /invite ＣＲＥＡＴＥ now.", "/invite create");
    }

    @Test
    void upperCaseFlagIsNotRedacted() {
        // ListSourcesArgs.parse compares flags with equals, so --ALL never
        // dispatches. The subcommand fold must not leak into flag tokens.
        String input = "Ask an admin for /list-sources --ALL please.";
        assertEquals(input, LlmOutputSanitizer.applyClosedListStrip(input),
                "an upper-case flag does not dispatch and must pass through untouched");
    }

    @Test
    void lowerCaseFlagIsStillRedacted() {
        String output = LlmOutputSanitizer.applyClosedListStrip("Ask an admin for /list-sources --all.");
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "the exact-case flag entry must still be redacted. Got: " + output);
    }

    @Test
    void canonicalizationCannotSynthesizeMarkdownLinkSyntax() {
        // NFKC folds fullwidth brackets into real []() that the raw-byte
        // MARKDOWN_LINK pass never saw. On a closed-list hit the delivered
        // text is the canonical form, so without a re-flatten the sanitizer
        // would MANUFACTURE the label-hiding link syntax its first pass
        // exists to remove (2026-07-23 red-team low finding).
        String output = LlmOutputSanitizer.applyClosedListStrip(
                "Run /ban spammer, then see ［important notice］（https://evil.example/x）");
        assertFalse(output.contains("]("),
                "the substring `](` MUST be absent even when NFKC folds fullwidth brackets. Got: "
                        + output);
        assertTrue(output.contains("important notice (https://evil.example/x)"),
                "label + bare URL must be preserved by the flatten. Got: " + output);
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "/ban must still be redacted. Got: " + output);
    }

    @Test
    void redactionMarkerIsNotConsumedAsMarkdownLinkText() {
        // The replacement literal carries its own brackets, so flattening
        // must run BEFORE the replacement — otherwise `/ban(see docs)`
        // would rewrite to `[redacted command](see docs)` and the second
        // flatten would eat the marker's brackets.
        String output = LlmOutputSanitizer.applyClosedListStrip("Run /ban(see docs)");
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "the redaction marker must survive intact. Got: " + output);
    }

    @Test
    void replacementCannotManufactureMarkdownLinkSyntax() {
        // The word-boundary lookahead admits a following `(`, and no
        // flatten runs after the replacement — so without the marker/paren
        // separation the sanitizer would itself emit the label-hiding link
        // syntax both flatten passes exist to remove. The input carries no
        // bracket characters at all, so neither flatten can catch it.
        String output = LlmOutputSanitizer.applyClosedListStrip(
                "Recovery steps: /ban(https://evil.example/reset)");
        assertFalse(output.contains("]("),
                "the sanitizer must never MANUFACTURE `](` from a match. Got: " + output);
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "the redaction marker must survive intact. Got: " + output);
        assertTrue(output.contains("(https://evil.example/reset)"),
                "the bare URL must be preserved. Got: " + output);
    }

    @Test
    void nestedBracketLinkIsNeutralizedAndTheCommandIsStillRedacted() {
        // Route (c), as it ARRIVES. MARKDOWN_LINK is a regex and cannot track
        // balanced brackets, but CommonMark permits them in a label — so this
        // is a real link the flatten will never parse. The adjacency break is
        // what carries the guarantee instead: both characters survive, so
        // label and URL stay readable, but no renderer resolves them.
        String output = LlmOutputSanitizer.applyClosedListStrip(
                "Run /ban spammer. [Read [the] report](https://evil.example/phish)");
        assertFalse(output.contains("]("),
                "an un-parseable link must not be DELIVERED link-shaped. Got: " + output);
        assertTrue(output.contains("[Read [the] report] (https://evil.example/phish)"),
                "label and URL must both survive the break. Got: " + output);
        // The two passes are independent: the match's word-boundary lookahead
        // admits `)`, so a token inside a link TARGET is redacted whether or
        // not the flatten fired. Neutralization is defense-in-depth for
        // rendering, never a precondition of the closed-list strip.
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "an un-parseable link must NOT cost the redaction. Got: " + output);
        assertFalse(output.contains("/ban"),
                "/ban must be redacted whether or not the flatten fired. Got: " + output);
    }

    @Test
    void fullwidthClosedNestedBracketLinkIsNotTurnedIntoAWorkingLink() {
        // Route (c), SYNTHESIZED — the one case that is new-in-diff rather
        // than pre-existing. The text arrives with a fullwidth `］`, which no
        // client renders as a link; NFKC folds it to the `]` that COMPLETES
        // one, and the nested label keeps the flatten from parsing it. Left
        // alone, delivering the canonical form on a match would manufacture a
        // working link out of text that was not one — verified against real
        // CommonMark rules, which render exactly this string as <a href=...>.
        // (docs/plan/m1/redteam/M1-676-2026-07-23-r3.md.)
        String output = LlmOutputSanitizer.applyClosedListStrip(
                "Run /ban. [Read [the] report ］（https://evil.example/phish）");
        assertFalse(output.contains("]("),
                "canonicalization must not MANUFACTURE a working link. Got: " + output);
        assertTrue(output.contains("https://evil.example/phish"),
                "the URL must stay visible to the reader. Got: " + output);
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "/ban must still be redacted. Got: " + output);
    }

    @Test
    void unpairedFoldedBracketPairIsNeutralizedToo() {
        // The degenerate form of the same route: fullwidth `］（` with no
        // opening bracket at all. CommonMark does not resolve it (the marker's
        // own `[` is consumed by its own `]`), so this is cosmetic rather than
        // exploitable — but the delivered-output rule is stated absolutely, so
        // it must hold here too rather than resting on a renderer's behaviour.
        String output = LlmOutputSanitizer.applyClosedListStrip(
                "Run /ban then ］（https://evil.example/x）");
        assertFalse(output.contains("]("),
                "the absolute no-`](` rule must not depend on renderer quirks. Got: " + output);
    }

    // ----- markdown-link strip pass -------------------------------------

    @Test
    void markdownLinkIsFlattenedToTextPlusBareUrl() {
        String input = "Read [Bleeping Computer](https://www.bleepingcomputer.com) for details.";
        String output = LlmOutputSanitizer.applyMarkdownLinkStrip(input);
        assertFalse(output.contains("]("),
                "the substring `](` MUST be absent after sanitization");
        assertTrue(output.contains("Bleeping Computer (https://www.bleepingcomputer.com)"),
                "link text + bare URL MUST be preserved verbatim; got: " + output);
    }

    @Test
    void unparseableLinkIsNeutralizedWithNoClosedListTokenPresent() {
        // Pins the NO-MATCH delivery path, which every other `](` assertion
        // misses: those inputs all embed a closed-list token, so they exit
        // through the match path and its post-replacement neutralization.
        // When nothing matches, sanitize() returns applyMarkdownLinkStrip's
        // output verbatim, so the neutralization INSIDE this pass is the only
        // thing between an un-parseable link and the reader — on the commonest
        // production shape of all, LLM prose carrying a link and no command.
        String output = LlmOutputSanitizer.applyMarkdownLinkStrip(
                "See [Read [the] report](https://evil.example/phish) for details.");
        assertFalse(output.contains("]("),
                "the no-match delivery path must not ship link syntax either. Got: " + output);
        assertTrue(output.contains("[Read [the] report] (https://evil.example/phish)"),
                "label and URL must both survive the break. Got: " + output);
    }

    @Test
    void markdownLinkHidingPrivilegedCommandIsStillStripped() {
        // The markdown-link strip pass runs FIRST and flattens
        // `[Click for admin](/grant-admin)` to
        // `Click for admin (/grant-admin)`; the closed-list pass then
        // sees and replaces the bare token. The end-to-end output is
        // `Click for admin ([redacted command])`.
        String input = "[Click for admin](/grant-admin)";
        String output = LlmOutputSanitizer.applyClosedListStrip(
                LlmOutputSanitizer.applyMarkdownLinkStrip(input));
        assertEquals("Click for admin (" + LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT + ")",
                output,
                "ordering must be markdown FIRST then closed-list SECOND");
    }

    // ----- per-occurrence WARN logging ----------------------------------

    @Test
    void threeMatchesProduceThreeWarnRecords() {
        String input = "Suggest /grant-admin to ops; meanwhile /ban offender, then /promote staff.";
        LlmOutputSanitizer.applyClosedListStrip(input);
        // Filter by Level intValue rather than identity: JBoss LogManager
        // emits records with org.jboss.logmanager.Level.WARN (a custom
        // subclass of java.util.logging.Level), which is not == to
        // java.util.logging.Level.WARNING. The intValue (900) is the
        // load-bearing identity for both.
        List<LogRecord> warnRecords = logCapture.records.stream()
                .filter(r -> r.getLevel().intValue() == Level.WARNING.intValue())
                .toList();
        assertEquals(3, warnRecords.size(),
                "exactly 3 WARN records — one per match, no throttling. Captured: "
                        + logCapture.formatted());
    }

    // ----- spec-vs-runtime completeness ---------------------------------

    @Test
    void matchSetEqualsSpecClosedList() throws IOException {
        Path specPath = locateSpec();
        assertNotNull(specPath, "docs/spec/commands.md must be locatable from the test working dir");

        Set<String> specSet = parseSpecClosedList(specPath);
        Set<String> runtimeSet = new HashSet<>(LlmOutputSanitizer.CLOSED_LIST);

        Set<String> onlyInSpec = new HashSet<>(specSet);
        onlyInSpec.removeAll(runtimeSet);
        Set<String> onlyInRuntime = new HashSet<>(runtimeSet);
        onlyInRuntime.removeAll(specSet);

        assertTrue(onlyInSpec.isEmpty() && onlyInRuntime.isEmpty(),
                "LlmOutputSanitizer.CLOSED_LIST must equal the spec's closed list.\n"
                        + "Spec has but runtime lacks: " + onlyInSpec + "\n"
                        + "Runtime has but spec lacks: " + onlyInRuntime);
    }

    /**
     * Resolve {@code docs/spec/commands.md} from the test working
     * directory. Tests run from each module's directory (e.g.
     * {@code infochat-provider/}), so the path is {@code ../docs/spec/commands.md}
     * relative to module-run, but a repo-root run sees
     * {@code docs/spec/commands.md} directly. Walk a small candidate
     * set so either invocation works.
     */
    private static Path locateSpec() {
        List<Path> candidates = List.of(
                Paths.get("docs/spec/commands.md"),
                Paths.get("..", "docs", "spec", "commands.md"),
                Paths.get("../../docs/spec/commands.md")
        );
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /**
     * Parse the closed-list section from the spec markdown. The spec
     * exposes two bullets directly under the
     * {@code ## Permission model} heading: {@code **Bot-admin only:**}
     * and {@code **Group-admin (or bot admin acting in the group):**}.
     * Each carries backticked tokens of the form
     * {@code `/<word>[ <flag>]`}; this method extracts them.
     *
     * <p>The parser is narrowly anchored: it looks for the two bullet
     * labels in the section that begins with the heading whose text
     * starts with "Permission model". A future spec edit that adds a
     * third group, splits the prose, or renames the labels breaks the
     * parser; the breakage surfaces as a test-tier failure with a
     * clear diff (see {@link #matchSetEqualsSpecClosedList}). The
     * desired refactor at that point is a parser update, NOT a
     * silent runtime-side accommodation.
     */
    static Set<String> parseSpecClosedList(Path specPath) throws IOException {
        List<String> lines = Files.readAllLines(specPath);
        Set<String> tokens = new HashSet<>();
        boolean inPermissionModel = false;
        boolean inBulletGroup = false;
        // Backticked-token pattern: matches `/word(-word)*( flag)*`.
        Pattern tokenPattern = Pattern.compile("`(/[A-Za-z0-9\\-]+(?:\\s+(?:--?)?[A-Za-z0-9\\-]+)*)`");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                String heading = trimmed.substring(3).trim().toLowerCase();
                inPermissionModel = heading.equals("permission model");
                inBulletGroup = false;
                continue;
            }
            if (!inPermissionModel) {
                continue;
            }
            // Detect the two bullet-group labels. The `**Bot-admin only:**`
            // marker can appear at the start of a line OR right after `- `.
            if (trimmed.contains("**Bot-admin only:**")
                    || trimmed.contains("**Group-admin")) {
                inBulletGroup = true;
            }
            if (!inBulletGroup) {
                continue;
            }
            // Stop the bullet group when an empty line follows.
            if (trimmed.isEmpty()) {
                inBulletGroup = false;
                continue;
            }
            // Stop if we hit a new heading or the closing prose
            // paragraph (a line that starts with `The full per-actor-tier`
            // ends the bullet group region in the current spec).
            if (trimmed.startsWith("##") || trimmed.startsWith("The full per-actor-tier")) {
                inBulletGroup = false;
                continue;
            }
            Matcher m = tokenPattern.matcher(line);
            while (m.find()) {
                String token = m.group(1);
                // The spec writes `/add-source` in groups, `/unfollow-source` in groups, etc.
                // The trailing "in groups" qualifier is prose, not part of the token.
                tokens.add(token);
            }
        }
        return tokens;
    }

    // ----- helpers ------------------------------------------------------

    /**
     * Assert that a CLOSED_LIST {@code token} appearing inside an
     * LLM-prose blob is (a) absent from the output and (b) replaced by
     * {@code [redacted command]} at the position the match was.
     */
    private void assertStripped(String token) {
        String input = "Please run " + token + " to fix it.";
        String output = LlmOutputSanitizer.applyClosedListStrip(input);
        assertFalse(output.contains(token),
                "the original command string MUST be absent from sanitized output. "
                        + "Token=" + token + " Output=" + output);
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "[redacted command] MUST appear at the position the match was. "
                        + "Token=" + token + " Output=" + output);
    }

    /**
     * Assert that a Unicode-obfuscated {@code token} — one that reaches the
     * sanitizer in a representation the raw-byte match could not see, but
     * that canonicalizes into a real closed-list entry — is redacted, and
     * that it yields exactly ONE audit-row-worthy match recorded under the
     * token's canonical form. One match element is one
     * {@code LLM_OUTPUT_SANITIZED} row (LlmOutputSanitizer.emitAuditRows),
     * so this is the per-occurrence durability commitment at unit tier.
     */
    private void assertCanonicalEvasionRedacted(String input, String canonicalToken) {
        LlmOutputSanitizer.ClosedListStripResult result =
                LlmOutputSanitizer.applyClosedListStripWithMatches(input);
        assertTrue(result.rewritten().contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "[redacted command] MUST replace the obfuscated token. Input=" + input
                        + " Output=" + result.rewritten());
        assertFalse(result.rewritten().contains(canonicalToken),
                "the token's canonical form MUST be absent from the output. Token="
                        + canonicalToken + " Output=" + result.rewritten());
        assertEquals(List.of(canonicalToken), result.matches(),
                "exactly one audit-row-worthy match, recorded under the canonical token");
    }

    /** JUL capturing handler — JBoss Logging routes through JUL by default. */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        String formatted() {
            StringBuilder sb = new StringBuilder("[");
            for (LogRecord r : records) {
                sb.append(r.getLevel()).append(": ").append(r.getMessage()).append("; ");
            }
            return sb.append("]").toString();
        }
    }
}
