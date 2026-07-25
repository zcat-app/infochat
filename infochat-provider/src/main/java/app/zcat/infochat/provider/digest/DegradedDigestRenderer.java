package app.zcat.infochat.provider.digest;

import java.util.List;


import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Produces a headlines-only digest (no LLM call) — the degraded fallback
 * per spec when the LLM does not return within the slot window.
 *
 * <p>The headline is the source-authored post title and each entry renders
 * at line start in a group-broadcast digest, so the title is passed through
 * {@link LlmOutputSanitizer} (M1-675): a title shaped like a privileged
 * command would otherwise land a copy-paste-ready admin line in front of
 * every group member. Sanitizing is deterministic string processing, not an
 * LLM call, and is a byte-identical no-op on a title with no closed-list
 * token.
 *
 * <p><b>The other two operands are deliberately not sanitized (M1-691).</b>
 * The line joins the sanitized title with {@code sourceDisplayName} and a
 * bare {@code url}, and both of those CAN carry {@code ](} into this
 * method's return value: {@code --name} rejects every {@code /} but no
 * bracket ({@code SourceUpsertService.acceptableOverride}), and a feed url
 * only has to parse as an http(s) {@code java.net.URI}, which permits
 * {@code ]} and {@code (} in its query and fragment. Neither can carry a
 * closed-list token — every entry starts with {@code /}, which
 * {@code acceptableOverride} rejects and a stored url cannot lead with.
 * So what is owed here is the no-link guarantee, and that is carried once
 * for every outbound message by {@code OutboundDelivery}, NOT by this
 * renderer. Running the full sanitizer over a url would be actively wrong:
 * the closed-list pass has no leading-boundary rule, so an ordinary feed
 * url pathed {@code /audit}, {@code /pending}, {@code /digest} or
 * {@code /lang} would be rewritten to {@code [redacted command]}.
 */
@ApplicationScoped
public class DegradedDigestRenderer {

    // Field injection (not constructor) so the implicit no-arg constructor
    // survives for the RecordingDegradedRenderer test stub, which subclasses
    // this renderer and overrides render() wholesale — same pattern the
    // sibling command handlers use.
    @Inject
    LlmOutputSanitizer llmOutputSanitizer;

    public String render(List<EligiblePostQuery.Post> posts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < posts.size(); i++) {
            if (i > 0) sb.append("\n\n");
            EligiblePostQuery.Post p = posts.get(i);
            // sourceDisplayName (and the url below) are NOT sanitized here: the
            // ]( no-link guarantee is carried once at OutboundDelivery (M1-691),
            // so this line may return ]( verbatim. See the class javadoc before
            // "fixing" this locally — running the full sanitizer over a url
            // would rewrite ordinary feed paths to [redacted command].
            sb.append(llmOutputSanitizer.sanitize(p.title())).append(" — ").append(p.sourceDisplayName());
            if (p.url() != null && !p.url().isEmpty()) {
                sb.append('\n').append(p.url());
            }
        }
        return sb.toString();
    }
}
