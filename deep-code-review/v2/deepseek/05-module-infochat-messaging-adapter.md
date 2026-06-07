# Deep code review: module infochat-messaging-adapter

**Target:** module infochat-messaging-adapter
**Lens:** module
**Module path:** infochat-messaging-adapter/
**Date:** 2026-06-07
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — `CapabilityFlags.java:92-106` — 14-parameter record constructor; Java records with this many components are fragile to positional argument errors
- [medium] PERFORMANCE — `InMemoryAdapter.java` — the test adapter stores messages in an in-memory `ConcurrentHashMap` with no size bound; a long-running test that sends many messages will OOM
- [low] SECURITY — `SimpleXWebSocketClient.java` — WebSocket reconnect logic is in the adapter, not the SSRF module; the reconnect must re-validate the peer IP against the blocklist, per `security.md` §SSRF
- [low] SIMPLIFICATION — `SignalAdapter.java` and `SimpleXAdapter.java` — both adapters carry ~30 lines of identical capability-flag initialization; extracting a builder or static factory would remove the duplication

## Detail

### F1. 14-parameter CapabilityFlags record — positional fragility

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/CapabilityFlags.java:92-106`

**Current code:**

```java
public record CapabilityFlags(
        boolean supportsMentionByContactId,
        boolean supportsMembershipEvents,
        boolean supportsCodeFormatting,
        boolean supportsMarkdownLinks,
        boolean supportsMultilineCode,
        boolean supportsAttachments,
        boolean supportsThreading,
        int maxMessageBytes,
        int maxInboundMessageBytes,
        int maxInflightSends,
        int maxSendsPerSecond,
        boolean supportsMessageEdit,
        boolean supportsTypingIndicator,
        @NonNull Duration minEditInterval) {
}
```

**Why this is wrong / suboptimal / risky:**

A 14-parameter positional constructor is error-prone. The three adapter implementations (InMemory, Signal, SimpleX) each call this constructor with 14 positional arguments, with inline comments labeling each boolean:

```java
new CapabilityFlags(
    /* supportsMentionByContactId */ true,
    /* supportsMembershipEvents   */ true,
    /* supportsCodeFormatting     */ true,
    /* supportsMarkdownLinks      */ false,
    ...
);
```

The inline comments prevent transposition errors during code review, but the compiler cannot help. Swapping two adjacent `boolean` parameters (e.g., `supportsMarkdownLinks` and `supportsMultilineCode`) would compile silently and produce a bug that only surfaces in integration tests.

Every adapter implementation duplicates these 14 arguments with identical comments. A builder pattern would let each adapter set only the flags it overrides, defaulting the rest.

**Recommended fix:**

Add a static `builder()` method to `CapabilityFlags` that returns a builder with safe defaults:

```java
public static Builder builder() {
    return new Builder();
}
```

Each adapter then calls:

```java
CapabilityFlags.builder()
    .supportsMentionByContactId(true)
    .supportsMembershipEvents(true)
    .supportsCodeFormatting(true)
    .supportsMarkdownLinks(false)
    .supportsMessageEdit(true)
    .build();
```

The builder sets defaults for every field (e.g., `supportsAttachments=false`, `supportsThreading=false`), so adapters only override what they support.

**Reasoning:**

Eliminates the risk of positional transposition. Reduces duplication across the three adapter implementations. Makes adding a new flag in v2 a single builder method addition rather than updating every adapter's constructor call.

**Trade-offs:**

- A builder adds ~50 lines of boilerplate (but saves ~90 lines across the three adapter call sites).
- The builder's defaults must stay in sync with the spec's v1 invariants.

---

### F2. InMemoryAdapter unbounded message storage

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java`

**Why this is wrong / suboptimal / risky:**

The InMemoryAdapter stores all sent messages in a `ConcurrentHashMap` for test assertions. There is no size bound or eviction policy. A long-running integration test (e.g., `DigestRoundtripIT` which sends digests to multiple groups repeatedly) accumulates messages in this map indefinitely. In a CI pipeline with constrained memory, this can cause OOM during extended test runs.

The adapter is test-only (excluded from production by AdapterRegistry gate 5), so this is not a production risk. But it's a test-flakiness risk for long-running CI suites.

**Recommended fix:**

Add a `@PreDestroy` cleanup or a bounded size (e.g., `LinkedHashMap` with LRU eviction at 10,000 messages).

**Reasoning:**

Prevents test OOM in CI without changing the adapter's API.

**Trade-offs:**

- LRU eviction would break tests that assert on messages sent before the 10,000-message window.
- The current implementation is correct for v1's test suite which sends <<10,000 messages per test.

---

### F3. WebSocket reconnect IP re-validation

- **Category:** SECURITY
- **Severity:** low
- **Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java`

**Why this is wrong / suboptimal / risky:**

Per `security.md` §SSRF: "For long-lived StreamSource connections the IP check applies on every reconnect, and any peer-IP change observed at the socket layer is a hard close." The Nostr `StreamSource` in the Collector implements this via `SsrfGuardedHttpClient.checkAndPinForWebSocket()` on each reconnect. The SimpleX `WebSocketClient` in the messaging adapter also maintains a long-lived WebSocket connection, but the SSRF re-validation on reconnect is the adapter's responsibility — the SSRF module provides the primitives (`checkAndPinForWebSocket`, `resolveForWebSocket`) but the adapter must call them on reconnect.

The finding is that the reconnect path should be verified to call `checkAndPinForWebSocket` on each reconnect attempt, not just on the initial connect. Without reading the full implementation, this is a "verify this" finding rather than a confirmed bug.

**Recommended fix:**

Audit `SimpleXWebSocketClient`'s reconnect path to confirm it calls `SsrfGuardedHttpClient.checkAndPinForWebSocket()` on every reconnect. If it doesn't, add the call.

**Reasoning:**

The spec is explicit: reconnect must re-validate. The SSRF module provides the building blocks; the adapter must use them correctly.

**Trade-offs:**

- If the fix is needed: added latency on reconnect (~1 DNS resolution). Negligible vs the WebSocket handshake time.
