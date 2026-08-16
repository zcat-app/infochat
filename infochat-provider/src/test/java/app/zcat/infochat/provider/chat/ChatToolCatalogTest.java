package app.zcat.infochat.provider.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Parity guards for ChatToolCatalog: the catalog DESCRIBES the same
// seven tools the registry allows — it adds, renames, and re-scopes
// nothing — and its arg shapes match the tools' actual parsing.
class ChatToolCatalogTest {

    @Test
    void catalogMatchesRegistryNamesAndInstructionLines() {
        Set<String> registryNames = new ChatToolRegistry().toolNames();
        assertEquals(registryNames, ChatToolCatalog.names(),
                "catalog and registry name sets must be equal");
        for (String name : registryNames) {
            assertTrue(ChatToolCatalog.renderInstructionTable()
                            .contains("- " + name + " "),
                    "catalog must render an instruction line for " + name);
        }
    }

    @Test
    void catalogMatchesRegistryNamesExactly() {
        Set<String> registryNames = new ChatToolRegistry().toolNames();
        for (String catalogName : ChatToolCatalog.names()) {
            assertTrue(registryNames.contains(catalogName),
                    "catalog entry without a registry twin: " + catalogName);
        }
        for (String registryName : registryNames) {
            assertTrue(ChatToolCatalog.names().contains(registryName),
                    "registry tool without a catalog entry: " + registryName);
        }
    }

    @Test
    void everyCatalogArgsShapeMatchesToolParsing() {
        assertArgs("searchPosts", List.of("tags:array", "window:string", "limit:integer"));
        assertArgs("semanticSearch", List.of("query:string", "limit:integer"));
        assertArgs("getPost", List.of("uid:string"));
        assertArgs("getReferences", List.of("uid:string", "limit:integer"));
        assertArgs("recallMemory", List.of("keywords:array"));
        assertArgs("listSaves", List.of("tags:array", "window:string"));
        assertArgs("helpLookup", List.of("query:string"));
    }

    @Test
    void parametersRenderAsValidJsonSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String name : new ChatToolRegistry().toolNames()) {
            String schema = ChatToolCatalog.parametersSchema(name);
            JsonNode node = mapper.readTree(schema);
            assertEquals("object", node.get("type").asText(),
                    name + " schema must declare object type");
            assertTrue(node.get("properties").isObject(),
                    name + " schema must carry per-param properties");
        }
    }

    private static void assertArgs(String toolName, List<String> expectedShapes) {
        List<String> actualShapes = ChatToolCatalog.tool(toolName).args().stream()
                .map(a -> a.name() + ":" + a.jsonType())
                .toList();
        assertEquals(expectedShapes, actualShapes,
                toolName + " declared args must match what the tool parses");
    }
}
