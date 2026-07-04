package app.zcat.infochat.collector.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

/**
 * Unit-level proof of the M1-551 eval-queue readiness gate (F-live-3):
 * {@link OutboxRehydrator#rehydrate()} must gate EVERY emit into the
 * {@code eval-queue} channel on
 * {@link EvalQueueProducer#hasDownstreamRequests()} reporting subscriber
 * demand — including a consumer that stalls mid-drain after a healthy
 * start — and must never poll at all when the RAW backlog is empty.
 *
 * <p>The lost-race interleaving this gate closes (the rehydrator's
 * {@code @PostConstruct} emitting before {@code Stage1Worker}'s
 * {@code @Incoming} subscription is active) cannot be forced inside a
 * {@code @QuarkusTest} — the subscriber wires up before test methods
 * run — so the gate is proven here without CDI: the rehydrator's
 * package-private {@code @Inject} fields are assigned directly, with a
 * hand-rolled {@link EvalQueueProducer} double and a reflection-proxy
 * JDBC stub serving canned RAW chunks.
 */
class OutboxRehydratorReadinessTest {

    @Test
    void emitProceedsOnceReadinessFlipsTrueOnKthPoll() {
        List<PostPersister.PersistedPostKey> backlog = keys(3);
        StubEvalQueueProducer producer = StubEvalQueueProducer.readyFromPoll(7);
        OutboxRehydrator rehydrator = rehydratorOver(producer, backlog, 100);

        int processed = rehydrator.rehydrate();

        assertEquals(3, processed);
        assertEquals(backlog, producer.emitted);
        // Per-emit gate: the first emit waits out polls 1-6 and proceeds
        // on poll 7; each later emit re-checks demand with exactly one
        // positive poll (8, 9) — the healthy-path cost of gating every
        // emit instead of only the first.
        assertEquals(9, producer.readinessPolls);
    }

    @Test
    void midDrainStallIsWaitedOutBySameBoundedPoll() {
        List<PostPersister.PersistedPostKey> backlog = keys(2);
        // Ready for the first emit, then the consumer stalls (polls 2-3
        // report no demand) and recovers at poll 4. A first-emit-only
        // gate would emit into the stall and overflow the buffer — the
        // mid-drain SRMSG00034 evidence (2026-07-03/04) this per-emit
        // extension closes.
        StubEvalQueueProducer producer = new StubEvalQueueProducer(
            poll -> poll == 1 || poll >= 4);
        OutboxRehydrator rehydrator = rehydratorOver(producer, backlog, 100);

        assertEquals(2, rehydrator.rehydrate());

        assertEquals(backlog, producer.emitted);
        assertEquals(4, producer.readinessPolls);
    }

    @Test
    void attemptsExhaustedThrowsIseNamingBothConfigKeys() {
        StubEvalQueueProducer producer = StubEvalQueueProducer.neverReady();
        OutboxRehydrator rehydrator = rehydratorOver(producer, keys(1), 5);

        IllegalStateException failure =
            assertThrows(IllegalStateException.class, rehydrator::rehydrate);

        String message = failure.getMessage();
        assertTrue(message.contains("eval-queue"), message);
        assertTrue(
            message.contains(OutboxRehydrator.CONFIG_KEY_READINESS_MAX_ATTEMPTS),
            message);
        assertTrue(
            message.contains(OutboxRehydrator.CONFIG_KEY_READINESS_POLL_MILLIS),
            message);
        assertEquals(5, producer.readinessPolls);
        assertTrue(producer.emitted.isEmpty());
    }

    @Test
    void emptyRawBacklogPerformsZeroReadinessPolls() {
        StubEvalQueueProducer producer = StubEvalQueueProducer.readyFromPoll(1);
        OutboxRehydrator rehydrator = rehydratorOver(producer, List.of(), 100);

        assertEquals(0, rehydrator.rehydrate());

        assertEquals(0, producer.readinessPolls);
        assertTrue(producer.emitted.isEmpty());
    }

