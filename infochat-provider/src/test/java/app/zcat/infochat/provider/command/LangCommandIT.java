package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
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
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IT for {@link LangCommandHandler} — walks the full inbound →
 * router → handler → adapter chain via {@link InMemoryAdapter}.
 *
 * <p>The two scenarios pin the T2-C.2 user-observable contract:
 * <ul>
 *   <li>{@code /lang cs} produces a Czech confirmation reply via the
 *       NEW 2-arg {@link BundleLoader#get(String, String)} accessor;
 *       a subsequent {@code /help} from the same DM still resolves
 *       through the legacy 1-arg accessor and lands in English. The
 *       cross-cutting per-scope migration of every handler is T2-F's
 *       responsibility — T2-C.2 ships ONLY the {@code LangCommandHandler}-
 *       side per-scope resolution, so {@code /help} remaining in English
 *       is the load-bearing back-compat property of the additive
 *       BundleLoader refactor.</li>
 *   <li>{@code /lang xx} (unsupported) surfaces a reply that lists
 *       both {@code en} and {@code cs} as supported codes verbatim,
 *       per spec §Conversation control "lists the supported codes —
 *       never a silent no-op and never a fall-through to the default."</li>
 * </ul>
 *
 * <p>The actor is seeded as {@code registration_state='vouched'} so
 * the intake-step probation gate (step 5) does not interfere — though
 * {@code /lang} is in the slow-start ALLOWED set regardless. Test
 * cleanup deletes per-prefix rows; no admin-row manipulation, so the
 * V5 last-admin-protection trigger does not fire.</p>
 */
@QuarkusTest
@TestProfile(LangCommandIT.RoundtripProfile.class)
class LangCommandIT {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-060-lang-it-";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "DELETE FROM scope_preferences WHERE scope_id IN "
                            + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN "
                                + "(SELECT id FROM users WHERE contact_id LIKE ?)",
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
        }
    }

    @Test
    void langCsRoundtripThroughInMemoryAdapter() throws Exception {
        String actor = PREFIX + "actor-cs";
        seedVouchedUser(actor);

        // /lang cs — confirmation reply must land in Czech (the NEW
        // 2-arg accessor against the just-set language).
        adapter.deliverDm(actor, "/lang cs");
        List<OutboundMessage> after1 = adapter.sentMessages();
        assertEquals(1, after1.size(),
                "step 1: /lang cs must produce exactly one outbound");
        String csConfirmation = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_LANG_SUCCESS, "cs"),
                "cs");
        assertEquals(csConfirmation, after1.get(0).text(),
                "step 1: outbound body must equal the cs.properties value of "
                        + "reply.lang.success interpolated with the just-set code");

        // /help — the legacy 1-arg accessor MUST still return the en
        // value. T2-C.2 ships ONLY LangCommandHandler-side per-scope
        // resolution; the cross-cutting migration is T2-F's. A future
        // regression that flipped HelpCommandHandler to the 2-arg
        // accessor would break this assertion — and rightly so until
        // T2-F lands.
        adapter.deliverDm(actor, "/help");
        List<OutboundMessage> after2 = adapter.sentMessages();
        assertEquals(2, after2.size(),
                "step 2: /help must produce one further outbound");
        String enHelp = String.join("\n",
                bundleLoader.get(BundleKeys.HELP_HEADER_DM_USER),
                bundleLoader.get(BundleKeys.HELP_CMD_HELP_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_STATUS_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_GET_TAGS_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_GET_SOURCES_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_SUMMARY_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_LIST_SOURCES_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_SAVE_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_SAVED_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_UNSAVE_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_EXPORT_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_ADD_SOURCE_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_FOLLOW_TAG_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_UNFOLLOW_TAG_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_LANG_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_CLEAR_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_COMPRESS_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_FORGET_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_STOP_SHORT),
                bundleLoader.get(BundleKeys.HELP_CMD_RETRY_SHORT));
        assertEquals(enHelp, after2.get(1).text(),
                "step 2: /help outbound must still resolve via the 1-arg accessor "
                        + "(en values verbatim) — T2-F migrates handlers wholesale to the "
                        + "2-arg per-scope accessor when chat-mode + digests land");
    }

    @Test
    void langUnsupportedCodeIT() throws Exception {
        String actor = PREFIX + "actor-xx";
        seedVouchedUser(actor);

        adapter.deliverDm(actor, "/lang xx");
        List<OutboundMessage> outbox = adapter.sentMessages();
        assertEquals(1, outbox.size(),
                "/lang xx must produce exactly one outbound (no silent no-op)");
        String body = outbox.get(0).text();
        assertTrue(body.contains("en"),
                "unsupported-code reply must list 'en' as a supported code — got: " + body);
        assertTrue(body.contains("cs"),
                "unsupported-code reply must list 'cs' as a supported code — got: " + body);
    }

    private void seedVouchedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, registration_state) "
                             + "VALUES (?, ?, 'vouched')")) {
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
     * Pins the minimum AdapterRegistry properties (non-empty list,
     * inmemory enabled with allow-low-trust) for the {@code inmemory}
     * adapter — mirrors {@code InviteIntakeRoundtripIT.RoundtripProfile}
     * and {@code AdapterRouterIT.MvpProfile}.
     */
    public static final class RoundtripProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }
    }
}
