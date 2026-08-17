package app.zcat.infochat.provider.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.llm.LlmProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Single source for the seven chat tools' descriptions: the instruction
// table ChatAgent renders into TOOL_INSTRUCTIONS and the JSON-Schema
// parameters a tools-bearing wire renders from (M1-872).
final class ChatToolCatalog {

    record ToolArg(String name, String jsonType, boolean required,
                   @Nullable String exampleJson) {
    }

    record Tool(String name, String description, List<ToolArg> args) {
    }

    // The rendered lines are pinned byte-for-byte by
    // ChatAgentTest.renderedInstructionTableIsByteIdentical — do not
    // reflow them. exampleJson is null for an arg the tool parses but
    // the prompt's example hides.
    private static final List<Tool> TOOLS = List.of(
            new Tool("searchPosts",
                    "search posts by tags within a time window",
                    List.of(new ToolArg("tags", "array", false, "[\"tag1\"]"),
                            new ToolArg("window", "string", false, "\"P7D\""),
                            new ToolArg("limit", "integer", false, "10"))),
            new Tool("semanticSearch",
                    "find posts semantically or by keyword related to a free-text query",
                    List.of(new ToolArg("query", "string", true, "\"free-text topic\""),
                            new ToolArg("limit", "integer", false, "10"))),
            new Tool("getPost",
                    "retrieve a single post by UID",
                    List.of(new ToolArg("uid", "string", true, "\"post-uid\""))),
            new Tool("getReferences",
                    "get references for a post",
                    List.of(new ToolArg("uid", "string", true, "\"post-uid\""),
                            new ToolArg("limit", "integer", false, null))),
            new Tool("recallMemory",
                    "recall conversation memories by keyword",
                    List.of(new ToolArg("keywords", "array", false,
                            "[\"keyword1\", \"keyword2\"]"))),
            new Tool("listSaves",
                    "list saved posts filtered by personal tags within a time window",
                    List.of(new ToolArg("tags", "array", false, "[\"tag1\"]"),
                            new ToolArg("window", "string", false, "\"P7D\""))),
            new Tool("helpLookup",
                    "resolve a free-text command intent to a command name plus its "
                            + "one-line description. Use this when the user asks how to do "
                            + "something the commands cover. NEVER restate command syntax "
                            + "from memory; always direct the user to /help <name> for "
                            + "usage and examples. If the tool returns no command, say you "
                            + "do not know and point at /help — do not invent commands.",
                    List.of(new ToolArg("query", "string", true,
                            "\"free-text intent in the user's language\""))));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatToolCatalog() {
    }

    static Set<String> names() {
        return TOOLS.stream().map(Tool::name).collect(Collectors.toSet());
    }

    /** The wire declarations a tools-bearing request renders. */
    static List<LlmProvider.ToolDeclaration> wireDeclarations() {
        return TOOLS.stream()
                .map(tool -> new LlmProvider.ToolDeclaration(
                        tool.name(), tool.description(), parametersSchema(tool.name())))
                .toList();
    }

    static Tool tool(String name) {
        return TOOLS.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown chat tool: " + name));
    }

    static String renderInstructionTable() {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : TOOLS) {
            sb.append("- ").append(tool.name()).append(' ')
                    .append(exampleArgsJson(tool))
                    .append(" — ").append(tool.description()).append('\n');
        }
        return sb.append('\n').toString();
    }

    static String parametersSchema(String toolName) {
        Tool tool = tool(toolName);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        for (ToolArg arg : tool.args()) {
            Map<String, Object> prop = new LinkedHashMap<>();
            if (arg.jsonType().equals("array")) {
                prop.put("type", "array");
                prop.put("items", Map.of("type", "string"));
            } else {
                prop.put("type", arg.jsonType());
            }
            properties.put(arg.name(), prop);
        }
        schema.put("properties", properties);
        List<String> required = tool.args().stream()
                .filter(ToolArg::required)
                .map(ToolArg::name)
                .toList();
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        try {
            return MAPPER.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("schema map is string-keyed", e);
        }
    }

    private static String exampleArgsJson(Tool tool) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (ToolArg arg : tool.args()) {
            if (arg.exampleJson() == null) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('"').append(arg.name()).append("\": ").append(arg.exampleJson());
        }
        return sb.append('}').toString();
    }
}
