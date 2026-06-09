# Deep code review: module infochat-ssrf

**Target:** module infochat-ssrf
**Lens:** module
**Module path:** infochat-ssrf/
**Date:** 2026-06-09
**Reviewer:** senior-developer (mimo)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — SsrfGuardedHttpClient.java:107 — body-cap default (10 MiB) contradicts design note (5 MB)
- [low] SIMPLIFICATION — SsrfGuardedHttpClientTest.java:686 — duplicate `LoopbackPermitting` inner class when shared `LoopbackPermittingBlocklist` exists

## Detail

### F1. Body-cap default contradicts design note

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** SsrfGuardedHttpClient.java:107

**Current code:**

```java
private static final long DEFAULT_BODY_CAP = 10L * 1024 * 1024;
```

**Why this is wrong / suboptimal / risky:**

The design note `docs/design/04-security.md` §4.2 item 5 states: "Body size cap. `infochat.fetch.max-body-bytes` (default 5 MB)." The library constant is 10 MiB (10,485,760 bytes), double the design-note default. Every caller using the no-arg constructor — `RssFetcher`, `OdyseeFetcher`, `NitterFetcher`, `YouTubeFetcher`, `BlueskyFetcher`, `RedditFetcher`, `CoingeckoSnapshotSource`, `KrakenSnapshotSource`, `BitfinexSnapshotSource`, `UrlProbe`, and `CollectorSsrfClientProducer` — inherits this 10 MiB default. The design note is the spec-derivative guidance for implementation; the code silently diverges.

This is not a security-critical gap (10 MiB vs 5 MB both bound the body), but it is a maintainability hazard: an operator reading the design note to understand the system's resource exposure would underestimate by 2x.

**Recommended fix:**

Either update the design note to match the code (if 10 MiB is the intended value), or change the constant:

```java
private static final long DEFAULT_BODY_CAP = 5L * 1024 * 1024;
```

**Reasoning:**

The design note and code must agree. The design note is the spec-derivative document operators and reviewers consult. If 10 MiB is correct, the design note is stale. If 5 MB is correct, the code is wrong. The fix is a one-line change either way.

**Trade-offs:**

None — this is a documentation/code alignment issue. Reducing the cap from 10 MiB to 5 MB could reject legitimate large feeds; updating the design note to 10 MiB changes the documented resource exposure. Either way, the two must agree.

---

### F2. Duplicate LoopbackPermitting test double

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** SsrfGuardedHttpClientTest.java:686-695

**Current code:**

```java
// SsrfGuardedHttpClientTest.java:686
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

**Why this is wrong / suboptimal / risky:**

The shared test double `LoopbackPermittingBlocklist` (top-level class at `LoopbackPermittingBlocklist.java`) has identical behavior. The inner `LoopbackPermitting` in `SsrfGuardedHttpClientTest` is a copy. The shared class exists specifically because `PinnedDnsResolverConcurrencyTest` and `SsrfGuardedHttpClientConcurrencyTest` need it — the Javadoc on `LoopbackPermittingBlocklist` says so. `SsrfGuardedHttpClientTest` should use it too.

**Recommended fix:**

Delete the inner `LoopbackPermitting` class from `SsrfGuardedHttpClientTest` and replace all usages with `LoopbackPermittingBlocklist`:

```java
// Replace all occurrences of:
new LoopbackPermitting()
// With:
new LoopbackPermittingBlocklist()
```

**Reasoning:**

One test double instead of two. The shared class is already package-private and in the same package. No behavior change.

**Trade-offs:**

None — the fix is strictly better.
