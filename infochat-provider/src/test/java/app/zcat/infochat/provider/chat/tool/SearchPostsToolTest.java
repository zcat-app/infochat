package app.zcat.infochat.provider.chat.tool;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link SearchPostsTool}'s result shape and
 * its window/ordering semantics. Two things are pinned: (1) the emitted
 * {@code ready_at} JSON field carries the post's {@code ready_at} column
 * value (the spec's tool-catalogue shape), not {@code published_at}; and
 * (2) the {@code window} filter binds to {@code ready_at} while result
 * ordering binds to {@code COALESCE(published_at, fetched_at)} (M1-689 —
 * membership is decided on when a post reached readers, presentation on
 * when its source says it was published, falling back to arrival when the
 * source says nothing so that omitting the date cannot buy the head of the
 * result). Seeds fixtures directly via JDBC against the &#64;QuarkusTest
 * DevServices DB.
 */
@QuarkusTest
class SearchPostsToolTest {

    private static final String PREFIX = "search-posts-test/";
    /** Tag names disallow '/', so the tag namespace uses a hyphenated prefix. */
    private static final String TAG_PREFIX = "spt-";
    /** All fixtures share one fetched_at so they land in the V11/V28/V29 May 2026 partition. */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    SearchPostsTool tool;

    @Inject
    CancellationService cancellationService;

    @Inject
    InFlightTracker inFlightTracker;

    @Inject
    EligiblePostQuery eligiblePostQuery;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // scope_tag references tag(id), so it must be cleared before the
            // tag rows; both scope deletes target the prior run's prefixed
            // users, which still exist until the users delete below.
            exec(conn,
                "DELETE FROM scope_tag WHERE scope_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn,
                "DELETE FROM scope_preferences WHERE scope_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM tag WHERE name LIKE '" + TAG_PREFIX + "%'");
            exec(conn,
                "DELETE FROM source_subscription WHERE source_id IN "
                    + "(SELECT id FROM source WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void readyAtFieldCarriesReadyAtColumnValueNotPublishedAt() throws Exception {
        UUID userId = seedUser("ready-at");
        UUID sourceId = seedSource("ready-at-src", "Ready-at source");
        seedSubscription("dm", userId, sourceId);
        // ready_at must sit inside the default search window (ready_at is
        // the window filter); published_at is a distinct value so the
        // assertion can tell the two columns apart.
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(1, ChronoUnit.HOURS);
        Instant readyAt = publishedAt.plus(15, ChronoUnit.MINUTES);
        seedReadyPost("ready-at-post", sourceId, publishedAt, readyAt);

        String json = tool.execute(userId, "dm", userId, Map.of());

        assertTrue(json.contains("\"ready_at\":\"" + readyAt + "\""),
            "the ready_at JSON field carries the ready_at column value; got: " + json);
        assertFalse(json.contains("\"ready_at\":\"" + publishedAt + "\""),
            "the ready_at JSON field must not carry published_at; got: " + json);
    }

    @Test
    void searchAcquiresOneConnectionAppliesTimeoutAndRegistersPid() throws Exception {
        UUID userId = seedUser("arm");
        UUID sourceId = seedSource("arm-src", "Arm source");
        seedSubscription("dm", userId, sourceId);
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(1, ChronoUnit.HOURS);
        seedReadyPost("arm-post", sourceId, publishedAt, publishedAt.plus(5, ChronoUnit.MINUTES));

        // Construct the tool against a counting/recording DataSource that
        // delegates to the seed DB, plus the CDI CancellationService (whose
        // InFlightTracker is the injected singleton). The tool runs real SQL;
        // the wrapper only observes connection acquisitions and executed SQL.
        CountingRecordingDataSource countingDs = new CountingRecordingDataSource(dataSource);
        SearchPostsTool directTool = new SearchPostsTool(countingDs, cancellationService);

        // Hold the in-flight slot as ChatAgent.handle() does for a chat turn,
        // so the tool has a handle to register the backend pid on.
        InFlightTracker.CancellationHandle slot =
                Objects.requireNonNull(inFlightTracker.tryAcquire(userId, "dm", userId));
        try {
            directTool.execute(userId, "dm", userId, Map.of());

            assertEquals(1, countingDs.connectionCount(),
                    "SearchPostsTool must acquire exactly one pooled connection per call");
            assertTrue(countingDs.executedSql().stream()
                            .anyMatch(s -> s.contains("SET LOCAL statement_timeout")),
                    "the single connection must have statement_timeout applied. Got: "
                            + countingDs.executedSql());
            assertTrue(slot.hasPgBackendPid(),
                    "the tool must register the connection's pg backend pid on the in-flight handle");
        } finally {
            inFlightTracker.release(userId, "dm", userId, slot);
        }
    }

    @Test
    void windowFilterBindsToReadyAtNotPublishedAt() throws Exception {
        UUID userId = seedUser("window-bind");
        UUID sourceId = seedSource("window-bind-src", "Window-bind source");
        seedSubscription("dm", userId, sourceId);
        // A late-readied post: published long before the window opens, but
        // readied just now. ready_at is the window filter, so it must be
        // INCLUDED in a 2h window even though its published_at is 5h old
        // (M1-689). Were the window still bound to published_at, this post
        // would be dropped — which is exactly the defect that ticket fixes:
        // a post delayed by fetch + evaluation lag was never delivered at
        // all, rather than merely delivered late.
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(5, ChronoUnit.HOURS);
        Instant readyAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(30, ChronoUnit.MINUTES);
        seedReadyPost("late-readied-post", sourceId, publishedAt, readyAt);

        String json = tool.execute(userId, "dm", userId, Map.of("window", "PT2H"));

        assertTrue(json.contains(PREFIX + "late-readied-post"),
            "a post whose ready_at falls inside the window must be returned even "
                + "when its published_at predates the window; got: " + json);
    }

    @Test
    void undatedPostSortsByFetchCeilingNotByPromotionInstant() throws Exception {
        // M1-689 redteam rounds 1-2. published_at is source-supplied AND
        // nullable, and Postgres sorts NULLs FIRST under DESC, so once the
        // window predicate moved to ready_at (admitting undated posts at all),
        // a bare `ORDER BY published_at DESC` handed the head of this result
        // to any feed that simply OMITS its date — the position re-injected
        // first into the chat prompt, and the one schema.md's ingest clamp
        // exists to defend.
        //
        // The fallback must be fetched_at, not ready_at. This fixture is built
        // so the two choices disagree: the undated post is fetched EARLIER but
        // promoted LATER than the dated one. Keyed on ready_at it would lead
        // (promotion is recent); keyed on fetched_at it trails, which is the
        // shipped behaviour. Both fetched_at values stay inside the May 2026
        // partition the other fixtures use.
        UUID userId = seedUser("null-order");
        UUID sourceId = seedSource("null-order-src", "Null-order source");
        seedSubscription("dm", userId, sourceId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant undatedFetchedAt = FETCHED_AT;
        Instant datedFetchedAt = FETCHED_AT.plus(1, ChronoUnit.DAYS);

        // Dated: fetched a day later, published just under its own fetch
        // instant (the clamp ceiling). Readied an hour ago.
        seedReadyPostAt("dated", sourceId, datedFetchedAt.minus(1, ChronoUnit.HOURS),
                now.minus(1, ChronoUnit.HOURS), datedFetchedAt);
        // Undated: fetched a day EARLIER, no date at all, promoted most
        // recently of the two.
        seedReadyPostAt("undated", sourceId, null,
                now.minus(30, ChronoUnit.MINUTES), undatedFetchedAt);

        String json = tool.execute(userId, "dm", userId, Map.of("window", "PT4H"));

        int datedIndex = json.indexOf(PREFIX + "dated");
        int undatedIndex = json.indexOf(PREFIX + "undated");
        assertTrue(datedIndex >= 0 && undatedIndex >= 0,
            "both posts are inside the 4h ready_at window and must be returned; got: " + json);
        assertTrue(datedIndex < undatedIndex,
            "an undated post must sort by the instant it was FETCHED — the same ceiling the "
                + "ingest clamp gives a dated post — not by its later promotion instant. "
                + "Ordering it by ready_at would let omitting a date outrank every dated post "
                + "in the window, and would let a quarantine-approve re-stamp move it; got: " + json);
    }

    @Test
    void resultOrderingBindsToPublishedAtNotReadyAt() throws Exception {
        UUID userId = seedUser("order-bind");
        UUID sourceId = seedSource("order-bind-src", "Order-bind source");
        seedSubscription("dm", userId, sourceId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // Two posts whose published_at and ready_at order them oppositely.
        // newerPublished has the more recent published_at but the older
        // ready_at; olderPublished is the reverse. published_at DESC ordering
        // puts newerPublished first; a ready_at DESC ordering would flip them.
        seedReadyPost("newer-published", sourceId,
                now.minus(1, ChronoUnit.HOURS), now.minus(3, ChronoUnit.HOURS));
        seedReadyPost("older-published", sourceId,
                now.minus(2, ChronoUnit.HOURS), now.minus(30, ChronoUnit.MINUTES));

        String json = tool.execute(userId, "dm", userId, Map.of("window", "PT4H"));

        int newerIndex = json.indexOf(PREFIX + "newer-published");
        int olderIndex = json.indexOf(PREFIX + "older-published");
        assertTrue(newerIndex >= 0 && olderIndex >= 0,
            "both posts must be within the 4h window; got: " + json);
        assertTrue(newerIndex < olderIndex,
            "results must be ordered by published_at descending (newer published_at "
                + "first), not by ready_at; got: " + json);
    }

    @Test
    void requestedTagOutsideFollowedSetStillReturnsHits() throws Exception {
        UUID userId = seedUser("explicit-miss");
        UUID sourceId = seedSource("explicit-miss-src", "Explicit-miss source");
        seedSubscription("dm", userId, sourceId);
        // Scope follows alpha only; beta is a known tag the scope does NOT
        // follow. Under D59 the scope's /follow-tag preferences narrow the
        // DIGEST only — chat search stays broad (M1-621) — so a
        // beta-tagged, in-world, in-window post MUST surface for an
        // explicit beta request. (Pre-D59, the EXPLICIT-mode intersection
        // made this empty.)
        UUID alpha = seedTag(TAG_PREFIX + "alpha");
        seedTag(TAG_PREFIX + "beta");
        followTag("dm", userId, alpha);
        setExplicitMode("dm", userId);
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(1, ChronoUnit.HOURS);
        seedReadyPostWithTags("beta-post", sourceId, publishedAt,
                publishedAt.plus(5, ChronoUnit.MINUTES), TAG_PREFIX + "beta");

        String json = tool.execute(userId, "dm", userId,
                Map.of("tags", List.of(TAG_PREFIX + "beta")));

        assertTrue(json.contains(PREFIX + "beta-post"),
            "chat search is decoupled from /follow-tag: requesting a known tag outside "
                + "the followed set still returns its posts; got: " + json);
    }

    @Test
    void explicitModeRequestingFollowedTagReturnsOnlyMatchingPosts() throws Exception {
        UUID userId = seedUser("explicit-hit");
        UUID sourceId = seedSource("explicit-hit-src", "Explicit-hit source");
        seedSubscription("dm", userId, sourceId);
        UUID alpha = seedTag(TAG_PREFIX + "alpha");
        seedTag(TAG_PREFIX + "beta");
        followTag("dm", userId, alpha);
        setExplicitMode("dm", userId);
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(1, ChronoUnit.HOURS);
        seedReadyPostWithTags("alpha-post", sourceId, publishedAt,
                publishedAt.plus(5, ChronoUnit.MINUTES), TAG_PREFIX + "alpha");
        seedReadyPostWithTags("beta-post", sourceId, publishedAt,
                publishedAt.plus(5, ChronoUnit.MINUTES), TAG_PREFIX + "beta");

        String json = tool.execute(userId, "dm", userId,
                Map.of("tags", List.of(TAG_PREFIX + "alpha")));

        assertTrue(json.contains(PREFIX + "alpha-post"),
            "EXPLICIT mode requesting a followed tag must return matching posts; got: " + json);
        assertFalse(json.contains(PREFIX + "beta-post"),
            "EXPLICIT mode requesting a followed tag must exclude non-matching posts; got: " + json);
    }

    @Test
    void allModeNoTagsReturnsFullSubscribedFeed() throws Exception {
        UUID userId = seedUser("all-mode");
        UUID sourceId = seedSource("all-mode-src", "All-mode source");
        seedSubscription("dm", userId, sourceId);
        // No scope_preferences row → tag_mode defaults to ALL; no requested
        // tags → the unconstrained subscribed feed (the path that must stay
        // unchanged). Posts carry distinct tags to prove no tag filter applies.
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(1, ChronoUnit.HOURS);
        seedReadyPostWithTags("alpha-post", sourceId, publishedAt,
                publishedAt.plus(5, ChronoUnit.MINUTES), TAG_PREFIX + "alpha");
        seedReadyPostWithTags("beta-post", sourceId, publishedAt,
                publishedAt.plus(5, ChronoUnit.MINUTES), TAG_PREFIX + "beta");

        String json = tool.execute(userId, "dm", userId, Map.of());

        assertTrue(json.contains(PREFIX + "alpha-post") && json.contains(PREFIX + "beta-post"),
            "ALL mode with no tags must return the full subscribed feed regardless of tags; "
                + "got: " + json);
    }

    @Test
    void searchStaysBroadWhileSummaryNarrowsForSameExplicitScopeState() throws Exception {
        UUID userId = seedUser("parity");
        UUID sourceId = seedSource("parity-src", "Parity source");
        seedSubscription("dm", userId, sourceId);
        UUID alpha = seedTag(TAG_PREFIX + "alpha");
        seedTag(TAG_PREFIX + "beta");
        followTag("dm", userId, alpha);
        setExplicitMode("dm", userId);
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(1, ChronoUnit.HOURS);
        seedReadyPostWithTags("alpha-post", sourceId, publishedAt,
                publishedAt.plus(5, ChronoUnit.MINUTES), TAG_PREFIX + "alpha");
        seedReadyPostWithTags("beta-post", sourceId, publishedAt,
                publishedAt.plus(5, ChronoUnit.MINUTES), TAG_PREFIX + "beta");

        // Intentional divergence (D59, M1-621): searchPosts ignores the
        // scope's followed set — chat/RAG searches the scope's whole world —
        // while EligiblePostQuery, the /summary digest sibling, KEEPS the
        // follow-tag narrowing. One scope state, two deliberate contracts
        // (this replaced the pre-D59 parity assertion).
        String json = tool.execute(userId, "dm", userId,
                Map.of("window", "PT24H"));
        Set<String> searchUids = uidsFromJson(json);
        assertEquals(Set.of(PREFIX + "alpha-post", PREFIX + "beta-post"), searchUids,
            "EXPLICIT scope with a narrow followed set: searchPosts still returns hits "
                + "outside those tags; got: " + searchUids);

        EligiblePostQuery.Result summaryResult = eligiblePostQuery.fetch(
                "dm", userId, Optional.empty(), Duration.ofHours(24));
        Set<String> summaryUids = summaryResult.posts().stream()
                .map(EligiblePostQuery.Post::uid)
                .collect(Collectors.toSet());
        assertEquals(Set.of(PREFIX + "alpha-post"), summaryUids,
            "the digest (EligiblePostQuery) still narrows to the followed set; got: "
                + summaryUids);
    }

    @Test
    void tagValidationIssuesOneSelectForAllTagsAndRejectsUnknown() throws Exception {
        UUID userId = seedUser("tag-batch");
        UUID sourceId = seedSource("tag-batch-src", "Tag-batch source");
        seedSubscription("dm", userId, sourceId);
        seedTag(TAG_PREFIX + "alpha");
        seedTag(TAG_PREFIX + "beta");
        seedTag(TAG_PREFIX + "gamma");

        // Count the tag-existence SELECTs across one execute() with three
        // requested tags: the batched validation must issue exactly one,
        // not one per tag.
        CountingRecordingDataSource countingDs = new CountingRecordingDataSource(dataSource);
        SearchPostsTool directTool = new SearchPostsTool(countingDs, cancellationService);
        InFlightTracker.CancellationHandle slot =
                Objects.requireNonNull(inFlightTracker.tryAcquire(userId, "dm", userId));
        try {
            directTool.execute(userId, "dm", userId, Map.of("tags",
                    List.of(TAG_PREFIX + "alpha", TAG_PREFIX + "beta", TAG_PREFIX + "gamma")));

            long tagSelects = countingDs.executedSql().stream()
                    .filter(s -> s.contains("FROM tag WHERE name"))
                    .count();
            assertEquals(1, tagSelects,
                    "validating N tags must issue exactly one tag-existence SELECT, not one "
                            + "per tag. Got: " + countingDs.executedSql());
        } finally {
            inFlightTracker.release(userId, "dm", userId, slot);
        }

        // An unknown tag anywhere in the batch still rejects the whole call,
        // naming the offending tag (request order).
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> tool.execute(userId, "dm", userId, Map.of("tags",
                        List.of(TAG_PREFIX + "alpha", TAG_PREFIX + "nonexistent"))));
        assertTrue(ex.getMessage().contains(TAG_PREFIX + "nonexistent"),
                "the unknown tag must be named in the rejection; got: " + ex.getMessage());
    }

    @Test
    void aggregateOutputIsBoundedByByteCap() throws Exception {
        UUID userId = seedUser("bytecap");
        UUID sourceId = seedSource("bytecap-src", "Byte-cap source");
        seedSubscription("dm", userId, sourceId);
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(1, ChronoUnit.HOURS);
        // A ~1500-char title per post over enough posts that the raw JSON
        // would far exceed MAX_RESULT_BYTES (16 KiB) if unbounded. Tool
        // outputs are reinjected verbatim into the chat prompt, so the
        // aggregate must be byte-bounded.
        String bigTitle = "T".repeat(1500);
        int seeded = 40;
        for (int i = 0; i < seeded; i++) {
            seedReadyPostWithTitle("bytecap-" + i, sourceId,
                    publishedAt.plusSeconds(i), publishedAt.plusSeconds(i), bigTitle);
        }

        String json = tool.execute(userId, "dm", userId, Map.of());

        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= SearchPostsTool.MAX_RESULT_BYTES,
                "the aggregate tool output must not exceed MAX_RESULT_BYTES; got " + bytes
                        + " bytes");
        int returned = uidsFromJson(json).size();
        assertTrue(returned < seeded,
                "the byte cap must drop entries past the budget (returned " + returned
                        + " of " + seeded + " seeded posts)");
        assertTrue(returned > 0, "at least one entry must fit under the budget; got " + returned);
    }

    // ---------- helpers ----------

    private UUID seedUser(String suffix) throws Exception {
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

    private UUID seedSource(String suffix, String displayName) throws Exception {
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

    private void seedSubscription(String scopeKind, UUID scopeId, UUID sourceId) throws Exception {
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

    private void seedReadyPost(String slug, UUID sourceId,
                               Instant publishedAt, Instant readyAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags, upstream_identifier) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, '{}', ?)")) {
            ps.setString(1, PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "Title " + slug);
            ps.setString(4, "Body " + slug);
            ps.setString(5, "https://example.com/" + slug);
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(8, Timestamp.from(readyAt));
            ps.setString(9, PREFIX + slug);
            ps.executeUpdate();
        }
    }

    /** Seeds with an explicit {@code fetched_at} so ordering tests can separate fetch from promotion. */
    private void seedReadyPostAt(String slug, UUID sourceId, @Nullable Instant publishedAt,
                                 Instant readyAt, Instant fetchedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags, upstream_identifier) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, '{}', ?)")) {
            ps.setString(1, PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "Title " + slug);
            ps.setString(4, "Body " + slug);
            ps.setString(5, "https://example.com/" + slug);
            ps.setTimestamp(6, publishedAt == null ? null : Timestamp.from(publishedAt));
            ps.setTimestamp(7, Timestamp.from(fetchedAt));
            ps.setTimestamp(8, Timestamp.from(readyAt));
            ps.setString(9, PREFIX + slug);
            ps.executeUpdate();
        }
    }

    private void seedReadyPostWithTags(String slug, UUID sourceId, Instant publishedAt,
                                       Instant readyAt, String... tags) throws Exception {
        String tagLiteral = "{" + String.join(",", tags) + "}";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags, upstream_identifier) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?::TEXT[], ?)")) {
            ps.setString(1, PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "Title " + slug);
            ps.setString(4, "Body " + slug);
            ps.setString(5, "https://example.com/" + slug);
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(8, Timestamp.from(readyAt));
            ps.setString(9, tagLiteral);
            ps.setString(10, PREFIX + slug);
            ps.executeUpdate();
        }
    }

    private void seedReadyPostWithTitle(String slug, UUID sourceId, Instant publishedAt,
                                        Instant readyAt, String title) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags, upstream_identifier) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, '{}', ?)")) {
            ps.setString(1, PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body " + slug);
            ps.setString(5, "https://example.com/" + slug);
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(8, Timestamp.from(readyAt));
            ps.setString(9, PREFIX + slug);
            ps.executeUpdate();
        }
    }

    private UUID seedTag(String name) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO tag (name, display) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, name);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void followTag(String scopeKind, UUID scopeId, UUID tagId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, tagId);
            ps.executeUpdate();
        }
    }

    private void setExplicitMode(String scopeKind, UUID scopeId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode) "
                     + "VALUES (?, ?, 'EXPLICIT')")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.executeUpdate();
        }
    }

    /** Extract the {@code uid} values from a searchPosts result JSON array. */
    private static Set<String> uidsFromJson(String json) {
        Set<String> uids = new HashSet<>();
        Matcher m = Pattern.compile("\"uid\":\"([^\"]*)\"").matcher(json);
        while (m.find()) {
            uids.add(m.group(1));
        }
        return uids;
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

}
