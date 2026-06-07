# Deep code review: module

**Target:** module infochat-ssrf
**Lens:** module
**Module path:**
    infochat-ssrf/
**Date:** 2026-06-07 00:57
**Reviewer:** senior-developer (opus)

## Headline findings

- [HIGH] PERFORMANCE — PinnedDnsResolver.java:111-118 / SsrfGuardedHttpClient.java:347-398 — a single JVM-wide pin slot guarded by one global lock serializes ALL outbound connection establishment (every feed fetch hop and every WebSocket relay dial); one slow or hostile upstream can hold the lock ~2-3 minutes per call.
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClientTest.java:66-68 (and 10 more sites) — tests assert on exception message text while `SsrfPolicyException`'s own contract says callers branch on `reason()` and the message is "free to reword"; the contract and the test suite directly contradict each other.
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:599-614 — the entire WebSocket public surface (`checkAndPinForWebSocket`, `resolveForWebSocket`, `PinnedDial`) has zero module-local tests; in particular the unlock-on-throw path is untestable by the existing downstream tests because `ReentrantLock` is reentrant, so a regression there would pass the whole suite and deadlock production.
- [LOW] SECURITY — IpBlocklist.java:201-223 — deprecated IPv6 site-local range `fec0::/10` is not blocked, although the blocklist's own standard already covers other deprecated forms (`::a.b.c.d`).
- [LOW] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:442-445 — scheme allowlist comparison is case-sensitive (`HTTP://…` is rejected) while `isCrossOrigin` case-folds schemes; RFC 3986 schemes are case-insensitive and the spec commits to allowing `http`/`https`/`ws`/`wss`.
- [LOW] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClientTest.java:72-83 — `rejectsWebsocketSchemeForNow` name and comment claim ws/wss support will land as "a separate WebSocket-aware wrapper", but that surface now exists in this very class; the comment is stale and misleading.

## Detail

### F1. JVM-wide single pin slot + global lock serializes all outbound connection establishment

- **Category:** PERFORMANCE
- **Severity:** high
- **Location:** PinnedDnsResolver.java:111-118, SsrfGuardedHttpClient.java:347-398, SsrfGuardedHttpClient.java:599-614

**Current code:**

```java
// PinnedDnsResolver.Provider
private static final ReentrantLock LOCK = new ReentrantLock();

// ACTIVE_PINS is mutated only while LOCK is held; reads are
// unsynchronized but volatile, so the JDK's DNS-lookup
// threads see a consistent snapshot (either null or a fully
// populated immutable map). @Nullable: the slot is empty
// (null) whenever no wrapper call holds a pin.
private static volatile @Nullable Map<String, List<InetAddress>> ACTIVE_PINS;
```

```java
// SsrfGuardedHttpClient.get — lock held across the entire redirect loop,
// including per-hop DNS resolution, connect, and header receipt:
ReentrantLock lock = PinnedDnsResolver.Provider.lock();
lock.lock();
try {
    URI current = uri;
    int redirectCount = 0;
    while (true) {
        ResolvedHost resolved = resolveAndValidate(current, HTTP_SCHEMES);
        PinnedDnsResolver.Provider.installPins(
            Map.of(resolved.canonicalHost(), resolved.addresses()));
        ...
        HttpResponse<InputStream> response =
            perCallClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        ...
    }
} finally {
    PinnedDnsResolver.Provider.clearPins();
    lock.unlock();
}
```

**Why this is wrong / suboptimal / risky:**

The pin slot holds exactly one map at a time, so correctness forces every caller to hold the global lock for the whole connection-establishment phase. The hold time per `get()` is, per hop: validation-time DNS lookup (`InetAddress.getAllByName` — no JDK timeout knob; OS resolver timeouts are typically 5-30s) + connect (default 5s) + header receipt (default `requestTimeout` 30s), times up to 4 hops (initial + `redirectCap` 3). An upstream that the wrapper dials — i.e. attacker-controllable content, since redirect targets are attacker-chosen — can engineer a hold of roughly 2-3 minutes per call without tripping any configured timeout.

The class javadoc on `PinnedDnsResolver` says "Concurrent wrapper calls serialize on the lock — acceptable for v1's RSS cadence", but that justification predates the WebSocket surface: `checkAndPinForWebSocket` hands the held lock to the caller via `PinnedDial`, and the Collector's `NostrRelayConnection.connectAndSubscribe` holds it across the entire WebSocket handshake (up to ~11s per relay connect attempt, on every reconnect of every relay). In one Collector JVM, all feed fetches and all relay (re)connects now serialize on this single lock. One hostile feed converts into a near-total outbound stall for the whole Collector — a DoS amplification, not just a throughput ceiling. Even in the benign case, N sources × per-source connect+header latency serializes, so fetch-cycle wall time grows linearly with source count regardless of available concurrency.

