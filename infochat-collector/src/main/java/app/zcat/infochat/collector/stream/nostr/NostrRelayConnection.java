package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
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
 * subscription proves productive — the relay answered with an EOSE or a
 * signature-verified EVENT — so a relay that accepts the socket and
 * immediately drops it backs
 * off instead of hot-looping (acceptance: "no tight-loop reconnect storm").
 * Each (re)connect re-reads the {@code since} cursor so the relay replays
 * only events newer than the last persisted one.</p>
 *
 * <h2>Trust boundary</h2>
 * <p>A relay is untrusted. A malformed frame is logged and skipped, never
 * propagated, so one bad frame cannot tear the subscription down. Signature
 * verification is M1-097. Every connect (initial and reconnect) runs through
 * {@link SsrfGuardedHttpClient#checkAndPinForWebSocket(URI)} — DNS resolves
 * to a validated IP set under the JVM-wide pin, the {@code WebSocket}
 * handshake lands on those IPs, and a periodic re-resolve watcher
 * ({@link #peerIpDiverged}) aborts the live socket if the re-resolved
 * address set diverges from the pinned set or newly fails the
 * {@link app.zcat.infochat.ssrf.IpBlocklist} gate. This is the spec's
 * "any peer-IP change observed at the socket layer is a hard close"
 * promise (see {@code docs/spec/security.md} §SSRF), implemented via
 * periodic DNS re-check because the JDK {@code WebSocket} API does not
 * expose the peer IP directly.</p>
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

    // Default cadence for the peer-IP-change watcher. Long enough that the
    // re-resolve cost is negligible against a 24h connection (~1440 lookups
    // through the SsrfGuardedHttpClient resolver seam); short enough that
    // a DNS-rebind attack is detected before significant payload exposure.
    // Tests override via the package-private constructor.
    static final Duration DEFAULT_PEER_IP_CHECK_INTERVAL = Duration.ofSeconds(60);

    private final URI relayUri;
    private final String subscriptionId;
    private final String filterSpec;
    private final Supplier<OptionalLong> sinceCursor;
    // Returns true iff the event crossed the signature trust boundary and was
    // accepted (NostrStreamSource::enqueueInbound runs verifier.verify()). Typed
    // as a Predicate, not a Consumer, so handleFrame can gate markProductive()
    // on the post-verify result (M1-326).
    private final Predicate<NostrEvent> eventSink;
    private final Duration backoffBase;
    private final Duration backoffMax;
    private final HttpClient httpClient;
    private final SsrfGuardedHttpClient ssrfClient;
    private final Duration peerIpCheckInterval;
    private final RelayHealthTracker healthTracker;

    private volatile boolean running = true;
    // Lazily initialized when the run loop connects (null in the pre-connect
    // window, which stop() guards with explicit null-checks); never assigned
    // null thereafter, so non-null at every deref under NullAway.Init.
    @SuppressWarnings("NullAway.Init")
    private volatile Thread loopThread;
    @SuppressWarnings("NullAway.Init")
    private volatile WebSocket currentWebSocket;
    @SuppressWarnings("NullAway.Init")
    private volatile CountDownLatch closeSignal;
    private volatile boolean productiveSinceConnect;
    // The address set the SSRF guard validated at connect time. The
    // watcher compares the periodic re-resolve against this snapshot;
    // a disjoint re-resolve is a peer-IP change → hard close. Captured
    // from PinnedDial#addresses() before the WebSocket handshake so the
    // watcher's snapshot is stable for the life of the connection.
    @SuppressWarnings("NullAway.Init")
    private volatile List<InetAddress> pinnedAddresses;

    NostrRelayConnection(URI relayUri, String filterSpec,
                         Supplier<OptionalLong> sinceCursor,
                         Predicate<NostrEvent> eventSink,
                         Duration backoffBase, Duration backoffMax,
                         HttpClient httpClient,
                         SsrfGuardedHttpClient ssrfClient,
                         Duration peerIpCheckInterval,
                         RelayHealthTracker healthTracker) {
        this.relayUri = relayUri;
        this.subscriptionId = "infochat-" + UUID.randomUUID();
        this.filterSpec = filterSpec;
        this.sinceCursor = sinceCursor;
        this.eventSink = eventSink;
        this.backoffBase = backoffBase;
        this.backoffMax = backoffMax;
        this.httpClient = httpClient;
        this.ssrfClient = ssrfClient;
        this.peerIpCheckInterval = peerIpCheckInterval;
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
                // Periodic peer-IP-change watcher. The JDK WebSocket
                // API does not expose the peer IP directly, so we
                // re-resolve DNS through the SSRF guard on a fixed
                // interval and compare against the address set the
                // guard pinned at connect time. A disjoint or
                // newly-blocked re-resolve is the "peer-IP change"
                // signal per security.md §SSRF → hard close.
                while (running) {
                    if (closeSignal.await(peerIpCheckInterval.toMillis(), TimeUnit.MILLISECONDS)) {
                        // Natural close from RelayListener.onClose/onError
                        // or from stop(); fall through to the backoff arm.
                        break;
                    }
                    if (peerIpDiverged()) {
                        // SafeLog not used: relayUri is operator-authored.
                        LOG.warn("Nostr relay {} peer IP diverged from pinned set; hard-closing", relayUri);
                        WebSocket webSocket = currentWebSocket;
                        if (webSocket != null) {
                            webSocket.abort();
                        }
                        // The abort() triggers RelayListener.onError
                        // which countDowns closeSignal, but breaking
                        // here is the deterministic exit; do not wait
                        // for the asynchronous signal.
                        break;
                    }
                }
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
            // handleFrame on the first EOSE or signature-verified EVENT (so the
            // source-level
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
                // the backoff (so a tracker that returns ZERO never produces
                // a zero-sleep tight loop); the cooldown extends it when the
                // relay must park longer than backoff alone would dictate. The
                // remaining cooldown is computed inside the tracker against its
                // injected Clock (untilNextAttempt) rather than here against a
                // wall-clock Instant.now(), so the park gate reads one clock
                // (no app-vs-wall split, §9 / M1-490).
                long backoffMs = backoffDelay(consecutiveFailures, backoffBase, backoffMax, ThreadLocalRandom.current()).toMillis();
                long cooldownMs = healthTracker.untilNextAttempt(relayUri).toMillis();
                Thread.sleep(Math.max(backoffMs, cooldownMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // Package-private so {@link NostrSsrfTest} can drive a single
    // connect attempt in isolation. The runLoop above is the only
    // production caller; tests bypass the loop to assert on the
    // synchronous SSRF gate.
    void connectAndSubscribe() throws Exception {
        closeSignal = new CountDownLatch(1);
        productiveSinceConnect = false;
        WebSocket webSocket;
        // SSRF gate per security.md §SSRF: validate hostname → DNS →
        // IpBlocklist, then pin in the JVM-wide resolver slot so the
        // WebSocket handshake below connects to a validated IP. The
        // pin is released when the try-with-resources block exits; the
        // already-established TCP connection survives the unpin.
        try (SsrfGuardedHttpClient.PinnedDial dial = ssrfClient.checkAndPinForWebSocket(relayUri)) {
            // Snapshot the validated address set BEFORE the handshake
            // so peerIpDiverged() always sees a stable baseline once
            // currentWebSocket is non-null. If buildAsync throws, the
            // value is overwritten on the next successful connect.
            pinnedAddresses = dial.addresses();
            webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(relayUri, new RelayListener())
                    .get(CONNECT_TIMEOUT.toMillis() + 1_000, TimeUnit.MILLISECONDS);
        }
        currentWebSocket = webSocket;
    }

    /**
     * True iff a fresh DNS resolution of {@link #relayUri} no longer
     * intersects {@link #pinnedAddresses} OR the re-resolve newly fails
     * the SSRF policy (raises {@link
     * app.zcat.infochat.ssrf.SsrfGuardedHttpClient.SsrfPolicyException}
     * inside {@link SsrfGuardedHttpClient#resolveForWebSocket(URI)}).
     * Both arms are the spec's "peer-IP change observed at the socket
     * layer" — either the host now points to different addresses
     * (DNS-rebind) or the {@link app.zcat.infochat.ssrf.IpBlocklist}
     * now refuses what it allowed before.
     */
    private boolean peerIpDiverged() {
        List<InetAddress> current;
        try {
            current = ssrfClient.resolveForWebSocket(relayUri);
        } catch (RuntimeException e) {
            // The re-resolve threw an SsrfPolicyException (blocked,
            // userinfo, scheme — only the blocklist arm is reachable
            // mid-session for an already-validated URI) or another
            // transient resolver failure. Both are "the address we
            // pinned is no longer servable safely" → hard close.
            return true;
        }
        List<InetAddress> pinned = pinnedAddresses;
        if (pinned == null) {
            // Watcher started before connectAndSubscribe captured the
            // pin; should not happen because the watcher runs strictly
            // after connectAndSubscribe returns. Treat as divergence.
            return true;
        }
        for (InetAddress addr : current) {
            if (pinned.contains(addr)) {
                return false;
            }
        }
        return true;
    }

    // Package-private (not private) so NostrProductivityAfterVerifyTest can
    // feed raw frames through the message dispatch and assert the post-verify
    // productivity gate without standing up a live WebSocket — mirrors the
    // connectAndSubscribe test seam above. M1-326.
    void handleFrame(String frame) {
        NostrMessage message;
        try {
            message = NostrMessage.parse(frame);
        } catch (NostrMessage.MalformedFrameException e) {
            LOG.warn("Nostr relay {} sent a malformed frame, skipping: {}", relayUri, e.getMessage());
            return;
        }
        switch (message) {
            case NostrMessage.Event event -> {
                // Credit productivity only AFTER the event crosses the signature
                // trust boundary. eventSink (NostrStreamSource::enqueueInbound)
                // runs verifier.verify() and returns false on a forged/invalid
                // signature. Crediting before verify would let a relay flooding
                // well-framed but signature-invalid EVENTs reset backoff every
                // connect and score healthy, silencing the per-relay cooldown /
                // terminal-failed safety valve (M1-326).
                if (eventSink.test(event.event())) {
                    markProductive();
                }
            }
            case NostrMessage.Eose ignored -> markProductive();
            case NostrMessage.Notice notice ->
                    // notice.message() is relay-authored, untrusted text; strip
                    // controls/bidi before it reaches the log so it cannot forge
                    // or split a log line, matching NostrMessage.summarize and the
                    // other relay-byte log sites (M1-491).
                    LOG.debug("Nostr relay {} NOTICE: {}", relayUri, SafeLog.stripControls(notice.message()));
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

    // Read-only test seam (M1-326): NostrProductivityAfterVerifyTest asserts a
    // signature-invalid EVENT flood leaves this false. markProductive() is the
    // sole writer and the sole caller of healthTracker.recordSuccess, so a
    // false reading proves recordSuccess never fired for this connection.
    boolean productiveSinceConnect() {
        return productiveSinceConnect;
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
    static Duration backoffDelay(int attempt, Duration base,
                                          Duration max, Random random) {
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

        // Fire-and-forget REQ send: the returned CompletableFuture is
        // intentionally not awaited — a send failure also drives the JDK
        // WebSocket into RelayListener.onError, which countDowns closeSignal
        // and routes to the reconnect/backoff arm.
        @SuppressWarnings("FutureReturnValueIgnored")
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            String req = NostrMessage.serializeReq(subscriptionId, filterSpec, sinceCursor.get());
            webSocket.sendText(req, true);
        }

        @Override
        public @Nullable CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
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
        public @Nullable CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            signalClosed();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
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
