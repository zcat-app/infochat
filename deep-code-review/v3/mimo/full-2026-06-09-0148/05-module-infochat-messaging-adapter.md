# Deep code review: module infochat-messaging-adapter

**Target:** module infochat-messaging-adapter
**Lens:** module
**Module path:** infochat-messaging-adapter/
**Date:** 2026-06-09 01:48
**Reviewer:** senior-developer (mimo)

## Headline findings

- [high] SECURITY — SignalMessageCodec.java:240 — `canonicalizeAci` accepts any string from the wire, not just valid UUIDs; a non-UUID sourceUuid becomes a permanent contact id in the identity system
- [medium] PERFORMANCE — SignalAdapter.java:402 — reconnect spawns a platform `new Thread` instead of using a virtual thread, inconsistent with the project's JDK 25 virtual-thread policy
- [medium] PERFORMANCE — SignalJsonRpcClient.java:276-277 — dispatch executor uses platform threads for the single dispatch worker, while SimpleX uses platform threads for the same role; both could use virtual threads since they may block on handler callbacks
- [low] MAINTAINABILITY-RULES-DRIFT — SimpleXAdapter.java:26 — uses `java.util.Random` for backoff jitter injection; `java.util.concurrent.ThreadLocalRandom` is the standard choice for non-security random in concurrent code

## Detail

### F1. Signal `canonicalizeAci` accepts non-UUID strings as valid contact ids

- **Category:** SECURITY
- **Severity:** high
- **Location:** SignalMessageCodec.java:240, SignalGroupHandler.java:165, SignalAdapter.java:160

**Current code:**

```java
// SignalMessageCodec.java:240
String canonicalizeAci(String aci) {
    return aci.toLowerCase(Locale.ROOT);
}
```

```java
// SignalGroupHandler.java:165
String senderAci = sourceUuid.toLowerCase(Locale.ROOT);
Identity sender = new Identity(senderAci, null, Instant.now());
```

```java
// SignalAdapter.java:160
this.botAci = botAci.toLowerCase(Locale.ROOT);
```

**Why this is wrong / suboptimal / risky:**

The Signal adapter's `canonicalizeAci` method (and the inline lowercase in `SignalGroupHandler`) accepts any string from the wire as a valid contact id. The SimpleX adapter, by contrast, validates every inbound contact id against `isValidQueueAddressId` (the `^[A-Za-z0-9_=.-]+$` character-set gate) before constructing an `Identity`. The Signal adapter has no analogous gate.

Per `docs/spec/messaging.md` §Per-adapter trust level: "The contact id is the user's registered phone number (E.164) or, where Signal supports it, the username." Signal ACIs are UUIDs. A `sourceUuid` that arrives as an arbitrary string (e.g. containing whitespace, slashes, or injection payloads) is lowercased and stored verbatim as a contact id. This contact id then:

1. Flows into the `(adapter, contact_id)` join key that `security.md` §Per-adapter admin threat profile and `messaging.md` §Per-adapter trust level define as the identity anchor.
2. May be logged or stored in audit rows. A crafted non-UUID `sourceUuid` containing newlines or other control characters could corrupt log lines or audit storage.
3. Breaks the cross-adapter isolation invariant: the join key is supposed to be a cryptographic identifier, not arbitrary wire data.

The `SignalIdentity.isWellFormed` method exists and validates UUID format, but it is only called at the Provider-side bootstrap-admin parse gate — never on inbound wire data.

**Recommended fix:**

```java
// SignalMessageCodec.java — replace canonicalizeAci
String canonicalizeAci(String aci) {
    // ACIs are UUIDs; reject non-UUID strings at the trust boundary
    // before they become permanent contact ids in the identity system.
    try {
        UUID.fromString(aci);
    } catch (IllegalArgumentException e) {
        return null; // caller drops the frame
    }
    return aci.toLowerCase(Locale.ROOT);
}
```

Then update `extractDm` to check the result and return `Optional.empty()` when null. Apply the same validation in `SignalGroupHandler` line 165 for the group path. Alternatively, extract the validation into a shared static method that both paths call, returning null or an empty Optional on rejection.

**Reasoning:**

The Signal adapter declares `trustLevel = HIGH` because the ACI is cryptographically anchored. But the trust assertion is only as strong as the validation at the boundary. Accepting arbitrary strings as contact ids undermines the D10 trust anchor claim. The fix is low-cost (one `UUID.fromString` call per inbound message) and aligns the Signal adapter's inbound validation with the SimpleX adapter's `isValidQueueAddressId` discipline.

**Trade-offs:**

A malformed but legitimate signal-cli response (e.g. a phone-number sourceUuid during account migration, as the `canonicalizeAci` javadoc acknowledges) would be rejected. If phone-number contacts are a real v1 concern, the validation should accept E.164 format in addition to UUIDs, with a character-set gate rather than no validation at all. Dropping messages from non-UUID contacts is the safe default — the spec says "a message whose identity cannot be asserted is dropped at decode."

