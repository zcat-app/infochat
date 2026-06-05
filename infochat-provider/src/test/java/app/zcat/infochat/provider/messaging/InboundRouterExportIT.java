package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-stack integration test for {@code /export} through the
 * {@link InboundRouter} dispatch chain: adapter → router → handler →
 * adapter outbound.
 */
@QuarkusTest
@TestProfile(InboundRouterExportIT.ExportProfile.class)
class InboundRouterExportIT {

    private static final String PREFIX = "m1-067-it-";
    private static final String ADAPTER = "inmemory";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    void setup() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Clean audit rows under PREFIX (triggers must be disabled).
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn, "DELETE FROM audit_log WHERE actor_user_id IN ("
                        + "SELECT id FROM users WHERE contact_id LIKE ?)", PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
        }
    }

    @Test
    void rateLimitedInCorrectBucket() throws Exception {
        String contactId = PREFIX + "rate-actor";
        seedUser(contactId);

        // The profile sets rate-cap to 2 per minute. Send 3 requests;
        // the third should be silently dropped (no reply).
        adapter.deliverDm(contactId, "/export");
        adapter.deliverDm(contactId, "/export");
        adapter.deliverDm(contactId, "/export");

        List<OutboundMessage> sent = adapter.sentMessages();
        assertTrue(sent.size() <= 2,
                "rate limiter must drop requests beyond the per-minute cap;"
                        + " got " + sent.size() + " replies for 3 requests");
    }

    @Test
    void deliveredInBand() throws Exception {
        String contactId = PREFIX + "inband-actor";
        seedUser(contactId);

        adapter.deliverDm(contactId, "/export");

        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(),
                "/export must produce exactly one reply message");

        String text = sent.getFirst().text();
        // In-band: the reply contains the JSON export, not a URL.
        assertTrue(text.contains("```json"),
                "export reply must contain triple-backtick JSON fence (in-band delivery)");
        assertFalse(text.contains("http://") || text.contains("https://"),
                "export reply must NOT contain external URLs (in-band delivery)");
    }

    // -- helpers --

    private void seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned,"
                             + " registration_state)"
                             + " VALUES (?, ?, FALSE, FALSE, 'vouched')"
                             + " ON CONFLICT (adapter, contact_id) DO NOTHING")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Test profile that lowers the rate cap so the rate-limit test
     * doesn't need 60+ requests.
     */
    public static class ExportProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.rate-cap.inbound-per-minute", "2");
        }
    }
}
