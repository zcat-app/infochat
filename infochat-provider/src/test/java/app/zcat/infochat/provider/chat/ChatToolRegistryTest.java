package app.zcat.infochat.provider.chat;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatToolRegistryTest {

    private final ChatToolRegistry registry = new ChatToolRegistry();

    // CI assertion: the registry's name set must equal the spec's
    // eight-tool list byte-for-byte (security.md §Prompt-injection defenses).
    // A name added or removed here without a matching spec amendment
    // breaks the build. M1-664 added helpLookup, the chat-side
    // command-intent lookup against the doc_embedding corpus.
    @Test
    void registryContainsExactlySpecTools() {
        Set<String> expected = Set.of(
                "searchPosts",
                "semanticSearch",
                "getPost",
                "getReferences",
                "recallMemory",
                "listSaves",
                "helpLookup",
                "getPrice"
        );
        assertEquals(expected, registry.toolNames());
    }
}
