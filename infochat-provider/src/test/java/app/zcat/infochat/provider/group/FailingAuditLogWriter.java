package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Test double whose {@link #write} always throws, simulating an
 * audit-write failure so tests can assert the audit-before-effect
 * transaction rolls the state mutation back (Invariant 7). The
 * failure message mimics a Postgres constraint-violation DETAIL
 * line echoing the inserted tuple (unredacted contact id included),
 * so sanitization assertions are non-vacuous: a propagation path
 * that leaks the SQLException message would leak the contact id.
 */
class FailingAuditLogWriter extends AuditLogWriter {

    @Override
    public void write(Connection conn, RedactionHook.AuditRow row)
            throws SQLException {
        throw new SQLException("simulated audit-write failure DETAIL: "
                + "(actor_contact_id, details_json)=(" + row.actorContactId()
                + ", " + row.detailsJson() + ") already exists.");
    }
}
