package app.zcat.infochat.collector.stream;

/**
 * Typed handle for a stream dispatch key minted by a {@link StreamSource}
 * registrar and held by {@link StreamSourceSupervisor}. Wrapping the bare
 * {@code long} in a distinct type is the whole point: the supervisor's
 * stream keyspace and the {@code FetchScheduler}'s polled-source keyspace
 * are both monotonic from 1, so a numerically-valid key from the wrong
 * keyspace passed to {@link StreamSourceSupervisor#stop} would silently
 * stop an unrelated stream. This type makes that mismatch a compile error
 * rather than a runtime collision (M1-371).
 *
 * <p>The wrapped {@code value} is unwrapped back to a bare {@code long}
 * only at the {@code StreamSource.start(long, ...)} SPI boundary
 * (infochat-core), where the key is stamped onto every delivered post and
 * persisted as a long. The handle is a Collector-side supervisor wrapper;
 * it never crosses into infochat-core.</p>
 */
public record StreamDispatchKey(long value) {
}
