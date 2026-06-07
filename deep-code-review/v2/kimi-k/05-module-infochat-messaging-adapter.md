# Deep code review: module
**Target:** module infochat-messaging-adapter
**Lens:** module
**Module path:**
    infochat-messaging-adapter/
**Date:** 2026-06-07 01:15
**Reviewer:** senior-developer (opus)

## Headline findings

- [CRITICAL] PERFORMANCE — SimpleXWebSocketClient.java:320 / SignalJsonRpcClient.java:542 — Inbound messages are dispatched synchronously on the transport's only read thread, so any Provider reply sent from inside `onMessage` self-deadlocks until the ack timeout (30 s SimpleX / 15 s Signal); on Signal, three such timeouts SIGKILL the healthy daemon.
- [HIGH] SECURITY — SignalMessageCodec.java:99-121, 153-155 — Typed JSON accessors after the parse guard throw raw `ClassCastException`/`NullPointerException` that escape `handleLine`'s `IllegalArgumentException` catch and permanently kill the `signal-jsonrpc-reader` thread.
- [HIGH] PERFORMANCE — SimpleXWebSocketClient.java:186, 238 — `ws.sendText` futures are discarded while the JDK WebSocket allows only one outstanding send; a concurrent second send is silently never transmitted and the caller stalls the full 30 s ack timeout, despite `maxInflightSends = 4` being advertised.
- [HIGH] MAINTAINABILITY-RULES-DRIFT — SimpleXGroupHandler.java:78-84, SignalGroupHandler.java:161-167 — Neither group handler strips the bot mention from the delivered text, violating spec/messaging.md §Required SPI surface and design §6.3.3 (MUST strip); group slash commands arrive as `"@bot /summary tech"` and cannot parse.
- [HIGH] MAINTAINABILITY-RULES-DRIFT — SimpleXSubprocess.java:27-31, SignalSubprocess.java:211-235 — Both supervisors restart the crashed child process but nothing ever reconnects the WS/JSON-RPC client; the javadocs claim a reconnect mechanism that does not exist, so the adapter is permanently dead after the first transport-process crash.
- [HIGH] MAINTAINABILITY-RULES-DRIFT — SignalJsonRpcClient.java:269-276 — Signal delivers group-mention inbound messages to Provider but `send`/`setTyping` reject `ScopeRef.Group` as PERMANENT, so every reply to a Signal group mention fails unconditionally.
- [MEDIUM] SECURITY — SignalMessageCodec.java:97, 111 — `decode()` interpolates the raw inbound line into exception messages, violating security.md §User content in exceptions and the module's own SimpleX fixed-message remediation.
- [MEDIUM] SIMPLIFICATION — SimpleXConfig.java:32-34, SignalConfig.java:27-29 — `@ApplicationScoped @Startup @PostConstruct` machinery on the config classes is dormant (the jar is never indexed; Provider constructs them with `new`) and would break non-simplex/non-signal deployments if ever discovered.
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — SignalAdapter.java:177-237 — `start()` throws `IllegalStateException` for transport failures the SPI contract (and the SimpleX twin) report as categorised `MessagingException`.
- [LOW] MAINTAINABILITY-RULES-DRIFT — SimpleXIdentity.java:28-31, SignalIdentity.java:28-31 — `resolve(Path)` stubs throw `UnsupportedOperationException` citing tickets that are already done and chose a different mechanism; no caller exists.
- [LOW] MAINTAINABILITY-RULES-DRIFT — SimpleXWebSocketClient.java:188-191 vs SignalJsonRpcClient.java:329-333 — The same condition (interrupted while awaiting an ack) classifies TRANSIENT on SimpleX and PERMANENT on Signal, the exact cross-adapter drift `AdapterCapabilityContractTest` exists to prevent.

## Detail

### F1. Synchronous inbound dispatch on the transport read thread self-deadlocks the request→reply cycle

- **Category:** PERFORMANCE
- **Severity:** critical
- **Location:** SimpleXWebSocketClient.java:260-327 (onText → dispatch), SimpleXAdapter.java:347-363 (onInbound), SignalJsonRpcClient.java:514-553 (dispatchNotification)

**Current code:**

```java
// SimpleXWebSocketClient.Listener.onText — dispatch BEFORE request(1):
buffer.append(data);
if (last) {
    String frame = buffer.toString();
    buffer.setLength(0);
    dispatch(frame);
}
webSocket.request(1);
return null;
```

```java
// SimpleXAdapter.onInbound — Provider handler invoked on the listener thread:
try {
    current.onMessage(msg);
} catch (RuntimeException e) { ... }
```

```java
// SignalJsonRpcClient.dispatchNotification — Provider handler invoked on the
// single signal-jsonrpc-reader thread:
try {
    handler.onMessage(inbound);
} catch (RuntimeException e) { ... }
```

