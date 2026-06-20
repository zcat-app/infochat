package app.zcat.infochat.provider.testing;

import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Result;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the {@link SeedFixture} delivers what the downstream testing-tool
 * tickets rely on: after the fixture loads, the deterministic
 * {@code /summary}-style retrieval ({@link EligiblePostQuery}) returns the
 * seeded {@code READY} posts for the fixture's (user, scope), and the seeded
 * {@code RAW} / {@code QUARANTINED} rows are excluded (M1-413 acceptance 3).
 *
 * <p>The fixture's scope is keyed on a fixed UUID unique to this fixture, so
 * the retrieval result contains exactly the three seeded READY posts and no
 * rows from other @SeedDataSource ITs sharing the test DB.
 */
@QuarkusTest
class SeedFixtureIT {

    @Inject @SeedDataSource DataSource dataSource;

    @Inject EligiblePostQuery query;

    @Test
    void seededReadyPostsRetrievableAndNonReadyExcluded() {
        SeedFixture.applyTo(dataSource);

        Result result = query.fetch(SeedFixture.SCOPE_KIND, SeedFixture.USER_ID,
                Optional.empty(), Duration.ofHours(24));

        Set<String> uids = result.posts().stream()
                .map(Post::uid)
                .collect(Collectors.toSet());

        // The three seeded READY posts are returned for the (user, scope) —
        // including the two with NULL embedding (retrieval is embedding-agnostic).
        assertTrue(uids.contains(SeedFixture.READY_UID_SECURITY),
                "security READY post (with embedding) is retrieved");
        assertTrue(uids.contains(SeedFixture.READY_UID_AI),
                "AI READY post (NULL embedding) is retrieved");
        assertTrue(uids.contains(SeedFixture.READY_UID_JAVA),
                "Java READY post (NULL embedding) is retrieved");

        // Exactly the three READY posts — the RAW and QUARANTINED rows are
        // excluded by the status='READY' filter, not merely ranked lower.
        assertEquals(3, result.posts().size(),
                "only the 3 READY posts; seeded RAW + QUARANTINED are excluded");
        assertFalse(uids.contains(SeedFixture.RAW_UID), "RAW row is excluded");
        assertFalse(uids.contains(SeedFixture.QUARANTINED_UID), "QUARANTINED row is excluded");
    }
}
