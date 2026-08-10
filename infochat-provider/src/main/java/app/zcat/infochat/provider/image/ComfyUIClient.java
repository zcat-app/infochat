package app.zcat.infochat.provider.image;

import app.zcat.infochat.core.log.SafeLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;

/** Backend leg of the /image flow: server-built graph, bounded fetch,
 * timeout-cancels, post-job history clear (D75/D77; commands.md §Content,
 * docs/design/future/image-generation.md §The backend client). */
@ApplicationScoped
public class ComfyUIClient {

    private static final Logger LOG = LoggerFactory.getLogger(ComfyUIClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The template marks the prompt slot with this value; the constructor
     * requires exactly one occurrence (P15). */
    public static final String PROMPT_PLACEHOLDER = "INFOCHAT_PROMPT_PLACEHOLDER";

    /** Consecutive transport failures that open the breaker, and the
     * cooldown an OPEN breaker denies calls — the LLM breaker's defaults
     * (design doc §The backend client, "Breaker"). */
    static final int BREAKER_FAILURE_THRESHOLD = 3;
    static final Duration BREAKER_COOLDOWN = Duration.ofSeconds(30);

    /** One sampling-dim edge cap: a hostile --resolution ratio must not
     * blow the latent past the budget class (the /16 floor on the small
     * edge would otherwise inflate the pixel count without bound). */
    static final long MAX_SAMPLING_EDGE = 4096;

    private final Optional<String> baseUrl;
    private final Duration callTimeout;
    private final Duration jobTimeout;
    private final Duration pollInterval;
    private final long maxResponseBytes;
    private final Clock clock;
    private final HttpClient http;
    private final @Nullable LoadedTemplate template;

    /** The validated workflow template: the graph plus the node keys the
     * builder parameterizes (prompt slot, sampler seed, per-job latent dims,
     * per-job fit target). */
    private record LoadedTemplate(ObjectNode graph, String promptNodeKey, String samplerNodeKey,
                                  String latentNodeKey, String fitNodeKey) {}

    /** Seam constructor: hand-supplied config + {@code Clock} for
     * plain-JUnit tests (fixed clock, stub server). */
    public ComfyUIClient(Optional<String> baseUrl, @Nullable Path workflowFile,
                         Duration connectTimeout, Duration callTimeout, Duration jobTimeout,
                         Duration pollInterval, long maxResponseBytes, Clock clock) {
        this.baseUrl = baseUrl.map(ComfyUIClient::validateBaseUrl);
        this.callTimeout = requirePositive(callTimeout, "infochat.image.call-timeout");
        this.jobTimeout = requirePositive(jobTimeout, "infochat.image.job-timeout");
        this.pollInterval = requirePositive(pollInterval, "infochat.image.poll-interval");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("infochat.image.max-response-bytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                // A compromised or misconfigured backend must not redirect
                // the egress to arbitrary hosts; the one-box form is
                // loopback.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.template = this.baseUrl.isPresent() ? loadTemplate(workflowFile) : null;
    }

    @Inject
    public ComfyUIClient(
            @ConfigProperty(name = "infochat.image.base-url") Optional<String> baseUrl,
            @ConfigProperty(name = "infochat.image.workflow-file") Optional<String> workflowFile,
            @ConfigProperty(name = "infochat.image.connect-timeout", defaultValue = "PT5S")
            Duration connectTimeout,
            @ConfigProperty(name = "infochat.image.call-timeout", defaultValue = "PT30S")
            Duration callTimeout,
            @ConfigProperty(name = "infochat.image.job-timeout", defaultValue = "PT3M")
            Duration jobTimeout,
            @ConfigProperty(name = "infochat.image.poll-interval", defaultValue = "PT0.5S")
            Duration pollInterval,
            @ConfigProperty(name = "infochat.image.max-response-bytes", defaultValue = "16777216")
            long maxResponseBytes,
            Clock clock) {
        this(baseUrl, workflowFile.map(Path::of).orElse(null), connectTimeout, callTimeout, jobTimeout,
                pollInterval, maxResponseBytes, clock);
    }

    /** The workflow graph for {@code prompt}: the validated template with
     * the placeholder replaced through the JSON serializer and a fresh
     * random seed — user text lands in exactly one string field (D77). */
    public String buildGraph(String prompt) {
        return serialize(builtGraphBase(templateOrThrow(), prompt));
    }

    /** The graph for {@code prompt} at an exact per-job output size: latent
     * dims = requested ratio at the baked budget (/16), fit node swapped to
     * an exact-W/H ImageScale (2026-08-10 DECIDE-BEFORE resolution). */
    public String buildGraph(String prompt, long targetWidth, long targetHeight) {
        LoadedTemplate loaded = templateOrThrow();
        ObjectNode graph = builtGraphBase(loaded, prompt);
        JsonNode latentInputs = graph.get(loaded.latentNodeKey()).path("inputs");
        long[] sampling = samplingDimsFor(
                latentInputs.path("width").asLong(), latentInputs.path("height").asLong(),
                targetWidth, targetHeight);
        ObjectNode latent = (ObjectNode) graph.get(loaded.latentNodeKey()).get("inputs");
        latent.put("width", sampling[0]);
        latent.put("height", sampling[1]);
        ObjectNode fitNode = (ObjectNode) graph.get(loaded.fitNodeKey());
        fitNode.put("class_type", "ImageScale");
        ObjectNode fitInputs = (ObjectNode) fitNode.get("inputs");
        fitInputs.remove("megapixels");
        fitInputs.remove("resolution_steps");
        fitInputs.put("width", targetWidth);
        fitInputs.put("height", targetHeight);
        return serialize(graph);
    }

    private static ObjectNode builtGraphBase(LoadedTemplate loaded, String prompt) {
        ObjectNode graph = loaded.graph().deepCopy();
        ObjectNode promptInputs = (ObjectNode) graph.get(loaded.promptNodeKey()).get("inputs");
        promptInputs.set("text", TextNode.valueOf(prompt));
        ObjectNode samplerInputs = (ObjectNode) graph.get(loaded.samplerNodeKey()).get("inputs");
        samplerInputs.put("seed", ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE));
        return graph;
    }

    private static String serialize(ObjectNode graph) {
        try {
            return JSON.writeValueAsString(graph);
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize the workflow graph", e);
        }
    }

    /** Sampling dims for one target: the template's baked budget at the
     * target's ratio, each dim rounded /16 — the unified model-agnostic
     * converter rule (the flag never constrains the sampler). */
    static long[] samplingDimsFor(long budgetWidth, long budgetHeight,
                                  long targetWidth, long targetHeight) {
        double budgetPixels = (double) budgetWidth * budgetHeight;
        double ratio = (double) targetWidth / (double) targetHeight;
        long width = roundToSixteen(Math.sqrt(budgetPixels * ratio));
        long height = roundToSixteen(Math.sqrt(budgetPixels / ratio));
        return new long[]{Math.min(width, MAX_SAMPLING_EDGE), Math.min(height, MAX_SAMPLING_EDGE)};
    }

    private static long roundToSixteen(double value) {
        return Math.max(16, (Math.round(value) + 8) / 16 * 16);
    }

    /** The backend's queue depth (running + pending) — the queue-depth gate
     * PRIMITIVE; the gate decision itself is the command handler's. */
    public int queueDepth() throws IOException, InterruptedException {
        JsonNode queue = sendJson("GET", "/queue", Optional.empty());
        return queue.path("queue_running").size() + queue.path("queue_pending").size();
    }

    /** One full job: submit, poll, fetch under the cap, clear the history
     * entry (D75). Timeout and interrupt CANCEL the backend job; a failed
     * clear fails the generation (design doc §The backend client). */
    public byte[] generate(String prompt) throws IOException, InterruptedException {
        return runJob(buildGraph(prompt));
    }

    /** One full job at an exact per-job output size — the converter wiring
     * of {@link #buildGraph(String, long, long)}. */
    public byte[] generate(String prompt, long targetWidth, long targetHeight)
            throws IOException, InterruptedException {
        return runJob(buildGraph(prompt, targetWidth, targetHeight));
    }

    private byte[] runJob(String graphJson) throws IOException, InterruptedException {
        String promptId = submit(graphJson);
        try {
            byte[] bytes = awaitAndFetch(promptId, clock.instant().plus(jobTimeout));
            clearHistory(promptId);
            return bytes;
        } catch (IOException | InterruptedException | RuntimeException e) {
            clearBestEffort(promptId);
            throw e;
        }
    }

    /** Cancel one job: interrupt it if running AND delete it from the queue
     * in case it never started (both verified against the pinned backend
     * commit; recorded in the design doc). */
    public void cancel(String promptId) throws IOException, InterruptedException {
        ObjectNode interrupt = JSON.createObjectNode();
        interrupt.put("prompt_id", promptId);
        sendJson("POST", "/interrupt", Optional.of(interrupt));
        sendJson("POST", "/queue", Optional.of(deleteBody(promptId)));
    }

    /** Delete the job's submitted-graph entry from the backend history
     * (D75 no-retention, Provider half). */
    public void clearHistory(String promptId) throws IOException, InterruptedException {
        sendJson("POST", "/history", Optional.of(deleteBody(promptId)));
    }

    /** One output image's bytes, read under the cap (P7). */
    public byte[] fetchImage(String filename, String subfolder) throws IOException, InterruptedException {
        String query = "/view?filename=" + URLEncoder.encode(filename, StandardCharsets.UTF_8)
                + "&type=output";
        if (!subfolder.isEmpty()) {
            query += "&subfolder=" + URLEncoder.encode(subfolder, StandardCharsets.UTF_8);
        }
        return sendBytes(request(query, "GET", Optional.empty()));
    }

    // --- job lifecycle ------------------------------------------------------

    private String submit(String graphJson) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        try {
            body.set("prompt", JSON.readTree(graphJson));
        } catch (IOException e) {
            throw new IllegalStateException("built graph does not serialize to valid JSON", e);
        }
        JsonNode response;
        try {
            response = sendJson("POST", "/prompt", Optional.of(body));
        } catch (ResponseException e) {
            // Carry the backend's error TYPE only — its message/details can
            // quote the graph, and the prompt never reaches a thrown message
            // (P4). Typed as graph-rejected so the caller can apply the D76
            // refund boundary: the backend refused BEFORE any job ran.
            String detail = e.errorType().isEmpty() ? "" : ": " + e.errorType();
            throw new GraphRejectedException("backend rejected the submitted graph" + detail);
        }
        String promptId = response.path("prompt_id").asText("");
        if (promptId.isEmpty()) {
            throw new ResponseException("backend accepted the graph without a prompt id");
        }
        return promptId;
    }

