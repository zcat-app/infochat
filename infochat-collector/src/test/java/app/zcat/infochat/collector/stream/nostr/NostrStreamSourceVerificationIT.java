package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.collector.outbox.TestEvalQueueConsumer;
import app.zcat.infochat.collector.stream.StreamSourceSupervisor;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.LoopbackPermittingBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the M1-097 trust-boundary gate: BIP-340 signature
 * verification + kind allowlist applied to every event delivered by a
 * {@link NostrStreamSource}. Each test seeds its own {@code source} row,
 * registers a worker against an in-process {@link FakeNostrRelay}, pushes
 * the relevant event shapes, and asserts on the persisted {@code post}
 * rows plus the failed-sig counter exposed by
 * {@link NostrStreamSource#failedSigCount()}.
 *
 * <p>End-to-end happy path lives in {@link NostrStreamSourceIT}; this
 * file isolates the verification + kind gates that M1-097 adds.</p>
 */
@QuarkusTest
class NostrStreamSourceVerificationIT {

    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    StreamSourceSupervisor supervisor;

    @Inject
    PostPersister postPersister;

    @Inject
    EvalQueueProducer evalQueueProducer;

    @Inject
    TestEvalQueueConsumer consumer;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    // Loopback-permitting SSRF guard so FakeNostrRelays (127.0.0.1) remain
    // dialable. Production Registrar wires a default-strict instance.
    private final SsrfGuardedHttpClient ssrfClient = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            10L * 1024 * 1024,
            3);
    private final NostrEventVerifier verifier = new NostrEventVerifier();
    private final NostrDedupFilter dedupFilter = new NostrDedupFilter();
    private final Set<Long> registeredDispatchKeys = new HashSet<>();
    private final List<FakeNostrRelay> relays = new ArrayList<>();

    @BeforeEach
    void setup() {
        consumer.drain();
    }

    @AfterEach
    void teardown() {
        for (Long key : registeredDispatchKeys) {
            supervisor.stop(key);
        }
        registeredDispatchKeys.clear();
        for (FakeNostrRelay relay : relays) {
            relay.close();
        }
        relays.clear();
    }

    @Test
    void unverifiedEventsDropped() throws Exception {
        FakeNostrRelay relay = newRelay();
        UUID sourceUuid = seedNostrSource("Nostr verify IT — bad sig");
        long preCount = countPostsForSource(sourceUuid);

        NostrStreamSource worker = registerWorker(990097L, relay, sourceUuid);
        assertTrue(relay.awaitFrameCount(1, FIVE_SECONDS), "relay received the subscriber's REQ");

        // Three events with well-formed-hex but cryptographically invalid sigs
        // — mimicking what a hostile relay would fabricate.
        NostrEvent forgedA = withBadSig(NostrSignedEventFixtures.VALID_KIND_1_EVENT, 0);
        NostrEvent forgedB = withBadSig(NostrSignedEventFixtures.VALID_KIND_1_DRAIN_A_EVENT, 1);
        NostrEvent forgedC = withBadSig(NostrSignedEventFixtures.VALID_KIND_1_DRAIN_B_EVENT, 2);
        relay.sendEvent(forgedA);
        relay.sendEvent(forgedB);
        relay.sendEvent(forgedC);

        awaitCondition(() -> worker.failedSigCount() >= 3, FIVE_SECONDS);
        assertEquals(3L, worker.failedSigCount(),
                "every forged event increments the failed-sig counter");
        assertEquals(preCount, countPostsForSource(sourceUuid),
                "no post row is written for any forged event");
    }

    @Test
    void disallowedKindDropped() throws Exception {
        FakeNostrRelay relay = newRelay();
        UUID sourceUuid = seedNostrSource("Nostr verify IT — disallowed kind");
        long preCount = countPostsForSource(sourceUuid);

        NostrStreamSource worker = registerWorker(990098L, relay, sourceUuid);
        assertTrue(relay.awaitFrameCount(1, FIVE_SECONDS), "relay received the subscriber's REQ");

        // Kind-7 (reaction): valid BIP-340 signature, disallowed kind. The
        // verifier accepts; the kind filter drops before the outbox write.
        // Send a valid kind-1 immediately after to provide a synchronization
        // point — once the kind-1 post lands, the FIFO-ordered kind-7
        // preceding it has already been processed (and silently dropped).
        relay.sendEvent(NostrSignedEventFixtures.VALID_KIND_7_EVENT);
        relay.sendEvent(NostrSignedEventFixtures.VALID_KIND_1_EVENT);
        awaitPostCount(sourceUuid, preCount + 1);

        assertEquals(preCount + 1, countPostsForSource(sourceUuid),
                "only the kind-1 event produces a post row; the kind-7 was dropped at the kind gate");
        assertEquals(NostrSignedEventFixtures.KIND_1_ID, upstreamIdentifierFor(sourceUuid),
                "the surviving post is the kind-1 event");
        assertEquals(0L, worker.failedSigCount(),
                "kind drops are silent — they do not increment the failed-sig counter");
    }

    @Test
    void kind1AndKind6Accepted() throws Exception {
        FakeNostrRelay relay = newRelay();
        UUID sourceUuid = seedNostrSource("Nostr verify IT — kind 1 + 6");
        long preCount = countPostsForSource(sourceUuid);

        NostrStreamSource worker = registerWorker(990099L, relay, sourceUuid);
        assertTrue(relay.awaitFrameCount(1, FIVE_SECONDS), "relay received the subscriber's REQ");

        relay.sendEvent(NostrSignedEventFixtures.VALID_KIND_1_EVENT);
        relay.sendEvent(NostrSignedEventFixtures.VALID_KIND_6_EVENT);
        awaitPostCount(sourceUuid, preCount + 2);

        assertEquals(preCount + 2, countPostsForSource(sourceUuid),
                "both kind-1 and kind-6 events produce post rows");
        Set<String> upstreamIds = upstreamIdentifiersFor(sourceUuid);
        assertTrue(upstreamIds.contains(NostrSignedEventFixtures.KIND_1_ID),
                "kind-1 event persisted");
        assertTrue(upstreamIds.contains(NostrSignedEventFixtures.KIND_6_ID),
                "kind-6 event persisted");
        assertEquals(0L, worker.failedSigCount(),
                "valid signatures do not touch the failed-sig counter");
    }

    private FakeNostrRelay newRelay() {
        FakeNostrRelay relay = new FakeNostrRelay();
        relays.add(relay);
        return relay;
    }

    private NostrStreamSource registerWorker(long dispatchKey, FakeNostrRelay relay, UUID sourceUuid) {
        List<URI> relayUris = List.of(relay.uri());
        NostrStreamSource worker = new NostrStreamSource(relayUris,
                OptionalLong::empty, Duration.ofMillis(50), Duration.ofMillis(200),
                httpClient, ssrfClient, verifier, noOpTracker(relayUris), dedupFilter);
        String filterSpec = "{\"kinds\":[1,6,7]}";
        Consumer<NormalizedPost> deliver =
                post -> postPersister.persist(sourceUuid, post).ifPresent(evalQueueProducer::emit);
        supervisor.register(dispatchKey, filterSpec, worker, deliver);
        registeredDispatchKeys.add(dispatchKey);
        return worker;
    }

    /** See {@code NostrStreamSourceTest.noOpTracker} — same intent: satisfy the constructor only. */
    private static RelayHealthTracker noOpTracker(List<URI> relayUris) {
        return new RelayHealthTracker(relayUris, Integer.MAX_VALUE,
                Duration.ofHours(1), Integer.MAX_VALUE, Clock.systemUTC(), t -> { });
    }

    /**
     * Replace the sig with a well-formed-hex 64-byte all-zeros string. The
     * length and hex shape pass {@link NostrEventVerifier#verify}'s decode
     * step, so the failure is genuinely at the BIP-340 verification step.
     * The {@code seed} byte is varied across calls so the events are
     * distinguishable on the wire (otherwise FakeNostrRelay sends
     * identical frames, hiding any ordering bugs).
     */
    private static NostrEvent withBadSig(NostrEvent base, int seed) {
        char[] sigChars = new char[128];
        for (int i = 0; i < sigChars.length; i++) {
            sigChars[i] = '0';
        }
        sigChars[0] = "0123456789abcdef".charAt(seed & 0xF);
        return new NostrEvent(base.id(), base.pubkey(), base.createdAt(), base.kind(),
                base.tags(), base.content(), new String(sigChars));
    }

    private UUID seedNostrSource(String displayName) throws Exception {
        // (kind, identifier) is unique per schema, so include a per-call UUID
        // suffix on the filter-spec identifier — each test method inserts
        // exactly once, but two test methods in the same Quarkus container
        // would collide on a shared identifier.
        String identifier = "{\"kinds\":[1,6,7],\"#test\":\"" + UUID.randomUUID() + "\"}";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                             + "VALUES ('nostr', ?, ?, 'social', '{}') "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private long countPostsForSource(UUID sourceUuid) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM post WHERE source_id = ?")) {
            ps.setObject(1, sourceUuid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String upstreamIdentifierFor(UUID sourceUuid) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT upstream_identifier FROM post WHERE source_id = ?")) {
            ps.setObject(1, sourceUuid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private Set<String> upstreamIdentifiersFor(UUID sourceUuid) throws Exception {
        Set<String> ids = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT upstream_identifier FROM post WHERE source_id = ?")) {
            ps.setObject(1, sourceUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        }
        return ids;
    }

    private void awaitPostCount(UUID sourceUuid, long expected) throws Exception {
        long deadline = System.currentTimeMillis() + FIVE_SECONDS.toMillis();
        while (countPostsForSource(sourceUuid) < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    private static void awaitCondition(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
    }
}
