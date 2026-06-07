# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-07
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — cross-cutting — NOTIFY channel `new_price_snapshot` has no `provider_state` cursor row; correctness relies solely on cache-flush-on-reconnect
- [high] MAINTAINABILITY-RULES-DRIFT — cross-cutting — `infochat-messaging-adapter` has no dependency on `infochat-core`, yet adapters produce `Identity` objects consumed by Provider intake that must match `users.adapter` enum values
- [medium] MAINTAINABILITY-RULES-DRIFT — cross-cutting — SPI interfaces are scattered across module root packages; `git ls-files '*/src/main/java/**/spi/*.java'` returns empty because no `spi/` convention exists
- [medium] SECURITY — `QuarantineReviewListener.java:58-62` — NOTIFY payload parsed via regex from getParameter() with no length bound before Pattern.match; a malformed or oversized payload could exhaust memory
- [medium] SIMPLIFICATION — cross-cutting — `AssetSnapshotReader` (Provider) and `AssetSnapshotFetcher` (Collector) duplicate refresh-interval `@ConfigProperty` keys (`infochat.assets.refresh.coingecko|kraken|bitfinex`) in both modules with different defaults
- [low] MAINTAINABILITY-RULES-DRIFT — `AdapterRegistry.java:227` — "Gate 7" counts as gate 7 but the code comment at line 64 says "six gates"

## Detail

### F1. `new_price_snapshot` channel has no high-water-mark cursor

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** cross-cutting (see CURRENT-CODE)
- **Surface:** NOTIFY

**Current code:**

`schema.md:526-528` (spec):
```
- `new_price_snapshot` — best-effort only; this channel does
  not maintain a `provider_state` row (cache-flush-on-reconnect
  is the correctness mechanism, not a high-water mark).
```

`architecture.md:43-49` (spec):
```
- `new_price_snapshot` — fires on a successful Fetcher write to
  `price_snapshot`. Payload carries `(asset, source)` ...
  Correctness mechanism: best-effort; the Provider's in-process
  cache is **flushed entirely on every Postgres reconnect** so
  a missed NOTIFY during a connection blip cannot serve a stale
  row past the reconnect
```

**Why this is wrong / suboptimal / risky:**

The `new_post` and `quarantine_review` channels both use the outbox + high-water-mark + compare-and-swap cursor pattern that makes correctness independent of NOTIFY delivery. The `new_price_snapshot` channel deliberately omits the cursor and instead flushes the entire in-process cache on reconnect.

This means:
1. A Provider reconnect (which happens routinely on connection blips, not just restarts) invalidates every cached price, forcing re-fetches from the `price_snapshot` table for every asset command until the cache repopulates.
2. The cache-flush is a Provider-internal mechanism; it's not visible to operators as a `provider_state` row they can inspect.
3. The asymmetry with the other two channels is a maintainability cost: every future NOTIFY channel must decide "cursor or cache-flush," and the decision rules are implicit rather than enumerated.

The spec acknowledges this explicitly ("best-effort only"), so this is not spec-drift — the design is intentional. But the intentional asymmetry carries a real operational cost: a flapping Provider connection causes repeated cache misses on `/zcash` and `/monero` until the connection stabilizes, and there is no `provider_state` row an operator can inspect to see whether the cache is current.

**Recommended fix:**

Add a `provider_state` row for `new_price_snapshot` using the same cursor shape as the other channels. The cursor key would be `(captured_at, asset, sub_verb)` — `captured_at` is the high part, `(asset, sub_verb)` is the tiebreaker tail. The reconcile query becomes `WHERE captured_at > :cursor_high OR (captured_at = :cursor_high AND (asset, sub_verb) > (:cursor_low_kind, :cursor_low_id))` and the cache is warmed from the table rather than flushed. The cache-flush-on-reconnect remains as defense-in-depth.

**Reasoning:**

Makes all three NOTIFY channels obey the same correctness pattern. Operators get an inspectable cursor. A Provider reconnect no longer causes a cache cold-start for asset prices.

**Trade-offs:**

- The cache-flush-on-reconnect is simpler and already correct per spec. Adding a cursor adds a `provider_state` row and a reconcile query that must be maintained.
- The asset-command cache is low-stakes (stale prices are a freshness issue, not a safety issue like a stale `quarantine_review` cursor would be).

**Alternative options:**

