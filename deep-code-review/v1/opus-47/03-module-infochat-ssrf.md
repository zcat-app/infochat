# Deep code review: module infochat-ssrf

**Target:** module infochat-ssrf
**Lens:** module
**Module path:** infochat-ssrf/
**Date:** 2026-06-01 12:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — IpBlocklist.java:85-87 — `IpBlocklist(Set<InetAddress>)` constructor is an explicitly-labeled M1-025-compat shim; §7 prohibits backwards-compatibility shims in greenfield M1.
- [medium] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:269-279 — `canonicalizeHost` runs `IDN.toASCII` unconditionally and throws on bracketed IPv6 host literals (`http://[2606:4700:4700::1111]/`), so IPv6 URL-literal destinations cannot pass the wrapper at all despite spec promising IPv6 coverage.
- [medium] SECURITY — SsrfGuardedHttpClient.java:334 — caller-supplied `extraHeaders` are forwarded verbatim to every redirect hop including cross-origin targets; no cross-origin scrub mechanism, so any future caller passing credentials (Authorization, Cookie) leaks them to attacker-controlled redirect targets.
- [medium] PERFORMANCE — SsrfGuardedHttpClient.java:420-424,471 — a fresh single-thread `ExecutorService` (and therefore a new OS thread) is allocated per `get()` call solely to enforce the per-read watchdog; an instance-level executor would eliminate the per-request thread spawn.
- [low] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:41-47 — class-level javadoc still claims ws/wss are "deliberately rejected" and "carved out per the ticket's out_of_scope" while the same class now exposes `checkAndPinForWebSocket` and `resolveForWebSocket`; the rejection statement is stale.
- [low] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:194-205 — three constructor-time `IllegalArgumentException` sites all carry the identical literal `"timeout must be configured"`, leaving misconfigurations ambiguous about which knob failed validation.

## Detail

### F1. IpBlocklist M1-025 compatibility-shim constructor

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java:78-87

**Current code:**

```java
/**
 * M1-025 test-mode constructor — preserved as an overload so
 * M1-025 tests pass unchanged. Internally widens to the Supplier
 * form via a defensive copy: the snapshot is captured once at
 * construction and returned by the supplier on every call, so
 * isBlocked semantics match the fixed-set intent.
 */
IpBlocklist(Set<InetAddress> hostInterfaces) {
    this(() -> Set.copyOf(hostInterfaces));
}
```

**Why this is wrong / suboptimal / risky:**

`engineering-rules-verbatim.md` §7 states verbatim: "Feature flags and backwards-compatibility shims are forbidden when the change can simply be made. M1 is a greenfield build; there is no prior version to be compatible with." The javadoc here explicitly self-identifies as a shim — "preserved as an overload so M1-025 tests pass unchanged" — which is the exact pattern §7 prohibits.

The change *can* simply be made: the two test callsites that depend on this overload are in the same module under the developer's control (`IpBlocklistTest.java` lines 150 and 160, both `new IpBlocklist(Set.of(hostIp))`). Wrapping the set in a `Supplier` literal is a one-line change at each callsite. There is no external consumer to be backwards-compatible with; M1-025 was internal-only refactoring superseded by M1-026 in the same line of development.

Keeping the shim adds API surface (two near-identical package-private constructors) that future readers must distinguish, and concretely creates an asymmetry: M1-025 callers get a frozen snapshot via `Set.copyOf`, M1-026 callers get per-call freshness. The shim hides this difference inside the constructor, so a casual reader of `IpBlocklist(Set.of(hostIp))` cannot see that they have opted into snapshot semantics.

**Recommended fix:**

```java
// IpBlocklist.java — delete the Set<InetAddress> overload entirely.
// The remaining package-private constructor is the Supplier form.

IpBlocklist(Supplier<Set<InetAddress>> hostInterfacesProvider) {
    this.hostInterfacesProvider = hostInterfacesProvider;
}
```

