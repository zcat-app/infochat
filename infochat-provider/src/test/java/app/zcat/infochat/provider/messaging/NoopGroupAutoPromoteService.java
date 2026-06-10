package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.provider.group.GroupAutoPromoteService;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * No-op {@link GroupAutoPromoteService} — never promotes and touches no
 * JDBC ({@code tryAutoPromote} is the class's only entry point, so the
 * super collaborators are never exercised). Log-silent like the other
 * Noop doubles in this package, so pinned call-order assertions stay
 * unchanged.
 */
final class NoopGroupAutoPromoteService extends GroupAutoPromoteService {

    NoopGroupAutoPromoteService(DataSource dataSource) {
        super(dataSource, new AuditLogWriter(row -> row));
    }

    @Override
    public boolean tryAutoPromote(UUID groupId, UUID userId,
                                  String adapter, String contactId) {
        return false;
    }
}
