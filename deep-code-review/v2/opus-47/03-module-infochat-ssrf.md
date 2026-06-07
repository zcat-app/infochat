# Deep code review: module infochat-ssrf

**Target:** module infochat-ssrf
**Lens:** module
**Module path:** infochat-ssrf/
**Date:** 2026-06-06 17:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:197-217 — Constructor performs null checks on package-default-non-null parameters; violates §7 (no defensive code) and the engineering-rules §7a "non-null is the package default."
- [medium] MAINTAINABILITY-RULES-DRIFT — cross-cutting (IpBlocklist.java:101, PinnedDnsResolver.java:64,131,177, SsrfGuardedHttpClient.java:304,329,599,633,675) — Hand-written `@NonNull` on parameters and returns; §7a says "`@NonNull` is no longer written by hand" because non-null is the package default.
- [medium] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:288-290 — Canonical host for bracketed IPv6 literals is keyed differently on the install side (with brackets) than what the JDK passes to the resolver SPI (typically without brackets), so the pin can never match for IPv6 URL literals.
- [low] MAINTAINABILITY-RULES-DRIFT — HostInterfaceSet.java:46-51 — Comment claims the failure surfaces "at module init," but the supplier is invoked on every `isBlocked` call, so the runtime semantics are different from what the doc states.
- [low] PERFORMANCE — PinnedDnsResolver.java:153-155, SsrfGuardedHttpClient.java:354-355 — `installPins` does `Map.copyOf` on an already-immutable `Map.of(...)`; redundant defensive copy per redirect hop.
- [low] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:384, 380-383 — `URI.resolve(location)` and `firstValue("Location").orElseThrow(...)` lack explicit handling of a `Location` value that the JDK URI parser will reject with `IllegalArgumentException`; an attacker-controlled malformed `Location` value escapes as a raw runtime exception instead of a typed `SsrfPolicyException`.

## Detail

### F1. Defensive null checks at an internal-only constructor

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:197-217

**Current code:**

```java
if (blocklist == null) {
    throw new IllegalArgumentException("blocklist must be configured");
}
if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
    throw new IllegalArgumentException("connect timeout must be configured");
}
if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
    throw new IllegalArgumentException("request timeout must be configured");
}
if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
    throw new IllegalArgumentException("read timeout must be configured");
}
if (bodyReadDeadline == null || bodyReadDeadline.isZero() || bodyReadDeadline.isNegative()) {
    throw new IllegalArgumentException("body read deadline must be configured");
}
```

**Why this is wrong / suboptimal / risky:**

§7 prohibits defensive code "for scenarios that cannot happen given the trust boundary the code lives in." §7a says non-null is the package default (`AnnotatedPackages`), so a bare parameter type `IpBlocklist blocklist` already means "must not be null" and a null caller is a compile-time NullAway error. The constructor here is called only by `infochat-collector` and `infochat-provider`, both NullAway-onboarded; an in-reactor caller passing `null` cannot exist at runtime.

The `isZero()` / `isNegative()` checks ARE meaningful (NullAway does not police duration values), but the `== null` half of each conjunction, plus the standalone `blocklist == null`, fall under §7. The fact that `SsrfGuardedHttpClientTest.constructorRejectsNullTimeout` and `constructorRejectsNullTimeout` exist to verify these defensive branches is itself a smell — those tests need to be deleted alongside the checks per §7, not used to justify keeping them.

The wrapper's public-API status does not justify the checks either: §7 says "internal code calling internal code is trusted," and a sibling Maven module under the same reactor is internal code by every reasonable reading.

**Recommended fix:**

```java
if (connectTimeout.isZero() || connectTimeout.isNegative()) {
    throw new IllegalArgumentException("connect timeout must be positive");
}
if (requestTimeout.isZero() || requestTimeout.isNegative()) {
    throw new IllegalArgumentException("request timeout must be positive");
}
if (readTimeout.isZero() || readTimeout.isNegative()) {
    throw new IllegalArgumentException("read timeout must be positive");
}
if (bodyReadDeadline.isZero() || bodyReadDeadline.isNegative()) {
    throw new IllegalArgumentException("body read deadline must be positive");
}
if (bodyCap <= 0) {
    throw new IllegalArgumentException("body cap must be positive");
}
if (redirectCap <= 0) {
    throw new IllegalArgumentException("redirect cap must be positive");
}
```