```java
// IpBlocklistTest.java line 150 — and the corresponding occurrence on line 160:

InetAddress hostIp = InetAddress.getByName("203.0.113.5");
Set<InetAddress> frozen = Set.of(hostIp);
IpBlocklist withHost = new IpBlocklist(() -> frozen);
```

**Reasoning:**

Removes the shim entirely, eliminates the constructor-overload asymmetry, and makes the snapshot-vs-fresh distinction visible at every callsite. The test-side change is two lines and the test name `hostInterfaceIpIsBlocked` / `nonHostPublicIpStillAllowed` semantics are preserved because a `Supplier` returning a frozen set behaves identically to the deleted overload.

**Trade-offs:**

None — the fix is strictly better. The shim's only justification was avoiding test churn, and §7 explicitly forbids exactly that justification in greenfield M1.

---

### F2. IPv6 URL-literal hosts cannot pass canonicalizeHost

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:269-279

**Current code:**

```java
static String canonicalizeHost(String host) {
    if (host == null || host.isBlank()) {
        throw new IllegalArgumentException("host must not be null or blank");
    }
    String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
    String lower = ascii.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".")) {
        return lower.substring(0, lower.length() - 1);
    }
    return lower;
}
```

**Why this is wrong / suboptimal / risky:**

`URI.getHost()` for an IPv6-literal URL such as `http://[2606:4700:4700::1111]/` returns the host **with the surrounding brackets** — i.e. `[2606:4700:4700::1111]` — by the JDK's documented `URI.getHost()` contract. `IDN.toASCII` is defined over RFC 3490 domain labels; `[`, `]`, and `:` are not valid label characters, so `IDN.toASCII("[2606:4700:4700::1111]", IDN.ALLOW_UNASSIGNED)` raises `IllegalArgumentException`. `resolveAndValidate` catches that and rejects the entire request with `SsrfPolicyException("invalid host: ...")` BEFORE the IP set is ever produced or consulted against `IpBlocklist`.

Consequences:

1. The wrapper cannot dial any IPv6-literal URL at all — neither legitimate public IPv6 targets nor the IPv6 forms `[::1]`, `[fe80::1]`, `[::ffff:127.0.0.1]` that `IpBlocklist` correctly blocks. The IPv6 portion of `IpBlocklist` (lines 166-188 + the IPv4-mapped delegation at lines 113-117) is unreachable via `get()` and `checkAndPinForWebSocket`.
2. Spec contradiction: `docs/spec/security.md` §SSRF says "DNS-resolved IPs are checked against a blocklist of private, loopback, link-local, multicast, CGNAT, and cloud-metadata ranges (notably `169.254.169.254` and IPv6 equivalents)". The wrapper's actual behaviour for `[::1]` is "rejected as invalid host" rather than "blocked IP" — the right outcome by accident for malicious targets, but the wrong outcome for legitimate IPv6 destinations.
3. No test in `SsrfGuardedHttpClientTest` exercises an IPv6-literal URL, so the regression is silent. `IpBlocklistTest` validates `isBlocked` directly with raw `InetAddress` instances, which bypasses the URI/canonicalize path entirely and provides false confidence.

**Recommended fix:**

```java
static String canonicalizeHost(String host) {
    if (host == null || host.isBlank()) {
        throw new IllegalArgumentException("host must not be null or blank");
    }
    // IPv6 literals arrive from URI.getHost() wrapped in brackets
    // ("[::1]"); IDN.toASCII would reject them because brackets and
    // colons are not valid domain-label characters. Strip the
    // brackets and bypass IDN — there is no IDN form of an IP
    // literal; lowercase + trailing-dot strip are still applied so
    // the pin key stays stable across JDK normalization choices
    // (e.g. "::FFFF:127.0.0.1" vs "::ffff:127.0.0.1").
    if (host.startsWith("[") && host.endsWith("]")) {
        String stripped = host.substring(1, host.length() - 1);
        return stripped.toLowerCase(Locale.ROOT);
    }
    String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
    String lower = ascii.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".")) {
        return lower.substring(0, lower.length() - 1);
    }
    return lower;
}
```

