package app.zcat.infochat.provider.digest;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Recording {@link SummaryCacheRepository} stub: captures the last
 * upsert's arguments without touching the DB; can throw a seeded
 * {@link SQLException} on the next upsert. DigestWorker writes the cache
 * via {@link SummaryCacheRepository#upsert} (atomic regenerate), so this
 * double records that call rather than the sentinel-only {@code insert}.
 * The previous digest boundary {@link SummaryCacheRepository#findPreviousBoundary}
 * resolves is seedable; it defaults to empty (no prior digest).
 */
final class RecordingCacheRepository extends SummaryCacheRepository {
    private int upserts;
    private String lastContent;
    private boolean lastIsDegraded;
    private long lastTagSubVer;
    private long lastSrcSubVer;
    private Instant lastExpiresAt;
    private SQLException nextFailure;
    private Optional<Instant> previousBoundary = Optional.empty();

    int upsertCount() { return upserts; }
    String lastContent() { return lastContent; }
    boolean lastIsDegraded() { return lastIsDegraded; }
    long lastTagSubVer() { return lastTagSubVer; }
    long lastSrcSubVer() { return lastSrcSubVer; }
    Instant lastExpiresAt() { return lastExpiresAt; }

    void failNextUpsert(SQLException failure) {
        this.nextFailure = failure;
    }

    void seedPreviousBoundary(Instant boundary) {
        this.previousBoundary = Optional.of(boundary);
    }

    @Override
    public Optional<Instant> findPreviousBoundary(UUID groupId, Instant before) {
        return previousBoundary;
    }

    @Override
    public void upsert(UUID groupId, String slotKind, Instant slotFiredAt,
                       long tagSubscriptionVersion, long sourceSubscriptionVersion,
                       String content, boolean isDegraded, Instant expiresAt)
            throws SQLException {
        if (nextFailure != null) {
            SQLException failure = nextFailure;
            nextFailure = null;
            throw failure;
        }
        upserts++;
        lastContent = content;
        lastIsDegraded = isDegraded;
        lastTagSubVer = tagSubscriptionVersion;
        lastSrcSubVer = sourceSubscriptionVersion;
        lastExpiresAt = expiresAt;
    }
}
