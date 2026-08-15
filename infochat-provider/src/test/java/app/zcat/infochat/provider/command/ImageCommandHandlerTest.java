package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundAttachment;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ProgressStage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.chat.tool.QueryAnchorTranslator;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.image.ComfyUIClient;
import app.zcat.infochat.provider.image.ImagePreviewGenerator;
import app.zcat.infochat.provider.image.ImageSpool;
import app.zcat.infochat.provider.image.PngMetadataStrip;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.messaging.HelpCommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.OutboundDelivery;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import app.zcat.infochat.provider.messaging.StageProgressNotifier;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.testsupport.PngFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Handler-tier (plain JUnit, no Quarkus boot) tests for
 * {@link ImageCommandHandler}: the D73 config gate (reproduction), the D76
 * refund boundary, and the eight-mode failure contract (commands.md §Content). */
class ImageCommandHandlerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-10T12:00:00Z");

    @TempDir
    Path tempDir;

    private ImageCommandHandler handler;
    private BundleLoader bundleLoader;
    private RateCapBucket rateCapBucket;
    private ImageCreditGate gate;
    private SettableClock clock;
    private StubComfyUIClient client;
    private StubImageAdapter adapter;
    private ImageStubDataSource dataSource;
    private RecordingImageAuditWriter auditWriter;
    private RecordingImageNotifier notifier;
    private ImageSpool spool;
    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void buildHandlerWithStubs() throws Exception {
        bundleLoader = newRealBundleLoader();
        clock = new SettableClock();
        rateCapBucket = new RateCapBucket(clock, RateCapBucket.Settings.defaults()
                .withImageUserCreditBucket(2, Duration.ofHours(1))
                .withImageGroupCreditBucket(2, Duration.ofHours(1))
                .withImageCooldownWindow(Duration.ofSeconds(15)));
        gate = new ImageCreditGate();
        gate.rateCapBucket = rateCapBucket;
        gate.maxQueueDepth = 3;

        client = new StubComfyUIClient();
        adapter = new StubImageAdapter(true, 1_048_576);
        dataSource = new ImageStubDataSource(userId, groupId);
        auditWriter = new RecordingImageAuditWriter();
        notifier = new RecordingImageNotifier();
        spool = new ImageSpool(tempDir, 1_000_000L);

        handler = new ImageCommandHandler();
        handler.bundleLoader = bundleLoader;
        handler.imageCreditGate = gate;
        handler.comfyUIClient = client;
        handler.dataSource = dataSource;
        handler.auditLogWriter = auditWriter;
        handler.progressNotifier = notifier;
        handler.imageSpool = spool;
        handler.imagePreviewGenerator = new ImagePreviewGenerator(65_536L, 14_822);
        handler.inFlightTracker = new InFlightTracker();
        handler.adapterRegistry = registryOver(adapter);
        handler.outboundDelivery = new OutboundDelivery(
                new ThrottledAdminNotifier(), new GroupRepository(dataSource), 3, 0L, 2.0, 3);
        handler.queryAnchorTranslator = new StubTranslator();
        handler.llmOutputSanitizer = new LlmOutputSanitizer(auditWriter, SanitizerTestDoubles.noOpDataSource());
        // Manual field injection misses @ConfigProperty defaults.
        handler.imageBaseUrl = Optional.of("http://comfyui:8188");
        handler.promptMaxChars = 500;
        handler.maxOutputPixels = 2_000_000L;
        handler.minOutputPixels = 16_384L;
        handler.steadyStateSeconds = Optional.empty();

        InboundContext context = new InboundContext();
        context.setAdapterName("inmemory");
        context.setSenderContactId("alice");
        handler.inboundContext = context;
    }

    /** REPRODUCTION (M1-803, workflow §0): with no base-url the command does
     * not exist (D73 runtime gating) — invoking it yields EXACTLY the router's
     * unknown-command body and /help does not list it; run RED before any fix. */
    @Test
    void unconfiguredBaseUrlYieldsTheUnknownCommandReply() throws Exception {
        handler.imageBaseUrl = Optional.empty();

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image -p foo");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND), reply.text(),
                "an unconfigured /image must answer with exactly the router's unknown-command body");

        HelpCommandHandlerProbe probe = new HelpCommandHandlerProbe(bundleLoader);
        HelpCommandHandler.CommandHelp imageEntry = probe.imageEntry();
        assertFalse(probe.visible(imageEntry, Optional.empty()),
                "an unconfigured /image must not be listed in /help");
        assertTrue(probe.visible(imageEntry, Optional.of("http://comfyui:8188")),
                "a configured /image is listed in /help — the gate is the config, not the tier");
    }

    @Test
    void falseFlagAdapterAnswersTextFallbackWithoutCharging() {
        adapter.supportsAttachments = false;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image a cat");

        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_NO_ATTACHMENT_SUPPORT), reply.text(),
                "a false capability flag answers with the no-attachment text");
        assertEquals(0, client.generateCalls.get(),
                "the client must never be invoked when the flag is false (P2)");
        assertEquals(0, client.queueDepthCalls.get(),
                "the static flag gate runs before the queue read");
        assertTrue(rateCapBucket.tryAcquireImageUserCredit(userId),
                "zero credits drawn — the flag gate runs BEFORE charging (P2)");
    }

    @Test
    void belowFloorResolutionIsRejectedLocalizedAndFree() {
        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image -r 1x1024 a cat");

        assertEquals(java.text.MessageFormat.format(
                bundleLoader.get(BundleKeys.IMAGE_ERROR_RESOLUTION_TOO_SMALL), "4", "4096"),
                reply.text());
        assertTrue(rateCapBucket.tryAcquireImageUserCredit(userId),
                "the parser rejection runs before the user credit gate");
        assertTrue(rateCapBucket.tryAcquireImageGroupCredit(groupId),
                "the parser rejection runs before the group credit gate");
        assertEquals(0, client.queueDepthCalls.get(), "the parser rejection skips backend calls");
        assertEquals(0, client.generateCalls.get(), "the parser rejection skips generation");
        assertTrue(auditWriter.rowsFor(AuditAction.IMAGE_GENERATE).isEmpty(),
                "the parser rejection writes no IMAGE_GENERATE row");
    }

    @Test
    void overCeilingResolutionIsRejectedLocalizedWithSuggestedDimensions() {
        handler.maxOutputPixels = 5_000_000L;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image -r 3000x3000 a cat");

        assertEquals("That resolution is too large (up to about 2236x2236).", reply.text());
    }

    @Test
    void backendUnreachableRefunds() {
        gate.rateCapBucket = rateCapBucket = new RateCapBucket(clock,
                RateCapBucket.Settings.defaults()
                        .withImageUserCreditBucket(1, Duration.ofHours(1))
                        .withImageGroupCreditBucket(1, Duration.ofHours(1))
                        .withImageCooldownWindow(Duration.ofMillis(1)));
        client.queueDepthThrow = new ComfyUIClient.UnreachableException("image backend unreachable");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image a cat");

        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_BACKEND_UNREACHABLE), reply.text());
        assertTrue(rateCapBucket.tryAcquireImageUserCredit(userId),
                "an unreachable backend refunds the charged attempt (D76: the GPU never ran)");
    }

    @Test
    void queueDepthUnreachableWritesTheAuditRow() {
        gate.rateCapBucket = rateCapBucket = new RateCapBucket(clock,
                RateCapBucket.Settings.defaults()
                        .withImageUserCreditBucket(1, Duration.ofHours(1))
                        .withImageGroupCreditBucket(1, Duration.ofHours(1))
                        .withImageCooldownWindow(Duration.ofMillis(1)));
        client.queueDepthThrow = new ComfyUIClient.UnreachableException("image backend unreachable");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image a cat");

        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_BACKEND_UNREACHABLE), reply.text());
        assertTrue(rateCapBucket.tryAcquireImageUserCredit(userId),
                "an unreachable backend refunds the charged attempt (D76)");
        List<RedactionHook.AuditRow> rows = auditWriter.rowsFor(AuditAction.IMAGE_GENERATE);
        assertEquals(1, rows.size(), "the failed queue-read attempt writes exactly one audit row");
        assertEquals("{\"outcome\":\"failed\"}", rows.get(0).detailsJson(),
                "the failed row is content-free");
    }

    @Test
    void queueDepthBreakerOpenWritesTheAuditRow() {
        gate.rateCapBucket = rateCapBucket = new RateCapBucket(clock,
                RateCapBucket.Settings.defaults()
                        .withImageUserCreditBucket(1, Duration.ofHours(1))
                        .withImageGroupCreditBucket(1, Duration.ofHours(1))
                        .withImageCooldownWindow(Duration.ofMillis(1)));
        client.queueDepthThrow = new ComfyUIClient.BreakerOpenException("breaker open");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image a cat");

        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_BREAKER_OPEN), reply.text());
        assertTrue(rateCapBucket.tryAcquireImageUserCredit(userId),
                "an open breaker refunds the charged attempt (D76)");
        List<RedactionHook.AuditRow> rows = auditWriter.rowsFor(AuditAction.IMAGE_GENERATE);
        assertEquals(1, rows.size(), "the breaker-refused attempt writes exactly one audit row");
        assertEquals("{\"outcome\":\"failed\"}", rows.get(0).detailsJson());
    }

    @Test
    void queueDepthIoFailureWritesTheAuditRow() {
        gate.rateCapBucket = rateCapBucket = new RateCapBucket(clock,
                RateCapBucket.Settings.defaults()
                        .withImageUserCreditBucket(1, Duration.ofHours(1))
                        .withImageGroupCreditBucket(1, Duration.ofHours(1))
                        .withImageCooldownWindow(Duration.ofMillis(1)));
        client.queueDepthThrow = new IOException("queue read failed");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image a cat");

        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_GENERATION_FAILED), reply.text());
        assertTrue(rateCapBucket.tryAcquireImageUserCredit(userId),
                "a queue-read I/O failure refunds the charged attempt (D76)");
        List<RedactionHook.AuditRow> rows = auditWriter.rowsFor(AuditAction.IMAGE_GENERATE);
        assertEquals(1, rows.size(), "the failed queue-read attempt writes exactly one audit row");
        assertEquals("{\"outcome\":\"failed\"}", rows.get(0).detailsJson());
    }

    @Test
    void queueOverBudgetWritesTheAuditRow() {
        gate.rateCapBucket = rateCapBucket = new RateCapBucket(clock,
                RateCapBucket.Settings.defaults()
                        .withImageUserCreditBucket(1, Duration.ofHours(1))
                        .withImageGroupCreditBucket(1, Duration.ofHours(1))
                        .withImageCooldownWindow(Duration.ofMillis(1)));
        client.queueDepthResult = gate.maxQueueDepth;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image a cat");

        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_QUEUE_BUSY_NO_ETA), reply.text());
        assertTrue(rateCapBucket.tryAcquireImageUserCredit(userId),
                "a queue-over-budget refusal refunds the charged attempt (D76)");
        List<RedactionHook.AuditRow> rows = auditWriter.rowsFor(AuditAction.IMAGE_GENERATE);
        assertEquals(1, rows.size(), "the queue-refused attempt writes exactly one audit row");
        assertEquals("{\"outcome\":\"failed\"}", rows.get(0).detailsJson());
    }

    @Test
    void adapterSendFailureDoesNotRefund() {
        gate.rateCapBucket = rateCapBucket = new RateCapBucket(clock,
                RateCapBucket.Settings.defaults()
                        .withImageUserCreditBucket(1, Duration.ofHours(1))
                        .withImageGroupCreditBucket(1, Duration.ofHours(1))
                        .withImageCooldownWindow(Duration.ofMillis(1)));
        client.generateResult = validPng();
        adapter.failAttachmentsWith = FailureCategory.PERMANENT;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image a cat");

        assertNull(reply, "the send-failure terminal self-delivers on the placeholder");
        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_SEND_FAILED), notifier.completedText());
        assertFalse(rateCapBucket.tryAcquireImageUserCredit(userId),
                "NO refund once the GPU ran — the adapter send failed AFTER generation (D76)");
    }

    @Test
    void czechScopeTranslatesAndEchoesTheEnglishPrompt() {
        StubTranslator translator = new StubTranslator();
        translator.translation = "a red bicycle";
        handler.queryAnchorTranslator = translator;
        handler.inboundContext.setEffectiveLanguage("cs");
        client.generateResult = validPng();

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image červené kolo");

        assertNull(reply);
        assertEquals("červené kolo", translator.lastQuery,
                "the non-en prompt enters the translation leg verbatim");
        assertEquals("cs", translator.lastSourceLanguage);
        assertEquals("a red bicycle", client.lastPrompt,
                "the translated English prompt — not the original — reaches generation");
        assertTrue(notifier.completedText().contains("a red bicycle"),
                "the echo carries the English prompt actually used; got: " + notifier.completedText());
        assertTrue(notifier.publishedStages().contains(ProgressStage.TRANSLATING),
                "a non-en scope publishes the TRANSLATING stage");
    }

    @Test
    void translatorFailureSendsTheOriginalPrompt() {
        // The inherited QueryAnchorTranslator fallback ships the ORIGINAL
        // prompt on any translator failure — degraded adherence, not an
        // error (P14). The stub mirrors that fallback contract.
        StubTranslator translator = new StubTranslator();
        translator.translation = null;
        handler.queryAnchorTranslator = translator;
        handler.inboundContext.setEffectiveLanguage("cs");
        client.generateResult = validPng();

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image červené kolo");

        assertNull(reply);
        assertEquals("červené kolo", translator.lastQuery);
        assertTrue(notifier.completedText().contains("červené kolo"),
                "the fallback ships the untranslated prompt; got: " + notifier.completedText());
    }

    @Test
    void promptContainingAPrivilegedCommandStringIsRedactedInTheEcho() {
        client.generateResult = validPng();

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm("alice"), "/image -p a poster saying /grant-admin now");

        assertNull(reply);
        String echo = notifier.completedText();
        assertFalse(echo.contains("/grant-admin"),
                "the privileged command string must not reach the reader verbatim; got: " + echo);
        assertTrue(echo.contains("[redacted command]"),
                "the echo carries the redaction marker; got: " + echo);
        assertTrue(auditWriter.actions().contains(AuditAction.LLM_OUTPUT_SANITIZED),
                "the sanitize call writes its LLM_OUTPUT_SANITIZED audit row");
    }

    @Test
    void overCeilingOutputFailsLoudlyAndContentFree() {
        // FAILURE-MODE (M1-811 acceptance 4, analysis P2): an over-ceiling
        // PNG is refused at the strip — generic terminal, one content-free
        // failed row, no spool file; the WARN is review-grepped.
        client.generateResult = PngFixtures.minimalPng(1500, 1500);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image a red cat");

        assertNull(reply, "the strip-arm terminal self-delivers on the placeholder");
        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_GENERATION_FAILED),
                notifier.completedText(), "the terminal is the generic generation failure");
        List<RedactionHook.AuditRow> rows = auditWriter.rowsFor(AuditAction.IMAGE_GENERATE);
        assertEquals(1, rows.size(), "exactly one content-free row per rejected output");
        assertTrue(rows.get(0).detailsJson().contains("\"outcome\":\"failed\""),
                "the row records the failed outcome; got: " + rows.get(0).detailsJson());
        assertFalse(rows.get(0).detailsJson().contains("a red cat"),
                "never the prompt in details_json (D75)");
        try (var entries = Files.list(tempDir)) {
            assertEquals(0, entries.count(),
                    "no spool file is created before the strip refusal");
        } catch (IOException e) {
            throw new AssertionError("spool dir unreadable", e);
        }
    }

    @Test
    void defaultOutputAtTheShippedCeilingDeliversEndToEnd() throws Exception {
        // REPRODUCTION (M1-816, analysis P14): the measured default output
        // 1792x1344 must deliver end-to-end at the SHIPPED ceiling — the
        // pre-M1-811 2_000_000 value refuses the pipeline's own default.
        handler.maxOutputPixels = readMaxOutputPixelsFromMainProperties();
        client.generateResult = PngFixtures.minimalPng(1792, 1344);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image a canary cat");

        assertNull(reply, "the delivered terminal self-delivers on the placeholder");
        assertEquals(1, adapter.attachmentSends.get(),
                "the stripped output is spooled and delivered through the real OutboundDelivery");
        List<RedactionHook.AuditRow> rows = auditWriter.rowsFor(AuditAction.IMAGE_GENERATE);
        assertEquals(1, rows.size(), "exactly one IMAGE_GENERATE row per delivered output");
        assertEquals("{\"outcome\":\"delivered\"}", rows.get(0).detailsJson());
        assertTrue(notifier.completedText().contains("a canary cat"),
                "the sanitized echo completes the placeholder; got: " + notifier.completedText());
        try (var entries = Files.list(tempDir)) {
            assertEquals(0, entries.count(),
                    "the spool file is reclaimed once delivery completes");
        }
    }

    /** M1-842 P2: the generator decodes exactly the STRIPPED bytes (the
     * strip's IHDR bound is what bounds the preview raster), and the
     * preview rides the delivery record. */
    @Test
    void previewIsGeneratedFromTheStrippedBytesAndRidesTheRecord() throws Exception {
        byte[] original = PngFixtures.withPromptChunk(
                PngFixtures.realPng(400, 200), "a canary prompt");
        client.generateResult = original;
        RecordingPreviewGenerator generator = new RecordingPreviewGenerator(65_536L, 14_822);
        handler.imagePreviewGenerator = generator;

        assertNull(handler.handle(new ScopeRef.Dm("alice"), "/image a cat"));

        assertArrayEquals(PngMetadataStrip.strip(original, 2_000_000L), generator.lastInput,
                "the generator never sees pre-strip bytes");
        assertEquals(1, adapter.attachmentSends.get());
        assertNotNull(adapter.lastAttachment.imagePreview(), "the generated preview rides the record");
        assertTrue(adapter.lastAttachment.imagePreview().startsWith("data:image/png;base64,"),
                "the recorded preview carries the recorded form");
    }

    /** M1-842 P10 (item 5): a preview-generation failure degrades to the
     * file form — delivery completes, the success outcome stands, no new
     * failure mode, no D76 refund interaction. */
    @Test
    void previewGenerationFailureDegradesToFileDelivery() {
        handler.imagePreviewGenerator = new RecordingPreviewGenerator(65_536L, 1);
        client.generateResult = PngFixtures.realPng(32, 32);

        assertNull(handler.handle(new ScopeRef.Dm("alice"), "/image a cat"));

        assertEquals(1, adapter.attachmentSends.get(), "the delivery still completes");
        assertNull(adapter.lastAttachment.imagePreview(),
                "a degraded preview rides the record as null");
        List<RedactionHook.AuditRow> rows = auditWriter.rowsFor(AuditAction.IMAGE_GENERATE);
        assertEquals(1, rows.size());
        assertEquals("{\"outcome\":\"delivered\"}", rows.get(0).detailsJson(),
                "the degrade writes no failure audit outcome");
    }

    /** M1-842 P2: bytes that fail strip validation never reach the preview
     * decoder — the strip refusal arm completes before the generator runs. */
    @Test
    void stripRefusalNeverInvokesThePreviewGenerator() {
        client.generateResult = PngFixtures.minimalPng(1500, 1500);
        RecordingPreviewGenerator generator = new RecordingPreviewGenerator(65_536L, 14_822);
        handler.imagePreviewGenerator = generator;

        assertNull(handler.handle(new ScopeRef.Dm("alice"), "/image a cat"));

        assertEquals(0, generator.calls.get(),
                "strip-failing bytes never reach the preview decoder");
        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_GENERATION_FAILED),
                notifier.completedText());
    }

    @Test
    void auditRowIsContentFree() {
        client.generateResult = validPng();
        String prompt = "a very distinctive canary prompt";

        OutboundMessage reply = handler.handle(new ScopeRef.Group("upstream-g1"), "/image " + prompt);

        assertNull(reply);
        List<RedactionHook.AuditRow> imageRows = auditWriter.rowsFor(AuditAction.IMAGE_GENERATE);
        assertEquals(1, imageRows.size(), "one IMAGE_GENERATE row per attempt");
        RedactionHook.AuditRow row = imageRows.get(0);
        assertEquals(userId, row.actorUserId(), "the row records the actor");
        assertEquals(groupId, row.scopeId(), "the row records the scope");
        assertTrue(row.detailsJson().contains("\"outcome\":\"delivered\""),
                "the row records the outcome; got: " + row.detailsJson());
        assertFalse(row.detailsJson().contains(prompt),
                "never the prompt in details_json (D75)");
        assertFalse(row.detailsJson().contains("hash"),
                "never a hash of the prompt (D75)");
    }

    @Test
    void generatingStageShowsTheEtaFromQueueDepthAndConfigConstant() {
        handler.steadyStateSeconds = Optional.of(5.0);
        client.queueDepthResult = 2;
        client.generateResult = validPng();

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image a cat");

        assertNull(reply);
        String expected = bundleLoader.get(BundleKeys.IMAGE_PROGRESS_GENERATING_ETA);
        String want = java.text.MessageFormat.format(expected, "3", "15");
        assertTrue(notifier.stageTexts().contains(want),
                "queue depth 2 + constant 5s → position 3, ETA (2+1)*5 = 15s; got: "
                        + notifier.stageTexts());
    }

    @Test
    void queueRefusalCarriesTheBacklogEstimate() {
        handler.steadyStateSeconds = Optional.of(5.0);
        gate.maxQueueDepth = 2;
        client.queueDepthResult = 4;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm("alice"), "/image a cat");

        String expected = bundleLoader.get(BundleKeys.IMAGE_ERROR_QUEUE_BUSY);
        assertEquals(java.text.MessageFormat.format(expected, "20"), reply.text(),
                "depth 4 × 5s constant → a 20s backlog estimate");
    }

    /** Acceptance item 6, second half: the /stop interrupt reaches the
     * client's cancel — a REAL {@link ComfyUIClient} against a JDK HttpServer
     * stub, handler on a worker thread interrupted in the backend poll loop. */
    @Test
    void stopCancelsARunningGeneration() throws Exception {
        String promptId = "99999999-8888-7777-6666-555555555555";
        List<RecordedCall> calls = new CopyOnWriteArrayList<>();
        CountDownLatch firstHistoryPoll = new CountDownLatch(1);
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/prompt", exchange -> {
            calls.add(record(exchange));
            respondJson(exchange, 200, "{\"prompt_id\": \"" + promptId + "\", \"number\": 1}");
        });
        stub.createContext("/history/", exchange -> {
            calls.add(record(exchange));
            firstHistoryPoll.countDown();
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
        stub.start();
        try {
            handler.comfyUIClient = new ComfyUIClient(
                    Optional.of("http://127.0.0.1:" + stub.getAddress().getPort()),
                    writeWorkflowTemplate(),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(5),
                    Duration.ofSeconds(5), 1024 * 1024, Clock.systemUTC());
            gate.rateCapBucket = rateCapBucket = new RateCapBucket(clock,
                    RateCapBucket.Settings.defaults()
                            .withImageUserCreditBucket(1, Duration.ofHours(1))
                            .withImageGroupCreditBucket(1, Duration.ofHours(1))
                            .withImageCooldownWindow(Duration.ofMillis(1)));

            AtomicReference<OutboundMessage> reply = new AtomicReference<>();
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    reply.set(handler.handle(new ScopeRef.Dm("alice"), "/image a cat"));
                } catch (Throwable t) {
                    workerFailure.set(t);
                }
            });
            worker.start();
            assertTrue(firstHistoryPoll.await(10, TimeUnit.SECONDS),
                    "the generation must reach the backend polling loop");
            Thread.sleep(100);
            worker.interrupt();
            worker.join(Duration.ofSeconds(10));
            assertFalse(worker.isAlive(), "the interrupted generation must terminate");
            if (workerFailure.get() != null) {
                throw new AssertionError("the generation worker failed", workerFailure.get());
            }

            assertNull(reply.get(), "a stopped turn self-delivers on the placeholder");
            Optional<RecordedCall> interrupt = calls.stream()
                    .filter(call -> "/interrupt".equals(call.path())).findFirst();
            assertTrue(interrupt.isPresent(),
                    "the /stop interrupt must reach the backend cancel call");
            assertTrue(interrupt.get().body().contains(promptId),
                    "the cancel must target the submitted job");
            assertEquals(bundleLoader.get(BundleKeys.PROGRESS_STOPPED), notifier.completedText(),
                    "the terminal is the stopped text");
            assertTrue(rateCapBucket.tryAcquireImageUserCredit(userId),
                    "the GPU never ran — the charged credit is refunded (D76)");
            List<RedactionHook.AuditRow> rows = auditWriter.rowsFor(AuditAction.IMAGE_GENERATE);
            assertEquals(1, rows.size(), "a stopped attempt still writes its content-free row");
            assertTrue(rows.get(0).detailsJson().contains("\"outcome\":\"stopped\""),
                    "the row records the stopped outcome; got: " + rows.get(0).detailsJson());
        } finally {
            stub.stop(0);
        }
    }

    /** Round-1 FINDING 1 probe: --resolution reaches the submitted graph as
     * per-job dims — a REAL {@link ComfyUIClient} against a JDK HttpServer
     * stub; the /prompt body carries the ratio-at-budget latent + exact fit. */
    @Test
    void resolutionReachesTheSubmittedGraphAsPerJobDims() throws Exception {
        String promptId = "12121212-3434-5656-7878-909090909090";
        byte[] png = validPng();
        List<RecordedCall> calls = new CopyOnWriteArrayList<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
        stub.createContext("/queue", exchange -> {
            calls.add(record(exchange));
            respondJson(exchange, 200, "{\"queue_running\": [], \"queue_pending\": []}");
        });
        stub.createContext("/history", exchange -> {
            calls.add(record(exchange));
            respondJson(exchange, 200, "{}");
        });
        stub.start();
        try {
            handler.comfyUIClient = new ComfyUIClient(
                    Optional.of("http://127.0.0.1:" + stub.getAddress().getPort()),
                    writeWorkflowTemplate(),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(5),
                    Duration.ofMillis(10), 1024 * 1024, Clock.systemUTC());

            assertNull(handler.handle(new ScopeRef.Dm("alice"), "/image -r 512x512 -p a cat"));
            assertTrue(notifier.completedText().contains("a cat"),
                    "the job completes the delivered path; got: " + notifier.completedText());

            RecordedCall promptCall = calls.stream()
                    .filter(call -> "/prompt".equals(call.path())).findFirst().orElseThrow();
            JsonNode graph = new ObjectMapper().readTree(promptCall.body()).path("prompt");
            assertEquals(1024, graph.path("6").path("inputs").path("width").asLong(),
                    "a square target at the 1024x1024 budget keeps the latent at budget");
            assertEquals(1024, graph.path("6").path("inputs").path("height").asLong());
            assertEquals("ImageScale", graph.path("9").path("class_type").asText(),
                    "the fit node is swapped to the exact-W/H ImageScale");
            assertEquals(512, graph.path("9").path("inputs").path("width").asLong(),
                    "the fit carries the exact requested width");
            assertEquals(512, graph.path("9").path("inputs").path("height").asLong(),
                    "the fit carries the exact requested height");
        } finally {
            stub.stop(0);
        }
    }

    /** Round-1 FINDING 2 probe: /stop landing inside submit leaves the
     * job's fate unreadable — conservatively started, NO refund. */
    @Test
    void stopDuringSubmitDoesNotRefund() {
        gate.rateCapBucket = rateCapBucket = new RateCapBucket(clock,
                RateCapBucket.Settings.defaults()
                        .withImageUserCreditBucket(1, Duration.ofHours(1))
                        .withImageGroupCreditBucket(1, Duration.ofHours(1))
                        .withImageCooldownWindow(Duration.ofMillis(1)));
        client.generateThrow = new InterruptedException();

        assertNull(handler.handle(new ScopeRef.Dm("alice"), "/image a cat"));
        Thread.interrupted();

        assertEquals(bundleLoader.get(BundleKeys.PROGRESS_STOPPED), notifier.completedText(),
                "the terminal is the stopped text");
        assertFalse(rateCapBucket.tryAcquireImageUserCredit(userId),
                "NO refund — an unreadable job state is conservatively started (D76)");
        List<RedactionHook.AuditRow> rows = auditWriter.rowsFor(AuditAction.IMAGE_GENERATE);
        assertEquals(1, rows.size(), "the stopped attempt still writes its content-free row");
        assertTrue(rows.get(0).detailsJson().contains("\"outcome\":\"stopped\""));
    }

    /** Round-1 FINDING 5 probe: /stop landing inside the queue-depth read
     * refunds AND writes the content-free stopped row like its siblings. */
    @Test
    void stopDuringQueueDepthReadWritesStoppedAuditRow() {
        gate.rateCapBucket = rateCapBucket = new RateCapBucket(clock,
                RateCapBucket.Settings.defaults()
                        .withImageUserCreditBucket(1, Duration.ofHours(1))
                        .withImageGroupCreditBucket(1, Duration.ofHours(1))
                        .withImageCooldownWindow(Duration.ofMillis(1)));
        client.queueDepthThrow = new InterruptedException();

        assertNull(handler.handle(new ScopeRef.Dm("alice"), "/image a cat"));
        Thread.interrupted();

        assertEquals(bundleLoader.get(BundleKeys.PROGRESS_STOPPED), notifier.completedText(),
                "the terminal is the stopped text");
        assertTrue(rateCapBucket.tryAcquireImageUserCredit(userId),
                "the GPU never ran — the queue-read stop refunds (D76)");
        List<RedactionHook.AuditRow> rows = auditWriter.rowsFor(AuditAction.IMAGE_GENERATE);
        assertEquals(1, rows.size(), "the queue-read stopped arm writes its content-free row");
        assertTrue(rows.get(0).detailsJson().contains("\"outcome\":\"stopped\""),
                "the row records the stopped outcome; got: " + rows.get(0).detailsJson());
    }

    @Test
    void failureContractCoversAllEightModes() {
        // One fast-cooldown, generous-credit bucket for the whole tour, so
        // each mode isolates its own gate; the clock advances past the
        // 1ms cooldown before every call.
        freshFastBucket();

        // 1. backend unreachable
        client.queueDepthThrow = new ComfyUIClient.UnreachableException("unreachable");
        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_BACKEND_UNREACHABLE),
                handler.handle(new ScopeRef.Dm("alice"), "/image a cat").text());
        tick();

        // 2. breaker open
        client.queueDepthThrow = new ComfyUIClient.BreakerOpenException("breaker open");
        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_BREAKER_OPEN),
                handler.handle(new ScopeRef.Dm("alice"), "/image a cat").text());
        tick();

        // 3. queue over budget
        client.queueDepthThrow = null;
        client.queueDepthResult = 99;
        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_QUEUE_BUSY_NO_ETA),
                handler.handle(new ScopeRef.Dm("alice"), "/image a cat").text());
        client.queueDepthResult = 0;
        tick();

        // 4. credit exhausted (zero per-user credits)
        gate.rateCapBucket = rateCapBucket = new RateCapBucket(clock,
                RateCapBucket.Settings.defaults()
                        .withImageUserCreditBucket(0, Duration.ofHours(1))
                        .withImageGroupCreditBucket(2, Duration.ofHours(1))
                        .withImageCooldownWindow(Duration.ofMillis(1)));
        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_CREDITS_EXHAUSTED),
                handler.handle(new ScopeRef.Dm("alice"), "/image a cat").text());
        tick();

        // 5. cooldown not elapsed (a full-hour window already drawn)
        freshFastBucket();
        rateCapBucket.tryAcquireImageCooldown(userId);
        long retryAfter = rateCapBucket.imageCooldownRetryAfterSeconds(userId);
        assertEquals(java.text.MessageFormat.format(
                        bundleLoader.get(BundleKeys.IMAGE_ERROR_COOLDOWN), Long.toString(retryAfter)),
                handler.handle(new ScopeRef.Dm("alice"), "/image a cat").text());
        tick();

        // 6. timeout (after cancelling the job) — a 1-credit bucket so the
        // refund assertion discriminates (drained-then-refunded token).
        gate.rateCapBucket = rateCapBucket = new RateCapBucket(clock,
                RateCapBucket.Settings.defaults()
                        .withImageUserCreditBucket(1, Duration.ofHours(1))
                        .withImageGroupCreditBucket(1, Duration.ofHours(1))
                        .withImageCooldownWindow(Duration.ofMillis(1)));
        client.generateThrow = new ComfyUIClient.JobTimeoutException("timed out", false);
        assertNull(handler.handle(new ScopeRef.Dm("alice"), "/image a cat"));
        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_TIMEOUT), notifier.completedText());
        assertTrue(rateCapBucket.tryAcquireImageUserCredit(userId),
                "timeout BEFORE start refunds the charged attempt (D76)");
        client.generateThrow = null;
        tick();

        // 7. adapter cannot carry attachments
        freshFastBucket();
        adapter.supportsAttachments = false;
        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_NO_ATTACHMENT_SUPPORT),
                handler.handle(new ScopeRef.Dm("alice"), "/image a cat").text());
        adapter.supportsAttachments = true;
        tick();

        // 8. attachment exceeds the platform limit
        freshFastBucket();
        adapter.maxAttachmentBytes = 10;
        client.generateResult = validPng();
        assertNull(handler.handle(new ScopeRef.Dm("alice"), "/image a cat"));
        assertEquals(bundleLoader.get(BundleKeys.IMAGE_ERROR_ATTACHMENT_OVER_LIMIT),
                notifier.completedText());
    }

    /** Swap in a 1ms-cooldown, 5-credit bucket wired into the gate. */
    private void freshFastBucket() {
        gate.rateCapBucket = rateCapBucket = new RateCapBucket(clock,
                RateCapBucket.Settings.defaults()
                        .withImageUserCreditBucket(5, Duration.ofHours(1))
                        .withImageGroupCreditBucket(5, Duration.ofHours(1))
                        .withImageCooldownWindow(Duration.ofMillis(1)));
    }

    /** Advance the fixed clock past the 1ms cooldown. */
    private void tick() {
        clock.now = clock.now.plusMillis(2);
    }

    // --- stubs ---------------------------------------------------------------

    private static final class SettableClock extends Clock {
        Instant now = FIXED_NOW;

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

    private static AdapterRegistry registryOver(MessagingAdapter adapter) {
        return new AdapterRegistry() {
            @Override
            public List<MessagingAdapter> activatedAdapters() {
                return List.of(adapter);
            }
        };
    }

    /** Reads the SHIPPED ceiling from the main application.properties via
     * the filesystem (PngMetadataStripTest precedent — test-resources
     * shadow the classpath name and carry no max-output-pixels key). */
    private static long readMaxOutputPixelsFromMainProperties() throws IOException {
        Path current = Path.of(System.getProperty("user.dir"));
        for (int i = 0; i < 10; i++) {
            Path candidate = current.resolve(
                    "infochat-provider/src/main/resources/application.properties");
            if (Files.isRegularFile(candidate)) {
                Properties props = new Properties();
                try (InputStream stream = Files.newInputStream(candidate)) {
                    props.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
                }
                String value = props.getProperty("infochat.image.max-output-pixels");
                if (value == null) {
                    throw new AssertionError(
                            "main application.properties carries no max-output-pixels key");
                }
                return Long.parseLong(value);
            }
            current = current.getParent();
            if (current == null) {
                break;
            }
        }
        throw new AssertionError("main application.properties not found walking up from user.dir");
    }

    /** A minimal valid PNG (1×1) that survives {@code PngMetadataStrip}. */
    private static byte[] validPng() {
        ByteBuffer buffer = ByteBuffer.allocate(45);
        buffer.put(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        buffer.putInt(13);                       // IHDR data length
        buffer.put("IHDR".getBytes());
        buffer.putInt(1);                        // width
        buffer.putInt(1);                        // height
        buffer.put(new byte[]{8, 2, 0, 0, 0});   // bit depth, color type, compression, filter, interlace
        buffer.putInt(0);                        // IHDR CRC (not validated by the strip)
        buffer.putInt(0);                        // IEND data length
        buffer.put("IEND".getBytes());
        buffer.putInt(0);                        // IEND CRC
        return buffer.array();
    }

    private record RecordedCall(String method, String path, String body) {}

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

    /** The wizard's template shape (prod/scripts/4b-image.sh write_template):
     * placeholder slot, numeric latent budget, KSampler seed, lanczos fit. */
    private static final String WORKFLOW_TEMPLATE = """
            {
              "4": {"class_type": "CLIPTextEncode", "inputs": {"text": "INFOCHAT_PROMPT_PLACEHOLDER"}},
              "6": {"class_type": "EmptyFlux2LatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
              "7": {"class_type": "KSampler", "inputs": {"seed": 0, "latent_image": ["6", 0]}},
              "9": {"class_type": "ImageScaleToTotalPixels", "inputs": {"image": ["8", 0],
                    "upscale_method": "lanczos", "megapixels": 1.0, "resolution_steps": 1}},
              "10": {"class_type": "SaveImage", "inputs": {"images": ["9", 0], "filename_prefix": "infochat"}}
            }
            """;

    private Path writeWorkflowTemplate() throws IOException {
        Path file = tempDir.resolve("workflow-stop-test.json");
        Files.writeString(file, WORKFLOW_TEMPLATE);
        return file;
    }

    /** Cross-package seam for the /help half of the config gate. */
    private static final class HelpCommandHandlerProbe {
        private final app.zcat.infochat.provider.messaging.HelpCommandHandler help =
                new app.zcat.infochat.provider.messaging.HelpCommandHandler();
        private final BundleLoader loader;

        HelpCommandHandlerProbe(BundleLoader loader) throws Exception {
            this.loader = loader;
            Field bundleField = help.getClass().getDeclaredField("bundleLoader");
            bundleField.setAccessible(true);
            bundleField.set(help, loader);
        }

        app.zcat.infochat.provider.messaging.HelpCommandHandler.CommandHelp imageEntry() {
            return app.zcat.infochat.provider.messaging.HelpCommandHandler.CATALOGUE.stream()
                    .filter(entry -> entry.command().equals("image"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the /image CATALOGUE entry must exist (P11)"));
        }

        boolean visible(app.zcat.infochat.provider.messaging.HelpCommandHandler.CommandHelp entry,
                        Optional<String> baseUrl) throws Exception {
            Field field = help.getClass().getDeclaredField("imageBaseUrl");
            field.setAccessible(true);
            field.set(help, baseUrl);
            app.zcat.infochat.provider.messaging.HelpCommandHandler.CallerTier plainUser =
                    new app.zcat.infochat.provider.messaging.HelpCommandHandler.CallerTier(
                            false, false, false, false);
            return help.visible(entry, plainUser);
        }
    }

    private static final class StubComfyUIClient extends ComfyUIClient {
        volatile int queueDepthResult = 0;
        volatile Exception queueDepthThrow = null;
        volatile byte[] generateResult = null;
        volatile Exception generateThrow = null;
        volatile String lastPrompt = null;
        volatile long lastTargetWidth = -1;
        volatile long lastTargetHeight = -1;
        final AtomicInteger generateCalls = new AtomicInteger();
        final AtomicInteger queueDepthCalls = new AtomicInteger();

        StubComfyUIClient() {
            super(Optional.empty(), (Path) null, Duration.ofSeconds(5), Duration.ofSeconds(30),
                    Duration.ofMinutes(3), Duration.ofMillis(500), 16 * 1024 * 1024,
                    Clock.systemUTC());
        }

        @Override
        public int queueDepth() throws java.io.IOException, InterruptedException {
            queueDepthCalls.incrementAndGet();
            if (queueDepthThrow != null) {
                throwDeclared(queueDepthThrow);
            }
            return queueDepthResult;
        }

        @Override
        public byte[] generate(String prompt) throws java.io.IOException, InterruptedException {
            generateCalls.incrementAndGet();
            lastPrompt = prompt;
            if (generateThrow != null) {
                throwDeclared(generateThrow);
            }
            return generateResult;
        }

        @Override
        public byte[] generate(String prompt, long targetWidth, long targetHeight)
                throws java.io.IOException, InterruptedException {
            lastTargetWidth = targetWidth;
            lastTargetHeight = targetHeight;
            return generate(prompt);
        }

        private static void throwDeclared(Exception e)
                throws java.io.IOException, InterruptedException {
            if (e instanceof java.io.IOException ioException) {
                throw ioException;
            }
            throw (InterruptedException) e;
        }
    }

    /** Records the generate() input and delegates — pins the P2 stripped-
     * bytes ordering at the wiring seam. */
    private static final class RecordingPreviewGenerator extends ImagePreviewGenerator {
        final AtomicInteger calls = new AtomicInteger();
        volatile byte[] lastInput;

        RecordingPreviewGenerator(long previewMaxPixels, int previewMaxChars) {
            super(previewMaxPixels, previewMaxChars);
        }

        @Override
        public String generate(byte[] strippedPng, long maxOutputPixels) {
            calls.incrementAndGet();
            lastInput = strippedPng;
            return super.generate(strippedPng, maxOutputPixels);
        }
    }

    private static final class StubTranslator extends QueryAnchorTranslator {
        volatile String translation = "translated";
        volatile String lastQuery = null;
        volatile String lastSourceLanguage = null;

        StubTranslator() {
            super(null, null, null, 500);
        }

        @Override
        public String translate(String query, String sourceLanguage, String scopeKind, UUID scopeId) {
            lastQuery = query;
            lastSourceLanguage = sourceLanguage;
            return translation == null ? query : translation;
        }
    }

    private static final class RecordingImageAuditWriter extends AuditLogWriter {
        private final List<RedactionHook.AuditRow> rows = new CopyOnWriteArrayList<>();

        RecordingImageAuditWriter() {
            super(row -> row);
        }

        @Override
        public void write(Connection conn, RedactionHook.AuditRow row) {
            rows.add(row);
        }

        List<AuditAction> actions() {
            return rows.stream().map(RedactionHook.AuditRow::action).toList();
        }

        List<RedactionHook.AuditRow> rowsFor(AuditAction action) {
            return rows.stream().filter(row -> row.action() == action).toList();
        }
    }

    private static final class RecordingImageNotifier extends StageProgressNotifier {
        private final List<ProgressStage> published = new ArrayList<>();
        private final List<String> stageTexts = new ArrayList<>();
        private String completedText;
        private int failCount;

        @Override
        public void publish(ScopeRef scope, ProgressStage stage) {
            published.add(stage);
        }

        @Override
        public void publishStageText(ScopeRef scope, String text) {
            stageTexts.add(text);
        }

        @Override
        public void complete(ScopeRef scope, String finalText) {
            this.completedText = finalText;
        }

        @Override
        public void fail(ScopeRef scope) {
            this.failCount++;
        }

        List<ProgressStage> publishedStages() {
            return List.copyOf(published);
        }

        List<String> stageTexts() {
            return List.copyOf(stageTexts);
        }

        String completedText() {
            return completedText;
        }
    }

    private static final class StubImageAdapter implements MessagingAdapter {
        volatile boolean supportsAttachments;
        volatile int maxAttachmentBytes;
        volatile FailureCategory failAttachmentsWith = null;
        volatile OutboundAttachment lastAttachment;
        final AtomicInteger attachmentSends = new AtomicInteger();

        StubImageAdapter(boolean supportsAttachments, int maxAttachmentBytes) {
            this.supportsAttachments = supportsAttachments;
            this.maxAttachmentBytes = maxAttachmentBytes;
        }

        @Override
        public String name() {
            return "inmemory";
        }

        @Override
        public CapabilityFlags capabilities() {
            return new CapabilityFlags(false, false, false, false, 65536, 1,
                    false, false, Duration.ZERO, supportsAttachments, maxAttachmentBytes);
        }

        @Override
        public boolean isWellFormedContactId(String contactId) {
            return true;
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            throw new UnsupportedOperationException("not exercised by image tests");
        }

        @Override
        public void sendAttachment(OutboundAttachment attachment) throws MessagingException {
            if (failAttachmentsWith != null) {
                throw new MessagingException(failAttachmentsWith, "simulated attachment failure");
            }
            lastAttachment = attachment;
            attachmentSends.incrementAndGet();
        }

        @Override
        public void update(MessageHandle handle, String body) {
            throw new UnsupportedOperationException("not exercised by image tests");
        }

        @Override
        public void finalizeMessage(MessageHandle handle, String body) {
            throw new UnsupportedOperationException("not exercised by image tests");
        }

        @Override
        public void setTyping(ScopeRef scope, boolean typing) {
            // no-op
        }

        @Override
        public AdapterTrustLevel trustLevel() {
            return AdapterTrustLevel.LOW;
        }

        @Override
        public void setInboundHandler(InboundHandler handler) {
            // no-op
        }
    }

    /** JDBC stub: user/group id lookups plus the audit transaction verbs. */
    private static final class ImageStubDataSource extends UnsupportedDataSource {
        private final UUID userId;
        private final UUID groupId;

        ImageStubDataSource(UUID userId, UUID groupId) {
            this.userId = userId;
            this.groupId = groupId;
        }

        @Override
        public Connection getConnection() {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> newPreparedStatement((String) args[0]);
                        case "setAutoCommit", "commit", "rollback", "close" -> null;
                        case "toString" -> "ImageStubConnection";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "Connection." + method.getName() + " not stubbed");
                    });
        }

        private PreparedStatement newPreparedStatement(String sql) {
            boolean isGroupQuery = sql.contains("FROM groups");
            UUID resultId = isGroupQuery ? groupId : userId;
            return (PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setString", "setObject", "close" -> null;
                        case "executeQuery" -> newIdResultSet(resultId);
                        case "toString" -> "ImageStubPreparedStatement";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "PreparedStatement." + method.getName() + " not stubbed");
                    });
        }

        private ResultSet newIdResultSet(UUID id) {
            boolean[] consumed = {false};
            return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            if (consumed[0]) {
                                yield false;
                            }
                            consumed[0] = true;
                            yield true;
                        }
                        case "getObject" -> id;
                        case "close" -> null;
                        case "toString" -> "ImageStubResultSet";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "ResultSet." + method.getName() + " not stubbed");
                    });
        }
    }
}