The serialization is not required for correctness. Pins are keyed by canonical host; pins for different hosts cannot conflict, and concurrent pins for the same host can be safely merged: every address in both sets passed the blocklist at validate time, so the union is all-validated.

A secondary wart of the current shape: `PinnedDial.close()` calls `lock.unlock()`, which throws `IllegalMonitorStateException` if invoked from a different thread than the one that acquired the lock — an undocumented thread-affinity constraint on a public API. The fix below dissolves it.

**Recommended fix:**

```java
// PinnedDnsResolver.Provider — replace the single slot + global lock with a
// per-host refcounted pin map. Union-merge is safe: both address sets passed
// the IpBlocklist at validate time, so every address in the union is validated.
private static final ConcurrentHashMap<String, PinEntry> ACTIVE_PINS = new ConcurrentHashMap<>();

private record PinEntry(int refCount, List<InetAddress> addresses) {}

static void installPin(String canonicalHost, List<InetAddress> addresses) {
    ACTIVE_PINS.merge(canonicalHost, new PinEntry(1, List.copyOf(addresses)),
        (old, fresh) -> new PinEntry(old.refCount() + 1,
            Stream.concat(old.addresses().stream(), fresh.addresses().stream())
                  .distinct().toList()));
}

static void releasePin(String canonicalHost) {
    ACTIVE_PINS.computeIfPresent(canonicalHost,
        (host, entry) -> entry.refCount() == 1
            ? null
            : new PinEntry(entry.refCount() - 1, entry.addresses()));
}

// ForwardingResolver.lookupByName:
PinEntry entry = ACTIVE_PINS.get(canonicalHost);
if (entry != null) {
    return entry.addresses().stream();
}
return BUILTIN.lookupByName(host, lookupPolicy);
```

`SsrfGuardedHttpClient.get` then drops the global lock entirely; each hop does `installPin(host, addrs)` before `send` and `releasePin(host)` after headers arrive (or in a per-hop `finally`). `PinnedDial` carries its canonical host and its `close()` calls `releasePin(host)` — no lock, no thread affinity.

**Reasoning:**

Pins become per-host and refcounted, so concurrent fetches to different hosts proceed fully in parallel, and concurrent fetches to the same host see the union of validated addresses — still strictly inside the validated set. The security property ("the JDK never dials an IP this wrapper did not validate") is preserved verbatim, while the global serialization, the DoS amplification, and the `PinnedDial` thread-affinity hazard all disappear. The redirect loop's per-hop install/release also removes the current behavior where a stale pin from hop N is visible during hop N+1's validation.

**Trade-offs:**

- Slightly more code (refcounting, per-hop release bookkeeping) than the single slot.
- During an overlap, a same-host caller may connect to an address validated by the *other* caller's resolution — all such addresses passed the blocklist, so this is a semantic widening but not a policy widening.
- Pre-existing tests that reach into `Provider.lock()` / `installPins` / `clearPins` (package-private surface) need updating; per §8, the ticket must explicitly authorize those test modifications.

**Alternative options:**

- **Option A** (the recommended fix above).
- **Option B** — keep the global lock but shrink the worst-case hold: dedicated short header-timeout for redirect hops, bound the validation-time DNS lookup with the same virtual-thread watchdog used in `readBounded`. Pros: smaller diff. Cons: still serializes all outbound establishment; only caps the abuse window (to perhaps ~30-45s), does not remove the scalability ceiling, and leaves the `PinnedDial` thread-affinity wart.
- **Option C** — per-host striped locks over the existing slot design. Pros: intermediate complexity. Cons: still needs a map of pins anyway, at which point Option A is simpler.

---

### F2. Tests freeze exception message text that the production contract declares rewordable

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** SsrfGuardedHttpClientTest.java:66, 80, 90, 104, 136, 174, 196, 212, 228, 358, 429

**Current code:**

```java
// SsrfGuardedHttpClient.SsrfPolicyException javadoc:
 * {@link #reason()} carries the typed
 * failure mode — callers branch on it, never on message text
 * (the message is human-facing and free to reword).
```

```java
// SsrfGuardedHttpClientTest.rejectsNonHttpScheme:
SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
    () -> client.get(URI.create("ftp://example.com/")));
assertTrue(ex.getMessage().contains("scheme not allowed"),
    "non-http(s) scheme must be rejected with the literal "
    + "\"scheme not allowed\" prefix");
```

