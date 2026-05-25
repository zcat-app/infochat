package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /save <uid> [-t personal-tags]} per
 * {@code docs/spec/commands.md} §Content + {@code docs/spec/schema.md}
 * §Per-user state (D13 — per-user-globally) and Invariant 6 (snapshot
 * the body so partition drops on {@code post} do not break the bookmark).
 *
 * <p>Dispatch sequence:
 * <ol>
 *   <li>Parse positional {@code <uid>} plus optional {@code -t personal-tags}.
 *       A missing UID falls back to {@code error.save.unknown_uid} —
 *       the spec catalogue does not assign a separate "missing arg"
 *       reply for {@code /save}, so the visibility-of-target rule's
 *       reply doubles as the "no UID supplied" reply.</li>
 *   <li>Open the transaction ({@code autoCommit=false}).
 *     <ol type="a">
 *       <li>Actor lookup INSIDE the tx via {@code SELECT id, save_count
 *           FROM users WHERE adapter = ? AND contact_id = ? FOR UPDATE}.
 *           The row lock is the spec's atomic-cap mechanism: two
 *           concurrent {@code /save} calls at {@code save_count = cap-1}
 *           serialize at the lock, and only the first commits an
 *           INSERT — the second observes the updated counter and
 *           returns {@code error.save.cap_met}. Verified by
 *           {@code SaveCapConcurrencyIT}.</li>
 *       <li>Target post lookup: {@code SELECT id, title, body, url,
 *           author, published_at, source_id, fetched_at FROM post
 *           WHERE uid = ? AND status = 'READY' ORDER BY fetched_at
 *           DESC LIMIT 1}. Non-READY rows (QUARANTINED, NEEDS_REVIEW)
 *           are indistinguishable from missing UIDs at the user
 *           surface per spec §Content Visibility-of-target rules — both
 *           branches return {@code error.save.unknown_uid}.</li>
 *       <li>Already-saved check: a {@code SELECT 1 FROM saved_post}
 *           round-trip is cheaper than catching the PK-collision
 *           SQLException after a failed INSERT and surfaces a
 *           deterministic friendly error. The check runs INSIDE the
 *           tx so the FOR UPDATE lock on the actor's users row
 *           serializes a concurrent {@code /save} of the same UID.</li>
 *       <li>Cap check: {@code save_count >= cap} → rollback +
 *           {@code error.save.cap_met}. No INSERT, no trigger fires.</li>
 *       <li>Snapshot INSERT into {@code saved_post}. The bot's
 *           current source.bootstrap_tags lands in {@code snapshot_tags}
 *           (per the ticket's snapshot rule: "the user is bookmarking
 *           the bot's tag classification AS WELL as the post"); the
 *           caller's {@code -t} values land in {@code personal_tags}.
 *           The V15 {@code trg_saved_post_count_ins} trigger
 *           increments {@code users.save_count} in the same tx.</li>
 *       <li>COMMIT.</li>
 *     </ol>
 *   </li>
 *   <li>Reply {@code reply.save.success} interpolated with the UID.</li>
 * </ol>
 *
 * <p>No audit row, no confirm gate. Spec §Content commits to /save being
 * non-destructive; the spec's audit-logged verb set in
 * {@code docs/spec/security.md} §Authorization model does not include a
 * SAVE verb.</p>
 */
@ApplicationScoped
public class SaveCommandHandler implements CommandHandler {

    private static final String SELECT_ACTOR_FOR_UPDATE_SQL =
            "SELECT id, save_count FROM users WHERE adapter = ? AND contact_id = ? FOR UPDATE";

    // ORDER BY fetched_at DESC LIMIT 1 picks the most-recent READY row
    // for the given uid. The post table's UNIQUE(uid, fetched_at) lets
    // the same uid appear across partitions (cross-window dedup is the
    // fetcher's job per V7 + schema.md §UID derivation); we snapshot
    // the latest READY version. The JOIN on source supplies the
    // current bootstrap_tags for snapshot_tags.
    private static final String SELECT_POST_SQL =
            "SELECT p.id, p.title, p.body, p.url, p.author, p.published_at, "
                    + "p.source_id, s.bootstrap_tags "
                    + "FROM post p JOIN source s ON s.id = p.source_id "
                    + "WHERE p.uid = ? AND p.status = 'READY' "
                    + "ORDER BY p.fetched_at DESC LIMIT 1";

    private static final String SELECT_ALREADY_SAVED_SQL =
            "SELECT 1 FROM saved_post WHERE user_id = ? AND post_uid = ?";

    private static final String INSERT_SAVED_POST_SQL =
            "INSERT INTO saved_post ("
                    + "user_id, post_uid, source_id, title, body, url, author, "
                    + "published_at, snapshot_tags, personal_tags"
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    @ConfigProperty(name = "infochat.save.cap")
    int saveCap;

    @Override
    public String name() {
        return "save";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        ParsedArgs args = parseArgs(rawText);
        if (args.uid == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = resolveContactId(scope);
        if (callerContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID));
        }

        return executeSave(scope, adapter, callerContactId, args);
    }

    private OutboundMessage executeSave(ScopeRef scope, String adapter,
                                        String callerContactId, ParsedArgs args) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Step 3a — actor lookup + FOR UPDATE row lock. The
                // lock serializes concurrent /save calls against the
                // same user and is the atomic-cap mechanism.
                Optional<ActorRow> actorOpt =
                        lookupActorForUpdate(conn, adapter, callerContactId);
                if (actorOpt.isEmpty()) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID));
                }
                ActorRow actor = actorOpt.get();

                // Step 3b — target post lookup. Non-READY posts and
                // missing UIDs both surface as unknown_uid per spec
                // §Content Visibility-of-target rules.
                Optional<PostSnapshot> postOpt = lookupReadyPost(conn, args.uid);
                if (postOpt.isEmpty()) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID));
                }
                PostSnapshot post = postOpt.get();

                // Step 3c — already-saved check INSIDE the tx (the
                // FOR UPDATE on the actor row above serializes a
                // concurrent /save of the same UID; this SELECT
                // observes the post-serialization state).
                if (isAlreadySaved(conn, actor.id, args.uid)) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_ALREADY_SAVED));
                }

                // Step 3d — cap check.
                if (actor.saveCount >= saveCap) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_CAP_MET));
                }

                // Step 3e — snapshot INSERT. The V15 AFTER-INSERT
                // trigger increments users.save_count in the same tx.
                insertSavedPost(conn, actor.id, args.uid, post, args.personalTags);

                conn.commit();

                String body = MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS),
                        args.uid);
                return reply(scope, body);
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "SaveCommandHandler.executeSave failed for adapter="
                                + adapter + " contact_id="
                                + ContactIds.redact(callerContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "SaveCommandHandler.executeSave connection failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(callerContactId), e);
        }
    }

    private Optional<ActorRow> lookupActorForUpdate(Connection conn, String adapter,
                                                    String contactId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ACTOR_FOR_UPDATE_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ActorRow(
                        (UUID) rs.getObject("id"),
                        rs.getInt("save_count")));
            }
        }
    }

    private Optional<PostSnapshot> lookupReadyPost(Connection conn, String uid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_POST_SQL)) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Array tagsArray = rs.getArray("bootstrap_tags");
                List<String> bootstrapTags = tagsArray == null
                        ? List.of()
                        : Arrays.asList((String[]) tagsArray.getArray());
                Timestamp publishedAtTs = rs.getTimestamp("published_at");
                return Optional.of(new PostSnapshot(
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("url"),
                        rs.getString("author"),
                        publishedAtTs == null ? null : publishedAtTs.toInstant(),
                        (UUID) rs.getObject("source_id"),
                        bootstrapTags));
            }
        }
    }

    private boolean isAlreadySaved(Connection conn, UUID userId, String postUid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ALREADY_SAVED_SQL)) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void insertSavedPost(Connection conn, UUID userId, String postUid,
                                 PostSnapshot post, List<String> personalTags) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SAVED_POST_SQL)) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            ps.setObject(3, post.sourceId);
            ps.setString(4, post.title);
            ps.setString(5, post.body);
            ps.setString(6, post.url);
            ps.setString(7, post.author);
            if (post.publishedAt == null) {
                ps.setObject(8, null);
            } else {
                ps.setObject(8, OffsetDateTime.ofInstant(post.publishedAt, java.time.ZoneOffset.UTC));
            }
            ps.setArray(9, conn.createArrayOf("TEXT", post.bootstrapTags.toArray(new String[0])));
            ps.setArray(10, conn.createArrayOf("TEXT", personalTags.toArray(new String[0])));
            ps.executeUpdate();
        }
    }

    private String resolveContactId(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm
                ? dm.contactId()
                : inboundContext.senderContactId();
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    /**
     * Parse the inbound body for {@code /save <uid> [-t tag1,tag2,...]}.
     * The {@code -t} flag is optional; when present, its single
     * argument is a comma-separated list of personal tags (free-form,
     * no controlled-vocabulary check per spec §Content "Personal tags
     * are free-form and never join the controlled vocabulary").
     * Returns {@link ParsedArgs} with {@code uid == null} when no UID
     * was supplied.
     */
    static ParsedArgs parseArgs(String rawText) {
        String[] tokens = rawText.trim().split("\\s+");
        // tokens[0] is "/save"; tokens[1] (if present) is the uid; the
        // -t flag comes after.
        if (tokens.length < 2 || tokens[1].startsWith("-")) {
            return new ParsedArgs(null, List.of());
        }
        String uid = tokens[1];
        List<String> personalTags = List.of();
        for (int i = 2; i < tokens.length; i++) {
            if ("-t".equals(tokens[i]) && i + 1 < tokens.length) {
                personalTags = parseTagList(tokens[i + 1]);
                break;
            }
        }
        return new ParsedArgs(uid, personalTags);
    }

    private static List<String> parseTagList(String csv) {
        List<String> out = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    record ParsedArgs(String uid, List<String> personalTags) {}

    private record ActorRow(UUID id, int saveCount) {}

    private record PostSnapshot(
            String title,
            String body,
            String url,
            String author,
            Instant publishedAt,
            UUID sourceId,
            List<String> bootstrapTags) {}
}
