# Deep code review: module infochat-messaging-adapter

**Target:** module infochat-messaging-adapter
**Lens:** module
**Module path:** infochat-messaging-adapter/
**Date:** 2026-06-01 12:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] PERFORMANCE — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:255-284 — `handles` and `finalized` maps grow unbounded across every send; identical DOS pattern that was already fixed in `SignalJsonRpcClient` but never applied here.
- [high] MAINTAINABILITY-RULES-DRIFT — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java:163-174 — `onMembershipEvent` is a confused SPI method (caller-is-implementer pattern); SignalAdapter bypasses it entirely while InMemoryAdapter routes through it, creating two incompatible dispatch shapes for the same SPI surface.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:288-298 — `supportsTypingIndicator=true` contradicts design §6.4.2 (`false` — "SimpleX has no first-class typing indicator"); Provider will emit typing pulses simplex-chat may reject, and the v1 design note is now wrong.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:178-183, infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java:226-232 — adapter SPI methods declare `throws MessagingException` but the codec validators and the wiring check raise `IllegalStateException` / `IllegalArgumentException`, escaping the two-category failure model the spec requires.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java:330-338, infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:348-355 — "adapter not connected" classifies as TRANSIENT in Signal but PERMANENT in SimpleX; identical semantic state, opposite retry posture.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingException.java:22-30 — public constructors take reference-type parameters with no `@NonNull` / `@Nullable` annotations, violating CLAUDE.md §7a parameter-contract rule.
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java:61 — `supportsCodeFormatting=false` drifts from design §6.6 (`true`); the test-double now never exercises the code-formatting render path the design banked on.

## Detail

### F1. SimpleXAdapter handle table grows unbounded — DOS / memory leak

- **Category:** PERFORMANCE
- **Severity:** high
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:88-91, 255-256, 282-284

**Current code:**

```java
private final Map<String, SimpleXMessageHandle> handles = new ConcurrentHashMap<>();
private final Map<String, Boolean> finalized = new ConcurrentHashMap<>();
...
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
...
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
```

**Why this is wrong / suboptimal / risky:**

Every successful `send()` adds an entry to `handles`. The SPI invariant is that after `finalize()` a handle is dead, but `finalize()` only flips `finalized.put(opaque, TRUE)` — it never removes either entry. Both maps grow monotonically forever for the lifetime of the adapter instance.

This is the exact pattern the M1-107 red-team caught for `SignalJsonRpcClient` and fixed there — see the `handleEvictedOnFinalize` test at `SignalJsonRpcClientTest.java:302-335` plus the production fix at `SignalJsonRpcClient.java:188-191`:

```java
void finalizeHandle(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
    SignalMessageHandle internal = lookupOpen(handle);
    editMessage(internal, body);
    // Eviction-on-finalize bounds the open-handle map. ...
    handles.remove(handle.opaqueValue());
}
```

The same red-team finding applies verbatim to SimpleXAdapter; the fix never propagated. A long-running Provider that handles, say, one chat message per second over a week sends roughly 600K outbound messages; that is 600K `SimpleXMessageHandle` records and 600K `Boolean.TRUE` boxes (plus the keys and the `ConcurrentHashMap` bucket overhead) the adapter cannot release until process restart. The blast radius is identical to the Signal bug: a hostile or busy correspondent driving the bot's `/summary` or progress flow can grow the heap until OOM, with no inbound message size cap able to defend (the leak is per outbound, not per inbound).

CLAUDE.md §"Never sacrifice performance, security, or simplicity to reach a goal" — and the SPI's own `MessageHandle` Javadoc says "Holding it in memory for a single request's processing (placeholder → updates → finalize) is the intended use", which the implementation today defeats by holding indefinitely.

**Recommended fix:**

