package app.zcat.infochat.llm.impl;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.OpenAiCompatibleProvider.LlmCallFailedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * Native Anthropic Messages API provider. Uses the Anthropic-specific
 * wire format (top-level {@code system} array, not a
 * {@code messages} entry) and marks the system prompt with
 * {@code cache_control: {"type": "ephemeral"}} so the server-side
 * prompt cache applies to the stable system prefix. This saves ~90%
 * on repeated system prompts for the summarizer and chat agent.
 *
 * <h2>Wire format</h2>
 * <pre>{@code
 * {
 *   "model": "<model-id>",
 *   "max_tokens": <integer>,
 *   "system": [
 *     {"type": "text", "text": "<systemPrompt>",
 *      "cache_control": {"type": "ephemeral"}}
 *   ],
 *   "messages": [
 *     {"role": "user", "content": "<userPrompt>"}
 *   ]
 * }
 * }</pre>
 * The top-level {@code system} array is omitted entirely when the
 * system prompt is blank — the API rejects an empty text block.
 *
 * <h2>Auth</h2>
 * <p>{@code anthropic-version} carries the stable API version;
 * {@code x-api-key} carries the raw key (not Bearer). The
 * api-key header is omitted when the config value is empty, matching
 * {@link OpenAiCompatibleProvider}'s empty-key behavior.
 *
 * <h2>Per-task config</h2>
 * <p>Config is read dynamically via {@link Config} rather than
 * per-field {@code @ConfigProperty} injection. With 6 tasks × 5
 * properties = 30 fields, dynamic lookup is cleaner. The property
 * key pattern is {@code infochat.llm.<taskKeySegment>.<property>}.
 * {@code max-tokens} is REQUIRED by the Anthropic API (unlike
 * OpenAI where it is optional).
 *
 * <h2>Failure surface</h2>
 * <p>Same contract as {@link OpenAiCompatibleProvider}, surfaced
 * through the shared {@link LlmHttpSupport#executeJsonCall} pipeline:
 * any {@link IOException}, {@link InterruptedException}, or non-2xx
 * HTTP status throws {@link LlmCallFailedException}. A non-2xx reply
 * carries a bounded body preview, which includes the inner Anthropic
 * {@code error.message} for the small JSON error bodies the API returns.
 */
@ApplicationScoped
public class AnthropicProvider implements LlmProvider {

    public static final String PROVIDER_NAME = "anthropic";

    private static final String API_VERSION = "2023-06-01";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Config config;
    private final HttpClient http;

    @Inject
    public AnthropicProvider(Config config) {
        // Explicit connect-timeout: the per-call .timeout(...) caps the
        // full exchange per request, but on HTTP/1.1 an unroutable
        // endpoint would otherwise hang on the OS connect default.
        this(config, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build());
    }

    /** Test seam: caller-supplied HttpClient targets a local mock server. */
    AnthropicProvider(Config config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    /** Test seam: exposes the shared client so tests can pin its construction. */
    HttpClient httpClient() {
        return http;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public LlmResponse generate(ModelTask task, String systemPrompt,
                                          String userPrompt) {
        TaskConfig cfg = configFor(task);
        return doCall(cfg, systemPrompt, userPrompt);
    }

    /**
     * Runs {@link #configFor} for its throw-on-missing-key effect and
     * discards the result, so the startup scan fails boot on the same
     * resolution the first {@link #generate} call would perform.
     */
    @Override
    public void assertTaskConfigResolvable(ModelTask task) {
        configFor(task);
    }

    private TaskConfig configFor(ModelTask task) {
        String prefix = "infochat.llm." + task.keySegment() + ".";
        String baseUrl = config.getValue(prefix + "base-url", String.class);
        String apiKey = config.getOptionalValue(prefix + "api-key", String.class).orElse("");
        String model = config.getValue(prefix + "model", String.class);
        long timeoutMs = config.getOptionalValue(prefix + "timeout-ms", Long.class).orElse(30000L);
        int maxTokens = config.getValue(prefix + "max-tokens", Integer.class);
        return new TaskConfig(baseUrl, apiKey, model, timeoutMs, maxTokens);
    }

    private LlmResponse doCall(TaskConfig cfg, String systemPrompt, String userPrompt) {
        String body;
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("model", cfg.model());
            root.put("max_tokens", cfg.maxTokens());

            // The Messages API rejects an empty system text block, and a
            // blank system prompt is a real call shape (translation passes
            // one) — omit the field entirely rather than sending text:"".
            if (!systemPrompt.isBlank()) {
                ArrayNode system = root.putArray("system");
                ObjectNode systemBlock = system.addObject();
                systemBlock.put("type", "text");
                systemBlock.put("text", systemPrompt);
                ObjectNode cacheControl = systemBlock.putObject("cache_control");
                cacheControl.put("type", "ephemeral");
            }

            ArrayNode messages = root.putArray("messages");
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userPrompt);

            body = JSON.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new LlmCallFailedException(
                "AnthropicProvider: failed to assemble request body", e);
        }

        URI uri = URI.create(LlmHttpSupport.joinPath(cfg.baseUrl(), "/messages"));
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(cfg.timeoutMs()))
            .header("Content-Type", "application/json")
            .header("anthropic-version", API_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!cfg.apiKey().isEmpty()) {
            reqBuilder.header("x-api-key", cfg.apiKey());
        }
        HttpRequest request = reqBuilder.build();

        return LlmHttpSupport.executeJsonCall(
            http, config, request, "AnthropicProvider", AnthropicProvider::parseContentText);
    }

    private static LlmResponse parseContentText(String responseBody, URI uri) {
        JsonNode root;
        try {
            root = JSON.readTree(responseBody);
        } catch (IOException e) {
            throw new LlmCallFailedException(
                "AnthropicProvider: failed to parse JSON response from " + uri, e);
        }
        JsonNode content = root.path("content");
        if (!content.isArray() || content.isEmpty()) {
            throw new LlmCallFailedException(
                "AnthropicProvider: response missing content[] from " + uri);
        }
        // Concatenate every text-typed block rather than reading
        // content[0].text: the Messages API may lead with non-text
        // blocks (e.g. thinking) or split the reply across several
        // text blocks — first-block-only would throw on the former
        // and silently truncate the latter.
        StringBuilder text = new StringBuilder();
        boolean sawTextBlock = false;
        for (JsonNode block : content) {
            if (!"text".equals(block.path("type").asText())) {
                continue;
            }
            JsonNode blockText = block.path("text");
            if (blockText.isTextual()) {
                text.append(blockText.asText());
                sawTextBlock = true;
            }
        }
        if (!sawTextBlock) {
            throw new LlmCallFailedException(
                "AnthropicProvider: response has no text content block from " + uri);
        }
        return new LlmResponse(text.toString());
    }

    private record TaskConfig(String baseUrl, String apiKey, String model, long timeoutMs, int maxTokens) {
    }
}
