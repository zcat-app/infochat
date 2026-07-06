package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bot-admin-only, DM-only handler for {@code /pending}: the bounded list of
 * users an admin can act on right now — awaiting a vouch or still in slow-start
 * probation (D45) — each with the copy-pasteable {@code contact_id} that
 * {@code /vouch <contact>} and {@code /ban <contact>} accept.
 *
 * <p>Deliberately NOT a general {@code /list-users} roster (D55,
 * docs/spec/commands.md §Permission model): only the probation / awaiting-vouch
 * subset is exposed, and it is scoped to the inbound adapter so every id resolves
 * against the same {@code (adapter, contact_id)} key the action commands match on
 * — a cross-adapter id would not be dialable from this conversation.
 */
@ApplicationScoped
public class PendingCommandHandler implements CommandHandler {

    private static final Logger LOG = Logger.getLogger(PendingCommandHandler.class);

    private static final String USAGE = "/pending [--page N]";

    // Raw-string bundle keys (the QuarantineCommandHandler precedent) so the new
    // keys do not require a BundleKeys constant change outside this ticket's scope.
    private static final String REPLY_HEADER = "reply.pending.header";
    private static final String REPLY_LINE = "reply.pending.line";
    private static final String REPLY_EMPTY = "reply.pending.empty";
    private static final String NO_PROBATION_PLACEHOLDER = "-";

    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject PendingUsersDao pendingUsersDao;
    @Inject DataSource dataSource;
    @Inject AuditLogWriter auditLogWriter;

    // The probation cutoff ('now') gates which users count as "in probation" — a
    // decision on "now" — so it reads the injected Clock to stay pinnable in tests
    // (engineering-rules §9 / D45). The reply timestamps below only render.
    @Inject
    Clock clock = Clock.systemUTC();

    @Inject
    @ConfigProperty(name = "infochat.provider.pending.page-size", defaultValue = "20")
    int pageSize;

    @Override
    public String name() {
        return "pending";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        String lang = inboundContext.effectiveLanguage();
        if (!(scope instanceof ScopeRef.Dm dm)) {
            // ScopeRef is sealed to Dm | Group; a non-Dm scope is a Group, which
            // this DM-only admin command rejects (docs/design/03-commands.md §3.2).
            return reply(scope, bundleLoader.get("error.command_dm_only", lang));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = dm.contactId();
        Optional<PendingUsersDao.ActorRow> actorOpt = pendingUsersDao.lookupActor(adapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin()) {
            return reply(scope, bundleLoader.get("error.admin_only", lang));
        }
        PendingUsersDao.ActorRow actor = actorOpt.get();

        Integer page = parsePage(rawText);
        if (page == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get("error.usage.missing_argument", lang), USAGE));
        }

        // Audit-before-effect: record the privileged-read intent BEFORE the
        // disclosure below (§Authorization model step 8, D55), so a
        // probe-and-abandon still leaves a trail and an empty result is still
        // audited. Own short transaction; mirrors AuditCommandHandler's
        // AUDIT_READ write. target_kind USER + target_id "all" is the sentinel
        // for a deployment-wide user enumeration (cf. LIST_GROUPS group/all).
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                RedactionHook.AuditRow auditRow = RedactionHook.AuditRow.builder()
                        .actorUserId(actor.id())
                        .actorContactId(callerContactId)
                        .actorAdapter(adapter)
                        .action(AuditAction.PENDING_LIST)
                        .targetKind(TargetKind.USER)
                        .targetId("all")
                        .requestId(UUID.randomUUID().toString())
                        .detailsJson("{\"page\":" + page + "}")
                        .build();
                auditLogWriter.write(conn, auditRow);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOG.errorf(e, "/pending audit write failed");
            return reply(scope, bundleLoader.get("error.internal", lang));
        }

        Instant now = clock.instant();
        try {
            long total = pendingUsersDao.countActionable(adapter, now);
            if (total == 0) {
                return reply(scope, bundleLoader.get(REPLY_EMPTY, lang));
            }
            int totalPages = (int) Math.ceil((double) total / pageSize);
            int effectivePage = Math.min(page, totalPages);

            List<PendingUsersDao.PendingUser> rows = pendingUsersDao.listActionable(
                    adapter, now, pageSize, (effectivePage - 1) * pageSize);

            StringBuilder sb = new StringBuilder();
            sb.append(MessageFormat.format(bundleLoader.get(REPLY_HEADER, lang),
                    String.valueOf(rows.size()),
                    String.valueOf(effectivePage),
                    String.valueOf(totalPages)));
            String lineTemplate = bundleLoader.get(REPLY_LINE, lang);
            for (PendingUsersDao.PendingUser u : rows) {
                sb.append('\n');
                sb.append(MessageFormat.format(lineTemplate,
                        u.contactId(),
                        u.adapter(),
                        u.registrationState(),
                        u.createdAt().toInstant().toString(),
                        u.probationUntil() != null
                                ? u.probationUntil().toInstant().toString()
                                : NO_PROBATION_PLACEHOLDER));
            }
            return reply(scope, sb.toString());
        } catch (SQLException e) {
            LOG.errorf(e, "/pending query failed");
            return reply(scope, bundleLoader.get("error.internal", lang));
        }
    }

    // A malformed --page value returns null (the parse-failure marker) so the
    // handler renders the usage error, matching AuditArgs / BanArgs rather than
    // silently falling back to page 1 (M1-343 convention).
    private static @Nullable Integer parsePage(String rawText) {
        List<String> tokens = CommandTokenizer.tokenize(rawText);
        int page = 1;
        int i = 1; // skip the command-name token
        while (i < tokens.size()) {
            String tok = tokens.get(i);
            if (tok.equals("--page") && i + 1 < tokens.size()) {
                Integer parsed = parsePositiveInt(tokens.get(i + 1));
                if (parsed == null) {
                    return null;
                }
                page = parsed;
                i += 2;
            } else if (tok.startsWith("--page=")) {
                Integer parsed = parsePositiveInt(tok.substring("--page=".length()));
                if (parsed == null) {
                    return null;
                }
                page = parsed;
                i++;
            } else {
                i++;
            }
        }
        return page;
    }

    private static @Nullable Integer parsePositiveInt(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
