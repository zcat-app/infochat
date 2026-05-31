package app.zcat.infochat.collector.stream.nostr;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One relay's subscription: opens a {@link WebSocket} to a single relay,
 * sends the NIP-01 REQ, and feeds every received {@link NostrEvent} to the
 * sink. A dedicated virtual thread owns the connect → subscribe → receive →
 * reconnect loop so a slow or dead relay never blocks {@link
 * NostrStreamSource#start} (which spawns one of these per configured relay
 * and returns immediately).
 *
 * <h2>Reconnect</h2>
 * <p>On disconnect the loop reconnects with exponential backoff plus jitter
 * (see {@link #backoffDelay}). The backoff counter resets only after the
 * subscription proves productive — the relay answered with an EOSE or an
 * EVENT — so a relay that accepts the socket and immediately drops it backs
 * off instead of hot-looping (acceptance: "no tight-loop reconnect storm").
 * Each (re)connect re-reads the {@code since} cursor so the relay replays
 * only events newer than the last persisted one.</p>
 *
 * <h2>Trust boundary</h2>
 * <p>A relay is untrusted. A malformed frame is logged and skipped, never
 * propagated, so one bad frame cannot tear the subscription down. Signature
 * verification is M1-097; SSRF guarding of the {@code wss://} target is
 * M1-101 — this connection dials with a plain {@link HttpClient}.</p>
 */
final class NostrRelayConnection {

    private static final Logger LOG = LoggerFactory.getLogger(NostrRelayConnection.class);

    // Bounds the WebSocket handshake. Not profile-driven (a relay handshake
    // does not get slower on Pi-class hardware), so a constant rather than a
    // config key.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    // Caps the pre-assembly buffer for fragmented text frames so a hostile
    // relay cannot OOM the Collector by streaming an infinitely-fragmented
    // frame without ever signalling last=true. NIP-01 events are typically
    // a few KB; 1 MiB is generous for legitimate traffic. Frames exceeding
    // the cap are dropped (buffer reset, remaining fragments skipped until
    // last=true) and the connection stays open.
    static final int MAX_FRAME_BYTES = 1_048_576;

    private final URI relayUri;
    private final String subscriptionId;
    private final String filterSpec;
    private final Supplier<OptionalLong> sinceCursor;
    private final Consumer<NostrEvent> eventSink;
    private final Duration backoffBase;
    private final Duration backoffMax;
    private final HttpClient httpClient;
    private final RelayHealthTracker healthTracker;
    private final Random random = new Random();

    private volatile boolean running = true;
    private volatile Thread loopThread;
    private volatile WebSocket currentWebSocket;
    private volatile CountDownLatch closeSignal;
    private volatile boolean productiveSinceConnect;

    NostrRelayConnection(@NonNull URI relayUri, @NonNull String filterSpec,
                         @NonNull Supplier<OptionalLong> sinceCursor,
                         @NonNull Consumer<NostrEvent> eventSink,
                         @NonNull Duration backoffBase, @NonNull Duration backoffMax,
                         @NonNull HttpClient httpClient,
                         @NonNull RelayHealthTracker healthTracker) {
        this.relayUri = relayUri;
        this.subscriptionId = "infochat-" + UUID.randomUUID();
        this.filterSpec = filterSpec;
        this.sinceCursor = sinceCursor;
        this.eventSink = eventSink;
        this.backoffBase = backoffBase;
        this.backoffMax = backoffMax;
        this.httpClient = httpClient;
        this.healthTracker = healthTracker;
    }

    /** Start the connect/reconnect loop on a dedicated virtual thread. Returns immediately. */
    void start() {
        loopThread = Thread.ofVirtual()
                .name("nostr-relay-" + relayUri.getHost())
                .start(this::runLoop);
    }

    /**
     * Tear the connection down: stop reconnecting, abort the live socket, and
     * join the loop thread so no further event reaches the sink after this
     * returns. Events already parsed and handed to the sink are kept; frames
     * still in flight on the wire are dropped per the drain protocol.
     */
    void stop() {
        running = false;
        Thread thread = loopThread;
        if (thread != null) {
            thread.interrupt();
        }
        WebSocket webSocket = currentWebSocket;
        if (webSocket != null) {
            webSocket.abort();
        }
        CountDownLatch signal = closeSignal;
        if (signal != null) {
            signal.countDown();
        }
        if (thread != null) {
            try {
                thread.join(CONNECT_TIMEOUT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        int consecutiveFailures = 0;
        // !healthTracker.isTerminal() exits the loop once the source-level
        // cycle cap fires; the Registrar's terminal-callback then runs
        // supervisor.stop() on a different thread (it joins this loop, so
        // it cannot be the same thread — see NostrStreamSource.Registrar).
        while (running && !healthTracker.isTerminal()) {
            try {
                connectAndSubscribe();
                closeSignal.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                // No SafeLog at debug — the throwable is omitted entirely
                // (only its class name is logged) so untrusted exception
                // message bodies cannot reach operator logs.
                LOG.debug("Nostr relay {} connect failed: {}", relayUri, e.getClass().getSimpleName());
            }
            if (!running) {
                break;
            }
            // Report the (un)productive outcome to the health tracker. A
            // productive connection had its recordSuccess fired inline by
            // handleFrame on the first EOSE/EVENT (so the source-level
            // RECOVERED notification fires in real time, not retrospectively
            // on disconnect); only the unproductive close path records a
            // failure here.
            if (!productiveSinceConnect) {
                healthTracker.recordFailure(relayUri);
            }
            // A subscription that proved productive resets the backoff; a
            // relay that never answered keeps escalating it.
            consecutiveFailures = productiveSinceConnect ? 1 : consecutiveFailures + 1;
            try {
                // Sleep at LEAST the backoff curve, but extend to the tracker's
                // cooldown expiry when the relay is in cooldown. The floor is
                // the backoff (so a tracker that returns "now" never produces
                // a zero-sleep tight loop); the cooldown extends it when the
                // relay must park longer than backoff alone would dictate.
                long backoffMs = backoffDelay(consecutiveFailures, backoffBase, backoffMax, random).toMillis();
                long cooldownMs = Math.max(0L,
                        Duration.between(Instant.now(), healthTracker.nextAttemptTime(relayUri)).toMillis());
                Thread.sleep(Math.max(backoffMs, cooldownMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void connectAndSubscribe() throws Exception {
        closeSignal = new CountDownLatch(1);
        productiveSinceConnect = false;
        WebSocket webSocket = httpClient.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(relayUri, new RelayListener())
                .get(CONNECT_TIMEOUT.toMillis() + 1_000, TimeUnit.MILLISECONDS);
        currentWebSocket = webSocket;
    }

    private void handleFrame(String frame) {
        NostrMessage message;
        try {
            message = NostrMessage.parse(frame);
        } catch (NostrMessage.MalformedFrameException e) {
            LOG.warn("Nostr relay {} sent a malformed frame, skipping: {}", relayUri, e.getMessage());
            return;
        }
        switch (message) {
            case NostrMessage.Event event -> {
                markProductive();
                eventSink.accept(event.event());
            }
            case NostrMessage.Eose ignored -> markProductive();
            case NostrMessage.Notice notice ->
                    LOG.debug("Nostr relay {} NOTICE: {}", relayUri, notice.message());
        }
    }

    /**
     * Mark this (re)connect productive and, on the false→true edge, fire
     * the tracker's recordSuccess so the source-level RECOVERED transition
     * notifies in real time on the first productive frame rather than
     * retrospectively on the next disconnect. handleFrame runs serially on
     * the per-connection WebSocket listener thread (request(1) pull model),
     * so the read-modify-write on {@code productiveSinceConnect} is race-free
     * within one connection. A fresh RelayListener instance is built per
     * (re)connect (so {@code productiveSinceConnect} is reset to false in
     * {@code connectAndSubscribe}), giving exactly one recordSuccess per
     * productive subscription.
     */
    private void markProductive() {
        if (!productiveSinceConnect) {
            productiveSinceConnect = true;
            healthTracker.recordSuccess(relayUri);
        }
    }

    /**
     * Equal-jitter exponential backoff. The deterministic component doubles
     * each consecutive failure up to {@code max}; the jittered delay lands in
     * {@code [exp/2, exp]}, so the lower bound still grows per attempt (no
     * thundering herd, no tight loop). Package-private and {@link Random}-
     * injected so the backoff curve is unit-testable without wall-clock waits.
     *
     * @param attempt 1-based consecutive-failure count.
     */
    static @NonNull Duration backoffDelay(int attempt, @NonNull Duration base,
                                          @NonNull Duration max, @NonNull Random random) {
        long maxMillis = max.toMillis();
        long exp = base.toMillis();
        for (int i = 1; i < attempt && exp < maxMillis; i++) {
            exp = Math.min(maxMillis, exp * 2);
        }
        long half = exp / 2;
        long jitter = half <= 0 ? 0 : random.nextLong(half + 1);
        return Duration.ofMillis(half + jitter);
    }

    /**
     * Per-connection WebSocket listener. Buffers partial text frames until the
     * final fragment, then dispatches the assembled frame. A fresh instance is
     * built on each (re)connect, so the buffer never spans connections.
     */
    private final class RelayListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();
        // True after a fragment overflow until the relay's last=true marker;
        // additional fragments of the same logical frame are discarded so we
        // don't half-parse a truncated frame.
        private boolean skipUntilLast = false;

        @Override
        public void onOpen(@NonNull WebSocket webSocket) {
            webSocket.request(1);
            String req = NostrMessage.serializeReq(subscriptionId, filterSpec, sinceCursor.get());
            webSocket.sendText(req, true);
        }

        @Override
        public CompletionStage<?> onText(@NonNull WebSocket webSocket, @NonNull CharSequence data, boolean last) {
            if (skipUntilLast) {
                if (last) {
                    skipUntilLast = false;
                }
                webSocket.request(1);
                return null;
            }
            if ((long) buffer.length() + data.length() > MAX_FRAME_BYTES) {
                LOG.warn("Nostr relay {} fragment would exceed {} bytes; dropping frame",
                        relayUri, MAX_FRAME_BYTES);
                buffer.setLength(0);
                skipUntilLast = !last;
                webSocket.request(1);
                return null;
            }
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                handleFrame(frame);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(@NonNull WebSocket webSocket, int statusCode, @NonNull String reason) {
            signalClosed();
            return null;
        }

        @Override
        public void onError(@NonNull WebSocket webSocket, @NonNull Throwable error) {
            signalClosed();
        }

        private void signalClosed() {
            CountDownLatch signal = closeSignal;
            if (signal != null) {
                signal.countDown();
            }
        }
    }
}