**Why this is wrong / suboptimal / risky:**

Every policy-violation test in this file asserts on a substring of `getMessage()`, several of them explicitly demanding "the literal X prefix". The production class promises the opposite: messages are human-facing and free to reword; `Reason` is the machine contract. As written, any message reword breaks eleven tests, which means the messages are *not* free to reword — the javadoc contract is false in practice. The `Reason` enum was added precisely to carry the typed failure mode, and the module's own tests do not use it once. This trains future callers (the tests are the most-read usage examples) to branch on text, the exact anti-pattern the contract forbids.

**Recommended fix:**

```java
SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
    () -> client.get(URI.create("ftp://example.com/")));
assertEquals(SsrfPolicyException.Reason.SCHEME_NOT_ALLOWED, ex.reason(),
    "non-http(s) scheme must surface SCHEME_NOT_ALLOWED");
```

Apply the same `reason()` assertion to all eleven sites (`USERINFO_NOT_ALLOWED`, `BLOCKED_IP`, `BODY_CAP_EXCEEDED`, `REDIRECT_CAP_EXCEEDED`, `BODY_READ_TIMEOUT`, `BODY_READ_DEADLINE_EXCEEDED`).

**Reasoning:**

Asserting on `reason()` is strictly stronger (exact equality on a typed value vs. substring on prose), makes the javadoc contract true, and decouples message wording from the test suite. The exception messages can then be improved (e.g. to include the redacted URL via `UrlRedactor`) without touching tests.

**Trade-offs:**

These are pre-existing tests; under §8 test-modification authorization, the change needs a ticket that states the new assertions and why. Constructor-validation tests (`constructorRejectsNullTimeout` etc.) assert on `IllegalArgumentException` text, which has no typed alternative — those can keep their message checks.

---

### F3. WebSocket public surface has no module-local tests; the unlock-on-throw guarantee is untested anywhere

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** SsrfGuardedHttpClient.java:599-614 (untested code); infochat-ssrf/src/test (absent coverage)

**Current code:**

```java
public @NonNull PinnedDial checkAndPinForWebSocket(@NonNull URI uri) {
    ReentrantLock lock = PinnedDnsResolver.Provider.lock();
    lock.lock();
    try {
        ResolvedHost resolved = resolveAndValidate(uri, WEBSOCKET_SCHEMES);
        PinnedDnsResolver.Provider.installPins(
            Map.of(resolved.canonicalHost(), resolved.addresses()));
        return new PinnedDial(lock, resolved.addresses());
    } catch (RuntimeException | Error e) {
        // Release the lock if the validation or pin install threw —
        // a thrown checkAndPinForWebSocket must not leave the
        // JVM-wide lock held.
        lock.unlock();
        throw e;
    }
}
```

**Why this is wrong / suboptimal / risky:**

`checkAndPinForWebSocket`, `resolveForWebSocket`, and `PinnedDial` are public API of this module, and this module's test suite exercises none of them. Coverage exists only downstream (`NostrSsrfTest` / `NostrSsrfIT` in infochat-collector), and that coverage cannot catch the most dangerous regression here: if the `catch`/`unlock` block were lost or reordered, the JVM-wide lock leaks on every refused dial. The downstream tests call `connectAndSubscribe` repeatedly *from the same thread*; `ReentrantLock` is reentrant per-thread, so a leaked lock re-acquires successfully and every existing test stays green — while in production the first refused relay dial permanently deadlocks all outbound HTTP and WebSocket traffic in that JVM. A one-line regression with total-outage impact that the entire suite cannot detect is exactly the gap module-local tests must close. Secondary untested behaviors: `get()`'s rejection of `ws`/`wss` is tested, but the inverse gating (`checkAndPinForWebSocket` rejects `http`/`https`, accepts `ws`/`wss`) and `PinnedDial.addresses()` content are not.

**Recommended fix:**

