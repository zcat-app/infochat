# Deep code review: module infochat-messaging-adapter

**Target:** module infochat-messaging-adapter
**Lens:** module
**Module path:** infochat-messaging-adapter/
**Date:** 2026-06-06 14:20
**Reviewer:** senior-developer (opus)

## Headline findings

- [critical] PERFORMANCE — SimpleXWebSocketClient.java:260-286 / SignalJsonRpcClient.java:514-553 — inbound messages are dispatched to Provider on the same single thread that reads transport responses, so any reply sent during `onMessage` deadlocks against its own ack.
- [high] PERFORMANCE — SignalJsonRpcClient.java:217-243 — the Signal open-handle registry is only evicted on `finalizeMessage`, so every fire-once reply (the common case) leaks a handle forever; SimpleX bounds the same map with an LRU, Signal does not.
- [high] MAINTAINABILITY-RULES-DRIFT — SignalGroupHandler.java:159-167 / SimpleXGroupHandler.java:76-84 — group adapters deliver the message body with the bot `@mention` span still in it, violating design §6.3.3 / §6.10 step 3 (and a test pins the un-stripped text).
- [medium] SECURITY — SignalJsonRpcClient.java:514-540 — the DM notification path lacks the reader-survival guard the group path has; an exception thrown inside `extractDm` or inbound construction escapes the reader loop and kills the reader thread (adapter goes deaf).
- [medium] MAINTAINABILITY-RULES-DRIFT — SimpleXAdapter.java:64-78 / SignalAdapter.java:72-86 — the `maxInflightSends` / `maxSendsPerSecond` capabilities are advertised but enforced nowhere, and the §6.3.7 bounded inbound queue + throttle reply are not implemented.
- [low] MAINTAINABILITY-RULES-DRIFT — SimpleXIdentity.java:28-31 / SignalIdentity.java:28-31 — `resolve(...)` are stale `UnsupportedOperationException` stubs for tickets that have shipped; the bot mention anchor is sourced elsewhere and these are unreachable.
- [low] SIMPLIFICATION — module-wide (e.g. MessagingAdapter.java:81-150) — hand-written `@NonNull` on every SPI parameter contradicts the stated convention that non-null is the package default and `@NonNull` is no longer written by hand.

## Detail

### F1. Inbound dispatch blocks the transport read thread; replies deadlock against their own ack

- **Category:** PERFORMANCE
- **Severity:** critical
- **Location:** SimpleXWebSocketClient.java:260-286 and :306-327; SignalJsonRpcClient.java:395-441 and :514-553; provider wiring AdapterRegistry.java:282 (cross-module, for confirmation)

**Current code:**

SimpleX — the WebSocket listener runs decode + dispatch inline and only requests the next frame after `onText` returns:

```java
public java.util.concurrent.@Nullable CompletionStage<?> onText(@NonNull WebSocket webSocket,
                                                      @NonNull CharSequence data,
                                                      boolean last) {
    ...
    buffer.append(data);
    if (last) {
        String frame = buffer.toString();
        buffer.setLength(0);
        dispatch(frame);          // -> inboundConsumer.onInbound -> Provider onMessage (synchronous)
    }
    webSocket.request(1);
    return null;
}
```

```java
private void dispatch(String frame) {
    ...
    switch (decoded) {
        case SimpleXMessageCodec.Inbound in -> inboundConsumer.onInbound(in.message());
        ...
        case SimpleXMessageCodec.SendAck ack -> completePending(ack.corrId(), ack.chatItemId());
        case SimpleXMessageCodec.CommandError err -> failPending(err);
        ...
    }
}
```

Signal — the single reader thread decodes and dispatches inline, and is also the only thread that completes pending command futures:

```java
private void readerLoop() {
    ...
    while ((c = r.read()) != -1) {
        ...
        handleLine(sb.toString());   // -> dispatchNotification -> Provider onMessage (synchronous)
    }
}

private void handleLine(String line) {
    ...
    switch (msg) {
        case ... Response r -> completePending(r.id(), r);          // unblocks send()
        case ... Notification n -> dispatchNotification(n);          // runs onMessage here
    }
}
```

Provider wires the handler with no executor hand-off:

```java
adapter.setInboundHandler(msg -> inboundRouter.onMessage(msg, adapterName));
```

