package app.zcat.infochat.collector.eval.stage1;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the {@link Stage1Pipeline} match-count cap
 * fail-closed path. Feeds a body that produces more regex hits than
 * the configured {@code infochat.security.stage1.max-matches} cap;
 * the accumulated match list trips the cap inside
 * {@code findAllMatchesUnderWatchdog}; the post is sealed at
 * {@code status='QUARANTINED'} with a whole-body
 * {@code rule_id='match_overflow'} quarantine row, per
 * {@code docs/spec/security.md} §Failure handling ("Stage 1
 * infrastructure failure → fail-closed: the post is immediately
 * QUARANTINED and never auto-released").
 *
 * <h2>Deterministic, fast trip via {@link TinyMaxMatchesProfile}</h2>
 * <p>Unlike the wall-clock watchdog (which depends on JIT state and
 * is inherently a sanity-band, see {@link Stage1WatchdogIT}), the
 * match-count cap is a deterministic count bound. A tiny cap
 * ({@link #TEST_CAP}) plus a body containing strictly more
 * impersonation-prefix lines than the cap trips it on a small input
 * with no backtracking — so the body never approaches the 100ms
 * watchdog window, isolating the overflow path from the timeout path.
 *
 * <h2>Why the impersonation-prefix line shape</h2>
 * <p>The {@code (?m)^\s*(?:system|assistant|user)\s*[:>\]]} pattern
 * (rule 3) matches once per {@code system:} line and nothing else in
 * this body, so the accumulated match count equals the line count
 * exactly — a deterministic way to push the list past a small cap
 * without relying on backtracking or overlap behavior.
 */
@QuarkusTest
@TestProfile(Stage1MatchOverflowIT.TinyMaxMatchesProfile.class)
class Stage1MatchOverflowIT {

    /**
     * Match-count cap the {@link TinyMaxMatchesProfile} overrides to;
     * documented so the body sizing is local to the test rather than
     * implicitly tied to the profile.
     */
    private static final int TEST_CAP = 3;

    /** Impersonation-prefix lines fed to the body — strictly above {@link #TEST_CAP}. */
    private static final int LINE_COUNT = 6;

    /**
     * Quarkus {@link QuarkusTestProfile} that overrides the Stage 1
     * match-count cap to {@link #TEST_CAP} (far below the base
     * profile default). Triggers a fresh Quarkus container for this
     * IT; the base ITs in this module keep their default cap.
     */
    public static class TinyMaxMatchesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("infochat.security.stage1.max-matches", String.valueOf(TEST_CAP));
        }
    }

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    Stage1Pipeline stage1Pipeline;

    @Test
    void matchOverflowSealsPostAtQuarantinedAndSkipsRedactPath() throws Exception {
        // LINE_COUNT impersonation-prefix lines → rule 3 matches once
        // per line → the accumulated match list exceeds TEST_CAP and
        // trips the fail-closed overflow path. Pure ASCII so NFKC +
        // strip is a no-op and the normalized body equals the input.
        StringBuilder bodyBuilder = new StringBuilder();
        for (int i = 0; i < LINE_COUNT; i++) {
            bodyBuilder.append("system:\n");
        }
        String body = bodyBuilder.toString();

        UUID sourceUuid = seedRssSource(
            "https://stage1-match-overflow-it.example.test/feed.xml",
            "Stage1 match-overflow IT source");
        SeededPost post = seedPost(sourceUuid, body);

        stage1Pipeline.process(post.id(), post.uid(), post.fetchedAt(), post.body());

        // (a) The post is sealed at QUARANTINED with a single
        // whole-body placeholder — never the normal redact/accept path.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status, stage1_done, body FROM post WHERE id = ?")) {
            ps.setObject(1, post.id());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "overflow post row must exist");
                assertEquals("QUARANTINED", rs.getString("status"),
                    "match-overflow must seal post at status='QUARANTINED', "
                        + "not the normal RAW redact/accept path");
                assertTrue(rs.getBoolean("stage1_done"),
                    "overflow path must still set stage1_done=true so the "
                        + "rehydrator does not re-enqueue ad infinitum");
                String postBody = rs.getString("body");
                assertTrue(postBody.startsWith("[REDACTED:") && postBody.endsWith("]"),
                    "match-overflow must rewrite post.body to the single "
                        + "whole-body placeholder; got: " + postBody);
            }
        }

        // (b) Exactly one quarantine row, carrying the overflow
        // rule_id and spanning the whole normalized body — proving no
        // per-match redact rows were written (the normal accept path).
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT rule_id, span_start, span_end, original_html, "
                     + "       flagged_by, status "
                     + "FROM quarantine WHERE post_id = ?")) {
            ps.setObject(1, post.id());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "match-overflow must INSERT one quarantine row");
                assertEquals(Stage1Pipeline.MATCH_OVERFLOW_RULE_ID, rs.getString("rule_id"),
                    "overflow quarantine row must carry rule_id='match_overflow'");
                assertEquals(0, rs.getInt("span_start"),
                    "overflow row's span_start must be 0 (whole-body span)");
                String originalHtml = rs.getString("original_html");
                // Pure ASCII body: NFKC + strip is a no-op, so the
                // normalized body length equals the input length.
                assertEquals(body.length(), rs.getInt("span_end"),
                    "overflow row's span_end must equal the normalized body length");
                assertEquals(body.length(), originalHtml.length(),
                    "overflow row's original_html must hold the entire normalized body");
                assertEquals("stage1", rs.getString("flagged_by"));
                assertEquals("PENDING", rs.getString("status"));
                assertFalse(rs.next(),
                    "match-overflow must INSERT exactly one whole-body "
                        + "quarantine row — never per-match redact rows");
            }
        }
    }

    private UUID seedRssSource(String identifier, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{}') "
                     + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private SeededPost seedPost(UUID sourceUuid, String body) throws Exception {
        Instant fetchedAt = Instant.parse("2026-05-15T14:00:00Z");
        String uid = "stage1-match-overflow-it-uid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status, "
                     + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'RAW',"
                     + "  FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, '{}'"
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceUuid);
            ps.setString(3, "stage1-match-overflow-it-upstream");
            ps.setString(4, "Stage1 match-overflow IT post");
            ps.setString(5, body);
            ps.setTimestamp(6, Timestamp.from(fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                UUID postId = (UUID) rs.getObject(1);
                Instant returnedFetchedAt = rs.getTimestamp(2).toInstant();
                return new SeededPost(postId, uid, returnedFetchedAt, body);
            }
        }
    }

    private record SeededPost(UUID id, String uid, Instant fetchedAt, String body) {
    }
}
