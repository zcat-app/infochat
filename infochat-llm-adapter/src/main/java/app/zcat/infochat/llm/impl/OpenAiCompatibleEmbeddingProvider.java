package app.zcat.infochat.llm.impl;

import org.jspecify.annotations.NonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * dense float array per input element, in input order per the OpenAI
 * /embeddings contract. {@code data.length} must equal the request's
 * {@code input.length}; a divergence is treated as a wrong-shape
 * failure by the caller (EmbeddingWorker's one-failure-fails-batch
 * retry).
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

    private static final Logger LOG = Logger.getLogger(OpenAiCompatibleEmbeddingProvider.class);

    private static final ObjectMapper JSON = new ObjectMapper();

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

    public OpenAiCompatibleEmbeddingProvider() {
        this.http = HttpClient.newHttpClient();
    }

    @Override
    public List<EmbeddingResult> embed(@NonNull List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }

        String body;
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("model", model);
            ArrayNode input = root.putArray("input");
            for (String text : texts) {
                input.add(text);
            }
            body = JSON.writeValueAsString(root);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new EmbeddingCallFailedException(
                "OpenAiCompatibleEmbeddingProvider: failed to assemble request body", e);
        }

        URI uri = URI.create(joinPath(baseUrl, "/embeddings"));
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        String key = apiKey.orElse("");
        if (!key.isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + key);
        }
        HttpRequest request = reqBuilder.build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new EmbeddingCallFailedException(
                "OpenAiCompatibleEmbeddingProvider: HTTP call failed for " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingCallFailedException(
                "OpenAiCompatibleEmbeddingProvider: HTTP call interrupted for " + uri, e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String preview = preview(response.body());
            LOG.warnf("OpenAiCompatibleEmbeddingProvider: non-2xx %d from %s; body preview: %s",
                response.statusCode(), uri, preview);
            throw new EmbeddingCallFailedException(
                "OpenAiCompatibleEmbeddingProvider: non-2xx status " + response.statusCode()
                    + " from " + uri);
        }

        return parseEmbeddings(response.body(), uri, texts.size());
    }

    private static List<EmbeddingResult> parseEmbeddings(String responseBody, URI uri, int expectedCount) {
        JsonNode root;
        try {
            root = JSON.readTree(responseBody);
        } catch (IOException e) {
            throw new EmbeddingCallFailedException(
                "OpenAiCompatibleEmbeddingProvider: failed to parse JSON response from " + uri, e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new EmbeddingCallFailedException(
                "OpenAiCompatibleEmbeddingProvider: response missing data[] from " + uri
                    + "; preview: " + preview(responseBody));
        }
        List<EmbeddingResult> results = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            JsonNode embedding = data.get(i).path("embedding");
            if (!embedding.isArray()) {
                throw new EmbeddingCallFailedException(
                    "OpenAiCompatibleEmbeddingProvider: data[" + i + "].embedding missing or not array from "
                        + uri + "; preview: " + preview(responseBody));
            }
            float[] vector = new float[embedding.size()];
            for (int j = 0; j < embedding.size(); j++) {
                vector[j] = (float) embedding.get(j).asDouble();
            }
            results.add(new EmbeddingResult(vector));
        }
        // The SPI contract is one EmbeddingResult per input, in input
        // order. A size divergence means the provider truncated or padded
        // the batch reply; a caller zip-indexing vectors to texts would
        // silently mis-attribute embeddings. Throw at the seam so the
        // divergence becomes a batch failure (EmbeddingWorker's
        // one-failure-fails-batch retry) rather than a corrupt result.
        if (results.size() != expectedCount) {
            throw new EmbeddingCallFailedException(
                "OpenAiCompatibleEmbeddingProvider: response shape mismatch from " + uri
                    + " — expected " + expectedCount + " embeddings, got " + results.size());
        }
        return results;
    }

    /**
     * Concatenate {@code base} + {@code path} with exactly one slash
     * between them. Same helper shape as
     * {@link OpenAiCompatibleProvider#joinPath} — kept inline rather
     * than extracted to a shared util because the helper is two
     * branches and pulling it into a third class would add an
     * abstraction without enough callers to justify the file.
     */
    private static String joinPath(String base, String path) {
        if (base.endsWith("/")) {
            return base.substring(0, base.length() - 1) + path;
        }
        return base + path;
    }

    /** Truncate a body for log inclusion — never leak the full reply. */
    private static String preview(String s) {
        if (s == null) {
            return "<null>";
        }
        if (s.length() <= 200) {
            return s;
        }
        return s.substring(0, 200) + "…(" + s.length() + " bytes)";
    }

    /**
     * Unchecked exception covering every failure mode the EmbeddingWorker
     * treats as a batch failure: network I/O, non-2xx HTTP, malformed
     * JSON, missing required response fields, and assembly errors on
     * the request side. The worker's one-failure-fails-batch retry
     * catches this type uniformly.
     */
    public static final class EmbeddingCallFailedException extends RuntimeException {
        public EmbeddingCallFailedException(String message) {
            super(message);
        }

        public EmbeddingCallFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
