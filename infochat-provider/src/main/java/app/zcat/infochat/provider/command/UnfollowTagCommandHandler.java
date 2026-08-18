package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupMembershipRepository;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /unfollow-tag <tag>} and
 * {@code /unfollow-tag --all} per {@code docs/spec/commands.md}
 * §Per-scope tag preferences. Auto-discovered by {@code InboundRouter}
 * via the CDI {@code Instance<CommandHandler>} scan.
 *
 * <p>Two argument shapes (mutually exclusive):
 * <ul>
 *   <li>{@code /unfollow-tag <tag>} — positional. One-transaction
 *       state-machine mutation per the spec table.</li>
 *   <li>{@code /unfollow-tag --all} — confirm-gated bulk reset. First
 *       call registers a {@link UnfollowTagAllConfirm} via M1-051's
 *       {@link ConfirmStateService} and returns the prompt; the
 *       second call ({@code /unfollow-tag --all confirm}) consumes
 *       the pending and executes the wipe.</li>
 * </ul>
 *
 * <p>Permission gate (DM = own scope; group = bot-admin or group-admin) is identical to
 * {@link FollowTagCommandHandler}; see that handler's class javadoc
 * for the T2-F deferral context.</p>
 *
 * <p>The handler writes zero rows to {@code audit_log} —
 * tag-preference mutations are user-preference, not privileged action
 * (spec §Authorization model). The {@code unfollowTagAllWritesNoAuditRow}
 * scenario is the regression guard.</p>
 */
@ApplicationScoped
public class UnfollowTagCommandHandler implements CommandHandler {

    /** Max fuzzy-suggestion entries surfaced in the unknown-tag error. */
    private static final int FUZZY_SUGGESTION_MAX = 5;

    /** {@link ConfirmStateService#takeMatching} key for the bulk-reset path. */
    private static final String CONFIRM_COMMAND_NAME = "unfollow-tag-all";

    private static final String SELECT_USER_ID_BY_ADAPTER_AND_CONTACT_ID =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_ID_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?";

    private static final String UPSERT_SCOPE_PREFERENCES_SQL =
            "INSERT INTO scope_preferences (scope_kind, scope_id) "
                    + "VALUES (?, ?) ON CONFLICT (scope_kind, scope_id) DO NOTHING";

    private static final String SELECT_TAG_MODE_FOR_UPDATE_SQL =
            "SELECT tag_mode FROM scope_preferences "
                    + "WHERE scope_kind = ? AND scope_id = ? FOR UPDATE";

    private static final String SELECT_TAG_ID_BY_NAME_SQL =
            "SELECT id FROM tag WHERE name = ?";

    private static final String SELECT_VOCABULARY_SQL =
            "SELECT name FROM tag ORDER BY name ASC";

    private static final String COUNT_SCOPE_TAG_FOR_SCOPE_SQL =
            "SELECT count(*) FROM scope_tag WHERE scope_kind = ? AND scope_id = ?";

    // The seed-all-minus-one INSERT joins the scope's D59 world source
    // set (live, non-excluded bootstrap sources plus the scope's
    // subscriptions — NOT source_subscription alone, or a fresh
    // subscription-less scope would seed an empty EXPLICIT set and zero
    // its digest; M1-621) with each source's bootstrap_tags array
    // (UNNEST), then resolves each name → tag(id) via the tag table.
    // The ON CONFLICT DO NOTHING keeps the INSERT idempotent against
    // ALL → EXPLICIT retries; the WHERE clause excludes the
    // unfollowed node and its subtree (recursive walk over
    // tag.parent_name) so "I want everything except X" is satisfied
    // in one statement (no fetch-then-loop window widening).
    private static final String INSERT_SEED_ALL_MINUS_ONE_SQL =
            "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                    + "SELECT DISTINCT ?, ?, t.id "
                    + "FROM source s "
                    + "JOIN UNNEST(s.bootstrap_tags) AS bt(name) ON TRUE "
                    + "JOIN tag t ON t.name = bt.name "
                    + "WHERE ((s.source_origin = 'bootstrap' AND s.deleted_at IS NULL "
                    + "        AND NOT EXISTS (SELECT 1 FROM source_exclusion e "
                    + "                         WHERE e.scope_kind = ? AND e.scope_id = ? "
                    + "                           AND e.source_id = s.id)) "
                    + "    OR s.id IN (SELECT source_id FROM source_subscription "
                    + "                 WHERE scope_kind = ? AND scope_id = ?)) "
                    + "  AND t.id NOT IN ("
                    + "      WITH RECURSIVE subtree(node) AS ("
                    + "          SELECT name FROM tag WHERE name = ?"
                    + "          UNION SELECT c.name FROM tag c JOIN subtree p"
                    + "              ON c.parent_name = p.node"
                    + "      )"
                    + "      SELECT id FROM tag WHERE name IN (SELECT node FROM subtree)) "
                    + "ON CONFLICT (scope_kind, scope_id, tag_id) DO NOTHING";