**Why this is wrong / suboptimal / risky:**

`InboundRouter.onMessage` is synchronous and, on essentially every path, produces a reply by calling `adapter.send(...)` in the same call stack (e.g. the size-cap path calls `sendReply(...)` → `target.send(...)`, and command/chat handlers do the same). `send()` blocks on the ack:

- SimpleX: `SimpleXAdapter.send` → `ws.sendCommand(corrId, envelope, ACK_TIMEOUT)` → `future.get(ACK_TIMEOUT)`. That future is completed only when the listener reads the `SendAck` frame and calls `completePending`. But the listener thread is *currently inside* `onText` → `dispatch` → `onInbound` → `onMessage` → `send`, and the JDK `HttpClient` WebSocket delivers frames strictly sequentially: it will not invoke `onText` again until the current invocation returns. The ack frame can never be read. The reply blocks for the full 30 s `ACK_TIMEOUT`, then throws `TRANSIENT`.
- Signal: `SignalAdapter.send` → `SignalJsonRpcClient.send` → `call` → `future.get(responseTimeout)` (15 s). The future is completed only by `completePending` on the reader thread — the same thread blocked inside `onMessage` → `send`. The reply times out, `recordTimeout()` increments, and after 3 such timeouts `hungRestartHook.run()` force-kills the signal-cli subprocess. So replies not only fail, they trigger spurious subprocess restarts.

The net effect is that neither production adapter can reliably answer any inbound message: the very act of replying inside the inbound callback starves the thread that must read the reply's acknowledgement. This is a hard deadlock-until-timeout on the primary user-facing path, i.e. the adapter does not work in production. Design §6.4.3 (steps 4 and 5) and §6.3.7 explicitly call for a *separate* event-reader and outbound-queue worker, and for inbound messages to be enqueued onto a bounded queue rather than processed inline on the reader; the implementation collapsed both onto the read thread.

**Recommended fix:**

Hand inbound dispatch off the read thread so the reader stays free to deliver acks. The minimal correct shape is a per-adapter bounded executor (virtual threads fit the JDK-25 target) that runs `onMessage`, leaving the read thread to do nothing but decode + complete pending futures + enqueue:

```java
// SimpleXWebSocketClient (or SimpleXAdapter.onInbound)
private final ExecutorService inboundExecutor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("simplex-inbound-", 0).factory());

case SimpleXMessageCodec.Inbound in ->
        inboundExecutor.execute(() -> inboundConsumer.onInbound(in.message()));
```

```java
// SignalJsonRpcClient.dispatchNotification — run the handler off the reader thread
SignalMessageCodec.ReceivedDm received = dm.get();
inboundExecutor.execute(() -> deliverDm(received));   // build Identity + InboundMessage + onMessage here
```

For a faithful implementation of design §6.3.7, replace the unbounded executor with a bounded queue (default 1000) plus the newest-drop + throttle-reply behavior the spec mandates; that also fixes F5's inbound half.

**Reasoning:**

The deadlock exists purely because the response read and the reply write share one thread. Moving `onMessage` to any other thread breaks the cycle: the read thread returns immediately, the next frame (the ack) is read, `completePending` fires, and `send()` unblocks on the worker thread. Acks and inbound deliveries become independent, which is exactly the reader/worker split the design already prescribes.

**Trade-offs:**

A worker pool introduces concurrency the inline version did not have — `onMessage` invocations may now overlap, so any per-scope ordering or per-user fairness guarantee (design §6.3.7 "per-user-fair scheduler") must be implemented in the queue rather than assumed from single-threaded delivery. That is additional work, but the current single-threaded delivery is non-functional, so there is no working behavior being traded away.

---

### F2. Group adapters do not strip the bot mention span from delivered text

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** SignalGroupHandler.java:159-167; SimpleXGroupHandler.java:76-84; pinned by SignalGroupEndToEndTest.java:83

**Current code:**

```java
// SignalGroupHandler.handleReceive
String body = dataMessage.getString("message", null);
...
if (!SignalMentionParser.botMentioned(dataMessage, botAci)) {
    return;
}
...
InboundMessage inbound = new InboundMessage(
        sender,
        new ScopeRef.Group(groupId),
        body,                       // <-- raw body, mention span NOT removed
        Instant.ofEpochMilli(timestamp),
        "signal-" + timestamp);
handler.onMessage(inbound);
```

