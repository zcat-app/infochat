package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Programmable {@link DigestPostCollector} stub: returns the seeded
 * posts + subscription versions, or throws the seeded failure. Honors
 * the collection lower bound — a seeded post published before
 * {@code since} is filtered out, so window-bound tests are not vacuous.
 */
final class RecordingPostCollector extends DigestPostCollector {
    private List<Post> posts = List.of();
    private long tagVer;
    private long srcVer;
    private RuntimeException failure;
    private Instant lastSince;

    void seed(List<Post> posts, long tagVer, long srcVer) {
        this.posts = posts;
        this.tagVer = tagVer;
        this.srcVer = srcVer;
        this.failure = null;
    }

    void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    Instant lastSince() {
        return lastSince;
    }

    @Override
    public CollectionResult collectForGroup(UUID groupId, Instant since) {
        if (failure != null) {
            throw failure;
        }
        lastSince = since;
        List<Post> visible = posts.stream()
                .filter(post -> {
                    Instant published = post.publishedAt();
                    return published != null && !published.isBefore(since);
                })
                .toList();
        return new CollectionResult(visible, tagVer, srcVer);
    }
}
