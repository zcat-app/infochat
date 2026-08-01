package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.digest.DigestCategorizer.CategorySection;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what retrieval does with a post carrying {@code tags = '{}'} — the
 * state M1-726 turns from a rare tagger failure into a normal outcome, since
 * a correct "no topic fits" reply no longer relabels the post with its
 * source's {@code bootstrap_tags}.
 *
 * <p>The post is SEEDED directly rather than produced by the tagger: the
 * tagger runs in the Collector, whose module graph cannot reach
 * {@link EligiblePostQuery}, {@link DigestPostCollector} or
 * {@link DigestCategorizer}. So the split is forced, not chosen — the
 * collector-side {@code TaggerWorkerTest} / {@code TaggerWorkerIT} pin that
 * the tagger PRODUCES the empty-tags state, and this IT pins what the real
 * provider-side queries then DO with it.
 *
 * <p>The three legs are the reachability claim in full: excluded from
 * topic-keyed retrieval, still present in the corpus, and rendered under the
 * D62 Other bucket rather than under a topic header it does not belong to.
 */
@QuarkusTest
class UntaggedPostRetrievalIT {

    private static final String PREFIX = "m1-726-";

    /**
     * The source's bootstrap tag. Before M1-726 the tagger stamped exactly
     * this tag onto a post it had judged to have no topic, which is what put
     * the post under this tag's digest header and inside
     * {@code /summary <tag>}.
     */
    private static final String SOURCE_TOPIC_TAG = PREFIX + "topic";

    private static final String UNTAGGED_UID = PREFIX + "untagged";

    /** Wide enough to hold every seed, short enough to exclude older fixtures. */
    private static final Duration WINDOW = Duration.ofHours(1);

    /**
     * Every fixture instant derives from this pinned "now" and the injected
     * Clock is fixed to it (M1-740): posts land in the migration-provisioned
     * May 2026 partition and the retrieval windows are deterministic, instead
     * of breaking on each unprovisioned month boundary.
     */
    private static final Instant PINNED_NOW = Instant.parse("2026-05-22T12:00:00Z");

    @Inject @SeedDataSource DataSource dataSource;

    @Inject DigestPostCollector digestPostCollector;

    @Inject DigestCategorizer digestCategorizer;

    @Inject EligiblePostQuery eligiblePostQuery;

    private UUID groupId;

    @BeforeEach
    void setUp() throws Exception {
        // EligiblePostQuery's ready_at window reads the injected Clock — pin
        // it into the same time family as the fixtures.
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        cleanupFixtures();
        groupId = insertGroup();
        UUID sourceId = insertBootstrapSource();
        insertTag();

        // Three tagged posts so SOURCE_TOPIC_TAG clears the categorizer's
        // three-cluster qualifying threshold and really does render a header;
        // without them the Other-bucket assertion would pass vacuously,
        // because a single-cluster tag folds into Other on its own.
        Instant now = PINNED_NOW;
        insertPost(sourceId, PREFIX + "tagged-a", "Tagged A",
                now.minus(Duration.ofMinutes(1)), List.of(SOURCE_TOPIC_TAG));
        insertPost(sourceId, PREFIX + "tagged-b", "Tagged B",
                now.minus(Duration.ofMinutes(2)), List.of(SOURCE_TOPIC_TAG));
        insertPost(sourceId, PREFIX + "tagged-c", "Tagged C",
                now.minus(Duration.ofMinutes(3)), List.of(SOURCE_TOPIC_TAG));
        insertPost(sourceId, UNTAGGED_UID, "Untagged",
                now.minus(Duration.ofMinutes(4)), List.of());
    }

    // A bootstrap-origin fixture source is visible to EVERY scope under the
    // D59 world predicate, so one left behind would pollute other classes'
    // scope-isolated retrieval assertions (DigestPostCollectorIT's rule).
    @AfterEach
    void tearDown() throws Exception {
        cleanupFixtures();
    }

    @Test
    void untaggedPostRendersUnderOtherRatherThanTheSourcesTopicHeader() throws Exception {
        List<Post> collected = collectPrefixedDigestPosts();
        assertEquals(4, collected.size(),
                "all four seeds are inside the digest window; got "
                        + collected.stream().map(Post::uid).toList());

        List<CategorySection> sections = digestCategorizer.categorize(singletonClusters(collected));

        CategorySection topicSection = namedSection(sections, SOURCE_TOPIC_TAG)
                .orElseThrow(() -> new AssertionError(
                        "the three tagged posts must form a real topic header for the assertion "
                                + "below to mean anything; sections: " + describe(sections)));
        assertFalse(clusterUids(topicSection).contains(UNTAGGED_UID),
                "an untagged post must not appear under the topic header its source's "
                        + "bootstrap_tags would previously have placed it under");

        CategorySection other = otherSection(sections)
                .orElseThrow(() -> new AssertionError(
                        "the untagged post must produce an Other bucket; sections: "
                                + describe(sections)));
        assertTrue(clusterUids(other).contains(UNTAGGED_UID),
                "a post with no qualifying tag renders in the D62 Other bucket, where a reader "
                        + "can still see it; Other held " + clusterUids(other));
    }

