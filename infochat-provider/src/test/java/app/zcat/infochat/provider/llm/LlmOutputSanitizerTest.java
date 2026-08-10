package app.zcat.infochat.provider.llm;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.Duration;
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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit + CI-completeness tests for {@link LlmOutputSanitizer}. Covers:
 * <ul>
 *   <li>One {@code @Test} per CLOSED_LIST entry asserting the token is
 *       stripped and replaced with {@code [redacted command]}.</li>
 *   <li>The scaffolding-marker strip pass.</li>
 *   <li>The markdown-link strip pass.</li>
 *   <li>The pass ordering (character-deleting passes FIRST, closed-list
 *       LAST).</li>
 *   <li>The aggregated WARN logging (one WARN per distinct token per
 *       call, carrying the exact occurrence count) captured via JUL,
 *       and the aggregated audit-row shape driven through a capturing
 *       {@link AuditLogWriter}.</li>
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

    // ----- flag entries match at any argument position -------------------

    // ListSourcesArgs.parse loops over EVERY token from index 1, so a flag
    // sitting behind --page dispatches the admin-only listing identically
    // to the adjacent form. Matching only the adjacent form shipped that
    // line verbatim — no marker, no WARN, no audit row (M1-680).

    @Test
    void allFlagBehindAnotherArgumentIsRedacted() {
        LlmOutputSanitizer.ClosedListStripResult result =
                LlmOutputSanitizer.applyClosedListStripWithMatches("Run /list-sources --page 1 --all now.");
        assertTrue(result.rewritten().contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "--all behind --page still dispatches and must be redacted. Got: " + result.rewritten());
        assertFalse(result.rewritten().contains("--all"),
                "the dispatching flag must be absent from output. Got: " + result.rewritten());
        assertEquals(List.of("/list-sources --all"), result.matches(),
                "one audit-row-worthy match per occurrence");
    }

    @Test
    void includeDeletedFlagBehindAnotherArgumentIsRedacted() {
        LlmOutputSanitizer.ClosedListStripResult result =
                LlmOutputSanitizer.applyClosedListStripWithMatches("Run /list-sources --page 2 --include-deleted now.");
        assertTrue(result.rewritten().contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "--include-deleted behind --page must be redacted. Got: " + result.rewritten());
        assertFalse(result.rewritten().contains("--include-deleted"),
                "the dispatching flag must be absent from output. Got: " + result.rewritten());
        assertEquals(List.of("/list-sources --include-deleted"), result.matches(),
                "one audit-row-worthy match per occurrence");
    }

    @Test
    void bareListSourcesIsNotRedacted() {
        // The closed list privileges the FLAG forms, not the command word:
        // /list-sources without an admin flag is a non-privileged command
        // any caller may run, so redacting it would strip it from
        // legitimate prose.
        String bare = "Run /list-sources to see what you follow.";
        assertEquals(bare, LlmOutputSanitizer.applyClosedListStrip(bare),
                "the bare command is not privileged and must pass through untouched");
        String paged = "Run /list-sources --page 2 to see the next page.";
        assertEquals(paged, LlmOutputSanitizer.applyClosedListStrip(paged),
                "a non-admin flag does not make the command privileged");
    }

    @Test
    void punctuationBearingSameLineArgumentIsRedacted() {
        // The parser has no else branch: ListSourcesArgs.parse ignores
        // unknown tokens, so a token carrying . ! ? or / is still a real
        // argument and --all still dispatches all=true. The match spans
        // the parser's whole argument run, not a sentence, so these
        // forms ARE redacted
        // (M1-680 red-team high finding; the earlier sentence-boundary
        // shape shipped them verbatim, no marker/WARN/audit row).
        for (String input : new String[]{
                "To audit every feed run: /list-sources --filter rss/news --all",
                "/list-sources why? --all",
                "/list-sources --page 1. --all"}) {
            String output = LlmOutputSanitizer.applyClosedListStrip(input);
            assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                    "a punctuation-bearing same-line argument must not evade the match. Got: " + output);
            assertFalse(output.contains("--all"),
                    "the dispatching flag must be absent from output. Got: " + output);
        }
    }

    @Test
    void flagOnFollowingLineIsMatched() {
        // The router hands the handler the WHOLE multi-line body and the
        // parser tokenizes it with split("\\s+"), where \n is whitespace
        // — so a --all on ANY line after /list-sources dispatches
        // all=true. The argument run spans lines, and the scan mirrors it
        // (M1-680 red-team round-3 high finding: the line-bounded scan
        // let these dispatch while evading the match — and regressed the
        // adjacent \n case the pre-M1-680 \s+ regex used to catch).
        LlmOutputSanitizer.ClosedListStripResult adjacent =
                LlmOutputSanitizer.applyClosedListStripWithMatches("/list-sources\n--all");
        assertTrue(adjacent.rewritten().contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a flag on the following line dispatches and must be redacted. Got: " + adjacent.rewritten());
        assertFalse(adjacent.rewritten().contains("--all"),
                "the dispatching flag must be absent from output. Got: " + adjacent.rewritten());
        assertEquals(List.of("/list-sources --all"), adjacent.matches());
        String prose = "Run /list-sources --page 1\nThen --all is separate.";
        String output = LlmOutputSanitizer.applyClosedListStrip(prose);
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "the argument run spans the whole message, not a line. Got: " + output);
        assertFalse(output.contains("--all"),
                "the dispatching flag must be absent from output. Got: " + output);
    }

    @Test
    void carriageReturnSeparatedFlagIsRedacted() {
        // The parser tokenizes with split("\\s+"), where \r is whitespace,
        // and the router splits lines on \n only — so \r is an intra-line
        // token SEPARATOR and /list-sources\r--all dispatches all=true. The
        // scan treats \r as a separator (not a line boundary) to match it,
        // closing the same evasion class as the punctuation case via \r
        // (M1-680 red-team low finding, docs/plan/m1/redteam/M1-680-2026-07-23-r2.md).
        String output = LlmOutputSanitizer.applyClosedListStrip("/list-sources\r--all");
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a CR-separated flag dispatches and must be redacted. Got: " + output);
        assertFalse(output.contains("--all"),
                "the dispatching flag must be absent. Got: " + output);
    }

    @Test
    void flagCommandTokenBoundariesMirrorTheParser() {
        // No leading boundary on the command word — `/` is the copy-paste
        // start, so a command glued to a preceding word still dispatches
        // and is redacted; but a command word glued to a following
        // non-whitespace char is a different token that resolves to no
        // handler, so it is not redacted.
        LlmOutputSanitizer.ClosedListStripResult glued =
                LlmOutputSanitizer.applyClosedListStripWithMatches("see foo/list-sources --all here");
        assertTrue(glued.rewritten().contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a command glued to a preceding word still dispatches. Got: " + glued.rewritten());
        assertEquals(List.of("/list-sources --all"), glued.matches());
        assertEquals("/list-sourcesX --all",
                LlmOutputSanitizer.applyClosedListStrip("/list-sourcesX --all"),
                "the command word must be followed by whitespace to be a command token");
        // Two dispatching occurrences on one line yield two audit-worthy matches.
        assertEquals(List.of("/list-sources --all", "/list-sources --all"),
                LlmOutputSanitizer.applyClosedListStripWithMatches(
                        "/list-sources --all and /list-sources --all").matches(),
                "one match per occurrence");
    }

    @Test
    void adversarialFlagScanIsLinearNotQuadratic() {
        // Flag entries are matched by a single left-to-right token scan,
        // not a regex, because no regex matches a flag at any argument
        // position both linearly and without an evasion. Two adversarial
        // shapes were live DOS findings on regex attempts:
        //   round 1 — a long whitespace run (a lazy run then greedy \s+ is
        //             quadratic on it);
        //   round 2 — many command-word occurrences (an unbounded lazy run
        //             re-anchors at each occurrence and rescans to
        //             end-of-input, O(P x L));
        // plus the cross-line variant of round 2, since the argument run
        // spans newlines. All must complete near-instantly. The 3s bound
        // clears the linear scan (~100ms through the full pass at these
        // sizes) by ~20x and fails only if a super-linear matcher is
        // reintroduced. (M1-680 red-team DOS findings,
        // docs/plan/m1/redteam/M1-680-2026-07-23-r2.md.)
        String whitespaceRun = "/list-sources" + " ".repeat(100_000);
        String manyAnchors = "/list-sources ".repeat(100_000);   // ~1.4 MB, no flag
        String crossLineAnchors = "/list-sources\n".repeat(100_000);  // ~1.3 MB, no flag
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertEquals(whitespaceRun, LlmOutputSanitizer.applyClosedListStrip(whitespaceRun),
                    "a whitespace-only run carrying no flag must not match");
            assertEquals(manyAnchors, LlmOutputSanitizer.applyClosedListStrip(manyAnchors),
                    "many command-word occurrences with no flag must not match");
            assertEquals(crossLineAnchors, LlmOutputSanitizer.applyClosedListStrip(crossLineAnchors),
                    "many newline-separated command occurrences with no flag must not match");
        });
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

    // ----- scaffolding-marker strip (M1-789) ------------------------------

    @Test
    void scaffoldingMarkersAreStrippedAndTheWrappedTextSurvives() {
        // REPRODUCTION (the parked M1779ReproProbeIT scaffolding method):
        // the model echoes the wrapper it was given — neither marker may
        // reach the reader, the wrapped text must.
        String id = "e4cea7de-0f11-4a7d-9f88-d2ecd6bcae2e";
        String wrapped = "Série „Softwarová sklizeň\" na Root.cz se věnuje…";
        String output = SanitizerTestDoubles.noAuditSanitizer().sanitize(
                "<<<UNTRUSTED_CONTENT id=\"" + id + "\">>>\n"
                        + wrapped + "\n"
                        + "<<<END id=\"" + id + "\">>>");
        assertEquals(wrapped, output,
                "neither marker may survive, and the wrapped text must");
    }

    @Test
    void aMarkerOnlyLineIsDroppedNotBlanked() {
        // Blanking instead would lead/tail the reply with empty lines the
        // model never wrote.
        String output = LlmOutputSanitizer.applyScaffoldingMarkerStrip(
                "intro\n<<<END id=\"x\">>>\noutro");
        assertEquals("intro\noutro", output,
                "the marker line goes; no blank line takes its place");
    }

    @Test
    void scaffoldingStripWalksAManyLineReplyWithoutDecomposingIt() {
        // 200k lines under the existing 3s adversarial bound: a per-line
        // String decomposition would turn an in-cap reply (§Trust
        // boundaries item 9) into a live-heap multiple of itself.
        String reply = "<<<END id=\"x\">>>\nb\n".repeat(100_000);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertEquals("b\n".repeat(100_000),
                        LlmOutputSanitizer.applyScaffoldingMarkerStrip(reply),
                        "marker-only lines drop, prose lines survive, in order"));
    }

    // ----- scaffolding line isolation (M1-790 round 2) ------------------

    @Test
    void emphasisJoiningCannotAssembleAScaffoldingMarkerPastTheStrip() {
        // FAILURE-MODE: emphasis deletion joins UNTR+USTED and E+N+D into
        // the wrapper keywords; the strip runs AFTER the downgrade, so the
        // manufactured marker is seen and its line drops.
        LlmOutputSanitizer sanitizer = SanitizerTestDoubles.noAuditSanitizer();
        assertEquals("", sanitizer.sanitize("<<<UNTR*USTED*_CONTENT id=\"x\">>>"),
                "an emphasis-assembled opener must not reach the reader");
        assertEquals("", sanitizer.sanitize("<<<E*N*D id=\"x\">>>"),
                "an emphasis-assembled closer must not reach the reader");
        String output = sanitizer.sanitize("pre\n<<<E*N*D id=\"x\">>>\npost");
        assertEquals("pre\npost", output,
                "the prose lines survive; the marker line drops");
        assertFalse(output.contains("<<<"),
                "no marker fragment may survive");
    }

    @Test
    void aNestedMarkerCannotAssembleAMarkerPastTheIsolation() {
        // FAILURE-MODE: the strip never excises-and-joins, so the nested
        // inner marker cannot manufacture an outer one — the whole
        // marker-bearing line drops instead.
        assertEquals("", SanitizerTestDoubles.noAuditSanitizer().sanitize(
                "<<<E<<<END id=\"x\">>>ND id=\"y\">>>"),
                "no joined marker survives a nested marker line");
    }

    @Test
    void aMarkerBearingLineIsDroppedWholesaleNotExtractedAround() {
        // DELIBERATE CONTRACT CHANGE (supersedes M1-789's prose-keeping):
        // extraction joined fragments and could assemble a marker, so a
        // marker-bearing line now loses its prose.
        assertEquals("", SanitizerTestDoubles.noAuditSanitizer().sanitize(
                "prose <<<END id=\"x\">>> more prose"),
                "the whole line drops; extraction is gone");
    }

    @Test
    void aCommandBearingMarkerIdIsStillNotAMarker() {
        // The /-in-id exclusion is unchanged: id="/ban" is not a marker,
        // the line survives the strip, the closed-list pass redacts+rows.
        List<RedactionHook.AuditRow> rows = new ArrayList<>();
        LlmOutputSanitizer sanitizer = new LlmOutputSanitizer(
                capturingAuditWriter(rows), SanitizerTestDoubles.noOpDataSource());
        String output = sanitizer.sanitize("<<<END id=\"/ban\">>>");
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "the command redacts; the line does not vanish. Got: " + output);
        assertFalse(output.contains("/ban"), "the command word must not survive");
        assertEquals(1, rows.size(), "one aggregated row for the redacted token");
        assertTrue(rows.get(0).detailsJson().contains("\"match_kind\":\"/ban\""),
                "the row must name the token; got: " + rows.get(0).detailsJson());
    }

    @Test
    void aClosedListTokenOnADroppedLineIsStillRowed() {
        // Audit-on-drop: /ban shares its line with a marker, so the line
        // drops — but the canonical-form match runs first and rows it.
        List<RedactionHook.AuditRow> rows = new ArrayList<>();
        LlmOutputSanitizer sanitizer = new LlmOutputSanitizer(
                capturingAuditWriter(rows), SanitizerTestDoubles.noOpDataSource());
        String output = sanitizer.sanitize("/ban <<<END id=\"x\">>>");
        assertEquals("", output, "the marker-bearing line drops wholesale");
        assertEquals(1, rows.size(), "the dropped line's token is still rowed");
        String detailsJson = rows.get(0).detailsJson();
        assertTrue(detailsJson.contains("\"match_kind\":\"/ban\""),
                "the row must name the token; got: " + detailsJson);
        assertTrue(detailsJson.contains("\"match_count\":1"),
                "the row must carry the exact count 1; got: " + detailsJson);
    }

    @Test
    void anUnclosedOpenerDropsOnlyItsOwnLine() {
        // Line-scope only: no cross-line block state, so an opener with no
        // closer cannot eat the rest of the reply.
        assertEquals("prose line one\nprose line two",
                SanitizerTestDoubles.noAuditSanitizer().sanitize(
                        "<<<UNTRUSTED_CONTENT id=\"x\">>>\nprose line one\nprose line two"),
                "only the marker line drops");
    }

    // ----- plain-text downgrade (M1-790) --------------------------------

    @Test
    void markdownEmphasisIsDowngradedAndThematicBreaksAreDropped() {
        // REPRODUCTION (the parked M1779ReproProbeIT markdown method,
        // v1.1.0 live test §F5): no ** and no thematic break may reach
        // the reader, the emphasized words must.
        String output = SanitizerTestDoubles.noAuditSanitizer().sanitize(
                "**Security flaws and fixes** – a large part of the news.\n"
                        + "- **JanusCape**: a flaw allowing VM escape\n"
                        + "---");
        assertEquals("Security flaws and fixes – a large part of the news.\n"
                        + "· JanusCape: a flaw allowing VM escape",
                output,
                "emphasis removed, bullet downgraded, thematic break dropped");
    }

    @Test
    void d30AllowedSetSurvivesTheDowngrade() {
        // D30's ALLOWED set is untouched BY THIS PASS: inline single-backtick
        // spans, triple-backtick fenced blocks (delimiters AND contents), and
        // bare URLs pass through; list markers downgrade to `· `.
        String output = LlmOutputSanitizer.applyPlainTextDowngrade(
                "- `inline *code* span` stays\n"
                        + "- bare URL https://example.com/a*b*c stays\n"
                        + "```java\n"
                        + "int a**b = 2; // fenced **content** stays\n"
                        + "```");
        assertEquals("· `inline *code* span` stays\n"
                        + "· bare URL https://example.com/a*b*c stays\n"
                        + "```java\n"
                        + "int a**b = 2; // fenced **content** stays\n"
                        + "```",
                output,
                "the D30 allowed set passes through; only markers downgrade");
    }

    @Test
    void bareUrlWithANonLowercaseSchemeIsProtectedToo() {
        // FAILURE-MODE (P4): RFC 3986 schemes are case-insensitive, so
        // the guard must be too — a non-lowercase scheme's destination
        // is never rewritten by the emphasis deletion.
        String input = "See HTTPS://host/a*b*c for the advisory.";
        String output = SanitizerTestDoubles.noAuditSanitizer().sanitize(input);
        assertEquals(input, output,
                "the bare-URL guard is scheme-case-insensitive");
    }

    @Test
    void emphasisDeletionCannotAssembleACommandPastTheRedaction() {
        // FAILURE-MODE (P5, closed-list half) AND the pass-ordering pin:
        // the deletion joins /b**a**n into /ban, and the downgrade runs
        // BEFORE the closed-list strip, so the join is re-scanned and rowed.
        List<RedactionHook.AuditRow> rows = new ArrayList<>();
        LlmOutputSanitizer sanitizer = new LlmOutputSanitizer(
                capturingAuditWriter(rows), SanitizerTestDoubles.noOpDataSource());

        String output = sanitizer.sanitize("/b**a**n");

        assertEquals(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT, output,
                "the emphasis-joined command must not survive");
        assertEquals(1, rows.size(), "the joined token is re-scanned and rowed");
        String detailsJson = rows.get(0).detailsJson();
        assertTrue(detailsJson.contains("\"match_kind\":\"/ban\""),
                "the row must name the joined token; got: " + detailsJson);
        assertTrue(detailsJson.contains("\"match_count\":1"),
                "the row must carry the exact count 1; got: " + detailsJson);
    }

    @Test
    void aThematicBreakWithACarriageReturnIsDroppedToo() {
        // P9: a CRLF endpoint trails the line with \r; the drop must
        // tolerate it so the spec sentence stays absolute.
        String output = LlmOutputSanitizer.applyPlainTextDowngrade(
                "intro\r\n---\r\noutro");
        assertEquals("intro\r\noutro", output,
                "the thematic-break line drops even with a trailing CR");
    }

    @Test
    void plainTextDowngradeWalksAManyLineReplyWithoutDecomposingIt() {
        // P1: 200k lines through the index walk under the existing 3s
        // adversarial bound — no per-line decomposition.
        String reply = "**a**\n- b\n".repeat(100_000);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertEquals("a\n· b\n".repeat(100_000),
                        LlmOutputSanitizer.applyPlainTextDowngrade(reply),
                        "emphasis and markers downgrade, in order, in one walk"));
    }

    @Test
    void aOneLineReplyFullOfVerbatimSpansStaysLinear() {
        // FAILURE-MODE (round-1 FINDING 2): one hostile LINE under the
        // body cap must not pin the worker thread — the span bookkeeping
        // is a linear merge, not a per-span full-list walk.
        String codeSpans = "`a` ".repeat(300_000);
        String urls = "http://x ".repeat(150_000);
        String reply = "**b** " + codeSpans + urls;
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertEquals("b " + codeSpans + urls,
                        LlmOutputSanitizer.applyPlainTextDowngrade(reply),
                        "emphasis downgrades; every span and URL passes through"));
    }

    // ----- internal config identifier strip (M1-815) --------------------

    @Test
    void configKeyTokensAreStrippedFromLlmOutput() {
        // REPRODUCTION (live test 2026-08-10 E9): an adversarial refusal
        // volunteers a raw config key mid-sentence; the dotted token must
        // not survive, the surrounding prose must.
        String output = SanitizerTestDoubles.noAuditSanitizer().sanitize(
                "I can't discuss the internals. The window is set by "
                        + "infochat.probation.duration in this deployment. "
                        + "Ask me about the news instead.");
        assertEquals("I can't discuss the internals. The window is set by "
                        + "  in this deployment. Ask me about the news instead.",
                output,
                "the dotted config token is stripped; the surrounding prose survives");
        assertFalse(output.contains("infochat."),
                "no dotted infochat token may survive; got: " + output);
    }

    @Test
    void emphasisJoinedConfigKeyIsStillStripped() {
        // FAILURE-MODE (P8 ordering): the downgrade joins the fragments
        // FIRST; a config-key pass placed before the downgrade would let
        // the split token through.
        String output = SanitizerTestDoubles.noAuditSanitizer().sanitize(
                "See infochat.prob**a**tion.duration for the window.");
        assertEquals("See   for the window.", output,
                "the emphasis-joined token is stripped after the downgrade joins it");
    }

    @Test
    void configKeyInsideAMarkerIdIsDroppedAndRowed() {
        // FAILURE-MODE (P8 ordering + audit-on-drop): the config-key pass
        // runs BEFORE the scaffolding strip, so the token is rowed before
        // its marker line drops wholesale.
        List<RedactionHook.AuditRow> rows = new ArrayList<>();
        LlmOutputSanitizer sanitizer = new LlmOutputSanitizer(
                capturingAuditWriter(rows), SanitizerTestDoubles.noOpDataSource());
        String output = sanitizer.sanitize(
                "intro\n<<<END id=\"infochat.probation.duration\">>>\noutro");
        assertEquals("intro\noutro", output, "the marker-bearing line drops wholesale");
        assertEquals(1, rows.size(), "the config token is rowed before the line drops");
        String detailsJson = rows.get(0).detailsJson();
        assertTrue(detailsJson.contains("\"match_kind\":\"infochat.probation.duration\""),
                "the row must name the token; got: " + detailsJson);
        assertTrue(detailsJson.contains("\"match_count\":1"),
                "the row must carry the exact count 1; got: " + detailsJson);
    }

    @Test
    void unicodeLetterBeforeInfochatDoesNotStartToken() {
        // FAILURE-MODE (left boundary): the boundary is letter/digit,
        // not ASCII-only — a Unicode letter before the root word means
        // the shape is not a config token and survives byte-identical.
        LlmOutputSanitizer sanitizer = SanitizerTestDoubles.noAuditSanitizer();
        String input = "éinfochat.probation.duration";
        assertEquals(input, sanitizer.sanitize(input),
                "a Unicode letter before the root word blocks the match");
    }

    @Test
    void plainMentionsOfInfochatSurvive() {
        // FAILURE-MODE (over-breadth): the category is the dotted config
        // shape, never the bare word — prose mentions survive (a greedy
        // `infochat\S*` regex fails this).
        LlmOutputSanitizer sanitizer = SanitizerTestDoubles.noAuditSanitizer();
        assertEquals("infochat is a news bot.",
                sanitizer.sanitize("infochat is a news bot."),
                "a bare-word mention survives byte-identical");
        assertEquals("See infochat. The bot posts summaries.",
                sanitizer.sanitize("See infochat. The bot posts summaries."),
                "a sentence-ending bare word survives byte-identical");
    }

    // ----- aggregated WARN logging + audit-row shape (M1-737) ----------

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
                "exactly 3 WARN records — one per distinct token, no throttling. Captured: "
                        + logCapture.formatted());
    }

    @Test
    void repeatedTokenCollapsesToOneWarnCarryingCount() {
        // M1-737 acceptance: the one-WARN-per-match log line at the
        // strip loop collapses to one WARN per distinct token per call,
        // carrying the count — N occurrences of one token cost one log
        // line, and the count keeps the signal lossless.
        String input = "Run /audit now, then /audit again, and /audit thrice.";
        LlmOutputSanitizer.applyClosedListStrip(input);
        List<LogRecord> warnRecords = logCapture.records.stream()
                .filter(r -> r.getLevel().intValue() == Level.WARNING.intValue())
                .toList();
        assertEquals(1, warnRecords.size(),
                "three occurrences of ONE token must collapse to one WARN. Captured: "
                        + logCapture.formatted());
        String rendered = renderLogMessage(warnRecords.get(0));
        assertTrue(rendered.contains("token=/audit"),
                "the collapsed WARN must name the token; got: " + rendered);
        assertTrue(rendered.contains("count=3"),
                "the collapsed WARN must carry the exact occurrence count; got: " + rendered);
    }

    @Test
    void sameTokenOccurrencesAggregateToOneAuditRowCarryingExactCount() {
        // M1-737 acceptance: one sanitize call over a field carrying N
        // occurrences of the SAME closed-list token writes ONE
        // LLM_OUTPUT_SANITIZED audit_log row for that token whose
        // detailsJson.match_count is exactly N — no occurrence
        // suppressed, no per-occurrence rows.
        List<RedactionHook.AuditRow> rows = new ArrayList<>();
        LlmOutputSanitizer sanitizer = new LlmOutputSanitizer(
                capturingAuditWriter(rows), SanitizerTestDoubles.noOpDataSource());

        sanitizer.sanitize("Run /audit now, then /audit again, and /audit thrice.");

        assertEquals(1, rows.size(),
                "N occurrences of one token must land ONE aggregated row, not N; got: " + rows);
        RedactionHook.AuditRow row = rows.get(0);
        assertEquals(AuditAction.LLM_OUTPUT_SANITIZED, row.action(),
                "the aggregated row must carry action LLM_OUTPUT_SANITIZED");
        String detailsJson = row.detailsJson();
        assertNotNull(detailsJson, "the aggregated row must carry detailsJson");
        assertTrue(detailsJson.contains("\"match_count\":3"),
                "match_count must be exactly the occurrence count 3; got: " + detailsJson);
        assertTrue(detailsJson.contains("\"match_kind\":\"/audit\""),
                "match_kind must name the token; got: " + detailsJson);
    }

    @Test
    void distinctTokensGetOneRowEachAndRewriteIsByteIdentical() {
        // M1-737 acceptance: DISTINCT tokens still get one row each, and
        // the redacted output text is byte-identical to the
        // per-occurrence implementation's — the rewrite is unchanged;
        // only the emission aggregates.
        List<RedactionHook.AuditRow> rows = new ArrayList<>();
        LlmOutputSanitizer sanitizer = new LlmOutputSanitizer(
                capturingAuditWriter(rows), SanitizerTestDoubles.noOpDataSource());

        String output = sanitizer.sanitize("First /ban one, then /audit them, then /ban two.");

        assertEquals("First " + LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT + " one, then "
                        + LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT + " them, then "
                        + LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT + " two.",
                output,
                "the rewrite must be byte-identical to the per-occurrence implementation's");
        assertEquals(2, rows.size(),
                "distinct tokens still get one row each; got: " + rows);
        for (RedactionHook.AuditRow row : rows) {
            assertEquals(AuditAction.LLM_OUTPUT_SANITIZED, row.action(),
                    "every row must carry action LLM_OUTPUT_SANITIZED");
        }
        String banJson = detailsJsonOf(rows, "/ban");
        assertTrue(banJson.contains("\"match_count\":2"),
                "the /ban row must carry the exact count 2; got: " + banJson);
        String auditJson = detailsJsonOf(rows, "/audit");
        assertTrue(auditJson.contains("\"match_count\":1"),
                "the /audit row must carry the exact count 1; got: " + auditJson);
    }

    /**
     * An {@link AuditLogWriter} that appends each row to {@code sink} —
     * the unit-tier seam for pinning the audit-row shape without a
     * database (the {@link SanitizerTestDoubles#noOpDataSource()}
     * connection satisfies the transaction calls).
     */
    private static AuditLogWriter capturingAuditWriter(List<RedactionHook.AuditRow> sink) {
        return new AuditLogWriter(row -> row) {
            @Override
            public void write(Connection conn, RedactionHook.AuditRow row) {
                sink.add(row);
            }
        };
    }

    /** The {@code detailsJson} of the one row naming {@code token}. */
    private static String detailsJsonOf(List<RedactionHook.AuditRow> rows, String token) {
        List<String> jsons = rows.stream()
                .map(RedactionHook.AuditRow::detailsJson)
                .filter(json -> json != null && json.contains(token))
                .toList();
        assertEquals(1, jsons.size(),
                "exactly one aggregated row must name " + token + "; got: " + rows);
        return jsons.get(0);
    }

    /**
     * Render a captured log record the way the log backend would: JBoss
     * Logging's {@code warnf} may leave the message as a printf format
     * with parameters, so apply them when present.
     */
    private static String renderLogMessage(LogRecord record) {
        Object[] params = record.getParameters();
        if (params == null || params.length == 0) {
            return record.getMessage();
        }
        try {
            return String.format(record.getMessage(), params);
        } catch (RuntimeException e) {
            return record.getMessage();
        }
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
     * that it yields exactly ONE match recorded under the token's
     * canonical form. Each match element feeds the per-token aggregation
     * behind the {@code LLM_OUTPUT_SANITIZED} row
     * (LlmOutputSanitizer.emitAuditRows), so a single element here is the
     * count-1 case of the aggregated durability commitment at unit tier.
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
