# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-01 20:57
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — `docs/design/09-reference.md` — Module DAG diagram and dependency table incorrectly claim that `infochat-ssrf`, `infochat-llm-adapter`, and `infochat-messaging-adapter` each depend on `infochat-core`. None of their poms declare this dependency and none of their source files import any `app.zcat.infochat.core.*` type.
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT — cross-cutting (NOTIFY) — The `new_price_snapshot` NOTIFY channel has a producer (`PriceSnapshotStore` in the Collector) but no consumer on the Provider side. The spec (architecture.md) describes a best-effort cache-flush-on-reconnect pattern that has no implementation. The Provider reads price data from the database on every request, which is functionally correct, but the NOTIFY is emitted into a vacuum and the spec's described cache layer is absent.

## Detail

### F1. Module DAG design document is inaccurate about sibling-to-core dependencies

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `docs/design/09-reference.md` lines 31-38 (table) and lines 18-28 (DAG diagram)
- **Surface:** DAG

**Current code:**

The DAG table at `docs/design/09-reference.md` states:

```
| Module | Depends on | Purpose |
|---|---|---|
| `infochat-ssrf` | `infochat-core` | SSRF-gated outbound HTTP/WS client |
| `infochat-llm-adapter` | `infochat-core` | LlmProvider, EmbeddingProvider, TranslationProvider SPIs and impls |
| `infochat-messaging-adapter` | `infochat-core` | MessagingAdapter SPI plus the v1 in-tree implementations |
```

The DAG diagram (lines 18-28) shows `infochat-core` at the root with arrows to all three sibling modules, depicting dependency.

Verification against actual poms and source:

```
infochat-ssrf/pom.xml:              [no infochat-core dependency]
infochat-ssrf/src/main/java/**/*:   [no imports from app.zcat.infochat.core]

infochat-llm-adapter/pom.xml:       [no infochat-core dependency]
infochat-llm-adapter/src/main/java/**/*: [no imports from app.zcat.infochat.core]

infochat-messaging-adapter/pom.xml: [no infochat-core dependency]
infochat-messaging-adapter/src/main/java/**/*: [no imports from app.zcat.infochat.core]
```

**Why this is wrong / suboptimal / risky:**

The design document says it "is normative for module dependencies." A developer reading the document to understand the module graph will believe these three modules depend on `infochat-core` and may make incorrect assumptions about shared type availability, classpath ordering, and build-time coupling. The inaccuracy also makes the document unreliable as a reference for future module additions.

The actual module graph is better than what the design claims: the three sibling modules are fully self-contained and do not couple to `infochat-core` at all. The design document should be corrected to reflect this stronger, more decoupled structure.

The existing M1-071 ticket demonstrates that the project cares about DAG accuracy (it fixed a different DAG violation where `infochat-llm-adapter` incorrectly depended on `infochat-messaging-adapter`). This is the same class of issue.

**Recommended fix:**

Update `docs/design/09-reference.md` to reflect the actual dependency graph. The DAG diagram and table should show the sibling modules with NO dependency on `infochat-core`:

```markdown
| Module | Depends on | Purpose |
|---|---|---|
| `infochat-core` | (none) | Domain entities, schema-level types, shared utilities. Pure Java; no Quarkus, no I/O. |
| `infochat-ssrf` | (none) | SSRF-gated outbound HTTP/WS client (allowlist, IP blocklist, DNS-rebind defense, redirect cap, scheme allowlist, timeout caps). Shared by every Collector fetch / StreamSource connect and every Provider `/add-source` URL probe. Self-contained — uses only the JDK. |
| `infochat-llm-adapter` | (none) | `LlmProvider`, `EmbeddingProvider`, `TranslationProvider` SPIs and impls. Self-contained SPI surfaces. |
| `infochat-messaging-adapter` | (none) | `MessagingAdapter` SPI plus the v1 in-tree implementations: SimpleX, Signal, and the in-memory test adapter. Self-contained SPI surfaces. |
| `infochat-collector` | `infochat-core`, `infochat-ssrf`, `infochat-llm-adapter` | Fetchers, eval pipeline, schedulers. Headless. No `messaging-adapter` dependency — Collector never talks to users. |
| `infochat-provider` | `infochat-core`, `infochat-ssrf`, `infochat-llm-adapter`, `infochat-messaging-adapter` | Command router, chat agent, periodic digest, admin commands. |
```

Update the DAG diagram to show the sibling modules without arrows from `infochat-core`.

**Reasoning:**

The fix aligns the normative reference with the actual build graph. The sibling modules being independent of core is an architectural strength (looser coupling, smaller compilation units), not something to hide. The fix clarifies for future developers that if a sibling module needs a type from core, it must add the dependency explicitly.

**Trade-offs:**

None — the fix is strictly better.

---

### F2. `new_price_snapshot` NOTIFY channel has producer but no consumer, and the spec-promised cache layer is absent

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** cross-cutting (see CURRENT-CODE)
- **Surface:** NOTIFY | spec-drift

**Current code:**

Producer side — `infochat-collector/src/main/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStore.java`, lines 20-42:

```java
/**
 * Persists one {@link PriceSnapshot} into {@code price_snapshot} and
 * emits {@code NOTIFY new_price_snapshot} on the same JDBC connection
 * inside the same {@code @Transactional} boundary.
 * ...
 * The NOTIFY payload is the spec-committed
 * {@code {"asset":"<asset>","source":"<sub_verb>"}} JSON shape per
 * docs/spec/commands.md §Asset commands — Provider/Collector contract.
 */
 ...
public static final String NEW_PRICE_SNAPSHOT_CHANNEL = "new_price_snapshot";
```