Plus a test in `SsrfGuardedHttpClientTest` that constructs an `HttpServer` on `[::1]:0` (when the test JDK supports it) and asserts that the strict-mode wrapper rejects `http://[::1]:port/` with `"blocked IP"` rather than `"invalid host"` — the assertion difference is the regression signal.

**Reasoning:**

IP literals are not domain names; they should not go through IDN. The bracket-prefix test is reliable because `URI.getHost()` always returns IPv6 hosts bracketed and never returns non-IPv6 hosts bracketed. Lowercase is still useful so that the pin key matches across `InetAddress.getByName(...).getHostAddress()` (which is lowercase) versus an upper-case form a caller might have supplied. The trailing-dot strip does not apply to IP literals.

The fix restores spec contract: the IP blocklist now governs IPv6 destinations as well, including the `::ffff:0:0/96` mapped-v4 delegation that `IpBlocklist` already implements.

**Trade-offs:**

Two extra branches in the canonicalization helper. No behavioural change for hostname inputs. The new IPv6 test requires a JDK with IPv6 stack enabled; on hosts where `HttpServer.create(new InetSocketAddress("[::1]", 0), ...)` fails, the test must skip cleanly (assume + skip, not silent pass).

---

### F3. extraHeaders leak across cross-origin redirects

- **Category:** SECURITY
- **Severity:** medium
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:319-335

**Current code:**

```java
while (true) {
    ResolvedHost resolved = resolveAndValidate(current, HTTP_SCHEMES);
    PinnedDnsResolver.Provider.installPins(
        Map.of(resolved.canonicalHost(), resolved.addresses()));

    HttpClient perCallClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
        .uri(current)
        .timeout(requestTimeout)
        .header("Accept", ACCEPT_HEADER)
        .header("User-Agent", USER_AGENT)
        .GET();
    extraHeaders.forEach(reqBuilder::header);
    HttpRequest request = reqBuilder.build();
```

**Why this is wrong / suboptimal / risky:**

The redirect loop re-applies `extraHeaders` to every hop without examining whether the redirect target is the same origin as the original request. Browsers, `curl --location`, and well-known HTTP clients all strip sensitive headers (notably `Authorization`, `Cookie`, `Proxy-Authorization`) on cross-origin redirects precisely because the original caller authorized them for the original origin only.

The current consumer documented in the javadoc — Provider's URL probe sending `Range: bytes=0-0` — is benign because `Range` is not a credential. The risk is shape, not exploit: the `Map<String, String> extraHeaders` overload presents itself as "attach these headers to the request"; nothing in the signature or javadoc warns a future caller that an `Authorization` header bound for `feed.example.com` will also be sent to a 302-redirect target on `attacker.example.org`. The redirect re-validation (DNS + blocklist) is by design transport-only — it does not scrub the request payload.

The `/add-source` URL probe is an immediate concrete vector: a user-supplied URL that 302-redirects to attacker-controlled host. If any acceptance criterion ever adds an `Authorization` header for authenticated probes (e.g., basic-auth-protected RSS feeds), the credential leaks on the very first redirect.

**Recommended fix:**

Either restrict the extra-headers contract to a known-safe set, or scrub credentials on cross-origin redirects. The cross-origin scrub is what the broader ecosystem does and is what the spec's "DNS is re-resolved after every redirect" clause structurally implies (re-validation extends to credentials, not just IPs):

```java
private static final Set<String> CROSS_ORIGIN_STRIPPABLE = Set.of(
    "authorization", "cookie", "proxy-authorization");

// inside get(URI, Map<String, String>):
URI current = uri;
String originHost = resolveAndValidate(current, HTTP_SCHEMES).canonicalHost();
int originPort = current.getPort();
String originScheme = current.getScheme();
int redirectCount = 0;
while (true) {
    ResolvedHost resolved = resolveAndValidate(current, HTTP_SCHEMES);
    boolean sameOrigin = resolved.canonicalHost().equals(originHost)
        && current.getPort() == originPort
        && originScheme.equals(current.getScheme());
    Map<String, String> effectiveHeaders = sameOrigin
        ? extraHeaders
        : extraHeaders.entrySet().stream()
            .filter(e -> !CROSS_ORIGIN_STRIPPABLE.contains(
                e.getKey().toLowerCase(Locale.ROOT)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    // ... rest of the loop builds the request with effectiveHeaders
}
```