**Why this is wrong / suboptimal / risky:**

Provider registers a synchronous handler (`adapter.setInboundHandler(msg -> inboundRouter.onMessage(msg, adapterName))` in `AdapterRegistry`), and `InboundRouter.onMessage` replies from inside the handler via `adapter.send(...)`. Both production adapters then deadlock structurally:

- **SimpleX:** `dispatch(frame)` runs inside `Listener.onText`, before `webSocket.request(1)` and before `onText` returns. The JDK WebSocket invokes listener methods sequentially and delivers no further frames until the previous invocation completes and demand exists. `send()` → `sendCommand()` blocks on an ack future that can only be completed by the *next* `onText` — which cannot run. Every inbound-triggered reply stalls the full `ACK_TIMEOUT` (30 s) and then fails TRANSIENT. The ack frame is then matched against a removed `pending` entry and dropped.
- **Signal:** `handler.onMessage` runs on the single `signal-jsonrpc-reader` thread. A reply calls `call()`, which blocks on a future only that same reader thread can complete. Every reply times out after 15 s — and worse, `recordTimeout()` escalates **three** consecutive timeouts into `restartHung()`, which SIGKILLs a perfectly healthy daemon and burns one of the five `maxRestarts` per cycle.

The in-memory adapter masks the defect (its `send()` needs no dispatch thread), so every unit test and IT passes while the core request→reply flow is broken on both production transports. No test exercises inbound → reply through a real wire client (the M1-109 IT drives handlers directly via `InboundContext`).

**Recommended fix:**

```java
// SimpleXAdapter.onInbound (same shape in SignalJsonRpcClient.dispatchNotification
// and dispatchGroupNotification):
private void onInbound(InboundMessage msg) {
    InboundHandler current = inboundHandler;
    if (current == null) {
        LOG.debug("dropping inbound; no handler registered");
        return;
    }
    // Hand off to a virtual thread so the WS listener / JSON-RPC reader thread
    // stays free to deliver acks for replies the handler sends. Blocking
    // Provider work on the transport's only read thread deadlocks the
    // request→reply cycle.
    Thread.ofVirtual().name("simplex-inbound-dispatch").start(() -> {
        try {
            current.onMessage(msg);
        } catch (RuntimeException e) {
            LOG.warn("inbound handler threw: {}", e.getClass().getSimpleName());
        }
    });
}
```

**Reasoning:**

The transport read thread must never be borrowed for Provider-side work: it is the only thread that can complete ack futures, so any blocking call from the handler back into the same adapter is a self-deadlock. A per-message virtual thread is the natural JDK-25 + Quarkus blocking-style answer (CLAUDE.md §Stack) and is what `SimpleXSubprocess` already does for its drains. This also removes the back-pressure coupling where one slow LLM-backed reply freezes all inbound delivery for the adapter.

**Trade-offs:**

Per-message virtual threads drop the implicit ordering guarantee between messages from the same sender. If strict per-scope ordering matters to Provider, use a single dedicated dispatcher virtual thread fed by an unbounded queue instead — same decoupling, preserved order, one more moving part.

**Alternative options:**

- **Option A** (the recommended fix above) — simplest, unordered.
- **Option B** — one dispatcher virtual thread + `LinkedBlockingQueue<InboundMessage>` per adapter — pros: preserves arrival order; cons: a slow handler still serialises all inbound for that adapter (but no longer blocks acks, which is the critical property).

---

### F2. Signal reader thread dies permanently on structurally-malformed frames

- **Category:** SECURITY
- **Severity:** high
- **Location:** SignalMessageCodec.java:99-121 (decode), SignalMessageCodec.java:153-155 (extractDm), SignalJsonRpcClient.java:474-492 (handleLine), SignalJsonRpcClient.java:386-393 (extractLong)

**Current code:**

```java
// decode(): only readObject is guarded; the typed accessors below are not.
String method = obj.getString("method", null);
if (method != null) {
    JsonObject params = obj.getJsonObject("params");   // CCE if params is an array
    ...
}
...
if (obj.containsKey("error")) {
    JsonObject err = obj.getJsonObject("error");        // CCE if error is a string
```

```java
// extractDm(): NPE when neither envelope nor dataMessage carries timestamp.
long timestamp = envelope.containsKey("timestamp")
        ? envelope.getJsonNumber("timestamp").longValueExact()
        : dataMessage.getJsonNumber("timestamp").longValueExact();
```

```java
// handleLine(): only IllegalArgumentException is caught …
} catch (IllegalArgumentException e) {
    LOG.warnf("ignoring malformed inbound JSON-RPC line (parse failure: %s)", ...);
    return;
}
```

```java
// readerLoop(): … and only IOException here. Anything else kills the thread.
} catch (IOException e) {
    LOG.debugf("signal-cli reader loop exited: %s", e.getMessage());
}
```