```java
@Test
void checkAndPinRefusalReleasesJvmWideLock() throws Exception {
    SsrfGuardedHttpClient strict = new SsrfGuardedHttpClient();
    // 127.0.0.1 is blocked by the strict blocklist -> validation throws
    // inside checkAndPinForWebSocket, after lock acquisition.
    assertThrows(SsrfPolicyException.class,
        () -> strict.checkAndPinForWebSocket(URI.create("ws://127.0.0.1:1/")));

    // Probe from ANOTHER thread: ReentrantLock is reentrant, so a
    // same-thread re-acquire would succeed even if the unlock-on-throw
    // regressed. Only a foreign thread can observe the leak.
    AtomicBoolean acquired = new AtomicBoolean();
    Thread probe = Thread.ofVirtual().start(() -> {
        ReentrantLock lock = PinnedDnsResolver.Provider.lock();
        if (lock.tryLock()) {
            acquired.set(true);
            lock.unlock();
        }
    });
    probe.join();
    assertTrue(acquired.get(),
        "a refused checkAndPinForWebSocket must not leave the JVM-wide lock held");
}

@Test
void webSocketEntrypointsRejectHttpSchemes() {
    SsrfGuardedHttpClient client = testModeClient();
    assertEquals(SsrfPolicyException.Reason.SCHEME_NOT_ALLOWED,
        assertThrows(SsrfPolicyException.class,
            () -> client.resolveForWebSocket(URI.create("https://example.com/"))).reason());
}
```

The test class lives in `app.zcat.infochat.ssrf`, so the package-private `Provider.lock()` accessor is reachable without widening visibility.

**Reasoning:**

The unlock-on-throw test makes the one-line catastrophic regression mechanically detectable, and it can only live in this module (downstream tests do not reach the package-private lock). The scheme-gating test pins the deliberate non-overlap between `HTTP_SCHEMES` and `WEBSOCKET_SCHEMES` that the class comment calls load-bearing.

**Trade-offs:**

