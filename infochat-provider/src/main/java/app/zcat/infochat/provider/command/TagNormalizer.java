package app.zcat.infochat.provider.command;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Controlled-vocabulary tag normalization: the
 * trim&nbsp;&rarr;&nbsp;NFC&nbsp;&rarr;&nbsp;lowercase&nbsp;&rarr;&nbsp;char-class
 * pipeline that {@code docs/spec/commands.md} §Surface conventions
 * mandates before any controlled-vocabulary lookup. Single source for
 * the three controlled-vocabulary tag consumers — {@code /summary}
 * ({@link SummaryArgs}), {@code /follow-tag}
 * ({@link FollowTagCommandHandler}), and {@code /unfollow-tag}
 * ({@link UnfollowTagCommandHandler}) — so a case/Unicode variant of a
 * vocabulary tag resolves identically at every site (M1-489).
 *
 * <p>Free-form personal tags ({@code /saved}) are deliberately NOT run
 * through this helper: personal tags are case-preserving and never join
 * the controlled vocabulary (commands.md), so normalizing them would
 * break their exact-match semantics.</p>
 */
final class TagNormalizer {

    /**
     * Tag-name regex from the V6 {@code tag.name} CHECK constraint and
     * {@code docs/design/03-commands.md} §Tag arguments — the char-class
     * step of the pipeline. The write-side bootstrap loader and
     * {@code /add-source} rely on the same constraint at the SQL
     * boundary; this is the read-side mirror for filters that have no
     * SQL CHECK to fall back on.
     */
    private static final Pattern TAG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,47}$");

    private TagNormalizer() {
    }

    /** trim &rarr; NFC &rarr; {@code Locale.ROOT} lower-case. */
    static String normalize(String raw) {
        return Normalizer.normalize(raw.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /**
     * The char-class step: {@code true} iff the already-normalized tag
     * matches the controlled-vocabulary shape. Callers pass the output
     * of {@link #normalize(String)}.
     */
    static boolean isValid(String normalized) {
        return TAG_PATTERN.matcher(normalized).matches();
    }
}
