package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link RegisteredContactSet} boot-rehydration eligibility
 * filter (M1-229 acceptance item 5) and the in-memory mutators. The
 * eligibility predicate is the security-critical part: a wrong filter
 * would mark a {@code 'preban'} (or banned, or absent) contact as
 * registered, routing it to a per-id rate-cap bucket — the exact
 * false-positive-for-stranger error the split exists to prevent.
 *
 * <p>Rows are staged via {@code @SeedDataSource} (the same DB the
 * application's default {@link DataSource} reads), then
 * {@link RegisteredContactSet#seed()} is re-run so the set reflects the
 * staged state. Assertions check specific {@code rcs-*} ids so other
 * rows in the shared schema (bootstrap admin, sibling tests) cannot
 * make the test flaky.</p>
 */
@QuarkusTest
@TestProfile(RegisteredContactSetTest.Profile.class)
class RegisteredContactSetTest {

    private static final String ADAPTER = "inmemory";

    @Inject
    RegisteredContactSet registeredContactSet;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @BeforeEach
    void stageRowsAndReseed() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement clean = conn.prepareStatement(
                     "DELETE FROM users WHERE adapter = ? AND contact_id LIKE 'rcs-%'")) {
            clean.setString(1, ADAPTER);
            clean.executeUpdate();
        }
        seedUser("rcs-invited", "invited", false);
        seedUser("rcs-vouched", "vouched", false);
        seedUser("rcs-preban", "preban", true);
        // is_banned=TRUE with an otherwise-eligible state isolates the
        // is_banned clause of the filter.
        seedUser("rcs-banned", "vouched", true);
        // rcs-absent: deliberately no row.

        registeredContactSet.seed();
    }

    @Test
    void eligibilityFilterIsConservative() {
        assertTrue(registeredContactSet.isRegistered(ADAPTER, "rcs-invited"),
                "an invited, non-banned contact is registered");
        assertTrue(registeredContactSet.isRegistered(ADAPTER, "rcs-vouched"),
                "a vouched, non-banned contact is registered");
        assertFalse(registeredContactSet.isRegistered(ADAPTER, "rcs-preban"),
                "a 'preban' contact (never-registered ban target) must NOT be reported registered");
        assertFalse(registeredContactSet.isRegistered(ADAPTER, "rcs-banned"),
                "a banned contact must NOT be reported registered");
        assertFalse(registeredContactSet.isRegistered(ADAPTER, "rcs-absent"),
                "a contact with no users row must NOT be reported registered");
    }

    @Test
    void markRegisteredThenInvalidateMutatesMembership() {
        assertFalse(registeredContactSet.isRegistered(ADAPTER, "rcs-dynamic"),
                "precondition: not registered before markRegistered");
        registeredContactSet.markRegistered(ADAPTER, "rcs-dynamic");
        assertTrue(registeredContactSet.isRegistered(ADAPTER, "rcs-dynamic"),
                "markRegistered adds the key");
        registeredContactSet.invalidate(ADAPTER, "rcs-dynamic");
        assertFalse(registeredContactSet.isRegistered(ADAPTER, "rcs-dynamic"),
                "invalidate removes the key");
    }

    private void seedUser(String contactId, String registrationState, boolean banned)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, probation_until) "
                             + "VALUES (?, ?, FALSE, ?, ?, NULL)")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, banned);
            ps.setString(4, registrationState);
            ps.executeUpdate();
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true"
            );
        }
    }
}
