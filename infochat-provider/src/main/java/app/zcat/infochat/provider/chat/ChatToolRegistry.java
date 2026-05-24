package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.NonNull;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Holds the closed five-tool allowlist for the chat agent. Additions or
// removals are spec amendments (security.md §Prompt-injection defenses).
@ApplicationScoped
public class ChatToolRegistry {

    private static final Set<String> TOOL_NAMES = Set.of(
            "searchPosts",
            "getPost",
            "getReferences",
            "recallMemory",
            "listSaves"
    );

    // Contract for a chat-agent tool. Receives caller identity, scope,
    // and parsed arguments; returns a JSON string. Throws
    // IllegalArgumentException for tool-specific validation failures.
    @FunctionalInterface
    public interface ChatTool {
        @NonNull String execute(@NonNull UUID userId, @NonNull String scopeKind,
                                @NonNull UUID scopeId, @NonNull Map<String, Object> args)
                throws SQLException;
    }

    public @NonNull Set<String> toolNames() {
        return TOOL_NAMES;
    }
}
