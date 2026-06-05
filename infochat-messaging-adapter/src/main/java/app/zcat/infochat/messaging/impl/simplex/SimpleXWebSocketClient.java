package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JDK-{@link HttpClient}-backed WebSocket connection to a running
 * simplex-chat instance. Owns the live socket, delegates framing/JSON
 * details to {@link SimpleXMessageCodec}, and exposes a
 * synchronous-looking {@link #sendCommand} API so callers (the adapter's
 * {@code send}/{@code update}/{@code finalize}) can stay blocking per the
 * Quarkus/JDK-25 virtual-thread + blocking style (CLAUDE.md §Stack, D46).
 *
 * <p>Reconnect is intentionally not owned by this class. Per
 * {@code docs/design/06-messaging.md} §6.4.6 the simplex-chat process and
 * its WebSocket form one supervised unit — {@link SimpleXSubprocess}
 * restarts the process on crash, and the adapter then re-runs
 * {@link #start} from scratch. Tearing the WS down independently of the
 * process would leave the adapter in a half-state nobody can recover; the
 * supervisor manages the whole pair.</p>
 *
 * <p>Trust boundary: the WebSocket is an EXTERNAL system boundary, so
 * frames are validated by {@link SimpleXMessageCodec#decode}. A malformed
 * frame is logged and dropped, never propagated — one bad frame must not
 * tear the connection down (parallel to {@code NostrRelayConnection}'s
 * MalformedFrameException discipline).</p>
 */
final class SimpleXWebSocketClient {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleXWebSocketClient.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    // Caps the pre-assembly buffer for fragmented text frames so a hostile
    // peer cannot OOM the adapter. Mirrors NostrRelayConnection's bound.
    static final int MAX_FRAME_BYTES = 1_048_576;

    /** Receives decoded inbound chat messages from the WS listener thread. */
    @FunctionalInterface
    interface InboundConsumer {
        void onInbound(@NonNull InboundMessage msg);
    }

    /**
     * Receives decoded group-scope candidates from the WS listener
     * thread. The mention-recognition decision belongs to the
     * downstream consumer ({@link SimpleXGroupHandler}); the client
     * just routes the variant.
     */
    @FunctionalInterface
    interface GroupCandidateConsumer {
        void onGroupCandidate(SimpleXMessageCodec.@NonNull GroupCandidate gc);
    }

    private final URI uri;
    private final HttpClient httpClient;
    private final InboundConsumer inboundConsumer;
    private final GroupCandidateConsumer groupCandidateConsumer;

    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    // Null until connect() completes the handshake; every read copies to a
    // local and guards on null before use.
    private volatile @Nullable WebSocket webSocket;
    private volatile boolean closed = false;

    SimpleXWebSocketClient(@NonNull URI uri,
                           @NonNull HttpClient httpClient,
                           @NonNull InboundConsumer inboundConsumer,
                           @NonNull GroupCandidateConsumer groupCandidateConsumer) {
        this.uri = uri;
        this.httpClient = httpClient;
        this.inboundConsumer = inboundConsumer;
        this.groupCandidateConsumer = groupCandidateConsumer;
    }

    /**
     * Block until the WebSocket handshake completes or
     * {@link #CONNECT_TIMEOUT} elapses. The adapter's {@code start()}
     * routes the failure to a {@link MessagingException} so Provider sees
     * a categorised failure rather than a bare exception.
     */
    void start() throws MessagingException {
        try {
            this.webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(uri, new Listener())
                    .get(CONNECT_TIMEOUT.toMillis() + 1_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "interrupted while opening WebSocket to " + uri, e);
        } catch (TimeoutException e) {
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "WebSocket handshake to " + uri + " did not complete within "
                            + CONNECT_TIMEOUT, e);
        } catch (ExecutionException e) {
            // The handshake failed because simplex-chat refused the connection,
            // the port is not listening yet, or the network rejected the dial.
            // The supervisor will restart the subprocess; treat as transient.
            // A future completed exceptionally always carries a cause.
            Throwable cause = Objects.requireNonNull(e.getCause());
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "WebSocket handshake to " + uri + " failed: "
                            + cause.getClass().getSimpleName(), cause);
        }
    }

    /**
     * Tear the connection down. Idempotent. Any in-flight
     * {@link #sendCommand} calls complete exceptionally with a
     * {@link FailureCategory#PERMANENT} {@link MessagingException} so
     * callers do not block forever on a corked socket.
     */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.abort();
        }
        // Drain pending command futures; the wire closing on us means none
        // will ever be acked. The category is PERMANENT because retrying on a
        // closed socket cannot succeed — callers must rebuild via start().
        var snapshot = Map.copyOf(pending);
        pending.clear();
        for (var entry : snapshot.entrySet()) {
            entry.getValue().completeExceptionally(new MessagingException(
                    FailureCategory.PERMANENT,
                    "WebSocket closed before command " + entry.getKey() + " was acked"));
        }
    }

    /**
     * Send a command envelope and block until the matching {@link
     * SimpleXMessageCodec.SendAck} arrives, returning the chat-item id the
     * adapter stores in {@link SimpleXMessageHandle}. A
     * {@link SimpleXMessageCodec.CommandError} response completes
     * exceptionally with a {@link MessagingException} whose
     * {@link FailureCategory} comes straight from
     * {@link SimpleXMessageCodec#classifyError(String)}.
     *
     * @param corrId       adapter-chosen correlation id matching the {@code corrId}
     *                     field in {@code envelopeJson} (the codec already wrote it).
     * @param envelopeJson the JSON envelope produced by {@link SimpleXMessageCodec}.
     * @param ackTimeout   bound on how long to wait for the ack.
     */
    @NonNull String sendCommand(@NonNull String corrId,
                                @NonNull String envelopeJson,
                                @NonNull Duration ackTimeout) throws MessagingException {
        if (closed) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "WebSocket is closed; cannot send corrId=" + corrId);
        }
        WebSocket ws = webSocket;
        if (ws == null) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "WebSocket is not yet started; cannot send corrId=" + corrId);
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(corrId, future);
        try {
            // The send's own CompletableFuture is intentionally not awaited —
            // a send failure surfaces as the ack future timing out below, which
            // the caller already handles as TRANSIENT.
            var unused = ws.sendText(envelopeJson, true);
            return future.get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "interrupted while awaiting ack for corrId=" + corrId, e);
        } catch (TimeoutException e) {
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "no ack for corrId=" + corrId + " within " + ackTimeout, e);
        } catch (ExecutionException e) {
            // Unwrap MessagingException raised by the listener thread so the
            // caller sees the original FailureCategory, not a wrapper.
            // A future completed exceptionally always carries a cause.
            Throwable cause = Objects.requireNonNull(e.getCause());
            if (cause instanceof MessagingException me) {
                throw me;
            }
            throw new MessagingException(FailureCategory.PERMANENT,
                    "ack future for corrId=" + corrId + " failed: "
                            + cause.getClass().getSimpleName(),
                    cause);
        } catch (RuntimeException e) {
            // close() can abort the WebSocket between the `closed` check above
            // and ws.sendText() here; the JDK WebSocket then rejects the send
            // with an IllegalStateException (a RuntimeException) that would
            // otherwise escape sendCommand raw and uncategorised. Classify as
            // PERMANENT — the socket is gone and retrying on it cannot succeed;
            // the caller must rebuild via start(), the same category close()
            // stamps on the pending futures it drains.
            throw new MessagingException(FailureCategory.PERMANENT,
                    "WebSocket send for corrId=" + corrId + " failed (closed concurrently): "
                            + e.getClass().getSimpleName(), e);
        } finally {
            pending.remove(corrId);
        }
    }

    /**
     * Fire-and-forget variant used by {@link MessagingAdapter#setTyping},
     * which the SPI defines as best-effort and not allowed to throw. A
     * send failure here is logged at DEBUG and absorbed.
     */
    void sendFireAndForget(@NonNull String envelopeJson) {
        WebSocket ws = webSocket;
        if (ws == null || closed) {
            LOG.debug("dropping fire-and-forget send; socket not available");
            return;
        }
        try {
            // Best-effort send: the returned CompletableFuture is intentionally
            // not awaited (see the method contract above); a synchronous reject
            // is caught below and an async failure is absorbed by design.
            var unused = ws.sendText(envelopeJson, true);
        } catch (RuntimeException e) {
            LOG.debug("fire-and-forget send failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Visible for tests. */
    boolean isClosed() {
        return closed;
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();
        private boolean skipUntilLast = false;

        @Override
        public void onOpen(@NonNull WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.@Nullable CompletionStage<?> onText(@NonNull WebSocket webSocket,
                                                              @NonNull CharSequence data,
                                                              boolean last) {
            if (skipUntilLast) {
                if (last) {
                    skipUntilLast = false;
                }
                webSocket.request(1);
                return null;
            }
            if ((long) buffer.length() + data.length() > MAX_FRAME_BYTES) {
                LOG.warn("simplex-chat fragment would exceed {} bytes; dropping frame",
                        MAX_FRAME_BYTES);
                buffer.setLength(0);
                skipUntilLast = !last;
                webSocket.request(1);
                return null;
            }
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                dispatch(frame);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.@Nullable CompletionStage<?> onClose(@NonNull WebSocket webSocket,
                                                               int statusCode,
                                                               @NonNull String reason) {
            // The supervisor (SimpleXSubprocess) sees the process exit and
            // restarts the pair; this listener just drains pending futures.
            failAllPending(new MessagingException(FailureCategory.PERMANENT,
                    "WebSocket closed by peer: " + statusCode + " " + reason));
            return null;
        }

        @Override
        public void onError(@NonNull WebSocket webSocket, @NonNull Throwable error) {
            failAllPending(new MessagingException(FailureCategory.TRANSIENT,
                    "WebSocket error: " + error.getClass().getSimpleName(), error));
        }
    }

    private void dispatch(String frame) {
        SimpleXMessageCodec.DecodedFrame decoded;
        try {
            decoded = SimpleXMessageCodec.decode(frame);
        } catch (SimpleXMessageCodec.MalformedFrameException e) {
            // No interpolation of the exception message or the frame
            // contents — security.md §User content in exceptions: the
            // application logger MUST NOT carry user-authored prose.
            // The exception's message is fixed by codec contract, so the
            // log site stays defense-in-depth by not echoing it either.
            LOG.warn("simplex-chat sent a malformed frame, skipping");
            return;
        }
        switch (decoded) {
            case SimpleXMessageCodec.Inbound in -> inboundConsumer.onInbound(in.message());
            case SimpleXMessageCodec.GroupCandidate gc -> groupCandidateConsumer.onGroupCandidate(gc);
            case SimpleXMessageCodec.SendAck ack -> completePending(ack.corrId(), ack.chatItemId());
            case SimpleXMessageCodec.CommandError err -> failPending(err);
            case SimpleXMessageCodec.Ignored ignored ->
                    LOG.debug("simplex-chat frame ignored: {}", ignored.reason());
        }
    }

    private void completePending(String corrId, String chatItemId) {
        CompletableFuture<String> future = pending.remove(corrId);
        if (future == null) {
            // No matching pending command — either a server-async event we do
            // not handle yet, or the caller has already given up. Drop.
            LOG.debug("no pending command for ack corrId={}", corrId);
            return;
        }
        future.complete(chatItemId);
    }

    private void failPending(SimpleXMessageCodec.CommandError err) {
        CompletableFuture<String> future = pending.remove(err.corrId());
        MessagingException ex = new MessagingException(err.category(),
                "simplex-chat error: " + err.detail());
        if (future == null) {
            LOG.debug("no pending command for error corrId={}: {}", err.corrId(), err.detail());
            return;
        }
        future.completeExceptionally(ex);
    }

    private void failAllPending(MessagingException ex) {
        var snapshot = Map.copyOf(pending);
        pending.clear();
        for (var future : snapshot.values()) {
            future.completeExceptionally(ex);
        }
    }
}
