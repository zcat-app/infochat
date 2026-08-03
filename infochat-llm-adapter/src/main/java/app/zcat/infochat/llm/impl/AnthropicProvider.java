package app.zcat.infochat.llm.impl;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Optional;

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
 * {@code base-url} and {@code api-key} fall back to the shared
 * deployment defaults ({@link LlmRouter#CONFIG_KEY_DEFAULT_BASE_URL} /
 * {@link LlmRouter#CONFIG_KEY_DEFAULT_API_KEY}, D56) when the per-task
 * key is unset — the api-key ONLY together with the base-url (a task
 * whose base-url is pinned per-task never inherits the shared
 * credential; see the default-api-key javadoc). {@code max-tokens} is
 * REQUIRED by the Anthropic API (unlike OpenAI where it is optional).
 *
 * <h2>Failure surface</h2>
 * <p>Same contract as {@link OpenAiCompatibleProvider}, surfaced
 * through the shared {@link LlmHttpSupport#executeJsonCall} pipeline:
 * any {@link IOException}, {@link InterruptedException}, or non-2xx
 * HTTP status throws {@link LlmCallFailedException}. A non-2xx reply's
 * body is intentionally NOT placed in the exception (U-13): the message
 * carries only the provider label, status code, and host, because
 * provider error bodies can echo request fragments or user content and
 * must never reach a log line or exception message.
 */
@ApplicationScoped
public class AnthropicProvider implements LlmProvider {

    public static final String PROVIDER_NAME = "anthropic";

    private static final String API_VERSION = "2023-06-01";

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
        return doCall(task, cfg, systemPrompt, userPrompt);
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
        String prefix = task.configPrefix();
        // Wrap the config system's own missing-required-property failure
        // (SmallRye-Config throws NoSuchElementException from getValue) in
        // the SPI-owned type so the misconfiguration surfaces as an
        // LlmProvider contract and never leaks the third-party type to
        // callers or the startup-scan tests. (M1-357)
        try {
            String baseUrlKey = prefix + "base-url";
            // Effective endpoint: the per-task override wins, else the shared
            // deployment default (D56). Neither set → refuse with a message
            // naming BOTH settable keys (mirrors OpenAiCompatibleProvider —
            // the M1-597 failure shape must be a loud startup error naming
            // the fix, not a silent per-call fallback). requireHttpBaseUrl
            // reports against the key the value actually came from.
            Optional<String> perTaskBaseUrl = config.getOptionalValue(baseUrlKey, String.class);
            String effectiveBaseUrlKey =
                perTaskBaseUrl.isPresent() ? baseUrlKey : LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL;
            String baseUrl = perTaskBaseUrl
                .or(() -> config.getOptionalValue(LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, String.class))
                .orElseThrow(() -> new LlmProvider.TaskConfigUnresolvableException(
                    "AnthropicProvider: no base-url configured for " + task
                        + " — set " + baseUrlKey + " (per-task) or "
                        + LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL + " (shared default)"));
            LlmHttpSupport.requireHttpBaseUrl(baseUrl, effectiveBaseUrlKey);
            // The default credential travels ONLY to the default endpoint
            // (redteam 2026-07-11, M1-603): a task whose base-url is pinned
            // per-task must not implicitly inherit the deployment-wide key —
            // mirrors OpenAiCompatibleProvider. A pinned route that needs the
            // key restates it explicitly via the per-task api-key.
            String apiKey = config.getOptionalValue(prefix + "api-key", String.class)
                .or(() -> perTaskBaseUrl.isPresent()
                    ? Optional.<String>empty()
                    : config.getOptionalValue(LlmRouter.CONFIG_KEY_DEFAULT_API_KEY, String.class))
                .orElse("");
            String model = config.getValue(prefix + "model", String.class);
            long timeoutMs = config.getOptionalValue(prefix + "timeout-ms", Long.class).orElse(30000L);
            LlmHttpSupport.requirePositiveTimeoutMs(timeoutMs, prefix + "timeout-ms");
            int maxTokens = config.getValue(prefix + "max-tokens", Integer.class);
            LlmHttpSupport.requirePositiveMaxTokens(maxTokens, prefix + "max-tokens");
            return new TaskConfig(baseUrl, apiKey, model, timeoutMs, maxTokens);
        } catch (NoSuchElementException e) {
            throw new LlmProvider.TaskConfigUnresolvableException(
                "AnthropicProvider: missing required per-task config for " + task, e);
        }
    }

    private LlmResponse doCall(ModelTask task, TaskConfig cfg, String systemPrompt, String userPrompt) {
        String body;
        try {
            ObjectNode root = LlmHttpSupport.JSON.createObjectNode();
            root.put("model", cfg.model());
            root.put("max_tokens", cfg.maxTokens());
            // D58 (a) GREEDY — mirrors OpenAiCompatibleProvider: the
            // translation leg is decoded at temperature 0, hard-coded for
            // ModelTask.TRANSLATOR (never a config knob; asserted on the
            // wire request in AnthropicProviderTest). Anthropic's Messages
            // API accepts "temperature" as a top-level request field.
            if (task == ModelTask.TRANSLATOR) {
                root.put("temperature", 0);
            }

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

            body = LlmHttpSupport.JSON.writeValueAsString(root);
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
            root = LlmHttpSupport.JSON.readTree(responseBody);
        } catch (IOException e) {
            throw new LlmCallFailedException(
                "AnthropicProvider: failed to parse JSON response from " + uri.getHost(), e);
        }
        JsonNode content = root.path("content");
        if (!content.isArray() || content.isEmpty()) {
            throw new LlmCallFailedException(
                "AnthropicProvider: response missing content[] from " + uri.getHost());
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
                "AnthropicProvider: response has no text content block from " + uri.getHost());
        }
        JsonNode modelNode = root.path("model");
        String model = modelNode.isTextual() ? modelNode.asText() : null;
        JsonNode usage = root.path("usage");
        LlmResponse.TokenUsage tokenUsage = null;
        if (usage.path("input_tokens").canConvertToLong()
                && usage.path("output_tokens").canConvertToLong()) {
            // The provider enables cache_control: ephemeral, so a cached prompt
            // prefix is counted under cache_read_input_tokens (or, on the write,
            // cache_creation_input_tokens) instead of input_tokens. Folding both
            // into the reported input keeps llm.tokens.in from undercounting on a
            // cache hit; asLong(0L) leaves a no-cache response byte-identical.
            tokenUsage = new LlmResponse.TokenUsage(
                usage.path("input_tokens").asLong()
                    + usage.path("cache_read_input_tokens").asLong(0L)
                    + usage.path("cache_creation_input_tokens").asLong(0L),
                usage.path("output_tokens").asLong());
        }
        return new LlmResponse(text.toString(), model, tokenUsage);
    }

    private record TaskConfig(String baseUrl, String apiKey, String model, long timeoutMs, int maxTokens) {
    }
}