Consumer side — `grep -r "new_price_snapshot\|LISTEN.*new_price" infochat-provider/src/main/` returns zero results. The only file referencing this channel on the Provider side is a test (`AssetCommandsRoundtripIT.java`) that sends NOTIFY and checks the message arrives — but there is no production LISTEN subscription.

Spec side — `docs/spec/architecture.md` §Inter-service communication:

```
- `new_price_snapshot` — fires on a successful Fetcher write to
  `price_snapshot`. Payload carries `(asset, source)` where
  `source` is the sub-verb value (e.g. `coingecko`, `kraken`).
  Correctness mechanism: best-effort; the Provider's in-process cache
  is **flushed entirely on every Postgres reconnect** so a missed NOTIFY
  during a connection blip cannot serve a stale row past the reconnect
```

Provider implementation — `infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReader.java` reads directly from `price_snapshot` table on every call with no cache:

```java
public @Nullable SnapshotResult readLatest(@NonNull String asset, @NonNull String subVerb, @NonNull String vsCurrency) {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(...))
```

**Why this is wrong / suboptimal / risky:**

Three interrelated gaps:

1. **Spec-drift on the cache contract.** The spec (architecture.md) promises a Provider-side in-process price cache that is flushed on every Postgres reconnect. No such cache exists. The spec describes the cached path as the primary mechanism ("best-effort; the Provider's in-process cache is flushed") and the DB read as the correctness backstop. The implementation uses only the DB-read backstop, skipping the entire caching layer.

2. **Orphan NOTIFY producer.** The Collector emits `NOTIFY new_price_snapshot` on every successful price snapshot write (every ~60-300s per sub-verb across all profiles). This is observable: each `pg_notify` allocates a Postgres backend-side notification buffer entry that persists until consumed by a LISTEN session or the backend's notification queue overflows. With no consumer, the notifications accumulate and are discarded when the backend's `notify_queue_capacity` is reached. While the per-event overhead is negligible at v1 scale, the pattern is architecturally wrong: a producer should not emit events on a channel with no consumers.

3. **The `new_price_snapshot` channel is declared in the closed list of v1 NOTIFY channels.** Adding a channel requires a spec amendment. If the channel exists only as a dead-letter path, it should either be removed from the closed list or a consumer should be implemented. Leaving it in its current half-implemented state means a future developer reading the spec will believe the cache exists and will design code that depends on it.

**Recommended fix:**

Two options, pick one:

**Option A (cleanest):** Remove the `new_price_snapshot` channel from both the producer and the spec. The Provider already reads directly from `price_snapshot` with correct freshness semantics. The NOTIFY layer is unused and the cache it would feed does not exist.

Update `PriceSnapshotStore.java` to remove the NOTIFY emission (lines 93-109). Update `docs/spec/architecture.md` to remove the `new_price_snapshot` entry from the closed channel list. Update `docs/spec/schema.md` §Operational to remove the `new_price_snapshot` provider_state entry.

**Option B (implement the spec):** Add a price-snapshot cache on the Provider side (using the already-declared `quarkus-caffeine` dependency) with a LISTEN consumer that invalidates entries on NOTIFY and flushes the cache on Postgres reconnect. This would match what the spec describes.

For v1, Option A is strongly recommended. The performance benefit of a price cache is negligible at v1 scale (one read per command invocation), and the complexity of a correct cache-invalidation layer (connection-loss detection, cache-flush on reconnect, TTL for freshness guarantee) is disproportionate to the gain. The NOTIFY channel can be re-added in v2 if profiling shows a need.

**Reasoning:**

Option A eliminates a spec/implementation gap with minimal diff. The system is correct without the cache; the spec should describe what actually exists. If a price cache is added in a future iteration, the NOTIFY channel can be re-established as part of that change.

**Trade-offs:**

Option A removes a channel that the spec currently says is part of the "closed list of v1 channels." Re-adding it later requires a spec amendment. This is acceptable for a v2 optimization feature.

Option B adds complexity for a feature with no demonstrated performance need at v1 scale. The `AssetSnapshotReader.readLatest` query is a simple indexed `ORDER BY captured_at DESC LIMIT 1` and the price snapshot table is small (one row per (asset, sub_verb) per tick, aged out by partition drop).

**Alternative options:**

- **Option A** (the recommended fix above) — Remove the NOTIFY and correct the spec.
- **Option B** — Implement the full cache + LISTEN consumer as described in the current spec. Pros: matches the spec. Cons: significant complexity for zero measured performance benefit in v1.

---

## Synthesizer-relevant observations

- The SPI interface inventory (Fetcher, StreamSource, LlmProvider, EmbeddingProvider, MessagingAdapter, TranslationProvider) is fully matched between spec and implementation. The Fetcher/StreamSource SPIs live in `infochat-core`; `TranslationProvider` lives in `infochat-messaging-adapter`. No orphan SPIs and no SPIs with conflicting implementations. The prior DAG violation (`infochat-llm-adapter` depending on `infochat-messaging-adapter` for `LlmTranslationProvider`) was resolved by M1-071.
- Capability flags are validated at startup via `AdapterRegistry` gate 3 (`supportsMarkdownLinks=false`). All three concrete adapters (SimpleX, Signal, InMemory) declare this flag as `false`. Gate 4 (mention-by-contact-id) is hardcoded to no-op in v1 via `GROUP_SPI_WIRED = false` — by design for v1, to be wired in T2-F.
- The `provider_state` cursor for `new_post` and `quarantine_review` channels uses the correct compare-and-swap pattern with compound-tuple comparison. The reconcilers and live listeners are properly sequenced via `@Priority` ordering.
- The `vps` profile does not declare `infochat.heartbeat.interval`, falling back to the base default of `5s`. The properties file's own comment acknowledges this is "too aggressive for Pi-class hardware" and recommends operator-side override. This is a pre-existing known gap, not a new finding.
