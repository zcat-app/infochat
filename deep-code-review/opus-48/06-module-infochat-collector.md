# Deep code review: module infochat-collector

**Target:** module infochat-collector
**Lens:** module
**Module path:** infochat-collector/
**Date:** 2026-06-01 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — ReadyPromoter.java:114,144 — `promoteOne` is `@Transactional` but is reached only via self-invocation from `onTick`, so in production the UPDATE and `pg_notify` run as two separate auto-commits, silently breaking the spec's same-transaction NOTIFY rule; the IT masks this by calling `promoteOne` through the proxy.
- [medium] MAINTAINABILITY-RULES-DRIFT — BlueskyFetcher.java:110-117, RedditFetcher.java:108-114 — upstream-controlled pagination cursor and the source `identifier` are string-concatenated into the request URL without URL-encoding, inconsistent with KrakenSnapshotSource's correct `URLEncoder.encode` usage and able to corrupt the request or inject extra query parameters.
- [low] MAINTAINABILITY-RULES-DRIFT — AssetSnapshotFetcher.java:189-198 — a `catch (RuntimeException)` branch is commented "Defensive guard" around an internal SPI call, contradicting §7 ("No defensive code") and the project's own no-defensive-code vocabulary.

## Detail

### F1. `@Transactional` on `ReadyPromoter.promoteOne` is bypassed by self-invocation, voiding the same-transaction NOTIFY guarantee in production

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/eval/ready/ReadyPromoter.java:114, 122-130, 143-192

**Current code:**

```java
@Scheduled(every = "{infochat.embeddings.poll-interval}")
public void onTick() {
    List<PromotionCandidate> pending;
    ...
    for (PromotionCandidate post : pending) {
        try {
            promoteOne(post.id(), post.fetchedAt());   // self-invocation
        } catch (RuntimeException e) {
            ...
        }
    }
}

@Transactional
public void promoteOne(UUID postId, Instant fetchedAt) {
    Instant readyAt = Instant.now();
    try (Connection conn = dataSource.getConnection()) {
        ... UPDATE post SET status='READY' ... ;
        afterUpdateHook.run();
        ... SELECT pg_notify(?, ?) ...
    }
    ...
}
```

**Why this is wrong / suboptimal / risky:**

The class javadoc states the invariant explicitly: "The `UPDATE post SET status='READY' ...` AND the `pg_notify('new_post', ...)` emit MUST commit or rollback together. The `@Transactional` boundary on `promoteOne` is the enforcement; a NOTIFY outside the transaction would survive a rollback as a phantom event." This mirrors `architecture.md` §Inter-service communication: "the high-water mark advances both fields **in the same DB transaction** as the side effect it triggers."

The only production caller of `promoteOne` is `onTick`, which calls it as `promoteOne(...)` — a plain `this` call on the same bean. CDI/ARC interceptors (including the `@Transactional` interceptor) are applied on the client proxy and are **not** invoked on self-invocation. Therefore, in production, no JTA transaction is active when `promoteOne` runs. The injected Agroal `DataSource` returns a connection in autocommit mode when no JTA transaction is in scope, so the `UPDATE` commits immediately and the subsequent `pg_notify` commits as a second, independent statement. The documented atomicity does not exist on the production path.

The `ReadyPromoterIT` does not catch this because every scenario calls `readyPromoter.promoteOne(...)` directly on the injected proxy (e.g. line 106, 144, 185, 202). On the proxy, the interceptor fires, a real transaction wraps the body, and the `afterUpdateHook`-throws-rollback assertion (Order 2) passes. The test exercises a code path that the production scheduler never takes — a §8-adjacent test-integrity smell (the test asserts a property the production caller does not have).

The happy-path data outcome is usually still correct because the UPDATE precedes the NOTIFY, so a "phantom NOTIFY without a READY row" cannot occur from ordering alone. But the guarantee the code claims to provide is absent: if the `pg_notify` statement itself fails after the UPDATE auto-committed, the post is READY with no live NOTIFY (recoverable only by Provider catch-up), and any future maintainer who adds a second mutation to `promoteOne` trusting the `@Transactional` wrapper will get silent non-atomicity.

**Recommended fix:**

Make the transaction boundary real by routing the call through the proxy, or by managing the transaction explicitly with the same raw-JDBC pattern the rest of the pipeline uses (`TransactionHelper.inTransaction`), which does not depend on interception.

Option A — explicit transaction control (consistent with Stage1Pipeline / Stage2VerdictHandler):

```java
// drop @Transactional; manage the unit of work explicitly
public void promoteOne(UUID postId, Instant fetchedAt) {
    Instant readyAt = Instant.now();
    TransactionHelper.inTransaction(dataSource, "ReadyPromoter", conn -> {
        int rowsUpdated = updateToReady(conn, postId, fetchedAt, readyAt);
        if (rowsUpdated == 0) {
            return; // no NOTIFY — same-transaction rule cuts both ways
        }
        afterUpdateHook.run();
        emitNewPostNotify(conn, postId, readyAt);
    });
}
```

