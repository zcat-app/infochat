package app.zcat.infochat.provider.chat;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.CompressCommandHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Between-turns trigger that fires when {@code chat_session.token_count}
 * exceeds the profile-driven threshold. The trigger is deterministic
 * (token count comparison, never LLM-judged). On success, returns a
 * one-line bundle notification; on failure, returns the shared
 * compression-failure error so the caller can surface it.
 *
 * <p>Called by {@link ChatAgent} after persisting both turns (user +
 * assistant) and computing the reply, but before the reply is returned
 * to the router. This placement satisfies the spec requirement:
 * "runs between turns — after the current reply is delivered and before
 * the next message is processed."</p>
 */
@ApplicationScoped
public class AutoCompressTrigger {

    private static final Logger log = LoggerFactory.getLogger(AutoCompressTrigger.class);

    private static final String SELECT_TOKEN_COUNT_SQL =
            "SELECT token_count FROM chat_session "
                    + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?";

    private final int compressAtThreshold;
    private final BundleLoader bundleLoader;
    private final CompressCommandHandler compressHandler;
    private final DataSource dataSource;

    @Inject
    public AutoCompressTrigger(
            @ConfigProperty(name = "infochat.context-compress-at") int compressAtThreshold,
            @NonNull BundleLoader bundleLoader,
            @NonNull CompressCommandHandler compressHandler,
            @NonNull DataSource dataSource) {
        this.compressAtThreshold = compressAtThreshold;
        this.bundleLoader = bundleLoader;
        this.compressHandler = compressHandler;
        this.dataSource = dataSource;
    }

    /**
     * Check the session's token count and auto-compress if above threshold.
     *
     * @return a notification string to append to the reply if auto-compress
     *         fired (success or failure), or empty if below threshold
     */
    public Optional<String> checkAndCompress(@NonNull UUID userId,
                                             @NonNull String scopeKind,
                                             @NonNull UUID scopeId,
                                             @NonNull String scopeLanguage) {
        int tokenCount = readTokenCount(userId, scopeKind, scopeId);
        if (tokenCount < compressAtThreshold) {
            return Optional.empty();
        }

        log.info("Auto-compress triggered for userId={} scope=({},{}) tokens={} threshold={}",
                userId, scopeKind, scopeId, tokenCount, compressAtThreshold);

        CompressCommandHandler.CompressResult result =
                compressHandler.compress(userId, scopeKind, scopeId, scopeLanguage);

        return switch (result) {
            case CompressCommandHandler.CompressResult.Success ignored ->
                    Optional.of(bundleLoader.get(BundleKeys.REPLY_AUTO_COMPRESS_NOTICE));
            case CompressCommandHandler.CompressResult.NoMessages ignored ->
                    Optional.empty();
            case CompressCommandHandler.CompressResult.Failure ignored ->
                    Optional.of(bundleLoader.get(BundleKeys.ERROR_COMPRESS_FAILED));
        };
    }

    // Package-private for test overrides.
    int readTokenCount(UUID userId, String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_TOKEN_COUNT_SQL)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0;
                return rs.getInt("token_count");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "AutoCompressTrigger.readTokenCount failed for userId=" + userId, e);
        }
    }

    // Test seam: expose the configured threshold for assertions.
    int threshold() {
        return compressAtThreshold;
    }
}