The `Set` covers the universally-recognized credential headers; a deny-list rather than allow-list because the documented contract is "attach extra headers" and adding a new safe header should not require a code change here.

**Reasoning:**

Closes the credential-leak vector against an attacker-controlled redirect target without changing the call surface for in-tree consumers (`Range`, `Accept`, and other non-credential headers continue to propagate). The same-origin check uses the *canonical* host (already computed for pinning) plus port and scheme, so a downgrade from `https` to `http` is treated as cross-origin even if the host string matches.

**Trade-offs:**

The same-origin check is per-hop overhead, but it is a few string compares and the per-hop blocklist + pin install already dominate. The deny-list is necessarily a fixed set; if a deployment one day introduces a custom credential header it will not be scrubbed. The compromise is documented in the constant declaration so a future maintainer reading the call sees the choice.

**Alternative options:**

- **Option A** (the recommended fix above).
- **Option B** — Reject all `extraHeaders` whose name matches the deny-list at `get` entry, raising `IllegalArgumentException`. Pros: no per-hop work, no silent dropping. Cons: precludes a legitimate same-origin Authorization use case (e.g. authenticated feed probes) entirely; the call surface becomes "extra non-credential headers" which the name does not signal.
- **Option C** — Forbid `extraHeaders` from being non-empty when the redirect cap is greater than 0. Pros: simplest. Cons: couples two unrelated knobs and effectively kills the redirect feature for any header injector.

---

### F4. Per-call ExecutorService spawn in readBounded

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:420-424, 471

**Current code:**

```java
ExecutorService readerExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "ssrf-body-reader");
    t.setDaemon(true);
    return t;
});
long bodyReadStartNanos = System.nanoTime();
try (InputStream in = response.body();
     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
    // ... loop submits to readerExecutor per read
} finally {
    readerExecutor.shutdownNow();
}
```

**Why this is wrong / suboptimal / risky:**

A new `ExecutorService` — and therefore a new OS thread on first `submit` — is created and torn down once per `get()` invocation. The thread exists solely to be the reader so the calling thread can supervise it with `Future.get(timeout)`. Per `docs/spec/security.md` §SSRF, the wrapper is the gate for every outbound HTTP fetch in both Collector (RSS, social feeds) and Provider (URL probes); on a busy Collector this is one thread-spawn per feed fetch. Thread creation on Linux is ~microseconds-to-tens-of-microseconds — not catastrophic at v1's RSS cadence, but it is wasted work that scales with fetch volume and is trivially avoidable.

The deeper cost is the rule precedent: `CLAUDE.md` §Stack notes the project targets JDK 25 + Quarkus 3.33 with virtual threads. A per-instance shared executor — or, better, a per-call virtual-thread `Thread.ofVirtual().start(...)` for the read — eliminates the platform-thread allocation entirely. The current code uses a platform thread (the factory builds `new Thread(r, ...)` not `Thread.ofVirtual()`).

**Recommended fix:**

```java
// SsrfGuardedHttpClient field:
private static final ThreadFactory READER_FACTORY =
    Thread.ofVirtual().name("ssrf-body-reader-", 0).factory();

// readBounded:
private byte[] readBounded(HttpResponse<InputStream> response) throws IOException {
    long bodyReadStartNanos = System.nanoTime();
    try (InputStream in = response.body();
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buf = new byte[8192];
        long total = 0;
        while (true) {
            long elapsedNanos = System.nanoTime() - bodyReadStartNanos;
            if (elapsedNanos > bodyReadDeadline.toNanos()) {
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
                throw new SsrfPolicyException(
                    "body read deadline exceeded after " + elapsedMs + "ms");
            }
            CompletableFuture<Integer> readFuture = new CompletableFuture<>();
            Thread readerThread = READER_FACTORY.newThread(() -> {
                try {
                    readFuture.complete(in.read(buf));
                } catch (Throwable t) {
                    readFuture.completeExceptionally(t);
                }
            });
            readerThread.start();
            int n;
            try {
                n = readFuture.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                readerThread.interrupt();
                throw new SsrfPolicyException(
                    "body read timeout after " + readTimeout.toMillis() + "ms");
            } catch (ExecutionException e) {
                // ... unchanged
            }
            // ... rest unchanged
        }
    }
}
```

