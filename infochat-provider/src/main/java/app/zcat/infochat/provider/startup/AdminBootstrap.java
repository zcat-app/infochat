package app.zcat.infochat.provider.startup;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bootstrap-admin seeding bean per {@code docs/spec/deployment.md}
 * §Operator inputs item 2 + §Bootstrap behavior on startup (decision
 * D9): for every enabled adapter that has a configured
 * {@code infochat.adapters.<name>.admin} contact id, ensures a
 * {@code users} row exists at {@code (adapter, contact_id)} with
 * {@code is_admin = true} (creating the user if needed) and writes a
 * {@code BOOTSTRAP_ADMIN} row to {@code audit_log} with
 * {@code details_json.cause = 'bootstrap'}.
 *
 * <p><b>Bean ordering.</b> {@code @Priority(200)} per the Provider
 * startup table in {@code docs/design/01-architecture.md} §1.4.3 —
 * after Flyway (100), before the new-post reconciler (250) and
 * adapter activation (300), so the admin row exists before any
 * adapter serves traffic. A seeding failure propagates and aborts
 * startup (Quarkus default): a deployment whose admin ensure failed
 * could come up with zero {@code is_admin = true} rows, which is the
 * unusable-bot state this bean exists to prevent.</p>
 *
 * <p><b>Drift / rotation behavior</b> (spec §Bootstrap behavior on
 * startup — Bootstrap admin drift): a configured contact id that does
 * not match an existing admin row gets a new row; prior admin rows
 * are left in place with {@code is_admin = true} intact. Pruning
 * stale bootstrap admins is an explicit operator action via
 * {@code /revoke-admin}, never an automatic startup effect.</p>
 *
 * <p><b>Contact ids are opaque here.</b> The adapter-specific
 * contact-id format validation the spec promises ("each value MUST be
 * parseable by its own adapter") needs a parsing surface on the
 * messaging SPI that does not exist yet; this bean treats the
 * configured value as an opaque string, exactly like Gate 7 in
 * {@link app.zcat.infochat.provider.messaging.AdapterRegistry}.</p>
 */
@Startup
@Priority(200)
@ApplicationScoped
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    /**
     * One-statement ensure: the INSERT arm creates the row with the
     * spec's bootstrap-seeded shape ({@code is_admin = true},
     * {@code is_banned = false}, {@code registration_state =
     * 'vouched'}, {@code probation_until = NULL} — bootstrap admins
     * skip the slow-start tier); the conflict arm promotes an
     * existing non-admin row by setting {@code is_admin} ONLY,
     * leaving registration_state / probation / ban columns untouched
     * (the creation shape applies to rows this bean creates, not to
     * pre-existing users the operator points the config at). The
     * {@code WHERE} filter makes the already-admin case a no-op, so
     * {@code RETURNING} yields a row exactly when something changed —
     * that is the audit gate: re-running startup with unchanged
     * configuration writes no duplicate audit rows.
     */
    private static final String ENSURE_ADMIN_SQL =
            "INSERT INTO users (adapter, contact_id, is_admin, is_banned,"
                    + " registration_state, probation_until)"
                    + " VALUES (?, ?, TRUE, FALSE, 'vouched', NULL)"
                    + " ON CONFLICT (adapter, contact_id)"
                    + " DO UPDATE SET is_admin = TRUE WHERE users.is_admin = FALSE"
                    + " RETURNING id";

    @Inject
    DataSource dataSource;

    @Inject
    AuditLogWriter auditLogWriter;

    @ConfigProperty(name = "infochat.adapters")
    String adaptersCsv;

    @PostConstruct
    void onStartup() {
        seed(adaptersCsv);
    }

    /**
     * Ensure the bootstrap-admin row for every adapter in the given
     * comma-separated enabled-adapter list that has a configured
     * {@code infochat.adapters.<name>.admin} value. An adapter
     * without one is skipped — the property is optional per adapter;
     * Gate 7 in {@code AdapterRegistry} enforces the union-non-empty
     * constraint at activation. The parameterized form exists so
     * tests can exercise multi-adapter and rotation shapes without
     * round-tripping through Quarkus config sources (mirroring
     * {@code AdapterRegistry.start(String)}).
     */
    public void seed(String csv) {
        Config config = ConfigProvider.getConfig();
        try (Connection conn = dataSource.getConnection()) {
            // All adapters seed in one transaction: a failure on any
            // adapter aborts startup anyway, and partial seeding would
            // leave audit_log claiming rows a rolled-back users INSERT
            // never durably created.
            conn.setAutoCommit(false);
            try {
                for (String adapterName : parseAdaptersList(csv)) {
                    String contactId = config.getOptionalValue(
                            "infochat.adapters." + adapterName + ".admin",
                            String.class).orElse("");
                    if (contactId.isBlank()) {
                        continue;
                    }
                    ensureAdmin(conn, adapterName, contactId);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            // Adapter names are operator config, never secrets; the
            // configured contact id deliberately stays out of the
            // message (contact-id redaction discipline, security.md
            // §Secrets handling).
            throw new IllegalStateException("bootstrap admin seeding failed", e);
        }
    }

    private void ensureAdmin(Connection conn, String adapterName, String contactId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(ENSURE_ADMIN_SQL)) {
            ps.setString(1, adapterName);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Already is_admin = true at (adapter, contact_id):
                    // idempotent no-op, no audit row.
                    return;
                }
                UUID userId = rs.getObject(1, UUID.class);
                // System-actor row: actor columns stay null per the
                // RedactionHook.AuditRow contract for bootstrap /
                // startup-bean writers.
                auditLogWriter.write(conn, RedactionHook.AuditRow.builder()
                        .action(AuditAction.BOOTSTRAP_ADMIN)
                        .targetKind("user")
                        .targetId(userId.toString())
                        .targetContactId(contactId)
                        .detailsJson("{\"cause\":\"bootstrap\"}")
                        .build());
                log.info("bootstrap admin ensured: adapter={}", adapterName);
            }
        }
    }

    private static List<String> parseAdaptersList(String csv) {
        if (csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
