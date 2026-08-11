package app.zcat.infochat.collector.testsupport;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression ratchet (M1-602) against scan-window fixture time-bombs: a
 * collector test that seeds an absolute instant ({@code Instant.parse("20NN-...")})
 * feeding a {@code fetched_at >= now - 32d}-style pickup gate WITHOUT pinning
 * the injected {@code java.time.Clock} stops matching that gate 32 days after
 * the seeded date — the test then fails, or worse, a negative assertion goes
 * silently vacuous, on a calendar boundary with no code change
 * (engineering-rules §9; the M1-398/M1-400/M1-444/M1-601 whack-a-mole history,
 * closed out by the M1-602 sweep).
 *
 * <p>Detection is file-granular: a {@code src/test/java} source containing an
 * absolute {@code Instant.parse("20NN-} with NO pin marker in the same file
 * ({@code Clock.fixed(}, or {@code installMockForType} together with
 * {@code Clock.class}) enters the found-set. The found-set must be a SUBSET of
 * the in-source benign baseline below — every entry individually verified
 * benign by the M1-602 census
 * (the fixture review: parser inputs, expected values, exact-key direct-call
 * paths, floor-less scans, explicit {@code now}
 * arguments, or deliberately-below-floor seeds with loud fixture guards). A
 * NEW unpinned absolute-instant fixture is not in the baseline and fails the
 * build; pinning or deleting a baseline file only shrinks the found-set, which
 * the subset check tolerates with no guard edit.
 *
 * <p>The baseline lives in-source (not a classpath resource like the M1-495
 * naming guard's): the benign set is census-derived and should be reviewed in
 * the same diff that grows it, alongside the census row that justifies the
 * addition.
 *
 * <p>Known limitations (documented in the census): (1) file granularity — a
 * file that pins in one method but hits a windowed query unpinned in another
 * (the pre-sweep {@code ReadyPromoterIT} shape) is invisible here; (2) absolute
 * timestamps seeded as SQL string literals ({@code '20NN-...'}) evade the
 * {@code Instant.parse} pattern (census "Detection-gap addendum" — all three
 * current instances verified benign). The guard is a strong lower bound, not a
 * proof.
 *
 * <p>This is a plain unit test (no {@code @QuarkusTest}, no {@code DataSource})
 * so it runs in the surefire phase and fails the build early. It locates the
 * multi-module root by walking up from the working directory (the module
 * basedir under surefire) — the M1-495 idiom — so IDE runs work too. Its own
 * source never self-matches: the detection pattern's escaped source literal
 * puts a backslash between {@code Instant} and {@code .parse}, and the pin
 * markers it names would exempt it anyway.
 */
class ScanWindowFixtureGuardTest {

    /** An absolute 20NN instant literal — the time-bomb seed marker. */
    private static final Pattern ABSOLUTE_INSTANT_PARSE =
            Pattern.compile("Instant\\.parse\\(\"20\\d\\d-");

    /**
     * The 53 unpinned collector test sources the M1-602 census verified benign
     * (§(B)). Add an entry ONLY for a genuinely benign absolute instant — one
     * that never feeds an unpinned
     * now-derived gate — and record the justification as a census row in the
     * same commit. If the seed gates worker pickup, pin the Clock instead.
     */
    private static final Set<String> BENIGN_BASELINE = Set.of(
            "app.zcat.infochat.collector.assets.AssetSnapshotFetcherSupportGateTest",
            "app.zcat.infochat.collector.assets.AssetSnapshotFetcherTest",
            "app.zcat.infochat.collector.assets.store.PriceSnapshotStoreTest",
            "app.zcat.infochat.collector.eval.embedding.EmbeddingWorkerBackoffTest",
            "app.zcat.infochat.collector.eval.embedding.EmbeddingWorkerNonFiniteTest",
            "app.zcat.infochat.collector.eval.embedding.EmbeddingWorkerPgvectorRejectionTest",
            "app.zcat.infochat.collector.eval.embedding.EmbeddingWorkerPickupFloorIT",
            "app.zcat.infochat.collector.eval.entity.EntityExtractorWorkerBackoffTest",
            "app.zcat.infochat.collector.eval.entity.EntityExtractorWorkerTest",
            "app.zcat.infochat.collector.eval.reeval.AdminReviewTtlJobTest",
            "app.zcat.infochat.collector.eval.reeval.FirstPassStage2RowBenignCloseIT",
            "app.zcat.infochat.collector.eval.reeval.QuarantineAuditBeforeEffectIT",
            "app.zcat.infochat.collector.eval.reeval.ReEvaluationBenignAuditScopeIT",
            "app.zcat.infochat.collector.eval.reeval.ReEvaluationJobTest",
            "app.zcat.infochat.collector.eval.reeval.ReEvaluationJobWindowTest",
            "app.zcat.infochat.collector.eval.stage1.QuarantinePendingNotifyIT",
            "app.zcat.infochat.collector.eval.stage1.Stage1MatchOverflowIT",
            "app.zcat.infochat.collector.eval.stage1.Stage1OrphanRescueIT",
            "app.zcat.infochat.collector.eval.stage1.Stage1PipelineIT",
            "app.zcat.infochat.collector.eval.stage1.Stage1WatchdogIT",
            "app.zcat.infochat.collector.eval.stage1.Stage1WorkerBoundaryIT",
            "app.zcat.infochat.collector.eval.stage1.Stage1WorkerEmitterThreadIT",
            "app.zcat.infochat.collector.eval.stage1.Stage1WorkerStaleRawReEmitterIT",
            "app.zcat.infochat.collector.eval.stage2.Stage2BenignNotifyScopeIT",
            "app.zcat.infochat.collector.eval.stage2.Stage2FirstPassQuarantineRowIT",
            "app.zcat.infochat.collector.eval.stage2.Stage2VerdictPersistenceIT",
            "app.zcat.infochat.collector.eval.stage2.Stage2WorkerIT",
            "app.zcat.infochat.collector.eval.tagger.TaggerWorkerBackoffTest",
            "app.zcat.infochat.collector.eval.tagger.TaggerWorkerTest",
            "app.zcat.infochat.collector.fetch.FetchSchedulerPersistFailureIT",
            "app.zcat.infochat.collector.fetcher.bluesky.BlueskyFetcherTest",
            "app.zcat.infochat.collector.fetcher.bluesky.BlueskyResponseParserTest",
            "app.zcat.infochat.collector.fetcher.reddit.RedditResponseParserItemCapTest",
            "app.zcat.infochat.collector.fetcher.reddit.RedditResponseParserNameValidationTest",
            "app.zcat.infochat.collector.fetcher.reddit.RedditResponseParserPermalinkTest",
            "app.zcat.infochat.collector.fetcher.reddit.RedditResponseParserSocialSignalTest",
            "app.zcat.infochat.collector.fetcher.rss.RssFeedParserTest",
            "app.zcat.infochat.collector.linking.LinkingJobBehaviorIT",
            "app.zcat.infochat.collector.linking.LinkingJobIT",
            "app.zcat.infochat.collector.linking.LinkingJobSemanticProbeIT",
            "app.zcat.infochat.collector.notify.ApproveQuarantinePhantomNotifyIT",
            "app.zcat.infochat.collector.notify.QuarantineProcedureNotifyIT",
            "app.zcat.infochat.collector.outbox.OutboxRehydratorIT",
            "app.zcat.infochat.collector.outbox.PostPersisterIT",
            "app.zcat.infochat.collector.outbox.PostPersisterNormalizationIT",
            "app.zcat.infochat.collector.partition.PartitionCreatorTest",
            "app.zcat.infochat.collector.partition.PartitionInsertIT",
            "app.zcat.infochat.collector.stream.StreamSourceStopDrainIT",
            "app.zcat.infochat.collector.stream.nostr.Kind6HandlerIT",
            "app.zcat.infochat.collector.stream.nostr.Kind6LinkingIT",
            "app.zcat.infochat.collector.stream.nostr.Kind6RepostResolutionIT",
            "app.zcat.infochat.collector.stream.nostr.NostrEventTest",
            "app.zcat.infochat.collector.stream.nostr.NostrSinceCursorIT",
            "app.zcat.infochat.collector.stream.nostr.RelayHealthTrackerTest");

    @Test
    void noUnpinnedAbsoluteInstantFixtureOutsideBenignBaseline() throws IOException {
        Set<String> found = scanUnpinnedAbsoluteInstantSources(
                locateRepoRoot().resolve("infochat-collector/src/test/java"));

        Set<String> unexpected = new TreeSet<>(found);
        unexpected.removeAll(BENIGN_BASELINE);

        assertTrue(unexpected.isEmpty(),
                "These collector test sources seed an absolute Instant.parse(\"20NN-...\") "
                        + "without pinning the injected Clock, so any fetched_at-style pickup "
                        + "gate they feed silently detaches from the seed 32 days after its "
                        + "date (engineering-rules §9). Pin the Clock in @BeforeEach via "
                        + "QuarkusMock.installMockForType(Clock.fixed(<seed>.plus(1h), "
                        + "ZoneOffset.UTC), Clock.class) (the M1-444/M1-601 pattern), or seed "
                        + "the instant relative to a pinned clock. Only if the instant is "
                        + "genuinely benign (parser input, expected value, exact-key "
                        + "direct-call path, explicit now argument, guarded below-floor seed) "
                        + "add the class to BENIGN_BASELINE here AND record why. Offenders: "
                        + unexpected);
    }

    private static Set<String> scanUnpinnedAbsoluteInstantSources(Path testRoot)
            throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(testRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> fileName(p).endsWith(".java"))
                    .filter(ScanWindowFixtureGuardTest::isUnpinnedAbsoluteInstantSource)
                    .map(ScanWindowFixtureGuardTest::fullyQualifiedName)
                    .forEach(out::add);
        }
        return out;
    }

    private static boolean isUnpinnedAbsoluteInstantSource(Path javaFile) {
        String source = read(javaFile);
        if (!ABSOLUTE_INSTANT_PARSE.matcher(source).find()) {
            return false;
        }
        boolean pinned = source.contains("Clock.fixed(")
                || (source.contains("installMockForType") && source.contains("Clock.class"));
        return !pinned;
    }

    private static String fullyQualifiedName(Path javaFile) {
        String className = fileName(javaFile).substring(0,
                fileName(javaFile).length() - ".java".length());
        for (String line : read(javaFile).split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                return trimmed.substring("package ".length(), trimmed.length() - 1).strip()
                        + "." + className;
            }
        }
        throw new IllegalStateException("no package declaration in " + javaFile);
    }

    /**
     * Walk up from the working directory (the module basedir under surefire)
     * until a directory holds both {@code infochat-collector} and
     * {@code infochat-provider} — the multi-module checkout root (the M1-495
     * idiom, so IDE runs resolve the same root).
     */
    private static Path locateRepoRoot() {
        for (@Nullable Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("infochat-collector"))
                    && Files.isDirectory(dir.resolve("infochat-provider"))) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "could not locate the multi-module repo root (a directory containing "
                        + "infochat-collector and infochat-provider) walking up from "
                        + Path.of("").toAbsolutePath());
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            throw new IllegalStateException("path has no file name: " + path);
        }
        return name.toString();
    }

    private static String read(Path javaFile) {
        try {
            return Files.readString(javaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading " + javaFile, e);
        }
    }
}
