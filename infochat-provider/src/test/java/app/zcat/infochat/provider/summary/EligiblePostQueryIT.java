package app.zcat.infochat.provider.summary;

import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Result;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-tier IT for {@link EligiblePostQuery}. Seeds a fixed set of
 * sources / subscriptions / posts / scope_preferences / scope_tag rows
 * via raw JDBC and asserts each filter rule from M1-037's acceptance
 * items 5 + 6. Test isolation is keyed on the {@code m1-037q-} prefix:
 * every fixture this IT writes carries it, and {@link #cleanup()}
 * deletes rows matching that prefix before each {@code @Test} so two
 * runs do not race and other tests' rows do not leak in.
 */
@QuarkusTest
@TestProfile(EligiblePostQueryIT.MvpProfile.class)
class EligiblePostQueryIT {

    private static final String PREFIX = "m1-037q-";

    @Inject @SeedDataSource DataSource dataSource;

    @Inject EligiblePostQuery query;

    // @AfterEach too: a bootstrap-origin fixture source is visible to EVERY
    // scope under the D59 world predicate, so one left behind would pollute
    // other classes' scope-isolated retrieval assertions. The before-run
    // cleanup alone (this class's original isolation) is not enough once
    // bootstrap fixtures exist.
    @BeforeEach
    @AfterEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source_exclusion "
                    + "WHERE source_id IN (SELECT id FROM source "
                    + "                     WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source_subscription "
                    + "WHERE source_id IN (SELECT id FROM source "
                    + "                     WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM scope_tag "
                    + "WHERE tag_id IN (SELECT id FROM tag WHERE name LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM scope_preferences "
                    + "WHERE scope_id IN ("
                    + "  SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM tag WHERE name LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void filtersOutNonReadyStatusRows() throws Exception {
        UUID userId = insertUser("ready-filter-user");
        UUID sourceId = insertSource("ready-filter-src", "RFS");
        insertSubscription("dm", userId, sourceId);
        // Same window for all four; status differs.
        Instant now = Instant.now();
        insertPost("ready-filter-r", sourceId, "Ready post", now, "READY", List.of(PREFIX + "news"));
        insertPost("ready-filter-q", sourceId, "Quarantined", now, "QUARANTINED", List.of(PREFIX + "news"));
        insertPost("ready-filter-n", sourceId, "Needs review", now, "NEEDS_REVIEW", List.of(PREFIX + "news"));
        insertPost("ready-filter-x", sourceId, "Raw", now, "RAW", List.of(PREFIX + "news"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(1, result.posts().size(), "only READY posts are returned");
        assertEquals("Ready post", result.posts().get(0).title());
    }

    @Test
    void filtersByPublishedAtWindow() throws Exception {
        UUID userId = insertUser("window-user");
        UUID sourceId = insertSource("window-src", "WIN");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        insertPost("window-fresh", sourceId, "Fresh", now.minus(Duration.ofHours(2)), "READY",
                List.of(PREFIX + "tech"));
        insertPost("window-stale", sourceId, "Stale", now.minus(Duration.ofHours(48)), "READY",
                List.of(PREFIX + "tech"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(1, result.posts().size(), "only posts inside the window survive");
        assertEquals("Fresh", result.posts().get(0).title());
    }

    @Test
    void filtersByActiveSubscriptions() throws Exception {
        UUID userId = insertUser("sub-user");
        UUID subscribedSourceId = insertSource("sub-yes", "Yes");
        UUID unsubscribedSourceId = insertSource("sub-no", "No");
        insertSubscription("dm", userId, subscribedSourceId);

        Instant now = Instant.now();
        insertPost("sub-yes-post", subscribedSourceId, "Yes!", now, "READY", List.of(PREFIX + "news"));
        insertPost("sub-no-post", unsubscribedSourceId, "No!", now, "READY", List.of(PREFIX + "news"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(1, result.posts().size(), "unsubscribed sources are excluded");
        assertEquals("Yes!", result.posts().get(0).title());
    }

    @Test
    void filtersByPositionalTag() throws Exception {
        UUID userId = insertUser("tag-user");
        UUID sourceId = insertSource("tag-src", "TagS");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        insertPost("tag-sec", sourceId, "Security post", now, "READY",
                List.of(PREFIX + "sec", PREFIX + "news"));
        insertPost("tag-ai", sourceId, "AI post", now, "READY",
                List.of(PREFIX + "ai"));

        Result result = query.fetch("dm", userId, Optional.of(PREFIX + "sec"),
                Duration.ofHours(24));
        assertEquals(1, result.posts().size());
        assertEquals("Security post", result.posts().get(0).title());
    }

    @Test
    void emptyResultWhenNoSubscriptions() throws Exception {
        UUID userId = insertUser("nosub-user");
        // Source + post exist but the user has no subscription.
        UUID sourceId = insertSource("nosub-src", "NS");
        Instant now = Instant.now();
        insertPost("nosub-post", sourceId, "Lonely", now, "READY", List.of(PREFIX + "news"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertTrue(result.posts().isEmpty(), "no subscriptions → empty post set");
    }

    @Test
    void emptyResultWhenSubscribedButNoReadyPosts() throws Exception {
        UUID userId = insertUser("empty-window-user");
        UUID sourceId = insertSource("empty-window-src", "EWS");
        insertSubscription("dm", userId, sourceId);
        // No posts at all in the window.
        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertTrue(result.posts().isEmpty());
    }

    @Test
    void postPublishedBeforeTheWindowButReadyInsideItIsReturned() throws Exception {
        // The M1-689 window semantics: `-w 24h` asks for posts that reached
        // readers in the last 24h, not posts a feed dated in the last 24h. An
        // item with a week-old publication date that cleared the evaluation
        // pipeline minutes ago belongs in the window; under the old
        // published_at predicate it was never returned at all, at any point.
        UUID userId = insertUser("late-user");
        UUID sourceId = insertSource("late-src", "LATE");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        insertPost("late-1", sourceId, "Late arrival",
                now.minus(Duration.ofDays(7)), now.minus(Duration.ofMinutes(5)),
                "READY", List.of(PREFIX + "news"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));

        assertEquals(1, result.posts().size(),
                "a post whose published_at predates the window but whose ready_at "
                        + "falls inside it is in the window /summary reports on");
        assertEquals("Late arrival", result.posts().get(0).title());
    }

    @Test
    void retainedRedactionPostIsIncluded() throws Exception {
        // stage2_failed=true posts with [REDACTED:<id>] placeholders are
        // STILL eligible per docs/spec/security.md §Failure handling.
        UUID userId = insertUser("redact-user");
        UUID sourceId = insertSource("redact-src", "RDS");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, published_at, ready_at, "
                             + "status, stage2_failed, tags, upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, 'READY', TRUE, ?, ?)")) {
            ps.setString(1, PREFIX + "redact-1");
            ps.setObject(2, sourceId);
            ps.setString(3, "Redacted post");
            ps.setString(4, "Body with [REDACTED:abc123] placeholder.");
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setArray(7, conn.createArrayOf("TEXT", new String[] { PREFIX + "news" }));
            ps.setString(8, PREFIX + "redact-1");
            ps.executeUpdate();
        }

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(1, result.posts().size(),
                "stage2_failed=true posts with [REDACTED:...] placeholders remain eligible");
        assertTrue(result.posts().get(0).body().contains("[REDACTED:"),
                "the placeholder is preserved in the projected body");
    }

    @Test
    void capDropsOldestAndReportsExcludedCount() throws Exception {
        UUID userId = insertUser("cap-user");
        UUID sourceId = insertSource("cap-src", "CAP");
        insertSubscription("dm", userId, sourceId);
        // Profile cap is 5 in the test profile (set in MvpProfile); seed 8
        // posts so the 3-post surplus is dropped and reported as excluded.
        Instant now = Instant.now();
        for (int i = 0; i < 8; i++) {
            // Stagger by minutes so ORDER BY published_at DESC has a
            // deterministic newest→oldest sequence.
            insertPost("cap-" + i, sourceId, "Post " + i,
                    now.minus(Duration.ofMinutes(i)), "READY", List.of(PREFIX + "news"));
        }

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(5, result.posts().size(),
                "cap from MvpProfile is 5; surplus is dropped");
        assertEquals(8, result.totalBeforeCap());
        assertEquals(3, result.excludedCount());
        assertEquals(5, result.profileCap());
        // Freshest 5 retained (posts 0–4 in time order).
        assertEquals("Post 0", result.posts().get(0).title());
        assertEquals("Post 4", result.posts().get(4).title());
    }

    @Test
    void sqlLimitBoundsMaterializedRowsToClusterCap() throws Exception {
        // The main query carries a SQL-side LIMIT: seeding more
        // eligible posts than clusterCap must never materialize more
        // than the cap in Java — the bound is applied before bodies
        // leave the database, not by a post-hoc subList.
        UUID userId = insertUser("sql-bound-user");
        UUID sourceId = insertSource("sql-bound-src", "SQB");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        for (int i = 0; i < 9; i++) {
            insertPost("sql-bound-" + i, sourceId, "Bound " + i,
                    now.minus(Duration.ofMinutes(i)), "READY", List.of(PREFIX + "news"));
        }

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(5, result.posts().size(),
                "9 eligible posts against cap 5 — the SQL LIMIT bounds the rows");
        // The LIMIT keeps the head of the DESC ordering: freshest five.
        assertEquals("Bound 0", result.posts().get(0).title());
        assertEquals("Bound 4", result.posts().get(4).title());
    }

    @Test
    void sqlLimitPreservesTotalAndExcludedCounts() throws Exception {
        // Cap-excess reporting must not regress under the SQL bound:
        // the window-function count keeps total/excluded exact even
        // though only cap rows come back (a naive LIMIT would report
        // total == cap and excluded == 0).
        UUID userId = insertUser("sql-count-user");
        UUID sourceId = insertSource("sql-count-src", "SQC");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        for (int i = 0; i < 7; i++) {
            insertPost("sql-count-" + i, sourceId, "Count " + i,
                    now.minus(Duration.ofMinutes(i)), "READY", List.of(PREFIX + "news"));
        }

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(5, result.posts().size());
        assertEquals(7, result.totalBeforeCap(),
                "totalBeforeCap is the true pre-LIMIT match count");
        assertEquals(2, result.excludedCount(),
                "excludedCount composes the cap-excess message exactly");
        assertEquals(5, result.profileCap());
    }

    @Test
    void deterministicOrderingByPublishedAtThenIdDesc() throws Exception {
        UUID userId = insertUser("order-user");
        UUID sourceId = insertSource("order-src", "ORD");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        insertPost("order-a", sourceId, "A", now.minus(Duration.ofMinutes(10)), "READY",
                List.of(PREFIX + "news"));
        insertPost("order-b", sourceId, "B", now.minus(Duration.ofMinutes(5)), "READY",
                List.of(PREFIX + "news"));
        insertPost("order-c", sourceId, "C", now.minus(Duration.ofMinutes(20)), "READY",
                List.of(PREFIX + "news"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        List<String> titles = result.posts().stream().map(Post::title).toList();
        // Newest → oldest by published_at.
        assertEquals(List.of("B", "A", "C"), titles);
    }

    @Test
    void explicitTagModeRestrictsToScopeTagUnion() throws Exception {
        UUID userId = insertUser("explicit-user");
        UUID sourceId = insertSource("explicit-src", "EXS");
        insertSubscription("dm", userId, sourceId);
        UUID techTagId = insertTag("explicit-tech");
        UUID sportsTagId = insertTag("explicit-sports");
        insertScopeTag("dm", userId, techTagId);
        // sports is NOT a followed tag; sports posts must be excluded.
        insertScopePreferences("dm", userId, "EXPLICIT");

        Instant now = Instant.now();
        insertPost("explicit-tech", sourceId, "Tech post", now, "READY",
                List.of(PREFIX + "explicit-tech"));
        insertPost("explicit-sports", sourceId, "Sports post", now, "READY",
                List.of(PREFIX + "explicit-sports"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(1, result.posts().size(),
                "EXPLICIT tag_mode + scope_tag={tech} excludes the sports post");
        assertEquals("Tech post", result.posts().get(0).title());
    }

    @Test
    void top3FollowedTagsRuleWithTieBreakOnNameAsc() throws Exception {
        // Acceptance item 6: when scope follows >5 tags AND no positional
        // tag, retrieval restricts to the top 3 most-active in the
        // window, ordered count DESC, name ASC. Seed 7 followed tags
        // + uneven post distribution; include at least one tied pair.
        UUID userId = insertUser("top3-user");
        UUID sourceId = insertSource("top3-src", "T3S");
        insertSubscription("dm", userId, sourceId);

        // 7 followed tags. Counts (post count in 24h window):
        //   alpha=5, beta=4, gamma=4, delta=3, epsilon=2, zeta=1, eta=1.
        // beta and gamma are tied at 4; tie-break → beta first (name ASC).
        // delta=3 is the third. Top-3 set = {alpha, beta, gamma}.
        Map<String, Integer> counts = new HashMap<>();
        counts.put(PREFIX + "alpha", 5);
        counts.put(PREFIX + "beta", 4);
        counts.put(PREFIX + "gamma", 4);
        counts.put(PREFIX + "delta", 3);
        counts.put(PREFIX + "epsilon", 2);
        counts.put(PREFIX + "zeta", 1);
        counts.put(PREFIX + "eta", 1);
        for (String tagName : counts.keySet()) {
            UUID tagId = insertTag(tagName.substring(PREFIX.length()));
            insertScopeTag("dm", userId, tagId);
        }
        insertScopePreferences("dm", userId, "ALL");

        Instant now = Instant.now();
        int seq = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String tagName = e.getKey();
            for (int i = 0; i < e.getValue(); i++) {
                insertPost("top3-" + tagName + "-" + i, sourceId,
                        tagName + " post " + i,
                        now.minus(Duration.ofMinutes(seq++)),
                        "READY",
                        List.of(tagName));
            }
        }

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertTrue(result.topTagRestriction().isPresent(),
                "scope follows >5 tags → top-3 restriction applies");
        EligiblePostQuery.TopTagRestriction restriction = result.topTagRestriction().get();
        assertEquals(7, restriction.followedTagCount());
        assertEquals(List.of(PREFIX + "alpha", PREFIX + "beta", PREFIX + "gamma"),
                restriction.topTagNames(),
                "top-3 ordered count DESC, then name ASC (beta < gamma)");

        // Every returned post intersects the top-3 tag set.
        long delta = result.posts().stream()
                .filter(p -> p.tags().contains(PREFIX + "delta"))
                .count();
        assertEquals(0, delta, "delta-tagged posts are outside top-3 and excluded");

        // The followedCount>threshold path now threads ONE shared connection
        // through all four helpers (countFollowedTags → topActiveFollowedTags →
        // readTagMode → selectPosts), M1-472. Tighten the eligible-set
        // assertions to pin that the restricted set is unchanged: the pre-cap
        // match count is exactly the 13 alpha/beta/gamma posts (5+4+4), and
        // every returned post carries a top-3 tag.
        assertEquals(13, result.totalBeforeCap(),
                "only the 13 alpha/beta/gamma posts match under the top-3 restriction");
        Set<String> top3 = Set.of(PREFIX + "alpha", PREFIX + "beta", PREFIX + "gamma");
        assertTrue(result.posts().stream()
                        .allMatch(p -> p.tags().stream().anyMatch(top3::contains)),
                "every returned post intersects the top-3 tag set on the shared connection");
    }

    @Test
    void noTop3RestrictionWhenScopeFollows5OrFewerTags() throws Exception {
        UUID userId = insertUser("five-user");
        UUID sourceId = insertSource("five-src", "FIV");
        insertSubscription("dm", userId, sourceId);
        for (int i = 0; i < 5; i++) {
            UUID t = insertTag("five-" + i);
            insertScopeTag("dm", userId, t);
        }
        insertScopePreferences("dm", userId, "ALL");

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertFalse(result.topTagRestriction().isPresent(),
                "exactly 5 followed tags is at threshold → no top-3 restriction");
    }

    @Test
    void countWorldSourcesCountsBootstrapAndSubscriptions() throws Exception {
        // Live-DB coverage of the M1-621 countWorldSources SQL. The handler-tier
        // SummaryCommandHandlerTest replaces this query with a test double, so
        // this IT is the only place the real SQL runs against a schema.
        // Delta-based assertions: the bootstrap arm counts EVERY live
        // bootstrap source globally, so an absolute count would couple this
        // test to unrelated fixtures.
        UUID subscribedUser = insertUser("countsub-user");
        UUID unsubscribedUser = insertUser("countsub-other");
        int baseline = query.countWorldSources("dm", unsubscribedUser);

        UUID sourceA = insertSource("countsub-a", "CSA");
        UUID sourceB = insertSource("countsub-b", "CSB");
        insertSubscription("dm", subscribedUser, sourceA);
        insertSubscription("dm", subscribedUser, sourceB);
        assertEquals(baseline + 2, query.countWorldSources("dm", subscribedUser),
                "the subscription arm adds the scope's own ('user'-origin) subscriptions");
        assertEquals(baseline, query.countWorldSources("dm", unsubscribedUser),
                "another scope's custom subscriptions never enter this scope's world");

        UUID bootstrapSource = insertBootstrapSource("countsub-boot", "CSBOOT");
        assertEquals(baseline + 1, query.countWorldSources("dm", unsubscribedUser),
                "a live bootstrap source is implicitly in every scope's world");

        insertExclusion("dm", unsubscribedUser, bootstrapSource);
        assertEquals(baseline, query.countWorldSources("dm", unsubscribedUser),
                "the scope's exclusion removes the bootstrap source from ITS world only");
        assertEquals(baseline + 3, query.countWorldSources("dm", subscribedUser),
                "the other scope still sees the bootstrap source (per-scope exclusion)");
    }

    @Test
    void bootstrapPostVisibleToSubscriptionlessScopeAndExclusionHidesIt() throws Exception {
        // Acceptance item 2 test (a), EligiblePostQuery half: a
        // bootstrap-origin post is retrieved by a scope with NO
        // subscriptions; the scope's exclusion then hides it for that
        // scope only.
        UUID userId = insertUser("boot-user");
        UUID otherUserId = insertUser("boot-other");
        UUID bootstrapSource = insertBootstrapSource("boot-src", "BOOT");
        insertPost("boot-post", bootstrapSource, "Bootstrap post", Instant.now(), "READY",
                List.of(PREFIX + "news"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(1, result.posts().size(),
                "the implicit bootstrap corpus reaches a subscription-less scope");
        assertEquals("Bootstrap post", result.posts().get(0).title());

        insertExclusion("dm", userId, bootstrapSource);
        assertTrue(query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24))
                        .posts().isEmpty(),
                "the excluding scope no longer sees the bootstrap post");
        assertEquals(1, query.fetch("dm", otherUserId, Optional.empty(), Duration.ofHours(24))
                        .posts().size(),
                "a different scope still sees it (exclusion is per-scope)");
    }

    @Test
    void top3RestrictionComputesOverBootstrapWorldForSubscriptionlessScope() throws Exception {
        // topActiveFollowedTags is a world-predicate site too (M1-621): a
        // subscription-less scope following >5 tags must compute a
        // non-empty top-3 over the bootstrap corpus, not an empty set.
        UUID userId = insertUser("boottop3-user");
        UUID bootstrapSource = insertBootstrapSource("boottop3-src", "BT3");
        String[] tagNames = { "bt-a", "bt-b", "bt-c", "bt-d", "bt-e", "bt-f" };
        for (String t : tagNames) {
            insertScopeTag("dm", userId, insertTag(t));
        }
        insertScopePreferences("dm", userId, "ALL");
        Instant now = Instant.now();
        // bt-a=3, bt-b=2, bt-c=1 posts; bt-d/e/f zero.
        for (int i = 0; i < 3; i++) {
            insertPost("boottop3-a" + i, bootstrapSource, "A" + i,
                    now.minus(Duration.ofMinutes(i)), "READY", List.of(PREFIX + "bt-a"));
        }
        for (int i = 0; i < 2; i++) {
            insertPost("boottop3-b" + i, bootstrapSource, "B" + i,
                    now.minus(Duration.ofMinutes(10 + i)), "READY", List.of(PREFIX + "bt-b"));
        }
        insertPost("boottop3-c0", bootstrapSource, "C0",
                now.minus(Duration.ofMinutes(20)), "READY", List.of(PREFIX + "bt-c"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertTrue(result.topTagRestriction().isPresent(),
                ">5 followed tags → restriction fires even with zero subscriptions");
        assertEquals(List.of(PREFIX + "bt-a", PREFIX + "bt-b", PREFIX + "bt-c"),
                result.topTagRestriction().get().topTagNames(),
                "the top-3 computes over the bootstrap world, not an empty subscription join");
        assertEquals(6, result.totalBeforeCap(),
                "all six bootstrap posts carry a top-3 tag and match");
    }

    @Test
    void projectsClassificationIntoPostRecord() throws Exception {
        // p.classification (V57, M1-597) round-trips into Post.classification,
        // seeded DISTINCT from tags so the projection is not confused with tags.
        UUID userId = insertUser("classif-user");
        UUID sourceId = insertSource("classif-src", "CLS");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, published_at, ready_at, "
                             + "status, tags, classification, upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, 'READY', ?, ?, ?)")) {
            ps.setString(1, PREFIX + "classif-1");
            ps.setObject(2, sourceId);
            ps.setString(3, "Classified post");
            ps.setString(4, "Body");
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setArray(7, conn.createArrayOf("TEXT", new String[] { PREFIX + "news" }));
            ps.setArray(8, conn.createArrayOf("TEXT", new String[] { "factual", "technical" }));
            ps.setString(9, PREFIX + "classif-1");
            ps.executeUpdate();
        }

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(1, result.posts().size());
        Post projected = result.posts().get(0);
        assertEquals(List.of("factual", "technical"), projected.classification(),
                "p.classification projects into the Post record, distinct from tags");
        assertEquals(List.of(PREFIX + "news"), projected.tags(),
                "tags remain independently projected");
    }

    // ----- helpers ------------------------------------------------------

    private UUID insertUser(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES ('inmemory', ?, FALSE, 'vouched') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID insertSource(String suffix, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', 'active') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    /** A live bootstrap-origin source — implicitly in every scope's world (D59). */
    private UUID insertBootstrapSource(String suffix, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status, source_origin) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', 'active', 'bootstrap') "
                             + "RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertExclusion(String scopeKind, UUID scopeId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_exclusion (scope_kind, scope_id, source_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    private void insertSubscription(String scopeKind, UUID scopeId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    /**
     * Seeds a post whose {@code ready_at} mirrors its {@code published_at} —
     * the negligible-lag shape, where both windows agree on membership.
     */
    private void insertPost(String uidSuffix, UUID sourceId, String title,
                             Instant publishedAt, String status, List<String> tags) throws Exception {
        insertPost(uidSuffix, sourceId, title, publishedAt, publishedAt, status, tags);
    }

    /**
     * Seeds a post with independent publication and readiness instants.
     * {@code ready_at} is the column the /summary window compares against, so
     * the two diverge exactly when fetch + evaluation lag is what the test is
     * about.
     */
    private void insertPost(String uidSuffix, UUID sourceId, String title,
                             Instant publishedAt, Instant readyAt, String status,
                             List<String> tags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, published_at, ready_at, "
                             + "status, tags, upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, PREFIX + uidSuffix);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setTimestamp(5, Timestamp.from(publishedAt));
            ps.setTimestamp(6, Timestamp.from(readyAt));
            ps.setString(7, status);
            ps.setArray(8, conn.createArrayOf("TEXT", tags.toArray(new String[0])));
            ps.setString(9, PREFIX + uidSuffix);
            ps.executeUpdate();
        }
    }

    private UUID insertTag(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tag (name, display, source_origin) "
                             + "VALUES (?, ?, 'user') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            ps.setString(2, PREFIX + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertScopeTag(String scopeKind, UUID scopeId, UUID tagId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, tagId);
            ps.executeUpdate();
        }
    }

    private void insertScopePreferences(String scopeKind, UUID scopeId, String tagMode) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode) "
                             + "VALUES (?, ?, ?) ON CONFLICT (scope_kind, scope_id) "
                             + "DO UPDATE SET tag_mode = EXCLUDED.tag_mode")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setString(3, tagMode);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /**
     * Pinning {@code cluster-cap=5} keeps the cap-excess test from
     * inserting 200 posts (the production laptop default).
     */
    public static final class MvpProfile implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of(
                    "infochat.summary.cluster-cap", "5",
                    "infochat.profile.label", "test");
        }
    }
}
