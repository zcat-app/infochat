package app.zcat.infochat.provider.chat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build-time guard against LLM tool-allowlist drift: {@link ChatToolRegistry}'s
 * name set must exactly equal the marker-delimited per-tool table in
 * {@code docs/spec/security.md} §Prompt-injection defenses.
 *
 * <p>Both that section and {@code docs/spec/verification.md} §Security already
 * CLAIMED this was machine-checked ("CI fails on a mismatch in either
 * direction"). It was not: no test read the spec at all, and the drift the
 * claim was meant to prevent had already happened — M1-589 added
 * {@code semanticSearch} to the registry and to security.md's table but left
 * verification.md enumerating only five of the six names. This test makes the
 * claim true (M1-654).
 *
 * <p>The assertion is a SET EQUALITY, which is what makes it bidirectional: a
 * registry name with no spec row and a spec row with no registry name both
 * fail. That matters because the tool surface is closed at spec level (D21) —
 * a name reaching the registry without a spec amendment widens the LLM's
 * capability, and a spec row with no implementation is a promise the code does
 * not keep.
 *
 * <p>The spec side is parsed EXCLUSIVELY from between the
 * {@code <!-- tool-allowlist:begin -->} / {@code <!-- tool-allowlist:end -->}
 * markers, the same convention {@code CommandCatalogueParityTest} uses for the
 * command index (M1-527). Parsing only the marked region is what keeps the
 * check false-positive-free: the surrounding section mentions tool names in
 * prose, and a backticked name there must not count as a table row.
 *
 * <p>No Quarkus container is needed — {@code toolNames()} reads a static set
 * and has no injected collaborators, so this instantiates the registry
 * directly, as {@link ChatToolRegistryTest} does.
 */
class ChatToolAllowlistSpecParityTest {

    private static final String BEGIN_MARKER = "<!-- tool-allowlist:begin -->";
    private static final String END_MARKER = "<!-- tool-allowlist:end -->";

    // Surefire runs with the module directory (infochat-provider) as the
    // working directory, so the repo-root spec file is one level up. The same
    // shape CommandCatalogueParityTest uses — deliberately not a second
    // repo-root discovery mechanism.
    private static final Path SECURITY_MD = Path.of("..", "docs", "spec", "security.md");

    // The Name cell of a tool row: a single backticked identifier and nothing
    // else. Anchored at both ends so a prose cell that merely CONTAINS a
    // backticked token cannot be mistaken for a tool row.
    private static final Pattern TOOL_NAME_CELL = Pattern.compile("^`([A-Za-z][A-Za-z0-9]*)`$");

    // A markdown alignment row (`|---|---|`), optionally colon-aligned.
    private static final Pattern SEPARATOR_CELL = Pattern.compile("^:?-{3,}:?$");

    // The table's header cell, skipped rather than parsed as a name.
    private static final String HEADER_CELL = "Name";

    private final ChatToolRegistry registry = new ChatToolRegistry();

    @Test
    void registryMatchesMarkedSpecTable() {
        Set<String> inSpec = parseMarkedToolNames(SECURITY_MD);
        Set<String> inRegistry = new TreeSet<>(registry.toolNames());

        assertEquals(inSpec, inRegistry, () -> diffMessage(inRegistry, inSpec));
    }

    /**
     * Vacuity guard: the marker region must yield a non-empty name set.
     *
     * <p>Unlike the guard it mirrors ({@code CommandCatalogueParityTest:196-203},
     * whose downstream assertions are {@code isEmpty()}-shaped and so would be
     * trivially true on an empty parse), the parity assertion above would
     * itself fail on an empty parse, because the registry is never empty. What
     * this test buys is the DIAGNOSIS: a formatting change that stops the
     * parser matching rows fails here with "the spec's table formatting
     * changed", instead of surfacing only as a six-name set diff in
     * {@link #registryMatchesMarkedSpecTable} that reads like a registry
     * regression and sends the reader to the wrong file.
     *
     * <p>The exact count is deliberately NOT pinned: a spec amendment that adds
     * a tool must be covered by this guard automatically rather than requiring
     * the test to be edited first — editing a guard to accept a change is how
     * guards get hollowed out.
     */
    @Test
    void parserIsNotVacuous() {
        Set<String> inSpec = parseMarkedToolNames(SECURITY_MD);

        assertFalse(inSpec.isEmpty(), () ->
                "Parsed no tool names from between " + BEGIN_MARKER + " and " + END_MARKER
                + " in " + SECURITY_MD.toAbsolutePath() + " — the spec's per-tool table "
                + "formatting changed and this parser needs updating (a spec restructure "
                + "must not silently disable the guard).");
    }

    /**
     * Tool names from the Name column of the marker-delimited table, reading
     * EXCLUSIVELY between the markers.
     *
     * <p>Every non-blank line in the region must be a table row whose first
     * cell is the header, an alignment row, or a single backticked name;
     * anything else fails the build. Skipping unrecognized lines instead would
     * reintroduce exactly the silent-shrink failure a closed-list guard must
     * not have — a restructured table would yield a smaller set that still
     * compared equal to a correspondingly stale registry.
     *
     * <p>File I/O is a system boundary, so the explicit file-exists and
     * marker-present checks here are correct rather than defensive-code drift:
     * a missing file or absent markers is a spec/environment error this test
     * must report with an actionable message rather than an opaque NPE.
     */
    private static Set<String> parseMarkedToolNames(Path securityMd) {
        assertTrue(Files.isRegularFile(securityMd), () ->
                "Security spec not found at " + securityMd.toAbsolutePath()
                + " (surefire working dir = " + Path.of("").toAbsolutePath()
                + "); the parity test resolves it relative to the provider module dir.");

        List<String> lines;
        try {
            lines = Files.readAllLines(securityMd);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + securityMd.toAbsolutePath(), e);
        }

        // Matched on the STRIPPED line: the markers sit inside a markdown list
        // item and so are indented, unlike the column-0 command-index markers.
        int begin = indexOfStrippedLine(lines, BEGIN_MARKER, 0);
        int end = indexOfStrippedLine(lines, END_MARKER, begin + 1);
        assertTrue(begin >= 0 && end > begin, () ->
                "Tool-allowlist markers missing or malformed in " + securityMd.toAbsolutePath()
                + ": expected '" + BEGIN_MARKER + "' followed by '" + END_MARKER + "'.");

        Set<String> names = new TreeSet<>();
        for (String raw : lines.subList(begin + 1, end)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            assertTrue(line.startsWith("|"), () ->
                    "Non-table line inside the tool-allowlist region of "
                    + securityMd.toAbsolutePath() + ": '" + raw + "'. The region must contain "
                    + "the per-tool table and nothing else, so that every row is either "
                    + "guarded or a loud failure.");

            String[] cells = line.split("\\|", -1);
            assertTrue(cells.length >= 2, () ->
                    "Malformed table row (no Name cell) inside the tool-allowlist region of "
                    + securityMd.toAbsolutePath() + ": '" + raw + "'");

            String nameCell = cells[1].strip();
            if (HEADER_CELL.equals(nameCell) || SEPARATOR_CELL.matcher(nameCell).matches()) {
                continue;
            }

            Matcher matcher = TOOL_NAME_CELL.matcher(nameCell);
            assertTrue(matcher.matches(), () ->
                    "Unrecognized Name cell inside the tool-allowlist region of "
                    + securityMd.toAbsolutePath() + ": '" + nameCell + "' (from '" + raw + "'). "
                    + "Each row's first cell must be a single backticked tool name, e.g. "
                    + "`searchPosts`. This fails loudly rather than skipping the row, because a "
                    + "skipped row would drop a tool out of the guarded set unnoticed.");
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Index of the first line at or after {@code from} whose stripped form equals {@code marker}, or -1. */
    private static int indexOfStrippedLine(List<String> lines, String marker, int from) {
        for (int i = Math.max(from, 0); i < lines.size(); i++) {
            if (lines.get(i).strip().equals(marker)) {
                return i;
            }
        }
        return -1;
    }

    private static String diffMessage(Set<String> inRegistry, Set<String> inSpec) {
        Set<String> registryOnly = new TreeSet<>(inRegistry);
        registryOnly.removeAll(inSpec);
        Set<String> specOnly = new TreeSet<>(inSpec);
        specOnly.removeAll(inRegistry);
        return "LLM tool-allowlist parity mismatch between ChatToolRegistry and the per-tool "
               + "table in docs/spec/security.md §Prompt-injection defenses:\n"
               + "  in the registry but NOT in the spec table (the LLM can call a tool the spec "
               + "never authorized — the tool surface is closed at spec level per D21, so this "
               + "needs a spec amendment, not a table edit to match): " + registryOnly + "\n"
               + "  in the spec table but NOT in the registry (the spec promises a tool no "
               + "handler serves): " + specOnly;
    }
}