Also delete `constructorRejectsNullTimeout` from `SsrfGuardedHttpClientTest` and replace the wording in `constructorRejectsZeroTimeout` to "must be positive" so the assertion text matches the new message.

**Reasoning:**

Drops the null branch in each check (NullAway is the compile-time guard for that), keeps the positive-value validation (NullAway cannot police it), and renames the assertion message so the error tells the caller the actual constraint. The fix removes one violation of §7 per call site without weakening any guarantee that is observable at runtime.

**Trade-offs:**

The library jar can in principle be consumed outside this reactor in the future (third-party Maven coordinates), where NullAway is not active. If that happens, a null parameter becomes a `NullPointerException` on the first dereference instead of a clean `IllegalArgumentException`. The fix is to add the null checks back when (and only when) the module starts shipping outside the reactor — at which point its API surface IS a system boundary and §7 permits the checks. Today it is not.

---

### F2. Hand-written `@NonNull` annotations

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** cross-cutting:
  - IpBlocklist.java:101 (`isBlocked(@NonNull InetAddress addr)`)
  - PinnedDnsResolver.java:64, 131, 177 (three `lookupByName`/`get` parameters)
  - SsrfGuardedHttpClient.java:304, 329, 599, 633, 675 (`get`, `checkAndPinForWebSocket`, `resolveForWebSocket`, `addresses()`)

**Current code:**

```java
public boolean isBlocked(@NonNull InetAddress addr) {
    ...
}
```

```java
public HttpResponse<byte[]> get(@NonNull URI uri) throws IOException, InterruptedException {
    ...
}

public @NonNull PinnedDial checkAndPinForWebSocket(@NonNull URI uri) {
    ...
}
```

**Why this is wrong / suboptimal / risky:**

CLAUDE.md §Engineering rules §7a is explicit: "Non-null is the **package default** — every `app.zcat.infochat` package is null-marked (NullAway `AnnotatedPackages`), so a bare reference type means 'never null.' Only genuinely-nullable parameters, returns, and fields carry `@Nullable` (from `org.jspecify.annotations`); **`@NonNull` is no longer written by hand**."

A `@NonNull` on a parameter that is already non-null by default is redundant noise and, worse, suggests to a reader that bare types in the same file MIGHT be nullable (otherwise why annotate just some of them?). The right reading is "bare = NonNull; only `@Nullable` is meaningful."

**Recommended fix:**

Delete every hand-written `@NonNull` in this module:

```java
public boolean isBlocked(InetAddress addr) {
    ...
}

public HttpResponse<byte[]> get(URI uri) throws IOException, InterruptedException {
    ...
}

public PinnedDial checkAndPinForWebSocket(URI uri) {
    ...
}
```

Keep `@Nullable` everywhere it appears (UrlRedactor.java:44, PinnedDnsResolver.java:118, IpBlocklist.java:260) because that carries semantic meaning the type alone cannot.

**Reasoning:**

Removes contradiction between the §7a contract and the actual code. After the change, a reader can rely on the rule that "bare type = NonNull, `@Nullable` = nullable" without exceptions in this module.

**Trade-offs:**

None — the fix is strictly better. NullAway runs the same enforcement either way; deleting the hand annotations cannot weaken any guarantee.

**Alternative options:**

- **Option A** (the recommended fix above) — delete the annotations module-wide.
- **Option B** — leave them. Status quo. The §7a rule is recent and may not yet be retro-applied across the codebase; flagging here aligns this module with the rule but the project-wide cleanup is a separate effort. If the team has decided to migrate lazily, this finding is informational rather than a fix-now item.

---

### F3. IPv6 URL-literal pin key never matches the JDK resolver lookup key

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:288-290