```java
// SimpleXGroupHandler.onGroupCandidate
if (!SimpleXMentionParser.botMentioned(gc.mentionQueueAddresses(), botIdentity.queueAddress())) {
    return;
}
...
InboundMessage msg = new InboundMessage(
        sender, new ScopeRef.Group(gc.adapterGroupId()),
        gc.text(),                  // <-- raw text, mention span NOT removed
        now, gc.adapterMessageId());
inboundHandler.onMessage(msg);
```

```java
// SignalGroupEndToEndTest — asserts the WRONG (un-stripped) text
assertEquals("@bot summarise this", msg.text());
```

**Why this is wrong / suboptimal / risky:**

Design §6.3.3 and §6.10 step 3 require the adapter to "strip the recognized mention payload (and the spans of the message body it covers) from the delivered text so the parser sees the user's actual command/message," with the worked example "`@infochat-bot /summary tech` … is delivered as `/summary tech`." Spec messaging.md §Required SPI surface allows the strip to be done by the adapter *or* by Provider — but the mention span data (`start`/`length` for Signal, the matched `format` span for SimpleX) is never propagated to Provider: `InboundMessage` carries only `text`. So if the adapter does not strip, nobody can, and the command parser receives `@bot /summary tech` (Signal keeps the literal mention text; SimpleX keeps the mention substring). Group commands therefore fail to parse, and chat-mode bodies carry a leading mention artefact.

`SignalGroupEndToEndTest` line 83 actively asserts the un-stripped body, so the wrong behavior is locked in by a test (a §8 concern: the test documents behavior that contradicts the spec it should be enforcing).

**Recommended fix:**

Strip the matched mention span(s) before constructing the `InboundMessage`. For Signal the `mentions` entries carry `start`/`length` over the UTF-16 body; remove those ranges (and a single trailing space) after confirming the bot mention:

```java
String stripped = SignalMentionParser.stripMentions(body, dataMessage); // remove all mention spans, collapse trailing space
InboundMessage inbound = new InboundMessage(sender, new ScopeRef.Group(groupId),
        stripped, Instant.ofEpochMilli(timestamp), "signal-" + timestamp);
```

For SimpleX, surface the mention span offsets in `GroupCandidate` (the codec already walks `formattedText`) and apply the same removal in `SimpleXGroupHandler`. Update `SignalGroupEndToEndTest` to assert the stripped text (`"summarise this"`) with a comment authorizing the change against design §6.10 step 3.

**Reasoning:**

Stripping at the adapter is the only place with the structured span data, and the design assigns it there. Doing it correctly makes group command parsing behave identically to DM parsing, which is the whole point of the SPI being transport-neutral.

**Trade-offs:**

Span arithmetic must use the same code-unit basis the transport uses (Signal mention offsets are UTF-16 indices), so the strip helper needs a unit test over multi-byte bodies. Minor implementation cost; no behavioral downside.

---

### F3. Signal open-handle registry leaks one entry per non-finalized send

- **Category:** PERFORMANCE
- **Severity:** high
- **Location:** SignalJsonRpcClient.java:119-127, :217-243

**Current code:**

```java
private final ConcurrentMap<String, SignalMessageHandle> handles = new ConcurrentHashMap<>();
...
MessageHandle send(@NonNull OutboundMessage msg) throws MessagingException {
    ...
    MessageHandle handle = new MessageHandle(OPAQUE_PREFIX + handleSerial);
    handles.put(handle.opaqueValue(), new SignalMessageHandle(timestamp, recipient, msg));
    return handle;
}

void update(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
    SignalMessageHandle internal = lookupOpen(handle);
    editMessage(internal, body);
}

void finalizeHandle(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
    SignalMessageHandle internal = lookupOpen(handle);
    editMessage(internal, body);
    handles.remove(handle.opaqueValue());   // <-- ONLY eviction point
}
```

The bounding rationale comment claims: "the map's size is naturally bounded by the count of in-flight (sent-but-not-yet-finalized) messages."

**Why this is wrong / suboptimal / risky:**

