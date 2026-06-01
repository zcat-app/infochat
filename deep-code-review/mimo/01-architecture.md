# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-01 23:55
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — cross-cutting — `new_price_snapshot` NOTIFY channel has no LISTEN consumer in Provider production code despite `security.md` §DB roles listing it as consumed

## Detail

### F1. `new_price_snapshot` NOTIFY channel missing Provider-side LISTEN consumer

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** cross-cutting (see CURRENT-CODE)
- **Surface:** NOTIFY

**Current code:**

`docs/spec/security.md` §DB roles (line 1047):
```
LISTEN/NOTIFY (consumes `new_post`, `new_price_snapshot`, and
`quarantine_review` channels per `architecture.md`
§Inter-service communication).
```

`docs/spec/architecture.md` §Inter-service communication (lines 43-50):
```
- `new_price_snapshot` — fires on a successful Fetcher write to
  `price_snapshot`. Payload carries `(asset, source)` where
  `source` is the sub-verb value (e.g. `coingecko`, `kraken`).
  Correctness mechanism: best-effort; the Provider's in-process
  cache is **flushed entirely on every Postgres reconnect** so
  a missed NOTIFY during a connection blip cannot serve a stale
  row past the reconnect
```

`docs/spec/commands.md` §Asset commands (lines 277-284):
```
The Provider may keep an in-process cache keyed by `(asset, sub-verb)`
and warm/invalidate it from the `NOTIFY` payload, but the cache
is an optimization; correctness comes from the table read, not
from the notification. **The Provider's in-process
`price_snapshot` cache is flushed entirely on every Postgres
reconnect** so a missed `NOTIFY` during a connection blip cannot
serve a stale row past the reconnect
```

Provider production code (`infochat-provider/src/main/java/`): grep for `new_price_snapshot` across all Java files returns zero matches. The `AssetSnapshotReader` reads directly from the `price_snapshot` table on every `/zcash` / `/monero` invocation with no NOTIFY-driven cache invalidation. There is no `PriceSnapshotListener` class.

By contrast, `new_post` has `NewPostListener` + `NewPostReconciler`, and `quarantine_review` has `QuarantineReviewListener` + `QuarantineReviewReconciler`.

**Why this is wrong / suboptimal / risky:**

The spec declares three NOTIFY channels the Provider consumes. Two of the three have LISTEN handlers; the third does not. The spec explicitly describes a Provider-side in-process cache with flush-on-reconnect semantics, but no such cache or listener exists.

This is not a correctness issue — the spec is clear that "correctness comes from the table read, not from the notification." However, the spec/security.md §DB roles explicitly commits to the Provider consuming all three channels, and the architecture.md describes cache-warming behavior that does not exist. A future developer reading the spec will expect to find a listener; the absence is a documentation-to-implementation drift.

The practical consequence is that every `/zcash` or `/monero` invocation hits the database directly. For a v1 deployment with a handful of assets and modest user load, this is negligible. At scale (many assets, frequent invocations), the missing cache means unnecessary DB round-trips that the spec anticipated and designed around.

**Recommended fix:**

Two options depending on intent:

1. **If the cache optimization is desired:** Implement a `PriceSnapshotListener` (parallel to `NewPostListener`) that LISTENs on `new_price_snapshot`, maintains a Caffeine `Cache<(asset, sub_verb), Snapshot>`, and flushes on Postgres reconnect. Wire `AssetSnapshotReader.readLatest()` to check the cache before falling back to the table. No `provider_state` row needed (best-effort channel).

2. **If the spec should match the implementation:** Amend `security.md` §DB roles to read "consumes `new_post` and `quarantine_review` channels" and add a note to `architecture.md` §Inter-service communication that the `new_price_snapshot` cache is deferred to a future milestone. Remove the "flushed entirely on every Postgres reconnect" sentence from `commands.md` §Asset commands.

**Reasoning:**

