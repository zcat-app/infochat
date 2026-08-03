package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link SaveCommandHandler} against the
 * DevServices Postgres container (V5 users, V6 source, V7 post, V15
 * saved_post). One {@code @Test} per acceptance scenario in M1-052
 * acceptance item 3.
 *
 * <p>Test isolation: per-test sub-prefix within the class-wide
 * {@code PREFIX} ({@code m1-052-save-}); the {@link #cleanup()}
 * {@code @BeforeEach} deletes rows under the class-wide prefix. The
 * test profile sets {@code infochat.save.cap=3} so cap-saturation
 * scenarios run cheaply.</p>
 *
 * @implNote Shape B (Thin-SQL) per
 *     {@code docs/process/test-pyramid.md} §Handler unit tests —
 *     the handler's behavior IS the lock-protected DB interaction
 *     (≥2 real-DB-dependent statements: {@code SELECT ... FOR UPDATE}
 *     on users for the atomic cap; {@code INSERT} against the
 *     {@code (user_id, post_uid)} PK; trigger-driven save_count).
 */
@QuarkusTest
class SaveCommandHandlerTest {

    private static final String PREFIX = "m1-052-save-";
    private static final String ADAPTER = "inmemory";

    @Inject SaveCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @Inject
    @ConfigProperty(name = "infochat.save.cap")
    int saveCap;

    @Inject
    @ConfigProperty(name = "infochat.save.personal-tag-max-length", defaultValue = "64")
    int personalTagMaxLength;

    @Inject
    @ConfigProperty(name = "infochat.save.personal-tag-max-count", defaultValue = "20")
    int personalTagMaxCount;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // saved_post rows for prefix-matched users (the AFTER-DELETE
            // trigger decrements users.save_count; the subsequent
            // DELETE FROM users removes those rows entirely).
            exec(conn,
                    "DELETE FROM saved_post WHERE user_id IN ("
                            + "SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            // post rows under the prefix's sources.
            exec(conn,
                    "DELETE FROM post WHERE source_id IN ("
                            + "SELECT id FROM source WHERE identifier LIKE ?)",
                    PREFIX + "%");
            // subscription + exclusion rows must go before their FK target source.
            exec(conn,
                    "DELETE FROM source_subscription WHERE source_id IN ("
                            + "SELECT id FROM source WHERE identifier LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM source_exclusion WHERE source_id IN ("
                            + "SELECT id FROM source WHERE identifier LIKE ?)",
                    PREFIX + "%");
            // source rows under the prefix.
            exec(conn,
                    "DELETE FROM source WHERE identifier LIKE ?",
                    PREFIX + "%");
            // membership rows must go before their FK targets groups/users.
            exec(conn,
                    "DELETE FROM group_membership WHERE group_id IN ("
                            + "SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM groups WHERE upstream_group_id LIKE ?",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM users WHERE contact_id LIKE ?",
                    PREFIX + "%");
        }
    }

    @Test
    void saveHappyPathReturnsSuccessAndWritesSnapshotRow() throws Exception {
        String contactId = PREFIX + "happy-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "happy-source", new String[] { "news", "tech" });
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "happy-uid";
        seedPost(sourceId, uid, "READY", "Title H", "Body H", "https://example.com/h", "Alice", Instant.parse("2026-05-01T00:00:00Z"));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                reply.text(),
                "/save success reply must interpolate the UID");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT post_uid FROM saved_post WHERE post_uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "saved_post row must exist after /save");
            }
        }
        assertEquals(1, readSaveCount(contactId), "users.save_count must increment to 1 after /save");
    }

    @Test
    void saveAgainstQuarantinedPostReturnsUnknownUid() throws Exception {
        String contactId = PREFIX + "qrnt-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "qrnt-source", new String[] {});
        // Subscribed on purpose: the status leg must be the only
        // reason this /save rejects, not the visibility filter.
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "qrnt-uid";
        seedPost(sourceId, uid, "QUARANTINED", "Title Q", "Body Q", null, null, null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID), reply.text(),
                "QUARANTINED post must surface error.save.unknown_uid (visibility-of-target)");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written for a QUARANTINED post");
    }

    @Test
    void saveAgainstNeedsReviewPostReturnsUnknownUid() throws Exception {
        String contactId = PREFIX + "need-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "need-source", new String[] {});
        // Subscribed on purpose: the status leg must be the only
        // reason this /save rejects, not the visibility filter.
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "need-uid";
        seedPost(sourceId, uid, "NEEDS_REVIEW", "Title N", "Body N", null, null, null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID), reply.text(),
                "NEEDS_REVIEW post must surface error.save.unknown_uid (visibility-of-target)");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written for a NEEDS_REVIEW post");
    }

    @Test
    void saveAgainstUnknownUidReturnsUnknownUid() throws Exception {
        String contactId = PREFIX + "unkn-actor";
        seedUser(contactId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(contactId), "/save " + PREFIX + "no-such-uid");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID), reply.text(),
                "an unknown UID must surface error.save.unknown_uid");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written for an unknown UID");
    }

    @Test
    void saveAgainstAlreadySavedPostReturnsAlreadySaved() throws Exception {
        String contactId = PREFIX + "dup-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "dup-source", new String[] {});
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "dup-uid";
        seedPost(sourceId, uid, "READY", "Title D", "Body D", null, null, null);

        OutboundMessage first = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);
        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                first.text(), "first /save must succeed");

        OutboundMessage second = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_ALREADY_SAVED), second.text(),
                "duplicate /save must surface error.save.already_saved");
        assertEquals(1L, countSavedPosts(contactId),
                "only one saved_post row may exist for the duplicate /save case");
        assertEquals(1, readSaveCount(contactId),
                "users.save_count must remain at 1 after the duplicate /save");
    }

    @Test
    void saveAtCapReturnsCapMetAndWritesNoRow() throws Exception {
        String contactId = PREFIX + "cap-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "cap-source", new String[] {});
        seedDmSubscription(userId, sourceId);
        // Saturate to the cap with cap distinct UIDs.
        for (int i = 0; i < saveCap; i++) {
            String uid = PREFIX + "cap-uid-" + i;
            seedPost(sourceId, uid, "READY", "T" + i, "B" + i, null, null, null);
            OutboundMessage r = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);
            assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                    r.text(), "seed save must succeed at index " + i);
        }
        assertEquals(saveCap, readSaveCount(contactId),
                "users.save_count must equal cap after saturation");

        // One more /save against a new READY post must surface cap_met
        // and write no row.
        String overflowUid = PREFIX + "cap-overflow";
        seedPost(sourceId, overflowUid, "READY", "TO", "BO", null, null, null);
        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + overflowUid);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_CAP_MET), reply.text(),
                "at-cap /save must surface error.save.cap_met");
        assertEquals(saveCap, readSaveCount(contactId),
                "users.save_count must remain at cap after the cap-met reject");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM saved_post WHERE post_uid = ?")) {
            ps.setString(1, overflowUid);
            try (ResultSet rs = ps.executeQuery()) {
                assertFalse(rs.next(),
                        "no saved_post row may exist for the cap-met UID");
            }
        }
    }

    @Test
    void saveWithPersonalTagsPopulatesPersonalTagsColumn() throws Exception {
        String contactId = PREFIX + "ptag-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "ptag-source", new String[] { "news" });
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "ptag-uid";
        seedPost(sourceId, uid, "READY", "Title P", "Body P", null, null, null);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(contactId), "/save " + uid + " -t read-later,interesting");

        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                reply.text(), "/save with -t must succeed");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT personal_tags FROM saved_post WHERE post_uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "saved_post row must exist");
                Array arr = rs.getArray("personal_tags");
                assertArrayEquals(new String[] { "read-later", "interesting" },
                        (String[]) arr.getArray(),
                        "personal_tags must carry the comma-split -t values verbatim");
            }
        }
    }

    @Test
    void saveWithOverLengthPersonalTagIsRejectedAndWritesNoRow() throws Exception {
        String contactId = PREFIX + "longtag-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "longtag-source", new String[] {});
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "longtag-uid";
        // A valid READY, visible post — the only reason nothing is stored
        // must be the over-length tag, not a missing/non-READY/invisible
        // target.
        seedPost(sourceId, uid, "READY", "Title L", "Body L", null, null, null);

        String overLengthTag = "x".repeat(personalTagMaxLength + 1);
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(contactId), "/save " + uid + " -t " + overLengthTag);

        assertEquals(MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_SAVE_TAG_TOO_LONG), personalTagMaxLength),
                reply.text(),
                "an over-length personal tag must surface error.save.tag_too_long");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written when a personal tag exceeds the length cap");
    }

    @Test
    void saveWithOverCountPersonalTagListIsRejectedAndWritesNoRow() throws Exception {
        String contactId = PREFIX + "manytags-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "manytags-source", new String[] {});
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "manytags-uid";
        seedPost(sourceId, uid, "READY", "Title M", "Body M", null, null, null);

        // One tag past the per-call count cap; each tag is short so only
        // the count cap (checked first) can trip.
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i <= personalTagMaxCount; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append('t').append(i);
        }
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(contactId), "/save " + uid + " -t " + csv);

        assertEquals(MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_SAVE_TOO_MANY_TAGS), personalTagMaxCount),
                reply.text(),
                "an over-count personal-tag list must surface error.save.too_many_tags");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written when the tag list exceeds the count cap");
    }

    @Test
    void saveWithSlashBearingPersonalTagIsRejectedAndWritesNoRow() throws Exception {
        String contactId = PREFIX + "slashtag-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "slashtag-source", new String[] {});
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "slashtag-uid";
        // A valid READY, visible post and a short tag well inside both caps:
        // the ONLY reason nothing may be stored is the slash.
        seedPost(sourceId, uid, "READY", "Title S", "Body S", null, null, null);

        // The M1-675 attack verbatim. Personal tags are echoed into the
        // group-visible /saved reply, so storing this would make the bot
        // broadcast a syntactically valid /grant-admin line to every member.
        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId),
                "/save " + uid + " -t \"/grant-admin 11111111-2222-3333-4444-555555555555\"");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_TAG_INVALID), reply.text(),
                "a slash-bearing personal tag must surface error.save.tag_invalid");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written when a personal tag contains a slash");
    }

    @Test
    void saveWithSlashBearingTagRejectsTheWholeCallNotJustThatTag() throws Exception {
        String contactId = PREFIX + "slashwhole-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "slashwhole-source", new String[] {});
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "slashwhole-uid";
        seedPost(sourceId, uid, "READY", "Title W", "Body W", null, null, null);

        // A benign tag FIRST, the payload second. Rejecting only the offending
        // tag and saving the rest would still leave the caller a stored row;
        // the rule is that one bad tag fails the whole /save.
        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId),
                "/save " + uid + " -t read-later,/ban 11111111-2222-3333-4444-555555555555");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_TAG_INVALID), reply.text(),
                "one slash-bearing tag must reject the whole /save");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written when any personal tag contains a slash");
    }

    @Test
    void saveWithFullwidthSolidusPersonalTagIsRejectedWithoutRouterNormalization() throws Exception {
        String contactId = PREFIX + "fwsolidus-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "fwsolidus-source", new String[] {});
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "fwsolidus-uid";
        seedPost(sourceId, uid, "READY", "Title F", "Body F", null, null, null);

        // U+FF0F FULLWIDTH SOLIDUS. The handler is invoked DIRECTLY here, with
        // no InboundRouter in the path, which is the point: the router's
        // normalization pass exempts fenced code blocks while routing reads
        // only line 1, so a /save on line 1 can carry an un-normalized payload
        // on line 3. The rejection must therefore be self-sufficient — the
        // handler NFKC-folds the tag itself, turning U+FF0F into '/' before
        // the gate sees it (M1-659).
        String fullwidthSlashTag = "／grant-admin";
        assertFalse(fullwidthSlashTag.contains("/"),
                "fixture must carry the un-folded fullwidth solidus, not a plain ASCII slash — "
                        + "otherwise this test would not exercise the handler's own NFKC pass");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(contactId), "/save " + uid + " -t " + fullwidthSlashTag);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_TAG_INVALID), reply.text(),
                "a tag whose slash is U+FF0F must be rejected after the handler's own NFKC fold");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written for a compatibility-folded slash tag");
    }

    @Test
    void saveWithOverLengthSlashBearingTagStillReportsTheLengthError() throws Exception {
        String contactId = PREFIX + "longslash-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "longslash-source", new String[] {});
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "longslash-uid";
        seedPost(sourceId, uid, "READY", "Title LS", "Body LS", null, null, null);

        // A tag that trips BOTH caps. The slash gate runs after the size caps
        // precisely so the pre-existing errors keep their behavior; this pins
        // that ordering, which is otherwise invisible and easy to invert.
        String tag = "/" + "x".repeat(personalTagMaxLength);
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(contactId), "/save " + uid + " -t " + tag);

        assertEquals(MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_SAVE_TAG_TOO_LONG), personalTagMaxLength),
                reply.text(),
                "the length cap runs before the slash gate, so an over-long slash tag "
                        + "must keep reporting error.save.tag_too_long");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written for an over-length slash-bearing tag");
    }

    @Test
    void saveSnapshotsBodyTitleUrlAuthorPublishedAtAndSourceId() throws Exception {
        String contactId = PREFIX + "snap-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "snap-source", new String[] { "news", "tech" });
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "snap-uid";
        Instant publishedAt = Instant.parse("2026-04-15T12:34:56Z");
        seedPost(sourceId, uid, "READY", "Snapshot Title", "Snapshot Body",
                "https://example.com/snap", "Bob Author", publishedAt);

        handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT title, body, url, author, published_at, source_id, snapshot_tags "
                             + "FROM saved_post WHERE post_uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "saved_post row must exist");
                assertEquals("Snapshot Title", rs.getString("title"));
                assertEquals("Snapshot Body", rs.getString("body"));
                assertEquals("https://example.com/snap", rs.getString("url"));
                assertEquals("Bob Author", rs.getString("author"));
                Timestamp ts = rs.getTimestamp("published_at");
                assertNotNull(ts, "published_at must be snapshotted");
                assertEquals(publishedAt, ts.toInstant(),
                        "published_at snapshot must match the source post");
                assertEquals(sourceId, rs.getObject("source_id"),
                        "source_id snapshot must match the source post's source_id");
                Array snapArr = rs.getArray("snapshot_tags");
                assertEquals(List.of("news", "tech"),
                        Arrays.asList((String[]) snapArr.getArray()),
                        "snapshot_tags must carry the source's bootstrap_tags at /save time");
            }
        }
    }

    @Test
    void savePersistsTheSourcesDeclaredLanguageIntoTheSnapshot() throws Exception {
        // M1-755: the save-time SELECT projects source.language and the
        // INSERT writes it, so the snapshot's source_language is frozen
        // with the rest of the row — a later /add-source --lang edit never
        // retro-applies. The probe reads the INSERT argument back from the
        // stored row, so deleting the column from the save path is a test
        // failure.
        String contactId = PREFIX + "lang-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "lang-source", new String[] { "news" }, "cs");
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "lang-uid";
        seedPost(sourceId, uid, "READY", "Title L", "Body L", null, null, null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                reply.text(),
                "/save must succeed for a source declaring a non-en language");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT source_language FROM saved_post WHERE post_uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "saved_post row must exist");
                assertEquals("cs", rs.getString("source_language"),
                        "the declared source language must be snapshotted with the row");
            }
        }
    }

    @Test
    void save_succeedsInGroupScope() throws Exception {
        String contactId = PREFIX + "group-actor";
        inboundContext.setSenderContactId(contactId);
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "group-source", new String[] { "news" });
        // The visibility filter is caller-based, not calling-scope-based:
        // a DM subscription makes the post saveable from group scope too.
        seedDmSubscription(userId, sourceId);
        String uid = PREFIX + "group-uid";
        seedPost(sourceId, uid, "READY", "Title G", "Body G", "https://example.com/g", null, null);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("adapter-group-id"), "/save " + uid);

        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                reply.text(),
                "/save in group scope must succeed for any active group member");
        assertEquals(1, readSaveCount(contactId),
                "users.save_count must increment after group-scope /save");
    }

    // ----- any-caller-scope visibility filter (spec §Content) -------------

    @Test
    void saveVisibleOnlyViaDmSubscriptionSucceeds() throws Exception {
        String contactId = PREFIX + "visdm-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "visdm-source", new String[] {});
        seedDmSubscription(userId, sourceId);
        // The actor is also a member of an approved group WITHOUT the
        // subscription — the DM leg alone must admit.
        UUID groupId = seedGroup(PREFIX + "visdm-group", "approved");
        seedGroupMembership(groupId, userId, false);
        String uid = PREFIX + "visdm-uid";
        seedPost(sourceId, uid, "READY", "Title V", "Body V", null, null, null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                reply.text(),
                "/save must admit a post visible via the caller's DM subscription");
    }

    @Test
    void saveVisibleOnlyViaApprovedGroupMembershipSucceeds() throws Exception {
        String contactId = PREFIX + "visgrp-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "visgrp-source", new String[] {});
        // No DM subscription — visibility flows only through the
        // approved group's subscription + the caller's active membership.
        UUID groupId = seedGroup(PREFIX + "visgrp-group", "approved");
        seedGroupMembership(groupId, userId, false);
        seedGroupSubscription(groupId, sourceId);
        String uid = PREFIX + "visgrp-uid";
        seedPost(sourceId, uid, "READY", "Title G", "Body G", null, null, null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                reply.text(),
                "/save from DM must admit a post visible via an approved-group membership");
    }

    @Test
    void saveOfReadyPostInvisibleInAllCallerScopesReturnsUnknownUid() throws Exception {
        String contactId = PREFIX + "invis-actor";
        seedUser(contactId);
        // READY post, but no subscription in any of the caller's scopes.
        UUID sourceId = seedSource(PREFIX + "invis-source", new String[] {});
        String uid = PREFIX + "invis-uid";
        seedPost(sourceId, uid, "READY", "Title I", "Body I", null, null, null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID), reply.text(),
                "a READY post invisible in every caller scope must surface "
                        + "error.save.unknown_uid (existence-vs-no-access never exposed)");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written for an invisible post");
    }

    @Test
    void saveOfBootstrapPostSucceedsForSubscriptionlessCaller() throws Exception {
        // Acceptance test (c), /save half (M1-621): a bootstrap-origin post
        // is in every scope's world, so a caller with zero subscriptions and
        // no groups can bookmark it — no search-visible-but-unsavable state.
        String contactId = PREFIX + "bootsave-actor";
        seedUser(contactId);
        UUID sourceId = seedBootstrapSource(PREFIX + "bootsave-source");
        String uid = PREFIX + "bootsave-uid";
        seedPost(sourceId, uid, "READY", "Title B", "Body B", null, null, null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                reply.text(),
                "/save must admit a bootstrap post for a subscription-less caller");
    }

    @Test
    void saveOfDmExcludedBootstrapPostReturnsUnknownUid() throws Exception {
        // The caller's DM exclusion removes the bootstrap source from their
        // world; with no group leg, /save falls into the same empty-result
        // path as an unknown UID (existence-vs-no-access never exposed).
        String contactId = PREFIX + "bootexcl-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedBootstrapSource(PREFIX + "bootexcl-source");
        seedDmExclusion(userId, sourceId);
        String uid = PREFIX + "bootexcl-uid";
        seedPost(sourceId, uid, "READY", "Title E", "Body E", null, null, null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID), reply.text(),
                "a DM-excluded bootstrap post must surface error.save.unknown_uid");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written for an excluded post");
    }

    @Test
    void saveViaDepartedGroupMembershipReturnsUnknownUid() throws Exception {
        String contactId = PREFIX + "left-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "left-source", new String[] {});
        // Approved group subscribes, but the caller's membership is
        // soft-cleared (removed_at set) — departure ends save-visibility.
        UUID groupId = seedGroup(PREFIX + "left-group", "approved");
        seedGroupMembership(groupId, userId, true);
        seedGroupSubscription(groupId, sourceId);
        String uid = PREFIX + "left-uid";
        seedPost(sourceId, uid, "READY", "Title D", "Body D", null, null, null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID), reply.text(),
                "a departed membership (removed_at set) must not grant save-visibility");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written via a departed membership");
    }

    @Test
    void saveViaPendingGroupReturnsUnknownUid() throws Exception {
        String contactId = PREFIX + "pend-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "pend-source", new String[] {});
        // Active membership, but the group is not approved — a
        // pending group processes no commands and grants no visibility.
        UUID groupId = seedGroup(PREFIX + "pend-group", "pending");
        seedGroupMembership(groupId, userId, false);
        seedGroupSubscription(groupId, sourceId);
        String uid = PREFIX + "pend-uid";
        seedPost(sourceId, uid, "READY", "Title P", "Body P", null, null, null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID), reply.text(),
                "a non-approved group must not grant save-visibility");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written via a non-approved group");
    }

    // ----- helpers --------------------------------------------------------

    private UUID seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state) VALUES (?, ?, FALSE, FALSE, 'vouched') "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedSource(String identifier, String[] bootstrapTags) throws Exception {
        return seedSource(identifier, bootstrapTags, "en");
    }

    private UUID seedSource(String identifier, String[] bootstrapTags, String language)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, language) VALUES ('rss', ?, ?, 'news', ?, ?) "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, "Test Source " + identifier);
            ps.setArray(3, conn.createArrayOf("TEXT", bootstrapTags));
            ps.setString(4, language);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    /**
     * A live bootstrap-origin source — implicitly in every scope's world
     * (D59). Cleaned by both the prefix cleanup and the {@code @AfterEach}
     * below: a bootstrap fixture that outlives this class would enter
     * every other class's world.
     */
    private UUID seedBootstrapSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, source_origin) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', 'bootstrap') "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, "Test Source " + identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedDmExclusion(UUID userId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO source_exclusion (scope_kind, scope_id, source_id) "
                            + "VALUES ('dm', ?, ?)",
                    userId, sourceId);
        }
    }

    @AfterEach
    void cleanupBootstrapFixtures() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // saved_post FK-references source: the successful bootstrap
            // /save writes a snapshot row that must go before its source.
            exec(conn,
                    "DELETE FROM saved_post WHERE source_id IN ("
                            + "SELECT id FROM source WHERE identifier LIKE ? "
                            + "AND source_origin = 'bootstrap')",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM post WHERE source_id IN ("
                            + "SELECT id FROM source WHERE identifier LIKE ? "
                            + "AND source_origin = 'bootstrap')",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM source_exclusion WHERE source_id IN ("
                            + "SELECT id FROM source WHERE identifier LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM source WHERE identifier LIKE ? "
                            + "AND source_origin = 'bootstrap'",
                    PREFIX + "%");
        }
    }

    private void seedDmSubscription(UUID userId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // DM scope_id is the user's own users.id (schema V7).
            exec(conn,
                    "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                            + "VALUES ('dm', ?, ?)",
                    userId, sourceId);
        }
    }

    private UUID seedGroup(String upstreamGroupId, String approvalStatus) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, approval_status) "
                             + "VALUES (?, ?, ?) RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, upstreamGroupId);
            ps.setString(3, approvalStatus);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedGroupMembership(UUID groupId, UUID userId, boolean removed) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO group_membership (group_id, user_id, removed_at) "
                            + "VALUES (?, ?, " + (removed ? "NOW()" : "NULL") + ")",
                    groupId, userId);
        }
    }

    private void seedGroupSubscription(UUID groupId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                            + "VALUES ('group', ?, ?)",
                    groupId, sourceId);
        }
    }

    private void seedPost(UUID sourceId, String uid, String status, String title,
                          String body, String url, String author, Instant publishedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (source_id, uid, title, body, url, author, "
                             + "published_at, fetched_at, status, upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, sourceId);
            ps.setString(2, uid);
            ps.setString(3, title);
            ps.setString(4, body);
            ps.setString(5, url);
            ps.setString(6, author);
            if (publishedAt == null) {
                ps.setObject(7, null);
            } else {
                ps.setObject(7, OffsetDateTime.ofInstant(publishedAt, java.time.ZoneOffset.UTC));
            }
            // fetched_at must fall inside the V7 bootstrap partition
            // (2026-05-01 .. 2026-06-01).
            ps.setObject(8, OffsetDateTime.parse("2026-05-15T00:00:00Z"));
            ps.setString(9, status);
            ps.setString(10, uid);
            ps.executeUpdate();
        }
    }

    private long countSavedPosts(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM saved_post sp "
                             + "JOIN users u ON u.id = sp.user_id "
                             + "WHERE u.contact_id = ?")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private int readSaveCount(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT save_count FROM users WHERE contact_id = ?")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "users row must exist for contact_id=" + contactId);
                return rs.getInt("save_count");
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
