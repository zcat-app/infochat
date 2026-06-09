package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.ChatSessionRepository;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /compress} per {@code docs/spec/commands.md}
 * §Conversation control and decision D37.
 *
 * <p>Forces an immediate {@code chat_memory} checkpoint: reads
 * the session's {@code chat_message} history, calls the LLM to
 * compress it into (summary, keywords, referenced_posts), inserts
 * a {@code chat_memory} row, and deletes the summarized
 * {@code chat_message} rows. On LLM failure the session is left
 * unchanged.</p>
 *
 * <p>The compression logic is exposed as a public method
 * ({@link #compress}) so {@link app.zcat.infochat.provider.chat.AutoCompressTrigger}
 * can reuse it without duplicating the LLM call + SQL sequence.</p>
 */
@ApplicationScoped
public class CompressCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CompressCommandHandler.class);

    private static final String SELECT_GROUP_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    private static final String SELECT_MESSAGES_SQL =
            "SELECT seq, role, content FROM chat_message "
                    + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ? "
                    + "ORDER BY seq ASC";

    private static final String INSERT_MEMORY_SQL =
            "INSERT INTO chat_memory "
                    + "(user_id, scope_kind, scope_id, summary, keywords, referenced_posts) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

    // Bounded by the max seq actually summarized so turns persisted while
    // the LLM call was in flight survive un-deleted. The chat_message
    // counter trigger decrements chat_session.token_count per deleted row
    // and next_seq is never reset, so surviving and future turns keep
    // monotonically increasing seqs.
    private static final String DELETE_MESSAGES_SQL =
            "DELETE FROM chat_message "
                    + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ? "
                    + "AND seq <= ?";

    static final String COMPRESS_SYSTEM_PROMPT =
            "Compress the following conversation into a memory entry. "
          + "Output EXACTLY in this format with no other text:\n"
          + "SUMMARY: <brief summary of the conversation in 1-3 sentences>\n"
          + "KEYWORDS: <comma-separated keywords, max 12>\n"
          + "REFERENCES: <comma-separated post UIDs mentioned in the conversation, or NONE>";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    LlmRouter llmRouter;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "compress";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        String adapter = inboundContext.adapterName();
        String callerContactId = inboundContext.senderContactId();

        Optional<UUID> actorIdOpt = lookupUserId(adapter, callerContactId);
        if (actorIdOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL));
        }
        UUID actorId = actorIdOpt.get();

        String scopeKind = scopeKindOf(scope);
        UUID scopeId = resolveScopeId(scope, actorId, adapter);
        String scopeLanguage = readScopeLanguage(scopeKind, scopeId);

        CompressResult result = compress(actorId, scopeKind, scopeId, scopeLanguage);
        return switch (result) {
            case CompressResult.Success s -> reply(scope,
                    MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_COMPRESS_SUCCESS),
                            Integer.toString(s.messageCount)));
            case CompressResult.NoMessages ignored ->
                    reply(scope, bundleLoader.get(BundleKeys.REPLY_COMPRESS_NOOP));
            case CompressResult.Failure ignored ->
                    reply(scope, bundleLoader.get(BundleKeys.ERROR_COMPRESS_FAILED));
        };
    }

    /**
     * Shared compression logic used by both {@code /compress} and
     * {@link app.zcat.infochat.provider.chat.AutoCompressTrigger}.
     * Two-transaction shape: transaction 1 reads the turns and records
     * the max seq they cover; the LLM call then runs with no JDBC
     * connection held (a multi-second call must not pin a pool
     * connection — auto-compress drives this on the hot chat path);
     * transaction 2 writes the chat_memory row and deletes only
     * {@code seq <=} that max, so turns persisted while the LLM call
     * was in flight survive. On LLM failure nothing has been written
     * or deleted — the session is left unchanged.
     */
    public CompressResult compress(UUID userId, String scopeKind,
                                   UUID scopeId, String scopeLanguage) {
        // Transaction 1: read the turns to summarize. The single SELECT
        // is its own transaction; the connection returns to the pool
        // before the LLM call.
        List<MessageRow> messages;
        try (Connection conn = dataSource.getConnection()) {
            messages = readMessages(conn, userId, scopeKind, scopeId);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "CompressCommandHandler.compress failed for userId=" + userId, e);
        }
        if (messages.isEmpty()) {
            return new CompressResult.NoMessages();
        }
        int maxSummarizedSeq = messages.getLast().seq();

        // Build conversation text for the LLM
        StringBuilder conversationText = new StringBuilder();
        for (MessageRow msg : messages) {
            conversationText.append(msg.role()).append(": ")
                    .append(msg.content()).append("\n");
        }

        // Call LLM with no connection held — failure leaves the session
        // unchanged because nothing has been written or deleted yet.
        ParsedCompression parsed;
        try {
            LlmProvider provider = llmRouter.forTask(ModelTask.CHAT_AGENT, scopeLanguage);
            LlmResponse response = provider.generate(
                    ModelTask.CHAT_AGENT, COMPRESS_SYSTEM_PROMPT,
                    conversationText.toString());
            parsed = parseCompression(response.text());
        } catch (Exception e) {
            SafeLog.warn(log, "Compression LLM call failed for userId=" + userId, e);
            return new CompressResult.Failure();
        }

        // Transaction 2: summary write + bounded delete, atomically.
        // Idempotent if retried: the delete is bounded by the seq set
        // already summarized. The counter trigger maintains
        // chat_session.token_count and updated_at on each deleted row.
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                insertMemory(conn, userId, scopeKind, scopeId, parsed);
                deleteMessages(conn, userId, scopeKind, scopeId, maxSummarizedSeq);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "CompressCommandHandler.compress failed for userId=" + userId, e);
        }
        return new CompressResult.Success(messages.size());
    }

    private List<MessageRow> readMessages(Connection conn, UUID userId,
                                          String scopeKind, UUID scopeId) throws SQLException {
        List<MessageRow> messages = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_MESSAGES_SQL)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    messages.add(new MessageRow(rs.getInt("seq"),
                            rs.getString("role"), rs.getString("content")));
                }
            }
        }
        return messages;
    }

    private void insertMemory(Connection conn, UUID userId, String scopeKind,
                              UUID scopeId, ParsedCompression parsed) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_MEMORY_SQL)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setString(4, parsed.summary);
            ps.setArray(5, conn.createArrayOf("text", parsed.keywords));
            ps.setArray(6, conn.createArrayOf("text", parsed.referencedPosts));
            ps.executeUpdate();
        }
    }

    private void deleteMessages(Connection conn, UUID userId, String scopeKind,
                                UUID scopeId, int maxSummarizedSeq) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DELETE_MESSAGES_SQL)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setInt(4, maxSummarizedSeq);
            ps.executeUpdate();
        }
    }

    // Parses the structured LLM compression response.
    static ParsedCompression parseCompression(String text) {
        String summary = "";
        String[] keywords = new String[0];
        String[] references = new String[0];

        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("SUMMARY:")) {
                summary = trimmed.substring("SUMMARY:".length()).trim();
            } else if (trimmed.startsWith("KEYWORDS:")) {
                String raw = trimmed.substring("KEYWORDS:".length()).trim();
                keywords = Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new);
            } else if (trimmed.startsWith("REFERENCES:")) {
                String raw = trimmed.substring("REFERENCES:".length()).trim();
                if (!"NONE".equalsIgnoreCase(raw) && !raw.isEmpty()) {
                    references = Arrays.stream(raw.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toArray(String[]::new);
                }
            }
        }

        if (summary.isEmpty()) {
            summary = text.length() > 200 ? text.substring(0, 200) : text;
        }
        return new ParsedCompression(summary, keywords, references);
    }

    record ParsedCompression(String summary, String[] keywords, String[] referencedPosts) {}

    record MessageRow(int seq, String role, String content) {}

    private String readScopeLanguage(String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT language FROM scope_preferences "
                             + "WHERE scope_kind = ? AND scope_id = ?")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "en";
                return rs.getString("language");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "CompressCommandHandler.readScopeLanguage failed", e);
        }
    }

    private UUID resolveScopeId(ScopeRef scope, UUID actorId, String adapter) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> actorId;
            case ScopeRef.Group group -> lookupGroupId(adapter, group.adapterGroupId())
                    .orElseThrow(() -> new IllegalStateException(
                            "CompressCommandHandler: group not found for adapter=" + adapter));
        };
    }

    private static String scopeKindOf(ScopeRef scope) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> "dm";
            case ScopeRef.Group ignored -> "group";
        };
    }

    private Optional<UUID> lookupUserId(String adapter, String contactId) {
        if (adapter == null || contactId == null) return Optional.empty();
        return userRepository.resolveUserId(adapter, contactId);
    }

    private Optional<UUID> lookupGroupId(String adapter, String upstreamGroupId) {
        if (adapter == null || upstreamGroupId == null) return Optional.empty();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "CompressCommandHandler.lookupGroupId failed for adapter=" + adapter, e);
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(),
                UUID.randomUUID().toString());
    }

    /** Result of a compression attempt — success, no messages, or LLM failure. */
    public sealed interface CompressResult {
        record Success(int messageCount) implements CompressResult {}
        record NoMessages() implements CompressResult {}
        record Failure() implements CompressResult {}
    }
}