That bound only holds if every `send` is eventually `finalizeMessage`-d. It is not. The overwhelming majority of bot replies are single fire-once sends with no progress lifecycle — `InboundRouter.sendReply` (and every command/error reply) calls `target.send(...)` and never calls `update`/`finalizeMessage` (only the long-running `ProgressNotifier` flow finalizes, and `ProgressNotifier` has zero implementations in v1). So each ordinary reply adds a `SignalMessageHandle` — which retains the full `OutboundMessage` (and its text) — that is never removed. The map grows for the entire lifetime of the connection, which can be days. `disconnect()` clears it, but that only happens on reconnect. This is an unbounded memory leak on the hot reply path.

SimpleX had exactly this problem and fixed it with an access-order LRU capped at `MAX_TRACKED_HANDLES = 1_024` (SimpleXAdapter.java:96-107); the eviction-on-only-finalize design in Signal reintroduces the pre-fix shape.

**Recommended fix:**

Bound the Signal handle map the same way SimpleX does — an access-order LRU keyed by opaque value, where an evicted handle simply behaves like an unknown one (which `lookupOpen` already maps to PERMANENT, the correct outcome for a stale handle):

```java
private static final int MAX_TRACKED_HANDLES = 1_024;
private final Map<String, SignalMessageHandle> handles =
        Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, SignalMessageHandle> e) {
                return size() > MAX_TRACKED_HANDLES;
            }
        });
```

Keep the `finalizeHandle` removal as the fast-path eviction; the LRU is the backstop for never-finalized sends.

**Reasoning:**

A fire-once reply only needs its handle until the (non-existent) follow-up edit; capping at 1024 keeps the hot tail for genuine progress flows while guaranteeing the map cannot grow without bound. This is the exact remediation already validated for SimpleX, so the two adapters converge on one bounding model.

**Trade-offs:**

`LinkedHashMap` access-order is not thread-safe, so the map needs external synchronization (as SimpleX does with its `synchronized (handles)` blocks), slightly heavier than the lock-free `ConcurrentHashMap`. At adapter send rates this is negligible and is the same trade SimpleX already accepts.

---

### F4. Signal DM notification path lacks the reader-survival guard the group path has

- **Category:** SECURITY
- **Severity:** medium
- **Location:** SignalJsonRpcClient.java:514-553 (and SignalMessageCodec.java:153-156)

**Current code:**

```java
private void dispatchNotification(SignalMessageCodec.JsonRpcMessage.Notification n) {
    if (!"receive".equals(n.method())) {
        return;
    }
    Optional<SignalMessageCodec.ReceivedDm> dm = codec.extractDm(n.params());   // <-- not guarded
    if (dm.isEmpty()) {
        dispatchGroupNotification(n.params());   // group path IS guarded (try/catch inside)
        return;
    }
    ...
    SignalMessageCodec.ReceivedDm received = dm.get();
    Identity sender = new Identity(received.senderContactId(), null, Instant.now());
    InboundMessage inbound = new InboundMessage(...);   // <-- construction not guarded
    try {
        handler.onMessage(inbound);                      // <-- only this is guarded
    } catch (RuntimeException e) { ... }
}
```

`extractDm` reads the timestamp like this:

```java
long timestamp = envelope.containsKey("timestamp")
        ? envelope.getJsonNumber("timestamp").longValueExact()
        : dataMessage.getJsonNumber("timestamp").longValueExact();   // NPE if neither present
```

**Why this is wrong / suboptimal / risky:**

`dispatchNotification` is invoked from `handleLine`'s switch, which is *outside* the `try/catch` that guards `codec.decode`. The code goes to great lengths to keep the reader thread alive — the group route wraps `route.accept(...)` in `catch (RuntimeException)` (line 562-569), and the DM handler call is wrapped (line 541-552) — precisely because "a Provider-side handler that throws must NOT kill the signal-jsonrpc-reader thread." But `extractDm` itself and the `Identity`/`InboundMessage` construction are not wrapped. A `receive` notification whose `dataMessage` is present but carries no `timestamp` (neither on the envelope nor the dataMessage) makes `getJsonNumber("timestamp")` return null and `longValueExact()` throw `NullPointerException`; a non-numeric or out-of-range timestamp throws `ClassCastException`/`ArithmeticException`. Any of these escapes `dispatchNotification` → `handleLine` → `readerLoop`, whose only `catch` is `IOException`, so the unchecked exception terminates the reader thread. The subprocess stays alive but the adapter goes permanently deaf (no inbound delivery, no ack completion) until a restart. This is a denial-of-service at the adapter-inbound trust boundary — the one boundary where defensive validation is explicitly appropriate (§7).

