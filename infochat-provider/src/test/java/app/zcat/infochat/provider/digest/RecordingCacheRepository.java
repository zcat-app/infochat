package app.zcat.infochat.provider.digest;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Recording {@link SummaryCacheRepository} stub: captures the last
 * insert's arguments without touching the DB; can throw a seeded
 * {@link SQLException} on the next insert.
 */
final class RecordingCacheRepository extends SummaryCacheRepository {
    private int inserts;
    private String lastContent;
    private boolean lastIsDegraded;
    private long lastTagSubVer;
    private long lastSrcSubVer;
    private SQLException nextFailure;

    int insertCount() { return inserts; }
    String lastContent() { return lastContent; }
    boolean lastIsDegraded() { return lastIsDegraded; }
    long lastTagSubVer() { return lastTagSubVer; }
    long lastSrcSubVer() { return lastSrcSubVer; }

    void failNextInsert(SQLException failure) {
        this.nextFailure = failure;
    }

    @Override
    public void insert(UUID groupId, String slotKind, Instant slotFiredAt,
                       long tagSubscriptionVersion, long sourceSubscriptionVersion,
                       String content, boolean isDegraded, Instant expiresAt)
            throws SQLException {
        if (nextFailure != null) {
            SQLException failure = nextFailure;
            nextFailure = null;
            throw failure;
        }
        inserts++;
        lastContent = content;
        lastIsDegraded = isDegraded;
        lastTagSubVer = tagSubscriptionVersion;
        lastSrcSubVer = sourceSubscriptionVersion;
    }
}