**Why this is wrong / suboptimal / risky:**

The daemon socket is a system boundary this module already treats as hostile-capable (the `MAX_INBOUND_LINE_CHARS` javadoc defends against "a buggy / compromised peer on the loopback daemon port"). But the shape validation is incomplete: a single line such as `{"jsonrpc":"2.0","method":"receive","params":[]}` (array params → `ClassCastException`), `{"id":"1","error":"x"}` (string error → CCE), or a dataMessage carrying `message` but no `timestamp` (NPE at extractDm:155) throws an unchecked exception that is neither an `IllegalArgumentException` (handleLine's catch) nor an `IOException` (readerLoop's catch). The reader thread dies silently; the adapter is permanently deaf while the subprocess stays alive. Recovery never happens — the hung-daemon escalation only restarts the *process*, never the dead reader (see F5). The module spent two remediation tickets making this reader survive handler exceptions and oversize lines; this hole defeats both.

`extractLong` in `SignalJsonRpcClient` has the sibling problem on the outbound side: `obj.getJsonNumber("timestamp")` on a non-numeric value throws CCE out of `send()`, violating the SPI contract that transport faults surface as categorised `MessagingException`.

**Recommended fix:**

```java
// SignalJsonRpcClient.handleLine — catch the whole malformed-shape class:
try {
    msg = codec.decode(line);
} catch (RuntimeException e) {
    // Trust boundary: any structurally-unexpected frame must be dropped, not
    // allowed to kill the reader. D37: class name only.
    LOG.warnf("ignoring malformed inbound JSON-RPC line (parse failure: %s)",
            e.getClass().getSimpleName());
    return;
}
```

