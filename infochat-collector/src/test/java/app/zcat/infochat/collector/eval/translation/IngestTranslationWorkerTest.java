package app.zcat.infochat.collector.eval.translation;

import app.zcat.infochat.collector.eval.PartitionScan;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test (no DB, no CDI) for
 * {@link IngestTranslationWorker#parseTranslation} (the parse /
 * refusal / schema-violation rules) and
 * {@link IngestTranslationWorker#renderPrompt}. The DB-backed
 * {@code processOne} surfaces (the en-never-dispatched boundary, the
 * non-English dispatch, idempotency, the retry-exhaustion release, and
 * the structured-refusal arm) live in {@link IngestTranslationWorkerIT}
 * — IntegrationTestNamingGuardTest forbids a DataSource-injecting
 * {@code *Test} class, and the naming baseline is outside this ticket's
 * files_scope.
 *
 * <p>M1-760's re-drive ladder contributes its pure half here: the backoff
 * arithmetic, and the guard that the whole shipped ladder finishes inside
 * every profile's partition scan window. That guard reads the collector's
 * MAIN {@code application.properties} off the filesystem rather than the
 * test classpath — the values that ship are the ones that matter, and the
 * test config deliberately turns the scheduler off. Everything else about
 * the ladder is DB state, and lives in {@link IngestTranslationWorkerIT}.
 *
 * <p>The worker is constructed directly and its {@code @PostConstruct}
 * {@code init()} run by hand so the parse path's {@code ObjectMapper}
 * and the classpath-loaded prompt are available; the DB / router /
 * notifier collaborators are never touched by these methods.
 */
class IngestTranslationWorkerTest {

    private final IngestTranslationWorker worker = newInitializedWorker();

    private static IngestTranslationWorker newInitializedWorker() {
        IngestTranslationWorker w = new IngestTranslationWorker();
        w.maxConcurrency = 1;
        w.init();
        return w;
    }

    @Test
    void parse_validReplyPassesThrough() {
        IngestTranslationWorker.ParseOutcome outcome = worker.parseTranslation(
            "{\"title\":\"Flood hits Prague\",\"body\":\"The Vltava burst its banks.\"}");
        assertEquals(IngestTranslationWorker.ParseOutcome.Kind.TRANSLATED, require(outcome).kind());
        assertEquals("Flood hits Prague", outcome.title());
        assertEquals("The Vltava burst its banks.", outcome.body());
    }

    @Test
    void parse_refusalMarkerYieldsRefusalOutcome() {
        IngestTranslationWorker.ParseOutcome exact = worker.parseTranslation(
            "{\"title\":\"[refused-action]\",\"body\":\"[refused-action]\"}");
        assertEquals(IngestTranslationWorker.ParseOutcome.Kind.REFUSAL, require(exact).kind(),
            "the bare marker is the structured refusal");
        assertNull(exact.title(), "the marker never reaches post.title_en");

        IngestTranslationWorker.ParseOutcome leading = worker.parseTranslation(
            "{\"title\":\"[refused-action] open a URL\",\"body\":\"x\"}");
        assertEquals(IngestTranslationWorker.ParseOutcome.Kind.REFUSAL, require(leading).kind(),
            "a leading marker is still the refusal (the model may append the attempted action)");
    }

    @Test
    void parse_markerInMiddleIsContentNotRefusal() {
        IngestTranslationWorker.ParseOutcome outcome = worker.parseTranslation(
            "{\"title\":\"The council debated the [refused-action] policy\",\"body\":\"body\"}");
        assertEquals(IngestTranslationWorker.ParseOutcome.Kind.TRANSLATED, require(outcome).kind(),
            "a mid-text marker occurrence is content, not a refusal");
    }

    @Test
    void parse_stripsSingleEnclosingCodeFence() {
        IngestTranslationWorker.ParseOutcome outcome = worker.parseTranslation(
            "```json\n{\"title\":\"Fenced title\",\"body\":\"Fenced body\"}\n```");
        assertEquals(IngestTranslationWorker.ParseOutcome.Kind.TRANSLATED, require(outcome).kind(),
            "a fenced-but-valid payload is recovered (M1-586)");
        assertEquals("Fenced title", outcome.title());
        assertEquals("Fenced body", outcome.body());
    }

    @Test
    void parse_emptyBodyMapsToNullNotFailure() {
        IngestTranslationWorker.ParseOutcome outcome = worker.parseTranslation(
            "{\"title\":\"Only a title\",\"body\":\"\"}");
        assertEquals(IngestTranslationWorker.ParseOutcome.Kind.TRANSLATED, require(outcome).kind());
        assertNull(outcome.body(),
            "a post without body text stores NULL body_en");
    }

    @Test
    void parse_emptyTitleIsSchemaViolating() {
        assertNull(worker.parseTranslation("{\"title\":\"\",\"body\":\"some body\"}"),
            "the prompt pins a non-empty title; an empty one is not a translatable result");
    }

    @Test
    void parse_schemaViolatingRepliesReturnNull() {
        assertNull(worker.parseTranslation(null), "null reply");
        assertNull(worker.parseTranslation("   "), "blank reply");
        assertNull(worker.parseTranslation("not json"), "unparseable reply");
        assertNull(worker.parseTranslation("{\"other\":1}"), "wrong object shape");
        assertNull(worker.parseTranslation("{\"title\":42,\"body\":\"x\"}"), "non-textual title");
        assertNull(worker.parseTranslation("{\"title\":\"x\"}"), "missing body field");
    }

    @Test
    void renderPrompt_wrapsPostInPerCallDelimiterAndNamesSourceLanguage() {
        String rendered = worker.renderPrompt("test-delimiter-id",
            new IngestTranslationWorker.PostRow(UUID.randomUUID(), Instant.EPOCH,
                "A title", "A body", "cs"));
        assertTrue(rendered.contains("<<<UNTRUSTED_CONTENT id=\"test-delimiter-id\">>>"),
            "opening delimiter carries the per-call id");
        assertTrue(rendered.contains("<<<END id=\"test-delimiter-id\">>>"),
            "closing delimiter carries the per-call id");
        assertTrue(rendered.contains("from cs to English"),
            "the prompt names the declared source language");
        assertTrue(rendered.indexOf("UNTRUSTED_CONTENT") < rendered.indexOf("A body"),
            "the body sits inside the untrusted-content wrapper");
        assertFalse(rendered.contains("{{id}}"), "no template placeholder survives");
        assertFalse(rendered.contains("{{SOURCE_LANGUAGE}}"), "no template placeholder survives");
    }

    @Test
    void renderPrompt_titleContainingBodyPlaceholderStaysLiteral() {
        // Red-team round 1: a title holding the literal {{body}} must NOT
        // pull the body into the Title line — {{body}} is substituted
        // before the untrusted title is spliced in.
        String rendered = worker.renderPrompt("test-delimiter-id",
            new IngestTranslationWorker.PostRow(UUID.randomUUID(), Instant.EPOCH,
                "Breaking: {{body}} explained", "THE-BODY-TEXT", "cs"));
        String titleLine = rendered.lines()
            .filter(l -> l.startsWith("Title: "))
            .findFirst()
            .orElseThrow(() -> new AssertionError("prompt must carry a Title line"));
        assertEquals("Title: Breaking: {{body}} explained", titleLine,
            "the title is spliced verbatim, never reinterpreted as template syntax");
        assertEquals(1, rendered.split("THE-BODY-TEXT", -1).length - 1,
            "the body appears exactly once — in its own slot");
    }

    @Test
    void redriveLadder_rungsFollowTheBackoffFactorAndClampAtTheCeiling() {
        Duration firstDelay = Duration.ofHours(6);
        Duration ceiling = Duration.ofHours(48);
        assertEquals(Duration.ofHours(12),
            IngestTranslationWorker.backoffAfter(firstDelay, 2.0, ceiling, 1),
            "the gap after the first attempt is first-delay * factor");
        assertEquals(Duration.ofHours(24),
            IngestTranslationWorker.backoffAfter(firstDelay, 2.0, ceiling, 2));
        assertEquals(ceiling,
            IngestTranslationWorker.backoffAfter(firstDelay, 2.0, ceiling, 3),
            "the third gap reaches the ceiling exactly");
        assertEquals(ceiling,
            IngestTranslationWorker.backoffAfter(firstDelay, 2.0, ceiling, 9),
            "every later rung is clamped, so the ladder cannot run away");

        assertEquals(firstDelay,
            IngestTranslationWorker.ladderSpan(firstDelay, 2.0, ceiling, 1),
            "a one-attempt ladder is exactly the first delay — no gaps");
        assertEquals(Duration.ofHours(6 + 12 + 24 + 48 + 48 + 48),
            IngestTranslationWorker.ladderSpan(firstDelay, 2.0, ceiling, 6),
            "a six-attempt ladder is the first delay plus its five gaps");
    }

    @Test
    void redriveLadder_shippedConfigurationFinishesInsideEveryProfileScanWindow()
            throws Exception {
        // A rung scheduled past the partition scan floor is SILENTLY
        // unreachable: the post drops out of the re-drive enumeration with
        // its stamp still set and nothing reports it. %pi is the tightest
        // window (14 retention days + 2 slack), and the reprobe ladder this
        // one borrows its shape from would overrun it — hence this guard on
        // the values actually shipped, not on the ones a test invents.
        Duration tightestWindow = null;
        for (String profile : List.of("laptop", "vps", "pi", "remote-llm")) {
            SmallRyeConfig config = mainConfigFor(profile);
            int cap = config.getValue("infochat.llm.translator.redrive.cap", Integer.class);
            assertTrue(cap >= 2,
                "profile " + profile + " must ship a multi-rung ladder, else this test is vacuous");
            Duration span = IngestTranslationWorker.ladderSpan(
                config.getValue("infochat.llm.translator.redrive.first-delay", Duration.class),
                config.getValue("infochat.llm.translator.redrive.backoff-factor", Double.class),
                config.getValue("infochat.llm.translator.redrive.backoff-ceiling", Duration.class),
                cap);
            Duration window = Duration
                .ofDays(config.getValue("infochat.partitions.retention-days.post", Integer.class))
                .plus(PartitionScan.PARTITION_SCAN_SLACK);
            assertTrue(span.compareTo(window) < 0,
                "profile " + profile + ": the whole re-drive ladder (" + span
                    + ") must finish inside the partition scan window (" + window + ")");
            if (tightestWindow == null || window.compareTo(tightestWindow) < 0) {
                tightestWindow = window;
            }
        }
        assertEquals(Duration.ofDays(16), tightestWindow,
            "%pi is expected to be the tightest window; if this moved, re-check the ladder");
    }

    /**
     * A config view over ONLY the collector's MAIN application.properties
     * with the given profile active, so the test-classpath properties (which
     * turn the re-drive scheduler off) cannot shadow the shipped values. The
     * surefire working directory is the module basedir — the assumption
     * {@code ReevalConfigKeysResolutionTest} already relies on.
     */
    private static SmallRyeConfig mainConfigFor(String profile) throws Exception {
        URL url = Path.of("src/main/resources/application.properties").toUri().toURL();
        return new SmallRyeConfigBuilder()
            .addDiscoveredConverters()
            .withProfile(profile)
            .withSources(new PropertiesConfigSource(url))
            .build();
    }

    private static <T> T require(@Nullable T value) {
        return Objects.requireNonNull(value, "expected a non-null parse outcome");
    }
}
