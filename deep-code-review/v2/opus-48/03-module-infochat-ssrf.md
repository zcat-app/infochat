# Deep code review: module infochat-ssrf

**Target:** module infochat-ssrf
**Lens:** module
**Module path:** infochat-ssrf/
**Date:** 2026-06-06
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] SECURITY — infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:384 — a malformed attacker-controlled `Location` header escapes as a raw `IllegalArgumentException`, bypassing the documented `SsrfPolicyException`/`IOException` contract that every caller's error classification is built on.
- [medium] PERFORMANCE — infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:347-398 — the JVM-wide exclusive pin lock is held across connect + header receipt of every hop; one slow or hostile origin can hold it for ~140 s per call, serializing all outbound HTTP and WebSocket dials in the service.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientTest.java:64-68 (and 9 sibling assertions) — every policy test asserts on the human-facing message text that the exception javadoc declares non-contractual; `SsrfPolicyException.reason()` — the actual contract callers branch on — is never asserted in the module.
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientTest.java:72-83 — `rejectsWebsocketSchemeForNow` comment claims WebSocket support lives in "a separate WebSocket-aware wrapper", but the entrypoints have since landed in this same class; the narrative is stale and misleading.

## Detail

### F1. Malformed `Location` header escapes the typed exception contract as `IllegalArgumentException`

- **Category:** SECURITY
- **Severity:** medium
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:380-389

**Current code:**

```java
String location = response.headers().firstValue("Location")
    .orElseThrow(() -> new SsrfPolicyException(
        SsrfPolicyException.Reason.REDIRECT_LOCATION_MISSING,
        "redirect response missing Location header"));
URI next = current.resolve(location);
```

**Why this is wrong / suboptimal / risky:**

`location` is attacker-controlled input (a response header from an untrusted origin — a system boundary). `URI.resolve(String)` delegates to `URI.create(location)`, which throws an unwrapped `IllegalArgumentException` when the header is not a parseable URI (e.g. `Location: http://exa mple.com/`, an embedded `%zz`, an illegal character). That exception escapes `get()` outside its documented contract — the class promises `SsrfPolicyException` on every policy violation and `IOException` on I/O failure, and the same file already wraps the analogous `IDN.toASCII` `IllegalArgumentException` into `Reason.INVALID_HOST` at SsrfGuardedHttpClient.java:463-469 precisely so "no JDK internals leak".

The impact is concrete and verified in callers: `infochat-provider/.../source/UrlProbe.java:88-104` classifies failures by catching `SsrfPolicyException` (branching on `reason()`), `HttpTimeoutException`, and `IOException`. An attacker who registers a URL that 302-redirects with a malformed `Location` makes the probe throw an `IllegalArgumentException` that none of those catch clauses see — escaping the probe's classification entirely and propagating into the command pipeline instead of returning the `BLOCKED_SSRF`/`UNREACHABLE` failure result. Collector fetchers that catch `IOException` per fetch have the same exposure on hostile feeds. The failure is still fail-closed (the request is not dialed), so this is a contract/availability defect, not a bypass — hence medium, not high.

**Recommended fix:**

```java
URI next;
try {
    next = current.resolve(location);
} catch (IllegalArgumentException e) {
    throw new SsrfPolicyException(
        SsrfPolicyException.Reason.REDIRECT_LOCATION_INVALID,
        "redirect Location header not parseable", e);
}
```

plus the new enum constant:

```java
public enum Reason {
    ...
    REDIRECT_LOCATION_MISSING,
    REDIRECT_LOCATION_INVALID,
    ...
}
```

**Reasoning:**

This mirrors the existing `INVALID_HOST` wrapping pattern one screen above, restoring the invariant that every attacker-influenced parse failure inside the pipeline surfaces as a typed `SsrfPolicyException`. `UrlProbe`'s `switch` already has a `default -> BLOCKED_SSRF` arm ("and any future one, via the default arm"), so the new `Reason` is classified conservatively in the Provider with zero caller changes. The raw `location` string is deliberately kept out of the message (it rides on the cause) — exception messages in this class reach logs, and the header value is attacker-controlled.

**Trade-offs:**

None — the fix is strictly better.

---

### F2. JVM-wide exclusive pin lock held across connect + headers of every hop — adversarial hold time ~140 s serializes all outbound dials

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:347-398, 599-614; infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/PinnedDnsResolver.java:111-118

**Current code:**

