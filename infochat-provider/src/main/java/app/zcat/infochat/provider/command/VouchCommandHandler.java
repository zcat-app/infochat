package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
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
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Implements {@code /vouch <contact>} per
 * {@code docs/spec/security.md} §Slow-start tier ("A bot admin can
 * issue /vouch <contact> at any time to immediately graduate a
 * user from probation") and {@code docs/spec/commands.md}
 * §Admin (bot admin).
 *
 * <p>Dispatch sequence (acceptance items 9 + 10 in M1-045 + the
 * round-2 redteam-fix items for banned-target rejection,
 * actor-admin TOCTOU, and admin-gate-before-target-probe):
 * <ol>
 *   <li>Group-scope short-circuit — DM-only per M1-044c convention;
 *       group inbounds hit {@code error.admin_only} without opening
 *       a transaction.</li>
 *   <li>Parse the positional {@code <contact>} argument.</li>
 *   <li>Open one application-side transaction
 *       ({@code autoCommit=false}). All admin and target reads
 *       happen INSIDE this transaction.</li>
 *   <li>Admin gate INSIDE the tx via {@code SELECT ... FOR UPDATE}
 *       on the actor row — M1-045 redteam-fix round 2 closes both
 *       (a) the /revoke-admin TOCTOU OUT-OF-MODEL #2 (the row lock
 *       serializes concurrent demotes) AND (b) the round-2 INFO-LEAK
 *       where the prior round 2 ordering (target lookup before admin
 *       check) let a non-admin caller probe arbitrary target state
 *       via the 4-way reply discrimination (contact_not_registered
 *       vs vouch.banned_target vs vouch.noop vs admin_only). Admin
 *       check FIRST guarantees that every non-admin caller sees
 *       {@code error.admin_only} regardless of what target they
 *       named. Non-admin / unknown actor → ROLLBACK +
 *       {@code error.admin_only}, no audit row written, no target
 *       lookup runs.</li>
 *   <li>Resolve the target row by {@code (inbound_adapter,
 *       target_contact_id)} INSIDE the tx. No row →
 *       ROLLBACK + {@code error.contact_not_registered} per spec
 *       §Admin Unknown-contact rule.</li>
 *   <li>Banned-target rejection (M1-045 redteam-fix OUT-OF-MODEL
 *       #1) — when {@code target.is_banned = true}, ROLLBACK +
 *       {@code error.vouch.banned_target}. The audit log stays
 *       clean and the row's {@code registration_state} /
 *       {@code probation_until} columns are preserved verbatim for
 *       the operator's later {@code /unban} pass.</li>
 *   <li>No-op detection — when the target row is already past
 *       probation ({@code probation_until IS NULL OR
 *       probation_until <= NOW()}), the UPDATE would change nothing.
 *       ROLLBACK + reply {@code reply.vouch.noop}, write no audit
 *       row, matching the M1-036 / {@code /unban} pattern for
 *       in-effect no-ops.</li>
 *   <li>Happy path — PRE-WRITE the VOUCH audit row INSIDE the same
 *       transaction BEFORE the UPDATE (audit-before-effect,
 *       Invariant 7); the audit row's {@code details_json} carries
 *       {@code probation_cleared}. Run the UPDATE
 *       ({@code SET probation_until = NULL}). COMMIT. Reply
 *       {@code reply.vouch.success}.</li>
 * </ol>
 *
 * <p>Per D47 (schema §Identity and access) {@code /vouch} clears the
 * target's {@code probation_until} but no longer changes
 * {@code registration_state}: the UPDATE is a single-column probation
 * clear.
 */
@ApplicationScoped
public class VouchCommandHandler implements CommandHandler {

    private static final String SELECT_TARGET_SQL =
            "SELECT id, contact_id, probation_until, is_banned "
                    + "FROM users WHERE adapter = ? AND contact_id = ?";

    // Single-column probation clear per spec §Slow-start tier
    // /vouch <contact>: probation_until → NULL. Per D47 the command
    // no longer advances registration_state.
    private static final String UPDATE_VOUCH_SQL =
            "UPDATE users SET probation_until = NULL WHERE id = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "vouch";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        String adapter = inboundContext.adapterName();
        String callerContactId = contactIdOf(scope);

        // Group-scope inbound: callerContactId=null per the M1-044c
        // DM-only convention. Short-circuit with error.admin_only so
        // we do not open a transaction just to immediately roll it
        // back when lookupActorForUpdate(null) inside the tx returns
        // empty.
        if (callerContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }

        // Parse `<contact>`.
        VouchArgs args = VouchArgs.parse(rawText);
        if (args == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }
        String targetContactId = args.contact;

        // All authorization-sensitive reads + the audit/UPDATE run
        // inside ONE transaction. The admin gate is the FIRST read so
        // non-admin callers cannot probe arbitrary target state via
        // the 4-way reply discrimination (M1-045 redteam-fix round 2
        // INFO-LEAK closure). The /revoke-admin TOCTOU close (OUT-OF-
        // MODEL #2) is the FOR UPDATE on the actor row.
        String requestId = UUID.randomUUID().toString();
        String successReplyText = null;
        String outcomeBundleKey = null;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Optional<ActorRow> actorOpt =
                        lookupActorForUpdate(conn, adapter, callerContactId);
                if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
                }
                ActorRow actor = actorOpt.get();
                try (Statement st = conn.createStatement()) {
                    st.execute("SET LOCAL infochat.actor_id = '" + actor.id + "'");
                }

                Optional<TargetRow> targetOpt =
                        lookupTargetInTx(conn, adapter, targetContactId);
                if (targetOpt.isEmpty()) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED));
                }
                TargetRow target = targetOpt.get();

                if (target.isBanned) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_VOUCH_BANNED_TARGET));
                }

                if (isAlreadyPastProbation(target)) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.REPLY_VOUCH_NOOP));
                }

                insertVouchAudit(conn, actor, adapter, target.id, targetContactId,
                        requestId, vouchDetailsJson());

                updateUserVouched(conn, target.id);

                conn.commit();
                outcomeBundleKey = BundleKeys.REPLY_VOUCH_SUCCESS;
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "VouchCommandHandler.handle failed for adapter="
                                + adapter + " contact_id="
                                + ContactIds.redact(targetContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "VouchCommandHandler.handle connection failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(targetContactId), e);
        }

        return reply(scope, bundleLoader.get(outcomeBundleKey));
    }

    /**
     * True when the target row's {@code probation_until} is already
     * NULL or in the past (i.e. the user is no longer in probation
     * by the spec's permission predicate
     * {@code probation_until IS NULL OR probation_until < NOW()}).
     */
    private static boolean isAlreadyPastProbation(TargetRow row) {
        if (row.probationUntil == null) {
            return true;
        }
        return !row.probationUntil.isAfter(Instant.now());
    }

    private Optional<ActorRow> lookupActorForUpdate(Connection conn,
                                                    String adapter,
                                                    String contactId) throws SQLException {
        return userRepository.findByAdapterAndContactIdForUpdate(conn, adapter, contactId)
                .map(u -> new ActorRow(u.id(), u.contactId(), u.isAdmin()));
    }

    private Optional<TargetRow> lookupTargetInTx(Connection conn,
                                                 String adapter,
                                                 String contactId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_TARGET_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID id = (UUID) rs.getObject("id");
                String resolvedContactId = rs.getString("contact_id");
                Timestamp ts = rs.getTimestamp("probation_until");
                Instant probationUntil = ts == null ? null : ts.toInstant();
                boolean isBanned = rs.getBoolean("is_banned");
                return Optional.of(new TargetRow(id, resolvedContactId,
                        probationUntil, isBanned));
            }
        }
    }

    private void insertVouchAudit(Connection conn,
                                  ActorRow actor,
                                  String adapter,
                                  UUID targetId,
                                  String targetContactId,
                                  String requestId,
                                  String detailsJson) throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actor.id)
                .actorContactId(actor.contactId)
                .actorAdapter(adapter)
                .action(AuditAction.VOUCH)
                .targetKind("user")
                .targetId(targetId.toString())
                .targetContactId(targetContactId)
                .requestId(requestId)
                .detailsJson(detailsJson)
                .build();
        auditLogWriter.write(conn, row);
    }

    private void updateUserVouched(Connection conn, UUID targetId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_VOUCH_SQL)) {
            ps.setObject(1, targetId);
            ps.executeUpdate();
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private static @Nullable String contactIdOf(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm ? dm.contactId() : null;
    }

    /**
     * Build the VOUCH audit row's {@code details_json}. Carries the
     * boolean {@code probation_cleared} (always true on the happy
     * path — the UPDATE nulls probation_until). Per D47 the command
     * no longer advances registration_state, so no state-transition
     * fields are recorded.
     */
    private static String vouchDetailsJson() {
        return "{\"probation_cleared\":true}";
    }

    /** Target row state read INSIDE the transaction. */
    private record TargetRow(UUID id, String contactId,
                             @Nullable Instant probationUntil, boolean isBanned) {}

    /** Actor row state read INSIDE the transaction via SELECT FOR UPDATE. */
    private record ActorRow(UUID id, String contactId, boolean isAdmin) {}

    /**
     * Parsed form of {@code /vouch <contact>}. The {@code contact}
     * positional is required; no flags. Returns {@code null} when
     * the positional contact arg is missing.
     */
    record VouchArgs(String contact) {
        static @Nullable VouchArgs parse(String rawText) {
            String[] split = rawText.trim().split("\\s+", 3);
            if (split.length < 2 || split[1].isBlank()) {
                return null;
            }
            return new VouchArgs(split[1]);
        }
    }
}