Plus type-guarded reads in `decode`/`extractDm` (mirror the existing `firstTextual` pattern: check `getValueType()` before casting; return `Optional.empty()` / throw the codec's own `IllegalArgumentException` with a fixed message on mismatch), and in `extractLong`:

```java
private static long extractLong(JsonObject obj, String key, String method) throws MessagingException {
    JsonValue v = obj.get(key);
    if (!(v instanceof JsonNumber n)) {
        throw new MessagingException(FailureCategory.PERMANENT,
                method + " response missing or non-numeric required field: " + key);
    }
    return n.longValueExact();
}
```

Also catch `RuntimeException` around `dispatchNotification`'s `codec.extractDm(...)` call or move extraction inside the existing try.

**Reasoning:**

Validation at the adapter-inbound boundary is exactly what engineering rules §7 calls for; the current code validates some shapes and lets others escape as raw unchecked exceptions. Catching `RuntimeException` at the single dispatch boundary makes the reader's survival invariant total, matching the SimpleX side (`MalformedFrameException` discipline) and the module's own stated threat model.

**Trade-offs:**

None — the fix is strictly better. The broad catch sits at a documented system boundary, so it is not §7 scope-drift.

---

### F3. Concurrent `sendText` collisions are silently swallowed; the JDK single-outstanding-send constraint is unhandled

- **Category:** PERFORMANCE
- **Severity:** high
- **Location:** SimpleXWebSocketClient.java:180-221 (sendCommand), SimpleXWebSocketClient.java:228-242 (sendFireAndForget)

**Current code:**

```java
// The send's own CompletableFuture is intentionally not awaited —
// a send failure surfaces as the ack future timing out below, which
// the caller already handles as TRANSIENT.
var unused = ws.sendText(envelopeJson, true);
return future.get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
```

**Why this is wrong / suboptimal / risky:**

`java.net.http.WebSocket` permits **one** outstanding send operation at a time; a `sendText` invoked while a previous send is pending returns a `CompletableFuture` that completes exceptionally with `IllegalStateException`. The adapter advertises `maxInflightSends = 4` (SimpleXAdapter.java:74) and `sendCommand` is callable concurrently (scheduled digests, multiple user replies on virtual threads), so two overlapping sends are a normal event, not a race-window curiosity. When it happens:

1. the second frame is **never transmitted** — the failure lives in the discarded future;
2. the caller blocks the full 30 s `ACK_TIMEOUT`, then reports TRANSIENT;
3. the Provider retry can collide again, compounding the stall.

`sendFireAndForget` (typing pulses) collides with `sendCommand` the same way. The comment's claim that "a send failure surfaces as the ack future timing out" is true but turns a fail-fast, retryable condition into a 30-second silent stall. (The existing `catch (RuntimeException)` branch and `ThrowingWebSocket` test cover a synchronous throw, which is not how the JDK reports this condition.)

**Recommended fix:**

```java
private final Object sendLock = new Object();

// in sendCommand (and sendFireAndForget):
try {
    synchronized (sendLock) {
        ws.sendText(envelopeJson, true).join();   // serialise; surface failures now
    }
} catch (CompletionException e) {
    pending.remove(corrId);
    throw new MessagingException(FailureCategory.TRANSIENT,
            "WebSocket send for corrId=" + corrId + " failed: "
                    + e.getCause().getClass().getSimpleName(), e.getCause());
}
return future.get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
```

**Reasoning:**

Serialising the transmit (not the ack wait) honours the JDK constraint while keeping concurrent commands in flight at the protocol level — the corrId demultiplexer already supports that. Joining the send future converts "silently lost frame + 30 s stall" into an immediate, correctly-categorised TRANSIENT failure. Blocking in `join()` is fine on virtual threads (project stack).

**Trade-offs:**

Transmissions serialise behind the lock; with `maxMessageBytes = 2000` on a loopback socket this is microseconds and irrelevant next to the 30 s failure mode it removes.

---

### F4. Bot mention is not stripped from delivered group-message text (spec-drift, breaks group commands)

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** SimpleXGroupHandler.java:71-85, SignalGroupHandler.java:138-167; contract claims at InboundMessage.java:19-22

**Current code:**

```java
// SimpleXGroupHandler.onGroupCandidate — body delivered verbatim:
InboundMessage msg = new InboundMessage(
        sender,
        new ScopeRef.Group(gc.adapterGroupId()),
        gc.text(),
        now,
        gc.adapterMessageId());
inboundHandler.onMessage(msg);
```

```java
// SignalGroupHandler.handleReceive — body delivered verbatim, mention
// spans (start/length) from dataMessage.mentions ignored:
InboundMessage inbound = new InboundMessage(
        sender,
        new ScopeRef.Group(groupId),
        body,
        Instant.ofEpochMilli(timestamp),
        "signal-" + timestamp);
handler.onMessage(inbound);
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/messaging.md` §Required SPI surface — Receive: "the mention is stripped before delivery." `docs/design/06-messaging.md` §6.3.3 resolves the adapter-vs-Provider question: "The adapter MUST strip the recognized mention payload (and the spans of the message body it covers) from the delivered text so the parser sees the user's actual command/message," with the worked example `"@infochat-bot /summary tech"` → `"/summary tech"`. `InboundMessage`'s own javadoc asserts "The text is already mention-stripped."

Neither group handler strips anything, and no Provider-side stripping exists (verified: no mention-strip code in `infochat-provider/src/main/java`). A group mention `"@bot /summary tech"` therefore reaches the command parser with a leading `"@bot "` and will not match any slash command — group mode is functionally broken for its primary use case. The tests pin the wrong behavior (`SimpleXGroupHandlerTest:64` asserts `"hi @bot"` delivered verbatim; `SignalGroupHandlerTest:61` asserts `"@bot summarise this"`), so the drift is enforced rather than caught.

**Recommended fix:**

Signal carries explicit spans — strip them where the mention's uuid matches the bot ACI:

```java
// SignalGroupHandler: after botMentioned() confirms, remove the bot-mention
// spans (and one trailing space) before delivery. Spans are (start, length)
// indices into the body per signal-cli's mentions records.
static String stripBotMentions(String body, JsonArray mentions, String botAciLower) {
    StringBuilder out = new StringBuilder(body);
    // Walk in reverse so earlier offsets stay valid after each removal.
    for (int i = mentions.size() - 1; i >= 0; i--) {
        if (!(mentions.get(i) instanceof JsonObject m)) continue;
        String uuid = m.getString("uuid", null);
        if (uuid == null || !uuid.toLowerCase(Locale.ROOT).equals(botAciLower)) continue;
        int start = m.getInt("start", -1);
        int length = m.getInt("length", -1);
        if (start < 0 || length < 0 || start + length > out.length()) continue;
        int end = start + length;
        if (end < out.length() && out.charAt(end) == ' ') end++;  // design §6.3.3 step 3
        out.delete(start, end);
    }
    return out.toString();
}
```

For SimpleX, rebuild the delivered text from the `formattedText` segments, omitting segments whose `format.type == "mention"` and `memberRef` byte-equals the bot's queue address (the codec already walks this array; carry the segment `text` fields alongside `memberRef` in `GroupCandidate`). Update the two pinned test assertions to the stripped text — authorized by the spec text quoted above.

**Reasoning:**

This restores the spec/design contract and makes group commands parse. The Signal fix is mechanical because the protocol hands over exact spans; the SimpleX fix needs the codec to surface segment text, which is a small extension of the existing `extractMentionQueueAddresses` walk.

**Trade-offs:**

SimpleX requires reconstructing the body from `formattedText` segments rather than using the flat `text` field, which must be validated against a live simplex-chat (segment concatenation == body). Until then, an interim Provider-side prefix-strip would be a workaround the design explicitly assigns to the adapter — prefer the adapter fix.

---

### F5. Process supervisors restart the child but nothing reconnects the transport client; javadocs describe a mechanism that does not exist

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** SimpleXSubprocess.java:27-31 (javadoc), SimpleXWebSocketClient.java:33-39 (javadoc), SimpleXAdapter.java:171-235 (start), SignalSubprocess.java:211-235 (doRestart), SignalJsonRpcClient.java:189-215 (disconnect)

**Current code:**

```java
// SimpleXSubprocess class javadoc:
 * <p>The subprocess and the WebSocket connection it serves form one
 * supervised unit ({@code docs/design/06-messaging.md} §6.4.6). This class
 * owns the process; the adapter rebuilds {@link SimpleXWebSocketClient}
 * after the supervisor reports each restart.
```

```java
// SimpleXWebSocketClient class javadoc:
 * <p>Reconnect is intentionally not owned by this class. … {@link SimpleXSubprocess}
 * restarts the process on crash, and the adapter then re-runs
 * {@link #start} from scratch.
```

**Why this is wrong / suboptimal / risky:**

There is no "supervisor reports each restart" channel. `SimpleXSubprocess` exposes only `state()` / `restartCount()` / the FAILED-transition `adminNotifier`; nothing in this module (or in Provider — verified, no production consumer of `restartCount`/`state()`) re-runs `SimpleXAdapter.start()` or rebuilds the `SimpleXWebSocketClient`. After the first simplex-chat crash the supervisor dutifully restarts the process, but the old WebSocket is dead (`onClose`/`onError` drained all pending futures), `SimpleXAdapter.webSocket` still points at the corpse, and **every subsequent send fails until the JVM restarts** — while the supervisor reports a healthy RUNNING child. Signal is identical: `doRestart()`/`restartHung()` respawn the daemon, but `SignalJsonRpcClient`'s socket and reader thread are gone and nothing reconnects (compounding F2's dead-reader hole — the "hung daemon" escalation restarts the process into an adapter that can no longer hear it). Design §6.4.6's "one supervised unit" and the design's own verification list ("Reconnection: simulated transport disconnect → adapter reconnects, queued outbounds eventually deliver") are both unimplemented, and two class javadocs assert behavior that is false — the worst kind of comment debt, because the next reader will assume recovery exists.

