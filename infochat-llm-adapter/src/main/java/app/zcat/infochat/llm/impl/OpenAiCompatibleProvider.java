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
 * The first concrete {@link LlmProvider} impl, per
 * {@code docs/design/05-llm-and-embeddings.md} §5.3. Speaks the
 * OpenAI-compatible {@code POST /chat/completions} wire shape that
 * Ollama, llama.cpp, OpenAI, OpenRouter, and NanoGPT all implement.
 * Distinguished at runtime by {@code (base-url, api-key, model)} —
 * one bean, four+ effective providers depending on operator config.
 *
 * <h2>Wire shape</h2>
 * <p>Request body:
 * <pre>{@code
 * {
 *   "model": "<model-id>",
 *   "max_tokens": <output-cap>,
 *   "messages": [
 *     {"role": "system", "content": "<systemPrompt>"},
 *     {"role": "user",   "content": "<userPrompt>"}
 *   ]
 * }
 * }</pre>
 * Response body's load-bearing path is
 * {@code choices[0].message.content} — the model's plain text reply.
 * The optional {@code usage.prompt_tokens} / {@code usage.completion_tokens}
 * counters and the root {@code model} field feed {@link LlmResponse}'s
 * observability companions when present (M1-321); their absence is not
 * an error — token metrics simply don't increment for that call.
 *
 * <h2>Per-task config</h2>
 * <p>Reads {@code (base-url, api-key, model, timeout-ms, max-tokens)} per
 * {@link ModelTask} for all six tasks, dynamically via {@link Config}
 * — the same pattern as {@link AnthropicProvider}. The property key
 * pattern is {@code infochat.llm.<taskKeySegment>.<property>}.
 * {@code base-url} and {@code api-key} fall back to the shared
 * deployment defaults ({@link LlmRouter#CONFIG_KEY_DEFAULT_BASE_URL} /
 * {@link LlmRouter#CONFIG_KEY_DEFAULT_API_KEY}, D56) when the per-task
 * key is unset — the api-key ONLY together with the base-url (a task
 * whose base-url is pinned per-task never inherits the shared
 * credential; see the default-api-key javadoc). A task with NEITHER
 * base-url key, or whose {@code model} is absent, fails the call —
 * surfacing a mis-configured route at the call rather than silently
 * issuing requests against the wrong endpoint.
 *
 * <h2>HTTP client</h2>
 * <p>{@link HttpClient} from {@code java.net.http} — built into JDK
 * 25, no third-party dependency. The client is constructed eagerly
 * in the bean's no-arg constructor (HttpClient is thread-safe;
 * connection pooling is automatic). A
 * {@code quarkus-rest-client-reactive} alternative was rejected to keep
 * this provider free of a third-party HTTP dependency.
 *
 * <h2>API key handling</h2>
 * <p>When {@code api-key} is non-empty, the value is sent as
 * {@code Authorization: Bearer <key>}. When the property is empty
 * (e.g. local Ollama, which ignores credentials), the header is
 * omitted — Ollama rejects an Authorization header with a stricter
 * error than not sending one. The empty-string sentinel is the
 * documented operator path: {@code infochat.llm.security.api-key=}
 * (no value).
 *
 * <h2>Failure surface</h2>
 * <p>Any {@link IOException}, {@link InterruptedException}, or
 * non-2xx HTTP status causes {@link #generate} to throw
 * {@link LlmCallFailedException} (a {@link RuntimeException}
 * subclass). The Stage 2 worker's retry-once-then-fallback harness
 * catches this and routes to the infra-failure path per
 * {@code docs/spec/security.md} §Failure handling. A 2xx response
 * with malformed JSON (Jackson parse failure or missing
 * {@code choices[0].message.content} path) also throws — Stage 2
 * treats both transport failures and unparseable-shape failures
 * identically per the spec.
 */
@ApplicationScoped
public class OpenAiCompatibleProvider implements LlmProvider {

    /**
     * Stable bean name. The router's per-task override property
     * (e.g. {@code infochat.llm.security.provider=openai-compatible})
     * resolves a provider entry by this name. Held as a public
     * constant so the router lookup and the tests reference the
     * exact same literal.
     */
    public static final String PROVIDER_NAME = "openai-compatible";

    private final HttpClient http;

    private final Config config;

    @Inject
    public OpenAiCompatibleProvider(Config config) {
        // Explicit connect-timeout: the per-call .timeout(...) caps the
        // full exchange per request, but on HTTP/1.1 an unroutable
        // endpoint would otherwise hang on the OS connect default.
        this(config, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build());
    }

    /** Test seam: caller-supplied HttpClient targets a local mock server. */
    OpenAiCompatibleProvider(Config config, HttpClient http) {
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
    public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
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

    /**
     * Dynamic per-call config read, same shape as
     * {@link AnthropicProvider#configFor}. A cached snapshot was
     * considered (per-call lookups are a known perf nit) and rejected:
     * the lookup is a map read measured in microseconds against an LLM
     * HTTP call measured in seconds, and caching would freeze values
     * the rest of the config surface treats as runtime-resolvable.
     * {@code api-key} uses optional lookup so an empty {@code api-key=}
     * property maps to absent (SmallRye Config treats {@code ""} as
     * absent) — local Ollama is the canonical empty-key case.
     */
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
            // naming BOTH settable keys, so the M1-597 failure shape (a task
            // added to the enum after the operator config was generated) is a
            // loud startup error naming the fix instead of a silent per-call
            // fallback. requireHttpBaseUrl reports against the key the value
            // actually came from, so the operator edits the right line.
            Optional<String> perTaskBaseUrl = config.getOptionalValue(baseUrlKey, String.class);
            String effectiveBaseUrlKey =
                perTaskBaseUrl.isPresent() ? baseUrlKey : LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL;
            String baseUrl = perTaskBaseUrl
                .or(() -> config.getOptionalValue(LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, String.class))
                .orElseThrow(() -> new LlmProvider.TaskConfigUnresolvableException(
                    "OpenAiCompatibleProvider: no base-url configured for " + task
                        + " — set " + baseUrlKey + " (per-task) or "
                        + LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL + " (shared default)"));
            LlmHttpSupport.requireHttpBaseUrl(baseUrl, effectiveBaseUrlKey);
            // The default credential travels ONLY to the default endpoint
            // (redteam 2026-07-11, M1-603): a task whose base-url is pinned
            // per-task must not implicitly inherit the deployment-wide key —
            // the pinned endpoint is a party that key was not minted for. An
            // operator pinning a route that needs the key restates it
            // explicitly via the per-task api-key.
            String apiKey = config.getOptionalValue(prefix + "api-key", String.class)
                .or(() -> perTaskBaseUrl.isPresent()
                    ? Optional.<String>empty()
                    : config.getOptionalValue(LlmRouter.CONFIG_KEY_DEFAULT_API_KEY, String.class))
                .orElse("");
            String model = config.getValue(prefix + "model", String.class);
            long timeoutMs = config.getOptionalValue(prefix + "timeout-ms", Long.class).orElse(30000L);
            LlmHttpSupport.requirePositiveTimeoutMs(timeoutMs, prefix + "timeout-ms");
            // Defaulted, not uncapped, when absent: an uncapped completion is
            // the F-live-6 failure mode — a local model generates until the
            // client timeout cancels it, turning a finishable reply into a
            // total loss. 1024 bounds every v1 task's legitimate output.
            int maxTokens = config.getOptionalValue(prefix + "max-tokens", Integer.class).orElse(1024);
            LlmHttpSupport.requirePositiveMaxTokens(maxTokens, prefix + "max-tokens");
            return new TaskConfig(baseUrl, apiKey, model, timeoutMs, maxTokens);
        } catch (NoSuchElementException e) {
            throw new LlmProvider.TaskConfigUnresolvableException(
                "OpenAiCompatibleProvider: missing required per-task config for " + task, e);
        }
    }

    private LlmResponse doCall(TaskConfig cfg, String systemPrompt, String userPrompt) {
        // Assemble the request body. Jackson handles the JSON escape
        // for any quote, backslash, newline, or non-ASCII codepoint
        // inside the prompt strings — a hand-rolled concat would
        // mis-handle a prompt containing a literal quote.
        String body;
        try {
            ObjectNode root = LlmHttpSupport.JSON.createObjectNode();
            root.put("model", cfg.model());
            root.put("max_tokens", cfg.maxTokens());
            ArrayNode messages = root.putArray("messages");
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userPrompt);
            body = LlmHttpSupport.JSON.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new LlmCallFailedException(
                "OpenAiCompatibleProvider: failed to assemble request body", e);
        }

        URI uri = URI.create(LlmHttpSupport.joinPath(cfg.baseUrl(), "/chat/completions"));
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(cfg.timeoutMs()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        // apiKey is coalesced to "" at the configFor site (orElse("")),
        // so emptiness is the only "no key configured" signal.
        if (!cfg.apiKey().isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + cfg.apiKey());
        }
        HttpRequest request = reqBuilder.build();

        return LlmHttpSupport.executeJsonCall(
            http, config, request, "OpenAiCompatibleProvider", OpenAiCompatibleProvider::parseChoiceText);
    }

    private static LlmResponse parseChoiceText(String responseBody, URI uri) {
        JsonNode root;
        try {
            root = LlmHttpSupport.JSON.readTree(responseBody);
        } catch (IOException e) {
            throw new LlmCallFailedException(
                "OpenAiCompatibleProvider: failed to parse JSON response from " + uri.getHost(), e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmCallFailedException(
                "OpenAiCompatibleProvider: response missing choices[] from " + uri.getHost());
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new LlmCallFailedException(
                "OpenAiCompatibleProvider: response missing choices[0].message.content from "
                    + uri.getHost());
        }
        JsonNode modelNode = root.path("model");
        String model = modelNode.isTextual() ? modelNode.asText() : null;
        JsonNode usage = root.path("usage");
        LlmResponse.TokenUsage tokenUsage = null;
        if (usage.path("prompt_tokens").canConvertToLong()
                && usage.path("completion_tokens").canConvertToLong()) {
            tokenUsage = new LlmResponse.TokenUsage(
                usage.path("prompt_tokens").asLong(),
                usage.path("completion_tokens").asLong());
        }
        return new LlmResponse(content.asText(), model, tokenUsage);
    }

    /** Per-task config snapshot extracted by {@link #configFor}. */
    private record TaskConfig(String baseUrl, String apiKey, String model, long timeoutMs,
                              int maxTokens) {
    }
}
