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
 * Implements {@code /follow-tag <tag>} per
 * {@code docs/spec/commands.md} §Per-scope tag preferences. Auto-
 * discovered by {@code InboundRouter} via the CDI
 * {@code Instance<CommandHandler>} scan; no router-side edit needed.
 *
 * <p>Mode-transition state machine (one transaction):
 * <ol>
 *   <li>Permission gate — DM scope is the caller's own scope; group
 *       scope requires bot-admin or group-admin: the actor is
 *       resolved from {@link InboundContext#senderContactId()}
 *       against {@code users}, then checked against
 *       {@code user.is_admin} and
 *       {@code group_membership.is_group_admin}. An unregistered
 *       actor, unknown group, or non-admin caller gets
 *       {@link BundleKeys#ERROR_FOLLOW_TAG_GROUP_ADMIN_ONLY}.</li>
 *   <li>Vocabulary validation — the tag must appear in {@code tag.name}.
 *       Unknown tags surface
 *       {@link BundleKeys#ERROR_FOLLOW_TAG_UNKNOWN_TAG} with a
 *       fuzzy-suggestion footer (the M1-037 SummaryCommandHandler /
 *       M1-036 AddSourceArgs precedent).</li>
 *   <li>State machine — in one transaction:
 *     <ol type="a">
 *       <li>Ensure {@code scope_preferences} row exists
 *           ({@code INSERT ... ON CONFLICT DO NOTHING}). The row is
 *           assumed to exist by the spec's M1-007c / M1-035 scope-
 *           bootstrap path, but no production code path seeds it yet
 *           — the upsert is the defensive belt that keeps the FOR
 *           UPDATE lock semantically correct when the row would
 *           otherwise be missing.</li>
 *       <li>{@code SELECT tag_mode ... FOR UPDATE} locks the row for
 *           the rest of the transaction.</li>
 *       <li>{@code tag_mode='ALL'} → flip to {@code EXPLICIT}, INSERT
 *           the single followed tag ({@code I asked for X, only X}).
 *           Same UPDATE bumps {@code tag_subscription_version}.</li>
 *       <li>{@code tag_mode='EXPLICIT'} → INSERT the tag with
 *           {@code ON CONFLICT DO NOTHING} (idempotent add in place);
 *           a separate UPDATE bumps the version counter.</li>
 *     </ol></li>
 * </ol>
 *
 * <p>The handler writes zero rows to {@code audit_log} — tag-preference
 * mutations are user-preference, not privileged action (spec
 * §Authorization model). The {@code followTagWritesNoAuditRow}
 * scenario is the regression guard.</p>
 */
@ApplicationScoped
public class FollowTagCommandHandler implements CommandHandler {

    /** Max fuzzy-suggestion entries surfaced in the unknown-tag error. */
    private static final int FUZZY_SUGGESTION_MAX = 5;

    /**
     * Per the V5 {@code UNIQUE (adapter, contact_id)} constraint on
     * {@code users}, the lookup MUST qualify on both columns — a
     * {@code contact_id}-only WHERE would cross-resolve across
     * adapters in a multi-adapter deployment (decision D46).
     */
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

    private static final String INSERT_SCOPE_TAG_SQL =
            "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) VALUES (?, ?, ?) "
                    + "ON CONFLICT (scope_kind, scope_id, tag_id) DO NOTHING";

    // The ALL → EXPLICIT flip bumps both columns in one UPDATE.
    private static final String UPDATE_FLIP_TO_EXPLICIT_SQL =
            "UPDATE scope_preferences "
                    + "SET tag_mode = 'EXPLICIT', "
                    + "    tag_subscription_version = tag_subscription_version + 1 "
                    + "WHERE scope_kind = ? AND scope_id = ?";

    // EXPLICIT-mode add-in-place only needs to bump the version counter.
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
    GroupMembershipRepository groupMembershipRepository;

    @Override
    public String name() {
        return "follow-tag";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        // Permission gate: group scope requires group-admin.
        final String scopeKind;
        final UUID scopeId;
        if (scope instanceof ScopeRef.Group group) {
            Optional<UUID> actorId = lookupActorId(inboundContext.senderContactId());
            if (actorId.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_FOLLOW_TAG_GROUP_ADMIN_ONLY, inboundContext.effectiveLanguage()));
            }
            UUID groupDbId = lookupGroupId(group.adapterGroupId());
            if (groupDbId == null
                    || (!isBotAdmin(actorId.get())
                        && !groupMembershipRepository.isGroupAdmin(groupDbId, actorId.get()))) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_FOLLOW_TAG_GROUP_ADMIN_ONLY, inboundContext.effectiveLanguage()));
            }
            scopeKind = "group";
            scopeId = groupDbId;
        } else {
            ScopeRef.Dm dm = (ScopeRef.Dm) scope;
            Optional<UUID> actorId = lookupActorId(dm.contactId());
            if (actorId.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
            }
            scopeKind = "dm";
            scopeId = actorId.get();
        }

        String suppliedTag = parsePositionalTag(rawText);
        if (suppliedTag == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
        }

        // Apply the controlled-vocabulary pipeline (trim → NFC → lowercase
        // → char-class) before the lookup, so a case/Unicode variant of a
        // vocabulary tag resolves instead of silently missing the exact
        // WHERE name = ? match. An input that fails the char-class cannot
        // be in the (char-class-constrained) vocabulary, so it folds into
        // the same unknown-tag path. (M1-489)
        String normalizedTag = TagNormalizer.normalize(suppliedTag);
        Optional<UUID> tagId = TagNormalizer.isValid(normalizedTag)
                ? lookupTagId(normalizedTag)
                : Optional.empty();
        if (tagId.isEmpty()) {
            List<String> vocab = readVocabulary();
            List<String> suggestions = fuzzySuggest(normalizedTag, vocab, FUZZY_SUGGESTION_MAX);
            String body = MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_FOLLOW_TAG_UNKNOWN_TAG, inboundContext.effectiveLanguage()),
                    suppliedTag, String.join(", ", suggestions));
            return reply(scope, body);
        }

        return executeFollowTagTransaction(scope, scopeKind, scopeId, tagId.get(), normalizedTag);
    }

    private OutboundMessage executeFollowTagTransaction(ScopeRef scope,
                                                        String scopeKind,
                                                        UUID scopeId,
                                                        UUID tagIdValue,
                                                        String tagName) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Ensure the scope_preferences row exists before locking
                // it. ON CONFLICT DO NOTHING is a no-op when the row
                // exists (the bootstrap path's promise) and a fresh
                // insert otherwise — the SELECT FOR UPDATE then locks
                // the same row in either case.
                upsertScopePreferences(conn, scopeKind, scopeId);

                String mode = selectTagModeForUpdate(conn, scopeKind, scopeId);

                final String body;
                if ("ALL".equals(mode)) {
                    // ALL → EXPLICIT: flip mode + bump version (one
                    // UPDATE), then seed the single followed tag. The
                    // INSERT cannot collide on the PK because (mode=ALL,
                    // scope_tag rows present) is the forbidden state —
                    // the row could only exist if a prior writer raced
                    // past our FOR UPDATE lock, which Postgres prevents.
                    updateFlipToExplicit(conn, scopeKind, scopeId);
                    insertScopeTag(conn, scopeKind, scopeId, tagIdValue);
                    body = MessageFormat.format(
                            bundleLoader.get(BundleKeys.REPLY_FOLLOW_TAG_SUCCESS_FROM_ALL, inboundContext.effectiveLanguage()),
                            tagName);
                } else {
                    // EXPLICIT → EXPLICIT: add in place. ON CONFLICT DO
                    // NOTHING makes the call idempotent on a duplicate
                    // /follow-tag of the same tag.
                    insertScopeTag(conn, scopeKind, scopeId, tagIdValue);
                    updateBumpTagVersion(conn, scopeKind, scopeId);
                    body = MessageFormat.format(
                            bundleLoader.get(BundleKeys.REPLY_FOLLOW_TAG_SUCCESS_IN_PLACE, inboundContext.effectiveLanguage()),
                            tagName);
                }
                conn.commit();
                return reply(scope, body);
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "FollowTagCommandHandler.executeFollowTag failed for contact_id="
                                + ContactIds.redact(inboundContext.senderContactId()), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "FollowTagCommandHandler connection failed for contact_id="
                            + ContactIds.redact(inboundContext.senderContactId()), e);
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

    private void insertScopeTag(Connection conn, String scopeKind, UUID scopeId, UUID tagId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SCOPE_TAG_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, tagId);
            ps.executeUpdate();
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
                    "FollowTagCommandHandler.lookupActorId failed for contact_id="
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
                    "FollowTagCommandHandler.lookupGroupId failed", e);
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
                    "FollowTagCommandHandler.isBotAdmin failed", e);
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
                    "FollowTagCommandHandler.lookupTagId failed", e);
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
                    "FollowTagCommandHandler.readVocabulary failed", e);
        }
    }

    /**
     * Extract the positional tag from {@code rawText}. Returns
     * {@code null} when no positional arg appears (the router only
     * dispatches when the first whitespace-delimited token is
     * {@code /follow-tag}, so a body of length-one is the only
     * sub-case here).
     */
    private static @Nullable String parsePositionalTag(String rawText) {
        String[] parts = rawText.trim().split("\\s+", 2);
        if (parts.length < 2) {
            return null;
        }
        String remainder = parts[1].trim();
        if (remainder.isEmpty()) {
            return null;
        }
        // The first whitespace-delimited token after the command name
        // is the tag (the spec accepts one tag per invocation).
        return remainder.split("\\s+", 2)[0];
    }

    /**
     * Fuzzy-suggestion list over the controlled vocabulary, ordered by
     * shared-prefix length DESC then name ASC. Same naive shape the
     * M1-037 SummaryCommandHandler / EligiblePostQuery.fuzzySuggest
     * uses; a future ticket can swap in a real distance metric.
     */
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

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
