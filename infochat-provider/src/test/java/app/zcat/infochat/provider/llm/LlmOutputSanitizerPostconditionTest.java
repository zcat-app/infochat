package app.zcat.infochat.provider.llm;

import app.zcat.infochat.core.llm.LlmOutputSanitizerCore;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Census meta test for M1-792. The caller-postcondition census
 * (docs/plan/m1/sanitize-caller-census.md) records every call site of the
 * shared sanitize() transform and the postcondition each caller assumes.
 * The roster is re-derived from the grep in the census header, NOT copied:
 * the header's hit count must equal the row count below, keeping the
 * document internally consistent. Drift from a newly added call site is
 * caught by re-running the census grep in the header (M1-792 pitfall P1),
 * not by this test.
 *
 * <p>This test resolves every call-site row to a named pinning test
 * (resolved in-tree) or a filed follow-up ticket, and fails on any row
 * that has neither. The resolution is the M1-779 meta lesson: two redteam
 * rounds each found one more unpinned caller by inspection, so the census
 * is machine-enforced instead of re-audited by hand.
 */
class LlmOutputSanitizerPostconditionTest {

    private static final Pattern PIN_TOKEN = Pattern.compile(
            "`([A-Za-z0-9_.]+)#([A-Za-z0-9_]+)`");
    private static final Pattern FOLLOW_UP_TOKEN = Pattern.compile(
            "`(M1-\\d+)`");

    @Test
    void everyBeanCallSitePostconditionIsPinned() throws IOException {
        Path census = locateCensus();
        assertNotNull(census, "docs/plan/m1/sanitize-caller-census.md must be locatable "
                + "from the test working dir");
        List<String> lines = Files.readAllLines(census);

        List<String> siteRows = siteRows(lines);
        assertFalse(siteRows.isEmpty(), "the census must carry call-site rows");

        int declaredCount = declaredRowCount(lines);
        assertNotNull(declaredCount, "the census header must declare its hit count");
        assertEquals(declaredCount, siteRows.size(),
                "the header's grep hit count must equal the call-site row count");

        List<String> failures = new ArrayList<>();
        for (String row : siteRows) {
            List<String> pins = pinTokensOf(row);
            List<String> followUps = followUpTokensOf(row);
            if (pins.isEmpty() && followUps.isEmpty()) {
                failures.add(row.trim() + "  ->  neither a pinning test nor a filed follow-up");
                continue;
            }
            for (String pin : pins) {
                if (!resolvesInTree(pin)) {
                    failures.add(row.trim() + "  ->  pinning test not resolved in-tree: " + pin);
                }
            }
            for (String followUp : followUps) {
                if (!ticketFiled(followUp)) {
                    failures.add(row.trim() + "  ->  follow-up not filed: " + followUp);
                }
            }
        }
        assertTrue(failures.isEmpty(),
                "every census row must resolve to a pinning test or a filed follow-up:\n"
                        + String.join("\n", failures));
    }

    // ----- parsing -------------------------------------------------------

