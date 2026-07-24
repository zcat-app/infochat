package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implements {@code /invite create / list / revoke / bot-contact /
 * pending-contacts} per {@code docs/spec/security.md} §Invite-code
 * registration and {@code docs/spec/commands.md} §Admin (bot admin).
 *
 * <p>The handler dispatches on the first whitespace-delimited
 * subcommand token to a {@code "create"} / {@code "list"} /
 * {@code "revoke"} / {@code "bot-contact"} / {@code "pending-contacts"}
 * branch; any other token →
 * {@code error.invite.unknown_subcommand} (acceptance item 16). The
 * mutating branches execute
 * the audit-before-effect transaction shape that
 * {@link BanCommandHandler} pins for {@code /ban}: the audit row
 * INSERT runs FIRST inside the same transaction as the state
 * mutation; a roll-back leaves no audit-vs-state divergence
 * (Invariant 7).</p>
 *
 * <p><b>Cross-adapter create.</b> {@code /invite create} reads the
 * target adapter from the {@code --adapter <name>} flag, NOT from
 * {@link InboundContext#adapterName()}. The inbound adapter (where
 * the bot admin ran the command) is the {@code actor_adapter} on the
 * audit row; the target adapter (the flag value) is the
 * {@code invite_code.adapter} column and the cap-query scope. Per spec
 * §Invite-code registration this is "the one admin command that may
 * name any adapter the deployment supports."</p>
 *
 * <p><b>Confirm gate (M1-051).</b> {@code /invite create --open} and
 * {@code /invite revoke} both pass through the {@link ConfirmStateService}
 * pre-dispatch confirm gate per spec §Surface conventions. First call:
 * validate inputs, store pending args under
 * {@code (actor.id, scope, "invite:create:open"|"invite:revoke")},
 * return the prompt. Second call (body ends with {@code " confirm"}):
 * {@code takeMatching} on the same key — non-empty pops the captured
 * args and runs the M1-044c transaction unchanged; empty returns
 * {@code error.confirm.no_pending}. {@code /invite create --contact}
 * is intentionally excluded from the gate (spec §Admin: "risk is
 * bounded to one specific identity").</p>
 */
@ApplicationScoped
public class InviteCommandHandler implements CommandHandler {

    // The open-cap query is scoped to one adapter per spec §Invite-code
    // registration ("per-adapter open cap"). The expires_at filter
    // matches the spec's active-PENDING definition — codes whose TTL
    // has passed do NOT count toward the cap. The cutoff is bound from
    // the injected Clock (not SQL NOW()) so the cap gate shares one clock
    // with the expires_at write (§9 / M1-490).
    private static final String COUNT_OPEN_PENDING_PER_ADAPTER_SQL =
            "SELECT count(*) FROM invite_code "
                    + "WHERE invite_type = 'OPEN_ADAPTER' "
                    + "  AND status = 'PENDING' "
                    + "  AND adapter = ? "
                    + "  AND (expires_at IS NULL OR expires_at > ?)";

    // The contact-cap query is global across adapters per spec
    // §Invite-code registration ("global --contact cap"). expires_at
    // cutoff bound from the injected Clock, as above.
    private static final String COUNT_CONTACT_PENDING_GLOBAL_SQL =
            "SELECT count(*) FROM invite_code "
                    + "WHERE invite_type = 'CONTACT_BOUND' "
                    + "  AND status = 'PENDING' "
                    + "  AND (expires_at IS NULL OR expires_at > ?)";

    // Pre-mint the code via a separate SELECT gen_random_uuid() so the
    // audit row can be written FIRST (audit-before-effect, Invariant 7)
    // with the code as its target_id, and the mint below can then run
    // with the same code passed as a JDBC bind parameter.
    // pgcrypto's gen_random_uuid() (cryptographically secure RNG)
    // remains the code source — the spec asks for it explicitly.
    private static final String SELECT_NEW_CODE_SQL =
            "SELECT gen_random_uuid() AS code";

    // Lives in the V62 SECURITY DEFINER routine mint_invite_code:
    // infochat_provider holds no INSERT on invite_code at all. The
    // routine resolves its actor from the infochat.actor_id GUC, which
    // this handler sets per transaction (M1-672).
    private static final String INSERT_INVITE_SQL =
            "SELECT mint_invite_code(?, ?, ?, ?, ?, ?)";

    // Active-PENDING filter (status='PENDING' AND not expired). Sorts
    // by created_at DESC; limit + offset support `--page N` paging.
    private static final String SELECT_PENDING_LIST_SQL =
            "SELECT code, invite_type, adapter, expected_contact_id, expires_at "
                    + "FROM invite_code "
                    + "WHERE status = 'PENDING' "
                    + "  AND (expires_at IS NULL OR expires_at > NOW()) "
                    + "ORDER BY created_at DESC "
                    + "LIMIT ? OFFSET ?";

    // The pending-contacts roster (M1-633, D60): distinct connected-but-
    // unregistered contacts on ONE adapter. invite_code_attempt is
    // append-per-rejected-attempt (V12: no PK, one row per bounce), so
    // GROUP BY collapses repeat knockers to one line with their most
    // recent attempt. "Unregistered" = no users row for the
    // (adapter, contact_id) key — a contact who registered (or was
    // pre-banned, which also creates a row) is no longer an onboarding
    // candidate. Most-recent first so the person the admin was just
    // pinged about heads the list; LIMIT/OFFSET pages like /invite list.
    private static final String SELECT_PENDING_CONTACTS_SQL =
            "SELECT contact_id, max(attempted_at) AS last_attempted_at "
                    + "FROM invite_code_attempt ica "
                    + "WHERE ica.adapter = ? "
                    + "  AND NOT EXISTS (SELECT 1 FROM users u "
                    + "      WHERE u.adapter = ica.adapter AND u.contact_id = ica.contact_id) "
                    + "GROUP BY contact_id "
                    + "ORDER BY last_attempted_at DESC "
                    + "LIMIT ? OFFSET ?";

    // Two routines, not one. The lock still has to happen before the
    // audit INSERT (so the pre-written row cannot reference an invite
    // another transaction flips out from under it) and the revoke after
    // it, so folding them together would put the effect before the audit
    // — Invariant 7. The lock moved into a routine at all because
    // revoking the table-level UPDATE on invite_code also revokes plain
    // FOR UPDATE reads on it (M1-672).
    private static final String SELECT_INVITE_FOR_REVOKE_SQL =
            "SELECT lock_pending_invite(?)";

    private static final String UPDATE_INVITE_REVOKED_SQL =
            "SELECT revoke_invite_code(?)";

    private static final int PAGE_SIZE = 20;

    @ConfigProperty(name = "infochat.invite.ttl", defaultValue = "7d")
    Duration inviteTtl;

    @ConfigProperty(name = "infochat.invite.open-cap-per-adapter", defaultValue = "3")
    int openCap;

    @ConfigProperty(name = "infochat.invite.contact-cap-global", defaultValue = "50")
    int contactCap;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    AdapterRegistry adapterRegistry;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    ConfirmStateService confirmStateService;

    @Inject
    UserRepository userRepository;

    // expires_at is written from this injected Clock and the open/contact cap
    // counts compare expires_at against the same Clock (bound param, not SQL
    // NOW()), so the invite-expiry cap gate reads and writes one clock — the
    // app-vs-DB split §9 prohibits, made pinnable in InviteExpiryClockIT. The
    // systemUTC() initializer keeps the field non-null for any hand-constructed
    // instance; CDI injection overrides it in the managed bean. The consume-time
    // expires_at > NOW() check in InviteCodeConsumer and the /invite list display
    // filter intentionally stay on the DB clock — see docs/plan/m1/now-clock-audit.md.
    // (M1-490, pattern from M1-444 ReEvaluationJob)
    @Inject
    Clock clock = Clock.systemUTC();

    @Override
    public String name() {
        return "invite";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        // Bot-global admin command: DM-only. A group-scope reply is
        // visible to every member, and /invite create & /invite list
        // disclose single-use invite codes verbatim. Return the accurate
        // scope error before resolving the caller — matching the guard in
        // the other bot-global admin handlers (Audit, Quarantine, etc.).
        if (scope instanceof ScopeRef.Group) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY, inboundContext.effectiveLanguage()));
        }
        String inboundAdapter = inboundContext.adapterName();
        String callerContactId = contactIdOf(scope);

        // Step 1 — admin gate. Resolved by the inbound adapter regardless
        // of which --adapter the command targets (the actor identity is
        // per-inbound; the target adapter is a flag).
        Optional<UserRow> actorOpt = lookupUser(inboundAdapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY, inboundContext.effectiveLanguage()));
        }
        UserRow actor = actorOpt.get();

        // Step 2 — dispatch on the first subcommand token.
        String[] split = rawText.trim().split("\\s+", 3);
        String subcommand = split.length > 1 ? split[1].toLowerCase(java.util.Locale.ROOT) : "";
        String remainder = split.length > 2 ? split[2] : "";

        return switch (subcommand) {
            case "create" -> handleCreate(scope, actor, inboundAdapter, remainder);
            case "list" -> handleList(scope, remainder);
            case "revoke" -> handleRevoke(scope, actor, inboundAdapter, remainder);
            case "bot-contact" -> handleBotContact(scope, inboundAdapter, remainder);
            case "pending-contacts" -> handlePendingContacts(scope, actor, inboundAdapter, remainder);
            default -> reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_UNKNOWN_SUBCOMMAND, inboundContext.effectiveLanguage()));
        };
    }

    // ------------------------------------------------------------------
    // /invite pending-contacts [--page N]
    // ------------------------------------------------------------------

    /**
     * Lists connected-but-unregistered contacts on the inbound adapter
     * with their FULL contact ids (M1-633, D60): the in-band sourcing
     * surface that makes {@code /invite create --contact} usable — the
     * id only exists after the person connects, and the intake bounce
     * that wrote their {@code invite_code_attempt} row is the one
     * signal the deployment has. Read-only, DM-only, bot-admin-only
     * (both gates ran in {@link #handle} before dispatch), scoped to
     * the inbound adapter so every listed id resolves against the same
     * {@code (adapter, contact_id)} key create matches on (the D55
     * {@code /pending} posture).
     *
     * <p>The contact ids are deliberately NOT {@link ContactIds#redact}'d
     * — the whole point is a copy-pasteable id. The disclosure and its
     * bounds are recorded in {@code docs/spec/security.md} §Invite-code
     * registration; the audit-before-effect row below is its trail.</p>
     */
    private OutboundMessage handlePendingContacts(ScopeRef scope,
                                                  UserRow actor,
                                                  String inboundAdapter,
                                                  String remainder) {
        int page = ListArgs.parse(remainder).page;
        int offset = Math.max(0, (page - 1) * PAGE_SIZE);

        // Audit-before-effect for a privileged PII read (the
        // PENDING_LIST posture, D55): the disclosure row commits BEFORE
        // any contact id is read, so the admin's intent is recorded even
        // when the roster turns out empty. Builder inlined rather than
        // insertAudit because the target is the unregistered-contact
        // roster (target_kind USER, target_id "all", like PENDING_LIST),
        // not one invite row (target_kind INVITE).
        String requestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                auditLogWriter.write(conn, RedactionHook.AuditRow.builder()
                        .actorUserId(actor.id)
                        .actorContactId(actor.contactId)
                        .actorAdapter(inboundAdapter)
                        .action(AuditAction.INVITE_PENDING_CONTACTS_LIST)
                        .targetKind(TargetKind.USER)
                        .targetId("all")
                        .requestId(requestId)
                        .detailsJson("{\"page\":" + page + "}")
                        .build());
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "InviteCommandHandler.handlePendingContacts audit write failed", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InviteCommandHandler.handlePendingContacts connection failed", e);
        }

        List<PendingContactRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_PENDING_CONTACTS_SQL)) {
            ps.setString(1, inboundAdapter);
            ps.setInt(2, PAGE_SIZE);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new PendingContactRow(
                            rs.getString("contact_id"),
                            rs.getTimestamp("last_attempted_at").toInstant()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InviteCommandHandler.handlePendingContacts failed for adapter="
                            + inboundAdapter, e);
        }

        if (rows.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_INVITE_PENDING_CONTACTS_EMPTY,
                    inboundContext.effectiveLanguage()));
        }
        StringBuilder body = new StringBuilder(MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_INVITE_PENDING_CONTACTS_HEADER,
                        inboundContext.effectiveLanguage()),
                inboundAdapter));
        for (PendingContactRow row : rows) {
            body.append('\n');
            body.append(MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_INVITE_PENDING_CONTACTS_ENTRY,
                            inboundContext.effectiveLanguage()),
                    row.contactId, row.lastAttemptedAt.toString()));
        }
        return reply(scope, body.toString());
    }

    // ------------------------------------------------------------------
    // /invite bot-contact [--adapter <name>]
    // ------------------------------------------------------------------

    /**
     * Returns the bot's own shareable onboarding contact in-band (M1-620):
     * the value an admin hands a new person so their app can reach the bot
     * — pull-only, DM-only, bot-admin-only (both gates already ran in
     * {@link #handle} before dispatch). The target adapter is the inbound
     * one by default, or the single activated adapter named via
     * {@code --adapter <name>} (operator decision 2026-07-13). No audit
     * row, deliberately: this is a read of the bot's OWN non-secret
     * address — the same posture as the un-audited {@code /invite list}
     * read — not a privileged user-PII read (Invariant 7 binds mutations
     * and user-data reads). D37: the contact value is displayed once in
     * the reply and never logged or persisted.
     */
    private OutboundMessage handleBotContact(ScopeRef scope,
                                             String inboundAdapter,
                                             String remainder) {
        String targetName = Optional.ofNullable(CreateArgs.parse(remainder).adapter())
                .orElse(inboundAdapter);
        MessagingAdapter target = null;
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            if (adapter.name().equals(targetName)) {
                target = adapter;
                break;
            }
        }
        if (target == null) {
            // Only reachable via --adapter: the inbound adapter is activated
            // by definition (it just delivered this command). The reply names
            // the valid choices per the M1-620 acceptance.
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_INVITE_BOT_CONTACT_UNKNOWN_ADAPTER, inboundContext.effectiveLanguage()),
                    targetName, String.join(", ", enabledAdapterNames())));
        }
        Optional<String> contact;
        try {
            contact = target.connectContact();
        } catch (MessagingException e) {
            // Live query failed or timed out. The exception's message is
            // adapter-internal detail (fixed sentinels by codec contract) —
            // the contact value is never in it, and nothing is logged here.
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_INVITE_BOT_CONTACT_UNAVAILABLE, inboundContext.effectiveLanguage()),
                    targetName));
        }
        if (contact.isEmpty()) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_INVITE_BOT_CONTACT_UNSUPPORTED, inboundContext.effectiveLanguage()),
                    targetName));
        }
        // Displayed once in the reply; the outbound path omits reply bodies
        // from logs, so the value reaches the admin's DM and nothing else (D37).
        return reply(scope, MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_INVITE_BOT_CONTACT, inboundContext.effectiveLanguage()),
                targetName, contact.get()));
    }

    // ------------------------------------------------------------------
    // /invite create --adapter <name> {--contact <id> | --open}
    // ------------------------------------------------------------------

    private OutboundMessage handleCreate(ScopeRef scope,
                                         UserRow actor,
                                         String inboundAdapter,
                                         String remainder) {
        // Confirm-gate fork for /invite create --open (M1-051). The
        // --contact branch has NO confirm gate per spec §Admin
        // ("No confirmation required (risk is bounded to one specific
        // identity)"); a body ending in ` confirm` against --contact
        // still reaches the first-call path and falls through to
        // mutually-exclusive validation since "confirm" is not a flag.
        String trimmed = remainder.trim();
        if (trimmed.equals("confirm") || trimmed.endsWith(" confirm")) {
            // Confirm-call: identifier "invite:create:open" — namespaced
            // discriminator so /invite create --open and /invite revoke
            // can both pend through the same actor+scope key without
            // ambiguity.
            Optional<ConfirmStateService.PendingConfirm> taken =
                    confirmStateService.takeMatching(actor.id, scope, "invite:create:open");
            if (taken.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING, inboundContext.effectiveLanguage()));
            }
            InviteCreateOpenConfirm pending = (InviteCreateOpenConfirm) taken.get();
            return createOpen(scope, actor, inboundAdapter, pending.targetAdapter());
        }

        CreateArgs args = CreateArgs.parse(remainder);

        // Input-shape checks before adapter-name validation, in priority
        // order: malformed first, then mutually-exclusive; unknown
        // adapter / cap fire later.
        //
        // Malformed = any token went unconsumed (typo'd flag, value-less
        // --contact, stray bare argument). Fail safe with zero state
        // change: without this gate a botched STRICT-invite attempt would
        // parse to "no flags" and be normalized into the broader-blast-
        // radius --open flow below (redteam M1-632 medium finding; D60
        // defaults only a TRULY-bare create).
        if (args.malformed) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_CREATE_MALFORMED, inboundContext.effectiveLanguage()));
        }
        if (args.contact != null && args.open) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_MUTUALLY_EXCLUSIVE, inboundContext.effectiveLanguage()));
        }
        // D60: a truly-bare create (neither flag, nothing unconsumed)
        // defaults to --open — the only practically-usable onboarding path
        // for a brand-new person, who has no contactId yet. Normalizing
        // here routes the bare form through the unchanged --open machinery
        // below (adapter inference M1-626, confirm gate M1-051, per-adapter
        // open cap), so every --open backstop fires exactly as for an
        // explicit --open.
        if (args.contact == null && !args.open) {
            args = new CreateArgs(args.adapter, null, true, false);
        }

        // Adapter validation against the currently-enabled set.
        Set<String> enabled = enabledAdapterNames();
        String targetAdapter = args.adapter;

        // --open with no explicit --adapter: an open invite binds to the
        // adapter only, so a single-adapter deployment has one unambiguous
        // target — resolve it rather than forcing the admin to name it.
        // When the target can't be inferred (more than one enabled adapter)
        // name the requirement and the choices, instead of falling through
        // to the empty-backtick "Unknown adapter ``" the null case would
        // otherwise render (M1-626). The --contact path is deliberately
        // excluded (out of scope): it stays explicitly adapter-scoped.
        if (args.open && targetAdapter == null) {
            if (enabled.size() == 1) {
                targetAdapter = enabled.iterator().next();
            } else {
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_INVITE_ADAPTER_REQUIRED, inboundContext.effectiveLanguage()),
                        String.join(", ", enabled)));
            }
        }

        if (targetAdapter == null || !enabled.contains(targetAdapter)) {
            String body = MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_INVITE_UNKNOWN_ADAPTER, inboundContext.effectiveLanguage()),
                    targetAdapter == null ? "" : targetAdapter);
            return reply(scope, body);
        }

        if (args.contact != null) {
            // --contact path executes immediately — spec carves it out
            // of the confirm requirement (bounded blast radius).
            return createContactBound(scope, actor, inboundAdapter, targetAdapter, args.contact);
        }

        // --open path — first-call: validate adapter (done above), write
        // the spec §Authorization model step-8 audit-on-intent row,
        // then remember pending + return prompt. Confirm-call lands in
        // createOpen via the confirm-fork above with the captured
        // targetAdapter. The intent row's target_id is a synthetic
        // placeholder UUID because the invite_code row doesn't exist
        // yet (it gets INSERTed on confirm in createOpen); details_json
        // carries the requested targetAdapter so an operator can
        // correlate intent and completion.
        String intentRequestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            insertAudit(conn, AuditAction.INVITE_CREATE_INTENT,
                    UUID.randomUUID().toString(), null, actor, inboundAdapter,
                    intentRequestId, inviteCreateOpenIntentDetailsJson(targetAdapter));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write INVITE_CREATE_INTENT audit row", e);
        }
        confirmStateService.remember(actor.id, scope,
                new InviteCreateOpenConfirm(targetAdapter));
        String prompt = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_INVITE_CREATE_OPEN, inboundContext.effectiveLanguage()),
                Long.toString(confirmStateService.timeoutSeconds()),
                targetAdapter);
        return reply(scope, prompt);
    }

    private static String inviteCreateOpenIntentDetailsJson(String targetAdapter) {
        return "{\"target_adapter\":\"" + targetAdapter.replace("\"", "\\\"") + "\"}";
    }

    private OutboundMessage createContactBound(ScopeRef scope,
                                               UserRow actor,
                                               String inboundAdapter,
                                               String targetAdapter,
                                               String targetContactId) {
        // Pre-banned-contact rejection: the (targetAdapter, targetContactId)
        // row must not be is_banned=TRUE per spec. The unknown-contact
        // case (no row at all) is permitted because /invite create
        // explicitly carves out a pre-bound invite for an unregistered
        // contact (spec §Admin Unknown-contact rule exception).
        Optional<UserRow> targetOpt = lookupUser(targetAdapter, targetContactId);
        if (targetOpt.isPresent() && targetOpt.get().isBanned) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_BANNED_TARGET, inboundContext.effectiveLanguage()));
        }

        String requestId = UUID.randomUUID().toString();
        // Sample the injected Clock once so the cap-count cutoff and the
        // expires_at write below share one instant (§9 / M1-490).
        Instant now = clock.instant();
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).plus(inviteTtl);
        UUID code;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                setActorGuc(conn, actor.id);
                long current = countContactBoundPending(conn, now);
                if (current >= contactCap) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_CONTACT_CAP_MET, inboundContext.effectiveLanguage()));
                }

                // Audit-before-effect: mint the code (pure read), write
                // the INVITE_CREATE audit row FIRST referencing that
                // code, then INSERT the invite_code row. A roll-back
                // anywhere below the audit INSERT discards both.
                code = generateInviteCode(conn);

                insertAudit(conn, AuditAction.INVITE_CREATE, code.toString(), targetContactId,
                        actor, inboundAdapter, requestId,
                        inviteCreateDetailsJson("CONTACT_BOUND", targetAdapter));

                insertInvite(conn, code, "CONTACT_BOUND", targetAdapter, targetContactId,
                        actor.id, expiresAt);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "InviteCommandHandler.createContactBound failed for adapter="
                                + targetAdapter + " contact_id="
                                + ContactIds.redact(targetContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InviteCommandHandler.createContactBound connection failed for adapter="
                            + targetAdapter + " contact_id="
                            + ContactIds.redact(targetContactId), e);
        }

        String body = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_INVITE_CREATED, inboundContext.effectiveLanguage()), code.toString());
        return reply(scope, body);
    }

    private OutboundMessage createOpen(ScopeRef scope,
                                       UserRow actor,
                                       String inboundAdapter,
                                       String targetAdapter) {
        String requestId = UUID.randomUUID().toString();
        // Sample the injected Clock once so the cap-count cutoff and the
        // expires_at write below share one instant (§9 / M1-490).
        Instant now = clock.instant();
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).plus(inviteTtl);
        UUID code;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                setActorGuc(conn, actor.id);
                long current = countOpenPendingForAdapter(conn, targetAdapter, now);
                if (current >= openCap) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_OPEN_CAP_MET, inboundContext.effectiveLanguage()));
                }

                // Audit-before-effect: see {@link #createContactBound}
                // for the rationale on the mint-audit-insert order.
                code = generateInviteCode(conn);

                insertAudit(conn, AuditAction.INVITE_CREATE, code.toString(), null,
                        actor, inboundAdapter, requestId,
                        inviteCreateDetailsJson("OPEN_ADAPTER", targetAdapter));

                insertInvite(conn, code, "OPEN_ADAPTER", targetAdapter, null,
                        actor.id, expiresAt);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "InviteCommandHandler.createOpen failed for adapter="
                                + targetAdapter, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InviteCommandHandler.createOpen connection failed for adapter="
                            + targetAdapter, e);
        }

        String body = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_INVITE_CREATED, inboundContext.effectiveLanguage()), code.toString());
        return reply(scope, body);
    }

    private long countOpenPendingForAdapter(Connection conn, String adapter, Instant now)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COUNT_OPEN_PENDING_PER_ADAPTER_SQL)) {
            ps.setString(1, adapter);
            ps.setObject(2, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countContactBoundPending(Connection conn, Instant now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COUNT_CONTACT_PENDING_GLOBAL_SQL)) {
            ps.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private UUID generateInviteCode(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_NEW_CODE_SQL);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return (UUID) rs.getObject("code");
        }
    }

    private void insertInvite(Connection conn,
                              UUID code,
                              String inviteType,
                              String adapter,
                              @Nullable String expectedContactId,
                              UUID createdBy,
                              OffsetDateTime expiresAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_INVITE_SQL)) {
            ps.setObject(1, code);
            ps.setString(2, inviteType);
            ps.setString(3, adapter);
            if (expectedContactId == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, expectedContactId);
            }
            ps.setObject(5, createdBy);
            ps.setObject(6, expiresAt);
            ps.execute();
        }
    }

    // ------------------------------------------------------------------
    // /invite list [--page N]
    // ------------------------------------------------------------------

    private OutboundMessage handleList(ScopeRef scope, String remainder) {
        int page = ListArgs.parse(remainder).page;
        int offset = Math.max(0, (page - 1) * PAGE_SIZE);

        List<PendingInviteRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_PENDING_LIST_SQL)) {
            ps.setInt(1, PAGE_SIZE);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID code = (UUID) rs.getObject("code");
                    String inviteType = rs.getString("invite_type");
                    String adapter = rs.getString("adapter");
                    String contactId = rs.getString("expected_contact_id");
                    Timestamp ts = rs.getTimestamp("expires_at");
                    Instant expiresAt = ts == null ? null : ts.toInstant();
                    rows.add(new PendingInviteRow(code, inviteType, adapter, contactId, expiresAt));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InviteCommandHandler.handleList failed", e);
        }

        StringBuilder body = new StringBuilder(bundleLoader.get(BundleKeys.REPLY_INVITE_LIST_HEADER, inboundContext.effectiveLanguage()));
        for (PendingInviteRow row : rows) {
            body.append('\n');
            body.append(renderListEntry(row));
        }
        return reply(scope, body.toString());
    }

    private String renderListEntry(PendingInviteRow row) {
        // Full code, not a prefix: /invite revoke matches the full UUID,
        // so the list must display exactly what revoke accepts.
        String codeText = row.code.toString();
        String expiresAtIso = row.expiresAt == null ? "(no expiry)" : row.expiresAt.toString();
        if ("OPEN_ADAPTER".equals(row.inviteType)) {
            return MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_INVITE_LIST_ENTRY_OPEN, inboundContext.effectiveLanguage()),
                    codeText, row.adapter, expiresAtIso);
        }
        String target = row.expectedContactId == null
                ? ""
                : ContactIds.redact(row.expectedContactId);
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_INVITE_LIST_ENTRY, inboundContext.effectiveLanguage()),
                codeText, row.adapter, target, expiresAtIso);
    }

    // ------------------------------------------------------------------
    // /invite revoke <code>
    // ------------------------------------------------------------------

    private OutboundMessage handleRevoke(ScopeRef scope,
                                         UserRow actor,
                                         String inboundAdapter,
                                         String remainder) {
        // Confirm-gate fork (M1-051). identifier "invite:revoke" is the
        // colon-namespaced takeMatching key; the router's step 4.5
        // sweep recognizes the "invite revoke" sweepPrefix on the
        // pending payload (see ConfirmStateService.PendingConfirm).
        String trimmed = remainder.trim();
        if (trimmed.equals("confirm") || trimmed.endsWith(" confirm")) {
            Optional<ConfirmStateService.PendingConfirm> taken =
                    confirmStateService.takeMatching(actor.id, scope, "invite:revoke");
            if (taken.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING, inboundContext.effectiveLanguage()));
            }
            InviteRevokeConfirm pending = (InviteRevokeConfirm) taken.get();
            return executeRevoke(scope, actor, inboundAdapter, pending.code());
        }

        String codeText = trimmed.split("\\s+", 2)[0];
        UUID code;
        try {
            code = UUID.fromString(codeText);
        } catch (IllegalArgumentException e) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_REVOKE_NOT_PENDING, inboundContext.effectiveLanguage()));
        }

        // First-call path — validation passes only the UUID-parse here.
        // The PENDING-row existence check lives inside the transaction
        // (the FOR UPDATE lock in executeRevoke), so a /invite revoke
        // against a non-PENDING code STILL stores pending and prompts;
        // the user's confirm will then surface error.invite.revoke_not_pending.
        // This is acceptable UX — the spec doesn't require pre-flight
        // existence-checking, and the lock is the canonical race guard.
        //
        // Audit-on-intent (spec §Authorization model step 8): write
        // ONE INVITE_REVOKE_INTENT row referencing the parsed code
        // BEFORE remember() / prompt. The completion row (action=
        // INVITE_REVOKE) writes on the confirm leg inside the FOR
        // UPDATE transaction. Together they record both "the admin
        // attempted to revoke this code" and "the row actually
        // transitioned PENDING→REVOKED" — an admin who probes a code
        // prefix without confirming still leaves the intent row.
        String intentRequestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            insertAudit(conn, AuditAction.INVITE_REVOKE_INTENT, code.toString(),
                    null, actor, inboundAdapter, intentRequestId,
                    inviteRevokeIntentDetailsJson());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write INVITE_REVOKE_INTENT audit row", e);
        }
        confirmStateService.remember(actor.id, scope,
                new InviteRevokeConfirm(code));
        String prompt = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_INVITE_REVOKE, inboundContext.effectiveLanguage()),
                Long.toString(confirmStateService.timeoutSeconds()),
                code.toString().substring(0, 8));
        return reply(scope, prompt);
    }

    private OutboundMessage executeRevoke(ScopeRef scope,
                                          UserRow actor,
                                          String inboundAdapter,
                                          UUID code) {
        String codeText = code.toString();
        String requestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                setActorGuc(conn, actor.id);
                UUID inviteId = lockPendingInviteId(conn, code);
                if (inviteId == null) {
                    // Zero rows pending under this code (already USED /
                    // REVOKED / absent). Roll back so no audit row
                    // survives — audit-before-effect with no effect = no
                    // audit row.
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_REVOKE_NOT_PENDING, inboundContext.effectiveLanguage()));
                }

                insertAudit(conn, AuditAction.INVITE_REVOKE, inviteId.toString(), null,
                        actor, inboundAdapter, requestId, "{}");

                int updated = updateInviteRevoked(conn, code);
                if (updated == 0) {
                    // Race-condition guard: the FOR UPDATE above held the
                    // row, so this should be unreachable. Roll back to
                    // keep the invariant audit-row-iff-mutation.
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_REVOKE_NOT_PENDING, inboundContext.effectiveLanguage()));
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "InviteCommandHandler.executeRevoke failed for code=" + codeText, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InviteCommandHandler.executeRevoke connection failed", e);
        }

        return reply(scope, bundleLoader.get(BundleKeys.REPLY_INVITE_REVOKED, inboundContext.effectiveLanguage()));
    }

    private @Nullable UUID lockPendingInviteId(Connection conn, UUID code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_INVITE_FOR_REVOKE_SQL)) {
            ps.setObject(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private int updateInviteRevoked(Connection conn, UUID code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_INVITE_REVOKED_SQL)) {
            ps.setObject(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Bind {@code infochat.actor_id} for the current transaction so the
     * V62 invite routines can resolve their actor. Transaction-local
     * (set_config's third argument), so it cannot leak to the next
     * borrower of this pooled connection. The bound value is the same
     * {@code users.id} every audit row in these transactions claims as
     * {@code actor_user_id} — V24's {@code trg_audit_log_actor_check}
     * compares the two and rejects a mismatch.
     */
    private void setActorGuc(Connection conn, UUID actorId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('infochat.actor_id', ?, true)")) {
            ps.setString(1, actorId.toString());
            ps.execute();
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private void insertAudit(Connection conn,
                             AuditAction action,
                             String targetId,
                             @Nullable String targetContactId,
                             UserRow actor,
                             String inboundAdapter,
                             String requestId,
                             String detailsJson) throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actor.id)
                .actorContactId(actor.contactId)
                .actorAdapter(inboundAdapter)
                .action(action)
                .targetKind(TargetKind.INVITE)
                .targetId(targetId)
                .targetContactId(targetContactId)
                .requestId(requestId)
                .detailsJson(detailsJson)
                .build();
        auditLogWriter.write(conn, row);
    }

    private Optional<UserRow> lookupUser(String adapter, @Nullable String contactId) {
        if (adapter == null || contactId == null) {
            return Optional.empty();
        }
        return userRepository.findByAdapterAndContactId(adapter, contactId)
                .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin(), u.isBanned(),
                        u.registrationState()));
    }

    private Set<String> enabledAdapterNames() {
        Set<String> out = new LinkedHashSet<>();
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            out.add(adapter.name());
        }
        return out;
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private static @Nullable String contactIdOf(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm ? dm.contactId() : null;
    }

    private static String inviteRevokeIntentDetailsJson() {
        return "{}";
    }

    private static String inviteCreateDetailsJson(String inviteType, String adapter) {
        // Both fields are closed-set / well-formed identifiers, so direct
        // concatenation into the JSON template is safe (no untrusted
        // user-supplied content). The invite_type set is the V5 CHECK
        // constraint values; the adapter is validated against
        // AdapterRegistry.activatedAdapters() upstream.
        return "{\"invite_type\":\"" + inviteType + "\",\"adapter\":\"" + adapter + "\"}";
    }

    /** Minimal in-memory representation of a users row this handler needs. */
    private record UserRow(UUID id, String contactId, boolean isAdmin, boolean isBanned,
                           String registrationState) {}

    /** One row of a {@code /invite list} result page. */
    private record PendingInviteRow(UUID code, String inviteType, String adapter,
                                    @Nullable String expectedContactId, @Nullable Instant expiresAt) {}

    /** One row of a {@code /invite pending-contacts} result page. */
    private record PendingContactRow(String contactId, Instant lastAttemptedAt) {}

    /**
     * Parsed form of {@code /invite create --adapter <name>
     * {--contact <id> | --open}}. The required-vs-optional shape and
     * mutually-exclusive validation live in {@link #handleCreate}; the
     * parser extracts the supplied flag values and records whether any
     * token went unconsumed ({@link #malformed}) so {@code handleCreate}
     * can fail safe on a typo'd flag or a value-less {@code --contact}
     * — either the space form ({@code --contact} at end of input) or
     * the equals form ({@code --contact=} with an empty value, M1-633)
     * — instead of mistaking it for a bare create (D60; redteam M1-632).
     * {@code /invite bot-contact} reuses this parser for its optional
     * {@code --adapter} flag (same tokenizer, same flag grammar) and
     * reads only {@link #adapter}.
     */
    record CreateArgs(@Nullable String adapter, @Nullable String contact, boolean open,
                      boolean malformed) {

        static CreateArgs parse(String remainder) {
            List<String> tokens = CommandTokenizer.tokenize(remainder);
            String adapter = null;
            String contact = null;
            boolean open = false;
            boolean malformed = false;
            int i = 0;
            while (i < tokens.size()) {
                String tok = tokens.get(i);
                if (tok.equals("--adapter") && i + 1 < tokens.size()) {
                    adapter = tokens.get(i + 1);
                    i += 2;
                } else if (tok.startsWith("--adapter=")) {
                    // A bare "--adapter=" (empty value after the equals) is
                    // a value-less flag in equals form: mark malformed like
                    // the space form instead of carrying "" downstream, so
                    // the malformed gate fires and nothing is minted or
                    // armed (M1-632 redteam out-of-model item; M1-633).
                    String value = tok.substring("--adapter=".length());
                    if (value.isEmpty()) {
                        malformed = true;
                    } else {
                        adapter = value;
                    }
                    i++;
                } else if (tok.equals("--contact") && i + 1 < tokens.size()) {
                    contact = tokens.get(i + 1);
                    i += 2;
                } else if (tok.startsWith("--contact=")) {
                    // Same empty-value rule as --adapter= above: without it,
                    // "--contact=" parsed to contact="" (non-null), slipped
                    // past both the malformed gate and the bare→open
                    // normalization, and minted a CONTACT_BOUND invite
                    // bound to "" that no redeemer could ever match.
                    String value = tok.substring("--contact=".length());
                    if (value.isEmpty()) {
                        malformed = true;
                    } else {
                        contact = value;
                    }
                    i++;
                } else if (tok.equals("--open")) {
                    open = true;
                    i++;
                } else {
                    // Unconsumed: a typo'd flag, a value-less --contact/
                    // --adapter, or a stray bare argument. Recorded, not
                    // skipped-and-forgotten, so create can fail safe (D60).
                    malformed = true;
                    i++;
                }
            }
            return new CreateArgs(adapter, contact, open, malformed);
        }
    }

    /** Parsed form of {@code /invite list [--page N]}. */
    record ListArgs(int page) {

        static ListArgs parse(String remainder) {
            List<String> tokens = CommandTokenizer.tokenize(remainder);
            int page = 1;
            for (int i = 0; i < tokens.size(); i++) {
                String tok = tokens.get(i);
                if (tok.equals("--page") && i + 1 < tokens.size()) {
                    try {
                        page = Math.max(1, Integer.parseInt(tokens.get(i + 1)));
                    } catch (NumberFormatException ignored) {
                        // Malformed --page N falls back to page 1.
                    }
                } else if (tok.startsWith("--page=")) {
                    try {
                        page = Math.max(1, Integer.parseInt(tok.substring("--page=".length())));
                    } catch (NumberFormatException ignored) {
                        // Malformed --page=N falls back to page 1.
                    }
                }
            }
            return new ListArgs(page);
        }
    }
}
