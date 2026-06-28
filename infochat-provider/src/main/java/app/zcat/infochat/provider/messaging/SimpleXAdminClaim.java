package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.log.ContactIds;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * SimpleX bootstrap-admin claim-token consumer (decision D50). SimpleX
 * has no pre-configurable cryptographic sender address — identity is the
 * per-connection contact id, and a sender's advertised profile address
 * ({@code contact.profile.contactLink}) is self-asserted, not verified
 * (out of scope of the SMP protocol) — so the by-address bootstrap
 * AdminBootstrap uses for Signal is impossible here (and the discarded
 * M1-505 approach of trusting the advertised address let any contact
 * spoof the admin). Instead the operator configures a secret
 * {@code infochat.adapters.simplex.admin-token}; the FIRST DM whose
 * normalized body equals that token registers the sending connection's
 * contact id and flips {@code is_admin = true} on that
 * {@code (simplex, contact_id)} row. The token is single-use.
 *
 * <p><b>Where it runs.</b> {@link InboundRouter} step 2 (unknown-contact
 * DM) calls {@link #claim} BEFORE the {@link InviteCodeConsumer} invite
 * consume. A {@link Claimed} outcome sends the welcome reply and stops;
 * a {@link NotClaimed} outcome falls through to the existing invite path,
 * so a wrong/used token reaches the SAME fixed {@code error.invite.required}
 * reply an invalid invite would — there is no oracle on token validity
 * (acceptance item 2). This also means a token-guessing flood is bounded
 * by the existing invite brute-force counter (the guess is not a valid
 * UUID, so the fall-through counts it as an attempt).</p>
 *
 * <p><b>Adapter scope.</b> The claim applies to the SimpleX adapter only;
 * {@link #claim} returns {@link NotClaimed} for any other adapter and when
 * no token is configured, so {@link InboundRouter} stays adapter-agnostic
 * (it always calls {@code claim} first and lets this bean own the SimpleX
 * specificity).</p>
 *
 * <p><b>Single-use while an admin exists; no schema (decision D50).</b>
 * "Used" is gated on the durable presence of a
 * {@code (simplex, is_admin = true)} row, not a stored copy of the secret:
 * the first claim is the only one for which no SimpleX admin yet exists.
 * This survives a restart (no in-memory flag) and needs no Flyway migration
 * (out_of_scope forbids a schema change). It is race-safe under concurrent
 * presentations via a transaction-scoped advisory lock (see below): without
 * it, two DIFFERENT contact ids presenting the token at the same instant
 * could both pass the {@code NOT EXISTS} check and both become admin (the
 * {@code ON CONFLICT} guard only covers the same
 * {@code (adapter, contact_id)}).
 *
 * <p><b>Residual gap and operator mitigation (M1-506 redteam, medium
 * PERM-ESCAL).</b> Because the gate is the live {@code is_admin} row rather
 * than a durable token-spent marker, a {@code /revoke-admin} of the claimed
 * SimpleX admin — possible in a multi-adapter deployment, since last-admin
 * protection is global across adapters — would re-arm a still-configured
 * token, letting a leaked token re-claim. A token-spent marker that would
 * make single-use survive a revoke cannot be built here: the application DB
 * role is write-only on {@code audit_log} (audit-integrity least-privilege,
 * D34), so the durable claim record is not app-readable, and a dedicated
 * "consumed" column/table would be a schema migration this ticket's
 * out_of_scope forbids. The mitigation v1 relies on is operator hygiene:
 * the operator unsets {@code infochat.adapters.simplex.admin-token} once the
 * first admin is established (deployment.md §Operator inputs / security.md
 * §Per-adapter admin threat profile) — with no token configured, nothing can
 * re-arm. Permanent single-use independent of unsetting the token is tracked
 * as a follow-up (it needs the durable marker + schema migration). Re-issuing
 * a spent token, rotation, and multi-admin issuance are likewise future
 * work.</p>
 *
 * <p><b>Audit-before-effect (schema Invariant 7).</b> The
 * {@code BOOTSTRAP_ADMIN} audit row ({@code details_json.cause = 'claim'})
 * and the {@code users} INSERT run in ONE transaction, so a partial
 * failure rolls back atomically — no admin row without its audit row.</p>
 *
 * <p><b>Secret handling (security.md §Secrets handling).</b> The token is
 * sourced from config/env, never the DB, and is never logged raw; the
 * body is compared against it in constant time
 * ({@link MessageDigest#isEqual} on UTF-8 bytes) so the match leaks no
 * timing signal beyond the fixed invite-style reply.</p>
 */
@ApplicationScoped
public class SimpleXAdminClaim {

    /**
     * Stable, arbitrary 64-bit key namespacing the transaction-scoped
     * advisory lock that serializes SimpleX admin-claim attempts. A
     * constant (not derived from input) so every concurrent claim
     * contends on the same lock; transaction-scoped, so it releases on
     * commit/rollback. The value is opaque — only its uniqueness within
     * the deployment's advisory-lock namespace matters.
     */
    private static final long CLAIM_ADVISORY_LOCK_KEY = 0x51_50_58_41_44_4D_4EL;

    private static final String ACQUIRE_CLAIM_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(?)";

    /**
     * Conditional first-admin claim. The {@code WHERE NOT EXISTS} arm is
     * the single-use gate (no row inserted once a SimpleX admin already
     * exists); the {@code ON CONFLICT DO NOTHING} arm is defense-in-depth
     * against the same contact already having a row (the router only calls
     * this for unknown contacts, so it normally cannot fire). A returned
     * id means THIS presentation established the SimpleX admin. The row
     * carries the bootstrap-seeded admin shape (deployment.md
     * §Bootstrap-seeded admin row shape): {@code is_admin = true},
     * {@code is_banned = false}, {@code registration_state = 'vouched'},
     * {@code probation_until = NULL} (bootstrap admins skip the slow-start
     * tier) — identical to a Signal/seed bootstrap admin.
     *
     * <p>The gate is the live {@code is_admin} row, NOT a durable
     * token-spent marker: see the class javadoc "Single-use" paragraph for
     * why a durable marker (which would make single-use survive a
     * {@code /revoke-admin}) is out of scope here, and the operator-side
     * mitigation that closes the residual gap.</p>
     */
    private static final String CLAIM_ADMIN_SQL =
            "INSERT INTO users (adapter, contact_id, is_admin, is_banned,"
                    + " registration_state, probation_until)"
                    + " SELECT ?, ?, TRUE, FALSE, 'vouched', NULL"
                    + " WHERE NOT EXISTS ("
                    + "   SELECT 1 FROM users WHERE adapter = ? AND is_admin = TRUE)"
                    + " ON CONFLICT (adapter, contact_id) DO NOTHING"
                    + " RETURNING id";

    @ConfigProperty(name = "infochat.adapters.simplex.admin-token")
    Optional<String> adminToken;

    @Inject
    DataSource dataSource;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    RegisteredContactSet registeredContactSet;

    public sealed interface Outcome permits Claimed, NotClaimed {}

    /** The token matched and THIS presentation established the SimpleX admin. */
    public record Claimed(UUID userId) implements Outcome {}

    /**
     * Nothing was granted: not the SimpleX adapter, no token configured,
     * the body did not match the token, or the token was already used
     * (a SimpleX admin already exists). The router falls through to the
     * invite path, which yields the same fixed reply for all of these.
     */
    public record NotClaimed() implements Outcome {}

    /**
     * Attempt to claim SimpleX bootstrap admin with {@code body} as the
     * presented secret. Returns {@link Claimed} only when this is the
     * SimpleX adapter, a token is configured, the (constant-time) compare
     * matches, and no SimpleX admin yet exists; otherwise {@link NotClaimed}.
     *
     * @param adapter the inbound adapter name (claim applies to SimpleX only)
     * @param contactId the connection-based contact id of the sender
     * @param body the normalized inbound message body (the presented token)
     */
    public Outcome claim(String adapter, String contactId, String body) {
        // SimpleX-only and only when a token is configured. Both checks
        // return before any DB work, so a non-SimpleX DM and the common
        // no-token deployment pay nothing here.
        if (!AdapterRegistry.SIMPLEX_NAME.equals(adapter)) {
            return new NotClaimed();
        }
        String token = adminToken.orElse("");
        if (token.isBlank()) {
            return new NotClaimed();
        }
        if (!constantTimeEquals(body, token)) {
            return new NotClaimed();
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Serialize all claim attempts so the NOT EXISTS single-use
                // gate is reliable under concurrent presentations (the
                // ON CONFLICT guard alone only covers same-(adapter,
                // contact_id); two distinct contacts could otherwise both
                // pass NOT EXISTS). Transaction-scoped: released on commit
                // or rollback.
                acquireClaimLock(conn);

                UUID userId = tryClaim(conn, adapter, contactId);
                if (userId == null) {
                    // A SimpleX admin already exists (token used) or the
                    // contact already had a row — grant nothing. No audit
                    // row: nothing changed.
                    conn.commit();
                    return new NotClaimed();
                }
                // Audit-before-effect: the BOOTSTRAP_ADMIN row and the
                // users INSERT commit atomically. The claiming contact is
                // the actor and the target (self-claim).
                auditLogWriter.write(conn, RedactionHook.AuditRow.builder()
                        .actorContactId(contactId)
                        .actorAdapter(adapter)
                        .action(AuditAction.BOOTSTRAP_ADMIN)
                        .targetKind(TargetKind.USER)
                        .targetId(userId.toString())
                        .targetContactId(contactId)
                        .detailsJson("{\"cause\":\"claim\"}")
                        .build());
                conn.commit();
                // M1-229 registered-set coherence: the contact is now a
                // 'vouched' users row. Mark AFTER commit so a rolled-back
                // claim never leaves a stale registered entry; their next
                // inbound routes to a per-id rate bucket.
                registeredContactSet.markRegistered(adapter, contactId);
                return new Claimed(userId);
            } catch (SQLException e) {
                conn.rollback();
                // The token is a secret and the contact id is sensitive:
                // neither appears in the message (security.md §Secrets
                // handling; contact-id redaction discipline).
                throw new IllegalStateException(
                        "SimpleXAdminClaim.claim failed for contact_id="
                                + ContactIds.redact(contactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "SimpleXAdminClaim.claim connection failed for contact_id="
                            + ContactIds.redact(contactId), e);
        }
    }

    private void acquireClaimLock(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(ACQUIRE_CLAIM_LOCK_SQL)) {
            ps.setLong(1, CLAIM_ADVISORY_LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    private static @Nullable UUID tryClaim(Connection conn, String adapter, String contactId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(CLAIM_ADMIN_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            ps.setString(3, adapter);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject(1, UUID.class);
                }
                return null;
            }
        }
    }

    /**
     * Constant-time UTF-8 comparison of the presented body against the
     * configured token, so a near-miss reveals no timing signal about how
     * many leading bytes matched. {@link MessageDigest#isEqual} is the
     * standard timing-safe primitive.
     */
    private static boolean constantTimeEquals(String body, String token) {
        return MessageDigest.isEqual(
                body.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
