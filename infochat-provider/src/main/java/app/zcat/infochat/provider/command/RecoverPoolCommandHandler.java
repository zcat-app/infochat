package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupJoinRepository;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /recover-pool} — the bot-admin in-band recovery of the
 * {@code auto_joined_group} pool (M1-526, remediates the M1-519 redteam
 * Finding 2 that the D47 caps were otherwise only recoverable via operator
 * psql under the DB owner role).
 *
 * <p>Two forms, dispatched on argument count:
 * <ul>
 *   <li>{@code /recover-pool} (no arg) — LIST the active (non-freed) pool so
 *       the admin can read off the natural key of the residual to free. This
 *       is the only way to surface a join-only SimpleX group that has no
 *       {@code groups} row (so {@code /list-groups} cannot show it) and no
 *       native leave signal (so M1-525's automatic freeing never fires).</li>
 *   <li>{@code /recover-pool <adapter> <upstream-group-id>} — FREE one slot by
 *       its natural key, setting {@code removed_at} so it stops counting
 *       against the D47 caps. Reuses M1-525's natural-key freeing; the
 *       provider role already holds {@code UPDATE (removed_at, ...)} from V56,
 *       so no new GRANT or migration is needed (DELETE stays revoked).</li>
 * </ul>
 *
 * <p>Authorization is a deterministic bot-admin ({@code user.is_admin}) check
 * in Java — NEVER exposed as an LLM tool — and the command is DM-only (a group
 * reply would leak deployment-wide membership state to every member). The
 * admin gate runs before any DB access, so a non-admin or group-scope
 * invocation touches no row. The free runs the M1-046 in-transaction
 * {@code SELECT ... FOR UPDATE} actor re-check (TOCTOU closure) and writes one
 * {@code RECOVER_AUTO_JOINED_GROUP} audit row audit-before-effect in the SAME
 * transaction as the {@code removed_at} UPDATE; a no-op free (unknown or
 * already-freed) rolls the transaction back, so only a successful free leaves
 * a trail. The free is single-step (not confirm-gated): the effect is
 * low-stakes and reversible — a later re-join reactivates the slot per V56's
 * {@code ON CONFLICT} reactivation — so there is no {@code _INTENT} verb.
 */
@ApplicationScoped
public class RecoverPoolCommandHandler implements CommandHandler {

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

    @Inject
    GroupJoinRepository groupJoinRepository;

    @Override
    public String name() {
        return "recover-pool";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        String lang = inboundContext.effectiveLanguage();
        // DM-only: both the pool listing and the free expose deployment-wide
        // membership state, so a group reply would leak it to every member.
        // Matching ScopeRef.Dm also yields the non-null caller contact id.
        if (!(scope instanceof ScopeRef.Dm dm)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY, lang));
        }
        String inboundAdapter = inboundContext.adapterName();
        String callerContactId = dm.contactId();

        // Admin gate — deterministic bot-admin check BEFORE any DB access, so a
        // non-admin invocation reads no pool and writes no removed_at (acceptance
        // item 1). The free path re-checks under a row lock below.
        Optional<UserRow> actorOpt = lookupUser(inboundAdapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY, lang));
        }

        List<String> tokens = parseArgs(rawText);
        if (tokens.isEmpty()) {
            return listMode(scope, lang);
        }
        if (tokens.size() != 2) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT, lang),
                    "/recover-pool [<adapter> <upstream-group-id>]"));
        }
        return freeMode(scope, inboundAdapter, callerContactId, tokens.get(0), tokens.get(1), lang);
    }

    private OutboundMessage listMode(ScopeRef scope, String lang) {
        List<GroupJoinRepository.ActivePoolEntry> pool = groupJoinRepository.listActivePool();
        if (pool.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_RECOVER_POOL_EMPTY, lang));
        }
        StringBuilder body = new StringBuilder(MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_RECOVER_POOL_HEADER, lang),
                Integer.toString(pool.size())));
        String lineTemplate = bundleLoader.get(BundleKeys.REPLY_RECOVER_POOL_LINE, lang);
        for (GroupJoinRepository.ActivePoolEntry entry : pool) {
            body.append('\n').append(MessageFormat.format(lineTemplate,
                    entry.adapter(),
                    entry.upstreamGroupId(),
                    entry.inviterUserId().toString(),
                    entry.joinedAt().toString()));
        }
        return reply(scope, body.toString());
    }

    private OutboundMessage freeMode(ScopeRef scope, String inboundAdapter,
                                     String callerContactId, String targetAdapter,
                                     String upstreamGroupId, String lang) {
        String requestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // In-tx admin gate — SELECT ... FOR UPDATE on the actor row,
                // authoritative for execution: a concurrent /revoke-admin against
                // the caller serializes on the row lock, so this read reflects the
                // post-revoke state (the GrantAdmin/ApproveGroup M1-046 closure).
                Optional<UserRow> actorOpt =
                        lookupUserForUpdate(conn, inboundAdapter, callerContactId);
                if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY, lang));
                }
                UserRow actor = actorOpt.get();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT set_config('infochat.actor_id', ?, true)")) {
                    ps.setString(1, actor.id.toString());
                    ps.execute();
                }

                // Audit-before-effect (Invariant 7): pre-write the RECOVER row,
                // then run the free. A no-op free (no matching non-removed row)
                // rolls back below, discarding this row — so only a successful
                // free leaves an audit trail.
                insertAudit(conn, actor, inboundAdapter, targetAdapter, upstreamGroupId, requestId);
                boolean freed = groupJoinRepository.markRemovedByNaturalKey(
                        conn, targetAdapter, upstreamGroupId);
                if (!freed) {
                    conn.rollback();
                    return reply(scope, MessageFormat.format(
                            bundleLoader.get(BundleKeys.ERROR_RECOVER_POOL_NOT_FOUND, lang),
                            targetAdapter, upstreamGroupId));
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "RecoverPoolCommandHandler.freeMode failed for adapter="
                                + targetAdapter + " upstreamGroupId=" + upstreamGroupId, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "RecoverPoolCommandHandler.freeMode connection failed for adapter="
                            + targetAdapter + " upstreamGroupId=" + upstreamGroupId, e);
        }

        return reply(scope, MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_RECOVER_POOL_FREED, lang),
                targetAdapter, upstreamGroupId));
    }

    private void insertAudit(Connection conn, UserRow actor, String inboundAdapter,
                             String targetAdapter, String upstreamGroupId,
                             String requestId) throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actor.id)
                .actorContactId(actor.contactId)
                .actorAdapter(inboundAdapter)
                .action(AuditAction.RECOVER_AUTO_JOINED_GROUP)
                .targetKind(TargetKind.GROUP)
                .targetId(upstreamGroupId)
                .targetContactId(null)
                .requestId(requestId)
                .detailsJson(detailsJson(targetAdapter, upstreamGroupId))
                .build();
        auditLogWriter.write(conn, row);
    }

    private Optional<UserRow> lookupUser(String adapter, String contactId) {
        return userRepository.findByAdapterAndContactId(adapter, contactId)
                .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin()));
    }

    private Optional<UserRow> lookupUserForUpdate(Connection conn, String adapter,
                                                  String contactId) throws SQLException {
        return userRepository.findByAdapterAndContactIdForUpdate(conn, adapter, contactId)
                .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin()));
    }

    /**
     * The audit row's {@code details_json} records the freed slot's natural key.
     * {@code target_adapter} is the adapter of the freed group, which may differ
     * from the inbound (actor) adapter — a Signal admin can free a SimpleX
     * residual — and {@code target_id} alone (the upstream group id) is not
     * unique across adapters, so the adapter rides in the details.
     */
    private static String detailsJson(String targetAdapter, String upstreamGroupId) {
        return "{\"target_adapter\":" + quoteJsonString(targetAdapter)
                + ",\"upstream_group_id\":" + quoteJsonString(upstreamGroupId) + "}";
    }

    private static String quoteJsonString(String s) {
        return "\"" + JsonEscaper.escape(s) + "\"";
    }

    private static List<String> parseArgs(String rawText) {
        String[] split = rawText.trim().split("\\s+", 2);
        String remainder = split.length > 1 ? split[1].trim() : "";
        return CommandTokenizer.tokenize(remainder);
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    /** Minimal in-memory representation of the actor users row the gate needs. */
    private record UserRow(UUID id, String contactId, boolean isAdmin) {}
}
