package app.zcat.infochat.core.util;

/** {@code \% \_ \\} — every LIKE metacharacter in {@code value}, backslash-escaped;
 *  only callers append the wildcard (docs/spec/security.md §Prompt-injection defenses). */
public final class LikeEscaper {

    private LikeEscaper() {
    }

    public static String escapeLike(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