- **Option A** (the recommended fix above)
- **Option B** — Leave as-is; the spec already documents the asymmetry and the stakes are low — pros: zero implementation cost — cons: the pattern divergence persists, and the operational cost of cache cold-start on reconnect remains

---

### F2. Messaging adapter module has no dependency on core

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** `infochat-messaging-adapter/pom.xml` (dependency section)
- **Surface:** DAG

**Current code:**

`infochat-messaging-adapter/pom.xml` — no `infochat-core` dependency declared.

`infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/Identity.java` — defines `adapter` field as a plain `String`.

`infochat-core/src/main/java/app/zcat/infochat/core/ingest/Fetcher.java` and `StreamSource.java` — defined in `infochat-core`, consumed by `infochat-collector`.

**Why this is wrong / suboptimal / risky:**

The `MessagingAdapter` SPI and the `Fetcher`/`StreamSource` SPIs share no common module. The `Identity` record carries an `adapter` string that must match the `users.adapter` enum values in the schema, but there is no single source of truth for adapter names. The `MessagingAdapter` interface lives in `infochat-messaging-adapter`; the `Fetcher` interface lives in `infochat-core`. A future adapter name added to one module but not the other would produce a runtime mismatch.

The 6-module DAG (`architecture.md`) places `infochat-core` at the bottom and `infochat-messaging-adapter` above it. A dependency from messaging-adapter → core would be a downward edge in the DAG, which is prohibited. But the practical consequence of not having it is that the `adapter` string in `Identity` and the `users.adapter` column (which the core schema defines) are kept in sync by convention, not by compilation.

The DAG rule is correct (messaging-adapter should not depend on core, because core is the bottom layer that nothing should depend downward to reach), but the absence of a shared contract is a real gap. The adapter name enum should either live in core (and messaging-adapter depends on it, violating the DAG) or in a separate `infochat-adapter-identity` module that both core and messaging-adapter depend on.

**Recommended fix:**

Extract adapter name constants to `infochat-core` (since they are schema-level values that the DB schema already encodes in CHECK constraints and enum types). Then have `infochat-messaging-adapter` depend on `infochat-core`. This is a DAG violation in the current ordering but corrects a more fundamental problem — the schema is the authoritative source of adapter names, and code that produces adapter-identified objects should depend on the schema module.

Alternatively, accept the current arrangement and document that `Identity.adapter()` values are validated at the Provider intake boundary against the `users.adapter` CHECK constraint, making the runtime DB the enforcer.

**Reasoning:**

The current arrangement works at runtime because the DB CHECK constraint on `users.adapter` rejects invalid values. But it relies on the DB as the only cross-module contract enforcer, which is fragile: a typo in an adapter name string literal in messaging-adapter code would only surface when a user tries to register.

**Trade-offs:**

- Adding a core dependency to messaging-adapter changes the DAG.
- Extracting a separate identity module adds an 8th module for a small contract.
- The runtime DB enforcement already catches errors before user-visible impact.

---

### F3. QuarantineReviewListener NOTIFY payload parsing has no length bound

- **Category:** SECURITY
- **Severity:** medium
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java:58-62`
- **Surface:** NOTIFY

**Current code:**

```java
private static final Pattern TARGET_KIND_PAT =
    Pattern.compile("\"target_kind\"\\s*:\\s*\"([^\"]+)\"");
private static final Pattern TARGET_ID_PAT =
    Pattern.compile("\"target_id\"\\s*:\\s*\"([^\"]+)\"");
private static final Pattern NEW_STATUS_PAT =
    Pattern.compile("\"new_status\"\\s*:\\s*\"([^\"]+)\"");