The same timestamp pattern in `SignalGroupHandler` (line 156-158) is harmless only because the group route is wrapped; the DM route is the unprotected twin.

**Recommended fix:**

Wrap the whole DM dispatch (extraction + construction + handler call) in the same reader-survival guard the group route uses, and make `extractDm` treat a missing/invalid timestamp as "not a usable DM" (return empty) rather than throwing:

```java
private void dispatchNotification(SignalMessageCodec.JsonRpcMessage.Notification n) {
    if (!"receive".equals(n.method())) return;
    try {
        Optional<SignalMessageCodec.ReceivedDm> dm = codec.extractDm(n.params());
        if (dm.isEmpty()) { dispatchGroupNotification(n.params()); return; }
        deliverDm(dm.get());
    } catch (RuntimeException e) {
        LOG.warnf("Signal receive dispatch threw %s; dropping notification, reader continues",
                e.getClass().getSimpleName());
    }
}
```

**Reasoning:**

The reader thread is the adapter's lifeline; nothing decoded from an external process should be able to terminate it. Extending the existing guard to cover extraction/construction makes the DM path as robust as the group path, and turning a malformed timestamp into a silent drop (matching how every other missing field in `extractDm` is handled) keeps the failure mode consistent.

**Trade-offs:**

None — the fix only widens an existing guard and makes one extraction branch consistent with its siblings.

---

### F5. Declared concurrency/rate caps are never enforced; inbound back-pressure is unimplemented

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** SimpleXAdapter.java:64-78; SignalAdapter.java:72-86; SimpleXWebSocketClient.java:168-221; SignalJsonRpcClient.java:217-228

**Current code:**

```java
// Both adapters declare these, e.g. SimpleXAdapter:
/* maxInflightSends */ 4,
/* maxSendsPerSecond */ 8,
```

`SimpleXAdapter.send` / `SignalJsonRpcClient.send` go straight to the wire with no semaphore and no token bucket; `onInbound` / `dispatchNotification` call the handler inline with no bounded queue and no throttle reply.

**Why this is wrong / suboptimal / risky:**

Design §6.3.6 makes the caps a hard contract: "the adapter MUST NOT have more than that many `send()` calls actively transmitting at once" and "the adapter MUST NOT exceed this many sends per second." Both adapters advertise concrete values for `maxInflightSends` and `maxSendsPerSecond` via `capabilities()`, but no code consults them — they are documentation masquerading as enforced contract. Likewise §6.3.7 requires a bounded inbound queue (default 1000) with newest-drop and a synchronous throttle reply; neither adapter has a queue at all (which is also the root cause of F1). A capability flag that callers may rely on but the adapter does not honor is a latent correctness gap: if Provider ever trusts the adapter to bound outbound rate (the spec says the adapter owns transport back-pressure, surfaced to Provider as transient failures), nothing actually bounds it.

**Recommended fix:**

Either implement the caps or stop advertising them as enforced. The faithful fix is a small outbound governor in each adapter — a `Semaphore(maxInflightSends)` acquired around the wire call plus a token bucket sized to `maxSendsPerSecond` — and the §6.3.7 bounded inbound queue (which the F1 fix should introduce anyway). If enforcement is genuinely deferred to a later ticket, add an explicit `// NOT YET ENFORCED — see M1-xxx` comment on the two capability fields so the gap is visible rather than implied-honored.

**Reasoning:**

A capability is a promise to the caller. Making the promise true (governor) or making the gap explicit (comment) both restore honesty; silently shipping an unenforced cap is the one option that misleads every future reader.

**Trade-offs:**

A real governor adds per-send synchronization and a scheduling component. Given design §6.5.2's own note that the v1 LLM concurrency cap is the binding constraint upstream, the practical urgency is moderate — hence medium, not high — but the advertised-vs-enforced mismatch should not persist undocumented.

---

