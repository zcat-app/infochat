package app.zcat.infochat.llm.impl;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

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
 *   "messages": [
 *     {"role": "system", "content": "<systemPrompt>"},
 *     {"role": "user",   "content": "<userPrompt>"}
 *   ]
 * }
 * }</pre>
 * Response body's load-bearing path is
 * {@code choices[0].message.content} — the model's plain text reply.
 * Token usage, finish-reason, and per-call latency are not exposed
 * through {@link LlmResponse} in v1 (spec commits only to the text;
 * future tickets that need them widen the wrapper).
 *
 * <h2>Per-task config</h2>
 * <p>Reads {@code (base-url, api-key, model, timeout-ms)} per
 * {@link ModelTask} for all six tasks, dynamically via {@link Config}
 * — the same pattern as {@link AnthropicProvider}. The property key
 * pattern is {@code infochat.llm.<taskKeySegment>.<property>}. A task
 * whose required keys ({@code base-url}, {@code model}) are absent
 * fails the call with the config system's missing-property error,
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

    /**
     * java.net.http.HttpClient is thread-safe and the connection
     * pool is bounded internally — one shared instance per bean is
     * correct. Constructed WITH an explicit connect-timeout: the
     * per-call request timeout caps the full exchange, but on
     * HTTP/1.1 an unroutable endpoint would otherwise hang on the
     * OS connect default before the request timeout can apply.
     */
    private final HttpClient http;

    private final Config config;

    @Inject
    public OpenAiCompatibleProvider(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
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
        String prefix = "infochat.llm." + task.keySegment() + ".";
        String baseUrl = config.getValue(prefix + "base-url", String.class);
        LlmHttpSupport.requireHttpBaseUrl(baseUrl, prefix + "base-url");
        String apiKey = config.getOptionalValue(prefix + "api-key", String.class).orElse("");
        String model = config.getValue(prefix + "model", String.class);
        long timeoutMs = config.getOptionalValue(prefix + "timeout-ms", Long.class).orElse(30000L);
        return new TaskConfig(baseUrl, apiKey, model, timeoutMs);
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
                "OpenAiCompatibleProvider: failed to parse JSON response from " + uri, e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmCallFailedException(
                "OpenAiCompatibleProvider: response missing choices[] from " + uri
                    + "; preview: " + LlmHttpSupport.preview(responseBody));
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new LlmCallFailedException(
                "OpenAiCompatibleProvider: response missing choices[0].message.content from "
                    + uri + "; preview: " + LlmHttpSupport.preview(responseBody));
        }
        return new LlmResponse(content.asText());
    }

    /** Per-task config snapshot extracted by {@link #configFor}. */
    private record TaskConfig(String baseUrl, String apiKey, String model, long timeoutMs) {
    }
}
