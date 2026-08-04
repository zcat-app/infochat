package app.zcat.infochat.provider.digest;

import java.util.List;


import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.render.DisplayHeadline;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Produces a headlines-only digest (no LLM call) — the degraded fallback
 * per spec when the LLM does not return within the slot window.
 *
 * <p>The headline is source-authored text — the post title, or its body when
 * the title is empty — and each entry renders at line start in a
 * group-broadcast digest, so it is passed through {@link LlmOutputSanitizer}
 * inside {@link DisplayHeadline} (M1-675): text shaped like a privileged
 * command would otherwise land a copy-paste-ready admin line in front of
 * every group member. Sanitizing is deterministic string processing, not an
 * LLM call, and is a byte-identical no-op on text with no closed-list
 * token. A post with neither title nor body yields no headline at all, and
 * the entry then leads with its source display name (M1-714).
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
 *
 * <p><b>These headlines are never translated (M1-756)</b>, unlike the
 * normal-mode digest headlines {@code DigestRenderer.appendHeadlines}
 * routes through the display-hit leg. The reason is the SPEC PIN, not the
 * translator's cost: {@code docs/spec/security.md} §Failure handling pins
 * degraded output to headlines + URLs + UIDs with NO LLM calls, and that
 * shape is exactly what this renderer exists to produce — the same pin
 * {@code ClusterBlockRenderer} cites for its own degraded skip. The cost
 * argument alone would not carry: the translator is a different
 * {@code ModelTask.TRANSLATOR} route than the summarizer whose failure
 * fell back here, and a cache hit makes no provider call at all. A reader
 * in a non-English scope therefore sees source-language headlines when a
 * slot degrades; that is the degradation, and it is intended.
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
            // An empty headline means the post carries no renderable text, so
            // the token AND its separator drop out and the entry leads with
            // the source display name (M1-714) — never a dangling " — ".
            String headline = DisplayHeadline.of(p, llmOutputSanitizer);
            if (!headline.isEmpty()) {
                sb.append(headline).append(" — ");
            }
            sb.append(p.sourceDisplayName());
            if (p.url() != null && !p.url().isEmpty()) {
                sb.append('\n').append(p.url());
            }
        }
        return sb.toString();
    }
}
