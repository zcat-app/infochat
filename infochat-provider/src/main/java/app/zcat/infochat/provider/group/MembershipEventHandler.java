package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.MembershipEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Provider-side handler for adapter membership events. Wired to each
 * activated adapter by {@code AdapterRegistry} via
 * {@code setMembershipEventHandler}. Processes {@code UserLeft} and
 * {@code BotRemoved}; other event types are logged and ignored per
 * M1-084 scope.
 */
@ApplicationScoped
public class MembershipEventHandler {

    private static final Logger log = LoggerFactory.getLogger(MembershipEventHandler.class);

    private static final String SELECT_GROUP_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    private static final String SELECT_USER_SQL =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private final DataSource dataSource;
    private final GroupMembershipRepository membershipRepository;
    private final GroupRepository groupRepository;
    private final AuditLogWriter auditLogWriter;

    @Inject
    public MembershipEventHandler(@NonNull DataSource dataSource,
                                  @NonNull GroupMembershipRepository membershipRepository,
                                  @NonNull GroupRepository groupRepository,
                                  @NonNull AuditLogWriter auditLogWriter) {
        this.dataSource = dataSource;
        this.membershipRepository = membershipRepository;
        this.groupRepository = groupRepository;
        this.auditLogWriter = auditLogWriter;
    }

    /**
     * Dispatch a membership event from the given adapter. Called by
     * the lambda wired in {@code AdapterRegistry.start()}.
     */
    public void handle(@NonNull MembershipEvent event, @NonNull String adapter) {
        switch (event) {
            case MembershipEvent.UserLeft left -> handleUserLeft(left, adapter);
            case MembershipEvent.BotRemoved removed -> handleBotRemoved(removed, adapter);
            default -> log.debug("ignoring unhandled membership event type: {}", event.getClass().getSimpleName());
        }
    }

    private void handleUserLeft(MembershipEvent.UserLeft event, String adapter) {
        UUID groupId = resolveGroup(adapter, event.adapterGroupId());
        if (groupId == null) {
            log.warn("UserLeft: unknown group adapter={} groupId={}", adapter, event.adapterGroupId());
            return;
        }
        UUID userId = resolveUser(adapter, event.contactId());
        if (userId == null) {
            log.warn("UserLeft: unknown user adapter={} contactId={}",
                    adapter, ContactIds.redact(event.contactId()));
            return;
        }
        // One transaction, audit row INSERTed BEFORE the mutation per
        // Invariant 7 (audit-before-effect, the BanCommandHandler
        // pattern): an audit failure rolls the mutation back, so the
        // was_group_admin flag is never silently lost.
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Check admin status before markMemberRemoved — the V5
                // trigger clears is_group_admin during the UPDATE, so
                // querying after would always return false.
                boolean wasGroupAdmin = membershipRepository.isGroupAdmin(conn, groupId, userId);
                writeAudit(conn, AuditAction.MEMBER_LEFT, userId, event.contactId(), adapter,
                        "user", userId.toString(), groupId,
                        "{\"was_group_admin\":" + wasGroupAdmin + "}");
                membershipRepository.markMemberRemoved(conn, groupId, userId);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw sanitizedFailure("UserLeft transaction failed group=" + groupId
                        + " user=" + userId, e);
            }
        } catch (SQLException e) {
            throw sanitizedFailure("UserLeft connection failed group=" + groupId
                    + " user=" + userId, e);
        }
        log.info("UserLeft: marked member removed group={} user={}", groupId, userId);
    }

    private void handleBotRemoved(MembershipEvent.BotRemoved event, String adapter) {
        UUID groupId = resolveGroup(adapter, event.adapterGroupId());
        if (groupId == null) {
            log.warn("BotRemoved: unknown group adapter={} groupId={}", adapter, event.adapterGroupId());
            return;
        }
        // One transaction, audit row INSERTed BEFORE the mutation per
        // Invariant 7 (audit-before-effect).
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // System-actor row: no user caused this — the platform removed the bot.
                writeAudit(conn, AuditAction.BOT_REMOVED, null, null, adapter,
                        "group", groupId.toString(), groupId, null);
                groupRepository.markRemoved(conn, groupId);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw sanitizedFailure("BotRemoved transaction failed group=" + groupId, e);
            }
        } catch (SQLException e) {
            throw sanitizedFailure("BotRemoved connection failed group=" + groupId, e);
        }
        log.info("BotRemoved: marked group removed group={}", groupId);
    }

    private void writeAudit(Connection conn, AuditAction action, @Nullable UUID actorUserId,
                            @Nullable String actorContactId, String actorAdapter,
                            String targetKind, String targetId,
                            UUID scopeId, @Nullable String detailsJson) throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actorUserId)
                .actorContactId(actorContactId)
                .actorAdapter(actorAdapter)
                .action(action)
                .targetKind(targetKind)
                .targetId(targetId)
                .scopeId(scopeId)
                .requestId(UUID.randomUUID().toString())
                .detailsJson(detailsJson)
                .build();
        auditLogWriter.write(conn, row);
    }

    // SQLException messages can echo bound values — a Postgres
    // constraint-violation DETAIL line carries the inserted tuple,
    // i.e. the audit row's unredacted contact id and details_json.
    // Per security.md §User content in exceptions the failure
    // propagates with the exception class name only (the SafeLog
    // convention): no cause, no message body. This also covers a
    // rollback() failure — the outer catch sanitizes it the same way.
    private static IllegalStateException sanitizedFailure(String context, SQLException e) {
        return new IllegalStateException(context + " | exception=" + e.getClass().getName());
    }

    private @Nullable UUID resolveGroup(String adapter, String adapterGroupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, adapterGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("resolveGroup failed", e);
        }
    }

    private @Nullable UUID resolveUser(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_USER_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("resolveUser failed", e);
        }
    }
}
