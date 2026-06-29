package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.MembershipEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    // Field-injected (the GroupRepository.auditLogWriter seam) so the existing
    // 4-arg constructor — used by the hand-constructed failing-writer test
    // doubles — stays unchanged. Non-null in the container; the only test that
    // hand-constructs the handler AND reaches handleBotRemoved sets this field
    // explicitly. handleUserLeft never touches it, so the UserLeft-only doubles
    // may leave it null. (M1-525)
    @Inject
    GroupJoinRepository joinRepository;

    @Inject
    public MembershipEventHandler(DataSource dataSource,
                                  GroupMembershipRepository membershipRepository,
                                  GroupRepository groupRepository,
                                  AuditLogWriter auditLogWriter) {
        this.dataSource = dataSource;
        this.membershipRepository = membershipRepository;
        this.groupRepository = groupRepository;
        this.auditLogWriter = auditLogWriter;
    }

    /**
     * Dispatch a membership event from the given adapter. Called by
     * the lambda wired in {@code AdapterRegistry.start()}.
     */
    public void handle(MembershipEvent event, String adapter) {
        switch (event) {
            case MembershipEvent.UserLeft left -> handleUserLeft(left, adapter);
            case MembershipEvent.BotRemoved removed -> handleBotRemoved(removed, adapter);
            default -> log.debug("ignoring unhandled membership event type: {}", event.getClass().getSimpleName());
        }
    }

    private void handleUserLeft(MembershipEvent.UserLeft event, String adapter) {
        UUID groupId = resolveGroup(adapter, event.adapterGroupId());
        if (groupId == null) {
            log.warn("UserLeft: unknown group adapter={} groupId={}",
                    adapter, ContactIds.redact(event.adapterGroupId()));
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
                // Read admin status before markMemberRemoved — the V5
                // trigger clears is_group_admin during the UPDATE, so
                // querying after would always return false. The FOR UPDATE
                // row lock is held until this transaction's commit, so a
                // concurrent /promote or /demote cannot invalidate the
                // audited was_group_admin value between read and UPDATE.
                GroupMembershipRepository.MembershipState state =
                        membershipRepository.lockMembership(conn, groupId, userId);
                if (state == null || state.removed()) {
                    // Verified no-op against current state: the row is
                    // absent or already removed, so the event implies no
                    // mutation — skip the audit row too. Leave events are
                    // attacker-repeatable; minting one MEMBER_LEFT row per
                    // repeat would grow audit_log without bound (the
                    // security.md §Invite-code registration suppression
                    // principle). Invariant 7 holds: no mutation, no audit
                    // duty.
                    conn.rollback();
                    log.debug("UserLeft: no-op, membership absent or already removed group={} user={}",
                            groupId, userId);
                    return;
                }
                writeAudit(conn, AuditAction.MEMBER_LEFT, userId, event.contactId(), adapter,
                        TargetKind.USER, userId.toString(), groupId,
                        "{\"was_group_admin\":" + state.groupAdmin() + "}");
                membershipRepository.markMemberRemoved(conn, groupId, userId);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                // Operator signal for a stranded removal: a member that
                // failed to be marked removed can be a phantom admin
                // occupying the one_admin_per_group slot. UUIDs only;
                // SafeLog drops the SQLException message body.
                SafeLog.error(log, "UserLeft transaction failed group=" + groupId
                        + " user=" + userId, e);
                throw sanitizedFailure("UserLeft transaction failed group=" + groupId
                        + " user=" + userId, e);
            }
        } catch (SQLException e) {
            SafeLog.error(log, "UserLeft connection failed group=" + groupId
                    + " user=" + userId, e);
            throw sanitizedFailure("UserLeft connection failed group=" + groupId
                    + " user=" + userId, e);
        }
        log.info("UserLeft: marked member removed group={} user={}", groupId, userId);
    }

    private void handleBotRemoved(MembershipEvent.BotRemoved event, String adapter) {
        // Free the auto_joined_group slot first, keyed by the natural key the
        // event carries (M1-525). This is the ONLY freeing point for a join-only
        // group — one auto-joined by invite but never @mentioned, so it has no
        // groups row — and so it must run even when resolveGroup returns null.
        // Unaudited, mirroring the unaudited tryRecordJoin INSERT on this same
        // table; idempotent via the removed_at IS NULL guard. Its own
        // transaction: the slot-free is the desired end state regardless of the
        // groups-row audit outcome below.
        boolean slotFreed = joinRepository.markRemovedByNaturalKey(adapter, event.adapterGroupId());

        UUID groupId = resolveGroup(adapter, event.adapterGroupId());
        if (groupId == null) {
            // No groups row: either a pure join-only group (slot just freed) or
            // a genuinely unknown group. Emit the redacted unknown-group WARN
            // only when nothing was freed; a real join-only free is INFO.
            if (slotFreed) {
                log.info("BotRemoved: freed join-only auto-join slot adapter={} groupId={}",
                        adapter, ContactIds.redact(event.adapterGroupId()));
            } else {
                log.warn("BotRemoved: unknown group adapter={} groupId={}",
                        adapter, ContactIds.redact(event.adapterGroupId()));
            }
            return;
        }
        // One transaction, audit row INSERTed BEFORE the mutation per
        // Invariant 7 (audit-before-effect).
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // System-actor row: no user caused this — the platform removed the bot.
                writeAudit(conn, AuditAction.BOT_REMOVED, null, null, adapter,
                        TargetKind.GROUP, groupId.toString(), groupId, null);
                groupRepository.markRemoved(conn, groupId);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                // Same operator-signal convention as UserLeft: UUIDs only,
                // SafeLog drops the SQLException message body.
                SafeLog.error(log, "BotRemoved transaction failed group=" + groupId, e);
                throw sanitizedFailure("BotRemoved transaction failed group=" + groupId, e);
            }
        } catch (SQLException e) {
            SafeLog.error(log, "BotRemoved connection failed group=" + groupId, e);
            throw sanitizedFailure("BotRemoved connection failed group=" + groupId, e);
        }
        log.info("BotRemoved: marked group removed group={}", groupId);
    }

    private void writeAudit(Connection conn, AuditAction action, @Nullable UUID actorUserId,
                            @Nullable String actorContactId, String actorAdapter,
                            TargetKind targetKind, String targetId,
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
