package app.zcat.infochat.provider.messaging;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory set of registered {@code (adapter, contactId)} keys
 * consulted at {@link InboundRouter} step 1.5 to split the inbound
 * rate cap (M1-229): a registered sender gets a per-id
 * {@link RateCapBucket} bucket; an unregistered sender (a miss here)
 * shares a single per-adapter stranger limiter and never mints per-id
 * rate-cap state. This is the remediation for the M1-205 medium DOS
 * finding — a Sybil flood of distinct stranger ids no longer pins the
 * per-id bucket map at {@code maxContactBuckets} and freezes new-user
 * registration.
 *
 * <p><b>Why a set and not a DB read.</b> Step 1.5 runs BEFORE the
 * router's single users-row SELECT ({@code InboundRouter.lookupUser},
 * deliberately downstream of the rate cap). Resolving registered-vs-
 * stranger with a DB query per inbound would re-introduce the per-
 * stranger DB flood the cap exists to prevent. This set keeps the
 * route decision a pure in-memory lookup.</p>
 *
 * <p><b>Conservative membership is the hard invariant.</b> The set
 * must NEVER report a stranger as registered — that would re-create
 * per-id state for strangers, the exact bug this class prevents. A
 * registered user briefly mis-routed to the shared stranger bucket
 * (e.g. between restart and rehydration, or before a fresh
 * registration's {@link #markRegistered}) is tolerable: transient
 * contention, no per-id state leak. Population is therefore
 * conservative — keys enter only from the boot rehydration query below
 * and from committed registration effects ({@code InviteCodeConsumer}
 * accept, {@code UnbanCommandHandler} unban of a registered identity);
 * keys leave on a committed ban.</p>
 *
 * <p><b>Memory bound.</b> The set grows only on invite-accept (admin-
 * gated) and unban, and shrinks on ban — it cannot be grown by a
 * stranger flood, so unlike the per-id bucket map it needs no hard cap
 * or eviction. Its size is bounded by the invite-gated registered
 * population.</p>
 *
 * <p><b>Boot rehydration.</b> The set is volatile across Provider
 * restarts, so {@code @PostConstruct} re-seeds it from {@code users}.
 * {@code @Priority(270)} runs the seed after the bootstrap-admin ensure
 * ({@code AdminBootstrap}, priority 200 — so the bootstrap admin is a
 * registered identity in the set) and before adapter activation
 * ({@code AdapterRegistry}/{@code MessagingStartup}, priority 300 — so
 * the set is populated before any adapter serves inbound). Flyway (100)
 * has already migrated the schema by the time any {@code @Startup} bean
 * runs.</p>
 */
@Startup
@Priority(270)
@ApplicationScoped
public class RegisteredContactSet {

    // Eligibility predicate (acceptance item 5): only invited / vouched
    // contacts that are not banned are registered. A 'preban' row (a
    // never-registered ban target), a banned row, and a missing row are
    // all strangers — none may be reported as registered.
    private static final String SEED_SQL =
            "SELECT adapter, contact_id FROM users "
                    + "WHERE registration_state IN ('invited', 'vouched') "
                    + "AND is_banned = FALSE";

    @Inject
    DataSource dataSource;

    private final Set<Key> registered = ConcurrentHashMap.newKeySet();

    public RegisteredContactSet() {
        // CDI no-arg constructor; @Inject DataSource populated post-construction.
    }

    /**
     * Rehydrate the set from {@code users} at Provider startup.
     * Package-private (not {@code @PostConstruct}-private) and
     * clear-then-load so {@code RegisteredContactSetTest} can re-seed
     * after staging rows. A seeding failure propagates and aborts
     * startup — coming up with an empty set would route every
     * registered user to the shared stranger bucket until they next
     * registered, an availability regression, not a security one.
     */
    @PostConstruct
    void seed() {
        Set<Key> reloaded = ConcurrentHashMap.newKeySet();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SEED_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                reloaded.add(new Key(rs.getString("adapter"), rs.getString("contact_id")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("RegisteredContactSet seed failed", e);
        }
        registered.clear();
        registered.addAll(reloaded);
    }

    /**
     * @return {@code true} iff {@code (adapter, contactId)} is a known
     *         registered identity. A {@code false} routes the inbound
     *         to the shared stranger limiter — the conservative default
     *         (a stranger is never reported registered).
     */
    public boolean isRegistered(String adapter, String contactId) {
        return registered.contains(new Key(adapter, contactId));
    }

    /**
     * Record a newly-registered identity. Called AFTER the committing
     * transaction (invite-accept, unban of a registered identity) so a
     * rolled-back registration never leaves a stale set entry.
     */
    public void markRegistered(String adapter, String contactId) {
        registered.add(new Key(adapter, contactId));
    }

    /**
     * Drop an identity from the set. Called AFTER a committed ban so the
     * banned contact's next inbound routes to the shared stranger bucket
     * and any per-id rate-cap state stops being refreshed.
     */
    public void invalidate(String adapter, String contactId) {
        registered.remove(new Key(adapter, contactId));
    }

    /**
     * Test-only seam: current registered-key count. Package-private —
     * production callers consult {@link #isRegistered}, never the size.
     */
    int size() {
        return registered.size();
    }

    private record Key(String adapter, String contactId) {
        Key {
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(contactId, "contactId");
        }
    }
}
