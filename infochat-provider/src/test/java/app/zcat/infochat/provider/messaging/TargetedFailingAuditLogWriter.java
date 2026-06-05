package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Test double whose {@link #write} throws for one configured target id
 * and delegates every other row to a real writer, so a sibling event's
 * audit row genuinely lands while the targeted event's transaction
 * fails. Delegate-style rather than {@code super.write}: the no-arg
 * super constructor leaves the redaction hook uninjected outside CDI.
 * The failure message mimics a Postgres constraint-violation DETAIL
 * line (the {@code FailingAuditLogWriter} pattern) so sanitization
 * assertions stay non-vacuous.
 */
class TargetedFailingAuditLogWriter extends AuditLogWriter {

    private final AuditLogWriter delegate;
    private final String failingTargetId;

    TargetedFailingAuditLogWriter(AuditLogWriter delegate, String failingTargetId) {
        this.delegate = delegate;
        this.failingTargetId = failingTargetId;
    }

    @Override
    public void write(Connection conn, RedactionHook.AuditRow row) throws SQLException {
        if (failingTargetId.equals(row.targetId())) {
            throw new SQLException("simulated audit-write failure DETAIL: "
                    + "(actor_contact_id, details_json)=(" + row.actorContactId()
                    + ", " + row.detailsJson() + ") already exists.");
        }
        delegate.write(conn, row);
    }
}
