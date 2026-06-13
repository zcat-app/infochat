package app.zcat.infochat.provider.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared whitespace-respecting tokenizer for slash-command argument
 * strings. Splits on runs of (Unicode) whitespace and unwraps
 * double-quote pairs so a quoted value such as {@code "Display Name With
 * Spaces"} stays one token. Quote characters are removed; toggling
 * {@code "} flips quoted state, so an unbalanced quote simply runs to the
 * end of the input.
 *
 * <p>Callers feed the already-normalized argument remainder (the router
 * has done NFKC + bidi/ZWS strip upstream); this method only splits and
 * unquotes. Extracted from four byte-identical per-handler copies so the
 * four commands cannot silently diverge in how they parse quoted args.
 */
final class CommandTokenizer {

    private CommandTokenizer() {
    }

    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (!inQuotes && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }
}