**Recommended fix:**

Give each subprocess an `onRestarted` callback and let the adapter own client rebuild:

```java
// SimpleXSubprocess: new constructor param
private final Runnable onRestarted;
// in runSupervisor(), after a successful relaunch:
currentProcess = process;
state.set(State.RUNNING);
onRestarted.run();
```

```java
// SimpleXAdapter.start(): pass a rebuild hook
SimpleXSubprocess sub = new SimpleXSubprocess(..., this::rebuildWebSocket, ...);

private synchronized void rebuildWebSocket() {
    SimpleXWebSocketClient old = webSocket;
    if (old != null) old.close();
    try {
        waitForWebSocketReady(cfg.wsPort());
        SimpleXWebSocketClient ws = new SimpleXWebSocketClient(uri, http, this::onInbound,
                groupHandler::onGroupCandidate);
        ws.start();
        this.webSocket = ws;
    } catch (MessagingException e) {
        LOG.warn("WebSocket rebuild after subprocess restart failed: {}", e.category());
        // next supervisor restart cycle (or FAILED transition) handles escalation
    }
}
```

Mirror for Signal (`SignalSubprocess.spawn()` success → adapter `reconnect()` that calls `client.disconnect()` + new `SignalJsonRpcClient(...).connect()` + `attachClient`). At minimum, if reconnection is deliberately deferred to a future ticket, the two javadocs must be corrected now — they currently document fiction.

**Reasoning:**

The supervisor already knows the exact moment a restart succeeds; a callback is the smallest honest channel. The adapter owns both halves of the supervised unit, matching the design's intent and the existing teardown symmetry (`close()` tears both down together).

**Trade-offs:**

The rebuild hook runs on the supervisor thread, so it must not block restart accounting for long — the bounded `WS_READY_TIMEOUT` (10 s) is acceptable. Concurrency between `rebuildWebSocket` and in-flight `send()` calls needs the same `volatile` discipline already used (`requireConnected` re-reads the field per call; stale-client sends fail and are retried).

---

### F6. Signal group inbound is delivered while group outbound is rejected PERMANENT

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** SignalJsonRpcClient.java:269-276 (recipientFromDmScope), SignalJsonRpcClient.java:245-250 (setTyping), versus SignalGroupHandler.java:161-167 (group delivery)

**Current code:**

```java
private String recipientFromDmScope(ScopeRef scope, String method) throws MessagingException {
    if (!(scope instanceof ScopeRef.Dm dm)) {
        throw new MessagingException(
                FailureCategory.PERMANENT,
                method + ": group scope not supported in M1-107 (lands in M1-108)");
    }
    return dm.contactId();
}
```

**Why this is wrong / suboptimal / risky:**