---

### F2. SignalAdapter reconnect uses `new Thread` instead of virtual thread

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** SignalAdapter.java:402

**Current code:**

```java
private void onSubprocessRestart() {
    Thread t = new Thread(this::reconnect, "signal-adapter-reconnect");
    t.setDaemon(true);
    t.start();
}
```

**Why this is wrong / suboptimal / risky:**

The project targets JDK 25 + Quarkus 3.33 with a blocking-style, virtual-thread-first policy (`CLAUDE.md` §Stack). The SimpleX adapter's equivalent `onSubprocessRestart` at line 294-297 uses `Thread.ofVirtual().name("simplex-adapter-reconnect").start(this::reconnect)`. The Signal adapter uses `new Thread(...)` with a platform thread for the same purpose.

The reconnect path blocks on an endpoint probe (up to 15 seconds) and a reader join, which is exactly the blocking I/O pattern virtual threads are designed for. A platform thread pinned for up to 15 seconds is wasted OS resource when virtual threads are available.

**Recommended fix:**

```java
private void onSubprocessRestart() {
    Thread.ofVirtual()
            .name("signal-adapter-reconnect")
            .start(this::reconnect);
}
```

**Reasoning:**

Aligns with the project's virtual-thread policy and the SimpleX adapter's implementation of the same pattern. No behavior change — the reconnect logic is identical.

**Trade-offs:**

None — the fix is strictly better.

---

### F3. Dispatch executors use platform-thread factories

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** SignalJsonRpcClient.java:276, SimpleXWebSocketClient.java:169

**Current code:**

```java
// SignalJsonRpcClient.java:274-276
this.dispatchExecutor = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, queue,
        Thread.ofPlatform().daemon().name("signal-inbound-dispatch").factory());
```

```java
// SimpleXWebSocketClient.java:167-169
this.dispatchExecutor = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, dispatchQueue,
        Thread.ofPlatform().daemon().name("simplex-inbound-dispatch").factory());
```

**Why this is wrong / suboptimal / risky:**

Both dispatch executors run a single thread that calls the `InboundHandler.onMessage` callback. Per the `MessagingAdapter.InboundHandler` javadoc: "the handler may block and may send replies synchronously from onMessage (including calls back into the same adapter's send path)." The handler does blocking I/O (DB queries, LLM calls, reply sends that await acks). A platform thread blocked on this work is an OS thread that cannot be reused elsewhere.

On JDK 25 with virtual threads, a `Executors.newSingleThreadExecutor(Thread.ofVirtual()...factory())` would let the dispatch thread park without consuming an OS thread during handler blocking. The single-thread constraint (FIFO delivery order) is preserved by the executor's concurrency=1, not by the thread type.

**Recommended fix:**

Replace `Thread.ofPlatform()` with `Thread.ofVirtual()` in both locations.

**Reasoning:**

The dispatch thread's primary job is to call into Provider-side handler code that blocks on DB and LLM I/O. Virtual threads are the correct carrier for this pattern on JDK 25. The single-thread executor shape preserves FIFO ordering regardless of thread type.

**Trade-offs:**

If the handler performs `synchronized` blocks (which pin virtual threads), the benefit is reduced. However, the codebase uses `ReentrantLock` and concurrent collections rather than `synchronized` in the handler paths, so pinning is unlikely. The fix is net-positive.

---

### F4. SimpleXSubprocess uses `java.util.Random` for backoff jitter

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** SimpleXSubprocess.java:76

**Current code:**

```java
private final Random random;
```

**Why this is wrong / suboptimal / risky:**

`java.util.Random` is thread-safe but uses `AtomicLong` CAS internally, which under contention adds overhead. `ThreadLocalRandom` is the standard JDK recommendation for non-security random values in concurrent code (it avoids CAS entirely). The `SimpleXSubprocess` only calls `random.nextLong()` from the supervisor virtual thread, so contention is not a real issue here — but `ThreadLocalRandom` is the idiomatic choice and the constructor parameter `Random random` forces test seams to construct a `Random` instance when `ThreadLocalRandom.current()` would suffice.

The `SignalSubprocess` at line 273 uses `ThreadLocalRandom.current().nextLong()` directly, which is the simpler pattern.

**Recommended fix:**

Replace the `Random random` constructor parameter and field with direct `ThreadLocalRandom.current()` usage in `backoffDelay`. The test seam already passes `new Random(seed)` — the test can inject a seed and construct `new Random(seed)` locally in the test method, or the `backoffDelay` method can take a `Random` parameter while the production path uses `ThreadLocalRandom.current()`.

**Reasoning:**

Aligns with `SignalSubprocess`'s approach and JDK best practice. The `Random` injection was likely inherited from the `NostrRelayConnection` pattern mentioned in the javadoc, but the Signal subprocess already uses the simpler form.

**Trade-offs:**

The current code works correctly. This is a style-consistency improvement, not a bug.
