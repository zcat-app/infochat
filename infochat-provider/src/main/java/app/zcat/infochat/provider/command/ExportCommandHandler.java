package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * {@code /export} — returns the calling user's own data as an in-band
 * JSON reply. Audit-logged before effect. Delivery is in-band: the
 * export is sent as the reply message on the same adapter channel;
 * no external URLs or out-of-band download links.
 *
 * <p>Group scope is rejected in v1: the frozen {@link CommandHandler}
 * SPI does not carry the inbound actor's identity in group scope
 * ({@link ScopeRef.Group} holds only the adapter-side group id).
 * {@link ExportDataCollector} supports group-scope queries when
 * called with explicit parameters; the handler's group path will be
 * enabled when T2-F wires the actor seam.
 */
@ApplicationScoped
public class ExportCommandHandler implements CommandHandler {

    static final String GROUP_NOT_SUPPORTED_REPLY =
            "Group /export is not available yet. Please use /export in a DM.";

    static final String NO_USER_REPLY =
            "Could not resolve your account. Please try again.";

    /** 32-char header budget per the spec (covers page=N/T + fences). */
    static final int HEADER_BUDGET = 32;

    private static final String USER_ID_SQL =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    @Inject
    DataSource dataSource;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    InboundContext inboundContext;

    @Inject
    ExportDataCollector dataCollector;

    /**
     * Export page cap per hardware profile. The effective page cap for
     * export pagination is this value minus {@link #HEADER_BUDGET}.
     * Separate from the chat-mode body cap ({@code infochat.chat.body-cap})
     * because export output sizing and chat input gating are independent
     * concerns with different test-profile values.
     */
    @ConfigProperty(name = "infochat.export.page-cap", defaultValue = "2048")
    int exportPageCap;

    @Override
    public String name() {
        return "export";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        if (scope instanceof ScopeRef.Group) {
            return reply(scope, GROUP_NOT_SUPPORTED_REPLY);
        }

        ScopeRef.Dm dm = (ScopeRef.Dm) scope;
        String contactId = dm.contactId();
        String adapter = inboundContext.adapterName();

        UUID userId = lookupUserId(adapter, contactId);
        if (userId == null) {
            return reply(scope, NO_USER_REPLY);
        }

        // Audit-logged before effect (Invariant 7).
        writeAuditRow(userId, contactId, adapter);

        ExportDataCollector.ExportResult result =
                dataCollector.collect(userId, "dm", userId);

        int effectiveCap = exportPageCap - HEADER_BUDGET;
        List<String> pages = ExportPaginator.paginate(result.tables(), effectiveCap);

        String body = formatPages(pages);
        if (!result.truncatedTables().isEmpty()) {
            body += "\n\nSome tables exceeded the row limit and were truncated: "
                    + String.join(", ", result.truncatedTables()) + ".";
        }
        return reply(scope, body);
    }

    private UUID lookupUserId(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(USER_ID_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject("id", UUID.class) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ExportCommandHandler.lookupUserId failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(contactId), e);
        }
    }

    private void writeAuditRow(UUID userId, String contactId, String adapter) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                        .actorUserId(userId)
                        .actorContactId(contactId)
                        .actorAdapter(adapter)
                        .action(AuditAction.EXPORT)
                        .targetKind("user")
                        .targetId(userId.toString())
                        .requestId(UUID.randomUUID().toString())
                        .build();
                auditLogWriter.write(conn, row);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ExportCommandHandler.writeAuditRow failed for user="
                            + userId, e);
        }
    }

    /**
     * Format paginated JSON pages into a single reply string. Single
     * page: no page marker. Multi-page: each page gets a
     * {@code page=N/T} header before the opening fence.
     */
    static String formatPages(List<String> pages) {
        if (pages.size() == 1) {
            return "```json\n" + pages.getFirst() + "\n```";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append("page=").append(i + 1).append('/').append(pages.size())
              .append('\n');
            sb.append("```json\n").append(pages.get(i)).append("\n```");
        }
        return sb.toString();
    }

    private static OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(
                scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
