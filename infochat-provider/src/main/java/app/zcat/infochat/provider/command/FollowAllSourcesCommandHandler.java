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
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /follow-all-sources} per {@code docs/spec/commands.md}
 * §Source management — a bulk counterpart to {@code /add-source} that
 * subscribes the caller's scope to every currently-live source in one call.
 *
 * <p>Motivation: bootstrap seeding creates the global {@code source} rows but
 * no {@code source_subscription} rows, and {@code /summary} is filtered to the
 * scope's subscribed sources — so a freshly approved user's {@code /summary} is
 * empty until they follow feeds. Following them one-by-one via
 * {@code /add-source} is the only remedy today; this command removes that toil
 * while keeping subscription an explicit, per-scope opt-in (auto-subscribe at
 * registration is a separate default-feed policy decision, out of scope).</p>
 *
 * <p><b>Idempotent bulk write.</b> The subscribe is a single set-based
 * {@code INSERT ... SELECT id FROM source WHERE deleted_at IS NULL} with
 * {@code ON CONFLICT (scope_kind, scope_id, source_id) DO NOTHING}, so a re-run
 * inserts only the not-yet-followed sources and never duplicates. The JDBC
 * update-count is the number of rows actually inserted (Postgres excludes
 * ON-CONFLICT-skipped rows) — that IS the "newly subscribed" count; a follow-up
 * {@code COUNT(*)} over the scope gives the "total now followed". Both run in one
 * transaction so the two figures in the reply are a consistent snapshot.</p>
 *
 * <p><b>Permission model</b> mirrors {@code /add-source} and
 * {@link UnfollowSourceCommandHandler}: any registered caller in their own DM
 * scope; group admin (or bot admin) only for the group scope. A probation
 * caller is refused upstream by {@code InboundRouter}'s step-5 gate — this
 * command is deliberately absent from {@link CommandPermissions}'s
 * allowed-during-probation set — so the handler carries no probation check (that
 * would be dead defensive code). Banned callers likewise never reach here (the
 * router's step-4 ban check is authoritative), matching the sibling
 * {@code /unfollow-source} which also omits a handler ban check.</p>
 *
 * <p>Auto-discovered by {@code InboundRouter} via the CDI
 * {@code Instance<CommandHandler>} scan; dispatch keys on {@link #name()}, so no
 * router-side edit is needed. Thin-SQL "Shape B" handler: the two statements
 * need no repository class.</p>
 */
@ApplicationScoped
public class FollowAllSourcesCommandHandler implements CommandHandler {

    private static final String SELECT_USER_FLAGS_SQL =
            "SELECT id, is_admin FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_ID_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?";

    // Bulk subscribe the scope to every currently-live source in one round-trip.
    // deleted_at IS NULL is the live-source predicate (mirrors ListSourcesCommandHandler's
    // --all set); ON CONFLICT DO NOTHING makes the write idempotent so a re-run
    // never duplicates and only inserts the not-yet-followed sources.
    private static final String BULK_SUBSCRIBE_SQL =
            "INSERT INTO source_subscription (scope_kind, scope_id, source_id, added_by) "
                    + "SELECT ?, ?, id, ? FROM source WHERE deleted_at IS NULL "
                    + "ON CONFLICT (scope_kind, scope_id, source_id) DO NOTHING";

    private static final String COUNT_SUBSCRIPTIONS_SQL =
            "SELECT count(*) FROM source_subscription WHERE scope_kind = ? AND scope_id = ?";

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
        return "follow-all-sources";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        String adapter = inboundContext.adapterName();
        String callerContactId = scope instanceof ScopeRef.Dm dm
                ? dm.contactId() : inboundContext.senderContactId();
        Optional<UserRow> actorOpt = lookupUser(adapter, callerContactId);

        // Permission gate + scope resolution, mirroring /add-source and
        // /unfollow-source: DM subscribes the caller's own scope; group is
        // group-admin-or-bot-admin only.
        final String scopeKind;
        final UUID scopeId;
        if (scope instanceof ScopeRef.Group group) {
            if (actorOpt.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_FOLLOW_ALL_SOURCES_GROUP_ADMIN_ONLY, inboundContext.effectiveLanguage()));
            }
            UserRow actor = actorOpt.get();
            UUID groupDbId = lookupGroupId(adapter, group.adapterGroupId());
            if (groupDbId == null
                    || (!actor.isAdmin()
                        && !groupMembershipRepository.isGroupAdmin(groupDbId, actor.id()))) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_FOLLOW_ALL_SOURCES_GROUP_ADMIN_ONLY, inboundContext.effectiveLanguage()));
            }
            scopeKind = "group";
            scopeId = groupDbId;
        } else {
            if (actorOpt.isEmpty()) {
                // The router dispatches /follow-all-sources only for a registered
                // DM caller, so an empty lookup here is a wiring invariant
                // violation, not a user-facing case (mirrors /add-source and
                // /unfollow-source's DM branch).
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
            }
            scopeKind = "dm";
            scopeId = actorOpt.get().id();
        }
        UUID actorId = actorOpt.get().id();

        SubscribeResult result = bulkSubscribe(scopeKind, scopeId, actorId);
        String body = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_FOLLOW_ALL_SOURCES_DONE, inboundContext.effectiveLanguage()),
                result.newlySubscribed(), result.totalFollowed());
        return reply(scope, body);
    }

    private SubscribeResult bulkSubscribe(String scopeKind, UUID scopeId, UUID actorId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int newlySubscribed;
                try (PreparedStatement ps = conn.prepareStatement(BULK_SUBSCRIBE_SQL)) {
                    ps.setString(1, scopeKind);
                    ps.setObject(2, scopeId);
                    ps.setObject(3, actorId);
                    // Postgres returns the count of rows actually inserted,
                    // excluding ON-CONFLICT-skipped rows — the newly-subscribed total.
                    newlySubscribed = ps.executeUpdate();
                }
                long totalFollowed;
                try (PreparedStatement ps = conn.prepareStatement(COUNT_SUBSCRIPTIONS_SQL)) {
                    ps.setString(1, scopeKind);
                    ps.setObject(2, scopeId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        totalFollowed = rs.getLong(1);
                    }
                }
                conn.commit();
                return new SubscribeResult(newlySubscribed, totalFollowed);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "FollowAllSourcesCommandHandler.bulkSubscribe failed for scope_kind="
                            + scopeKind, e);
        }
    }

    private Optional<UserRow> lookupUser(@Nullable String adapter, @Nullable String contactId) {
        if (adapter == null || contactId == null) {
            return Optional.empty();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_USER_FLAGS_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserRow(
                        rs.getObject("id", UUID.class), rs.getBoolean("is_admin")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "FollowAllSourcesCommandHandler.lookupUser failed for contact_id="
                            + ContactIds.redact(contactId), e);
        }
    }

    private @Nullable UUID lookupGroupId(String adapter, String upstreamGroupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_ID_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "FollowAllSourcesCommandHandler.lookupGroupId failed", e);
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private record UserRow(UUID id, boolean isAdmin) {}

    private record SubscribeResult(int newlySubscribed, long totalFollowed) {}
}
