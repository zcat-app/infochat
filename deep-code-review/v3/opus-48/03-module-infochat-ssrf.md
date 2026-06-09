# Deep code review: module infochat-ssrf

**Target:** module infochat-ssrf
**Lens:** module
**Module path:** infochat-ssrf/
**Date:** 2026-06-09 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClientTest.java:686-695 vs LoopbackPermittingBlocklist.java:13 — two identical `LoopbackPermitting*` test doubles exist; the inner-class copy duplicates the extracted top-level one, against the project's "extract test doubles to top-level" rule.
- [low] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:440-444 — `isCrossOrigin` compares raw `URI.getHost()` for the credential-scrub decision while every other host decision in the module canonicalizes first; the two paths should use the one canonical-host helper.
- [low] SIMPLIFICATION — SsrfGuardedHttpClient.java:766-816 — `BoundedByteArrayResponse` reimplements all 8 `HttpResponse` methods to swap one accessor; a minimal carrier would drop the boilerplate (only worth folding into a change already touching the return type).

## Detail

### F1. Duplicated loopback-permitting test double

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** SsrfGuardedHttpClientTest.java:686-695 (inner class `LoopbackPermitting`) vs LoopbackPermittingBlocklist.java:13 (top-level `LoopbackPermittingBlocklist`)

**Current code:**

```java
// SsrfGuardedHttpClientTest.java
private static final class LoopbackPermitting extends IpBlocklist {
    @Override
    public boolean isBlocked(InetAddress addr) {
        if (addr.isLoopbackAddress()) {
            return false;
        }
        return super.isBlocked(addr);
    }
}
```

```java
// LoopbackPermittingBlocklist.java (top-level, package-private)
class LoopbackPermittingBlocklist extends IpBlocklist {
    @Override
    public boolean isBlocked(InetAddress addr) {
        if (addr.isLoopbackAddress()) {
            return false;
        }
        return super.isBlocked(addr);
    }
}
```

**Why this is wrong / suboptimal / risky:**

These two classes are byte-for-byte identical in behavior and live in the same test package. `LoopbackPermittingBlocklist` was extracted to its own top-level file specifically so `PinnedDnsResolverConcurrencyTest` and `SsrfGuardedHttpClientConcurrencyTest` could share it (its own javadoc states exactly this). But `SsrfGuardedHttpClientTest` still carries a private inner copy named `LoopbackPermitting` and uses it at five construction sites. The consolidation was done halfway, leaving the original inner class behind.

This is the pattern the project's test-hygiene guidance calls out — a test double belongs in a single top-level package-private class, not duplicated as an inner class. A future change to the loopback carve-out (e.g. also permitting a specific link-local test address) must now be made in two places, and a reviewer diffing one file will not see the other copy. It is not a §8 test-integrity violation (no assertion is weakened), but it is real maintainability drift in a security-critical module's test suite.

**Recommended fix:**

Delete the inner `LoopbackPermitting` class from `SsrfGuardedHttpClientTest` and replace each `new LoopbackPermitting()` (lines 93, 148, 330, 537, 669) with `new LoopbackPermittingBlocklist()`.

```java
private SsrfGuardedHttpClient testModeClient() {
    return new SsrfGuardedHttpClient(
        new LoopbackPermittingBlocklist(),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        Duration.ofMinutes(2),
        10L * 1024,
        3);
}
```

**Reasoning:**

One definition of the loopback-permitting double for the whole test package; a policy change is made once. The behavior is identical, so no assertion changes and the suite stays green.

**Trade-offs:**

None — the fix is strictly better. The top-level class is already in the same package and already referenced by two other test files.

---

### F2. Credential-scrub origin check compares raw hosts while the rest of the module canonicalizes

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** SsrfGuardedHttpClient.java:440-444

**Current code:**

```java
private static boolean isCrossOrigin(URI from, URI to) {
    return !from.getScheme().equalsIgnoreCase(to.getScheme())
        || !from.getHost().equalsIgnoreCase(to.getHost())
        || effectivePort(from) != effectivePort(to);
}
```

**Why this is wrong / suboptimal / risky:**

`isCrossOrigin` decides whether caller-injected credential headers (`Authorization` / `Cookie` / `Proxy-Authorization`) are stripped before the next redirect hop. It compares `from.getHost()` against `to.getHost()` with `equalsIgnoreCase` on the **raw** strings. Every other host decision in this module — the IP-blocklist check, the DNS pin install, and the pin lookup — goes through `canonicalizeHost` (IDN→ASCII, lowercase, trailing-dot strip), precisely because the whole module's design premise (documented at length on `canonicalizeHost`) is that raw `URI.getHost()` comparisons are unreliable across IDN/case/trailing-dot variants.