### F6. Stale `resolve(...)` identity stubs throw for already-shipped tickets and are unreachable

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** SimpleXIdentity.java:28-31; SignalIdentity.java:28-31

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

Both stubs name the ticket that was supposed to implement them (M1-103, M1-107), both of which have shipped, yet the bodies still throw. They are not called anywhere on the production path: Provider's wiring constructs the identity directly from a config value (`new SimpleXIdentity(...)`, `new SignalIdentity(...)`), so these `resolve` entry points are dead public API that no longer reflect how identity material is obtained. A reader trusting the design (§6.4.1 / §6.5.4: "derived from this material at adapter startup; it is NOT an operator-typed property") would look here for the derivation and find a throwing stub, then discover the real source is a property — a confusing divergence. The mention anchor's *source* is left open by spec messaging.md, so the property-based wiring is not itself a spec violation, but the leftover stubs are stale code that misdocuments the design's intended mechanism.

**Recommended fix:**

Either implement `resolve` and route the adapters through it (so the bot contact id is derived from on-disk material as the design intends), or delete the unreachable stubs and update the design note to record that the bot contact id is operator-configured. Pick one; do not leave a throwing stub that claims a shipped ticket implements it.

**Reasoning:**

Dead entry points that lie about their status erode trust in the rest of the file. Resolving the divergence one way or the other makes the identity-sourcing story single-valued.

**Trade-offs:**

Deleting is trivial but loses the design's preferred derive-from-disk path; implementing is more work but matches the design note. The choice is a product call, not a mechanical one.

---

### F7. Hand-written `@NonNull` throughout contradicts the package-default convention

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** module-wide; representative: MessagingAdapter.java:81, :95, :108, :129, :141, :150, :168; CapabilityFlags.java:106; Identity.java:20

**Current code:**

```java
MessageHandle send(@NonNull OutboundMessage msg) throws MessagingException;
void update(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException;
void setTyping(@NonNull ScopeRef scope, boolean typing);
```

**Why this is wrong / suboptimal / risky:**

The project convention (CLAUDE.md §7a) states that every `app.zcat.infochat` package is null-marked via NullAway `AnnotatedPackages`, so a bare reference type already means "never null," and "`@NonNull` is no longer written by hand." This module writes `@NonNull` on essentially every SPI parameter, return, and record component. The annotations are redundant — they restate the package default — and they add visual noise to the public API surface that is supposed to read as "bare = non-null, `@Nullable` = the exception." The redundancy also invites a subtle misreading: a reader may infer that an *un-annotated* type somewhere is intentionally weaker than a `@NonNull` one, when both are identical under the package default.

**Recommended fix:**

Drop the hand-written `@NonNull` annotations across the module, keeping only `@Nullable` where a value is genuinely nullable (e.g. `Identity.displayName`, the `@Nullable` fields and locals that already exist). The NullAway build continues to enforce non-null contracts on the now-bare types.

**Reasoning:**

Removing the redundant annotations makes `@Nullable` the only nullability marker in the file, which is exactly the signal the convention intends: the presence of an annotation always means "this one is special." It also shortens every signature.

**Trade-offs:**

None functionally — the build semantics are unchanged. The edit touches many lines, but it is mechanical and aligns the module with the rest of the codebase's stated convention.

---

## Synthesizer-relevant observations (cross-module — not scored here)

- The inbound deadlock (F1) is realized jointly with `infochat-provider`'s `AdapterRegistry.java:282`, which wires `setInboundHandler(msg -> inboundRouter.onMessage(...))` with no executor, and `InboundRouter.sendReply` (`:675-703`), which calls `target.send(...)` synchronously inside the inbound call stack. The architecture pass should confirm where the reader/worker boundary is meant to live (adapter queue per design §6.3.7, or a Provider-side dispatch executor).
- The bot mention anchor (F6) is sourced from operator-typed properties (`infochat.adapters.simplex.bot-queue-address`, `infochat.adapters.signal.bot-aci`) in `infochat-provider`'s `ProductionAdapterBeans.java:110,126,147`, contradicting design §6.10's "there is no operator-typed mention name … derived from the adapter's bot identity material." Spec leaves the source open, so this is a design/impl divergence rather than a spec violation, but it is worth a single architecture-level note since the design note and the wiring disagree.
