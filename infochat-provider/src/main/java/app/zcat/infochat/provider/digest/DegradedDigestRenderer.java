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
            sb.append(llmOutputSanitizer.sanitize(p.title())).append(" — ").append(p.sourceDisplayName());
            if (p.url() != null && !p.url().isEmpty()) {
                sb.append('\n').append(p.url());
            }
        }
        return sb.toString();
    }
}