Option B — keep `@Transactional` but invoke through the proxy so the interceptor fires:

```java
@Inject ReadyPromoter self;   // self-injection forces proxy dispatch
...
self.promoteOne(post.id(), post.fetchedAt());
```

**Reasoning:**

Option A removes the reliance on interception entirely; `TransactionHelper.inTransaction` already sets `autoCommit=false`, commits on success, and rolls back on any exception, so the UPDATE and the `pg_notify` genuinely share one transaction on the production path. It also makes `ReadyPromoter` consistent with `Stage1Pipeline` and `Stage2VerdictHandler`, which already use `TransactionHelper` and run from non-interceptable callers. The IT keeps passing because `promoteOne` then behaves identically regardless of how it is invoked, so the test finally exercises the production behavior.

**Trade-offs:**

Option A is a few more lines than the current `@Transactional` annotation and requires refactoring the inline `UPDATE`/`pg_notify` into helper closures. Option B is smaller but relies on the subtler self-injection idiom and keeps the JTA path; a future caller adding another self-invocation re-introduces the same trap. Option A is the more robust choice.

**Alternative options:**

- **Option A** (recommended above) — explicit `TransactionHelper`, no interception dependency.
- **Option B** — self-injection so the proxy dispatches and the `@Transactional` interceptor fires — pros: minimal diff — cons: keeps the fragile interception dependency; the next self-invocation re-breaks it silently.

---

### F2. Upstream pagination cursor and source identifier are concatenated into request URLs without URL-encoding

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcher.java:110-117; infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcher.java:108-114

**Current code:**

```java
// BlueskyFetcher
private URI buildUri(String actor, String cursor) {
    StringBuilder sb = new StringBuilder(xrpcBase)
        .append("?actor=").append(actor);
    if (cursor != null) {
        sb.append("&cursor=").append(cursor);
    }
    return URI.create(sb.toString());
}
```

```java
// RedditFetcher
private static URI buildPageUri(String identifier, String afterCursor) {
    String url = identifier + ".json";
    if (afterCursor != null) {
        url += "?after=" + afterCursor;
    }
    return URI.create(url);
}
```

`cursor` comes from `BlueskyResponseParser` (`root.get("cursor").asText()`) and `afterCursor` from `RedditResponseParser` (`data.path("after").textValue()`) — both are values pulled verbatim from the untrusted upstream JSON response body. `actor` is the source `identifier`.

**Why this is wrong / suboptimal / risky:**

`security.md` §Threat model states "The Collector is exposed to arbitrary feed content. Every RSS publisher, Reddit poster, Bluesky user, etc. is untrusted." A cursor value is part of that untrusted surface. Appending it raw into a query string has two failure modes:

1. **Request corruption / denial of progress.** A cursor containing a space, `{`, `}`, `|`, `#`, `^`, or other characters illegal in a URI causes `URI.create` to throw `IllegalArgumentException`. The Bluesky fetcher does not wrap `buildUri` in its typed `BlueskyFetchException`, so this propagates as a raw `IllegalArgumentException`. It is caught by `FetchScheduler.tickOnce`'s broad `catch (Exception)`, but it is mis-classified as a generic fetch failure and counts toward the D42 failure ladder, so a single malformed cursor can drive an otherwise-healthy source toward `status='failed'`. A `#` in the cursor silently truncates the query (everything after becomes a URI fragment), causing the fetcher to silently re-request page 0 — a non-terminating pagination loop bounded only by `pageCap`.

2. **Query-parameter injection.** A cursor of the form `x&limit=1` (Bluesky) or `x&before=...` (Reddit) injects an additional query parameter into the same request. The base host is fixed and the `SsrfGuardedHttpClient` re-validates the host on every hop, so this is **not** an SSRF vector; but it lets the upstream alter its own request parameters in ways the fetcher did not intend.

The same module already does this correctly: `KrakenSnapshotSource.java:108` builds its query with `URLEncoder.encode(pair, StandardCharsets.UTF_8)`. The inconsistency is the tell — two fetchers handle boundary input one way and a third handles it another way, with no documented reason.

**Recommended fix:**

Percent-encode every dynamic query component:

```java
// BlueskyFetcher
private URI buildUri(String actor, @Nullable String cursor) {
    StringBuilder sb = new StringBuilder(xrpcBase)
        .append("?actor=").append(URLEncoder.encode(actor, StandardCharsets.UTF_8));
    if (cursor != null) {
        sb.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    }
    return URI.create(sb.toString());
}
```

```java
// RedditFetcher
private static URI buildPageUri(String identifier, @Nullable String afterCursor) {
    String url = identifier + ".json";
    if (afterCursor != null) {
        url += "?after=" + URLEncoder.encode(afterCursor, StandardCharsets.UTF_8);
    }
    return URI.create(url);
}
```

**Reasoning:**

