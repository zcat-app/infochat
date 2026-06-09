# Deep code review: module infochat-ssrf

**Target:** module infochat-ssrf
**Lens:** module
**Module path:** infochat-ssrf/
**Date:** 2026-06-08 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [low] PERFORMANCE — SsrfGuardedHttpClient.java:552-553 — a fresh virtual thread plus `FutureTask` is created for every 8 KiB `in.read()`, i.e. ~1280 short-lived threads per full-cap body on the hot fetch path.
- [low] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:449-455 — `effectivePort` hardcodes the https/http default-port pair and returns 80 for `wss`, a latent wrong-default that is currently unreachable but co-located with WS-aware code.
- [low] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:197-199 — `blocklist == null` is a defensive null-check between internal/trusted callers, redundant under the null-marked package contract (§7 / §7a).

## Detail

### F1. Virtual-thread-per-read in the body reader

- **Category:** PERFORMANCE
- **Severity:** low
- **Location:** SsrfGuardedHttpClient.java:552-553 (loop body of `readBounded`, lines 530-593)

**Current code:**

```java
byte[] buf = new byte[8192];
long total = 0;
while (true) {
    ...
    FutureTask<Integer> readTask = new FutureTask<>(() -> in.read(buf));
    BODY_READER_THREAD_FACTORY.newThread(readTask).start();
    int n;
    try {
        n = readTask.get(readBudgetMillis, TimeUnit.MILLISECONDS);
    ...
```

**Why this is wrong / suboptimal / risky:**

`get()` is the primary hot path for the Collector — every RSS/feed fetch and every `/add-source` probe runs through it. With an 8 KiB buffer and the default 10 MiB body cap, a full-size body spins on the order of 1280 fresh virtual threads plus 1280 `FutureTask` allocations and 1280 `get(timeout)` park/unpark cycles, all sequential (the next read cannot start until the current one returns). Virtual threads are cheap, but "cheap" is not "free": each iteration pays a thread creation, a `FutureTask` allocation, and a timed `get` that wakes the carrier thread. The watchdog requires *a* second thread because `InputStream.read` is not natively cancellable, but it does not require *a new* thread per read.

This is not a correctness problem and the design is deliberate (the `B-READBOUNDED-EXECUTOR` comment documents the choice), so it is recorded as low rather than inflated.

**Recommended fix:**

Reuse one supervised reader thread per call by handing successive read tasks to a single-thread structure, or — simpler — keep the current shape but raise the buffer to 64 KiB so the thread/allocation count drops ~8x for the same body:

```java
byte[] buf = new byte[64 * 1024];
```

**Reasoning:**

A larger buffer cuts the per-read fixed overhead by the same factor without changing any of the watchdog semantics (each `read` still runs under `min(readTimeout, remaining)` and the total deadline still bounds the phase). 64 KiB is still tiny against the 10 MiB cap and bounds worst-case wasted allocation on a small body to one buffer.

**Trade-offs:**

A 64 KiB buffer is allocated even for a 200-byte body, so the smallest responses pay a slightly larger transient allocation. Negligible against the connection and TLS cost already incurred. The per-read latency granularity also coarsens (a stalled read is detected at most one read-budget later regardless of buffer size, so this is unaffected).

**Alternative options:**

- **Option A** (raise buffer size — above).
- **Option B** — supervise reads from one reused thread per call (e.g. a single-thread executor created at method entry, shut down in the outer `finally`). Pros: removes per-read thread churn entirely. Cons: more code, an executor lifecycle to manage, and the cancellation-on-timeout story (`shutdownNow` + interrupt) is no simpler than the current `cancel(true)`, so the win over Option A is marginal.

---

### F2. `effectivePort` returns the wrong default for `wss`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** SsrfGuardedHttpClient.java:449-455

**Current code:**

```java
private static int effectivePort(URI uri) {
    int port = uri.getPort();
    if (port != -1) {
        return port;
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
}
```

**Why this is wrong / suboptimal / risky:**

