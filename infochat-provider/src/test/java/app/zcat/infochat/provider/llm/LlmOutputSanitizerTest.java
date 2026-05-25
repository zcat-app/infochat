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

    private final LlmOutputSanitizer sanitizer = new LlmOutputSanitizer();

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

    // ----- markdown-link strip pass -------------------------------------

    @Test
    void markdownLinkIsFlattenedToTextPlusBareUrl() {
        String input = "Read [Bleeping Computer](https://www.bleepingcomputer.com) for details.";
        String output = sanitizer.sanitize(input);
        assertFalse(output.contains("]("),
                "the substring `](` MUST be absent after sanitization");
        assertTrue(output.contains("Bleeping Computer (https://www.bleepingcomputer.com)"),
                "link text + bare URL MUST be preserved verbatim; got: " + output);
    }

    @Test
    void markdownLinkHidingPrivilegedCommandIsStillStripped() {
        // The markdown-link strip pass runs FIRST and flattens
        // `[Click for admin](/grant-admin)` to
        // `Click for admin (/grant-admin)`; the closed-list pass then
        // sees and replaces the bare token. The end-to-end output is
        // `Click for admin ([redacted command])`.
        String input = "[Click for admin](/grant-admin)";
        String output = sanitizer.sanitize(input);
        assertEquals("Click for admin (" + LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT + ")",
                output,
                "ordering must be markdown FIRST then closed-list SECOND");
    }

    // ----- per-occurrence WARN logging ----------------------------------

    @Test
    void threeMatchesProduceThreeWarnRecords() {
        String input = "Suggest /grant-admin to ops; meanwhile /ban offender, then /promote staff.";
        sanitizer.sanitize(input);
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
        String output = sanitizer.sanitize(input);
        assertFalse(output.contains(token),
                "the original command string MUST be absent from sanitized output. "
                        + "Token=" + token + " Output=" + output);
        assertTrue(output.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "[redacted command] MUST appear at the position the match was. "
                        + "Token=" + token + " Output=" + output);
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