`URLEncoder.encode` guarantees the dynamic segment is a valid `application/x-www-form-urlencoded` token, eliminating both the `URI.create` parse-failure path and the parameter-injection path, and aligning all three fetchers with the Kraken pattern. The host and path remain fixed, so the SSRF guard's invariants are unaffected.

**Trade-offs:**

`URLEncoder` encodes a space as `+` rather than `%20`; for query-string values this is the standard form and both Bluesky and Reddit cursors are opaque tokens the server round-trips, so the encoding choice is immaterial to them. None of practical concern.

---

### F3. "Defensive guard" catch around an internal SPI call contradicts §7 No-defensive-code

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java:189-198

**Current code:**

```java
} catch (FetchException e) {
    recordFailure(row, e);
    return;
} catch (RuntimeException e) {
    // Defensive guard: an impl bug must not break the tick
    // loop for sibling pairs. Treat as a failure for D42
    // counter purposes — operator visibility is the priority.
    LOG.warnf(e, "AssetSnapshotFetcher: %s.fetchSnapshot threw RuntimeException for asset=%s vs=%s",
        host, row.asset(), row.defaultQuoteCurrency());
    recordFailure(row, new FetchException(
        "RuntimeException from " + host + ".fetchSnapshot: " + e.getClass().getSimpleName(), e));
    return;
}
```

**Why this is wrong / suboptimal / risky:**

`AssetDataSource` is an internal SPI; `fetchSnapshot` declares it throws `FetchException` and the three v1 implementations (Kraken, Coingecko, Bitfinex) translate every IO/JSON/HTTP failure into `FetchException` themselves. The extra `catch (RuntimeException)` is explicitly labelled "Defensive guard … an impl bug must not break the tick loop." §7 of the engineering rules prohibits exactly this: "no try/catch around operations that cannot throw; no 'just in case' branches … a defensive check between two internal classes is scope drift." The comment naming it a "guard" against a hypothetical "impl bug" is the no-defensive-code anti-pattern stated in its own words.

There is a legitimate concern hiding here — one bad pair should not abort the sibling-pair loop — but the correct shape for that is per-pair iteration isolation, which `runHostTick` already provides by calling `tickOnePair` per row. A genuinely unexpected `RuntimeException` from an internal collaborator is a bug that should surface loudly (and be diagnosed), not be folded into the D42 fetch-health counter where it masquerades as an upstream fetch failure and can wrongly trip a source to `failed`.

This is a contained smell, hence low severity; but it is a clear, citable §7 violation and worth recording so it is not copied as a pattern.

**Recommended fix:**

Remove the `catch (RuntimeException)` arm. Let `FetchException` drive the D42 counter (its purpose) and let an unexpected runtime exception propagate out of `tickOnePair`; if loop-isolation across pairs is still wanted, isolate at the loop in `runHostTick` without conflating the failure class:

```java
for (EnabledPair row : rows) {
    try {
        tickOnePair(host, row);
    } catch (RuntimeException e) {
        // Boundary of the per-pair unit of work: an unexpected bug in
        // one pair must not abort sibling pairs. NOT counted as a D42
        // fetch failure — that counter tracks upstream health only.
        LOG.errorf(e, "AssetSnapshotFetcher: unexpected error ticking asset=%s sub_verb=%s",
            row.asset(), row.subVerb());
    }
}
```

**Reasoning:**

This keeps the loop-isolation property the original comment actually cared about, but stops a programming bug from polluting the upstream-failure counter and tripping `active → failed`. It also removes a §7-violating "just in case" branch from inside a trusted internal call. The D42 counter then reflects only real `FetchException`s, which is what the spec's per-source failure-ladder is about.

**Trade-offs:**

If a v1 `AssetDataSource` impl genuinely throws an unchecked exception on every tick, the source no longer auto-disables via D42 and instead logs at ERROR every tick. That is arguably the correct outcome (an impl bug should be noisy, not silently quarantined as "upstream is down"), but it is a behavior change from the current code. If auto-disable on impl-bugs is explicitly desired, that should be stated as a deliberate decision rather than left as a "defensive guard" comment.

## Synthesizer-relevant observations

- No cross-module layering violation found: `infochat-collector` depends only on `infochat-core`, `infochat-ssrf`, and `infochat-llm-adapter`; no import of `infochat-provider` or `infochat-messaging-adapter` appears in the module.
- The SSRF guard (`infochat-ssrf/SsrfGuardedHttpClient`) is correctly consumed on every outbound path checked — RSS, Bluesky, Reddit, the three asset sources, and the Nostr websocket (`checkAndPinForWebSocket` + the periodic `resolveForWebSocket` peer-IP re-check). The Nostr trust-boundary ordering (signature verify → kind allowlist → dedup → outbox) matches `security.md` §Per-source trust boundaries, and `NostrEventVerifier` uses a constant-time id comparison. These are out of this module's finding scope but confirm the boundary is honored.