Option 2 is the lower-cost fix and matches the current implementation. Option 1 closes the spec-implementation gap by building the described feature. The choice depends on whether the cache optimization is a near-term priority.

**Trade-offs:**

- Option 1: Adds a LISTENER bean, a Caffeine cache, and reconnect-flush logic — roughly 100-150 lines of new code plus a test. Closes the spec gap by implementation.
- Option 2: A spec/doc edit. Acknowledges the optimization is deferred. Simpler but leaves the architectural surface smaller than originally designed.

---

## Synthesizer-relevant observations

- **SPI inventory:** All ten SPI interfaces (`Fetcher`, `StreamSource`, `LlmProvider`, `EmbeddingProvider`, `TranslationProvider`, `ProgressNotifier`, `MessagingAdapter`, `RedactionHook`, `AssetDataSource`, `CommandHandler`) are present with implementations. No orphan SPIs (interfaces with zero impls). The `Fetcher` and `AssetDataSource` SPIs are cleanly separated by output type (posts vs. price snapshots), matching the spec's "output-type discriminator" concept at the SPI level rather than within a single interface.

- **Module DAG:** All six modules match the spec's declared DAG exactly. `infochat-core` has no inter-module deps; `infochat-ssrf`, `infochat-llm-adapter`, and `infochat-messaging-adapter` depend only on `infochat-core` and not on each other; `infochat-collector` depends on `core + ssrf + llm-adapter` (correctly NOT `messaging-adapter`); `infochat-provider` depends on all four shared modules. No layering violations found.

- **Schema/migrations:** 29 migration files (V1-V29) cover all spec-committed entities. Key invariants are enforced at the schema layer: audit-log append-only (triggers on UPDATE/DELETE), last-admin protection (trigger serialization), at-most-one group admin (partial unique index), soft-delete-only for sources (DELETE revoked from service roles). The `provider_state` singleton-per-channel constraint and CAS cursor update match the spec's inter-service contract precisely.

- **NOTIFY contract:** All three channels (`new_post`, `new_price_snapshot`, `quarantine_review`) have correctly-shaped producers in the Collector. The `new_post` and `quarantine_review` consumers in Provider honor the high-water-mark cursor contract with compare-and-swap updates and same-transaction idempotency. The `quarantine_review` consumer correctly looks up `reviewed_at` from the DB (per the spec's "NOTIFY is the wake-up signal" principle) rather than embedding it in the payload.

- **Capability flags:** `supportsMarkdownLinks=false` is validated at adapter registration startup (AdapterRegistry gate 3). All spec-required flags are present in the `CapabilityFlags` record. The `supportsMentionByContactId` + group-SPI gate (gate 4) correctly prevents group mode for adapters without cryptographic mention anchoring.

- **Trust boundaries:** Adapter inbound validation (identity assertion, ban check before parser, rate cap before all application logic), SSRF guards (shared `infochat-ssrf` module used by both services), LLM tool surface (closed allowlist in spec), and quarantine stored procedures (SECURITY DEFINER, Provider has EXECUTE not SELECT on raw quarantine) are all correctly placed. The `audit_log_view` redaction boundary (Provider reads through the view, never the raw table) matches the spec's DB-roles commitment.

- **Property-key surface:** ~90 `@ConfigProperty` annotations across collector and provider, with consistent `infochat.*` namespace. Profile-driven keys (`%laptop.*`, `%vps.*`, `%pi.*`, `%remote-llm.*`) are consistently declared in both modules' `application.properties`. No orphaned or undeclared keys found.

- **Audit log coverage:** The `AuditAction` enum (36 values) covers all spec-committed verbs plus post-V5 additions. The `audit_log` CHECK constraint on `target_kind` matches the spec's closed set. The `RedactionHook` SPI correctly applies API-key catalogue redaction to `details_json` at write time, while contact-id redaction happens at read time via `audit_log_view`.