    /**
     * The call-site rows: top-level bullets after the {@code ## Call-site
     * rows} heading. Shared-contract bullets and the header stay excluded;
     * a row's continuation lines are folded into it so its resolution
     * tokens (pins / follow-ups on indented continuation lines) parse.
     */
    private static List<String> siteRows(List<String> lines) {
        List<String> rows = new ArrayList<>();
        boolean inSites = false;
        StringBuilder current = null;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                inSites = line.contains("Call-site rows");
                if (current != null) {
                    rows.add(current.toString());
                    current = null;
                }
                continue;
            }
            if (!inSites) {
                continue;
            }
            if (line.startsWith("- ")) {
                if (current != null) {
                    rows.add(current.toString());
                }
                current = new StringBuilder(line);
            } else if (current != null) {
                current.append('\n').append(line);
            }
        }
        if (current != null) {
            rows.add(current.toString());
        }
        return rows;
    }

    /** The {@code hit count: N rows below} figure the header declares. */
    private static Integer declaredRowCount(List<String> lines) {
        Matcher m = Pattern.compile("hit count:\\s*(\\d+)").matcher(String.join("\n", lines));
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static List<String> pinTokensOf(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher m = PIN_TOKEN.matcher(text);
        while (m.find()) {
            tokens.add(m.group(1) + "#" + m.group(2));
        }
        return tokens;
    }

    private static List<String> followUpTokensOf(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher m = FOLLOW_UP_TOKEN.matcher(text);
        while (m.find()) {
            tokens.add(m.group(1));
        }
        return tokens;
    }

    // ----- resolution ----------------------------------------------------

    /**
     * Resolve {@code ClassName#method} in-tree: the named test class file
     * must exist under either module's test tree and carry the method
     * signature — the same resolution tick-lint applies to named tests.
     */
    private static boolean resolvesInTree(String classAndMethod) {
        String[] parts = classAndMethod.split("#");
        if (parts.length != 2) {
            return false;
        }
        String className = parts[0];
        String method = parts[1];
        Path repoRoot = census().getParent().getParent().getParent().getParent();
        for (String module : List.of("infochat-provider", "infochat-collector")) {
            Path testRoot = repoRoot.resolve(module).resolve("src").resolve("test");
            if (!Files.isDirectory(testRoot)) {
                continue;
            }
            try (var files = Files.walk(testRoot)) {
                boolean found = files
                        .filter(p -> p.getFileName().toString().equals(className + ".java"))
                        .anyMatch(p -> {
                            try {
                                return Files.readString(p).contains("void " + method + "(");
                            } catch (IOException e) {
                                return false;
                            }
                        });
                if (found) {
                    return true;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    /** A follow-up ticket resolves when its file exists in tick-tickets/. */
    private static boolean ticketFiled(String id) {
        Path ticketsDir = census().getParent().resolve("tick-tickets");
        try (var files = Files.list(ticketsDir)) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith(id + "-"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Resolve the census doc from the test working directory. Tests run
     * from each module's directory (e.g. {@code infochat-provider/}), so
     * the path is {@code ../docs/plan/m1/sanitize-caller-census.md}
     * relative to module-run, but a repo-root run sees
     * {@code docs/plan/m1/sanitize-caller-census.md} directly.
     */
    private static Path locateCensus() {
        for (Path p : List.of(
                Paths.get("docs/plan/m1/sanitize-caller-census.md"),
                Paths.get("..", "docs", "plan", "m1", "sanitize-caller-census.md"),
                Paths.get("../../docs/plan/m1/sanitize-caller-census.md"))) {
            if (Files.exists(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static Path census() {
        return locateCensus();
    }

    // ----- honest transform pins (census "shared contract" rows) --------

    @Test
    void sanitizeReturnsOriginalBytesOnNoClosedListMatch() {
        // LlmOutputSanitizerCore.java:646-647 — on zero matches the caller's
        // ORIGINAL bytes are returned, so canonicalization never reflows
        // legitimate prose. The input carries canonicalizable-but-harmless
        // bytes (zero-width spaces): byte-identity proves the original-bytes
        // contract, not a coincidence of a byte-identical canonical form.
        String input = "legitimate \u200B prose \u200B with no token";
        LlmOutputSanitizerCore.ClosedListStripResult result =
                LlmOutputSanitizerCore.applyClosedListStripWithMatches(input);
        assertSame(input, result.rewritten(),
                "zero matches must hand the caller's own String back untouched");
        assertTrue(result.matches().isEmpty(),
                "no closed-list token means no matches");
    }

    @Test
    void sanitizeMayReturnTheCanonicalFormOnMatch() {
        // LlmOutputSanitizerCore.java:652 — on ANY match the canonical form
        // is returned. A zero-width-prefixed token is not what the raw-byte
        // detectors upstream ever see, but the canonical form carries it at
        // index 0 — the pre-existing synthesis channel P5 names and M1-791
        // closes. Pinned as documentation, so the deleting-pass ticket sees
        // the contract it joins.
        String input = "\u200B/grant-admin";
        LlmOutputSanitizerCore.ClosedListStripResult result =
                LlmOutputSanitizerCore.applyClosedListStripWithMatches(input);
        assertFalse(result.rewritten().contains("\u200B"),
                "the canonical form is returned on match, not the original bytes");
        assertTrue(result.rewritten().contains(
                        LlmOutputSanitizerCore.REDACTED_COMMAND_REPLACEMENT),
                "the token still redacts");
        assertNotEquals(input, result.rewritten(),
                "a match changes the representation — that is the documented channel");
    }

    @Test
    void deletionShapesMatchTheirDocumentedPostconditions() {
        // FAILURE-MODE (census "deletion shapes" row). Each shape is fed to
        // the production sanitize() and asserted against the DOCUMENTED
        // postcondition; each pin catches a named mutation of the pass
        // composition (reordering, dropping the id-class exclusion), so no
        // pin is vacuous.
        LlmOutputSanitizer sanitizer = SanitizerTestDoubles.noAuditSanitizer();

        // Marker-only line is dropped, not blanked (M1-789).
        assertEquals("intro\noutro",
                sanitizer.sanitize("intro\n<<<END id=\"x\">>>\noutro"),
                "a marker-only line is dropped; blanking it instead fails this pin");

        // "" is a possible sanitize() return TODAY (P8): a markers-only
        // reply reduces to nothing. M1-794's deliverLlmReply refuses the
        // emptied shape at delivery; the pass contract itself is unchanged.
        assertEquals("", sanitizer.sanitize("<<<END id=\"x\">>>"),
                "a markers-only reply reduces to empty today — P8's documented residual");

        // Thematic-break line is dropped by the plain-text downgrade
        // (M1-790); the pin was deliberately flipped from the pre-M1-790
        // contract its own comment mandated.
        assertEquals("intro\noutro",
                sanitizer.sanitize("intro\n---\noutro"),
                "a thematic-break line is dropped by the downgrade");

        // Emphasis-joined token: the downgrade joins the fragments, and
        // the joined marker is what the post-sanitize detectors evaluate
        // (M1-791). Flipped deliberately from the pre-M1-790 contract.
        assertEquals("[REFUSAL: something]",
                sanitizer.sanitize("[REFUS**AL**: something]"),
                "emphasis is joined by the downgrade before any detector sees it");

        // The id class excludes '/' (P3): a command-bearing "marker" line
        // is NOT a marker, survives the strip, and redacts+audits through
        // the closed-list pass — dropping the exclusion would let the line
        // vanish without a WARN or an audit row.
        String commandBearingMarker =
                "intro\n<<<END id=\"/grant-admin\">>>\noutro";
        String redacted = sanitizer.sanitize(commandBearingMarker);
        assertTrue(redacted.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a marker id carrying '/' is not a marker: it must redact, not vanish");
        assertFalse(redacted.contains("/grant-admin"),
                "the command word must not survive");

        // Dotted config token replaced by a single space (M1-815):
        // shrinkage on that token class, no token synthesis; the bare
        // word survives byte-identical (over-breadth direction).
        assertEquals("The   window applies.",
                sanitizer.sanitize("The infochat.probation.duration window applies."),
                "a dotted config token is replaced by a single space");
        assertEquals("Ask infochat about the news.",
                sanitizer.sanitize("Ask infochat about the news."),
                "the bare word is not a config token and survives");
    }
}
