package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Holds the closed eight-tool allowlist for the chat agent. Additions or
// removals are spec amendments (security.md §Prompt-injection defenses);
// M1-654's ChatToolAllowlistSpecParityTest asserts this set equals the
// marker-delimited table in security.md byte-for-byte. M1-664 added
// helpLookup, the chat-side command-intent lookup (doc_embedding corpus).
@ApplicationScoped
public class ChatToolRegistry {

    private static final Set<String> TOOL_NAMES = Set.of(
            "searchPosts",
            "semanticSearch",
            "getPost",
            "getReferences",
            "recallMemory",
            "listSaves",
            "helpLookup",
            "getPrice"
    );

    // Contract for a chat-agent tool. Receives caller identity, scope,
    // and parsed arguments; returns a JSON string. Throws
    // IllegalArgumentException for tool-specific validation failures.
    @FunctionalInterface
    public interface ChatTool {
        String execute(UUID userId, String scopeKind,
                                UUID scopeId, Map<String, Object> args)
                throws SQLException;
    }

    public Set<String> toolNames() {
        return TOOL_NAMES;
    }
}