```java
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

The class comment acknowledges that concurrent calls "serialize on the lock — acceptable for v1's RSS cadence", but neither the comment nor the design quantifies the adversarial worst case. The lock is held while `perCallClient.send` waits for connect (default 5 s) and response headers (default `requestTimeout` 30 s), per hop, for up to `redirectCap + 1 = 4` hops: a single origin that accepts the TCP connection and then stalls before sending headers holds the JVM-wide lock for up to ~140 s per `get()` call at default settings — and can do so repeatedly on every scheduled fetch. The redirect-body drain at SsrfGuardedHttpClient.java:370-373 also runs under the lock.

During that window nothing else in the service can dial out: in the Collector, every other feed fetch AND every Nostr relay dial/reconnect (`checkAndPinForWebSocket` takes the same lock at line 600) queues behind it. The spec (`docs/spec/security.md` §SSRF) requires that a `StreamSource` reconnect "re-pass the full allowlist before any event is emitted on the new socket" — so a starved reconnect is a dead stream source, meaning one slow RSS feed translates into Nostr ingest downtime. `checkAndPinForWebSocket` additionally holds the lock for the caller's entire WebSocket handshake with no bound enforced by this module; the hold time there depends entirely on caller discipline (the dial timeout the caller configures).

This is a compounding cost: it gets worse with every source added, and it is attacker-influencable, which moves it past "documented v1 trade-off" into a finding.

**Recommended fix:**

Replace the exclusive single-slot pin with a refcounted concurrent pin map keyed by canonical host, removing the lock entirely. Both sides change:

```java
// PinnedDnsResolver.Provider
private record PinEntry(int refCount, Set<InetAddress> addresses) {}

private static final ConcurrentHashMap<String, PinEntry> ACTIVE_PINS = new ConcurrentHashMap<>();

static void pin(String canonicalHost, List<InetAddress> addresses) {
    ACTIVE_PINS.merge(canonicalHost, new PinEntry(1, Set.copyOf(addresses)),
        (old, fresh) -> new PinEntry(old.refCount() + 1,
            // Union is safe: every address in both sets passed the
            // blocklist during its own call's validation, so any of
            // them is a legal connect target for this host.
            union(old.addresses(), fresh.addresses())));
}

static void unpin(String canonicalHost) {
    ACTIVE_PINS.compute(canonicalHost, (host, entry) ->
        entry.refCount() == 1 ? null
                              : new PinEntry(entry.refCount() - 1, entry.addresses()));
}
```

In `get()`, replace `lock()/installPins/clearPins/unlock` with `pin(host, addrs)` before the hop's `send` and `unpin(host)` in a per-hop (or per-call, tracking all pinned hosts) `finally`. `PinnedDial` carries its host and refcount-decrements on `close()` instead of unlocking.

**Reasoning:**

The lock exists only because the pin slot is a single JVM-wide value. Keying pins by canonical host makes concurrent calls to different hosts fully independent — the resolver SPI lookup (`ForwardingResolver.lookupByName`) reads `ACTIVE_PINS.get(canonicalHost(host))`, which is exactly as race-free against a `ConcurrentHashMap` as against the current `volatile` slot. The only true conflict — two concurrent calls pinning the *same* host with different validated IP sets — is resolved by the union, which preserves the security invariant the pin exists for: the JDK can only ever connect this host to an IP that passed the blocklist during some live call's validation. The drip/slow-origin worst case then costs only its own call's time, not the whole service's outbound capacity, and `checkAndPinForWebSocket`'s caller-controlled hold stops being a JVM-wide hazard.

**Trade-offs:**

- The spec's rebind defense reads "must connect to those SAME IPs"; under the union, a call may connect to an IP validated by a *concurrent* call to the same host rather than strictly its own set. Every reachable IP is still blocklist-validated, so the defended property (no unvalidated IP) is intact, but the implementation note in the class javadoc must be updated to state the widened invariant explicitly.
- More bookkeeping than a single slot (~30 lines net), and `unpin` must be exception-proof in `finally` along every path (the current `finally` discipline already exists).

**Alternative options:**

- **Option A** (the recommended refcounted pin map above)
- **Option B** — keep the exclusive lock but shrink the hold: validate + pin + initiate connect per hop, release between hops, re-acquire for the next hop — pros: smaller diff — cons: still serializes on the slowest single hop (up to 35 s), still couples WebSocket dial duration to the global lock; half-fixes the problem.
- **Option C** — accept for v1 but extend the class comment with the quantified adversarial hold time (~140 s/call, WebSocket handshake unbounded) so the operator-facing limitation is visible and a follow-up ticket exists — pros: zero code risk — cons: the starvation remains and grows with source count.

---

### F3. Module tests pin the non-contractual message text; `reason()` — the contract — is never asserted

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientTest.java:64-68 (pattern repeats at 78-82, 88-92, 102-106, 134-137, 172-176, 194-198, 354-360, 425-431)

**Current code:**

```java
SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
    () -> client.get(URI.create("ftp://example.com/")));
assertTrue(ex.getMessage().contains("scheme not allowed"),
    "non-http(s) scheme must be rejected with the literal "
    + "\"scheme not allowed\" prefix");