None — the fix is strictly better. (If F1's per-host redesign lands, the lock-leak test is rewritten against the refcount invariant instead; the coverage requirement is the same.)

---

### F4. Deprecated IPv6 site-local range fec0::/10 is not blocked

- **Category:** SECURITY
- **Severity:** low
- **Location:** IpBlocklist.java:201-223

**Current code:**

```java
private static boolean isBlockedV6(byte[] raw) {
    if (isAllZeroV6(raw)) {
        return true;
    }
    if (isLoopbackV6(raw)) {
        return true;
    }
    int b0 = raw[0] & 0xFF;
    int b1 = raw[1] & 0xFF;
    // fe80::/10 — link-local.
    if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
        return true;
    }
    // fc00::/7 — unique-local.
    if ((b0 & 0xFE) == 0xFC) {
        return true;
    }
    // ff00::/8 — multicast.
    if (b0 == 0xFF) {
        return true;
    }
    return false;
}
```

**Why this is wrong / suboptimal / risky:**

`fec0::/10` (site-local, RFC 3879-deprecated) falls through: `b0 == 0xFE` but `(b1 & 0xC0) == 0xC0`, so the link-local check misses it, and `(0xFE & 0xFE) == 0xFE ≠ 0xFC`, so the unique-local check misses it too. The spec's blocklist intent ("private, loopback, link-local, … and IPv6 equivalents") treats site-local as the deprecated IPv6 private range — exactly the category this list exists to block. The module already applies the deprecated-forms standard elsewhere: the RFC 4291-deprecated IPv4-compatible `::a.b.c.d` form is decoded and blocked. An OS will still route `fec0::/10` if a legacy internal network publishes a route for it, so an attacker whose hostname resolves to `fec0::…` reaches an internal IPv6 host that every other private-range spelling would have blocked. Exploitability is low in practice (site-local deployments are rare in 2026), hence the severity, but the inconsistency against the module's own deprecated-forms standard is real and the fix is two lines.

**Recommended fix:**

```java
    // fec0::/10 — deprecated site-local (RFC 3879); the legacy IPv6
    // private range. Deprecated but still routable where legacy
    // internal networks publish routes for it, so blocked for the
    // same reason as fc00::/7.
    if (b0 == 0xFE && (b1 & 0xC0) == 0xC0) {
        return true;
    }
```

Plus one test in `IpBlocklistTest` (`blocksIpv6SiteLocal` on `fec0::1`).

**Reasoning:**

Closes the last fe-prefixed private-ish gap; with this, fe80::/10, fec0::/10, and fc00::/7 cover every non-global fc/fe-prefixed range. Cannot over-block: `fec0::/10` has no legitimate public assignment.

**Trade-offs:**

None — the fix is strictly better.

---

### F5. Scheme allowlist comparison is case-sensitive, inconsistent with the module's own origin comparison

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** SsrfGuardedHttpClient.java:442-445

**Current code:**

```java
String scheme = uri.getScheme();
if (scheme == null || !allowedSchemes.contains(scheme)) {
    throw new SsrfPolicyException(
        SsrfPolicyException.Reason.SCHEME_NOT_ALLOWED, "scheme not allowed: " + scheme);
}
```

**Why this is wrong / suboptimal / risky:**

RFC 3986 §3.1: scheme names are case-insensitive, and "an implementation should accept uppercase letters as equivalent to lowercase". `URI.getScheme()` preserves input case, so `HTTP://feed.example.com/rss` is rejected with `scheme not allowed: HTTP`. The spec commits to allowing `http`/`https`/`ws`/`wss` as schemes — `HTTP` *is* the `http` scheme, so rejecting it drifts from that commitment. The inputs reaching this gate include operator-pasted URLs (`/add-source`, `bootstrap-sources.json`), where uppercase or mixed-case schemes occur in the wild. The failure is fail-closed (no security impact), but it is an over-block with a confusing operator-facing error, and it is internally inconsistent: `isCrossOrigin` ten lines down compares schemes with `equalsIgnoreCase`.

**Recommended fix:**

```java
String scheme = uri.getScheme();
if (scheme == null || !allowedSchemes.contains(scheme.toLowerCase(Locale.ROOT))) {
    throw new SsrfPolicyException(
        SsrfPolicyException.Reason.SCHEME_NOT_ALLOWED, "scheme not allowed: " + scheme);
}
```

**Reasoning:**

One-line change; aligns with RFC 3986, with the spec's scheme-allowlist commitment, and with the module's own `isCrossOrigin` case handling. `Locale.ROOT` avoids the Turkish-dotless-i hazard, matching `canonicalizeHost`'s precedent.

**Trade-offs:**

None — the fix is strictly better.

---

### F6. Stale test name and comment claim WebSocket support lives in a future separate wrapper

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** SsrfGuardedHttpClientTest.java:72-83

**Current code:**

```java
@Test
void rejectsWebsocketSchemeForNow() {
    // ws/wss are spec-allowed but carved out of this ticket per
    // out_of_scope. The wrapper must reject them with the same
    // literal so the future StreamSource ticket can widen the
    // allowlist without contradicting committed test text.
    SsrfGuardedHttpClient client = testModeClient();
    SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
        () -> client.get(URI.create("wss://example.com/relay")));
    assertTrue(ex.getMessage().contains("scheme not allowed"),
        "ws/wss is rejected by this wrapper; the StreamSource "
        + "ticket rejects a separate WebSocket-aware wrapper");
}
```

**Why this is wrong / suboptimal / risky:**

The comment describes a world that no longer exists: the WebSocket-aware surface did not land as "a separate wrapper" — it landed as `checkAndPinForWebSocket` / `resolveForWebSocket` on this same class, and `get()`'s rejection of `ws`/`wss` is now a permanent, deliberate transport split (documented at SsrfGuardedHttpClient.java:111-118), not a temporary carve-out. The name `…ForNow` tells a reader the behavior is provisional when it is contractual. Tests are documentation; this one documents the wrong contract.

**Recommended fix:**

```java
@Test
void getRejectsWebsocketSchemes() {
    // ws/wss run through the dedicated WebSocket entrypoints
    // (checkAndPinForWebSocket / resolveForWebSocket); get() dials
    // only http/https. The two scheme sets deliberately do not
    // overlap — a misrouted scheme is a programming error.
    SsrfGuardedHttpClient client = testModeClient();
    SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
        () -> client.get(URI.create("wss://example.com/relay")));
    assertEquals(SsrfPolicyException.Reason.SCHEME_NOT_ALLOWED, ex.reason());
}
```

**Reasoning:**

Aligns the test's documentation with the implemented contract and removes the false "temporary" signal. Folding in the `reason()` assertion matches F2.

**Trade-offs:**

Pre-existing test rename/modification requires explicit ticket authorization per §8; cheapest bundled with the F2 ticket.

---

## Synthesizer-relevant observations

- Downstream tests in infochat-collector (`NostrSsrfTest`, `NostrSsrfIT`) also assert on `SsrfPolicyException` message text ("blocked IP") rather than `reason()` — the F2 drift crosses module boundaries.
- The `LoopbackPermitting` `IpBlocklist` test subclass is re-implemented in at least three test files (this module's `SsrfGuardedHttpClientTest`, collector's `NostrSsrfTest`/`NostrSsrfIT`/`NostrStreamSourceTest`); a shared test fixture would remove the duplication, but where it should live is a cross-module decision.
- F1's lock-hold-across-handshake amplification involves the caller in infochat-collector (`NostrRelayConnection.connectAndSubscribe` holds the `PinnedDial` across `buildAsync(...).get(~11s)`); the architecture pass should weigh the global-pin design against the cross-module call pattern.
