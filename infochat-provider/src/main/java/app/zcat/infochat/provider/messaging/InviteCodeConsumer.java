package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.ContactIds;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authorization step 2 invite-code consumer per
 * docs/spec/security.md §Invite-code registration and the
 * race-safe conditional UPDATE pinned at docs/spec/schema.md
 * §Identity and access — Invite code. The intake-step splice
 * (M1-044b) calls {@link #consume} when the inbound contact has
 * no {@code users} row, passing the normalized inbound message
 * body. The consumer owns the body-to-UUID parse so a non-UUID
 * probe also increments the brute-force counter (M1-044e
 * AUDIT-EVASION fix). The Outcome drives the splice's branch:
 * {@link Accepted} registers the contact and proceeds; {@link
 * Rejected} and {@link BruteForceThresholdBreached} both drop
 * the inbound with the same fixed {@code error.invite.required}
 * reply at the InboundRouter outcome dispatch — per
 * docs/spec/security.md §Invite-code registration the brute-force
 * limit "does not change the per-failure user-visible reply", so
 * a threshold breach is not observable as a distinct reply.
 *
 * <p><b>Race-safe consume.</b> The conditional
 * {@code UPDATE invite_code SET status='USED' ... RETURNING id}
 * is the serialization point — two concurrent consumes of the
 * same PENDING code see one row returned and one returning zero
 * rows. The zero-row path falls to {@link Rejected} and
 * increments the per-{@code (adapter, contact_id)} brute-force
 * counter.</p>
 *
 * <p><b>Audit-before-effect (Invariant 7).</b> The audit INSERT
 * runs in the same transaction as the conditional UPDATE and
 * the {@code users} INSERT, so a partial failure rolls back
 * atomically — no audit row for a failed consume, no users row
 * for a failed audit.</p>
 *
 * <p><b>Brute-force breach audit (exactly once per breach
 * event).</b> An in-memory map of {@code (adapter, contact_id)}
 * keys remembers which keys have already had an
 * {@code INVITE_BRUTE_FORCE_BREACH} audit row written; the value
 * is the last time the key was observed over threshold. A key
 * leaves the map when a subsequent consume sees its counter
 * drop below threshold (window expired), or via the opportunistic
 * stale-entry sweep in {@code evictStaleBreachAudited} (same
 * semantics — a key quiet for a full window has no attempts left
 * inside it). A Provider restart resets the map — the spec
 * tolerates a second breach audit if the attacker is patient
 * enough to wait through a Provider restart.</p>
 *
 * <p><b>Drop counter.</b> The spec also names a
 * {@code invite_drop_total} Micrometer counter that increments
 * on every Rejected outcome. That wiring is DEFERRED to a
 * follow-up ticket (Provider-level Micrometer config + metrics
 * endpoint + per-counter registration is out of scope for the
 * intake-step services). A {@code TODO followup} comment marks
 * the deferred site.</p>
 */
@ApplicationScoped
public class InviteCodeConsumer {

    // The brute-force counter is keyed per (adapter, contact_id), NOT
    // per code — deliberate. Codes are minted via gen_random_uuid()
    // (CSPRNG UUIDv4, 122 random bits; InviteCommandHandler's
    // SELECT_NEW_CODE_SQL), so even N colluding contact ids pooling
    // N× the per-contact budget cannot meaningfully search the 2^122
    // space, and docs/spec/security.md §Invite-code registration pins
    // this keying ("prevents a patient brute-force search of the UUID
    // space"). A per-code counter would add nothing at this entropy.
    private static final String COUNT_ATTEMPTS_SQL =
            "SELECT count(*) FROM invite_code_attempt "
                    + "WHERE adapter = ? AND contact_id = ? AND attempted_at > ?";

    private static final String CONSUME_INVITE_SQL =
            "UPDATE invite_code SET status = 'USED', used_at = NOW(), used_by_contact_id = ? "
                    + "WHERE code = ? AND status = 'PENDING' "
                    + "AND (expires_at IS NULL OR expires_at > NOW()) "
                    + "AND adapter = ? "
                    + "AND (invite_type = 'OPEN_ADAPTER' OR expected_contact_id = ?) "
                    + "RETURNING id";

    private static final String INSERT_USER_SQL =
            "INSERT INTO users (adapter, contact_id, is_admin, registration_state, probation_until) "
                    + "VALUES (?, ?, FALSE, 'invited', ?) "
                    + "ON CONFLICT (adapter, contact_id) DO NOTHING "
                    + "RETURNING id";

    private static final String SELECT_USER_ID_SQL =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String INSERT_ATTEMPT_SQL =
            "INSERT INTO invite_code_attempt (adapter, contact_id) VALUES (?, ?)";

    static final String INVITE_CONSUME = AuditAction.INVITE_CONSUME.name();
    static final String INVITE_BRUTE_FORCE_BREACH = AuditAction.INVITE_BRUTE_FORCE_BREACH.name();

    @ConfigProperty(name = "infochat.invite.brute-force-threshold", defaultValue = "10")
    int bruteForceThreshold;

    @ConfigProperty(name = "infochat.invite.brute-force-window", defaultValue = "1h")
    Duration bruteForceWindow;

    @ConfigProperty(name = "infochat.probation.duration", defaultValue = "24h")
    Duration probationDuration;

    @Inject
    DataSource dataSource;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    RegisteredContactSet registeredContactSet;

    // Sentinel map of (adapter, contact_id) tuples that have ALREADY
    // had an INVITE_BRUTE_FORCE_BREACH audit row written within the
    // current breach event, valued with the last over-threshold
    // observation time. A subsequent consume that observes the
    // counter back below threshold removes the key — so a re-breach
    // after the window expires audits again. A breached key whose
    // attacker simply walks away never travels that remove path; the
    // stale-entry sweep bounds the map instead. Package-private for
    // the eviction tests.
    final ConcurrentHashMap<Key, Instant> breachAudited = new ConcurrentHashMap<>();

    // Time-gate for the stale-entry sweep in evictStaleBreachAudited.
    // Package-private + volatile so the gating test can preset it the
    // same way it seeds breachAudited directly.
    volatile Instant lastSweep = Instant.EPOCH;

    public sealed interface Outcome
            permits Accepted, Rejected, BruteForceThresholdBreached {}

    public record Accepted(UUID userId) implements Outcome {}

    public record Rejected() implements Outcome {}

    public record BruteForceThresholdBreached() implements Outcome {}

    public Outcome consume(String adapter, String contactId, String body) {
        // Parse the normalized body into a UUID candidate. The router
        // (M1-044e fix) no longer pre-parses the body; the consumer owns
        // both "is this a UUID?" and "does this UUID match a PENDING
        // invite?" so a non-UUID probe also increments the brute-force
        // counter (closes the AUDIT-EVASION redteam finding).
        UUID candidateCode = parseUuid(body);
        evictStaleBreachAudited();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long attempts = countAttempts(conn, adapter, contactId);
                Key key = new Key(adapter, contactId);

                if (attempts >= bruteForceThreshold) {
                    // Rollback-safe breach-audit ordering: containsKey-check
                    // before insertAudit so a SQL fault on the audit INSERT
                    // does NOT permanently mark the key in the in-memory
                    // map. The put(key, now) runs AFTER conn.commit() so
                    // the in-memory mark only fires once the DB row is
                    // durable. If insertAudit OR commit throws, the outer
                    // catch rolls back the DB AND leaves breachAudited
                    // untouched — the next call retries the audit.
                    //
                    // Trade-off: if insertAudit succeeds but commit throws
                    // (rare), no DB row survives AND the next call retries
                    // the audit insert; the spec language "an audit row
                    // records the threshold breach" tolerates the over-
                    // once retry artifact in that narrow commit-failure
                    // window. Audit-insert failure (the common mode) is
                    // correctly retried by this ordering.
                    //
                    // The put also runs when the key is already marked: it
                    // refreshes the over-threshold observation time, so the
                    // stale-entry sweep can only evict a key that has been
                    // quiet for a full window — by which point every attempt
                    // inside the window has aged out and eviction is
                    // equivalent to the below-threshold remove path.
                    if (!breachAudited.containsKey(key)) {
                        insertAudit(conn, contactId, adapter, AuditAction.INVITE_BRUTE_FORCE_BREACH,
                                contactId, contactId);
                    }
                    conn.commit();
                    breachAudited.put(key, Instant.now());
                    return new BruteForceThresholdBreached();
                }
                // Counter under threshold — clear any stale sentinel for
                // this key (window expired, breach event ended).
                breachAudited.remove(key);

                // Two failure modes share the same Rejected branch: a
                // non-UUID body (parseUuid returned null) and a UUID that
                // does not match any PENDING invite (tryConsume returned
                // null). Both increment the brute-force counter so a
                // sustained attack of either shape accumulates toward the
                // threshold within the window.
                // TODO followup: register `invite_drop_total` Micrometer
                // counter (deferred from M1-044a).
                UUID inviteId = candidateCode == null
                        ? null
                        : tryConsume(conn, contactId, candidateCode, adapter);
                if (inviteId == null) {
                    insertAttempt(conn, adapter, contactId);
                    conn.commit();
                    return new Rejected();
                }

                UUID userId = insertOrSelectUser(conn, adapter, contactId);
                insertAudit(conn, contactId, adapter,
                        AuditAction.INVITE_CONSUME, userId.toString(), contactId);
                conn.commit();
                // M1-229 registered-set coherence: the contact is now an
                // 'invited' users row. Mark AFTER commit so a rolled-back
                // accept never leaves a stale registered entry (the
                // conservative-membership invariant). Their next inbound
                // routes to a per-id rate-cap bucket instead of the shared
                // stranger limiter.
                registeredContactSet.markRegistered(adapter, contactId);
                return new Accepted(userId);
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "InviteCodeConsumer.consume failed for adapter="
                                + adapter + " contact_id="
                                + ContactIds.redact(contactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InviteCodeConsumer.consume connection failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(contactId), e);
        }
    }

    // Opportunistic sweep, time-gated to at most once per window. An
    // entry whose last over-threshold observation is older than the
    // window is safe to drop: every attempt inside the window has aged
    // out, so the counter is below threshold and eviction is equivalent
    // to the remove path (window expired = breach event ended).
    //
    // The gate amortizes the full-map removeIf so an adapter with free
    // identity minting cannot make every unknown-contact consume pay an
    // O(N) scan. Per-key correctness never depends on the sweep — the
    // below-threshold path removes its own key inline — so gating only
    // stretches how long an abandoned key lingers: at most one window
    // (staleness) plus one gate interval (next sweep opportunity) after
    // the breach event ends. The check-then-set on the volatile is
    // deliberately not atomic: two racing consumes may both sweep,
    // which is benign (removeIf is idempotent) and cheaper than a CAS.
    private void evictStaleBreachAudited() {
        Instant now = Instant.now();
        if (now.isBefore(lastSweep.plus(bruteForceWindow))) {
            return;
        }
        lastSweep = now;
        Instant cutoff = now.minus(bruteForceWindow);
        breachAudited.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }

    private static @Nullable UUID parseUuid(String body) {
        try {
            return UUID.fromString(body);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private long countAttempts(Connection conn, String adapter, String contactId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COUNT_ATTEMPTS_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            ps.setObject(3, OffsetDateTime.now().minus(bruteForceWindow));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private @Nullable UUID tryConsume(Connection conn, String contactId, UUID candidateCode,
                            String adapter) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(CONSUME_INVITE_SQL)) {
            ps.setString(1, contactId);
            ps.setObject(2, candidateCode);
            ps.setString(3, adapter);
            ps.setString(4, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject(1, UUID.class);
                }
                return null;
            }
        }
    }

    private UUID insertOrSelectUser(Connection conn, String adapter, String contactId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_USER_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            ps.setObject(3, OffsetDateTime.now().plus(probationDuration));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject(1, UUID.class);
                }
            }
        }
        // ON CONFLICT DO NOTHING fired (defense-in-depth race against
        // an unrelated concurrent registration on the same (adapter,
        // contact_id)) — read back the row that won.
        try (PreparedStatement ps = conn.prepareStatement(SELECT_USER_ID_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "users row missing after invite-consume INSERT for adapter="
                                    + adapter + " contact_id="
                                    + ContactIds.redact(contactId));
                }
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private void insertAttempt(Connection conn, String adapter, String contactId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_ATTEMPT_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            ps.executeUpdate();
        }
    }

    private void insertAudit(Connection conn, String actorContactId, String actorAdapter,
                             AuditAction action, String targetId, String targetContactId)
            throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorContactId(actorContactId)
                .actorAdapter(actorAdapter)
                .action(action)
                .targetKind("user")
                .targetId(targetId)
                .targetContactId(targetContactId)
                .detailsJson("{}")
                .build();
        auditLogWriter.write(conn, row);
    }

    // Package-private (not private) so the eviction tests can seed
    // and inspect breachAudited directly.
    record Key(String adapter, String contactId) {
        Key {
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(contactId, "contactId");
        }
    }
}