    private byte[] awaitAndFetch(String promptId, Instant deadline)
            throws IOException, InterruptedException {
        try {
            while (true) {
                JsonNode entry = sendJson("GET", "/history/" + promptId, Optional.empty())
                        .path(promptId);
                if (!entry.isMissingNode()) {
                    if (!"success".equals(entry.path("status").path("status_str").asText(""))) {
                        throw new ResponseException("backend job finished with an error status");
                    }
                    JsonNode image = firstOutputImage(entry.path("outputs"));
                    if (image == null) {
                        throw new ResponseException("backend job completed without an output image");
                    }
                    return fetchImage(image.path("filename").asText(),
                            image.path("subfolder").asText());
                }
                if (!clock.instant().isBefore(deadline)) {
                    boolean started = isJobRunning(promptId);
                    LOG.warn("image job {} timed out after {}; cancelling the backend job",
                            promptId, jobTimeout);
                    cancelBestEffort(promptId);
                    throw new JobTimeoutException("image generation exceeded " + jobTimeout, started);
                }
                Thread.sleep(pollInterval.toMillis());
            }
        } catch (InterruptedException e) {
            // /stop's interrupt must reach the same cancellation path as the
            // timeout (P9). The started peek feeds the D76 refund boundary:
            // a job cancelled before it ever ran refunds the attempt.
            boolean started = isJobRunning(promptId);
            cancelBestEffort(promptId);
            throw new JobCancelledException("image generation cancelled by /stop", started);
        }
    }

