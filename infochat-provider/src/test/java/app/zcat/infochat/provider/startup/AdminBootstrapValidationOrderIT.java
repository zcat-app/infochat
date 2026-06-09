package app.zcat.infochat.provider.startup;

import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-270 (deep-review H9): {@link AdminBootstrap} validates every
 * configured bootstrap admin contact id via the owning adapter's
 * {@code MessagingAdapter.isWellFormedContactId} BEFORE any users/audit
 * write — a malformed value aborts startup with NOTHING committed, so no
 * malformed admin row (or audit entry) survives the failed boot into the
 * next one.
 *
 * <p><b>Boot-path equivalence.</b> The {@code @Startup} boot path is
 * {@code @PostConstruct → seed(adaptersCsv)}; a {@code @QuarkusTest}
 * cannot assert its own container's boot failure, so these tests drive
 * the identical parameterized {@link AdminBootstrap#seed(String)} the
 * boot runs (the established {@link AdminBootstrapIT} pattern) and
 * assert the throw — which on a real boot propagates out of
 * {@code @PostConstruct} and aborts startup (Quarkus default, per the
 * {@link AdminBootstrap} class contract). This profile's own boot stays
 * on the {@code %test} default {@code infochat.adapters=inmemory}; the
 * malformed {@code signal.admin} below is only reached by the explicit
 * {@code seed(...)} calls.</p>
 */
@QuarkusTest
@TestProfile(AdminBootstrapValidationOrderIT.Profile.class)
class AdminBootstrapValidationOrderIT {

    private static final String MALFORMED_SIGNAL_ACI = "not-a-valid-aci-m1270";
    private static final String FAKE_X_ADMIN = "m1270-order-fake-x-admin";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    AdminBootstrap adminBootstrap;

    @Test
    void malformedSignalAdminAbortsSeedingWithNoUsersRowAndNoAuditRow() throws Exception {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> adminBootstrap.seed("signal"));

        assertTrue(e.getMessage().contains("infochat.adapters.signal.admin"),
                "the failure must name the offending property, got: " + e.getMessage());
        assertEquals(0, usersCountAt("signal", MALFORMED_SIGNAL_ACI),
                "validate-before-write: no users row may be committed for the malformed value");
        assertEquals(0, bootstrapAuditCountFor(MALFORMED_SIGNAL_ACI),
                "validate-before-write: no audit row may be committed for the malformed value");
    }

    @Test
    void malformedValueInAnySlotAbortsBeforeAnyWriteAcrossAllAdapters() throws Exception {
        // fake-x precedes signal in the CSV and its admin is well-formed
        // (FakeAdapterX accepts any id); a write-then-validate order would
        // commit the fake-x row before signal's validation throws. The
        // all-validate-before-first-write order leaves BOTH slots clean.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> adminBootstrap.seed("fake-x,signal"));

        assertTrue(e.getMessage().contains("infochat.adapters.signal.admin"),
                "the failure must name the malformed slot, got: " + e.getMessage());
        assertEquals(0, usersCountAt("fake-x", FAKE_X_ADMIN),
                "the well-formed slot must not be committed when a later slot is malformed");
        assertEquals(0, bootstrapAuditCountFor(FAKE_X_ADMIN),
                "no audit row may exist for the well-formed slot of an aborted seeding");
        assertEquals(0, usersCountAt("signal", MALFORMED_SIGNAL_ACI),
                "the malformed slot must not be committed");
    }

    private int usersCountAt(String adapter, String contactId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int bootstrapAuditCountFor(String contactId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log"
                             + " WHERE action = 'BOOTSTRAP_ADMIN' AND target_contact_id = ?")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // The boot inherits infochat.adapters=inmemory plus its
            // well-formed admin contact from the base %test profile; the
            // entries below are reached only by the parameterized
            // seed(...) calls — neither signal nor fake-x is activated.
            return Map.of(
                    "infochat.adapters.signal.admin", MALFORMED_SIGNAL_ACI,
                    "infochat.adapters.fake-x.admin", FAKE_X_ADMIN);
        }
    }
}