**Reasoning:**

Virtual threads are essentially free to spawn (~hundreds of nanoseconds, no OS-thread allocation). Per-call spawn becomes negligible and the global executor / shutdown-now bookkeeping disappears. The cancellation semantics are the same — `interrupt()` on a virtual thread propagates the same way; `InputStream.read` on a blocking socket may or may not honor it (this is true of the current code too).

**Trade-offs:**

- The `READER_FACTORY` is a static field that lives for the JVM lifetime. Acceptable — it carries no state, just a thread builder.
- The code becomes a touch less symmetric (no `ExecutorService` to shut down), but the `try` block is simpler.

**Alternative options:**

- **Option A** (the recommended fix — virtual-thread per read).
- **Option B** — Reuse a single per-`SsrfGuardedHttpClient`-instance `ExecutorService` instead of per-call. Pros: minimal code change. Cons: still platform threads; the pool can starve if multiple concurrent `get` calls reach `readBounded` simultaneously (the JVM-wide `PinnedDnsResolver.Provider` lock currently serializes the connection-establishment phase but NOT the body-read phase, per the class javadoc, so concurrent body reads are an explicit design choice).
- **Option C** — Use `HttpClient.sendAsync` with `CompletableFuture.orTimeout(bodyReadDeadline)` and `BodyHandlers.ofByteArray(bodyCap)`. Pros: pushes the whole problem to the JDK. Cons: loses the per-read watchdog (only total deadline remains) and `BodyHandlers.ofByteArray` does not cap size — would need a custom `BodySubscriber`. Higher complexity than the win justifies.

---

### F5. Class-level javadoc claims ws/wss are rejected while the class supports them

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:41-47

**Current code:**

```java
/**
 * ...
 *   <li><strong>Scheme allowlist</strong> — only {@code http} and
 *       {@code https} are dialed. {@code ws} and {@code wss} are
 *       deliberately rejected for now: the WebSocket transport
 *       wrapper for {@code StreamSource} consumes the same
 *       {@link IpBlocklist} policy class but is its own
 *       implementation (carved out per the ticket's
 *       {@code out_of_scope}).</li>
 * ...
 */
public final class SsrfGuardedHttpClient {
```

**Why this is wrong / suboptimal / risky:**

The class-level scheme-allowlist bullet is from the M1-024/M1-025 era when WebSocket support was deliberately carved out. M1-101 then landed `checkAndPinForWebSocket` and `resolveForWebSocket` in this same class with their own `WEBSOCKET_SCHEMES` allowlist; the bullet's claim that ws/wss are "deliberately rejected" and "its own implementation" is no longer true — the WebSocket path is right here, in this class, two methods down. A reader following the javadoc top-down learns that ws/wss are not supported, then encounters the methods that contradict the claim. The HTTP_SCHEMES comment at lines 111-118 explicitly acknowledges the two-surface design, making this top-of-class bullet doubly stale.

`CLAUDE.md` §Coding style and the project's why-not-what comment policy require that javadoc reflect the current state of the code; ticket-time wording ("carved out per the ticket's out_of_scope") rots and now actively misleads.

**Recommended fix:**

