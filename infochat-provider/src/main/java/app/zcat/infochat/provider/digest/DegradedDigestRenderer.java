package app.zcat.infochat.provider.digest;

import java.util.List;

import org.jspecify.annotations.NonNull;

import app.zcat.infochat.provider.summary.EligiblePostQuery;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Produces a headlines-only digest (no LLM call) — the degraded fallback
 * per spec when the LLM does not return within the slot window.
 */
@ApplicationScoped
public class DegradedDigestRenderer {

    public @NonNull String render(@NonNull List<EligiblePostQuery.Post> posts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < posts.size(); i++) {
            if (i > 0) sb.append("\n\n");
            EligiblePostQuery.Post p = posts.get(i);
            sb.append(p.title()).append(" — ").append(p.sourceDisplayName());
            if (p.url() != null && !p.url().isEmpty()) {
                sb.append('\n').append(p.url());
            }
        }
        return sb.toString();
    }
}
