package app.zcat.infochat.provider.digest;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Recording {@link DigestCategoryDeliveryRepository} stub: captures
 * {@code recordDelivery} calls and seeds the slugs {@code findDeliveredSlugs}
 * returns, without touching the DB. Used by {@link DigestRetryServiceTest}
 * to seed replay scenarios (which categories are already delivered) and
 * by {@link DigestWorkerTest}-style tests that need the delivery-repo
 * collaborator wired without DB I/O.
 */
final class RecordingCategoryDeliveryRepository extends DigestCategoryDeliveryRepository {
    private final Set<String> recorded = new HashSet<>();
    private Set<String> seededDelivered = new HashSet<>();

    void seedDelivered(Set<String> slugs) { this.seededDelivered = new HashSet<>(slugs); }

    Set<String> recorded() { return recorded; }

    @Override
    public void recordDelivery(UUID groupId, Instant windowStart, String categorySlug) {
        recorded.add(categorySlug);
    }

    @Override
    public Set<String> findDeliveredSlugs(UUID groupId, Instant windowStart) {
        return seededDelivered;
    }
}