```java
/**
 * ...
 *   <li><strong>Scheme allowlist</strong> — the
 *       {@link #get(URI)} entrypoint dials only {@code http} and
 *       {@code https}; the WebSocket entrypoints
 *       {@link #checkAndPinForWebSocket(URI)} and
 *       {@link #resolveForWebSocket(URI)} accept only {@code ws} and
 *       {@code wss}. The two allowlists are disjoint because the
 *       JDK's {@code HttpClient.send} cannot dial WebSocket and
 *       {@code WebSocket.Builder} cannot dial HTTP; a misrouted
 *       scheme is a programming error rather than a policy choice.
 *       The IP-blocklist + DNS-pinning pipeline runs identically
 *       on both surfaces.</li>
 * ...
 */
```

**Reasoning:**

Matches the actual code (two surfaces, disjoint allowlists), keeps the why-not-what spirit (it explains *why* the surfaces are disjoint), and drops the ticket-id reference that the memory `feedback_no_plan_refs_in_docs.md` discourages in long-lived documentation.

**Trade-offs:**

None — the fix is strictly better.

---

### F6. Indistinct constructor-validation error messages

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:194-205

**Current code:**

```java
if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
    throw new IllegalArgumentException("timeout must be configured");
}
if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
    throw new IllegalArgumentException("timeout must be configured");
}
if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
    throw new IllegalArgumentException("read timeout must be configured");
}
if (bodyReadDeadline == null || bodyReadDeadline.isZero() || bodyReadDeadline.isNegative()) {
    throw new IllegalArgumentException("body read deadline must be configured");
}
```

**Why this is wrong / suboptimal / risky:**

The constructor is a system boundary (it accepts configuration from outside the SSRF module), so per §7 the validation itself is appropriate. The issue is that `connectTimeout` and `requestTimeout` both report the identical message `"timeout must be configured"`. An operator who configures `infochat.ssrf.request-timeout=0` and an operator who forgets `infochat.ssrf.connect-timeout` see exactly the same exception text — they cannot tell from the error which knob to fix. This forces them to read the source (or guess) to recover.

The information was present in the parameter name and the developer chose to discard it; per `CLAUDE.md` §Coding style "Descriptive names", the cost of naming the parameter in the message is paid once at write time and the benefit is paid every time the error fires.

**Recommended fix:**

```java
requirePositive(connectTimeout, "connectTimeout");
requirePositive(requestTimeout, "requestTimeout");
requirePositive(readTimeout, "readTimeout");
requirePositive(bodyReadDeadline, "bodyReadDeadline");

private static void requirePositive(Duration value, String paramName) {
    if (value == null || value.isZero() || value.isNegative()) {
        throw new IllegalArgumentException(
            paramName + " must be configured as a positive Duration");
    }
}
```

**Reasoning:**

One helper collapses four near-identical branches into four lines; the message names the failing parameter; the "positive Duration" clarifies that zero is rejected (which the current message does not state). The same helper can collapse the `blocklist`, `bodyCap`, `redirectCap`, and `resolverSeam` null/positive checks symmetrically, taking the constructor body from ~24 lines to ~8.

**Trade-offs:**

One extra private static method on the class. Marginal.

## Synthesizer-relevant observations

These are cross-module concerns the architecture lens will catch; recording here once for the synthesizer rather than as numbered findings:

- The JVM-wide `PinnedDnsResolver.Provider` lock serialises every outbound HTTP and WebSocket call across both Collector and Provider. The class javadoc explicitly accepts this for v1 RSS cadence, but the lock is also held across user-supplied WebSocket handshakes (`PinnedDial`); a slow or hanging relay handshake stalls *every* RSS fetch on a node. Worth examining at architecture lens whether `checkAndPinForWebSocket` callers enforce a connect timeout that bounds the lock-hold time.
- `META-INF/services/java.net.spi.InetAddressResolverProvider` registers `app.zcat.infochat.ssrf.PinnedDnsResolver$Provider` JVM-globally. Any module that depends on `infochat-ssrf` — including transitively, via Collector/Provider — installs the resolver provider for the entire JVM, which means tests in unrelated modules that happen to inherit `infochat-ssrf` on the test classpath get the pinned-forwarder in front of their DNS too. Worth checking that no module has a non-test runtime dependency on `infochat-ssrf` it does not actually use.
