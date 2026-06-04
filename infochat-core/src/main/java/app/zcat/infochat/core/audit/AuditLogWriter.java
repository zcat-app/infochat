package app.zcat.infochat.core.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Sole application-layer INSERT path into {@code audit_log}. Every
 * raw-JDBC {@code INSERT INTO audit_log} call site that existed in
 * M1 before this ticket has been migrated to call
 * {@link #write(Connection, RedactionHook.AuditRow)}; the SOLE-WRITER
 * grep (acceptance item 1) holds because the only remaining
 * {@code INSERT INTO audit_log} text under each module's
 * {@code src/main} tree lives inside this class.
 *
 * <p>SECURITY DEFINER stored procedures
 * ({@code delete_preban_user} from V5, {@code approve_quarantine}
 * and {@code reject_quarantine} from V10) carve out a separate
 * internal-INSERT path: those procedures write their own audit
 * rows in SQL inside the procedure body and do NOT route through
 * this writer. Acceptance item 1's SOLE-WRITER grep is scoped to
 * Java sources only ({@code src/main}); the procedure-body
 * INSERTs live under {@code db/migration/} and are correctly
 * excluded.</p>
 *
 * <h2>Caller-supplied transaction</h2>
 * <p>The writer accepts an explicit {@link Connection} on its sole
 * entry point. Every migrated call site manages its own JDBC
 * connection with {@code setAutoCommit(false)} + manual
 * {@code commit()} / {@code rollback()}, NOT ambient
 * {@code @Transactional} JTA, so the audit row must commit (or
 * roll back) in the caller's transaction. A writer overload that
 * opens its own Connection would defeat Invariant 7's audit-
 * before-effect commitment for these six call sites (a rolled-back
 * mutation would leave its pre-written audit row behind).</p>
 *
 * <h2>Redaction</h2>
 * <p>Every row passes through {@link RedactionHook#redact} before
 * the INSERT. The default {@link DefaultRedactionHook} applies the
 * closed API-key-shape catalogue from
 * {@code docs/spec/security.md} §Secrets handling to
 * {@code details_json}. Tests can substitute an alternative
 * {@link RedactionHook} via CDI {@code @Alternative} (the SPI
 * boundary documented at {@link RedactionHook}).</p>
 */
@ApplicationScoped
public class AuditLogWriter {

    private static final String INSERT_SQL =
            "INSERT INTO audit_log ("
                    + "  actor_user_id, actor_contact_id, actor_adapter,"
                    + "  action, target_kind, target_id, target_contact_id,"
                    + "  scope_id, request_id, details_json"
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)";

    @Inject
    RedactionHook redactionHook;

    /**
     * Public no-arg constructor for the CDI runtime. Quarkus ARC
     * instantiates the bean via this constructor and field-injects
     * {@link #redactionHook}.
     */
    public AuditLogWriter() {
    }

    /**
     * Constructor-injection form for non-CDI consumers (plain
     * JUnit tests that do not stand up the Quarkus container).
     * Production code uses the no-arg form and lets CDI inject
     * the hook.
     *
     * @param redactionHook the redaction layer applied to every
     *                      row before INSERT.
     */
    public AuditLogWriter(@NonNull RedactionHook redactionHook) {
        this.redactionHook = redactionHook;
    }

    /**
     * INSERT one {@code audit_log} row in the caller's transaction.
     * The row is first handed to {@link RedactionHook#redact} —
     * the redacted form is what reaches the database.
     *
     * @param conn the caller's Connection. The writer does NOT
     *             call {@code commit()} or {@code rollback()} —
     *             the caller's transaction owns the row's
     *             durability.
     * @param row  the audit row to write. Built by the caller
     *             from its dispatch state; the writer treats it
     *             as opaque past the redaction step.
     * @throws SQLException if the INSERT itself fails (driver,
     *                      role grant, append-only trigger, etc.);
     *                      the caller is responsible for rolling
     *                      back the surrounding transaction.
     */
    public void write(@NonNull Connection conn, RedactionHook.@NonNull AuditRow row) throws SQLException {
        RedactionHook.AuditRow redacted = redactionHook.redact(row);
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            if (redacted.actorUserId() == null) {
                ps.setNull(1, Types.OTHER);
            } else {
                ps.setObject(1, redacted.actorUserId());
            }
            setNullableString(ps, 2, redacted.actorContactId());
            setNullableString(ps, 3, redacted.actorAdapter());
            ps.setString(4, redacted.action().name());
            ps.setString(5, redacted.targetKind());
            ps.setString(6, redacted.targetId());
            setNullableString(ps, 7, redacted.targetContactId());
            if (redacted.scopeId() == null) {
                ps.setNull(8, Types.OTHER);
            } else {
                ps.setObject(8, redacted.scopeId());
            }
            setNullableString(ps, 9, redacted.requestId());
            // The {@code ::jsonb} cast in the SQL handles a null
            // details_json correctly (PostgreSQL casts a SQL NULL
            // to JSONB NULL). The redacted detailsJson may be null
            // (e.g. SourceUpsertService writes a NULL details
            // column) or a JSON-shaped string; either way the
            // String binding + ::jsonb cast is correct.
            setNullableString(ps, 10, redacted.detailsJson());
            ps.executeUpdate();
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, @Nullable String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
