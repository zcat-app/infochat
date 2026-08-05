package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.ingest.IngestTextNormalizer;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import java.util.Objects;

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
 *       <li>Target post lookup: the {@code READY}-status match plus
 *           the any-caller-scope visibility filter (spec §Content
 *           Visibility-of-target rules) — the post's source must be
 *           subscribed in the caller's DM scope or in an approved,
 *           non-removed group where the caller holds an active
 *           membership. Non-READY rows (QUARANTINED, NEEDS_REVIEW),
 *           missing UIDs, and READY rows invisible in every caller
 *           scope are indistinguishable at the user surface — all
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

    // ORDER BY fetched_at DESC LIMIT 1 picks the most-recent READY row
    // for the given uid. The post table's UNIQUE(uid, fetched_at) lets
    // the same uid appear across partitions (cross-window dedup is the
    // fetcher's job per V7 + schema.md §UID derivation); we snapshot
    // the latest READY version. The JOIN on source supplies the
    // current bootstrap_tags for snapshot_tags.
    //
    // The two legs are the any-caller-scope visibility filter (spec
    // §Content visibility-of-target rules), each now the D59 world of
    // one caller scope: the post's source must be in the caller's DM
    // world (a live, non-DM-excluded bootstrap source, or a DM
    // subscription; scope_id = the caller's users.id) or in the world
    // of an approved, non-removed group where the caller holds an
    // active membership (same bootstrap∨subscription form, exclusion
    // keyed by that group's id). A READY post outside that union falls
    // into the same empty-result path as an unknown UID, so the
    // existence-vs-no-access distinction is never exposed (the getPost
    // contract, security.md §Prompt-injection defenses).
    private static final String SELECT_POST_SQL =
            "SELECT p.id, p.title, p.body, p.title_en, p.body_en, p.url, p.author, "
                    + "p.published_at, p.source_id, s.bootstrap_tags, s.language "
                    + "FROM post p JOIN source s ON s.id = p.source_id "
                    + "WHERE p.uid = ? AND p.status = 'READY' "
                    + "AND (((s.source_origin = 'bootstrap' AND s.deleted_at IS NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM source_exclusion e "
                    + "WHERE e.scope_kind = 'dm' AND e.scope_id = ? "
                    + "AND e.source_id = p.source_id)) "
                    + "OR EXISTS (SELECT 1 FROM source_subscription ss "
                    + "WHERE ss.source_id = p.source_id "
                    + "AND ss.scope_kind = 'dm' AND ss.scope_id = ?)) "
                    + "OR EXISTS (SELECT 1 FROM group_membership gm "
                    + "JOIN groups g ON g.id = gm.group_id "
                    + "WHERE gm.user_id = ? AND gm.removed_at IS NULL "
                    + "AND g.approval_status = 'approved' AND g.removed_at IS NULL "
                    + "AND ((s.source_origin = 'bootstrap' AND s.deleted_at IS NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM source_exclusion e "
                    + "WHERE e.scope_kind = 'group' AND e.scope_id = g.id "
                    + "AND e.source_id = p.source_id)) "
                    + "OR EXISTS (SELECT 1 FROM source_subscription ss "
                    + "WHERE ss.source_id = p.source_id "
                    + "AND ss.scope_kind = 'group' AND ss.scope_id = g.id)))) "
                    + "ORDER BY p.fetched_at DESC LIMIT 1";

    private static final String SELECT_ALREADY_SAVED_SQL =
            "SELECT 1 FROM saved_post WHERE user_id = ? AND post_uid = ?";

    private static final String INSERT_SAVED_POST_SQL =
            "INSERT INTO saved_post ("
                    + "user_id, post_uid, source_id, title, body, url, author, "
                    + "published_at, snapshot_tags, personal_tags, source_language, "
                    + "title_en, body_en"
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    @ConfigProperty(name = "infochat.save.cap")
    int saveCap;

    // Profile-driven personal-tag caps. defaultValue mirrors the
    // read-side comparator (ChatToolDispatcher's input-max-length /
    // list-max-size), so the write side bounds tag length + count
    // symmetrically without a separate application.properties entry.
    @Inject
    @ConfigProperty(name = "infochat.save.personal-tag-max-length", defaultValue = "64")
    int personalTagMaxLength;

    @Inject
    @ConfigProperty(name = "infochat.save.personal-tag-max-count", defaultValue = "20")
    int personalTagMaxCount;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "save";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        ParsedArgs args = parseArgs(rawText);

        // Parser-boundary personal-tag caps. /saved interpolates
        // personal_tags into outbound text (bypassing the inbound body
        // cap) and listSaves reads them into the chat prompt, so the
        // write side must reject over-length / over-count tags here —
        // before any DB work — so they are never stored.
        if (args.personalTags.size() > personalTagMaxCount) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_SAVE_TOO_MANY_TAGS, inboundContext.effectiveLanguage()),
                    personalTagMaxCount));
        }
        for (String tag : args.personalTags) {
            if (tag.length() > personalTagMaxLength) {
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_SAVE_TAG_TOO_LONG, inboundContext.effectiveLanguage()),
                        personalTagMaxLength));
            }
        }

        // Reject rather than rewrite. Personal tags are echoed verbatim into
        // the group-visible /saved reply (SavedCommandHandler's joinTags ->
        // REPLY_SAVED_LINE), so a tag shaped like "/grant-admin <uuid>" makes
        // the bot broadcast a syntactically valid privileged command to every
        // group member, including any bot admin who copy-pastes it. That is
        // the deterministic-reply social-engineering class of M1-656 /
        // M1-659, and it is the channel security.md §LLM output sanitizer
        // leaves unfiltered by design, so no output-side filter catches it.
        //
        // A slash ANYWHERE disqualifies the tag — deliberately absolute
        // rather than a judgement about whether the slash "opens a word".
        // M1-659 defeated the boundary form twice, once on each side of the
        // predicate (U+2800 BRAILLE PATTERN BLANK against an isWhitespace
        // test, the Hangul fillers U+115F/U+1160/U+3164 against an
        // isLetterOrDigit test); the lesson is structural, since every
        // partition of Unicode has blank-rendering members on both sides. D12
        // makes '/' the only command sigil, so a slash-free tag cannot carry
        // a command token regardless of what surrounds it.
        //
        // Testing the ASCII slash alone suffices because parseTagList already
        // NFKC-folds each tag, which turns U+FF0F FULLWIDTH SOLIDUS into '/'.
        // The homoglyphs that do NOT fold (U+2215, U+2044, U+29F8) equally do
        // not parse as a command when pasted back, so they yield nothing
        // executable. The check runs after the size caps so an over-long or
        // over-count tag set keeps reporting its existing error.
        for (String tag : args.personalTags) {
            if (tag.indexOf('/') >= 0) {
                return reply(scope, bundleLoader.get(
                        BundleKeys.ERROR_SAVE_TAG_INVALID, inboundContext.effectiveLanguage()));
            }
        }

        if (args.uid == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID, inboundContext.effectiveLanguage()));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = resolveContactId(scope);
        if (callerContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID, inboundContext.effectiveLanguage()));
        }

        return executeSave(scope, adapter, callerContactId, args);
    }

    private OutboundMessage executeSave(ScopeRef scope, String adapter,
                                        String callerContactId, ParsedArgs args) {
        // uid is non-null here: handle() rejects a null uid before dispatch.
        String uid = Objects.requireNonNull(args.uid);
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
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID, inboundContext.effectiveLanguage()));
                }
                ActorRow actor = actorOpt.get();

                // Step 3b — target post lookup. Non-READY posts,
                // missing UIDs, and READY posts invisible in every
                // caller scope all surface as unknown_uid per spec
                // §Content Visibility-of-target rules.
                Optional<PostSnapshot> postOpt = lookupVisibleReadyPost(conn, uid, actor.id);
                if (postOpt.isEmpty()) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID, inboundContext.effectiveLanguage()));
                }
                PostSnapshot post = postOpt.get();

                // Step 3c — already-saved check INSIDE the tx (the
                // FOR UPDATE on the actor row above serializes a
                // concurrent /save of the same UID; this SELECT
                // observes the post-serialization state).
                if (isAlreadySaved(conn, actor.id, uid)) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_ALREADY_SAVED, inboundContext.effectiveLanguage()));
                }

                // Step 3d — cap check.
                if (actor.saveCount >= saveCap) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_SAVE_CAP_MET, inboundContext.effectiveLanguage()));
                }

                // Step 3e — snapshot INSERT. The V15 AFTER-INSERT
                // trigger increments users.save_count in the same tx.
                insertSavedPost(conn, actor.id, uid, post, args.personalTags);

                conn.commit();

                String body = MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS, inboundContext.effectiveLanguage()),
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
        return userRepository.findByAdapterAndContactIdForUpdate(conn, adapter, contactId)
                .map(u -> new ActorRow(u.id(), u.saveCount()));
    }

    private Optional<PostSnapshot> lookupVisibleReadyPost(Connection conn, String uid,
                                                          UUID actorId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_POST_SQL)) {
            // Binds: DM-world exclusion probe (the caller's users.id is the
            // DM scope_id), DM subscription arm, then gm.user_id for the
            // group-world leg (the group legs' scope ids correlate to g.id).
            ps.setString(1, uid);
            ps.setObject(2, actorId);
            ps.setObject(3, actorId);
            ps.setObject(4, actorId);
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
                        rs.getString("title_en"),
                        rs.getString("body_en"),
                        rs.getString("url"),
                        rs.getString("author"),
                        publishedAtTs == null ? null : publishedAtTs.toInstant(),
                        (UUID) rs.getObject("source_id"),
                        bootstrapTags,
                        rs.getString("language")));
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
            ps.setString(11, post.sourceLanguage);
            // A NULL anchor is snapshotted AS NULL, never coalesced to the
            // original: NULL is what M1-759's anchor-absent branch reads to
            // decide the bookmark has no anchor, and substituting the
            // original here would make DisplayHeadline report anchored=true
            // for text that is still the publisher's own words — an
            // unbracketed foreign line to a reader who cannot read it.
            ps.setString(12, post.titleEn);
            ps.setString(13, post.bodyEn);
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

    /**
     * Split the {@code -t} argument and canonicalize each tag.
     *
     * <p>Canonicalization happens HERE, at the parse boundary, so that the
     * size caps, the slash gate in {@link #handle}, and the value actually
     * stored all measure the same representation the {@code /saved} reply
     * will echo. Relying on the router's inbound normalization instead would
     * not be self-sufficient: {@code InboundRouter.normalize} works per line
     * and appends fenced code-block content verbatim while routing is decided
     * on the whole body's first character, so a {@code /save} on line 1 can
     * carry an un-normalized fenced payload on line 3 (M1-659).
     *
     * <p>NFKC folds compatibility forms — notably U+FF0F FULLWIDTH SOLIDUS to
     * a real {@code '/'} — so the gate cannot be slipped by a homoglyph that
     * renders as a slash once pasted back. {@code stripMetadataField} then
     * removes bidi overrides, zero-width characters and the Unicode
     * line/paragraph separators, which would otherwise let a tag forge extra
     * apparent lines inside the group-visible {@code /saved} listing; it is
     * the project's single declaration of that strip for single-line metadata
     * fields, shared with the {@code --name} display-name fix.
     */
    private static List<String> parseTagList(String csv) {
        List<String> out = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String canonical = IngestTextNormalizer.stripMetadataField(
                    Normalizer.normalize(raw, Normalizer.Form.NFKC)).trim();
            if (!canonical.isEmpty()) {
                out.add(canonical);
            }
        }
        return out;
    }

    record ParsedArgs(@Nullable String uid, List<String> personalTags) {}

    private record ActorRow(UUID id, int saveCount) {}

    private record PostSnapshot(
            String title,
            String body,
            // `post.title_en` / `post.body_en` are NULL until the collector's
            // IngestTranslationWorker has run, and stay NULL forever for an
            // en-language source or after the translator exhausts its
            // attempts (V74). The snapshot preserves that state verbatim.
            @Nullable String titleEn,
            @Nullable String bodyEn,
            String url,
            String author,
            @Nullable Instant publishedAt,
            UUID sourceId,
            List<String> bootstrapTags,
            String sourceLanguage) {}
}
