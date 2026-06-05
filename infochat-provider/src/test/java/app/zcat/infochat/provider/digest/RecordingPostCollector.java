package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Programmable {@link DigestPostCollector} stub: returns the seeded
 * posts + subscription versions, or throws the seeded failure.
 */
final class RecordingPostCollector extends DigestPostCollector {
    private List<Post> posts = List.of();
    private long tagVer;
    private long srcVer;
    private RuntimeException failure;

    void seed(List<Post> posts, long tagVer, long srcVer) {
        this.posts = posts;
        this.tagVer = tagVer;
        this.srcVer = srcVer;
        this.failure = null;
    }

    void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public CollectionResult collectForGroup(UUID groupId, Instant since) {
        if (failure != null) {
            throw failure;
        }
        return new CollectionResult(posts, tagVer, srcVer);
    }
}
