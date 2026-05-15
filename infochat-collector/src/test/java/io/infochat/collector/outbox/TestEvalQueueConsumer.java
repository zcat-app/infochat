package io.infochat.collector.outbox;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only consumer that drains the {@code eval-queue} channel for
 * the duration of every {@code @QuarkusTest} in this module. T1-C
 * ships no production consumer — T1-D's eval workers attach the
 * real subscriber — so the test classpath supplies a stub consumer
 * that simply collects every emitted
 * {@link PostPersister.PersistedPostKey} into a thread-safe list.
 *
 * <p>Each IT injects this bean, drains the list via
 * {@link #drain()}, and asserts against the drained set. Tests that
 * run in sequence within the same Quarkus container should call
 * {@link #drain()} at the start of each test so prior-test
 * emissions do not leak.
 */
@ApplicationScoped
public class TestEvalQueueConsumer {

    private final List<PostPersister.PersistedPostKey> received = new CopyOnWriteArrayList<>();

    @Incoming("eval-queue")
    public void onPostKey(PostPersister.PersistedPostKey key) {
        received.add(key);
    }

    /**
     * Drain the collected emissions. Returns a stable snapshot of
     * everything received since the last drain (or since startup if
     * never drained); clears the internal buffer.
     */
    public List<PostPersister.PersistedPostKey> drain() {
        List<PostPersister.PersistedPostKey> snapshot = List.copyOf(received);
        received.clear();
        return snapshot;
    }

    /**
     * Peek at currently-received messages without draining. Useful
     * for Awaitility polls that wait for an expected count to arrive.
     */
    public int size() {
        return received.size();
    }
}