    private static final String DELETE_SCOPE_TAG_ONE_SQL =
            "DELETE FROM scope_tag "
                    + "WHERE scope_kind = ? AND scope_id = ? AND tag_id = ?";

    private static final String DELETE_SCOPE_TAG_ALL_SQL =
            "DELETE FROM scope_tag "
                    + "WHERE scope_kind = ? AND scope_id = ?";

    private static final String UPDATE_FLIP_TO_EXPLICIT_SQL =
            "UPDATE scope_preferences "
                    + "SET tag_mode = 'EXPLICIT', "
                    + "    tag_subscription_version = tag_subscription_version + 1 "
                    + "WHERE scope_kind = ? AND scope_id = ?";

    private static final String UPDATE_FLIP_TO_ALL_SQL =
            "UPDATE scope_preferences "
                    + "SET tag_mode = 'ALL', "
                    + "    tag_subscription_version = tag_subscription_version + 1 "
                    + "WHERE scope_kind = ? AND scope_id = ?";

    private static final String UPDATE_BUMP_TAG_VERSION_SQL =
            "UPDATE scope_preferences "
                    + "SET tag_subscription_version = tag_subscription_version + 1 "
                    + "WHERE scope_kind = ? AND scope_id = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    ConfirmStateService confirmStateService;

    @Inject
    GroupMembershipRepository groupMembershipRepository;