The group *inbound* path is live: `SignalGroupHandler` delivers group-mention messages with `ScopeRef.Group` to Provider, `SignalAdapter` declares `supportsMentionByContactId = true` / `supportsMembershipEvents = true`, and `SignalGroupEndToEndTest` proves the production wiring. But every reply Provider sends back to that group scope hits `recipientFromDmScope` and dies PERMANENT — and PERMANENT replies are exactly the signal spec §Failure handling escalates toward group cleanup ("repeated permanent send failures past a profile-driven threshold" → `groups.removed_at`). So a working Signal group would never receive a reply and could eventually be soft-removed by Provider's own permanent-failure bookkeeping. The half-shipped state is worse than either complete state: deliver-and-reply, or don't deliver. The "lands in M1-108" comment is stale — M1-108's group receive path is in the tree; the send half never landed.

**Recommended fix:**

Implement group send in the codec/client (signal-cli's `send` accepts `groupId` instead of `recipient`):

```java
// SignalMessageCodec
String encodeGroupSend(long rpcId, String account, String groupId, String message) {
    JsonObject params = Json.createObjectBuilder()
            .add("account", account)
            .add("groupId", groupId)
            .add("message", message)
            .build();
    return encodeRequest(rpcId, "send", params);
}
```

and branch in `SignalJsonRpcClient.send` on the sealed `ScopeRef` (switch expression — both cases handled, no rejection path needed). `updateMessage`/`sendTyping` get the same `groupId` form. If group send genuinely cannot land yet, the honest interim is to suppress group *delivery* in `SignalAdapter.attachClient` (don't wire the group route) so Provider never sees a scope it cannot answer.

**Reasoning:**

The sealed `ScopeRef` exists precisely so adapters handle both scopes exhaustively; SimpleX already does (`targetSelector` handles `Dm` and `Group`). Completing the Signal send path removes the asymmetry and the false-PERMANENT signal feeding Provider's group-cleanup heuristics.

**Trade-offs:**

The exact `groupId` param spelling must be verified against a live signal-cli (same caveat the module already carries for other wire details). The suppress-delivery interim loses Signal group mode entirely — acceptable only as an explicit, short-lived state.

---

### F7. Raw inbound frame bytes interpolated into exception messages in the Signal codec

- **Category:** SECURITY
- **Severity:** medium
- **Location:** SignalMessageCodec.java:97, SignalMessageCodec.java:111

**Current code:**

```java
} catch (RuntimeException e) {
    throw new IllegalArgumentException("Malformed JSON-RPC envelope: " + line, e);
}
...
if (id == null) {
    throw new IllegalArgumentException("JSON-RPC envelope missing both method and id: " + line);
}
```

**Why this is wrong / suboptimal / risky:**

security.md §User content in exceptions: "Exception messages and stack traces emitted via the application logger MUST NOT contain user-authored prose (chat-mode message bodies, …)." The daemon line routinely carries inbound chat-mode bodies. The current log site (`handleLine`) prints only the class name, but the exception object itself carries the full frame — one future log statement, one `SafeLog` bypass, or one test failure dump away from leaking message bodies into application logs. The SimpleX codec was explicitly remediated to the fixed-message contract for this exact reason (M1-119; `malformedFrameExceptionHasFixedMessage` pins `"frame is not JSON"` with no interpolation, and the in-code comment states "the structural fix is to keep the bytes out of the exception in the first place"). The Signal codec contradicts its sibling and the spec.

**Recommended fix:**

```java
} catch (RuntimeException e) {
    // Fixed message only — the line may carry chat-mode bodies
    // (security.md §User content in exceptions). Cause is dropped for the
    // same reason: jakarta.json parse exceptions embed input fragments.
    throw new IllegalArgumentException("malformed JSON-RPC envelope");
}
...
throw new IllegalArgumentException("JSON-RPC envelope missing both method and id");
```

**Reasoning:**

Identical to the accepted SimpleX remediation: keep the bytes out of the `Throwable` structurally rather than relying on every log site forever doing class-name-only logging. Note the wrapped `cause` (jakarta.json's `JsonParsingException`) also embeds input fragments, so drop it or wrap with class name only.

**Trade-offs:**

Loses parse-failure detail for debugging; the corrId/line-length could be logged separately at DEBUG if ever needed. The module already accepted this trade on the SimpleX side.

---

### F8. Dormant CDI eager-bean machinery on SimpleXConfig / SignalConfig

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** SimpleXConfig.java:32-34, 52-58, 86-87; SignalConfig.java:27-29, 44-47, 70

**Current code:**

```java
@ApplicationScoped
@Startup
public class SimpleXConfig {
    ...
    @Inject
    public SimpleXConfig(@ConfigProperty(name = BINARY_KEY) @NonNull String binary,
                         @ConfigProperty(name = DATA_DIR_KEY) @NonNull String dataDir,
                         @ConfigProperty(name = WS_PORT_KEY, defaultValue = "" + DEFAULT_WS_PORT) int wsPort) {
    ...
    @PostConstruct
    public void validate() {
```

**Why this is wrong / suboptimal / risky:**

The CDI path is dead: Provider's `quarkus.index-dependency` entries cover only `infochat-llm-adapter` and `infochat-core`, so neither config bean is ever discovered; `ProductionAdapterBeans` constructs both with `new` from its own `@ConfigProperty` injections, and `SimpleXAdapter.start()` calls `validate()` explicitly. Three concrete costs:

1. **Latent trap.** If the jar is ever indexed (the javadoc invites it: "making the Provider discover this bean … is Provider-side wiring"), `@Startup` + no-default `@ConfigProperty(BINARY_KEY)` fails startup of *every* deployment — including inmemory-only and signal-only — directly contradicting `SimpleXAdapter.start()`'s comment "Runs only for activated adapters so an inmemory-only deployment never trips simplex's checks."
2. **Double validation** in the live wiring (`@PostConstruct` claim + explicit `start()` call) with two contradictory in-code stories about when validation runs.
3. The `quarkus-arc` dependency in the module pom exists chiefly to carry these dead annotations.

**Recommended fix:**

```java
// Plain value class; Provider constructs and the adapter validates at start():
public class SimpleXConfig {
    public static final String BINARY_KEY = "infochat.adapters.simplex.binary";
    ...
    public SimpleXConfig(String binary, String dataDir, int wsPort) { ... }

    public void validate() { ... }   // unchanged body, no @PostConstruct
}
```

Same for `SignalConfig` (which additionally has no public getters, so it could never have served an injected consumer). Drop `@ApplicationScoped`, `@Startup`, `@Inject`, `@PostConstruct`; re-evaluate whether `quarkus-arc` is still needed by the module.

**Reasoning:**

The activated-adapter validation model (validate inside `start()`, per-adapter isolation in Provider's startup driver) won; the eager-bean model lost but its skeleton remained. Removing it deletes the discovery trap, the duplicate-validation ambiguity, and a per-class CDI dependency — pure simplification with no behavior change in any current deployment.

**Trade-offs:**

None — the fix is strictly better. (`SimpleXConfigTest`/`SignalConfigTest` call `validate()` directly and keep passing.)

---

### F9. SignalAdapter.start() throws IllegalStateException for transport failures the SPI reports as MessagingException

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** SignalAdapter.java:208-234

**Current code:**

```java
try {
    sp.start();
} catch (IOException e) {
    throw new IllegalStateException("Failed to start signal-cli subprocess", e);
}
this.subprocess = sp;
if (!awaitEndpoint(daemonEndpoint, ENDPOINT_PROBE_TIMEOUT)) {
    sp.stop();
    this.subprocess = null;
    throw new IllegalStateException(
            "signal-cli daemon endpoint " + daemonEndpoint + " not reachable within "
                    + ENDPOINT_PROBE_TIMEOUT);
}
...
} catch (IOException e) {
    sp.stop();
    this.subprocess = null;
    throw new IllegalStateException("Failed to connect SignalJsonRpcClient", e);
}
```

**Why this is wrong / suboptimal / risky:**

`MessagingAdapter.start()` declares `@throws MessagingException on transport startup failure`, and `SimpleXAdapter.start()` honours it — its launch/readiness/connect failures surface as categorised `MessagingException` (`waitForWebSocketReady` → TRANSIENT, etc.). Signal reports the same three environmental conditions (subprocess launch failure, endpoint never reachable, connect refused) as `IllegalStateException`, which the codebase otherwise reserves for caller bugs (wrong constructor, blank ACI). Provider's per-adapter startup isolation must catch broad `RuntimeException` to absorb Signal where catching `MessagingException` would suffice for SimpleX, and any future categorised handling (transient startup → retry) is impossible for Signal. The two adapters' divergence is exactly what `AdapterCapabilityContractTest` calls "the drift this module accumulated."

**Recommended fix:**

```java
try {
    sp.start();
} catch (IOException e) {
    throw new MessagingException(FailureCategory.TRANSIENT,
            "Failed to start signal-cli subprocess", e);
}
this.subprocess = sp;
if (!awaitEndpoint(daemonEndpoint, ENDPOINT_PROBE_TIMEOUT)) {
    sp.stop();
    this.subprocess = null;
    throw new MessagingException(FailureCategory.TRANSIENT,
            "signal-cli daemon endpoint " + daemonEndpoint + " not reachable within "
                    + ENDPOINT_PROBE_TIMEOUT);
}
```

(`start()` already declares no checked exception override — add `throws MessagingException` to the override signature; the interface permits it.) Keep `IllegalStateException` for the two genuine caller-bug guards (capability-only constructor, blank ACI), matching SimpleX's split.

**Reasoning:**

Restores the SPI contract and cross-adapter symmetry; environmental failures become categorised and machine-routable, caller bugs stay loud.

**Trade-offs:**

Provider's startup catch block may need its catch type adjusted (it must already handle `MessagingException` for SimpleX, so this likely shrinks, not grows, the handled surface).

---

### F10. Dead `resolve(Path)` stubs with javadoc citing completed tickets that chose a different mechanism

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** SimpleXIdentity.java:28-31, SignalIdentity.java:28-31; stale reference at SignalAdapter.java:129-133

**Current code:**

```java
public static SimpleXIdentity resolve(@NonNull Path dataDir) {
    throw new UnsupportedOperationException(
            "resolving the bot queue address from simplex-chat data is implemented in M1-103");
}
```

```java
public static SignalIdentity resolve(@NonNull Path dataDir) {
    throw new UnsupportedOperationException(
            "resolving the bot ACI from signal-cli account state is implemented in M1-107");
}
```

**Why this is wrong / suboptimal / risky:**

M1-103 and M1-107 landed (the adapters they describe are in the tree) and the chosen identity mechanism is the operator-supplied config property — `ProductionAdapterBeans` constructs `new SimpleXIdentity(simplexBotQueueAddress.orElse(""))` and passes the configured `botAci` string; no production or test code calls either `resolve`. The stubs are throw-only dead public API whose javadoc promises a future that already arrived differently, and `SignalAdapter`'s constructor javadoc still claims the ACI is "Resolved by Provider-side wiring … via {@link SignalIdentity#resolve}", which is false. A reader (or a future wiring ticket) will be misled into the data-dir-parsing path the project abandoned.

**Recommended fix:**

```java
/** The bot's SimpleX identity — its queue address … (operator-configured;
 *  see ProductionAdapterBeans / infochat.adapters.simplex.bot-queue-address). */
public record SimpleXIdentity(@NonNull String queueAddress) {
}
```

Delete both `resolve` methods and correct the `SignalAdapter` constructor javadoc to name the config property instead.

**Reasoning:**

Dead, never-callable public API on a module's exported surface is a standing hazard; the records' value is purely the typed carrier, which survives intact.

**Trade-offs:**

None — the fix is strictly better. (No caller exists to break; verified by grep across all modules.)

---

### F11. Interrupted-await classification drifts between adapters (TRANSIENT vs PERMANENT)

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** SimpleXWebSocketClient.java:188-191 versus SignalJsonRpcClient.java:329-333

**Current code:**

```java
// SimpleX:
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new MessagingException(FailureCategory.TRANSIENT,
            "interrupted while awaiting ack for corrId=" + corrId, e);
```

```java
// Signal:
} catch (InterruptedException e) {
    pending.remove(id);
    Thread.currentThread().interrupt();
    throw new MessagingException(
            FailureCategory.PERMANENT, "Interrupted awaiting JSON-RPC response", e);
```

**Why this is wrong / suboptimal / risky:**

The same semantic state — caller interrupted while awaiting the transport ack — classifies TRANSIENT on SimpleX and PERMANENT on Signal. `AdapterCapabilityContractTest` was created precisely to pin "the same semantic state must classify identically across adapters" after the not-connected drift; this is the same drift class, unpinned. An interrupt almost always means shutdown, so PERMANENT (abort, don't re-enqueue a retry during teardown) is the right shared answer.

**Recommended fix:**

```java
// SimpleXWebSocketClient.sendCommand:
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new MessagingException(FailureCategory.PERMANENT,
            "interrupted while awaiting ack for corrId=" + corrId, e);
}
```

Add the case to `AdapterCapabilityContractTest`'s cross-adapter contract so it cannot re-drift.

**Reasoning:**

Interruption during shutdown should abort the reply, not feed the retry layer another attempt against a closing transport — and one answer across adapters is the module's own stated contract.

**Trade-offs:**

A non-shutdown interrupt (none exist in the current call graph) would lose its retry; given interrupts are only generated by `stop()`/`disconnect()` paths today, this is theoretical.

---

## Synthesizer-relevant observations (cross-module, not findings here)

- Provider's `AdapterRegistry` wires `setInboundHandler(msg -> inboundRouter.onMessage(msg, adapterName))` synchronously with no offload; whichever side fixes F1, the other side's assumption should be documented in the SPI javadoc (architecture lens: SPI threading contract is currently unstated).
- `ProductionAdapterBeans` constructs `new SimpleXIdentity(simplexBotQueueAddress.orElse(""))` — the blank-default is only caught later inside `SimpleXAdapter.start()`; the config gate and the adapter guard duplicate one validation across modules.
- §7a says `@NonNull` is no longer written by hand, yet ~275 hand-written `@NonNull` occurrences persist across all modules (20 in this one); a codebase-wide sweep decision belongs to the architecture/process level, not one module.
