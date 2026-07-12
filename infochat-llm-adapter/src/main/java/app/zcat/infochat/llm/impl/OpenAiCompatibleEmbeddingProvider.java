package app.zcat.infochat.llm.impl;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The first concrete {@link EmbeddingProvider} impl, per
 * {@code docs/design/05-llm-and-embeddings.md} §5.1 / §5.5. Speaks the
 * OpenAI-compatible {@code POST /embeddings} wire shape that Ollama,
 * OpenAI, OpenRouter, and similar endpoints all implement.
 *
 * <h2>Wire shape</h2>
 * <p>Request body:
 * <pre>{@code
 * {
 *   "model": "<model-id>",
 *   "input": ["text1", "text2", ...]
 * }
 * }</pre>
 * Response body's load-bearing path is {@code data[i].embedding} — a
 * dense float array per input element. The OpenAI /embeddings contract
 * tags each element with the input {@code index} it belongs to, so order
 * is recoverable via that field rather than guaranteed positional; the
 * parse places each vector at its declared slot (falling back to response
 * position when {@code index} is absent). The set of indices must cover
 * {@code [0, input.length)} exactly; a divergence is treated as a
 * wrong-shape failure by the caller (EmbeddingWorker's
 * one-failure-fails-batch retry).
 *
 * <h2>Per-deployment config</h2>
 * <p>Per {@code docs/spec/llm.md} §SPI shape "Scope of the enum":
 * the embedder has its own SPI distinct from the chat-completions
 * task enum. ONE provider per deployment: one
 * {@code (base-url, api-key, model)} tuple, no per-language or
 * per-task routing. This impl reads the three keys directly via
 * {@link ConfigProperty} — no router lookup.
 *
 * <h2>HTTP client</h2>
 * <p>{@link HttpClient} from {@code java.net.http} — same shape as
 * {@link OpenAiCompatibleProvider} (the chat-completions sibling).
 * One shared thread-safe instance with connection pooling; the
 * per-call timeout is the upper bound for both connect and read on
 * HTTP/2 multiplexed connections.
 *
 * <h2>API-key handling</h2>
 * <p>When {@code api-key} is non-empty, the value is sent as
 * {@code Authorization: Bearer <key>}. When empty (the documented
 * sentinel for local Ollama, which ignores credentials), the header
 * is omitted — Ollama rejects an Authorization header with a stricter
 * error than not sending one.
 *
 * <h2>Failure surface</h2>
 * <p>Any {@link IOException}, {@link InterruptedException}, non-2xx
 * HTTP status, malformed JSON, or missing {@code data[i].embedding}
 * path throws {@link EmbeddingCallFailedException}. The EmbeddingWorker
 * catches this uniformly and retries the SAME batch once per
 * {@code docs/spec/llm.md} §Failure handling (recap) "Retry policy: on
 * a batch failure the same batch is resubmitted as-is; the batch is
 * not split on retry."
 */
