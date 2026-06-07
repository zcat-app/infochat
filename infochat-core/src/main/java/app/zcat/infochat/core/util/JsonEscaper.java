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
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