    /**
     * Deterministic backlog keys: {@code new UUID(0, i)} plus
     * epoch-offset timestamps keep assertions reproducible with no
     * ambient randomness.
     */
    private static List<PostPersister.PersistedPostKey> keys(int count) {
        List<PostPersister.PersistedPostKey> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(new PostPersister.PersistedPostKey(
                new UUID(0, i), Instant.EPOCH.plusSeconds(i)));
        }
        return keys;
    }

    /**
     * Field-injection seam: the rehydrator's package-private
     * {@code @Inject} / {@code @ConfigProperty} fields are assigned
     * directly, no CDI container. {@code readinessPollMillis} is zero
     * so exhaustion tests spend no wall-clock time sleeping.
     */
    private static OutboxRehydrator rehydratorOver(
            StubEvalQueueProducer producer,
            List<PostPersister.PersistedPostKey> backlog,
            int maxAttempts) {
        OutboxRehydrator rehydrator = new OutboxRehydrator();
        rehydrator.dataSource = dataSourceServing(backlog);
        rehydrator.evalQueueProducer = producer;
        rehydrator.rehydratePageSize = 500;
        rehydrator.readinessMaxAttempts = maxAttempts;
        rehydrator.readinessPollMillis = 0;
        return rehydrator;
    }

    /**
     * Hand-rolled {@link EvalQueueProducer} double: answers each
     * readiness poll from a 1-based poll-number predicate (so tests can
     * script mid-drain stalls, not just a single flip-to-ready point),
     * recording every poll and every emitted key. The overrides never
     * touch the inherited (uninjected) emitter field, so no CDI wiring
     * is needed.
     */
    private static final class StubEvalQueueProducer extends EvalQueueProducer {

        private final IntPredicate readyAtPoll;
        int readinessPolls;
        final List<PostPersister.PersistedPostKey> emitted = new ArrayList<>();

        StubEvalQueueProducer(IntPredicate readyAtPoll) {
            this.readyAtPoll = readyAtPoll;
        }

        static StubEvalQueueProducer readyFromPoll(int firstReadyPoll) {
            return new StubEvalQueueProducer(poll -> poll >= firstReadyPoll);
        }

        static StubEvalQueueProducer neverReady() {
            return new StubEvalQueueProducer(poll -> false);
        }

        @Override
        public boolean hasDownstreamRequests() {
            readinessPolls++;
            return readyAtPoll.test(readinessPolls);
        }

        @Override
        public void emit(PostPersister.PersistedPostKey key) {
            emitted.add(key);
        }
    }

    /**
     * Reflection-proxy JDBC stub: the first {@code executeQuery()}
     * serves the whole backlog as one forward-only {@code (id,
     * fetched_at)} result, later queries serve nothing — with the test
     * page size (500) above every backlog used here, the rehydrator's
     * keyset loop sees one residual-tail chunk exactly as it would
     * against a small real table. Only the methods the chunk scan
     * actually touches are implemented; anything else fails the test
     * loudly.
     */
    private static DataSource dataSourceServing(
            List<PostPersister.PersistedPostKey> backlog) {
        Iterator<List<PostPersister.PersistedPostKey>> pendingChunks =
            List.of(backlog).iterator();
        InvocationHandler statement = (proxy, method, args) ->
            switch (method.getName()) {
                case "executeQuery" -> resultSetOver(
                    pendingChunks.hasNext() ? pendingChunks.next() : List.of());
                case "setInt", "setTimestamp", "setObject", "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            };
        InvocationHandler connection = (proxy, method, args) ->
            switch (method.getName()) {
                case "prepareStatement" -> proxyOf(PreparedStatement.class, statement);
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            };
        InvocationHandler dataSource = (proxy, method, args) ->
            switch (method.getName()) {
                case "getConnection" -> proxyOf(Connection.class, connection);
                default -> throw new UnsupportedOperationException(method.getName());
            };
        return proxyOf(DataSource.class, dataSource);
    }

    private static ResultSet resultSetOver(
            List<PostPersister.PersistedPostKey> rows) {
        AtomicInteger cursor = new AtomicInteger(-1);
        InvocationHandler resultSet = (proxy, method, args) ->
            switch (method.getName()) {
                case "next" -> cursor.incrementAndGet() < rows.size();
                case "getObject" -> rows.get(cursor.get()).id();
                case "getTimestamp" -> Timestamp.from(rows.get(cursor.get()).fetchedAt());
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            };
        return proxyOf(ResultSet.class, resultSet);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxyOf(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
            OutboxRehydratorReadinessTest.class.getClassLoader(),
            new Class<?>[] {type}, handler);
    }
}