The security impact here is bounded: the only direction that matters for safety is "treat a genuinely different origin as same-origin and replay credentials," and that cannot happen via a host quirk — if two raw hosts are case-insensitively equal they are the same name and resolve to the same IPs (rebind is separately handled by the pin). The realistic divergence is the harmless direction: `https://example.com/` redirecting to `Location: https://example.com./` (trailing dot) is classified cross-origin and *over-scrubs* credentials, which is safe but inconsistent. So this is a consistency/maintainability drift, not an exploitable gap — recorded as low.

The reason to still fix it: leaving one of the module's security-relevant host comparisons on a weaker primitive than all the others is the kind of inconsistency that invites a real bug the next time this code is touched. The canonical helper already exists and is package-static.

**Recommended fix:**

```java
private static boolean isCrossOrigin(URI from, URI to) {
    if (!from.getScheme().equalsIgnoreCase(to.getScheme())) {
        return true;
    }
    String fromHost = from.getHost();
    String toHost = to.getHost();
    if (fromHost == null || toHost == null) {
        return true; // fail safe to cross-origin: scrub when unsure
    }
    String fromCanonical;
    String toCanonical;
    try {
        fromCanonical = canonicalizeHost(fromHost);
        toCanonical = canonicalizeHost(toHost);
    } catch (IllegalArgumentException e) {
        return true;
    }
    return !fromCanonical.equals(toCanonical)
        || effectivePort(from) != effectivePort(to);
}
```

**Reasoning:**

The credential-scrub host comparison now uses the identical canonical form the SSRF gate and pin map use, so the two decisions cannot drift on a trailing-dot/case/IDN variant. The explicit null/uncanonicalizable arms make the existing "fails safe to cross-origin" comment (already written on the original method) actually structural rather than relying on `equalsIgnoreCase(null) == false`.

**Trade-offs:**

Two extra `canonicalizeHost` calls per redirect hop (each is `IDN.toASCII` + lowercase + substring). Redirects are capped at 3 and are not a hot path, so cost is negligible. More lines than the three-condition boolean.

---

### F3. `BoundedByteArrayResponse` is a heavyweight wrapper for "the body is now a byte array"

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** SsrfGuardedHttpClient.java:766-816

**Current code:**

```java
private static final class BoundedByteArrayResponse implements HttpResponse<byte[]> {
    private final HttpResponse<InputStream> delegate;
    private final byte[] body;
    // ... 8 delegating overrides ...
    @Override public byte[] body() { return body; }
    // ...
}
```

**Why this is wrong / suboptimal / risky:**

The class exists only to swap `body()` from the streamed `InputStream` to the already-drained `byte[]`, passing every other accessor straight through to the delegate. It is ~50 lines for one substantive method; `sslSession()`, `version()`, and `previousResponse()` are passthroughs no caller in this codebase needs. This is the single-use indirection the coding-style guide nudges away from ("a flat function beats an unnecessary class").

It is correct and the `HttpResponse<byte[]>` return type is a defensible public contract, so this is a smell, not a defect — hence low.

**Recommended fix:**

Only if a change is already revising the return type, return a minimal record stating exactly what the gate produces:

```java
public record FetchResult(int statusCode, HttpHeaders headers, URI uri, byte[] body) {}
```

**Reasoning:**

Callers read `statusCode()`, `headers()`, `uri()`, and `body()`; a narrow record drops six delegating methods and the `HttpResponse<InputStream>` field.

**Trade-offs:**

Changes the public return type, which ripples into `infochat-collector` and `infochat-provider` callers — not worth doing for its own sake. Fold in only if the return type is being revised anyway; until then the current wrapper is fine.

---

## Synthesizer-relevant observations

- The WebSocket peer-IP-change defense is split across modules. `infochat-ssrf` supplies the primitives correctly — `resolveForWebSocket(URI)` (re-resolve + re-validate, no pin) and `PinnedDial.addresses()` (the connect-time validated set) — but the spec's "any peer-IP change observed **at the socket layer** is a hard close" can only be honored by the consumer in `infochat-collector` (`NostrRelayConnection` / `NostrStreamSource`). Note the gap: `resolveForWebSocket` re-resolves DNS, which detects a DNS-answer change, not necessarily the actual connected peer IP. Whether the collector closes on a true socket-layer peer change (vs only a DNS re-resolution divergence) is not verifiable inside this module and should be checked in the collector review.