**Current code:**

```java
// IPv6 URL-literals arrive bracketed from URI.getHost() (e.g.
// "[::1]"); IDN.toASCII rejects the brackets. Strip them,
// case-fold the inner literal, and re-add the brackets so the
// pin key and the dial target agree on the IPv6 literal form.
if (host.startsWith("[") && host.endsWith("]")) {
    return "[" + host.substring(1, host.length() - 1).toLowerCase(Locale.ROOT) + "]";
}
```

**Why this is wrong / suboptimal / risky:**

`canonicalizeHost` is invoked on both the install side (`SsrfGuardedHttpClient.resolveAndValidate` line 465 — input is `URI.getHost()`, which DOES include brackets for IPv6 literals) and the lookup side (`PinnedDnsResolver.lookupByName` line 81 — input is whatever the JDK's `HttpClient.send` chose to hand the resolver SPI).

The JDK's documented contract for `InetAddressResolver.lookupByName(String host, …)` is that `host` is a host name. For IPv6 URL literals, the JDK strips the brackets before invoking the resolver SPI (brackets are URL syntax, not part of the address). So on a `URI` like `http://[2606:4700::abcd]/path`:

- Install side: `URI.getHost()` returns `[2606:4700::abcd]`; canonical key is `[2606:4700::abcd]` (with brackets).
- Lookup side: JDK passes `2606:4700::abcd` (no brackets); canonical key is `2606:4700::abcd` (no brackets — the `if (host.startsWith("[")` branch does not fire, so the code falls through to `IDN.toASCII` which accepts `2606:4700::abcd` and returns it).

The two keys do not match, the pin lookup misses, and the resolver falls through to the BUILTIN. For IPv6 literals the BUILTIN happens not to query DNS (it just parses the literal), so the security impact is zero in the IPv6-literal case — but the code path is silently dead, which is a maintenance hazard: the comment swears the pin works, the test `canonicalizeHostStripsAndReAddsIpv6Brackets` only checks the helper in isolation, and any future refactor that DOES need DNS pinning for an IPv6 host (e.g., a hostname that resolves to multiple IPv6 records) will inherit this broken assumption.

This contradicts the SSRF spec commitment ("DNS-resolved IPs are checked against a blocklist [...] DNS is re-resolved after every redirect (TOCTOU defense)"): pinning is the mechanism that ties validation to the actual dial, and the documented invariant "wrapper validates IPs A; HttpClient dials IPs A" is silently violated for one subset of inputs.

**Recommended fix:**

Strip brackets on the install side too (the resolver-side canonical form is the source of truth, because we cannot control what the JDK passes):

```java
static String canonicalizeHost(String host) {
    if (host == null || host.isBlank()) {
        throw new IllegalArgumentException("host must not be null or blank");
    }
    // Strip IPv6 URL brackets up front; the JDK resolver SPI passes
    // host names without URL syntax, so the canonical key must not
    // carry brackets either. Without this, an install-side
    // URI.getHost() == "[::1]" would never match a lookup-side
    // host == "::1" arriving from HttpClient.send.
    String unbracketed = host;
    if (host.startsWith("[") && host.endsWith("]")) {
        unbracketed = host.substring(1, host.length() - 1);
    }
    // IDN.toASCII accepts IPv6 literals (they pass the LDH check
    // trivially) so a single code path handles literals and DNS names.
    String ascii = IDN.toASCII(unbracketed);
    String lower = ascii.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".")) {
        return lower.substring(0, lower.length() - 1);
    }
    return lower;
}
```

Add an end-to-end test (parallel to `pinSurvivesMixedCaseAndTrailingDot`) that pins an IPv6 hostname (e.g., `ip6-local.invalid` resolving via the seam to `::1` — combined with a loopback-permitting blocklist for the dial) and asserts the seam is called exactly once.

**Reasoning:**

After the fix both sides yield the same canonical key for IPv6 literals (`::1` rather than `[::1]`), pin lookups match, and the wrapper's invariant "the IP I validated is the IP the JDK dials" holds for IPv6 too. The dial URI passed to `perCallClient.send(...)` still carries the bracketed form (untouched), so the actual TCP connection target is unchanged.

**Trade-offs:**

The unbracketed form is no longer recognizable as "an IPv6 literal" at a glance when it appears in pin-map log lines or thread dumps. Minor — the IPv6 hex format is itself a strong tell.

---

### F4. `HostInterfaceSet.enumerate()` comment misstates failure-handling lifecycle

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/HostInterfaceSet.java:42-52

**Current code:**

```java
public static Set<InetAddress> enumerate() {
    Enumeration<NetworkInterface> interfaces;
    try {
        interfaces = NetworkInterface.getNetworkInterfaces();
    } catch (SocketException e) {
        // System-boundary I/O failure. The spec's host-non-loopback
        // clause is unsatisfiable without OS interface enumeration,
        // so we surface the failure at module init rather than
        // silently degrading the defense surface to an empty set.
        throw new IllegalStateException(
            "could not enumerate host network interfaces", e);
    }
```

**Why this is wrong / suboptimal / risky:**

The comment claims the failure surfaces "at module init," but `enumerate` is wired via `HostInterfaceSet::enumerate` as a `Supplier` consulted on **every** `IpBlocklist.isBlocked` call (per IpBlocklist.java:53-62 doc and per the M1-026 design intent). A `SocketException` from `NetworkInterface.getNetworkInterfaces()` therefore propagates out of `isBlocked` mid-request, at fetch time, not at JVM startup. The behavior is correct (fail-closed is the right outcome) but the rationale text is misleading and will mislead a future reader auditing the failure path.

There is also no rate-limit or admin-notification path for a runtime "OS lost its interface table" failure — the wrapper simply throws `IllegalStateException` through `isBlocked` → `resolveAndValidate` → out of `get`. Today's callers (fetch scheduler, URL probe) treat `IllegalStateException` like any other runtime exception, which is plausible but worth verifying explicitly.

**Recommended fix:**

```java
public static Set<InetAddress> enumerate() {
    Enumeration<NetworkInterface> interfaces;
    try {
        interfaces = NetworkInterface.getNetworkInterfaces();
    } catch (SocketException e) {
        // Fail closed: the spec's "host's own non-loopback interfaces"
        // clause is unsatisfiable without an OS interface table. This
        // supplier is consulted on every IpBlocklist.isBlocked call,
        // so the throw propagates out of the wrapper at fetch time —
        // not at JVM startup. The fetch scheduler handles it via the
        // standard per-source retry+failure ladder (D42).
        throw new IllegalStateException(
            "could not enumerate host network interfaces", e);
    }
```

**Reasoning:**

The text now matches the runtime behavior and points an auditor at where the propagated failure is handled.

**Trade-offs:**

None — the fix is strictly better.

---

### F5. Redundant `Map.copyOf` on already-immutable pin map

- **Category:** PERFORMANCE
- **Severity:** low
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/PinnedDnsResolver.java:153-155 and infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:354-355

**Current code:**

```java
// SsrfGuardedHttpClient.get
PinnedDnsResolver.Provider.installPins(
    Map.of(resolved.canonicalHost(), resolved.addresses()));
```

```java
// PinnedDnsResolver.Provider.installPins
static void installPins(Map<String, List<InetAddress>> pins) {
    ACTIVE_PINS = Map.copyOf(pins);
}
```

**Why this is wrong / suboptimal / risky:**

The caller already passes a `Map.of(...)` (immutable since JDK 9). `Map.copyOf` returns the input unchanged when it is an instance of the JDK's immutable Map types, so the second copy is a no-op for the production caller — but it is also dead-weight code that obscures the invariant "the install side owns the immutability guarantee." A reader auditing this for a TOCTOU issue has to walk both call sites before being sure no external alias of the map could mutate `ACTIVE_PINS` after install.

The same applies to the public constructor of `PinnedDnsResolver` (line 59) which does `Map.copyOf(pins)` — but for that constructor the caller is arbitrary, so the copy is justified. The Provider's static `installPins` has exactly one caller and that caller always passes a singleton `Map.of`.

**Recommended fix:**

```java
static void installPins(Map<String, List<InetAddress>> pins) {
    ACTIVE_PINS = pins;
}
```

Add a one-line comment at the single call site (or document on the method) that `installPins` requires an immutable map:

```java
/**
 * Install the per-call pin map. Caller must hold {@link #LOCK} and
 * must pass an immutable map (the value is stored as-is; mutation
 * after install would race the resolver thread).
 */
static void installPins(Map<String, List<InetAddress>> pins) {
    ACTIVE_PINS = pins;
}
```

**Reasoning:**

Drops one allocation per HTTP hop on the hot path, and the documented invariant carries the same guarantee the dead copy was implicitly enforcing.

**Trade-offs:**

A future caller that forgets the "immutable map" requirement loses the defensive backstop. The fix accepts that risk because there is only one caller and it lives in the same package — a future second caller would also see the doc comment.

---

### F6. Malformed `Location` header escapes as a non-typed runtime exception

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:380-388

**Current code:**

```java
String location = response.headers().firstValue("Location")
    .orElseThrow(() -> new SsrfPolicyException(
        SsrfPolicyException.Reason.REDIRECT_LOCATION_MISSING,
        "redirect response missing Location header"));
URI next = current.resolve(location);
if (isCrossOrigin(current, next)) {
    hopHeaders.keySet().removeIf(SsrfGuardedHttpClient::isCredentialHeader);
}
current = next;
```

**Why this is wrong / suboptimal / risky:**

`URI.resolve(String)` throws `IllegalArgumentException` when the input is not a valid URI reference (control characters, leading whitespace mishandled by upstream proxies, malformed percent-encoding, etc.). The wrapper raises `SsrfPolicyException` consistently for every other policy violation (scheme, userinfo, host, blocked-IP, body cap, redirect cap, body-read deadline) — `Reason` is the typed contract callers branch on. An attacker-controlled `Location: <garbage>` slips through as a raw `IllegalArgumentException` with no `SsrfPolicyException.Reason` enum value, so a caller doing `catch (SsrfPolicyException e) { switch (e.reason()) … }` will not see this failure mode and the exception falls through to the generic `RuntimeException` handler.

Same applies to `isCrossOrigin(current, next)` if the resolved `next` URI has a null host (a `Location` value like `"/path"` resolves to the same host so this is fine; but a malformed scheme-only `Location: "https:"` resolves to a URI whose host is null, then `to.getHost()` returns null, then `from.getHost().equalsIgnoreCase(null)` returns false (cross-origin sentinel) — which is fail-safe but leaves a `null.toLowerCase` style accident waiting if a future maintainer reorders the equality check).

**Recommended fix:**

```java
String location = response.headers().firstValue("Location")
    .orElseThrow(() -> new SsrfPolicyException(
        SsrfPolicyException.Reason.REDIRECT_LOCATION_MISSING,
        "redirect response missing Location header"));
URI next;
try {
    next = current.resolve(location);
} catch (IllegalArgumentException e) {
    throw new SsrfPolicyException(
        SsrfPolicyException.Reason.REDIRECT_LOCATION_MALFORMED,
        "redirect Location header is not a valid URI", e);
}
```

Add a new `Reason` enum constant `REDIRECT_LOCATION_MALFORMED` and a unit test that wires a server returning `Location: " "` (space) and asserts the new reason.

**Reasoning:**

Brings the redirect-header failure path under the same typed `SsrfPolicyException` contract as the other policy violations, so callers' switch-on-reason dispatch covers the case. The exception's `cause` chain preserves the JDK `IllegalArgumentException` for diagnostic logging.

**Trade-offs:**

One more `Reason` enum constant; tests covering other reasons need no update because they assert on the message prefix not the reason value, and the new constant is additive. None of the existing call sites switch exhaustively over `Reason`.

---
