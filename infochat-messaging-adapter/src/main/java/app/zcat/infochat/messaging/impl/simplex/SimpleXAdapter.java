package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * SimpleX production adapter. Owns a {@link SimpleXSubprocess} that runs
 * a local simplex-chat instance and a {@link SimpleXWebSocketClient} that
 * speaks the simplex-chat WebSocket bot API. Implements the full
 * {@link MessagingAdapter} contract: send / update / finalize via the
 * WebSocket, typing as a best-effort fire-and-forget, identity assertion
 * from the SimpleX-cryptographically-routed inbound envelope (decision
 * D10 + D32, {@code docs/spec/messaging.md} §Per-adapter trust level).
 *
 * <p>The no-arg constructor is preserved so the pre-existing
 * {@code SimpleXAdapterSkeletonTest} (which only inspects the static
 * capability flags) keeps passing. Call sites that intend to
 * {@link #start} or transport messages MUST use the configured
 * constructor — the no-arg variant intentionally cannot reach the wire,
 * and methods that need the deps throw {@link IllegalStateException}.
 * Provider-side wiring (M1-105) instantiates with the configured form.</p>
 *
 * <p>The supportsTypingIndicator capability flag remains {@code true}
 * (declared by the M1-102 skeleton); acceptance item 11 commits to
 * sending {@code apiSetContactTyping}-shaped commands when the SPI's
 * {@link #setTyping} is invoked. If simplex-chat rejects the command, the
 * fire-and-forget path absorbs the failure — typing is best-effort by
 * the SPI's own contract ({@link MessagingAdapter#setTyping}).</p>
 */
public final class SimpleXAdapter implements MessagingAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleXAdapter.class);

    // maxInboundMessageBytes is the 16 KiB laptop default per
    // docs/design/06-messaging.md §6.2.2 (profile-tunable). maxMessageBytes,
    // maxSendsPerSecond, and minEditInterval are best-guess defaults not
    // fixed by spec and are expected to be tuned against a live simplex-chat
    // in M1-105.
    private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
            /* supportsMentionByContactId */ true,
            /* supportsMembershipEvents   */ false,
            /* supportsCodeFormatting     */ false,
            /* supportsMarkdownLinks      */ false,
            /* supportsMultilineCode      */ false,
            /* supportsAttachments        */ false,
            /* supportsThreading          */ false,
            /* maxMessageBytes            */ 2_000,
            /* maxInboundMessageBytes     */ 16_384,
            /* maxInflightSends           */ 4,
            /* maxSendsPerSecond          */ 8,
            /* supportsMessageEdit        */ true,
            /* supportsTypingIndicator    */ true,
            /* minEditInterval            */ Duration.ZERO);

    static final Duration WS_READY_TIMEOUT = Duration.ofSeconds(10);
    static final Duration ACK_TIMEOUT = Duration.ofSeconds(30);

    private final @Nullable SimpleXConfig config;
    private final @Nullable HttpClient httpClient;
    private final @Nullable Consumer<String> adminNotifier;

    private final AtomicLong handleCounter = new AtomicLong();
    private final AtomicLong commandCounter = new AtomicLong();
    private final Map<String, SimpleXMessageHandle> handles = new ConcurrentHashMap<>();
    private final Map<String, Boolean> finalized = new ConcurrentHashMap<>();

    private volatile @Nullable InboundHandler inboundHandler;
    private volatile @Nullable SimpleXSubprocess subprocess;
    private volatile @Nullable SimpleXWebSocketClient webSocket;

    /**
     * Capability-only constructor. The resulting adapter can answer
     * {@link #name}, {@link #trustLevel}, {@link #capabilities}, and
     * {@link #setInboundHandler} (the handler is stored for inspection)
     * but every transport call — {@link #start}, {@link #send},
     * {@link #update}, {@link #finalize}, {@link #setTyping},
     * {@link #assertIdentity} — throws because no config or
     * {@link HttpClient} was provided. Used by
     * {@code SimpleXAdapterSkeletonTest} to assert the static capability
     * surface.
     */
    public SimpleXAdapter() {
        this.config = null;
        this.httpClient = null;
        this.adminNotifier = null;
    }

    /**
     * Full constructor used by Provider-side wiring (M1-105). Supplies
     * the operator config, the JDK {@link HttpClient} used to dial the
     * simplex-chat WebSocket, and the admin-notification consumer the
     * subprocess supervisor calls at the FAILED transition.
     */
    public SimpleXAdapter(@NonNull SimpleXConfig config,
                          @NonNull HttpClient httpClient,
                          @NonNull Consumer<String> adminNotifier) {
        this.config = config;
        this.httpClient = httpClient;
        this.adminNotifier = adminNotifier;
    }

    @Override
    public String name() {
        return "simplex";
    }

    @Override
    public CapabilityFlags capabilities() {
        return CAPABILITIES;
    }

    @Override
    public AdapterTrustLevel trustLevel() {
        return AdapterTrustLevel.HIGH;
    }

    /**
     * Start the simplex-chat subprocess, wait for its WebSocket endpoint
     * to become reachable, then open the WebSocket. Acceptance items 5 +
     * 6 of M1-103. Throws {@link MessagingException} (categorised) on
     * launch / readiness / connect failure so Provider sees the failure
     * via the same exception channel as transport faults.
     */
    public void start() throws MessagingException {
        SimpleXConfig cfg = requireWired();
        HttpClient http = httpClient;
        Consumer<String> notify = adminNotifier;
        if (http == null || notify == null) {
            throw new IllegalStateException(
                    "SimpleXAdapter not fully wired (httpClient/adminNotifier are null)");
        }
        SimpleXSubprocess sub = new SimpleXSubprocess(
                SimpleXSubprocess.commandFor(cfg),
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                SimpleXSubprocess.DEFAULT_CRASH_CAP,
                notify,
                new Random());
        sub.start();
        this.subprocess = sub;
        try {
            waitForWebSocketReady(cfg.wsPort());
        } catch (MessagingException e) {
            sub.stop();
            this.subprocess = null;
            throw e;
        }
        URI uri = URI.create("ws://127.0.0.1:" + cfg.wsPort());
        SimpleXWebSocketClient ws = new SimpleXWebSocketClient(uri, http, this::onInbound);
        try {
            ws.start();
        } catch (MessagingException e) {
            sub.stop();
            this.subprocess = null;
            throw e;
        }
        this.webSocket = ws;
    }

    /**
     * Disconnect the WebSocket and terminate the simplex-chat subprocess
     * (SIGTERM, grace, SIGKILL via {@link SimpleXSubprocess#stop}).
     * Idempotent — safe to call on a never-started adapter or after a
     * prior close.
     */
    public void close() {
        SimpleXWebSocketClient ws = webSocket;
        if (ws != null) {
            ws.close();
            webSocket = null;
        }
        SimpleXSubprocess sub = subprocess;
        if (sub != null) {
            sub.stop();
            subprocess = null;
        }
    }

    @Override
    public Identity assertIdentity(@NonNull InboundMessage msg) {
        // SimpleX is HIGH trust because the contact id is the cryptographic
        // queue address SimpleX's message-routing layer verifies before
        // delivery to the bot. The codec extracted the verified contact id
        // into msg.sender() at decode time; assertIdentity returns it
        // directly (the assertion already happened upstream of the codec).
        return msg.sender();
    }

    @Override
    public MessageHandle send(@NonNull OutboundMessage msg) throws MessagingException {
        SimpleXWebSocketClient ws = requireConnected();
        String corrId = nextCorrId();
        String envelope = SimpleXMessageCodec.encodeSendCommand(corrId, msg.scope(), msg.text());
        String chatItemId = ws.sendCommand(corrId, envelope, ACK_TIMEOUT);
        String opaque = "simplex-" + handleCounter.incrementAndGet();
        handles.put(opaque, new SimpleXMessageHandle(chatItemId, msg.scope(), msg.correlationId()));
        return new MessageHandle(opaque);
    }

    @Override
    public void update(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
        SimpleXMessageHandle internal = requireKnownAndOpen(handle);
        SimpleXWebSocketClient ws = requireConnected();
        String corrId = nextCorrId();
        String envelope = SimpleXMessageCodec.encodeUpdateCommand(
                corrId, internal.chatItemId(), internal.scope(), body);
        // update returns the (possibly changed) chatItemId — the SimpleX
        // surface re-acks edits with the same id. We don't need the return
        // value here, only the success/failure outcome.
        ws.sendCommand(corrId, envelope, ACK_TIMEOUT);
    }

    @Override
    public void finalize(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
        SimpleXMessageHandle internal = requireKnownAndOpen(handle);
        SimpleXWebSocketClient ws = requireConnected();
        String corrId = nextCorrId();
        String envelope = SimpleXMessageCodec.encodeFinalizeCommand(
                corrId, internal.chatItemId(), internal.scope(), body);
        ws.sendCommand(corrId, envelope, ACK_TIMEOUT);
        // SPI Javadoc lines 115–123: after finalize, any update() on the
        // same handle MUST throw PERMANENT. The flag is checked above in
        // requireKnownAndOpen — set it here on success.
        finalized.put(handle.opaqueValue(), Boolean.TRUE);
    }

    @Override
    public void setTyping(@NonNull ScopeRef scope, boolean typing) {
        SimpleXWebSocketClient ws = webSocket;
        if (ws == null) {
            // Best-effort: an un-started or closed adapter silently absorbs
            // the typing pulse per SPI Javadoc on setTyping (no throw).
            return;
        }
        String envelope = SimpleXMessageCodec.encodeTypingCommand(
                nextCorrId(), scope, typing);
        ws.sendFireAndForget(envelope);
    }

    @Override
    public void setInboundHandler(@NonNull InboundHandler handler) {
        this.inboundHandler = handler;
    }

    // -- internals -----------------------------------------------------------

    private void onInbound(InboundMessage msg) {
        InboundHandler current = inboundHandler;
        if (current == null) {
            // No registered consumer yet — Provider hasn't called
            // setInboundHandler. Dropping is the right move; the inbound
            // event arrived before the consumer attached.
            LOG.debug("dropping inbound; no handler registered");
            return;
        }
        try {
            current.onMessage(msg);
        } catch (RuntimeException e) {
            // The handler is Provider-side code; a misbehaving handler must
            // not tear the WebSocket listener thread down.
            LOG.warn("inbound handler threw: {}", e.getClass().getSimpleName());
        }
    }

    private SimpleXMessageHandle requireKnownAndOpen(MessageHandle handle) throws MessagingException {
        SimpleXMessageHandle internal = handles.get(handle.opaqueValue());
        if (internal == null) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "unknown handle: " + handle.opaqueValue());
        }
        if (Boolean.TRUE.equals(finalized.get(handle.opaqueValue()))) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "handle already finalized: " + handle.opaqueValue());
        }
        return internal;
    }

    private SimpleXConfig requireWired() {
        SimpleXConfig cfg = config;
        if (cfg == null) {
            throw new IllegalStateException(
                    "SimpleXAdapter constructed without config — use the (config, httpClient, "
                            + "adminNotifier) constructor for transport operations");
        }
        return cfg;
    }

    private SimpleXWebSocketClient requireConnected() throws MessagingException {
        SimpleXWebSocketClient ws = webSocket;
        if (ws == null) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "SimpleXAdapter is not started; call start() first");
        }
        return ws;
    }

    private String nextCorrId() {
        return "simplex-cmd-" + commandCounter.incrementAndGet();
    }

    /**
     * TCP-probe loop: dial 127.0.0.1:wsPort every ~100 ms until the
     * handshake succeeds (simplex-chat opened its socket) or the deadline
     * elapses. Acceptance item 5 requires the WS endpoint be reachable
     * before WS connect — otherwise the {@link HttpClient} handshake
     * fails with a connection-refused that the supervisor then routes
     * through a full restart cycle.
     */
    private void waitForWebSocketReady(int port) throws MessagingException {
        long deadline = System.nanoTime() + WS_READY_TIMEOUT.toNanos();
        Throwable lastError = null;
        while (System.nanoTime() < deadline) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                lastError = e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new MessagingException(FailureCategory.TRANSIENT,
                            "interrupted while waiting for WebSocket port", ie);
                }
            }
        }
        throw new MessagingException(FailureCategory.TRANSIENT,
                "simplex-chat WebSocket port " + port + " not reachable within "
                        + WS_READY_TIMEOUT,
                lastError);
    }
}
