package app.zcat.infochat.provider.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stub-server tests for the ComfyUI backend client (M1-802; D75/D77,
 * commands.md §Content). The stub is a JDK HttpServer; the client is
 * built through its seam constructor with a pinned or real clock. */
class ComfyUIClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The wizard's template shape (prod/scripts/4b-image.sh write_template):
     * latent + KSampler + decode + lanczos fit + SaveImage. */
    private static final String TEMPLATE = """
            {
              "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "stub.safetensors"}},
              "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": "stub.safetensors", "type": "mage"}},
              "4": {"class_type": "CLIPTextEncode", "inputs": {"text": "INFOCHAT_PROMPT_PLACEHOLDER", "clip": ["2", 0]}},
              "5": {"class_type": "CLIPTextEncode", "inputs": {"text": "blurry, low quality", "clip": ["2", 0]}},
              "6": {"class_type": "EmptyFlux2LatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
              "7": {"class_type": "KSampler", "inputs": {"model": ["1", 0], "seed": 0, "steps": 4, "cfg": 1.0,
                    "sampler_name": "euler", "scheduler": "simple", "positive": ["4", 0], "negative": ["5", 0],
                    "latent_image": ["6", 0], "denoise": 1.0}},
              "8": {"class_type": "VAEDecode", "inputs": {"samples": ["7", 0], "vae": ["3", 0]}},
              "9": {"class_type": "ImageScaleToTotalPixels", "inputs": {"image": ["8", 0],
                    "upscale_method": "lanczos", "megapixels": 1.0, "resolution_steps": 1}},
              "10": {"class_type": "SaveImage", "inputs": {"images": ["9", 0], "filename_prefix": "infochat"}}
            }
            """;

    /** TEMPLATE minus the fit node — rejected at load since the converter
     * could not honour an exact output size. */
    private static final String TEMPLATE_WITHOUT_FIT = """
            {
              "4": {"class_type": "CLIPTextEncode", "inputs": {"text": "INFOCHAT_PROMPT_PLACEHOLDER"}},
              "6": {"class_type": "EmptyFlux2LatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
              "7": {"class_type": "KSampler", "inputs": {"seed": 0, "latent_image": ["6", 0]}},
              "8": {"class_type": "VAEDecode", "inputs": {"samples": ["7", 0]}},
              "9": {"class_type": "SaveImage", "inputs": {"images": ["8", 0], "filename_prefix": "infochat"}}
            }
            """;

    /** TEMPLATE with the KSampler's latent_image link removed — rejected at
     * load (the budget derivation needs the link). */
    private static final String TEMPLATE_WITHOUT_LATENT_LINK = """
            {
              "4": {"class_type": "CLIPTextEncode", "inputs": {"text": "INFOCHAT_PROMPT_PLACEHOLDER"}},
              "6": {"class_type": "EmptyFlux2LatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
              "7": {"class_type": "KSampler", "inputs": {"seed": 0}},
              "9": {"class_type": "ImageScaleToTotalPixels", "inputs": {"image": ["8", 0],
                    "upscale_method": "lanczos", "megapixels": 1.0, "resolution_steps": 1}},
              "10": {"class_type": "SaveImage", "inputs": {"images": ["9", 0], "filename_prefix": "infochat"}}
            }
            """;

    @TempDir
    Path tempDir;

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopStubs() {
        for (HttpServer server : servers) {
            server.stop(0);
        }
    }

    @Test
    void promptLandsInExactlyOneGraphStringField() throws Exception {
        ComfyUIClient client = configuredClient(stubServer(), Duration.ofMinutes(1));
        String prompt = "He said \"hi\" {curly} [brackets] \\backslash\\ "
                + "{{template}} %s $var </script>\nnew line\ttab — unicode é";

        String graph = client.buildGraph(prompt);

        JsonNode root = JSON.readTree(graph);
        List<String> locations = new ArrayList<>();
        findStringValues(root, prompt, "", locations);
        assertEquals(1, locations.size(),
                "the prompt must appear as exactly one string value in the serialized graph, found: " + locations);
        String location = locations.get(0);
        assertTrue(location.endsWith("/inputs/text"), "the prompt must sit in a node's text input");
        String nodePointer = location.substring(0, location.length() - "/inputs/text".length());
        assertEquals("CLIPTextEncode", root.at(nodePointer).path("class_type").asText(),
                "the prompt must sit in a CLIPTextEncode node");

        List<String> leftovers = new ArrayList<>();
        findStringValues(root, ComfyUIClient.PROMPT_PLACEHOLDER, "", leftovers);
        assertTrue(leftovers.isEmpty(), "no placeholder may remain in the built graph");
    }

    @Test
    void buildGraphWithoutResolutionKeepsTheBakedGraph() throws Exception {
        ComfyUIClient client = configuredClient(stubServer(), Duration.ofMinutes(1));

        JsonNode graph = JSON.readTree(client.buildGraph("a prompt"));

        assertEquals(1024, graph.path("6").path("inputs").path("width").asLong(),
                "a no-flag job keeps the baked latent width");
        assertEquals(1024, graph.path("6").path("inputs").path("height").asLong(),
                "a no-flag job keeps the baked latent height");
        assertEquals("ImageScaleToTotalPixels", graph.path("9").path("class_type").asText(),
                "a no-flag job keeps the baked fit node");
        assertEquals(1.0, graph.path("9").path("inputs").path("megapixels").asDouble(),
                "a no-flag job keeps the baked fit target");
    }

    @Test
    void buildGraphHonoursAnExactPerJobOutputSize() throws Exception {
        ComfyUIClient client = configuredClient(stubServer(), Duration.ofMinutes(1));

        JsonNode graph = JSON.readTree(client.buildGraph("a prompt", 512, 768));

        // The ratio is steered at sampling: the 1024x1024 budget at 2:3, /16.
        assertEquals(832, graph.path("6").path("inputs").path("width").asLong(),
                "the latent carries the requested ratio at the baked budget");
        assertEquals(1248, graph.path("6").path("inputs").path("height").asLong(),
                "the latent carries the requested ratio at the baked budget");
        // The resolution is steered at the fit stage: exact W/H.
        assertEquals("ImageScale", graph.path("9").path("class_type").asText(),
                "the fit node becomes an exact-W/H ImageScale");
        assertEquals(512, graph.path("9").path("inputs").path("width").asLong());
        assertEquals(768, graph.path("9").path("inputs").path("height").asLong());
        assertEquals("lanczos", graph.path("9").path("inputs").path("upscale_method").asText(),
                "the baked lanczos method survives the swap");
        assertTrue(graph.path("9").path("inputs").path("megapixels").isMissingNode(),
                "the megapixels scalar is gone after the swap");
        assertTrue(graph.path("9").path("inputs").path("resolution_steps").isMissingNode(),
                "the resolution_steps scalar is gone after the swap");
    }

    @Test
    void samplingDimsHoldTheBudgetForSmallTargets() {
        long[] dims = ComfyUIClient.samplingDimsFor(1024, 1024, 512, 512);
        assertEquals(1024, dims[0], "a small target still samples at the budget —");
        assertEquals(1024, dims[1], "the flag never constrains the sampler");
    }

    @Test
    void samplingDimsCapAHostileRatio() {
        long[] dims = ComfyUIClient.samplingDimsFor(1024, 1024, 2_000_000, 1);
        assertTrue(dims[0] <= ComfyUIClient.MAX_SAMPLING_EDGE,
                "a hostile ratio must not blow the latent width past the cap; got " + dims[0]);
        assertTrue(dims[1] >= 16, "the small edge floors at 16; got " + dims[1]);
        assertEquals(0, dims[0] % 16, "sampling dims stay /16");
        assertEquals(0, dims[1] % 16, "sampling dims stay /16");
    }

    @Test
    void templateWithoutAFitNodeIsRejectedAtLoad() throws Exception {
        Path file = writeTemplate(TEMPLATE_WITHOUT_FIT);
        assertThrows(IllegalArgumentException.class, () -> clientOver(file));
    }

    @Test
    void templateWithoutALatentLinkIsRejectedAtLoad() throws Exception {
        Path file = writeTemplate(TEMPLATE_WITHOUT_LATENT_LINK);
        assertThrows(IllegalArgumentException.class, () -> clientOver(file));
    }

    @Test
    void overCapResponseBodyIsRefusedBeforeAnyBytesAreRetained() throws Exception {
        long cap = 1024;
        long total = 64 * 1024;
        AtomicLong bytesAcceptedByStub = new AtomicLong();
        HttpServer stub = stubServer();
        stub.createContext("/view", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, total);
            try (OutputStream out = exchange.getResponseBody()) {
                byte[] chunk = new byte[64];
                for (long written = 0; written < total; written += chunk.length) {
                    out.write(chunk);
                    out.flush();
                    bytesAcceptedByStub.addAndGet(chunk.length);
                }
            } catch (IOException expected) {
                // the client cut the connection at the cap
            }
            exchange.close();
        });
        ComfyUIClient client = configuredClient(stub, Duration.ofMinutes(1), cap);

        assertThrows(ComfyUIClient.BodyOverCapException.class,
                () -> client.fetchImage("infochat_00001_.png", ""));
        assertTrue(bytesAcceptedByStub.get() < total,
                "the client must cut the connection at the cap, but the stub managed to push "
                        + bytesAcceptedByStub.get() + " of " + total + " bytes");
    }

    @Test
    void timeoutCancelsTheBackendJob() throws Exception {
        String promptId = "11111111-2222-3333-4444-555555555555";
        List<RecordedCall> calls = new CopyOnWriteArrayList<>();
        HttpServer stub = stubServer();
        stub.createContext("/prompt", exchange -> {
            calls.add(record(exchange));
            respondJson(exchange, 200, "{\"prompt_id\": \"" + promptId + "\", \"number\": 1}");
        });
        stub.createContext("/history/", exchange -> {
            calls.add(record(exchange));
            respondJson(exchange, 200, "{}");
        });
        stub.createContext("/interrupt", exchange -> {
            calls.add(record(exchange));
            respondJson(exchange, 200, "{}");
        });
        stub.createContext("/queue", exchange -> {
            calls.add(record(exchange));
            if ("POST".equals(exchange.getRequestMethod())) {
                respondJson(exchange, 200, "{}");
            } else {
                respondJson(exchange, 200, "{\"queue_running\": [], \"queue_pending\": []}");
            }
        });
        stub.createContext("/history", exchange -> {
            calls.add(record(exchange));
            respondJson(exchange, 200, "{}");
        });
        ComfyUIClient client = configuredClientBuilder(stub)
                .jobTimeout(Duration.ofMillis(1))
                .pollInterval(Duration.ofMillis(1))
                .build();

        assertThrows(ComfyUIClient.JobTimeoutException.class, () -> client.generate("a prompt"));

        Optional<RecordedCall> interrupt = calls.stream()
                .filter(call -> "/interrupt".equals(call.path())).findFirst();
        assertTrue(interrupt.isPresent(), "the timeout must issue the backend interrupt call");
        assertTrue(interrupt.get().body().contains(promptId),
                "the interrupt must target the submitted job");
        Optional<RecordedCall> queueDelete = calls.stream()
                .filter(call -> "/queue".equals(call.path()) && "POST".equals(call.method()))
                .findFirst();
        assertTrue(queueDelete.isPresent(),
                "the timeout must also delete the job from the queue in case it never started");
        assertTrue(queueDelete.get().body().contains(promptId));
        Optional<RecordedCall> historyClear = calls.stream()
                .filter(call -> "/history".equals(call.path()) && "POST".equals(call.method()))
                .findFirst();
        assertTrue(historyClear.isPresent(),
                "a cancelled job's submitted graph must still be cleared from the backend history (D75)");
        assertTrue(historyClear.get().body().contains(promptId));
    }

    @Test
    void queueDepthIsReadFromTheBackend() throws Exception {
        HttpServer stub = stubServer();
        stub.createContext("/queue", exchange -> respondJson(exchange, 200,
                "{\"queue_running\": [[1, \"a\", {}, {}]], "
                        + "\"queue_pending\": [[2, \"b\", {}, {}], [3, \"c\", {}, {}]]}"));
        ComfyUIClient client = configuredClient(stub, Duration.ofMinutes(1));

        assertEquals(3, client.queueDepth());
    }

    @Test
    void historyIsClearedAfterACompletedJob() throws Exception {
        String promptId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        List<RecordedCall> calls = new CopyOnWriteArrayList<>();
        HttpServer stub = stubServer();
        stub.createContext("/prompt", exchange -> {
            calls.add(record(exchange));
            respondJson(exchange, 200, "{\"prompt_id\": \"" + promptId + "\", \"number\": 1}");
        });
        stub.createContext("/history/", exchange -> {
            calls.add(record(exchange));
            respondJson(exchange, 200, """
                    {"%s": {"prompt": [], "status": {"status_str": "success", "completed": true},
                      "outputs": {"10": {"images": [
                        {"filename": "infochat_00001_.png", "subfolder": "", "type": "output"}]}}}}
                    """.formatted(promptId));
        });
        stub.createContext("/view", exchange -> {
            calls.add(record(exchange));
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(png);
            }
            exchange.close();
        });
        stub.createContext("/history", exchange -> {
            calls.add(record(exchange));
            respondJson(exchange, 200, "{}");
        });
        ComfyUIClient client = configuredClient(stub, Duration.ofMinutes(1));

        byte[] fetched = client.generate("a prompt");

        assertArrayEquals(png, fetched);
        Optional<RecordedCall> clear = calls.stream()
                .filter(call -> "/history".equals(call.path()) && "POST".equals(call.method()))
                .findFirst();
        assertTrue(clear.isPresent(), "the client must clear the backend history after a completed job");
        JsonNode clearBody = JSON.readTree(clear.get().body());
        JsonNode delete = clearBody.path("delete");
        assertEquals(1, delete.size(), "the clear must target exactly one history entry");
        assertEquals(promptId, delete.get(0).asText(),
                "the clear must delete exactly the submitted job's entry");
    }

    @Test
    void backendErrorBodyIsReducedToItsErrorType() throws Exception {
        String canary = "CANARY-prompt-text-quoted-by-backend";
        HttpServer stub = stubServer();
        stub.createContext("/prompt", exchange -> respondJson(exchange, 400, """
                {"error": {"type": "value_error", "message": "invalid prompt: %s"},
                 "node_errors": {}}
                """.formatted(canary)));
        ComfyUIClient client = configuredClient(stub, Duration.ofMinutes(1));

        ComfyUIClient.ResponseException e = assertThrows(ComfyUIClient.ResponseException.class,
                () -> client.generate("a prompt"));
        assertTrue(e.getMessage().contains("value_error"),
                "the failure must carry the backend's error type");
        assertFalse(e.getMessage().contains(canary),
                "the backend's error body must not reach the failure message (P4)");
    }

    @Test
    void breakerOpensAfterConsecutiveTransportFailures() throws Exception {
        // A port with nothing listening: connection REFUSAL is a transport
        // failure the JDK HttpClient does not transparently retry (an
        // accepted-then-closed socket IS retried).
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            deadPort = probe.getLocalPort();
        }

        SettableClock clock = new SettableClock();
        ComfyUIClient client = new ComfyUIClient(
                Optional.of("http://127.0.0.1:" + deadPort),
                writeTemplate(),
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(1),
                Duration.ofMillis(10), 1024 * 1024, clock);

        for (int i = 0; i < ComfyUIClient.BREAKER_FAILURE_THRESHOLD; i++) {
            assertThrows(ComfyUIClient.UnreachableException.class, client::queueDepth);
        }

        // The breaker is now OPEN: a live backend on the same port must not
        // be attempted at all until the cooldown elapses.
        AtomicInteger queueHits = new AtomicInteger();
        HttpServer lateStub = HttpServer.create(
                new InetSocketAddress("127.0.0.1", deadPort), 0);
        lateStub.createContext("/queue", exchange -> {
            queueHits.incrementAndGet();
            respondJson(exchange, 200, "{\"queue_running\": [], \"queue_pending\": []}");
        });
        lateStub.start();
        servers.add(lateStub);
        try {
            assertThrows(ComfyUIClient.UnreachableException.class, client::queueDepth);
            assertEquals(0, queueHits.get(),
                    "an open breaker must deny the call without any HTTP attempt");

            clock.now = clock.now.plus(ComfyUIClient.BREAKER_COOLDOWN).plusSeconds(1);
            assertEquals(0, client.queueDepth(),
                    "after the cooldown the half-open probe must reach the backend");
            assertEquals(1, queueHits.get());
        } finally {
            lateStub.stop(0);
            servers.remove(lateStub);
        }
    }

    // --- fixtures ----------------------------------------------------------

    private record RecordedCall(String method, String path, String body) {}

    private static final class SettableClock extends Clock {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Builder so each test can tune the timeouts around the defaults. */
    private final class ClientBuilder {
        private final HttpServer stub;
        private Duration jobTimeout = Duration.ofMinutes(1);
        private Duration pollInterval = Duration.ofMillis(10);

        ClientBuilder(HttpServer stub) {
            this.stub = stub;
        }

        ClientBuilder jobTimeout(Duration value) {
            jobTimeout = value;
            return this;
        }

        ClientBuilder pollInterval(Duration value) {
            pollInterval = value;
            return this;
        }

        ComfyUIClient build() throws IOException {
            return new ComfyUIClient(
                    Optional.of("http://127.0.0.1:" + stub.getAddress().getPort()),
                    writeTemplate(),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), jobTimeout,
                    pollInterval, 1024 * 1024, Clock.systemUTC());
        }
    }

    private ClientBuilder configuredClientBuilder(HttpServer stub) {
        return new ClientBuilder(stub);
    }

    private ComfyUIClient configuredClient(HttpServer stub, Duration jobTimeout) throws IOException {
        return configuredClient(stub, jobTimeout, 1024 * 1024);
    }

    private ComfyUIClient configuredClient(HttpServer stub, Duration jobTimeout, long maxResponseBytes)
            throws IOException {
        return new ComfyUIClient(
                Optional.of("http://127.0.0.1:" + stub.getAddress().getPort()),
                writeTemplate(),
                Duration.ofSeconds(5), Duration.ofSeconds(5), jobTimeout,
                Duration.ofMillis(10), maxResponseBytes, Clock.systemUTC());
    }

    private Path writeTemplate() throws IOException {
        return writeTemplate(TEMPLATE);
    }

    private Path writeTemplate(String content) throws IOException {
        Path file = tempDir.resolve("workflow-" + System.nanoTime() + ".json");
        Files.writeString(file, content);
        return file;
    }

    private ComfyUIClient clientOver(Path templateFile) {
        return new ComfyUIClient(
                Optional.of("http://127.0.0.1:1"), templateFile,
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(1),
                Duration.ofMillis(10), 1024 * 1024, Clock.systemUTC());
    }

    private HttpServer stubServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        servers.add(server);
        return server;
    }

    private static RecordedCall record(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new RecordedCall(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body);
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private static void findStringValues(JsonNode node, String wanted, String pointer, List<String> out) {
        if (node.isTextual()) {
            if (node.asText().equals(wanted)) {
                out.add(pointer);
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    findStringValues(entry.getValue(), wanted, pointer + "/" + entry.getKey(), out));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                findStringValues(node.get(i), wanted, pointer + "/" + i, out);
            }
        }
    }
}