The helper maps an unset port to 443 only for `https`; every other scheme — including `wss`, whose default port is 443 — falls to 80. `effectivePort` is only called from `isCrossOrigin`, which is only called inside the `get()` redirect loop, and `get()` rejects `ws`/`wss` at the scheme gate, so today no `wss` URI ever reaches this code. The bug is latent. But this class is explicitly WebSocket-aware (`WEBSOCKET_SCHEMES`, `checkAndPinForWebSocket`, `resolveForWebSocket`), so a future change that reuses `effectivePort` or `isCrossOrigin` for a WS path — the natural place to add WS redirect or origin checks — would silently treat `wss://h/` and `wss://h:443/` as different origins and, worse, treat `wss://h/` (resolved to 80) and `ws://h/` (also 80) as same-origin. A same-origin misjudgement in credential-scrub logic is a credential-leak shape.

**Recommended fix:**

```java
private static int effectivePort(URI uri) {
    int port = uri.getPort();
    if (port != -1) {
        return port;
    }
    return switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
        case "https", "wss" -> 443;
        default -> 80;
    };
}
```

**Reasoning:**

Encoding the secure-scheme default-port pair explicitly makes the helper correct for every scheme the module knows about and matches the project's switch-expression preference. The lowercase fold matches how schemes are compared everywhere else in the file.

**Trade-offs:**

None — the fix is strictly better and behavior on the currently-live `http`/`https` paths is unchanged.

---

### F3. Redundant defensive null-check on `blocklist`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** SsrfGuardedHttpClient.java:197-199

**Current code:**

```java
if (blocklist == null) {
    throw new IllegalArgumentException("blocklist must be configured");
}
```

**Why this is wrong / suboptimal / risky:**

Every `app.zcat.infochat` package is null-marked (§7a, D48): `blocklist` is a bare reference-type parameter, so "never null" is the machine-checked contract and a caller passing null fails the build, not a runtime check. §7 (no defensive code for impossible scenarios) classifies a null-check for a parameter callers cannot legally pass null for as scope drift. The timeout/cap checks immediately below it are different: an unset or non-positive timeout is a documented configuration error (`security.md` §SSRF: "an unset timeout is a configuration error"), and those values originate from config parsing — a legitimate boundary. `blocklist`, `redirectCap`, and `bodyCap` carry no such config-boundary justification; `blocklist == null` in particular is pure paranoia under the null contract.

**Recommended fix:**

Drop the null branch:

```java
// (delete lines 197-199)
```

**Reasoning:**

Removing it leaves the contract enforced where the project decided to enforce it — the compiler — and removes a branch that can never be taken in a green build. The positive (config-boundary) checks for timeouts stay, because they validate *values*, not nullity, and those values cross the config boundary.

**Trade-offs:**

A non-null-marked external module (a sibling that opted out of the null-marking, or a future non-Java consumer) could pass null and get a `NullPointerException` later instead of an `IllegalArgumentException` here. In this repo every module is null-marked, so the scenario is the "impossible scenario" §7 names. If the reviewer wants belt-and-suspenders for the public constructor specifically, that is a deliberate boundary call — but then it should be applied consistently (it is not: `requestTimeout`/`readTimeout` get value-checks but `resolverSeam` gets none), and the inconsistency is itself the smell.

---

## Synthesizer-relevant observations

- The blocklist, DNS-rebind pinning, redirect re-validation, per-hop scheme/userinfo gating, transition-form embedded-IPv4 decoding (6to4/Teredo/NAT64/IPv4-compatible/IPv4-mapped), host-own-interface checks (including the embedded-v4 host-interface hop), and the negative-control tests for all of them are thorough and match the spec commitments in `security.md` §SSRF and outbound connections. The `latest-wins` per-host pin semantics under concurrency are a documented, sound TOCTOU posture (every served IP passed the blocklist in some still-active holder's validation) and are not a finding.
- `pom.xml` depends on no infochat sibling module (only `junit-jupiter`, test scope), satisfying the "lowest in the DAG" constraint for this module.
- The `JVM-wide` resolver registered via `META-INF/services/java.net.spi.InetAddressResolverProvider` is a process-global side effect installed by merely having this jar on the classpath. That is a cross-module/architecture concern (every DNS lookup in either service routes through `ForwardingResolver`), correct for the design but worth confirming under the architecture lens that no other module installs a competing `InetAddressResolverProvider` (the JDK ServiceLoader loads exactly one).
