package app.zcat.infochat.provider.digest;

import app.zcat.infochat.core.util.TagNormalizer;
import app.zcat.infochat.provider.summary.EligiblePostQuery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One ranking mechanism, three consumers (digest topics footer, bare
 * /topic listing, --full): weightedCount = postCount + corroboration,
 * corroboration = round(100 × distinct tag sources ÷ active sources)
 * (D71's denominator variant at tag level); ties postCount DESC then
 * name ASC, integer arithmetic only (D19).
 */
public final class TopicRanking {

    /** One free tag's integer statistics over the ranked post set. */
    public record RankedTopic(
            String name,
            int postCount,
            int distinctSources,
            int corroboration,
            int weightedCount) {}

    private TopicRanking() {
    }

    public static List<RankedTopic> rank(List<EligiblePostQuery.Post> posts) {
        int activeSources = (int) posts.stream()
                .map(EligiblePostQuery.Post::sourceId)
                .distinct()
                .count();
        Map<String, Integer> postCount = new LinkedHashMap<>();
        Map<String, java.util.Set<UUID>> sources = new HashMap<>();
        for (EligiblePostQuery.Post post : posts) {
            for (String tag : post.searchTags()) {
                if (tag == null || !TagNormalizer.TAG_NAME_PATTERN.matcher(tag).matches()) {
                    continue;
                }
                postCount.merge(tag, 1, Integer::sum);
                sources.computeIfAbsent(tag, t -> new java.util.HashSet<>(8))
                        .add(post.sourceId());
            }
        }
        List<RankedTopic> ranked = new ArrayList<>(postCount.size());
        for (Map.Entry<String, Integer> entry : postCount.entrySet()) {
            String tag = entry.getKey();
            int count = entry.getValue();
            int distinct = java.util.Objects.requireNonNull(sources.get(tag)).size();
            int corroboration = activeSources == 0
                    ? 0
                    // Integer half-up rounding of 100×distinct÷active, no
                    // floating point anywhere in the ranking.
                    : (int) ((100L * distinct + activeSources / 2) / activeSources);
            ranked.add(new RankedTopic(tag, count, distinct, corroboration,
                    count + corroboration));
        }
        ranked.sort(Comparator
                .comparingInt(RankedTopic::weightedCount).reversed()
                .thenComparing(Comparator.comparingInt(RankedTopic::postCount).reversed())
                .thenComparing(RankedTopic::name));
        return List.copyOf(ranked);
    }
}
