package app.zcat.infochat.provider.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import app.zcat.infochat.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Parity guards for ChatToolCatalog: the catalog DESCRIBES the same
// eight tools the registry allows — it adds, renames, and re-scopes
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
        assertArgs("getPrice", List.of("asset:string", "vs_currency:string"));
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

    @Test
    void searchToolDescriptionsCarryTemporalRoutingGuidance() {
        String searchPosts = ChatToolCatalog.tool("searchPosts").description();
        assertTrue(searchPosts.contains("recent")
                        && searchPosts.contains("latest")
                        && searchPosts.contains("today's or top news"),
                "searchPosts must name the temporal intents it serves");
        assertTrue(searchPosts.contains("'Top' means most recent, not most important"),
                "searchPosts must bind 'top' to recency, promising no importance "
                        + "ranking it does not have");
        String semanticSearch = ChatToolCatalog.tool("semanticSearch").description();
        assertTrue(semanticSearch.contains("no time window and no recency ordering"),
                "semanticSearch must disclaim its missing time dimension");
        assertTrue(semanticSearch.contains("use searchPosts instead"),
                "semanticSearch must steer temporal intents to searchPosts");
    }

    @Test
    void wireDeclarationsCarryTheSameRoutingGuidance() {
        Map<String, String> wireDescriptions =
                ChatToolCatalog.wireDeclarations().stream()
                        .collect(Collectors.toMap(
                                LlmProvider.ToolDeclaration::name,
                                LlmProvider.ToolDeclaration::description,
                                (a, b) -> a));
        for (String name : List.of("searchPosts", "semanticSearch")) {
            assertEquals(ChatToolCatalog.tool(name).description(),
                    wireDescriptions.get(name),
                    name + "'s wire description must be the single-sourced "
                            + "catalog string");
            assertTrue(wireDescriptions.get(name).contains("time dimension"),
                    name + "'s wire declaration must carry the routing "
                            + "guidance, not only the instruction table");
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