```java
@Override
public void finalize(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
    SimpleXMessageHandle internal = requireKnownAndOpen(handle);
    SimpleXWebSocketClient ws = requireConnected();
    String corrId = nextCorrId();
    String envelope = SimpleXMessageCodec.encodeFinalizeCommand(
            corrId, internal.chatItemId(), internal.scope(), body);
    ws.sendCommand(corrId, envelope, ACK_TIMEOUT);
    // Eviction-on-finalize bounds the open-handle map. A subsequent
    // update() on the now-removed handle resolves to a missing key in
    // requireKnownAndOpen, which throws PERMANENT — same category the
    // SPI requires for "already finalized", so no behavioral change.
    handles.remove(handle.opaqueValue());
}

private SimpleXMessageHandle requireKnownAndOpen(MessageHandle handle) throws MessagingException {
    SimpleXMessageHandle internal = handles.get(handle.opaqueValue());
    if (internal == null) {
        // Missing key collapses two cases — never-existed and
        // already-finalized — both PERMANENT per the SPI invariant.
        throw new MessagingException(FailureCategory.PERMANENT,
                "unknown handle: " + handle.opaqueValue());
    }
    return internal;
}
```

The `finalized` map is then removed entirely.

**Reasoning:**

This is the same fix the SignalJsonRpcClient applied (and that has a regression test pinning it). Both "unknown handle" and "handle already finalized" classify as PERMANENT per the SPI, so collapsing them into one missing-key case preserves observable behavior. The fix bounds the map at the in-flight outbound count rather than the cumulative outbound count, which is what the SPI's "in memory for a single request's processing" invariant promises. The matching regression test in `SignalJsonRpcClientTest` (`handleEvictedOnFinalize`) should be mirrored in `SimpleXWebSocketClientTest` (or a new `SimpleXAdapterTest`) so the bug cannot regress.

**Trade-offs:**

None — the fix is strictly better. The two-map design buys nothing: it doubles allocation per outbound message and surfaces an unbounded growth in a hot path.

---

### F2. `MessagingAdapter.onMembershipEvent` is a confused SPI method that creates two incompatible dispatch shapes

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java:148-174

**Current code:**

```java
default void setMembershipEventHandler(@NonNull MembershipHandler handler) {
    // No-op — overridden by adapters that fire membership events.
}

/**
 * Receive a group-membership lifecycle signal from the adapter.
 * Default is no-op so adapters that do not support groups (or
 * whose group wiring is not yet implemented) are unaffected.
 * Once Provider calls {@link #setMembershipEventHandler}, the
 * adapter dispatches events through the registered handler.
 *
 * @param event the membership event; never null.
 */
default void onMembershipEvent(@NonNull MembershipEvent event) {
    // No-op — adapters surface events; Provider consumes them.
}
```

**Why this is wrong / suboptimal / risky:**

`onMembershipEvent` sits on the SPI but is conceptually an internal hook: it is the adapter's own code that calls it (the in-memory adapter's `removeMember` and `removeBot`), not Provider. Provider's role on this SPI is to register a `MembershipHandler` via `setMembershipEventHandler` — Provider never has a reason to call `onMembershipEvent`. Exposing it on the interface invites confusion at three levels:

1. The Javadoc reads "Receive a group-membership lifecycle signal from the adapter" — making it sound like a Provider-facing receive method, when in fact it is the adapter's self-dispatch.
2. `SignalAdapter` overrides `setMembershipEventHandler` and stores the handler, but its `SignalGroupHandler.dispatchMembership` calls `handler.onEvent(event)` directly without going through `onMembershipEvent`. So Signal's membership dispatch path bypasses the SPI method entirely.
3. `InMemoryAdapter` overrides `onMembershipEvent` and routes through it. So InMemoryAdapter relies on it, Signal does not, and both implement the same SPI.

The asymmetric pattern means a future adapter author cannot tell from the SPI which path is canonical. If they pick the InMemoryAdapter shape and depend on calling `onMembershipEvent`, the default no-op silently drops their events whenever they forget to override. If they pick the Signal shape, the `onMembershipEvent` method on the SPI is dead weight.

This is the "leaky abstraction" failure mode the engineering rules' §"Push back when simpler exists" addresses: the `setMembershipEventHandler` method by itself is sufficient (the adapter stores the handler and calls it directly). Adding `onMembershipEvent` to the SPI exposes implementation detail and creates an unenforced invariant.

**Recommended fix:**

