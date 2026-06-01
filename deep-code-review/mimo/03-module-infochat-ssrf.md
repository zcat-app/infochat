# Deep code review: module infochat-ssrf

**Target:** module infochat-ssrf
**Lens:** module
**Module path:** infochat-ssrf/
**Date:** 2026-06-02 00:05
**Reviewer:** senior-developer (mimo)

## Headline findings

- [medium] PERFORMANCE — SsrfGuardedHttpClient.java:420 — `readBounded` creates a new platform-thread executor per HTTP request; JDK 25 virtual threads would be strictly cheaper
- [low] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:40-46 — Class-level Javadoc claims ws/wss are "deliberately rejected for now" but the class supports them through `checkAndPinForWebSocket` / `resolveForWebSocket`
- [low] SECURITY — SsrfGuardedHttpClient.java:273 — `canonicalizeHost` uses `IDN.ALLOW_UNASSIGNED`, processing unassigned Unicode code points in a security-critical SSRF canonicalization path

## Detail

### F1. Platform thread executor created per body-read phase

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** SsrfGuardedHttpClient.java:420-424

**Current code:**

```java
ExecutorService readerExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "ssrf-body-reader");
    t.setDaemon(true);
    return t;
});
```

**Why this is wrong / suboptimal / risky:**

Every `get()` call creates a new `SingleThreadExecutor` backed by a platform thread. The global `ReentrantLock` is released before the body-read phase (line 359-362), so concurrent fetches to different hosts can have overlapping body-read phases, each spawning its own platform thread. Under the Collector's multi-source RSS cadence or the Provider's `/add-source` probe bursts, this produces thread creation/teardown churn proportional to concurrent fetch count.

The project targets JDK 25 + Quarkus 3.33 LTS, which provides virtual threads. A virtual thread blocks on `in.read(buf)` identically to a platform thread but consumes no OS thread slot while parked. The `readFuture.cancel(true)` and `shutdownNow()` interrupt semantics work the same way on virtual threads.

**Recommended fix:**

```java
ExecutorService readerExecutor = Executors.newVirtualThreadPerTaskExecutor();
```

No other changes needed — the rest of `readBounded` (the `submit`, `get(timeout)`, `cancel`, `shutdownNow` sequence) works identically with a virtual-thread executor.

**Reasoning:**

Virtual threads are the idiomatic JDK 25 mechanism for wrapping blocking I/O with a timeout. The single-thread executor was the correct choice on JDK 17/21 without virtual threads; on JDK 25 it is strictly worse: an OS thread is allocated, parked on the socket read, and torn down for every request, while a virtual thread does the same work with a continuation on the carrier pool.

**Trade-offs:**

None -- the fix is strictly better. Virtual threads have identical interruption semantics for blocking socket reads. The `readFuture.get(timeout)` call parks the calling virtual thread, which is free.

---

### F2. Stale class-level Javadoc rejects ws/wss but code supports them

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** SsrfGuardedHttpClient.java:40-46

**Current code:**

```java
 *   <li><strong>Scheme allowlist</strong> — only {@code http} and
 *       {@code https} are dialed. {@code ws} and {@code wss} are
 *       deliberately rejected for now: the WebSocket transport
 *       wrapper for {@code StreamSource} consumes the same
 *       {@link IpBlocklist} policy class but is its own
 *       implementation (carved out per the ticket's
 *       {@code out_of_scope}).</li>
```

**Why this is wrong / suboptimal / risky:**

The class defines `WEBSOCKET_SCHEMES = Set.of("ws", "wss")` (line 121) and exposes two public WebSocket-aware methods: `checkAndPinForWebSocket(URI)` (line 502) and `resolveForWebSocket(URI)` (line 536). Both validate against the WebSocket scheme set. The Javadoc bullet was accurate for M1-025 (HTTP-only) but M1-026 added WebSocket support. A reader relying on the class-level Javadoc would conclude ws/wss are unsupported and look for a separate WebSocket wrapper that does not exist.

**Recommended fix:**

Replace lines 40-46 with:

```java
 *   <li><strong>Scheme allowlist</strong> — {@code http} and
 *       {@code https} are accepted by {@link #get(URI)} and
 *       {@link #get(URI, Map)}. {@code ws} and {@code wss} are
 *       accepted by {@link #checkAndPinForWebSocket(URI)} and
 *       {@link #resolveForWebSocket(URI)}. A misrouted scheme
 *       raises {@link SsrfPolicyException}; the two transport
 *       surfaces do not overlap.</li>
```

**Reasoning:**

The class-level Javadoc is the first thing a reader sees. When it contradicts the method surface, the reader wastes time looking for a nonexistent separate class.

**Trade-offs:**

None -- the fix is documentation-only.

---

### F3. `IDN.ALLOW_UNASSIGNED` in security-critical host canonicalization

- **Category:** SECURITY
- **Severity:** low
- **Location:** SsrfGuardedHttpClient.java:273

**Current code:**

```java
String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
```

**Why this is wrong / suboptimal / risky:**

`IDN.ALLOW_UNASSIGNED` instructs `toASCII` to process labels containing Unicode code points that are not assigned in the current Unicode version. This is the "lookup" semantics from IDNA2003, intended for resolving user-typed input against a resolver that may also accept unassigned code points.

In the SSRF canonicalization path, the concern is defense-in-depth: accepting unassigned code points widens the input surface that the SSRF guard will pass through without rejection. If a future Unicode version assigns a currently-unassigned code point with a non-identity mapping, the ACE form of a hostname containing that code point would change between JDK versions, potentially breaking pin-key stability across upgrades.

The practical exploitability is low -- the pin is always consistent within a single JVM because both the install side and the lookup side call the same `canonicalizeHost` method. But the defense-in-depth argument favors the stricter form: `IDN.toASCII(host, 0)` rejects unassigned code points, shrinking the SSRF guard's acceptance surface to only code points that have a stable, deterministic mapping today.

**Recommended fix:**

```java
String ascii = IDN.toASCII(host, 0);
```

**Reasoning:**

`IDN.toASCII(host, 0)` applies the strictest IDN processing: unassigned code points cause an `IllegalArgumentException`, which the caller already wraps as `SsrfPolicyException("invalid host: ...")`. This means hostnames with unassigned code points are rejected at the SSRF gate rather than passed through. The security model's fail-closed stance favors rejecting ambiguous inputs.

**Trade-offs:**

Hostnames containing unassigned Unicode code points (rare in practice -- most real-world hostnames are ASCII or use assigned code points) would be rejected by the SSRF gate. The caller sees `SsrfPolicyException("invalid host: ...")`, which is the correct fail-closed behavior for a security gate. If a future Unicode version assigns those code points, `IDN.toASCII(host, 0)` would then accept them with the correct mapping -- no code change needed.

**Alternative options:**

- **Option A** (recommended) -- `IDN.toASCII(host, 0)` -- strictest; rejects unassigned code points
- **Option B** -- keep `ALLOW_UNASSIGNED` but add a `// WHY:` comment explaining the trade-off -- pros: no behavior change; cons: the wider acceptance surface remains