```

**Why this is wrong / suboptimal / risky:**

Postgres NOTIFY payloads are bounded to ~8KB by the wire protocol, so an attacker cannot send an arbitrarily large payload through this path. However, the `([^\"]+)` capture groups are unbounded — a payload that fills the entire 8KB with a single quoted string will produce an 8KB captured group, which is then used in string concatenation for the admin notification message. This is not a critical vulnerability (8KB is Postgres' hard ceiling, not attacker-controlled), but the spec-level payload-size rule ("payloads are bounded to the cursor key") means the code should reject a payload that clearly exceeds the expected cursor-key size (~100 bytes) rather than silently processing it.

The same patterns in `NewPostListener.parsePayload()` parse a cursor-only payload `(ready_at, post_id)` which is similarly bounded by the wire protocol but also relies on Postgres as the only size gate.

**Recommended fix:**

Add a pre-check: if `n.getParameter().length() > 512` (or similar bound), log a warning and drop the notification without parsing. The cursor-key payload is at most ~100 bytes; anything larger is malformed and should not reach the regex engine.

**Reasoning:**

Defense-in-depth at the NOTIFY intake boundary. Postgres bounds the payload to 8KB, but the application should reject clearly-malformed payloads before pattern matching.

**Trade-offs:**

- None — the fix is strictly better. A 512-byte ceiling on a cursor-key payload is far above any legitimate value.

---

### F4. Duplicate `@ConfigProperty` keys across Collector and Provider for asset refresh intervals

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** cross-cutting (see CURRENT-CODE)
- **Surface:** property

**Current code:**

```java
// infochat-collector: AssetSnapshotFetcher.java:121-129
@ConfigProperty(name = "infochat.assets.refresh.coingecko")
@ConfigProperty(name = "infochat.assets.refresh.kraken")
@ConfigProperty(name = "infochat.assets.refresh.bitfinex")

// infochat-provider: AssetSnapshotReader.java:58-64
@ConfigProperty(name = "infochat.assets.refresh.coingecko", defaultValue = "90")
@ConfigProperty(name = "infochat.assets.refresh.kraken", defaultValue = "90")
@ConfigProperty(name = "infochat.assets.refresh.bitfinex", defaultValue = "90")
```

**Why this is wrong / suboptimal / risky:**

The same three property keys are declared in both modules, but the Provider side has `defaultValue = "90"` while the Collector side has no default (required property). The Provider reads these properties only to surface staleness warnings in asset command replies — it doesn't drive scheduling. Having the defaults only on the Provider side means a configuration file that omits these keys will cause a Collector startup failure (missing required property) but the Provider would silently use 90-minute defaults, creating an inconsistency that only surfaces as incorrect staleness warnings.

The duplication also means changing the refresh interval requires updating it in two places if the operator uses a single `application.properties`.

**Recommended fix:**

Move the refresh-interval constants to `infochat-core` (or a shared config class) so both services read the same property with the same default from a single definition. Or, since the Provider only needs them for staleness warnings, have the Provider read the staleness threshold from a separate property (`infochat.assets.staleness-warning-minutes`) that defaults to the refresh interval but can be tuned independently.

**Reasoning:**

Removes the drift risk between the two services' understanding of "how fresh should the data be." The Collector owns scheduling; the Provider should either ask the Collector (not possible in the DB-only communication model) or read its staleness threshold from a single source of truth.

**Trade-offs:**

- A shared config constant in core adds a dependency from both services on core (already true).
- Separate properties give the operator more granular control (e.g., "fetch every 60s but warn at 90s").

---

### F5. AdapterRegistry gate count mismatch (comment says 6, code has 7)

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java:64,227`
- **Surface:** capability-flag

**Current code:**

```java
// line 60-64:
 * <p><b>Gate order.</b> The six gates are evaluated in §6.7's
 * documented order...

// line 227:
        // Gate 7: per-adapter bootstrap admin union non-empty per
```

**Why this is wrong / suboptimal / risky:**

The Javadoc says "six gates" but the code implements seven (gates 1–7). Gate 7 (bootstrap admin union non-empty) was added after the initial six-gate design but the class-level Javadoc was not updated. This is a minor documentation drift — anyone reading the class header to understand the gate count will be off by one.

**Recommended fix:**

Update the Javadoc at line 64 from "six gates" to "seven gates" and add Gate 7 to the enumerated list in the class header.

**Reasoning:**

Minor documentation fix. The gate itself is correctly implemented and tested in `StartupGatesTest`.

**Trade-offs:**

- None — the fix is strictly better.

---

## Synthesizer-relevant observations

- SPI inventory was empty for the `*/src/main/java/**/spi/*.java` glob — the project does not use a `spi/` subpackage convention. All SPI interfaces live in the module root package or a subpackage named after the concept (e.g., `messaging/MessagingAdapter.java`, `ingest/Fetcher.java`). The architecture review treated the `git grep 'public interface'` output as the effective SPI surface.
- `infochat-provider` depends on `infochat-ssrf` for `/add-source` URL probes, which is consistent with the DAG (ssrf is below provider). The Provider does NOT depend on Collector, which is correct per the DB-only communication rule.
- All 38 Flyway migrations live in `infochat-core`. Both services depend on core transitively, so both see the migration classpath. This is the correct shape for a shared-schema two-service deployment.
