package app.zcat.infochat.provider.bundle;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build-time guard: <b>no friendly-error template reflects unvalidated inbound
 * text</b> (M1-658, enforcing the property {@code docs/spec/commands.md}
 * §Discovery already states — "any friendly error reachable below bot admin
 * must not reflect inbound text"). It is the mechanical successor to six
 * rounds of per-site fixing (M1-647, M1-656): those converged on the right
 * code, but nothing stopped the next handler from reintroducing the defect.
 * This test is that stop.
 *
 * <h2>What it does</h2>
 * It <em>censuses</em> every place provider code interpolates a value into a
 * bundle template whose key resolves to an {@code error.*} string, across the
 * three interpolation forms in the codebase:
 * <ol>
 *   <li>inline {@code MessageFormat.format(bundleLoader.get(KEY, lang), args)};</li>
 *   <li>the {@code new Failure(KEY, List.of(args))} / accumulator pattern in
 *       the {@code *Args} parsers;</li>
 *   <li>the private {@code format(KEY, args)} helper call sites.</li>
 * </ol>
 * Each interpolation argument is either auto-classified as trivially
 * bot-authored (a string literal, a numeric conversion, a {@code String.join}
 * over a collection, {@code *.commaList()}, or a local {@code static final
 * String} constant) or must appear in {@link #BASELINE_RESOURCE} with a
 * one-line provenance justification. A NEW or CHANGED error interpolation that
 * is neither trivially safe nor baselined fails the build — the census keys on
 * the normalized argument <em>expression</em>, so changing <em>what</em> is
 * interpolated at an existing site (the AddSourceArgs regression shape) breaks
 * the baseline match and forces a conscious re-record.
 *
 * <h2>What it deliberately does NOT do — read before trusting a green run</h2>
 * <ul>
 *   <li><b>Errors only.</b> The census is scoped to {@code error.*} keys. It
 *       does NOT prove {@code reply.*} / success / confirmation templates
 *       safe. A future reflection introduced on a non-error-keyed template is
 *       outside its view. Errors are covered because that is where all six
 *       historical regressions lived and where un-validated input structurally
 *       flows (an error fires on the rejected input; a success fires only
 *       after validation).</li>
 *   <li><b>Placeholder interpolation only.</b> The census recognises the three
 *       interpolation forms above. An error reply assembled by raw string
 *       concatenation against a bundle string
 *       ({@code bundleLoader.get(ERROR_X, lang) + rawToken}) or via
 *       {@code String.format(bundleLoader.get(...), rawToken)} is not a
 *       placeholder-interpolation site and is outside the census. No such site
 *       exists today; the property to preserve is "friendly errors render
 *       inbound text only through recorded, justified interpolation".</li>
 *   <li><b>Syntactic, not provenance-aware.</b> A text census cannot tell a
 *       stored DB value from a raw inbound token — they are syntactically
 *       identical. The baseline records a human's provenance judgment per
 *       site; the guard freezes the set so any change re-triggers that
 *       judgment. The complete fix — a taint-carrying wrapper type for
 *       inbound strings (the deferred "3a" provenance-discipline project) —
 *       would make this guard unnecessary. Until then a green result means
 *       "no unrecorded error interpolation", NOT "reflection is impossible".</li>
 * </ul>
 * This boundary is stated so a green guard is never mistaken for a
 * completeness proof — the exact false confidence the original
 * {@code security.md} sanitizer exemption carried ("that text never passes
 * through an LLM").
 *
 * <p>No Quarkus container is needed: the census is pure text parsing over the
 * source tree, like {@code ChatToolAllowlistSpecParityTest}.
 */
class InboundReflectionGuardTest {

    // Surefire runs with the module dir (infochat-provider) as CWD.
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path BUNDLE_KEYS =
            MAIN_JAVA.resolve("app/zcat/infochat/provider/bundle/BundleKeys.java");
    // Baseline lives on the test classpath; loaded as a resource so a move of
    // the source tree does not silently blank it.
    private static final String BASELINE_RESOURCE = "/inbound-reflection-error-baseline.txt";

    /** A single argument interpolated into an error-keyed template. */
    private record Site(String file, String keyValue, int argIndex, String argExpr) {
        String fingerprint() {
            return file + " | " + keyValue + " | " + argIndex + " | " + argExpr;
        }
    }

    // ---- assertions -------------------------------------------------------

    @Test
    void everyErrorInterpolationIsTriviallySafeOrBaselined() {
        List<Site> residue = residueSites();
        Set<String> baseline = baselineFingerprints();
        List<String> unaccounted = residue.stream()
                .map(Site::fingerprint)
                .filter(fp -> !baseline.contains(fp))
                .sorted()
                .collect(Collectors.toList());
        assertTrue(unaccounted.isEmpty(),
                "Error-keyed interpolation sites that are neither trivially "
                        + "bot-authored nor recorded in " + BASELINE_RESOURCE
                        + " with a provenance justification. A new one is a "
                        + "possible reflection of inbound text into a friendly "
                        + "error — validate the argument at parse time (see "
                        + "SummaryArgs) or add a baseline line stating why it is "
                        + "safe. Unaccounted sites:\n  "
                        + String.join("\n  ", unaccounted));
    }

    @Test
    void noDeadBaselineEntries() {
        Set<String> live = residueSites().stream()
                .map(Site::fingerprint).collect(Collectors.toCollection(TreeSet::new));
        List<String> dead = baselineFingerprints().stream()
                .filter(fp -> !live.contains(fp))
                .sorted()
                .collect(Collectors.toList());
        assertTrue(dead.isEmpty(),
                "Baseline entries matching no current error interpolation site. "
                        + "The site was removed or its argument changed; delete "
                        + "the stale line so the baseline cannot rot into an "
                        + "unchecked allowlist. Dead entries:\n  "
                        + String.join("\n  ", dead));
    }

    @Test
    void censusIsNotVacuous() {
        // The census must find the known error interpolations; a parser that
        // silently matches nothing must fail loudly, not pass green. The tree
        // carries well over a dozen error-keyed interpolation sites.
        int total = censusTree().size();
        assertTrue(total >= 40,
                "Census found only " + total + " error-keyed interpolation "
                        + "sites; expected the full tree (>=40). The parser has "
                        + "gone blind — a silent match failure would make every "
                        + "other assertion vacuously pass.");
    }

    @Test
    void summaryParseValidatedEchoIsInScope() {
        // Regression guard for the scope resolver itself: SummaryArgs echoes
        // the supplied tag under a constant NAMED `BUNDLE_UNKNOWN_TAG`, not
        // `ERROR_*`. A resolver that scoped by constant-name prefix would miss
        // it. Scope must follow the resolved VALUE ("error.summary.unknown_tag").
        boolean found = censusTree().stream().anyMatch(s ->
                s.file().equals("SummaryArgs.java")
                        && s.keyValue().equals("error.summary.unknown_tag"));
        assertTrue(found,
                "The /summary unknown-tag echo (error.summary.unknown_tag, the "
                        + "parse-validated precedent) is not in the census — the "
                        + "key resolver is scoping by constant name, not resolved "
                        + "value, and would miss error keys held in non-ERROR_ "
                        + "constants.");
    }

    @Test
    void syntheticRawInboundEchoIsFlagged() {
        // Non-vacuity of the CLASSIFIER (not just the walker): a synthetic
        // handler that echoes a raw inbound token into an error template must
        // land in the residue. If this stops failing, the detector is blind.
        String synthetic = """
                package x;
                class Fake {
                    String handle(String rawText) {
                        String token = rawText.trim();
                        return MessageFormat.format(
                                bundleLoader.get(BundleKeys.ERROR_FAKE_UNKNOWN, lang),
                                token);
                    }
                }
                """;
        Map<String, String> keys = Map.of("BundleKeys.ERROR_FAKE_UNKNOWN", "error.fake.unknown");
        List<Site> sites = census("Fake.java", synthetic, keys);
        List<Site> residue = sites.stream()
                .filter(s -> !isTriviallySafe(s.argExpr(), Map.of()))
                .collect(Collectors.toList());
        assertEquals(1, residue.size(),
                "The classifier must flag a raw inbound echo into an error "
                        + "template as residue (not auto-safe). Got: " + sites);
        assertEquals("token", residue.get(0).argExpr());
    }

    @Test
    void changedArgumentAtExistingSiteBreaksBaselineMatch() {
        // The AddSourceArgs regression shape: same file, same key, same arg
        // index, but the interpolated EXPRESSION changes from a safe
        // bot-authored value to a raw token. Because the fingerprint includes
        // the normalized argument expression, the old baseline line no longer
        // matches — the site becomes unaccounted.
        Site original = new Site("Demo.java", "error.demo", 0, "SourceKind.commaList()");
        Site changed = new Site("Demo.java", "error.demo", 0, "rawToken");
        assertFalse(original.fingerprint().equals(changed.fingerprint()),
                "A changed interpolation argument (same file/key/index) must "
                        + "produce a different fingerprint so the prior baseline "
                        + "entry no longer covers it.");
    }

    // ---- census -----------------------------------------------------------

    private List<Site> residueSites() {
        return censusTree().stream()
                .filter(s -> !isTriviallySafe(s.argExpr(), localConstantsFor(s.file())))
                .collect(Collectors.toList());
    }

    private final Map<String, Map<String, String>> localConstantCache = new LinkedHashMap<>();

    private Map<String, String> localConstantsFor(String fileName) {
        return localConstantCache.getOrDefault(fileName, Map.of());
    }

    /** Census the whole main source tree; also populates the local-constant cache. */
    private List<Site> censusTree() {
        Map<String, String> bundleKeys = parseBundleKeyValues();
        List<Site> all = new ArrayList<>();
        try (var paths = Files.walk(MAIN_JAVA)) {
            List<Path> javaFiles = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
            for (Path p : javaFiles) {
                String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                String name = p.getFileName().toString();
                localConstantCache.put(name, parseLocalStringConstants(src));
                all.addAll(census(name, src, bundleKeys));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return all;
    }

    private static final Pattern MESSAGE_FORMAT = Pattern.compile("MessageFormat\\.format\\s*\\(");
    private static final Pattern NEW_FAILURE = Pattern.compile("new\\s+Failure\\s*\\(");
    // A bare `format(` helper call: `format(` NOT preceded by an identifier
    // char or a dot (so `MessageFormat.format` / `String.format` / a method
    // whose name ends in `format` are excluded).
    private static final Pattern HELPER_FORMAT = Pattern.compile("(?<![A-Za-z0-9_.])format\\s*\\(");
    private static final Pattern GET_CALL = Pattern.compile("^bundleLoader\\.get\\s*\\(");

    /**
     * Pure census of one source string. {@code bundleKeys} maps a key
     * expression ({@code BundleKeys.NAME} or a bare {@code NAME}) to its
     * resolved {@code error.*}/other string value; only sites whose key
     * resolves to an {@code error.*} value are returned.
     */
    List<Site> census(String fileName, String source, Map<String, String> bundleKeys) {
        Map<String, String> localConsts = parseLocalStringConstants(source);
        Map<String, String> keyAssignments = parseKeyLocalAssignments(source);
        List<Site> sites = new ArrayList<>();

        // Form 1: MessageFormat.format(bundleLoader.get(KEY, ...), args...)
        Matcher mf = MESSAGE_FORMAT.matcher(source);
        while (mf.find()) {
            int open = mf.end() - 1;
            String args = balancedArgs(source, open);
            if (args == null) continue;
            List<String> parts = splitTopArgs(args);
            if (parts.isEmpty()) continue;
            String first = norm(parts.get(0));
            String keyExpr = templateKeyExpr(first);
            if (keyExpr == null) continue; // not a bundle-template render (e.g. helper body)
            addInterpolation(sites, fileName, keyExpr, parts.subList(1, parts.size()),
                    bundleKeys, localConsts, keyAssignments);
        }

        // Form 2: new Failure(KEY, List.of(args) | accumulatorVar)
        Matcher nf = NEW_FAILURE.matcher(source);
        while (nf.find()) {
            int open = nf.end() - 1;
            String args = balancedArgs(source, open);
            if (args == null) continue;
            List<String> parts = splitTopArgs(args);
            if (parts.size() < 2) continue; // key-only Failure: no interpolation
            String keyExpr = norm(parts.get(0));
            List<String> interp = failureInterpolation(norm(parts.get(1)), source);
            addInterpolation(sites, fileName, keyExpr, interp,
                    bundleKeys, localConsts, keyAssignments);
        }

        // Form 3: format(KEY, args...) — the private per-handler helper.
        Matcher hf = HELPER_FORMAT.matcher(source);
        while (hf.find()) {
            int open = hf.end() - 1;
            String args = balancedArgs(source, open);
            if (args == null) continue;
            List<String> parts = splitTopArgs(args);
            if (parts.size() < 2) continue;
            String keyExpr = norm(parts.get(0));
            // The helper BODY forwards its `bundleKey` parameter, which
            // resolves to nothing — naturally excluded by the error-scope test.
            addInterpolation(sites, fileName, keyExpr, parts.subList(1, parts.size()),
                    bundleKeys, localConsts, keyAssignments);
        }
        return sites;
    }

    /**
     * If {@code first} renders a bundle template, return the key expression
     * inside {@code bundleLoader.get(...)}; else null.
     */
    private static String templateKeyExpr(String first) {
        Matcher m = GET_CALL.matcher(first);
        if (!m.find()) return null;
        String inner = balancedArgs(first, first.indexOf('('));
        if (inner == null) return null;
        List<String> getArgs = splitTopArgs(inner);
        return getArgs.isEmpty() ? null : norm(getArgs.get(0));
    }

    private void addInterpolation(List<Site> sites, String fileName, String keyExpr,
                                  List<String> interp, Map<String, String> bundleKeys,
                                  Map<String, String> localConsts,
                                  Map<String, String> keyAssignments) {
        String errorValue = resolveErrorKeyValue(keyExpr, bundleKeys, localConsts, keyAssignments);
        if (errorValue == null) return; // not an error-keyed template
        for (int i = 0; i < interp.size(); i++) {
            sites.add(new Site(fileName, errorValue, i, norm(interp.get(i))));
        }
    }

    /**
     * Resolve a key expression to its {@code error.*} string value, or null if
     * it does not (provably) resolve to an error key. Handles direct
     * {@code BundleKeys.NAME} references, string literals, local
     * {@code static final String} constants, and locals assigned a ternary /
     * expression over those (e.g. {@code String key = c ? ERROR_A : ERROR_B}).
     */
    private String resolveErrorKeyValue(String keyExpr, Map<String, String> bundleKeys,
                                        Map<String, String> localConsts,
                                        Map<String, String> keyAssignments) {
        for (String v : resolveKeyValues(keyExpr, bundleKeys, localConsts, keyAssignments,
                new LinkedHashSet<>())) {
            if (v.startsWith("error.")) return v;
        }
        return null;
    }

    private static final Pattern BUNDLE_REF = Pattern.compile("BundleKeys\\.[A-Z0-9_]+");
    private static final Pattern STRING_LIT = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern BARE_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private Set<String> resolveKeyValues(String expr, Map<String, String> bundleKeys,
                                         Map<String, String> localConsts,
                                         Map<String, String> keyAssignments,
                                         Set<String> guard) {
        Set<String> out = new LinkedHashSet<>();
        String e = norm(expr);
        if (!guard.add(e)) return out; // cycle guard
        // Direct BundleKeys.NAME references anywhere in the expression.
        Matcher b = BUNDLE_REF.matcher(e);
        while (b.find()) {
            String v = bundleKeys.get(b.group());
            if (v != null) out.add(v);
        }
        // String literals.
        Matcher s = STRING_LIT.matcher(e);
        while (s.find()) out.add(s.group(1));
        // A bare identifier: a local String constant, or a key local assigned
        // an expression we can resolve transitively.
        if (BARE_IDENT.matcher(e).matches()) {
            if (localConsts.containsKey(e)) out.add(localConsts.get(e));
            if (keyAssignments.containsKey(e)) {
                out.addAll(resolveKeyValues(keyAssignments.get(e), bundleKeys,
                        localConsts, keyAssignments, guard));
            }
        }
        return out;
    }

    // ---- classifier -------------------------------------------------------

    private static final Pattern SIZE_CALL = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]*\\.size\\(\\)$");
    private static final Pattern INT_LITERAL = Pattern.compile("^-?\\d+$");
    // A single Java string literal, including one with escaped interior
    // quotes (e.g. a usage string containing \"...\"). Anchored at both ends
    // so a concatenation like `"x" + rawToken` does NOT match.
    private static final Pattern SINGLE_LITERAL = Pattern.compile("^\"(?:\\\\.|[^\"\\\\])*\"$");

    /**
     * True when the argument expression is trivially bot-authored by SHAPE
     * alone, safe REGARDLESS of what the argument's value or runtime type is —
     * so no provenance judgment is needed. The set is deliberately tiny and
     * every member is whole-expression anchored:
     * <ul>
     *   <li>a single string or integer literal;</li>
     *   <li>a {@code .size()} call (returns {@code int});</li>
     *   <li>a bare local {@code static final String} constant (a bot-authored
     *       usage string, e.g. {@code USAGE}).</li>
     * </ul>
     * A bare identifier, a member access, and content-dependent shapes such as
     * {@code String.join(delim, list)} or {@code String.valueOf(obj)} are NOT
     * auto-safe — a raw inbound token is syntactically identical to a stored DB
     * value, and {@code String.valueOf(Object)} / {@code String.join} over an
     * inbound list would reflect it verbatim. Those fall to the baseline for an
     * explicit judgment. (Tightened after the M1-658 r1 red-team finding, which
     * showed the earlier {@code String.valueOf}/{@code String.join} shapes,
     * matched by prefix, admitted {@code String.valueOf(rawToken)}.)
     */
    boolean isTriviallySafe(String argExpr, Map<String, String> localConsts) {
        String e = norm(argExpr);
        if (SINGLE_LITERAL.matcher(e).matches()) return true;
        if (INT_LITERAL.matcher(e).matches()) return true;
        if (SIZE_CALL.matcher(e).matches()) return true;
        if (BARE_IDENT.matcher(e).matches() && localConsts.containsKey(e)) return true;
        return false;
    }

    // ---- Failure interpolation extraction ---------------------------------

    private static final Pattern LIST_OF = Pattern.compile("^List\\.of\\s*\\(");

    /**
     * The interpolation argument list of a {@code new Failure(key, second)}:
     * either {@code List.of(a, b)} inline, or an accumulator variable whose
     * {@code .add(expr)} calls appear elsewhere in the method.
     */
    private List<String> failureInterpolation(String second, String source) {
        if (LIST_OF.matcher(second).find()) {
            String inner = balancedArgs(second, second.indexOf('('));
            return inner == null ? List.of() : splitTopArgs(inner);
        }
        if (BARE_IDENT.matcher(second).matches()) {
            Pattern add = Pattern.compile(Pattern.quote(second) + "\\.add\\s*\\(");
            Matcher m = add.matcher(source);
            List<String> exprs = new ArrayList<>();
            while (m.find()) {
                String a = balancedArgs(source, m.end() - 1);
                if (a != null) exprs.add(norm(a));
            }
            return exprs;
        }
        return List.of();
    }

    // ---- key / constant resolution parsing --------------------------------

    private static final Pattern BUNDLE_KEY_DECL = Pattern.compile(
            "public\\s+static\\s+final\\s+String\\s+([A-Z0-9_]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern LOCAL_STRING_CONST = Pattern.compile(
            "static\\s+final\\s+String\\s+([A-Za-z0-9_]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern KEY_LOCAL_ASSIGN = Pattern.compile(
            "\\bString\\s+([A-Za-z0-9_]+)\\s*=\\s*([^;]*?(?:BundleKeys\\.[A-Z0-9_]+|\"error\\.[^\"]*\")[^;]*);");

    private Map<String, String> parseBundleKeyValues() {
        try {
            String src = stripComments(Files.readString(BUNDLE_KEYS, StandardCharsets.UTF_8));
            Map<String, String> out = new LinkedHashMap<>();
            Matcher m = BUNDLE_KEY_DECL.matcher(src);
            while (m.find()) {
                out.put("BundleKeys." + m.group(1), m.group(2));
                out.put(m.group(1), m.group(2));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> parseLocalStringConstants(String source) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = LOCAL_STRING_CONST.matcher(source);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    /** Locals assigned an expression that mentions an error key (for key ternaries). */
    private static Map<String, String> parseKeyLocalAssignments(String source) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = KEY_LOCAL_ASSIGN.matcher(source);
        while (m.find()) out.put(m.group(1), norm(m.group(2)));
        return out;
    }

    // ---- baseline ---------------------------------------------------------

    private Set<String> baselineFingerprints() {
        String raw;
        try (var in = getClass().getResourceAsStream(BASELINE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Baseline resource missing: " + BASELINE_RESOURCE);
            }
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> out = new LinkedHashSet<>();
        for (String line : raw.split("\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            // Format: file | key | argIndex | argExpr | justification
            String[] f = t.split("\\|", 5);
            if (f.length < 5) {
                throw new IllegalStateException("Malformed baseline line (need "
                        + "5 pipe-separated fields incl. justification): " + line);
            }
            out.add(f[0].strip() + " | " + f[1].strip() + " | " + f[2].strip()
                    + " | " + f[3].strip());
        }
        return out;
    }

    // ---- source-text utilities --------------------------------------------

    /** Remove block and line comments, respecting string/char literals. */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        boolean inStr = false, inChar = false;
        while (i < n) {
            char c = src.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < n) { out.append(src.charAt(++i)); }
                else if (c == '"') inStr = false;
                i++;
            } else if (inChar) {
                out.append(c);
                if (c == '\\' && i + 1 < n) { out.append(src.charAt(++i)); }
                else if (c == '\'') inChar = false;
                i++;
            } else if (c == '"') { inStr = true; out.append(c); i++; }
            else if (c == '\'') { inChar = true; out.append(c); i++; }
            else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                i += 2;
            } else { out.append(c); i++; }
        }
        return out.toString();
    }

    /** {@code source.charAt(openParen) == '('}; return the inside of the matched pair. */
    static String balancedArgs(String source, int openParen) {
        int depth = 0, i = openParen, n = source.length(), start = openParen + 1;
        boolean inStr = false, inChar = false;
        while (i < n) {
            char c = source.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else if (inChar) {
                if (c == '\\') i++;
                else if (c == '\'') inChar = false;
            } else if (c == '"') inStr = true;
            else if (c == '\'') inChar = true;
            else if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return source.substring(start, i);
            }
            i++;
        }
        return null;
    }

    /** Split a call's argument string on top-level commas. */
    static List<String> splitTopArgs(String argStr) {
        List<String> args = new ArrayList<>();
        int depth = 0, n = argStr.length();
        boolean inStr = false, inChar = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < n; i++) {
            char c = argStr.charAt(i);
            if (inStr) {
                cur.append(c);
                if (c == '\\' && i + 1 < n) cur.append(argStr.charAt(++i));
                else if (c == '"') inStr = false;
            } else if (inChar) {
                cur.append(c);
                if (c == '\\' && i + 1 < n) cur.append(argStr.charAt(++i));
                else if (c == '\'') inChar = false;
            } else if (c == '"') { inStr = true; cur.append(c); }
            else if (c == '\'') { inChar = true; cur.append(c); }
            else if (c == '(' || c == '[' || c == '{') { depth++; cur.append(c); }
            else if (c == ')' || c == ']' || c == '}') { depth--; cur.append(c); }
            else if (c == ',' && depth == 0) { args.add(cur.toString().strip()); cur.setLength(0); }
            else cur.append(c);
        }
        if (!cur.toString().strip().isEmpty()) args.add(cur.toString().strip());
        return args;
    }

    private static final Pattern WS = Pattern.compile("\\s+");

    static String norm(String expr) {
        return WS.matcher(expr).replaceAll(" ").strip();
    }
}