```

while the exception's own javadoc (SsrfGuardedHttpClient.java:686-693) states:

```java
 * ... {@link #reason()} carries the typed
 * failure mode — callers branch on it, never on message text
 * (the message is human-facing and free to reword).
```

**Why this is wrong / suboptimal / risky:**

The tests and the documented contract point in opposite directions. Every policy test in the module asserts substring matches on the message that the javadoc declares "free to reword" — so an innocuous message reword breaks the suite. Meanwhile no test in the module asserts any `Reason` value, and real callers do branch on it: `infochat-provider/.../source/UrlProbe.java:88-97` maps `BODY_READ_TIMEOUT`/`BODY_READ_DEADLINE_EXCEEDED` to a user-facing TIMEOUT error and everything else to BLOCKED_SSRF. If a pipeline path ever raised the wrong `Reason` (e.g. `BODY_READ_TIMEOUT` where `BODY_READ_DEADLINE_EXCEEDED` is correct — exactly the distinction the B-DEADLINE-TOCTOU clamp at SsrfGuardedHttpClient.java:515-539 exists to keep straight), the Provider would misclassify the failure and no test anywhere in this module would fail. The actual contract surface is untested; the explicitly non-contractual surface is over-tested.

**Recommended fix:**

In each policy test, assert the `Reason` as the primary check; keep or drop the message assertion as a secondary readability check:

```java
SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
    () -> client.get(URI.create("ftp://example.com/")));
assertEquals(SsrfPolicyException.Reason.SCHEME_NOT_ALLOWED, ex.reason(),
    "non-http(s) scheme must surface SCHEME_NOT_ALLOWED — callers branch on reason()");
```

Apply the same to the userinfo, blocked-IP, body-cap, redirect-cap, redirect-revalidation, body-read-timeout, and body-read-deadline tests (`Reason.USERINFO_NOT_ALLOWED`, `BLOCKED_IP`, `BODY_CAP_EXCEEDED`, `REDIRECT_CAP_EXCEEDED`, `BLOCKED_IP`, `BODY_READ_TIMEOUT`, `BODY_READ_DEADLINE_EXCEEDED` respectively).

**Reasoning:**

Tests should pin the surface callers depend on. Asserting `reason()` makes the suite catch exactly the bug class that matters (wrong typed failure mode → caller misclassification) and frees the human-facing text to be reworded without test churn — which is what the javadoc already promises. The timeout-vs-deadline pair is the highest-value conversion: those two reasons drive different user-visible outcomes in the Provider and are distinguished by subtle elapsed-time classification logic in `readBounded`.

**Trade-offs:**

The existing test comments mention keeping literal message prefixes stable for an acceptance grep; converting the assertion changes the greppable literal. If that grep is still load-bearing, keep the message assertion alongside the new `reason()` assertion (two assertions per test) instead of replacing it — slightly noisier tests, no lost coverage.

---

### F4. Stale `rejectsWebsocketSchemeForNow` test narrative contradicts the class it tests

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientTest.java:71-83

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
        + "ticket lands a separate WebSocket-aware wrapper");
}
```

**Why this is wrong / suboptimal / risky:**

The comment and assertion message describe a world that no longer exists: WebSocket support did not land as "a separate WebSocket-aware wrapper" — it landed in this same class (`checkAndPinForWebSocket` / `resolveForWebSocket`, SsrfGuardedHttpClient.java:599-635), and the production code documents the split deliberately (HTTP_SCHEMES vs WEBSOCKET_SCHEMES comment at lines 111-118: "a misrouted scheme is a programming error rather than a policy choice"). A reader of this test concludes the module has no WebSocket support. The "ForNow" name also implies `get()` will later accept `wss`, which the design has since rejected permanently. Stale ticket-flow narration in comments is exactly what CLAUDE.md §Coding style warns against ("don't reference the current ticket... rots as the codebase evolves").

**Recommended fix:**

```java
@Test
void getRejectsWebsocketScheme() {
    // ws/wss never route through get(): the JDK HttpClient.send cannot
    // dial them. They run the same policy pipeline through the dedicated
    // checkAndPinForWebSocket / resolveForWebSocket entrypoints.
    SsrfGuardedHttpClient client = testModeClient();
    SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
        () -> client.get(URI.create("wss://example.com/relay")));
    assertEquals(SsrfPolicyException.Reason.SCHEME_NOT_ALLOWED, ex.reason(),
        "wss is permanently outside get()'s scheme allowlist; "
        + "WebSocket dials use checkAndPinForWebSocket");
}
```

**Reasoning:**

The test's behavioral assertion is correct and worth keeping; only the name and narrative need to match the current, permanent design. Aligning it with the HTTP_SCHEMES/WEBSOCKET_SCHEMES comment in the production code removes the contradiction.

**Trade-offs:**

None — the fix is strictly better. (Renaming a test is a test modification; bundle it with the F3 assertion conversion under one explicitly-authorized ticket.)

---

## Synthesizer-relevant observations

- Hand-written `@NonNull` annotations appear repo-wide (752 occurrences across 171 main-source files, including 10 in this module), while CLAUDE.md §Method parameter contracts states "`@NonNull` is no longer written by hand." This is a cross-module convention-vs-rule drift, not specific to infochat-ssrf — architecture lens should rule on whether the corpus or the rule text is canonical.
- `checkAndPinForWebSocket` makes the JVM-wide lock hold time during a WebSocket dial entirely dependent on the caller's handshake timeout discipline (`NostrRelayConnection` in infochat-collector). Whether that caller bounds the dial is a cross-module contract question; the in-module serialization aspect is covered by F2.