    @Override
    public String name() {
        return "unfollow-tag";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        // Permission gate: group scope requires group-admin.
        final String scopeKind;
        final UUID scopeId;
        final UUID actorId;
        if (scope instanceof ScopeRef.Group group) {
            Optional<UUID> actorIdOpt = lookupActorId(inboundContext.senderContactId());
            if (actorIdOpt.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_TAG_GROUP_ADMIN_ONLY, inboundContext.effectiveLanguage()));
            }
            UUID groupDbId = lookupGroupId(group.adapterGroupId());
            if (groupDbId == null
                    || (!isBotAdmin(actorIdOpt.get())
                        && !groupMembershipRepository.isGroupAdmin(groupDbId, actorIdOpt.get()))) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_TAG_GROUP_ADMIN_ONLY, inboundContext.effectiveLanguage()));
            }
            actorId = actorIdOpt.get();
            scopeKind = "group";
            scopeId = groupDbId;
        } else {
            ScopeRef.Dm dm = (ScopeRef.Dm) scope;
            Optional<UUID> actorIdOpt = lookupActorId(dm.contactId());
            if (actorIdOpt.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
            }
            actorId = actorIdOpt.get();
            scopeKind = "dm";
            scopeId = actorIdOpt.get();
        }

        UnfollowTagArgs parsed = UnfollowTagArgs.parse(rawText);

        // Mutex check — positional tag AND --all are mutually exclusive.
        if (parsed.hasAllFlag && parsed.positionalTag != null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_TAG_MUTUALLY_EXCLUSIVE, inboundContext.effectiveLanguage()));
        }

        // Confirm-leg fork: trailing ` confirm` token combined with the
        // --all flag form is the second leg of the bulk-reset pair.
        // takeMatching keyed on CONFIRM_COMMAND_NAME pops the pending
        // and dispatches to the wipe transaction.
        if (parsed.hasAllFlag && parsed.hasConfirmToken) {
            Optional<ConfirmStateService.PendingConfirm> taken =
                    confirmStateService.takeMatching(actorId, scope, CONFIRM_COMMAND_NAME);
            if (taken.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING, inboundContext.effectiveLanguage()));
            }
            return executeUnfollowTagAllTransaction(scope, scopeKind, scopeId);
        }

        // First-call `--all` (no trailing ` confirm`): register pending
        // and return the prompt + current row-count warning.
        if (parsed.hasAllFlag) {
            long currentCount = countScopeTagRows(scopeKind, scopeId);
            confirmStateService.remember(actorId, scope, new UnfollowTagAllConfirm());
            String prompt = MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_UNFOLLOW_TAG_ALL, inboundContext.effectiveLanguage()),
                    Long.toString(confirmStateService.timeoutSeconds()),
                    Long.toString(currentCount));
            return reply(scope, prompt);
        }

        // Positional `/unfollow-tag <tag>` form.
        if (parsed.positionalTag == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT, inboundContext.effectiveLanguage()),
                    "/unfollow-tag <tag>|--all"));
        }

        // Apply the controlled-vocabulary pipeline (trim → NFC → lowercase
        // → char-class) before the lookup, mirroring /follow-tag, so a
        // case/Unicode variant of a vocabulary tag resolves instead of
        // silently missing the exact WHERE name = ? match. (M1-489)
        String normalizedTag = TagNormalizer.normalize(parsed.positionalTag);
        Optional<UUID> tagId = TagNormalizer.isValid(normalizedTag)
                ? lookupTagId(normalizedTag)
                : Optional.empty();
        if (tagId.isEmpty()) {
            List<String> vocab = readVocabulary();
            List<String> suggestions = fuzzySuggest(normalizedTag, vocab, FUZZY_SUGGESTION_MAX);
            String body = MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_TAG_UNKNOWN_TAG, inboundContext.effectiveLanguage()),
                    String.join(", ", suggestions));
            return reply(scope, body);
        }

        return executeUnfollowTagPositionalTransaction(
                scope, scopeKind, scopeId, tagId.get(), normalizedTag);
    }

    private OutboundMessage executeUnfollowTagPositionalTransaction(ScopeRef scope,
                                                                    String scopeKind,
                                                                    UUID scopeId,
                                                                    UUID tagIdValue,
                                                                    String tagName) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                upsertScopePreferences(conn, scopeKind, scopeId);
                String mode = selectTagModeForUpdate(conn, scopeKind, scopeId);

                final String body;
                if ("ALL".equals(mode)) {
                    // ALL → EXPLICIT: flip + seed the
                    // set excluding the selected tag and its descendants
                    // in one INSERT…SELECT join.
                    // The flip UPDATE runs BEFORE the INSERT so a
                    // concurrent read on the same scope cannot observe
                    // (mode=ALL, scope_tag non-empty) — the forbidden
                    // intermediate state per spec.
                    updateFlipToExplicit(conn, scopeKind, scopeId);
                    insertSeedAllMinusOne(conn, scopeKind, scopeId, tagName);
                    body = MessageFormat.format(
                            bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_TAG_SUCCESS_FROM_ALL, inboundContext.effectiveLanguage()),
                            tagName);
                } else {
                    // EXPLICIT mode: DELETE the row; if post-delete
                    // count is 0, flip back to ALL (the spec's
                    // empty-set semantic). The COUNT happens after the
                    // DELETE so the row count reflects the post-delete
                    // state inside the same transaction.
                    deleteOneScopeTag(conn, scopeKind, scopeId, tagIdValue);
                    long remaining = countScopeTagRowsInTx(conn, scopeKind, scopeId);
                    if (remaining == 0L) {
                        updateFlipToAll(conn, scopeKind, scopeId);
                        body = MessageFormat.format(
                                bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_TAG_FLIPS_BACK_TO_ALL, inboundContext.effectiveLanguage()),
                                tagName);
                    } else {
                        updateBumpTagVersion(conn, scopeKind, scopeId);
                        body = MessageFormat.format(
                                bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_TAG_SUCCESS_IN_PLACE, inboundContext.effectiveLanguage()),
                                tagName);
                    }
                }
                conn.commit();
                return reply(scope, body);
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "UnfollowTagCommandHandler.executeUnfollowTagPositional failed for contact_id="
                                + ContactIds.redact(inboundContext.senderContactId()), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnfollowTagCommandHandler connection failed for contact_id="
                            + ContactIds.redact(inboundContext.senderContactId()), e);
        }
    }

    private OutboundMessage executeUnfollowTagAllTransaction(ScopeRef scope,
                                                             String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                upsertScopePreferences(conn, scopeKind, scopeId);
                // Lock the scope_preferences row for the duration of
                // the wipe so a concurrent /follow-tag cannot race
                // through SELECT FOR UPDATE → INSERT between our
                // DELETE and our UPDATE.
                selectTagModeForUpdate(conn, scopeKind, scopeId);
                long deleted = deleteAllScopeTags(conn, scopeKind, scopeId);
                updateFlipToAll(conn, scopeKind, scopeId);
                conn.commit();
                String body = MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_TAG_ALL_SUCCESS, inboundContext.effectiveLanguage()),
                        Long.toString(deleted));
                return reply(scope, body);
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "UnfollowTagCommandHandler.executeUnfollowTagAll failed for contact_id="
                                + ContactIds.redact(((ScopeRef.Dm) scope).contactId()), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnfollowTagCommandHandler connection failed for contact_id="
                            + ContactIds.redact(inboundContext.senderContactId()), e);
        }
    }

    private long countScopeTagRows(String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection()) {
            return countScopeTagRowsInTx(conn, scopeKind, scopeId);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnfollowTagCommandHandler.countScopeTagRows failed", e);
        }
    }

    private long countScopeTagRowsInTx(Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COUNT_SCOPE_TAG_FOR_SCOPE_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void upsertScopePreferences(Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_SCOPE_PREFERENCES_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.executeUpdate();
        }
    }

    private String selectTagModeForUpdate(Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_TAG_MODE_FOR_UPDATE_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString("tag_mode");
            }
        }
    }

    private void insertSeedAllMinusOne(Connection conn, String scopeKind, UUID scopeId,
                                       String excludedTagName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SEED_ALL_MINUS_ONE_SQL)) {
            // Binds 3-6: the world predicate's exclusion probe pair, then
            // its subscription-arm pair.
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setString(3, scopeKind);
            ps.setObject(4, scopeId);
            ps.setString(5, scopeKind);
            ps.setObject(6, scopeId);
            ps.setString(7, excludedTagName);
            ps.executeUpdate();
        }
    }

    private void deleteOneScopeTag(Connection conn, String scopeKind, UUID scopeId, UUID tagId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DELETE_SCOPE_TAG_ONE_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, tagId);
            ps.executeUpdate();
        }
    }

    private long deleteAllScopeTags(Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DELETE_SCOPE_TAG_ALL_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            return ps.executeUpdate();
        }
    }

    private void updateFlipToExplicit(Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_FLIP_TO_EXPLICIT_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.executeUpdate();
        }
    }

    private void updateFlipToAll(Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_FLIP_TO_ALL_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.executeUpdate();
        }
    }

    private void updateBumpTagVersion(Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_BUMP_TAG_VERSION_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.executeUpdate();
        }
    }

    private Optional<UUID> lookupActorId(String contactId) {
        String adapter = inboundContext.adapterName();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     SELECT_USER_ID_BY_ADAPTER_AND_CONTACT_ID)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnfollowTagCommandHandler.lookupActorId failed for contact_id="
                            + ContactIds.redact(contactId), e);
        }
    }

    private @Nullable UUID lookupGroupId(String adapterGroupId) {
        String adapter = inboundContext.adapterName();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_ID_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, adapterGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnfollowTagCommandHandler.lookupGroupId failed", e);
        }
    }

    private boolean isBotAdmin(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_admin FROM users WHERE id = ?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_admin");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnfollowTagCommandHandler.isBotAdmin failed", e);
        }
    }

    private Optional<UUID> lookupTagId(String tagName) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_TAG_ID_BY_NAME_SQL)) {
            ps.setString(1, tagName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnfollowTagCommandHandler.lookupTagId failed", e);
        }
    }

    private List<String> readVocabulary() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_VOCABULARY_SQL);
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString("name"));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnfollowTagCommandHandler.readVocabulary failed", e);
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private static List<String> fuzzySuggest(String supplied, List<String> vocabulary, int max) {
        record Scored(String name, int shared) {}
        List<Scored> scored = new ArrayList<>(vocabulary.size());
        for (String v : vocabulary) {
            scored.add(new Scored(v, sharedPrefixLength(supplied, v)));
        }
        scored.sort((a, b) -> {
            int cmp = Integer.compare(b.shared, a.shared);
            return cmp != 0 ? cmp : a.name.compareTo(b.name);
        });
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, scored.size()); i++) {
            out.add(scored.get(i).name);
        }
        return out;
    }

    private static int sharedPrefixLength(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /**
     * Parsed form of {@code /unfollow-tag [<tag>] [--all] [confirm]}.
     * Mutex enforcement happens in the caller (it surfaces a friendly
     * error). The trailing {@code confirm} token is recognized only
     * when {@code --all} is also present — the positional path has no
     * confirm gate.
     */
    private record UnfollowTagArgs(@Nullable String positionalTag, boolean hasAllFlag,
                                   boolean hasConfirmToken) {

        static UnfollowTagArgs parse(String rawText) {
            String[] parts = rawText.trim().split("\\s+");
            String positional = null;
            boolean all = false;
            boolean confirmToken = false;
            // Skip parts[0] — the "/unfollow-tag" token.
            for (int i = 1; i < parts.length; i++) {
                String tok = parts[i];
                if (tok.equals("--all")) {
                    all = true;
                } else if (tok.equals("confirm")) {
                    // `confirm` is recognized as the trailing confirm
                    // token only when it appears LAST. A user typing
                    // `/unfollow-tag confirm` as a positional is an
                    // unknown-tag path (handled by the vocabulary
                    // check downstream).
                    if (i == parts.length - 1) {
                        confirmToken = true;
                    } else if (positional == null) {
                        positional = tok;
                    }
                } else if (positional == null) {
                    positional = tok;
                }
            }
            return new UnfollowTagArgs(positional, all, confirmToken);
        }
    }
}
