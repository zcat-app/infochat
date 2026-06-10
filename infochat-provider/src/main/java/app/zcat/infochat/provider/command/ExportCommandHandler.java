package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * {@code /export [--page N]} — returns the calling user's own data as
 * an in-band JSON reply. Audit-logged before effect. Delivery is
 * in-band: the export is sent as the reply message on the same adapter
 * channel; no external URLs or out-of-band download links.
 *
 * <p>When the export exceeds the per-message page cap, each reply
 * carries exactly one page ({@code page=N/T} header) and the remaining
 * pages are fetched by re-invoking with {@code --page N} (1-indexed,
 * the corpus paging shape — see {@link ListSourcesCommandHandler}).
 * The {@link CommandHandler} SPI returns a single reply per inbound
 * command, so pages map to re-invocations rather than a multi-message
 * send.
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

    /** 32-char header budget per the spec (covers page=N/T + fences). */
    static final int HEADER_BUDGET = 32;

    @Inject
    DataSource dataSource;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    InboundContext inboundContext;

    @Inject
    ExportDataCollector dataCollector;

    @Inject
    UserRepository userRepository;

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
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        String language = inboundContext.effectiveLanguage();
        if (scope instanceof ScopeRef.Group) {
            return reply(scope, bundleLoader.get(
                    BundleKeys.ERROR_EXPORT_GROUP_NOT_SUPPORTED, language));
        }

        ScopeRef.Dm dm = (ScopeRef.Dm) scope;
        String contactId = dm.contactId();
        String adapter = inboundContext.adapterName();

        UUID userId = lookupUserId(adapter, contactId);
        if (userId == null) {
            return reply(scope, bundleLoader.get(
                    BundleKeys.ERROR_EXPORT_NO_USER, language));
        }

        // Audit-logged before effect (Invariant 7). Every invocation
        // (including --page re-invocations) is its own audited /export.
        writeAuditRow(userId, contactId, adapter);

        ExportDataCollector.ExportResult result =
                dataCollector.collect(userId, "dm", userId);

        String truncationNote = result.truncatedTables().isEmpty() ? ""
                : "\n\nSome tables exceeded the row limit and were truncated: "
                        + String.join(", ", result.truncatedTables()) + ".";

        // Reserve the note's length so header + fenced page + note —
        // the whole reply body — stays within the page cap.
        int effectiveCap = exportPageCap - HEADER_BUDGET - truncationNote.length();
        List<String> pages = ExportPaginator.paginate(result.tables(), effectiveCap);

        int requestedPage = parseRequestedPage(rawText);
        if (requestedPage > pages.size()) {
            String key = pages.size() == 1
                    ? BundleKeys.ERROR_EXPORT_PAGE_OUT_OF_RANGE_ONE
                    : BundleKeys.ERROR_EXPORT_PAGE_OUT_OF_RANGE_MANY;
            return reply(scope, MessageFormat.format(bundleLoader.get(key, language),
                    String.valueOf(requestedPage), String.valueOf(pages.size())));
        }
        String body = formatPage(pages.get(requestedPage - 1),
                requestedPage, pages.size());
        return reply(scope, body + truncationNote);
    }

    private @Nullable UUID lookupUserId(String adapter, String contactId) {
        return userRepository.resolveUserId(adapter, contactId).orElse(null);
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
     * Format one page as a reply body. Single-page export: no page
     * marker. Multi-page: a {@code page=N/T} header before the opening
     * fence (the header + fences fit {@link #HEADER_BUDGET}).
     */
    static String formatPage(String pageJson, int page, int totalPages) {
        String fenced = "```json\n" + pageJson + "\n```";
        if (totalPages == 1) {
            return fenced;
        }
        return "page=" + page + "/" + totalPages + "\n" + fenced;
    }

    /**
     * Parse {@code --page N} from the raw command text (1-indexed;
     * both {@code --page N} and {@code --page=N} forms, the corpus
     * shape). Missing, malformed, or non-positive values fall back
     * to page 1.
     */
    static int parseRequestedPage(String rawText) {
        String[] tokens = rawText.trim().split("\\s+");
        for (int i = 1; i < tokens.length; i++) {
            String tok = tokens[i];
            if (tok.equals("--page") && i + 1 < tokens.length) {
                return parsePage(tokens[i + 1]);
            }
            if (tok.startsWith("--page=")) {
                return parsePage(tok.substring("--page=".length()));
            }
        }
        return 1;
    }

    private static int parsePage(String value) {
        try {
            int n = Integer.parseInt(value);
            return n >= 1 ? n : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(
                scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
