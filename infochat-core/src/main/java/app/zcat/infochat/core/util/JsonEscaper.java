package app.zcat.infochat.core.util;


/**
 * Escapes a raw string into the <em>content</em> of a JSON string
 * literal (no surrounding double-quotes — callers that emit a full
 * literal wrap the result themselves).
 *
 * <p>Escapes backslash and double-quote, emits the named shortcuts for
 * {@code \n \r \t}, and {@code \\u}-escapes every other C0 control
 * character ({@code c < 0x20}) as {@code \\uXXXX}. The C0 branch is the
 * load-bearing correctness property: ingest text (feed titles, post
 * bodies) carries raw control characters, and a raw control byte inside
 * a JSON string literal is invalid JSON. The hand-rolled escapers this
 * class replaces handled only a subset of C0 and emitted the rest raw.
 */
public final class JsonEscaper {

    // Lowercase hex lookup for the \\uXXXX nibble emitter. Pinned lowercase to
    // stay byte-identical to the String.format("\\u%04x", ...) this replaced.
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private JsonEscaper() {
    }

    public static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        // Emit \\uXXXX by direct nibble lookup rather than
                        // String.format: escape() runs on the attacker-influenced
                        // ingest path (feed titles, post bodies) where a hostile
                        // body can carry many control bytes, and String.format
                        // allocates a Formatter + Appendable per call. The output
                        // is byte-identical to format("\\u%04x", (int) c).
                        out.append("\\u")
                           .append(HEX_DIGITS[(c >> 12) & 0xF])
                           .append(HEX_DIGITS[(c >> 8) & 0xF])
                           .append(HEX_DIGITS[(c >> 4) & 0xF])
                           .append(HEX_DIGITS[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
