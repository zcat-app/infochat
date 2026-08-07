package app.zcat.infochat.collector.eval.stage1;

import org.owasp.html.HtmlStreamEventReceiver;

import java.util.List;
import java.util.Set;

/**
 * Receives {@code OWASP_POLICY}'s post-allowlist event stream and emits
 * plain text — {@code docs/design/04-security.md} §4.2 step 4 bullet 3
 * ("Convert allowed-but-formatted HTML to plain text equivalent for
 * storage in {@code post.body}", M1-784). The policy object stays the
 * single authority on what is markup; this class only changes what the
 * surviving events render to (text instead of re-serialized HTML).
 *
 * <p>Emission rules:
 * <ul>
 *   <li>Text is appended verbatim — never entity-encoded, collapsed or
 *       re-normalized, so a markup-free body persists byte-identical
 *       and pre-existing line starts stay where rule 3's {@code (?m)^}
 *       anchor expects them.</li>
 *   <li>A run of block boundaries (open or close of a
 *       {@link #BLOCK_BOUNDARY_ELEMENTS} element, or a {@code <br>})
 *       becomes ONE {@code '\n'}, emitted lazily when the next text
 *       arrives — so {@code </p><p>} yields a single line break and a
 *       document never starts or ends with a synthesized newline.
 *       Whitespace-only text next to a pending boundary is absorbed
 *       into that newline (inter-element formatting, not content).</li>
 *   <li>Attributes are never emitted: an anchor contributes its text
 *       only, per the M1-784 contract ({@code post.url} carries the
 *       item's own URL).</li>
 *   <li>Elements the policy dropped never reach this receiver, so a
 *       dropped element cannot contribute a boundary newline.</li>
 * </ul>
 *
 * <p>One-shot: create one instance per sanitize call and read
 * {@link #bodyText()} after the parse; the accumulator is not reusable
 * or thread-safe.
 */
final class PlainTextSink implements HtmlStreamEventReceiver {

    /**
     * The block subset of the elements {@code Stage1Pipeline.OWASP_POLICY}
     * can emit — exactly {@code Sanitizers.BLOCKS}' element list
     * (verified against owasp-java-html-sanitizer 20240325.1); every
     * other allowlisted element (FORMATTING's inline set, LINKS' a) is
     * inline. If the policy ever widens, this set must be re-derived
     * with it.
     */
    private static final Set<String> BLOCK_BOUNDARY_ELEMENTS = Set.of(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "li", "blockquote");

    private final StringBuilder out = new StringBuilder();
    private boolean boundaryPending;

    @Override
    public void openDocument() {
    }

    @Override
    public void closeDocument() {
        // A boundary still pending here is discarded: no trailing newline.
    }

    @Override
    public void openTag(String elementName, List<String> attrs) {
        if ("br".equals(elementName) || BLOCK_BOUNDARY_ELEMENTS.contains(elementName)) {
            boundaryPending = true;
        }
    }

    @Override
    public void closeTag(String elementName) {
        if (BLOCK_BOUNDARY_ELEMENTS.contains(elementName)) {
            boundaryPending = true;
        }
    }

    @Override
    public void text(String text) {
        if (boundaryPending) {
            if (text.isBlank()) {
                return;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            boundaryPending = false;
        }
        out.append(text);
    }

    /** The collected plain-text body. */
    String bodyText() {
        return out.toString();
    }
}
