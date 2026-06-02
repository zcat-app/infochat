package app.zcat.infochat.core.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Canonical tag-normalization rule: NFC normalization + {@code
 * Locale.ROOT} lower-case + character-class validation against
 * {@link #TAG_NAME_PATTERN} ({@code ^[a-z0-9][a-z0-9-]{0,47}$}).
 *
 * <p>Returns the normalized form, or {@code null} when the input fails
 * the character-class filter. Callers that want a hard failure on an
 * invalid tag null-check the result and throw themselves; the helper
 * itself never throws (so it composes in both the lenient filter path
 * and the fail-fast bootstrap path).
 */
public final class TagNormalizer {

    public static final Pattern TAG_NAME_PATTERN =
        Pattern.compile("^[a-z0-9][a-z0-9-]{0,47}$");

    private TagNormalizer() {
    }

    public static @Nullable String normalize(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String lower = Normalizer.normalize(raw, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return TAG_NAME_PATTERN.matcher(lower).matches() ? lower : null;
    }
}
