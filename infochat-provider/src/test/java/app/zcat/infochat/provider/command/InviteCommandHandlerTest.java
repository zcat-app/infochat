package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link InviteCommandHandler} against the
 * DevServices Postgres container. One {@code @Test} per acceptance
 * scenario 21..32 in M1-044c.
 *
 * <p>Test isolation: each {@code @Test} uses a unique sub-prefix
 * within the class-wide {@code PREFIX} ({@code m1-044c-invite-});
 * {@link #cleanup()} disables the V5 audit-log append-only triggers
 * for the cleanup pass (we own the table) so audit rows from prior
 * runs can be deleted alongside the rows they reference. The triggers
 * are re-enabled in {@code finally}.</p>
 *
 * @implNote Canonical thin-SQL handler exception per
 *     {@code docs/process/test-pyramid.md} §Shape B: Thin-SQL.
 */
@QuarkusTest
class InviteCommandHandlerTest {

    private static final String PREFIX = "m1-044c-invite-";
    private static final String ADAPTER = "inmemory";

    @Inject InviteCommandHandler handler;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject ConfirmStateService confirmStateService;

    @AfterEach
    void restoreClock() {
        // Restore production clock between tests in case a scenario
        // swapped it via setClock — keeps subsequent scenarios from
        // inheriting a warped clock.
        confirmStateService.setClock(Clock.systemUTC());
    }

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-044c-invite-permanent");
            exec(conn,
                    "DELETE FROM invite_code WHERE expected_contact_id LIKE ? "
                            + "OR created_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%", PREFIX + "%");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_contact_id LIKE ? "
                                + "OR actor_user_id IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%", PREFIX + "%");
                exec(conn,
                        "UPDATE users SET banned_by = NULL WHERE contact_id LIKE ?",
                        PREFIX + "%");
                exec(conn,
                        "UPDATE users SET banned_by = NULL "
                                + "WHERE banned_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ?",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            // Clean up any extra OPEN_ADAPTER seeds left over (per-test cap
            // tests insert many under different created_by; the prefixed
            // cleanup above handles only those tied to PREFIXed users —
            // an over-cap test seeds via the guardian admin, so its rows
            // remain unless we clean by adapter as well).
            exec(conn,
                    "DELETE FROM invite_code WHERE adapter = ? AND status IN ('PENDING','REVOKED','USED') "
                            + "AND created_by IN (SELECT id FROM users WHERE contact_id LIKE 'guardian-m1-044c-invite-%')",
                    ADAPTER);
        }
    }

    // ----- (21) /invite (no subcommand) → unknown_subcommand --------------

    @Test
    void inviteWithoutSubcommandReturnsUnknownSubcommand() throws Exception {
        String actor = PREFIX + "noSub-actor";
        seedUser(actor, /* isAdmin */ true);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_INVITE_UNKNOWN_SUBCOMMAND), reply.text());
        assertEquals(0L, countInvitesByCreator(userId(actor)),
                "/invite with no subcommand must not write any invite_code row");
    }

    // ----- (22) /invite create with neither flag → missing_flag -----------

    @Test
    void inviteCreateWithoutContactOrOpenReturnsMissingFlag() throws Exception {
        String actor = PREFIX + "noFlag-actor";
        seedUser(actor, true);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite create --adapter " + ADAPTER);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_INVITE_MISSING_FLAG), reply.text());
        assertEquals(0L, countInvitesByCreator(userId(actor)));
    }

    // ----- (23) /invite create with both flags → mutually_exclusive -------

    @Test
    void inviteCreateWithBothFlagsReturnsMutuallyExclusive() throws Exception {
        String actor = PREFIX + "bothFlag-actor";
        seedUser(actor, true);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite create --adapter " + ADAPTER + " --contact " + PREFIX
                        + "bothFlag-target --open");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_INVITE_MUTUALLY_EXCLUSIVE), reply.text());
        assertEquals(0L, countInvitesByCreator(userId(actor)));
    }

    // ----- (24) Unknown adapter → unknown_adapter -------------------------

    @Test
    void inviteCreateWithUnknownAdapterReturnsUnknownAdapter() throws Exception {
        String actor = PREFIX + "unkAdapter-actor";
        seedUser(actor, true);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite create --adapter not-a-real-adapter --contact "
                        + PREFIX + "unkAdapter-target");

        assertTrue(reply.text().contains("Unknown adapter"),
                "unknown_adapter reply must include the spec literal — got: " + reply.text());
        assertTrue(reply.text().contains("not-a-real-adapter"),
                "unknown_adapter reply must interpolate the supplied name — got: " + reply.text());
        assertEquals(0L, countInvitesByCreator(userId(actor)));
    }

    // ----- (25) Pre-banned contact → banned_target ------------------------

    @Test
    void inviteCreateAgainstBannedContactReturnsBannedTarget() throws Exception {
        String actor = PREFIX + "banned-actor";
        String bannedTarget = PREFIX + "banned-target";
        seedUser(actor, true);
        seedUserWithBanned(bannedTarget, /* isAdmin */ false, /* isBanned */ true, "invited");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite create --adapter " + ADAPTER + " --contact " + bannedTarget);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_INVITE_BANNED_TARGET), reply.text());
        assertEquals(0L, countInvitesByCreator(userId(actor)),
                "no invite_code row may be written when target is banned");
    }

    // ----- (26) CONTACT_BOUND happy path ----------------------------------
    // M1-051 augmentation: assert no pending state appears on
    // ConfirmStateService.peek — --contact is excluded from the
    // confirm gate per spec §Admin line 881 ("No confirmation required
    // (risk is bounded to one specific identity)").

    @Test
    void inviteCreateContactBoundHappyPathDoesNotInvokeConfirmGate() throws Exception {
        String actor = PREFIX + "cbHappy-actor";
        String target = PREFIX + "cbHappy-target";
        UUID actorId = seedUser(actor, true);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite create --adapter " + ADAPTER + " --contact " + target);

        // Reply contains the new code's UUID literal (length 36) —
        // the FIRST call executed the M1-044c INSERT directly, NOT a
        // confirm prompt.
        String body = reply.text();
        UUID code = extractUuid(body);
        assertNotNull(code, "reply must contain the new code's UUID: " + body);

        // No pending confirm state was stored — --contact bypasses the gate.
        Optional<ConfirmStateService.PendingConfirm> peeked =
                confirmStateService.peek(actorId, new ScopeRef.Dm(actor));
        assertFalse(peeked.isPresent(),
                "ConfirmStateService.peek must be empty after /invite create --contact "
                        + "(--contact is not in the confirmable-command catalogue)");

        // invite_code row exists with the expected shape.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT invite_type, adapter, expected_contact_id, status "
                             + "FROM invite_code WHERE code = ?")) {
            ps.setObject(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "invite_code row must exist post-create");
                assertEquals("CONTACT_BOUND", rs.getString("invite_type"));
                assertEquals(ADAPTER, rs.getString("adapter"));
                assertEquals(target, rs.getString("expected_contact_id"));
                assertEquals("PENDING", rs.getString("status"));
            }
        }
        // Exactly one INVITE_CREATE audit row exists referencing the code.
        assertEquals(1L, countAuditRowsForCode("INVITE_CREATE", code),
                "/invite create must write exactly one INVITE_CREATE audit row");
    }

    // ----- (27) OPEN happy path -------------------------------------------
    // M1-051 augmentation: drives prompt-then-confirm. The M1-044c
    // post-create assertions on the invite_code row + INVITE_CREATE
    // audit row survive verbatim; the new intermediate-state assertion
    // (no invite_code row after the prompt) proves the first call
    // wrote no DB state.

    @Test
    void inviteCreateOpenHappyPath() throws Exception {
        String actor = PREFIX + "openHappy-actor";
        UUID actorId = seedUser(actor, true);
        long invitesBefore = countInvitesByCreator(actorId);

        OutboundMessage promptReply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite create --adapter " + ADAPTER + " --open");
        assertEquals(expectedOpenPrompt(ADAPTER), promptReply.text(),
                "first /invite create --open call must return the confirm prompt");
        assertEquals(invitesBefore, countInvitesByCreator(actorId),
                "first /invite create --open must not write an invite_code row");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite create --open confirm");

        UUID code = extractUuid(reply.text());
        assertNotNull(code, "confirm reply must contain the new code's UUID");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT invite_type, expected_contact_id, status "
                             + "FROM invite_code WHERE code = ?")) {
            ps.setObject(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "invite_code row must exist post-create");
                assertEquals("OPEN_ADAPTER", rs.getString("invite_type"));
                assertEquals(null, rs.getString("expected_contact_id"),
                        "OPEN_ADAPTER row must have expected_contact_id=NULL");
                assertEquals("PENDING", rs.getString("status"));
            }
        }
        assertEquals(1L, countAuditRowsForCode("INVITE_CREATE", code));
    }

    // ----- (28) Contact cap met → contact_cap_met -------------------------

    @Test
    void inviteCreateWhenContactCapMetReturnsContactCapMet() throws Exception {
        // Cap default = 50. Seed 50 PENDING CONTACT_BOUND rows by the
        // guardian admin so the cleanup of THIS class collects them after
        // the test. Use ADAPTER for cap consistency.
        String actor = PREFIX + "cbCap-actor";
        UUID actorId = seedUser(actor, true);
        UUID guardianId = guardianId();
        long preexisting = countContactBoundPending();
        long need = 50 - preexisting;
        for (long i = 0; i < need; i++) {
            seedInvitePending("CONTACT_BOUND",
                    "guardian-cb-cap-filler-" + UUID.randomUUID() + "-" + i,
                    guardianId);
        }

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite create --adapter " + ADAPTER + " --contact "
                        + PREFIX + "cbCap-overflow");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_INVITE_CONTACT_CAP_MET), reply.text());
        assertEquals(0L, countInvitesByCreator(actorId),
                "no invite_code row may be created when global contact cap is met");
    }

    // ----- (29) Open cap met → open_cap_met -------------------------------
    // M1-051 augmentation: the cap check fires inside createOpen,
    // which now runs on the CONFIRM path. The first call returns the
    // prompt unconditionally (cap not pre-flighted); the confirm call
    // surfaces ERROR_INVITE_OPEN_CAP_MET.

    @Test
    void inviteCreateWhenOpenCapMetReturnsOpenCapMet() throws Exception {
        // Cap default = 3 per adapter.
        String actor = PREFIX + "openCap-actor";
        UUID actorId = seedUser(actor, true);
        UUID guardianId = guardianId();
        long preexisting = countOpenPendingForAdapter();
        long need = 3 - preexisting;
        for (long i = 0; i < need; i++) {
            seedInvitePending("OPEN_ADAPTER", null, guardianId);
        }

        OutboundMessage promptReply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite create --adapter " + ADAPTER + " --open");
        assertEquals(expectedOpenPrompt(ADAPTER), promptReply.text(),
                "first /invite create --open call must return the confirm prompt even when "
                        + "the cap will trip on confirm");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite create --open confirm");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_INVITE_OPEN_CAP_MET), reply.text());
        assertEquals(0L, countInvitesByCreator(actorId),
                "no invite_code row may be created when open cap is met");
    }

    // ----- (30) /invite list: sort + OPEN marker + expiry filter ----------

    @Test
    void inviteListReturnsActivePendingRowsSortedByCreatedAtDesc() throws Exception {
        String actor = PREFIX + "list-actor";
        UUID actorId = seedUser(actor, true);

        // Seed: 1 OPEN, 1 CONTACT_BOUND, 1 expired (PENDING but past
        // expires_at), all created in known order so created_at DESC has
        // a deterministic head.
        UUID open = UUID.randomUUID();
        UUID contact = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        seedInviteWithExpiry(open, "OPEN_ADAPTER", null, actorId, "PENDING",
                OffsetDateTime.now().plusDays(7));
        seedInviteWithExpiry(contact, "CONTACT_BOUND", PREFIX + "list-target",
                actorId, "PENDING",
                OffsetDateTime.now().plusDays(7));
        seedInviteWithExpiry(expired, "CONTACT_BOUND", PREFIX + "list-expired-target",
                actorId, "PENDING",
                OffsetDateTime.now().minusDays(1));

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite list");

        String body = reply.text();
        // Active PENDING rows appear; expired one does NOT.
        assertTrue(body.contains(open.toString().substring(0, 8)),
                "active OPEN code prefix must be listed — got: " + body);
        assertTrue(body.contains(contact.toString().substring(0, 8)),
                "active CONTACT_BOUND code prefix must be listed — got: " + body);
        assertFalse(body.contains(expired.toString().substring(0, 8)),
                "expired PENDING row must NOT be listed — got: " + body);
        // OPEN literal marker on the OPEN row (per spec §Invite-code
        // registration "the list output must visually distinguish --open
        // codes from --contact codes").
        assertTrue(body.contains("OPEN"),
                "OPEN_ADAPTER row must carry the OPEN marker in the rendered list — got: "
                        + body);
    }

    // ----- (31) /invite revoke happy path ---------------------------------
    // M1-051 augmentation: drives prompt-then-confirm.

    @Test
    void inviteRevokeHappyPathTransitionsRowToRevoked() throws Exception {
        String actor = PREFIX + "revoke-actor";
        UUID actorId = seedUser(actor, true);
        UUID code = UUID.randomUUID();
        seedInvitePending("CONTACT_BOUND", PREFIX + "revoke-target", actorId, code);

        OutboundMessage promptReply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite revoke " + code);
        assertEquals(expectedRevokePrompt(code), promptReply.text(),
                "first /invite revoke call must return the confirm prompt");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite revoke confirm");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_INVITE_REVOKED), reply.text());
        // invite_code row transitioned to REVOKED.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM invite_code WHERE code = ?")) {
            ps.setObject(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("REVOKED", rs.getString("status"));
            }
        }
        // One INVITE_REVOKE audit row exists.
        assertEquals(1L, countAuditRowsForCodeInviteRow("INVITE_REVOKE", code),
                "/invite revoke must write exactly one INVITE_REVOKE audit row");
    }

    // ----- (32) /invite revoke on already-revoked → not_pending -----------
    // M1-051 augmentation: the not-PENDING check fires inside
    // executeRevoke (the FOR UPDATE row lock returns no row); the
    // first call still stores pending + returns the prompt, the
    // confirm call surfaces ERROR_INVITE_REVOKE_NOT_PENDING.

    @Test
    void inviteRevokeOnAlreadyRevokedReturnsNotPending() throws Exception {
        String actor = PREFIX + "revokeRe-actor";
        UUID actorId = seedUser(actor, true);
        UUID code = UUID.randomUUID();
        seedInviteWithStatus("CONTACT_BOUND", PREFIX + "revokeRe-target", actorId, code, "REVOKED");

        long auditBefore = countAuditRowsForCodeInviteRow("INVITE_REVOKE", code);

        OutboundMessage promptReply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite revoke " + code);
        assertEquals(expectedRevokePrompt(code), promptReply.text(),
                "first /invite revoke call must return the confirm prompt even when "
                        + "the row is already REVOKED (check fires on confirm)");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite revoke confirm");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_INVITE_REVOKE_NOT_PENDING), reply.text());
        // No new audit row written (transaction rolled back).
        assertEquals(auditBefore, countAuditRowsForCodeInviteRow("INVITE_REVOKE", code),
                "no audit row may be written when /invite revoke targets an already-REVOKED row");
    }

    // ----- M1-051 first-call prompt + confirm scenarios -------------------

    @Test
    void inviteCreateOpenFirstCallReturnsPromptAndWritesIntentAuditRowOnly() throws Exception {
        String actor = PREFIX + "openFirst-actor";
        UUID actorId = seedUser(actor, true);
        long invitesBefore = countInvitesByCreator(actorId);
        long intentBefore = countAuditByActorAndAction(actorId, "INVITE_CREATE_INTENT");
        long completionBefore = countAuditByActorAndAction(actorId, "INVITE_CREATE");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite create --adapter " + ADAPTER + " --open");

        assertEquals(expectedOpenPrompt(ADAPTER), reply.text(),
                "first /invite create --open call must return the confirm prompt");
        assertEquals(invitesBefore, countInvitesByCreator(actorId),
                "first /invite create --open must not write an invite_code row");

        // Spec §Authorization model step 8: audit-on-intent. The
        // first-call writes exactly ONE INVITE_CREATE_INTENT audit
        // row BEFORE remember()/prompt; no INVITE_CREATE (completion)
        // row may appear here — that fires on confirm.
        assertEquals(intentBefore + 1,
                countAuditByActorAndAction(actorId, "INVITE_CREATE_INTENT"),
                "first /invite create --open writes exactly ONE INVITE_CREATE_INTENT row");
        assertEquals(completionBefore,
                countAuditByActorAndAction(actorId, "INVITE_CREATE"),
                "first /invite create --open must NOT write an INVITE_CREATE (completion) row");

        Optional<ConfirmStateService.PendingConfirm> peeked =
                confirmStateService.peek(actorId, new ScopeRef.Dm(actor));
        assertTrue(peeked.isPresent(),
                "ConfirmStateService.peek must show pending under (actor.id, scope)");
        assertEquals("invite:create:open", peeked.get().commandName(),
                "pending commandName must be \"invite:create:open\"");
    }

    @Test
    void inviteCreateOpenConfirmWithinWindowExecutesCreateTransaction() throws Exception {
        // End-state assertions duplicate inviteCreateOpenHappyPath's
        // post-confirm checks; this scenario exists to pin the
        // confirm-within-window contract as a directly named acceptance
        // item AND assert pending state is cleared after confirm.
        String actor = PREFIX + "openConfirm-actor";
        UUID actorId = seedUser(actor, true);

        handler.handle(new ScopeRef.Dm(actor),
                "/invite create --adapter " + ADAPTER + " --open");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite create --open confirm");

        UUID code = extractUuid(reply.text());
        assertNotNull(code, "/invite create --open confirm reply must contain the new code's UUID");
        assertEquals(1L, countAuditRowsForCode("INVITE_CREATE", code),
                "/invite create --open confirm must write exactly one INVITE_CREATE audit row");
        Optional<ConfirmStateService.PendingConfirm> peeked =
                confirmStateService.peek(actorId, new ScopeRef.Dm(actor));
        assertFalse(peeked.isPresent(),
                "ConfirmStateService.peek must be empty after the confirm executes");
    }

    @Test
    void inviteRevokeFirstCallThenConfirmExecutesRevokeTransaction() throws Exception {
        String actor = PREFIX + "revokeConfirm-actor";
        UUID actorId = seedUser(actor, true);
        UUID code = UUID.randomUUID();
        seedInvitePending("CONTACT_BOUND", PREFIX + "revokeConfirm-target", actorId, code);

        // First call — prompt, no invite_code mutation, ONE intent row.
        OutboundMessage promptReply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite revoke " + code);
        assertEquals(expectedRevokePrompt(code), promptReply.text(),
                "first /invite revoke call must return the confirm prompt");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM invite_code WHERE code = ?")) {
            ps.setObject(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("PENDING", rs.getString("status"),
                        "first /invite revoke must not flip the row to REVOKED");
            }
        }
        // Spec §Authorization step 8: first-call writes a single
        // INVITE_REVOKE_INTENT row referencing the parsed code; the
        // INVITE_REVOKE (completion) row fires on confirm below.
        assertEquals(1L, countAuditRowsForCode("INVITE_REVOKE_INTENT", code),
                "first /invite revoke must write exactly one INVITE_REVOKE_INTENT row");
        assertEquals(0L, countAuditRowsForCodeInviteRow("INVITE_REVOKE", code),
                "first /invite revoke must NOT write an INVITE_REVOKE (completion) row");

        // Confirm — executes the M1-044c revoke transaction.
        OutboundMessage confirmReply = handler.handle(
                new ScopeRef.Dm(actor),
                "/invite revoke confirm");
        assertEquals(bundleLoader.get(BundleKeys.REPLY_INVITE_REVOKED), confirmReply.text());
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM invite_code WHERE code = ?")) {
            ps.setObject(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("REVOKED", rs.getString("status"),
                        "confirm must flip the row to REVOKED");
            }
        }
        assertEquals(1L, countAuditRowsForCodeInviteRow("INVITE_REVOKE", code),
                "confirm must write exactly one INVITE_REVOKE audit row");
        // Both the intent (first call) and the completion (confirm)
        // rows persist — operators see WHO attempted AND that it
        // executed.
        assertEquals(1L, countAuditRowsForCode("INVITE_REVOKE_INTENT", code),
                "the INVITE_REVOKE_INTENT row from the first call must remain after confirm");
    }

    private String expectedOpenPrompt(String targetAdapter) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_INVITE_CREATE_OPEN),
                Long.toString(confirmStateService.timeoutSeconds()),
                targetAdapter);
    }

    private String expectedRevokePrompt(UUID code) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_INVITE_REVOKE),
                Long.toString(confirmStateService.timeoutSeconds()),
                code.toString().substring(0, 8));
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String contactId, boolean isAdmin) throws Exception {
        return seedUserWithBanned(contactId, isAdmin, false, "vouched");
    }

    private UUID seedUserWithBanned(String contactId, boolean isAdmin, boolean isBanned,
                                    String registrationState) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, banned_at) "
                             + "VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END) "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            ps.setString(5, registrationState);
            ps.setBoolean(6, isBanned);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedInvitePending(String inviteType, String expectedContactId,
                                   UUID createdBy) throws Exception {
        seedInvitePending(inviteType, expectedContactId, createdBy, UUID.randomUUID());
    }

    private void seedInvitePending(String inviteType, String expectedContactId,
                                   UUID createdBy, UUID code) throws Exception {
        seedInviteWithExpiry(code, inviteType, expectedContactId, createdBy, "PENDING",
                OffsetDateTime.now().plusDays(7));
    }

    private void seedInviteWithStatus(String inviteType, String expectedContactId,
                                      UUID createdBy, UUID code, String status) throws Exception {
        seedInviteWithExpiry(code, inviteType, expectedContactId, createdBy, status,
                OffsetDateTime.now().plusDays(7));
    }

    private void seedInviteWithExpiry(UUID code, String inviteType, String expectedContactId,
                                      UUID createdBy, String status,
                                      OffsetDateTime expiresAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO invite_code (code, invite_type, adapter, expected_contact_id, "
                             + "status, created_by, expires_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, code);
            ps.setString(2, inviteType);
            ps.setString(3, ADAPTER);
            if (expectedContactId == null) {
                ps.setNull(4, java.sql.Types.VARCHAR);
            } else {
                ps.setString(4, expectedContactId);
            }
            ps.setString(5, status);
            ps.setObject(6, createdBy);
            ps.setObject(7, expiresAt);
            ps.executeUpdate();
        }
    }

    private UUID userId(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID guardianId() throws Exception {
        return userId("guardian-m1-044c-invite-permanent");
    }

    private long countInvitesByCreator(UUID createdBy) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM invite_code WHERE created_by = ?")) {
            ps.setObject(1, createdBy);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditByActorAndAction(UUID actorUserId, String action) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE actor_user_id = ? AND action = ?")) {
            ps.setObject(1, actorUserId);
            ps.setString(2, action);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditRowsForCode(String action, UUID code) throws Exception {
        // For INVITE_CREATE, the handler writes target_id = <code.toString()>.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, code.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditRowsForCodeInviteRow(String action, UUID code) throws Exception {
        // For INVITE_REVOKE, the handler writes target_id = <invite.id.toString()>.
        // Resolve that id via the invite_code table.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log al "
                             + "JOIN invite_code ic ON ic.id::TEXT = al.target_id "
                             + "WHERE al.action = ? AND ic.code = ?")) {
            ps.setString(1, action);
            ps.setObject(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countContactBoundPending() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM invite_code "
                             + "WHERE invite_type = 'CONTACT_BOUND' AND status = 'PENDING' "
                             + "  AND (expires_at IS NULL OR expires_at > NOW())")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countOpenPendingForAdapter() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM invite_code "
                             + "WHERE invite_type = 'OPEN_ADAPTER' AND status = 'PENDING' "
                             + "  AND adapter = ? "
                             + "  AND (expires_at IS NULL OR expires_at > NOW())")) {
            ps.setString(1, ADAPTER);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static UUID extractUuid(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(text);
        if (m.find()) {
            return UUID.fromString(m.group());
        }
        return null;
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