```java
public interface MessagingAdapter {

    // ... existing methods ...

    /**
     * Register the callback Provider uses to receive membership
     * events. Provider sets exactly one handler per adapter instance
     * at startup; replacing a handler is undefined for v1. Adapters
     * with {@link CapabilityFlags#supportsMembershipEvents}() false
     * MUST treat the registration as a no-op.
     *
     * @param handler the membership-event callback; never null.
     */
    default void setMembershipEventHandler(@NonNull MembershipHandler handler) {
        // No-op default — adapters with supportsMembershipEvents=false
        // do not fire events, so they have nothing to register.
    }

    // onMembershipEvent is REMOVED from the SPI.

    // ... InboundHandler / MembershipHandler interfaces unchanged ...
}
```

Then `InMemoryAdapter.removeMember` / `removeBot` call the stored `membershipHandler.onEvent(event)` directly (mirroring Signal's pattern), and `onMembershipEvent` is deleted from both adapters.

**Reasoning:**

The SPI now has a single, unambiguous dispatch shape: Provider registers, adapter dispatches when its underlying transport reports an event. Both v1 adapters converge on the same pattern. The "no consumer attached yet" case becomes a single null-check in the adapter's dispatch site (or, more cleanly, the adapter does not start surfacing events until the handler is set). The InMemoryAdapter's existing tests pass once `removeMember` / `removeBot` invoke the stored handler directly.

**Trade-offs:**

The change touches three files (`MessagingAdapter.java`, `InMemoryAdapter.java`, `SignalAdapter.java`) and one test (`InMemoryAdapterGroupTest.java` — only as far as the dispatch contract; the assertions stay the same because the user-visible behavior is unchanged). The Javadoc on `setMembershipEventHandler` becomes the single point of authority for the SPI's membership shape.

---

### F3. SimpleX `supportsTypingIndicator=true` contradicts design §6.4.2

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:77, 287-298

**Current code:**

```java
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
        /* supportsTypingIndicator    */ true,    // <-- here
        /* minEditInterval            */ Duration.ZERO);
...
@Override
public void setTyping(@NonNull ScopeRef scope, boolean typing) {
    SimpleXWebSocketClient ws = webSocket;
    if (ws == null) {
        return;
    }
    String envelope = SimpleXMessageCodec.encodeTypingCommand(
            nextCorrId(), scope, typing);
    ws.sendFireAndForget(envelope);
}
```

**Why this is wrong / suboptimal / risky:**

`docs/design/06-messaging.md` §6.4.2 declares for SimpleX:

```
supportsTypingIndicator    = false  // SimpleX has no first-class typing indicator
```

The code declares `true`. The class Javadoc rationalises this as "acceptance item 11 commits to sending apiSetContactTyping-shaped commands ... If simplex-chat rejects the command, the fire-and-forget path absorbs the failure". But this is the wrong direction of resolution. Design says the protocol has no typing surface; code claims it does and sends best-effort commands that the protocol won't honor. The capability flag is what Provider uses to decide whether to invoke `setTyping(scope, true)` around long-running operations. With `true`, Provider sends typing pulses for every progress-notifier session that will silently fail on the wire.

Two consequences:

1. **Spec/design drift.** Either the design note is wrong (and should be flipped) or the code is wrong (and should match). One of the two must be corrected — both cannot remain authoritative for opposite values.
2. **Wasted outbound traffic per long-running request.** Every `setTyping(true)` / `setTyping(false)` pair on a SimpleX scope produces one fire-and-forget command, with no protocol effect. The cost is small but unbounded across active conversations.

The right move depends on whether `apiSetContactTyping` actually exists on the simplex-chat bot API. If yes, design needs to be updated; if no, the capability needs to be flipped and `encodeTypingCommand` deleted as speculative SPI surface (per CLAUDE.md §7: "speculative SPI surface for non-existent callers would violate the engineering rules' 'no defensive code for impossible scenarios' corollary against speculative API" — verbatim from `MessagingAdapter.java:27-30`).

**Recommended fix:**

If `apiSetContactTyping` is verified by M1-105 against a live simplex-chat: update `docs/design/06-messaging.md` §6.4.2 to declare `supportsTypingIndicator = true` and add the wire shape to the design note. If it does not exist: flip the capability flag to `false`, remove `encodeTypingCommand` and the `setTyping` body's fire-and-forget call, and add a unit test pinning the false declaration.

```java
// Option B: flip to false until verified.
/* supportsTypingIndicator    */ false,
...
@Override
public void setTyping(@NonNull ScopeRef scope, boolean typing) {
    // No-op per supportsTypingIndicator=false — SPI contract on
    // adapters that lack a typing surface.
}
```

**Reasoning:**

Either the protocol supports the command or it does not — capability flags are the contract Provider relies on. The "send it and hope" posture papers over the gap and lets the contradiction between design and code persist. A correctly-declared capability is what makes Provider's per-message dispatch deterministic. The skeleton-defers-to-M1-105 framing in the Javadoc is exactly the speculative-SPI pattern §7 rejects.

**Trade-offs:**

If the future M1-105 integration discovers that simplex-chat *does* support typing in a way that was missed, flipping back to true is one boolean. The cost of being conservative now (no fire-and-forget noise) is lower than the cost of staying optimistic (silent wasted traffic per request).

**Alternative options:**

- **Option A** (the recommended fix above) — pick the right side of the contradiction now.
- **Option B** — leave the code as-is but mark the capability declaration with an explicit TODO and a tracking ticket. Cons: the design note is wrong indefinitely, and the deferral creates exactly the kind of "review-irrelevant" drift `M1-026` cleanup was meant to prevent.

---

### F4. Adapter SPI methods leak `IllegalStateException` / `IllegalArgumentException` past the `throws MessagingException` contract

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:177-183, infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java:190-198, 226-232

**Current code:**

```java
// SimpleXAdapter.start (line 177-183)
if (identity.queueAddress().isBlank()) {
    throw new IllegalStateException(
            "infochat.adapters.simplex.bot-queue-address must be set"
                    + " to the bot's own SimpleX queue address (distinct"
                    + " from the bootstrap admin's queue address in"
                    + " infochat.adapters.simplex.admin)");
}

// SimpleXMessageCodec.requireValidQueueAddressId (line 190-198)
private static void requireValidQueueAddressId(String id, String fieldName) {
    if (!isValidQueueAddressId(id)) {
        throw new IllegalStateException(
                fieldName + " fails queue-address validator (design §6.4.4); length="
                        + id.length());
    }
}

// SimpleXMessageCodec.requireWithinCap (line 226-232)
private static void requireWithinCap(String text) {
    int byteLength = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if (byteLength > MAX_OUTBOUND_TEXT_BYTES) {
        throw new IllegalArgumentException(
                "outbound text " + byteLength + " bytes exceeds adapter cap "
                        + MAX_OUTBOUND_TEXT_BYTES);
    }
}
```

**Why this is wrong / suboptimal / risky:**

`SimpleXAdapter.send`, `update`, and `finalize` all flow through `SimpleXMessageCodec.encodeXxxCommand`, which in turn call `requireValidQueueAddressId` (raises `IllegalStateException`) and `requireWithinCap` (raises `IllegalArgumentException`). These methods are declared `throws MessagingException` — the SPI contract is that transport errors come with a `FailureCategory`. A runtime exception from the codec escapes that contract and reaches Provider's outbound retry layer as an unclassified throwable.

Two concrete consequences:

1. Provider's retry policy (3-attempt full-jitter, per `docs/spec/messaging.md` §Failure handling) reads `MessagingException.category()` to decide whether to retry. A bare `IllegalStateException` bypasses that branch — depending on Provider's wrapping logic it could be treated as a fatal startup-class failure or, worse, as a transient retryable error that re-throws the same exception three times.
2. `SimpleXAdapter.start()`'s blank-identity check raises `IllegalStateException` from a method only declared `throws MessagingException`. The bootstrap fail-path documented in design §6.7 ("Per-adapter resilience ... each failed adapter is logged at ERROR severity and retries on a profile-driven backoff") expects a categorised failure to reach the registry; an `IllegalStateException` skips the category check.

The codec's defensive validators are correct — design §6.4.4 explicitly mandates the "defense-in-depth" encode-time check — but the chosen exception type for the failure is wrong for callers on the SPI path.

**Recommended fix:**

The codec raises a categorised `MessagingException` (PERMANENT — the input id is not on the spec's character set, retrying cannot fix that) and the encode entry points declare it:

```java
static @NonNull String encodeSendCommand(@NonNull String corrId,
                                         @NonNull ScopeRef scope,
                                         @NonNull String text) throws MessagingException {
    requireWithinCap(text);
    String target = targetSelector(scope);
    String cmd = "/_send " + target + " json " + jsonString(textContent(text));
    return envelope(corrId, cmd);
}

private static void requireValidQueueAddressId(String id, String fieldName) throws MessagingException {
    if (!isValidQueueAddressId(id)) {
        throw new MessagingException(FailureCategory.PERMANENT,
                fieldName + " fails queue-address validator (design §6.4.4); length="
                        + id.length());
    }
}

private static void requireWithinCap(String text) throws MessagingException {
    int byteLength = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if (byteLength > MAX_OUTBOUND_TEXT_BYTES) {
        throw new MessagingException(FailureCategory.PERMANENT,
                "outbound text " + byteLength + " bytes exceeds adapter cap "
                        + MAX_OUTBOUND_TEXT_BYTES);
    }
}
```

For the `SimpleXAdapter.start()` blank-identity check, this is at a configuration boundary, so `IllegalStateException` is fine for a boot-time failure (Provider's AdapterRegistry catches it at startup). The current code is consistent — but the SPI Javadoc on `start()` (line 153 — `throws MessagingException`) should either drop the throws declaration entirely (since the method also throws ISE) or document that misconfiguration surfaces as ISE.

**Reasoning:**

Once the codec raises `MessagingException`, the adapter's send / update / finalize call chain is exhaustively-categorised — Provider's retry decision is a single branch on category. The encode-time validator that exists today is the *intended* place for defense-in-depth per design §6.4.4, but the chosen exception type for the failure misclassifies it. Switching to `MessagingException` preserves the validator and the test surface (just the exception type changes — `encodeRejectsContactIdWithCommandInjectionChars` swaps `IllegalStateException.class` for `MessagingException.class`).

**Trade-offs:**

The codec's encode entry points become `throws MessagingException`, which slightly widens the checked-exception surface for callers (every `encodeSendCommand` / `encodeUpdateCommand` / `encodeFinalizeCommand` / `encodeTypingCommand` site declares it). But the SimpleXAdapter callers already declare `throws MessagingException`, so this is zero-overhead. The encode tests need a one-line update to assert the new exception type. Two unit tests touch this surface (`SimpleXMessageCodecTest.encodeRejectsContactIdWithCommandInjectionChars`, line 299-331), which is a bounded change.

---

### F5. "Adapter not connected" classifies inconsistently between Signal (TRANSIENT) and SimpleX (PERMANENT)

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java:330-338, infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:348-355

**Current code:**

```java
// SignalAdapter
private SignalJsonRpcClient requireConnected(String op) throws MessagingException {
    SignalJsonRpcClient c = client;
    if (c == null) {
        throw new MessagingException(
                FailureCategory.TRANSIENT,
                "Signal adapter " + op + ": JSON-RPC client not connected (start() not called or close() in progress)");
    }
    return c;
}

// SimpleXAdapter
private SimpleXWebSocketClient requireConnected() throws MessagingException {
    SimpleXWebSocketClient ws = webSocket;
    if (ws == null) {
        throw new MessagingException(FailureCategory.PERMANENT,
                "SimpleXAdapter is not started; call start() first");
    }
    return ws;
}
```

**Why this is wrong / suboptimal / risky:**

The same semantic state — "the adapter's transport client is null because `start()` has not been called, or `close()` has happened, or the connection has been lost" — is reported with opposite `FailureCategory`. Provider's retry layer (`docs/spec/messaging.md` §Failure handling) makes its retry/abort decision purely on `category()`. With Signal's TRANSIENT, Provider retries the same call three times against a definitely-disconnected client; with SimpleX's PERMANENT, Provider aborts the reply immediately.

Neither choice is wrong in isolation (the spec's "cannot tell → PERMANENT" guidance favours SimpleX's posture; Signal's TRANSIENT acknowledges that a transient subprocess hiccup may resolve mid-retry). But the same SPI surface in the same module producing opposite classifications for the same observable state is a contract bug — a cross-adapter contract test would catch it and the spec's "uniform across adapters" framing for the retry policy is undermined.

**Recommended fix:**

Pick one classification and apply it to both adapters. The conservative choice (matching `docs/spec/messaging.md` §Failure handling's default-to-PERMANENT rule) is PERMANENT:

```java
// SignalAdapter.requireConnected — change category to PERMANENT
throw new MessagingException(
        FailureCategory.PERMANENT,
        "Signal adapter " + op + ": JSON-RPC client not connected");
```

**Reasoning:**

The "not connected" state is observable from the adapter — the supervisor knows whether the subprocess is up. If the connection has dropped, retrying instantly cannot succeed; the adapter needs the supervisor's reconnect cadence to restore the wire. Provider's per-user rate limiter (the "single source of truth for slow this user down" per the spec) provides the rebuild backpressure. PERMANENT-then-the-next-inbound-reuses-the-standard-intake-path is the expected pattern.

The Signal comment "(start() not called or close() in progress)" hints at why the current code chose TRANSIENT — a close-in-progress could complete just before the retry. But TRANSIENT against a closed client is the worst-case wait — three full retry attempts against a client that cannot succeed. The PERMANENT-then-rebuild-on-next-inbound model wastes less budget.

**Trade-offs:**

Switching Signal to PERMANENT means a single transient subprocess hiccup mid-send aborts the affected reply rather than waiting it out. The bot-removed-from-group threshold (3 consecutive PERMANENTs) is per-group, not per-call, so a single failure does not trigger group cleanup. A user who hits a one-shot send failure sees the request fail; the next message starts a fresh send. This matches SimpleX's posture today.

**Alternative options:**

- **Option A** (recommended) — both PERMANENT.
- **Option B** — both TRANSIENT. Pros: gives the supervisor a window to reconnect mid-retry. Cons: against a permanently-closed client (operator stopped the adapter), Provider burns all three retries before giving up.

---

### F6. `MessagingException` public constructors lack `@NonNull` / `@Nullable` annotations

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingException.java:22-30

**Current code:**

```java
public class MessagingException extends Exception {

    private final FailureCategory category;

    public MessagingException(FailureCategory category, String message) {
        super(message);
        this.category = category;
    }

    public MessagingException(FailureCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }
    ...
}
```

**Why this is wrong / suboptimal / risky:**

CLAUDE.md §7a (verbatim from `docs/process/engineering-rules-verbatim.md` §7a): "Every reference-type parameter on a public method declares nullability — either via annotation (`@NonNull`/`@Nullable` from `org.jspecify.annotations`) or via javadoc `@param`. Public/protected methods MUST annotate". Constructors are public methods; the three reference-type parameters across the two constructors (`FailureCategory category`, `String message`, `Throwable cause`) carry no annotations and no `@param` documentation either.

Every throw site in the module currently passes non-null values (the codec, the WebSocket client, the JSON-RPC client, the adapter's `requireConnected`), so the runtime behavior is fine. But the SPI surface is what gets read at integration time — a future adapter author cannot tell from the signature whether passing `null` is legal. The complement to §7 (no defensive null-checks inside trust boundaries) requires §7a's contract on the signature so the trust is anchored.

Note also that this is the same module that introduced `@NonNull`/`@Nullable` consistently on every other SPI record (Identity, InboundMessage, OutboundMessage, MessageHandle, CapabilityFlags, ScopeRef, MembershipEvent) — `MessagingException` is the odd one out.

**Recommended fix:**

```java
public class MessagingException extends Exception {

    private final FailureCategory category;

    public MessagingException(@NonNull FailureCategory category, @NonNull String message) {
        super(message);
        this.category = category;
    }

    public MessagingException(@NonNull FailureCategory category,
                              @NonNull String message,
                              @Nullable Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public @NonNull FailureCategory category() {
        return category;
    }
}
```

**Reasoning:**

The annotations encode what the call sites already obey. `cause` is `@Nullable` because the two-arg constructor exists for failures without an underlying throwable; `category` is `@NonNull` because the spec's "every failure is categorised at throw site" forcing-function depends on it. The class becomes consistent with the rest of the SPI's parameter contracts.

**Trade-offs:**

None — the fix is strictly better. Three annotation imports plus three call-site decorations.

---

### F7. `InMemoryAdapter` capability `supportsCodeFormatting=false` drifts from design §6.6

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java:60

**Current code:**

```java
private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
        /* supportsMentionByContactId */ true,
        /* supportsMembershipEvents   */ true,
        /* supportsCodeFormatting     */ false,    // <-- drifts from design
        /* supportsMarkdownLinks      */ false,
        ...
```

**Why this is wrong / suboptimal / risky:**

`docs/design/06-messaging.md` §6.6 declares for the InMemoryAdapter:

```
true,            // supportsCodeFormatting — exercises the markdown-code render path
```

The actual code declares `false`, and the InMemoryAdapter test (`InMemoryAdapterTest.defaultTrustLevelIsLowAndHighConstructorFlipsIt` line 125) asserts the false value. The design's stated rationale was specifically "so tests exercise the code-formatting render path" — the chosen capability is the inverse of what the design wants. If a Provider-side path conditionally formats backticks for code-formatting-supporting adapters, no test in the in-memory deployment exercises that path. The "SimpleX declares false; tests of the plain-text fallback also run" half of the design note still holds, but the positive path is uncovered.

Either the design note is wrong (the team intentionally chose false to keep the test harness on the plain-text fallback) or the code is wrong (the design's "exercises the code-formatting render path" rationale should hold and the flag should be true). One must be corrected.

**Recommended fix:**

If the in-memory adapter is meant to exercise the code-formatting render path (per design's stated rationale), flip the flag and update the assertion:

```java
// InMemoryAdapter.java
/* supportsCodeFormatting     */ true,
```

```java
// InMemoryAdapterTest.java
assertTrue(defaultAdapter.capabilities().supportsCodeFormatting(),
        "InMemoryAdapter exercises the code-formatting render path "
        + "so Provider-side formatting logic is test-covered");
```

If the deliberate choice is to keep the in-memory adapter on the plain-text path (matching SimpleX), update `docs/design/06-messaging.md` §6.6 to drop the "exercises the markdown-code render path" rationale and document the change.

**Reasoning:**

Either choice is defensible, but the current drift leaves the design wrong relative to the code. The recommended fix path (flip the flag) restores the test-coverage goal the design committed to.

**Trade-offs:**

Flipping the flag may cause some Provider-side test to observe formatted output where it previously observed plain text — but that is exactly the test coverage the design was banking on. The alternative (update design to match code) is purely documentary, with no code change.

---

## Synthesizer-relevant observations

- The `MessagingAdapter` SPI's `start()` lifecycle is intentionally absent from the interface ("deferred to the first concrete adapter") — both SimpleXAdapter and SignalAdapter declare `start()` on the concrete type instead. This makes adapter registration (AdapterRegistry, M1-035b/M1-105 in Provider) require knowledge of each concrete type and prevents a polymorphic start loop. The architecture lens should evaluate whether this is the right shape for the multi-adapter registry, or whether `start()` belongs on the SPI after all (design §6.2 originally placed it there).
- The codec/encoder constants `MAX_OUTBOUND_TEXT_BYTES = 4_000` (SimpleXMessageCodec) and `maxMessageBytes = 2_000` (SimpleXAdapter capability) are declared independently in two places. Cross-module Provider chunking uses the capability; the codec's value is a defensive second wall. Out-of-sync changes (e.g., raising the protocol limit) would silently break the second wall. Architecture lens should check whether the lockstep is enforced anywhere or whether it is documentation-only.
- `SignalSubprocess` hardcodes `SUBPROCESS_MAX_RESTARTS = 5` (SignalAdapter.java:89), while `SimpleXSubprocess.DEFAULT_CRASH_CAP = 5` and design §6.3.6's bot-removed-from-group threshold is 3 for laptop. The crash cap is not strictly the same as the bot-removed threshold, but the design's profile-driven table only addresses the latter. Architecture lens should confirm whether a separate subprocess crash cap belongs in the per-profile table.