    /** Whether the backend reports the job RUNNING — the D76 refund-boundary
     * discriminator at timeout/cancel; an unreadable answer is conservatively
     * "started", since D76 refunds only what is KNOWN never to have run. */
    private boolean isJobRunning(String promptId) {
        try {
            JsonNode queue = sendJson("GET", "/queue", Optional.empty());
            for (JsonNode job : queue.path("queue_running")) {
                if (promptId.equals(promptIdOf(job))) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /** The job's prompt id in either verified {@code /queue} entry shape:
     * the positional {@code [number, prompt_id, ...]} array of the pinned
     * backend, or an object carrying {@code prompt_id}. */
    private static String promptIdOf(JsonNode job) {
        if (job.isArray() && job.size() > 1) {
            return job.path(1).asText("");
        }
        return job.path("prompt_id").asText("");
    }

    private static @Nullable JsonNode firstOutputImage(JsonNode outputs) {
        for (JsonNode nodeOutput : outputs) {
            for (JsonNode image : nodeOutput.path("images")) {
                if (!image.path("filename").asText("").isEmpty()) {
                    return image;
                }
            }
        }
        return null;
    }

    private void cancelBestEffort(String promptId) {
        // Park an already-armed interrupt across the cancel calls and
        // restore it after (the M1-763 park pattern): an armed flag makes
        // the HTTP calls fail before they leave the process.
        boolean interrupted = Thread.interrupted();
        try {
            cancel(promptId);
        } catch (IOException e) {
            SafeLog.warn(LOG, "image job " + promptId + " ended and the cancel call failed", e);
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void clearBestEffort(String promptId) {
        // Same interrupt park as cancelBestEffort: the D75 history clear is
        // owed even on a cancelled job, and an armed flag would drop it.
        boolean interrupted = Thread.interrupted();
        try {
            clearHistory(promptId);
        } catch (IOException e) {
            SafeLog.warn(LOG, "image job " + promptId + " failed and the history clear failed too", e);
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ObjectNode deleteBody(String promptId) {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode delete = JSON.createArrayNode();
        delete.add(promptId);
        body.set("delete", delete);
        return body;
    }

    // --- transport ----------------------------------------------------------

    private JsonNode sendJson(String method, String path, Optional<ObjectNode> body)
            throws IOException, InterruptedException {
        byte[] bytes = sendBytes(request(path, method, body));
        try {
            return JSON.readTree(bytes);
        } catch (IOException e) {
            throw new ResponseException("backend returned unparseable JSON");
        }
    }

    private HttpRequest request(String path, String method, Optional<ObjectNode> body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrlOrThrow() + path))
                .timeout(callTimeout);
        if (body.isPresent()) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.get().toString()));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    /** One bounded HTTP exchange under the breaker. Any response — success
     * or application error — is reachability evidence; only transport
     * failures advance the breaker (LlmCircuitBreakerRegistry semantics). */
    private byte[] sendBytes(HttpRequest request) throws IOException, InterruptedException {
        if (!breakerTryAcquire()) {
            throw new BreakerOpenException("image backend breaker is open; not attempting the call");
        }
        try {
            HttpResponse<byte[]> response = http.send(request, boundedBytes());
            breakerRecordReachable();
            if (response.statusCode() / 100 != 2) {
                throw new ResponseException(response.statusCode(),
                        "backend returned status " + response.statusCode(),
                        errorTypeOf(response.body()));
            }
            return response.body();
        } catch (ResponseException e) {
            throw e;
        } catch (IOException e) {
            // The JDK surfaces the subscriber's cap refusal either directly
            // or wrapped; the endpoint WAS streaming, so it is reachability
            // evidence, never a breaker input.
            if (e instanceof BodyOverCapException || e.getCause() instanceof BodyOverCapException) {
                breakerRecordReachable();
                throw e instanceof BodyOverCapException overCap
                        ? overCap
                        : (BodyOverCapException) e.getCause();
            }
            breakerRecordUnreachable();
            throw new UnreachableException("image backend unreachable", e);
        }
    }

    /** The backend's {@code error.type} from a non-2xx body, or empty — the
     * only part of an error body that is backend boilerplate rather than a
     * possible echo of the submitted graph (P4). */
    private static String errorTypeOf(byte[] body) {
        try {
            return JSON.readTree(body).path("error").path("type").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    /** Reads the body under the byte cap, cutting the connection at the cap
     * and discarding the partial body — endpoint-chosen bytes are never
     * retained past the bound (P7, security.md §Trust boundaries item 9). */
    private HttpResponse.BodyHandler<byte[]> boundedBytes() {
        return responseInfo -> new BoundedBytesSubscriber(maxResponseBytes);
    }

    private static final class BoundedBytesSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long maxBytes;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private long byteCount = 0;
        // Assigned in onSubscribe() before any other signal can arrive
        // (reactive-streams contract); NullAway models only constructors
        // (LlmHttpSupport precedent).
        @SuppressWarnings("NullAway.Init")
        private Flow.Subscription subscription;

        BoundedBytesSubscriber(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                byteCount += buffer.remaining();
            }
            if (byteCount > maxBytes) {
                subscription.cancel();
                body.completeExceptionally(new BodyOverCapException(
                        "backend response exceeded the " + maxBytes + "-byte cap"));
                return;
            }
            for (ByteBuffer buffer : buffers) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                received.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(received.toByteArray());
        }
    }

    // --- breaker (single endpoint, in-memory; restart resets) ---------------

    private enum BreakerState { CLOSED, OPEN, HALF_OPEN }

    private BreakerState breakerState = BreakerState.CLOSED;
    private int consecutiveFailures = 0;
    private Instant breakerDeadline = Instant.EPOCH;

    private synchronized boolean breakerTryAcquire() {
        return switch (breakerState) {
            case CLOSED -> true;
            case OPEN, HALF_OPEN -> {
                if (clock.instant().isBefore(breakerDeadline)) {
                    yield false;
                }
                breakerState = BreakerState.HALF_OPEN;
                breakerDeadline = clock.instant().plus(BREAKER_COOLDOWN);
                yield true;
            }
        };
    }

    private synchronized void breakerRecordReachable() {
        if (breakerState != BreakerState.CLOSED) {
            LOG.info("image backend breaker CLOSED (endpoint reachable again)");
        }
        breakerState = BreakerState.CLOSED;
        consecutiveFailures = 0;
    }

    private synchronized void breakerRecordUnreachable() {
        switch (breakerState) {
            case CLOSED -> {
                consecutiveFailures++;
                if (consecutiveFailures >= BREAKER_FAILURE_THRESHOLD) {
                    breakerState = BreakerState.OPEN;
                    breakerDeadline = clock.instant().plus(BREAKER_COOLDOWN);
                    LOG.warn("image backend breaker OPEN after {} consecutive transport failures",
                            consecutiveFailures);
                }
            }
            case HALF_OPEN -> {
                breakerState = BreakerState.OPEN;
                breakerDeadline = clock.instant().plus(BREAKER_COOLDOWN);
                LOG.warn("image backend breaker re-OPENED (probe failed)");
            }
            case OPEN -> {
                // a straggler failing after the trip must not extend the
                // outage window
            }
        }
    }

    // --- template loading and validation ------------------------------------

    private LoadedTemplate loadTemplate(@Nullable Path file) {
        if (file == null) {
            throw new IllegalArgumentException(
                    "infochat.image.workflow-file is required when infochat.image.base-url is set");
        }
        JsonNode parsed;
        try {
            parsed = JSON.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "infochat.image.workflow-file is not readable JSON: " + file, e);
        }
        if (!(parsed instanceof ObjectNode graph) || graph.isEmpty()) {
            throw new IllegalArgumentException(
                    "infochat.image.workflow-file must be a non-empty API-format graph object");
        }
        String samplerKey = validateSampler(graph);
        return new LoadedTemplate(graph, validatePromptSlot(graph), samplerKey,
                validateLatentSlot(graph, samplerKey), validateFitSlot(graph));
    }

    /** The key of the node whose {@code inputs.text} carries the
     * placeholder — exactly one, or the template is rejected (P15). */
    private static String validatePromptSlot(ObjectNode template) {
        String holder = null;
        var fields = template.fields();
        while (fields.hasNext()) {
            var node = fields.next();
            JsonNode text = node.getValue().path("inputs").path("text");
            if (text.isTextual() && PROMPT_PLACEHOLDER.equals(text.asText())) {
                if (holder != null) {
                    throw new IllegalArgumentException("workflow template must contain exactly one "
                            + PROMPT_PLACEHOLDER + " text field, found more than one");
                }
                holder = node.getKey();
            }
        }
        if (holder == null) {
            throw new IllegalArgumentException("workflow template must contain exactly one "
                    + PROMPT_PLACEHOLDER + " text field, found none");
        }
        return holder;
    }

    /** The key of the single KSampler node carrying a numeric seed. */
    private static String validateSampler(ObjectNode template) {
        String sampler = null;
        var fields = template.fields();
        while (fields.hasNext()) {
            var node = fields.next();
            if ("KSampler".equals(node.getValue().path("class_type").asText(""))
                    && node.getValue().path("inputs").path("seed").isNumber()) {
                if (sampler != null) {
                    throw new IllegalArgumentException(
                            "workflow template must contain exactly one KSampler node, found more than one");
                }
                sampler = node.getKey();
            }
        }
        if (sampler == null) {
            throw new IllegalArgumentException(
                    "workflow template must contain exactly one KSampler node with a numeric seed, found none");
        }
        return sampler;
    }

    /** The key of the latent node the KSampler's {@code latent_image} link
     * targets — its baked numeric width/height ARE the sampling budget the
     * converter derives (the template is the single source of truth). */
    private static String validateLatentSlot(ObjectNode template, String samplerKey) {
        JsonNode link = template.get(samplerKey).path("inputs").path("latent_image");
        if (!link.isArray() || link.isEmpty() || !link.get(0).isTextual()) {
            throw new IllegalArgumentException(
                    "workflow template's KSampler must link a latent_image node");
        }
        String key = link.get(0).asText();
        JsonNode inputs = template.path(key).path("inputs");
        if (!inputs.path("width").isIntegralNumber() || !inputs.path("height").isIntegralNumber()) {
            throw new IllegalArgumentException("workflow template's latent node must carry "
                    + "numeric width/height — the converter's baked sampling budget");
        }
        return key;
    }

    /** The key of the single ImageScaleToTotalPixels node — the converter's
     * per-job seam for exact --resolution targets (wizard step 4b always
     * bakes exactly one). */
    private static String validateFitSlot(ObjectNode template) {
        String fit = null;
        var fields = template.fields();
        while (fields.hasNext()) {
            var node = fields.next();
            if ("ImageScaleToTotalPixels".equals(node.getValue().path("class_type").asText(""))) {
                if (fit != null) {
                    throw new IllegalArgumentException("workflow template must contain exactly one "
                            + "ImageScaleToTotalPixels fit node, found more than one");
                }
                fit = node.getKey();
            }
        }
        if (fit == null) {
            throw new IllegalArgumentException("workflow template must contain exactly one "
                    + "ImageScaleToTotalPixels fit node, found none");
        }
        return fit;
    }

    private LoadedTemplate templateOrThrow() {
        LoadedTemplate loaded = template;
        if (loaded == null) {
            throw new NotConfiguredException("infochat.image.base-url is not set");
        }
        return loaded;
    }

    private String baseUrlOrThrow() {
        return baseUrl.orElseThrow(
                () -> new NotConfiguredException("infochat.image.base-url is not set"));
    }

    private static String validateBaseUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "infochat.image.base-url must be an http(s) URL: " + url);
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static Duration requirePositive(Duration value, String key) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    // --- exception family -----------------------------------------------------

    /** The feature is config-gated off: no base-url, no backend calls. */
    public static final class NotConfiguredException extends IllegalStateException {
        NotConfiguredException(String message) {
            super(message);
        }
    }

    /** Transport-class failure (connect, timeout, reset) — feeds the breaker.
     * Non-final so {@link BreakerOpenException} can be discriminated while
     * still satisfying {@code assertThrows(UnreachableException.class)}. */
    public static class UnreachableException extends IOException {
        public UnreachableException(String message) {
            super(message);
        }

        UnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The breaker denied the call after consecutive transport failures —
     * distinct from a fresh transport failure so the caller can answer the
     * breaker-open failure mode without attempting the call. */
    public static final class BreakerOpenException extends UnreachableException {
        public BreakerOpenException(String message) {
            super(message);
        }
    }

    /** The backend answered non-2xx or malformed — reachability evidence,
     * never a breaker input; non-final so {@link GraphRejectedException}
     * stays assertable as {@code ResponseException} (D76 refund boundary). */
    public static class ResponseException extends IOException {
        private final int status;
        private final String errorType;

        ResponseException(String message) {
            this(0, message, "");
        }

        ResponseException(int status, String message) {
            this(status, message, "");
        }

        ResponseException(int status, String message, String errorType) {
            super(message);
            this.status = status;
            this.errorType = errorType;
        }

        public int status() {
            return status;
        }

        public String errorType() {
            return errorType;
        }
    }

    /** The backend rejected the submitted graph BEFORE any job ran — the
     * D76 refund boundary: the GPU never started. */
    public static final class GraphRejectedException extends ResponseException {
        public GraphRejectedException(String message) {
            super(message);
        }
    }

    /** An endpoint-chosen body crossed the byte cap; the connection was cut
     * and the partial body discarded before any retention (P7). */
    public static final class BodyOverCapException extends IOException {
        BodyOverCapException(String message) {
            super(message);
        }
    }

    /** The job exceeded its deadline AFTER the cancel call was issued (P9).
     * {@code jobStarted} carries the D76 refund boundary: a job that never
     * left the queue refunds the attempt. */
    public static final class JobTimeoutException extends IOException {
        private final boolean jobStarted;

        public JobTimeoutException(String message, boolean jobStarted) {
            super(message);
            this.jobStarted = jobStarted;
        }

        public boolean jobStarted() {
            return jobStarted;
        }
    }

    /** /stop's interrupt cancelled the job; {@code jobStarted} carries the
     * same D76 refund boundary as {@link JobTimeoutException}. */
    public static final class JobCancelledException extends IOException {
        private final boolean jobStarted;

        public JobCancelledException(String message, boolean jobStarted) {
            super(message);
            this.jobStarted = jobStarted;
        }

        public boolean jobStarted() {
            return jobStarted;
        }
    }
}