@ApplicationScoped
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    /**
     * Stable, operator-visible name for the embedding metric's
     * {@code provider} label. Distinct from the chat-completions
     * {@link OpenAiCompatibleProvider#PROVIDER_NAME} ({@code openai-compatible})
     * because they are separate SPIs with separate selection, so the
     * metric label must not collapse the two onto one identifier.
     */
    public static final String PROVIDER_NAME = "openai-compatible-embedding";

    private final HttpClient http;

    @ConfigProperty(name = "infochat.embeddings.base-url")
    String baseUrl;

    /**
     * Optional injection so an empty {@code api-key=} property maps to
     * {@link Optional#empty()} (SmallRye Config treats {@code ""} as
     * absent). Local Ollama is the canonical empty-key case.
     */
    @ConfigProperty(name = "infochat.embeddings.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "infochat.embeddings.model")
    String model;

    @ConfigProperty(name = "infochat.embeddings.timeout-ms", defaultValue = "30000")
    long timeoutMs;

    /**
     * Operator-configurable response-body cap. {@code "8388608"} is
     * 8 MiB ({@link LlmHttpSupport#DEFAULT_BODY_CAP_BYTES}); the value is
     * clamped into {@code [1 MiB, 8 MiB]} before use.
     */
    @ConfigProperty(name = "infochat.embeddings.max-response-bytes", defaultValue = "8388608")
    long maxResponseBytes;

    public OpenAiCompatibleEmbeddingProvider() {
        // Explicit connect-timeout, same rationale as the chat-completions
        // sibling: the per-call request timeout cannot bound a hanging
        // HTTP/1.1 TCP connect to an unroutable endpoint.
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Config-boundary validation of the operator-supplied embedding config,
     * run at bean creation. In the Collector this fires at startup — the
     * @Startup {@code EmbeddingMetadataStartupGuard} drives this bean — so a
     * malformed {@code base-url} or a non-positive {@code timeout-ms} fails boot
     * naming the property, rather than surfacing the per-call {@code URI.create}
     * / {@code HttpRequest.Builder.timeout} throw inside the EmbeddingWorker's
     * batch-failure catch, where it would read as a transient outage. Both
     * checks share the validators the two chat providers run at their own
     * startup scan ({@link LlmHttpSupport#requireHttpBaseUrl},
     * {@link LlmHttpSupport#requirePositiveTimeoutMs}). Package-private so the
     * unit test can invoke it.
     */
    @PostConstruct
    void validateStartupConfig() {
        LlmHttpSupport.requireHttpBaseUrl(baseUrl, "infochat.embeddings.base-url");
        LlmHttpSupport.requirePositiveTimeoutMs(timeoutMs, "infochat.embeddings.timeout-ms");
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
    public List<EmbeddingResult> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }

        String body;
        try {
            ObjectNode root = LlmHttpSupport.JSON.createObjectNode();
            root.put("model", model);
            ArrayNode input = root.putArray("input");
            for (String text : texts) {
                input.add(text);
            }
            body = LlmHttpSupport.JSON.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new EmbeddingCallFailedException(
                "OpenAiCompatibleEmbeddingProvider: failed to assemble request body", e);
        }

        URI uri = URI.create(LlmHttpSupport.joinPath(baseUrl, "/embeddings"));
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        String key = apiKey.orElse("");
        if (!key.isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + key);
        }
        HttpRequest request = reqBuilder.build();

        String responseBody = LlmHttpSupport.sendForBody(http, request,
            LlmHttpSupport.clampBodyCapBytes(maxResponseBytes),
            "OpenAiCompatibleEmbeddingProvider",
            (message, cause, unreachable) -> {
                if (unreachable) {
                    // cause is always non-null here (only the IOException
                    // path classifies unreachable); the null-split form is
                    // what the nullness analysis can verify.
                    return cause == null
                        ? new EmbeddingProviderUnreachableException(message)
                        : new EmbeddingProviderUnreachableException(message, cause);
                }
                return cause == null
                    ? new EmbeddingCallFailedException(message)
                    : new EmbeddingCallFailedException(message, cause);
            });

        return parseEmbeddings(responseBody, uri, texts.size());
    }

    private static List<EmbeddingResult> parseEmbeddings(String responseBody, URI uri, int expectedCount) {
        JsonNode root;
        try {
            root = LlmHttpSupport.JSON.readTree(responseBody);
        } catch (IOException e) {
            throw new EmbeddingCallFailedException(
                "OpenAiCompatibleEmbeddingProvider: failed to parse JSON response from "
                    + uri.getHost(), e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new EmbeddingCallFailedException(
                "OpenAiCompatibleEmbeddingProvider: response missing data[] from " + uri.getHost());
        }
        // The OpenAI /embeddings contract tags each data[] element with the
        // input `index` it belongs to: order is recoverable via that field,
        // NOT guaranteed positional. Place each parsed vector at its declared
        // input slot rather than zip-indexing by response position, so a
        // reordered reply cannot silently attribute the wrong vector to a post
        // (the silent cosine-corruption class the per-coordinate type check
        // below also guards). A reply that omits `index` — the in-order,
        // index-less shape the default local provider returns — falls back to
        // response position, so an already-in-order reply yields output
        // identical to the positional loop this replaced. (M1-369)
        EmbeddingResult[] slots = new EmbeddingResult[expectedCount];
        int placed = 0;
        for (int i = 0; i < data.size(); i++) {
            JsonNode element = data.get(i);
            JsonNode indexNode = element.path("index");
            int slot;
            if (indexNode.isMissingNode()) {
                slot = i;
            } else if (indexNode.isIntegralNumber()) {
                slot = indexNode.intValue();
            } else {
                throw new EmbeddingCallFailedException(
                    "OpenAiCompatibleEmbeddingProvider: data[" + i + "].index is not an integer from "
                        + uri.getHost());
            }
            if (slot < 0 || slot >= expectedCount) {
                throw new EmbeddingCallFailedException(
                    "OpenAiCompatibleEmbeddingProvider: data[" + i + "].index " + slot
                        + " out of range [0," + expectedCount + ") from " + uri.getHost());
            }
            // A duplicate slot (two elements claiming one input) would overwrite
            // a placed vector and leave another input uncovered — caught here so
            // the message names the collision, not a downstream gap.
            if (slots[slot] != null) {
                throw new EmbeddingCallFailedException(
                    "OpenAiCompatibleEmbeddingProvider: duplicate index " + slot + " from " + uri.getHost());
            }
            JsonNode embedding = element.path("embedding");
            if (!embedding.isArray()) {
                throw new EmbeddingCallFailedException(
                    "OpenAiCompatibleEmbeddingProvider: data[" + i + "].embedding missing or not array from "
                        + uri.getHost());
            }
            float[] vector = new float[embedding.size()];
            for (int j = 0; j < embedding.size(); j++) {
                JsonNode coordinate = embedding.get(j);
                // A non-numeric coordinate (string, boolean, object, JSON null)
                // would coerce to 0.0 under the lenient asDouble(), persisting a
                // silently corrupt vector that passes the size check below and
                // pollutes cosine scoring. Validate the JSON type tag and read
                // via doubleValue() so element-type divergence becomes a batch
                // failure at the seam, like the size divergence guarded below.
                if (!coordinate.isNumber()) {
                    throw new EmbeddingCallFailedException(
                        "OpenAiCompatibleEmbeddingProvider: data[" + i + "].embedding[" + j
                            + "] is not numeric from " + uri.getHost());
                }
                vector[j] = (float) coordinate.doubleValue();
            }
            slots[slot] = new EmbeddingResult(vector);
            placed++;
        }
        // The SPI contract is one EmbeddingResult per input, in input order. A
        // gap (some input slot never filled) or a short reply leaves placed <
        // expectedCount; a caller would otherwise under-count or mis-attribute
        // embeddings. Full, gap-free, duplicate-free [0, expectedCount)
        // coverage is exactly the guarantee the old positional size check made,
        // so this subsumes it. Throw at the seam so the divergence becomes a
        // batch failure (EmbeddingWorker's one-failure-fails-batch retry)
        // rather than a corrupt result.
        if (placed != expectedCount) {
            throw new EmbeddingCallFailedException(
                "OpenAiCompatibleEmbeddingProvider: response shape mismatch from " + uri.getHost()
                    + " — expected " + expectedCount + " embeddings, got " + placed);
        }
        List<EmbeddingResult> results = new ArrayList<>(expectedCount);
        for (EmbeddingResult result : slots) {
            results.add(result);
        }
        return results;
    }

    /**
     * Unchecked exception covering every failure mode the EmbeddingWorker
     * treats as a batch failure: network I/O, non-2xx HTTP, malformed
     * JSON, missing required response fields, and assembly errors on
     * the request side. The worker's one-failure-fails-batch retry
     * catches this type uniformly.
     *
     * <p>Not {@code final}: {@link EmbeddingProviderUnreachableException}
     * subtypes it so the transport-unreachable failure class stays
     * catchable under this one family — the embedding-side mirror of
     * {@link LlmCallFailedException.ProviderUnreachableException} (M1-606).</p>
     */
    public static class EmbeddingCallFailedException extends RuntimeException {

        public EmbeddingCallFailedException(String message) {
            super(message);
        }

        public EmbeddingCallFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The transport-unreachable subclass of the embedding call-failure
     * family: connection refused, DNS failure, no route, or a
     * connect/read timeout — decided by
     * {@link LlmHttpSupport#isTransportUnreachable} at the shared
     * {@code sendForBody} catch site. Only THIS type advances the circuit
     * breaker's consecutive-failure count for the embedding endpoint, and
     * the breaker's OPEN short-circuit throws it too; application-level
     * failures (non-2xx, body cap, parse, wrong shape) stay the plain
     * {@link EmbeddingCallFailedException} and prove reachability. (M1-606)
     */
    public static final class EmbeddingProviderUnreachableException
            extends EmbeddingCallFailedException {

        public EmbeddingProviderUnreachableException(String message) {
            super(message);
        }

        public EmbeddingProviderUnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
