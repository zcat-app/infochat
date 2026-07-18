package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the M1-645 D1 invariant: every dash-prefixed flag the {@code /summary}
 * help surface advertises is a flag {@link SummaryArgs#parse} actually accepts.
 *
 * <p><b>Red-before / green-after.</b> {@code help.cmd.summary.short} advertised
 * {@code [--since <duration>] [--tag <tag>]} while the parser folds every
 * dash-prefixed token other than {@code -w} to
 * {@code Failure(error.summary.window_out_of_range)} — a user who followed the
 * help text got an error. The sibling {@code help.cmd.summary.usage} value for
 * the same command was already correct, so the two strings contradicted each
 * other inside one file, in both locales. Per the ticket's out_of_scope the fix
 * is to the string, not the parser: the narrow accepted set is deliberate.
 *
 * <p>Deliberately scoped to {@code /summary}. A generic flag-parity harness
 * across all catalogue commands is a much larger design problem — argument
 * shapes are not uniform, so there is no single parser to feed — and is not
 * authorized here.
 *
 * <p>Plain JUnit (no Quarkus boot): the real {@link BundleLoader} is built by
 * hand and its package-private {@code load()} invoked via reflection (mirroring
 * {@link ProbationCommandListConsistencyTest}) so the assertions run against the
 * production {@code en}/{@code cs} bundle values.
 */
class SummaryHelpFlagParityTest {

    /**
     * A dash-prefixed flag token: one or two leading dashes then a letter.
     * The {@code (?<![\w-])} guard is what keeps the duration ranges the usage
     * block spells out ({@code 1h-168h}, {@code 1d-30d}, {@code 1w-4w}) from
     * reading as flags — their dash is preceded by a word character.
     */
    private static final Pattern FLAG_TOKEN = Pattern.compile("(?<![\\w-])(--?[A-Za-z][\\w-]*)");

    private BundleLoader bundleLoader;

    @BeforeEach
    void setUp() throws Exception {
        bundleLoader = new BundleLoader();
        Method load = BundleLoader.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(bundleLoader);
    }

    @Test
    void advertisedSummaryFlagsAreAcceptedByParser() {
        for (String lang : List.of("en", "cs")) {
            for (String key : List.of(BundleKeys.HELP_CMD_SUMMARY_SHORT, BundleKeys.HELP_CMD_SUMMARY_USAGE)) {
                String helpText = bundleLoader.get(key, lang);
                for (String flag : extractFlags(helpText)) {
                    assertTrue(parserAcceptsFlag(flag),
                            lang + " " + key + " advertises flag " + flag
                                    + " but SummaryArgs.parse rejects it; got help text: " + helpText);
                }
            }
        }
    }

    /**
     * Guards the extractor itself: if {@link #FLAG_TOKEN} ever stopped matching
     * anything, {@code advertisedSummaryFlagsAreAcceptedByParser} would pass
     * vacuously. {@code -w} is the one flag the parser accepts, so the usage
     * block must always yield it.
     */
    @Test
    void flagExtractorFindsTheOneRealFlagAndNotDurationRanges() {
        Set<String> usageFlags = extractFlags(bundleLoader.get(BundleKeys.HELP_CMD_SUMMARY_USAGE, "en"));
        assertTrue(usageFlags.contains("-w"),
                "extractor must find -w in the summary usage block; got: " + usageFlags);
        assertFalse(usageFlags.contains("-168h"),
                "extractor must not read the duration range 1h-168h as a flag; got: " + usageFlags);
    }

    private static Set<String> extractFlags(String helpText) {
        Set<String> flags = new LinkedHashSet<>();
        Matcher matcher = FLAG_TOKEN.matcher(helpText);
        while (matcher.find()) {
            flags.add(matcher.group(1));
        }
        return flags;
    }

    /**
     * Whether the parser accepts {@code flag} in either arity — bare, or
     * carrying a value. Trying both is what keeps the assertion about flag
     * ACCEPTANCE rather than about any one flag's argument shape: {@code -w}
     * needs a value ({@code /summary -w} alone is a Failure), a hypothetical
     * boolean flag would not, and an unadvertised flag fails in both forms.
     */
    private static boolean parserAcceptsFlag(String flag) {
        return parses("/summary " + flag) || parses("/summary " + flag + " 24h");
    }

    private static boolean parses(String rawBody) {
        return SummaryArgs.parse(rawBody) instanceof SummaryArgs.Success;
    }
}
