package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.text.MessageFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * M1-483: /group-timezone must (1) return a usage error — not the not-admin
 * reply — for a missing argument, and (2) run the authorization gate BEFORE
 * the ZoneId.of validation + full-IANA fuzzy scan, so an unauthorized member
 * cannot force the zone work with an invalid tz.
 */
@QuarkusTest
class GroupTimezoneAuthOrderTest {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-483-gtz-";
    private static final String UPSTREAM_GROUP_ID = PREFIX + "group-" + UUID.randomUUID();

    @Inject GroupTimezoneCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject GroupRepository groupRepository;

    private String botAdminContactId;
    private String regularUserContactId;

    @BeforeEach
    void setup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);

        botAdminContactId = PREFIX + "botadmin-" + UUID.randomUUID();
        regularUserContactId = PREFIX + "regular-" + UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            cleanTestData(conn);

            // Guardian admin keeps a bot admin present independent of this test's data.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-" + PREFIX + "permanent");

            // Bot admin
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE",
                    ADAPTER, botAdminContactId);

            // Regular user (neither bot admin nor group admin)
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, FALSE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO NOTHING",
                    ADAPTER, regularUserContactId);
        }

        groupRepository.findOrCreateByAdapterAndUpstreamId(ADAPTER, UPSTREAM_GROUP_ID);
    }

    @Test
    void groupTimezone_missingArg_returnsUsageErrorNotNotAdmin() {
        inboundContext.setSenderContactId(botAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/group-timezone");

        String expectedUsage = MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT),
                "/group-timezone <tz>");
        assertEquals(expectedUsage, result.text());
        assertNotEquals(bundleLoader.get(BundleKeys.ERROR_GROUP_TIMEZONE_NOT_ADMIN), result.text());
    }

    @Test
    void groupTimezone_unauthorizedInvalidZone_rejectedBeforeZoneValidation() {
        inboundContext.setSenderContactId(regularUserContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        // An invalid zone from an unauthorized caller: the auth gate must fire
        // first, so the reply is the not-admin error, never the invalid-zone
        // error that the (now post-auth) ZoneId.of path would produce.
        OutboundMessage result = handler.handle(scope, "/group-timezone NotAReal/Zone");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_GROUP_TIMEZONE_NOT_ADMIN), result.text());
    }

    private void cleanTestData(Connection conn) throws Exception {
        exec(conn,
                "DELETE FROM group_membership WHERE group_id IN "
                        + "(SELECT id FROM groups WHERE upstream_group_id = ?)",
                UPSTREAM_GROUP_ID);
        exec(conn,
                "DELETE FROM groups WHERE upstream_group_id = ?",
                UPSTREAM_GROUP_ID);
    }

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
