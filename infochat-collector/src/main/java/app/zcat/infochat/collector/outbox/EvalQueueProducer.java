package app.zcat.infochat.collector.outbox;

import io.smallrye.reactive.messaging.annotations.Broadcast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

/**
 * Emits one post-key message to the in-memory SmallRye Reactive
 * Messaging channel named {@code eval-queue} per CLAUDE.md §Stack
 * ("SmallRye Reactive Messaging (in-memory channels v1, Kafka
 * optional later)") and decision D4.
 *
 * <p>The message payload is the {@link PostPersister.PersistedPostKey}
 * composite key — the eval worker subscribed to this channel in T1-D
 * needs both the row {@code id} and the partition key
 * {@code fetched_at} to SELECT the post row efficiently (post is
 * partitioned by {@code fetched_at} per V7 / {@code docs/design/02-schema.md}
 * §2.3.1). A bare UUID would force a partition-wide scan; the
 * composite key restricts the partition pruner to the right window.
 *
 * <p>This is the SOLE writer to {@code eval-queue} in the
 * {@code infochat-collector/outbox/} package; the
 * {@link OutboxRehydrator} re-uses this same producer rather than
 * declaring its own bespoke channel name (per
 * {@code docs/plan/m1/tickets/M1-028} §Definition of Done — "the
 * same producer the FetchScheduler's live path uses"). The T1-D
 * consumer side subscribes to the same channel.
 *
 * <p>The channel has no consumer in T1-C scope. SmallRye in-memory
 * channels tolerate a producer-without-consumer; the buffer fills
 * to the configured size and applies back-pressure to the producer
 * per {@code docs/design/01-architecture.md} §1.6. T1-D's eval
 * workers attach the consumer.
 *
 * <p>{@link Broadcast @Broadcast} allows the M1-032 production
 * {@code Stage1Worker} and the M1-028 {@code TestEvalQueueConsumer}
 * to subscribe to the same channel; SmallRye otherwise rejects
 * multiple subscribers with
 * {@code TooManyDownstreamCandidatesException}. M1-032 widened
 * {@code files_scope} to cover this annotation under the
 * "bare consumer wiring needed" carve-out from its {@code out_of_scope}.
 */
@ApplicationScoped
public class EvalQueueProducer {

    @Inject
    @Channel("eval-queue")
    @Broadcast
    Emitter<PostPersister.PersistedPostKey> emitter;

    /**
     * Emit one persisted-post key to {@code eval-queue}.
     *
     * @param key the {@link PostPersister.PersistedPostKey} returned
     *            by {@link PostPersister#persist}; the eval worker
     *            uses it to {@code SELECT} the post row.
     */
    public void emit(PostPersister.PersistedPostKey key) {
        emitter.send(key);
    }

    /**
     * Whether the {@code eval-queue} subscriber side currently signals
     * outstanding demand ({@link Emitter#hasRequests()}). {@code false}
     * until the {@code @Incoming("eval-queue")} subscription finishes
     * its asynchronous startup wiring — emitting before then fills
     * SmallRye's default 128-item buffer and the next {@code send}
     * throws SRMSG00034. {@link OutboxRehydrator} polls this before its
     * first emit so a startup-time RAW backlog cannot race the
     * subscriber wiring and crash boot (M1-551 / F-live-3).
     */
    public boolean hasDownstreamRequests() {
        return emitter.hasRequests();
    }
}