    @Test
    void untaggedPostDoesNotMatchPositionalSummaryTag() {
        // /summary <tag> → `p.tags @> ARRAY[?]`, which an empty array cannot
        // satisfy. This is the user-visible half of the fix: asking for the
        // topic stops returning the post that merely came from a source about
        // that topic.
        List<String> uids = fetchUids(Optional.of(SOURCE_TOPIC_TAG));

        assertFalse(uids.contains(UNTAGGED_UID),
                "/summary " + SOURCE_TOPIC_TAG + " must not return an untagged post; got " + uids);
        assertTrue(uids.contains(PREFIX + "tagged-a"),
                "the genuinely tagged posts still match the positional tag; got " + uids);
    }

    @Test
    void untaggedPostIsStillRetrievedByBareSummaryInAllTagMode() {
        // The complement, and the reason this ticket narrows reachability
        // without losing content: the default scope (tag_mode='ALL', no
        // positional tag, no followed tags) applies NO tag predicate, so the
        // post is still in the corpus — it just stops being filed by topic.
        List<String> uids = fetchUids(Optional.empty());

        assertTrue(uids.contains(UNTAGGED_UID),
                "a bare /summary in an ALL-mode scope applies no tag predicate and must still "
                        + "reach the untagged post; got " + uids);
    }

    // -- query helpers --------------------------------------------------------

    private List<String> fetchUids(Optional<String> positionalTag) {
        return eligiblePostQuery.fetch("group", groupId, positionalTag, WINDOW)
                .posts().stream()
                .map(Post::uid)
                .filter(uid -> uid.startsWith(PREFIX))
                .toList();
    }

    /**
     * The real digest collection, narrowed to this class's own fixtures. The
     * query decides whether the untagged post is collected at all (asserted on
     * the returned size); the filter only keeps a concurrently-seeded bootstrap
     * post from another class perturbing the categorizer's tag arithmetic.
     */
    private List<Post> collectPrefixedDigestPosts() throws SQLException {
        return digestPostCollector.collectForGroup(groupId, PINNED_NOW.minus(WINDOW))
                .posts().stream()
                .filter(post -> post.uid().startsWith(PREFIX))
                .toList();
    }

    private static List<Cluster> singletonClusters(List<Post> posts) {
        List<Cluster> clusters = new ArrayList<>(posts.size());
        for (Post post : posts) {
            clusters.add(new Cluster("cluster-" + post.uid(), List.of(post)));
        }
        return clusters;
    }

    private static Optional<CategorySection> namedSection(
            List<CategorySection> sections, String tag) {
        return sections.stream().filter(section -> tag.equals(section.tag())).findFirst();
    }

    private static Optional<CategorySection> otherSection(List<CategorySection> sections) {
        return sections.stream().filter(section -> section.tag() == null).findFirst();
    }

    private static List<String> clusterUids(CategorySection section) {
        return section.clusters().stream()
                .flatMap(cluster -> cluster.posts().stream())
                .map(Post::uid)
                .toList();
    }

    private static String describe(List<CategorySection> sections) {
        return sections.stream()
                .map(section -> (section.tag() == null ? "OTHER" : section.tag())
                        + "=" + clusterUids(section))
                .toList()
                .toString();
    }

    // -- fixture helpers ------------------------------------------------------

    private void cleanupFixtures() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM tag WHERE name LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM groups WHERE upstream_group_id LIKE '" + PREFIX + "%'");
        }
    }

    private UUID insertGroup() throws Exception {
        // No source_subscription, no scope_tag and no scope_preferences row:
        // the default scope shape the third leg needs — tag_mode='ALL' with
        // zero followed tags, so neither the EXPLICIT filter nor the >5-tag
        // top-3 restriction fires.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, display_name, timezone) "
                             + "VALUES ('inmemory', ?, 'Untagged Retrieval IT Group', 'UTC') "
                             + "RETURNING id")) {
            ps.setString(1, PREFIX + "group");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    /**
     * A live bootstrap-origin source carrying a non-empty {@code
     * bootstrap_tags} — implicitly in every scope's world (D59), and the
     * source whose topic tag the tagger used to hand to its off-topic posts.
     */
    private UUID insertBootstrapSource() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status, source_origin) "
                             + "VALUES ('rss', ?, 'Untagged Retrieval IT Source', 'news', ?, "
                             + "'active', 'bootstrap') RETURNING id")) {
            ps.setString(1, PREFIX + "src");
            ps.setArray(2, conn.createArrayOf("TEXT", new String[] { SOURCE_TOPIC_TAG }));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertTag() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tag (name, display, source_origin) "
                             + "VALUES (?, ?, 'bootstrap')")) {
            ps.setString(1, SOURCE_TOPIC_TAG);
            ps.setString(2, SOURCE_TOPIC_TAG);
            ps.executeUpdate();
        }
    }

    /**
     * Seeds a READY post. {@code fetched_at} (the partition key) and
     * {@code ready_at} mirror {@code published_at} — the negligible-lag
     * shape, where the digest's window and /summary's agree on membership.
     * All instants derive from the pinned {@link #PINNED_NOW}, so the
     * fixture lands in the May 2026 partition and cannot age out of either
     * window on a future calendar date (M1-740).
     */
    private void insertPost(UUID sourceId, String uid, String title, Instant at,
            List<String> tags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, published_at, fetched_at, "
                             + "ready_at, status, tags, upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?)")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setTimestamp(5, Timestamp.from(at));
            ps.setTimestamp(6, Timestamp.from(at));
            ps.setTimestamp(7, Timestamp.from(at));
            ps.setArray(8, conn.createArrayOf("TEXT", tags.toArray(new String[0])));
            ps.setString(9, uid);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
