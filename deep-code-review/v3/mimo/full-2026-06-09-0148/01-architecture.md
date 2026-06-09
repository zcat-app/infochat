# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-09
**Reviewer:** senior-developer (mimo)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — cross-cutting — ReEvaluationJob emits quarantine_review NOTIFY with Java-built JSON while stored procedures use jsonb_build_object; two payload-build strategies for the same channel contract.
- [low] SIMPLIFICATION — cross-cutting — TranslationProvider lives in infochat-messaging-adapter but its LLM-backed implementation dispatches through the LlmProvider SPI; the split adds a cross-module indirection with one consumer.

## Detail

### F1. Dual NOTIFY payload construction strategies for quarantine_review

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** cross-cutting (see CURRENT-CODE)
- **Surface:** NOTIFY

**Current code:**

```java
// QuarantineNotifyEmitter.java:79-81
String payload = "{\"target_kind\":\"" + targetKind.wireValue()
    + "\",\"target_id\":\"" + targetId
    + "\",\"new_status\":\"" + newStatus.name() + "\"}";
```

```sql
-- V32__quarantine_review_notify_completeness.sql:96-99
PERFORM pg_notify('quarantine_review',
    jsonb_build_object('target_kind', 'quarantine',
                       'target_id', p_quarantine_id,
                       'new_status', 'APPROVED')::text);
```

**Why this is wrong / suboptimal / risky:**

The `quarantine_review` NOTIFY channel has one spec-defined payload shape: `{"target_kind":"...","target_id":"...","new_status":"..."}`. Two independent construction strategies exist:

1. **Java-side** (`QuarantineNotifyEmitter`): string concatenation of closed-set enum values and a UUID. Safe because every interpolated value comes from a closed set (TargetKind enum, NewStatus enum, UUID), as the class javadoc correctly notes.

2. **SQL-side** (`approve_quarantine` / `reject_quarantine` stored procedures, V32+): `jsonb_build_object(...)::text`.

These produce semantically equivalent but byte-different payloads. The Java side always uses `Instant.toString()` for timestamps (in `ReadyPromoter` for `new_post`) and `UUID.toString()` / `Enum.name()` for the quarantine channel. The SQL side uses Postgres's `to_jsonb(timestamptz)` rendering, which V32's comment explicitly notes differs from V25's `::TEXT` cast (the ISO-8601 T separator vs. Postgres's space-separated format).

For `quarantine_review` specifically, there is no timestamp in the payload (only `target_kind`, `target_id`, `new_status`), so the format divergence is currently harmless -- `UUID.toString()` and `jsonb_build_object(uuid)` both produce the standard UUID string, and the enum wire values are identical ASCII. But the two-construction-pattern precedent is a maintenance risk: if the channel contract ever adds a timestamp field (e.g., a `transitioned_at` cursor key), the Java and SQL sides would silently produce different ISO-8601 formats, and the Provider's `Instant.parse` would reject one.

The spec says "cursor only" for NOTIFY payloads, and the `quarantine_review` channel's cursor is `(reviewed_at, target_kind, target_id)` per `architecture.md`. The `reviewed_at` value is read from the row by the consumer (`QuarantineReviewListener.lookupRowState`), not from the payload, so the payload does not carry it today. This is correct. The finding is about construction-path divergence, not about a current parse failure.

**Recommended fix:**

No code change needed today. The two strategies are correct for their contexts (Java code cannot call `jsonb_build_object`; PL/pgSQL cannot call Java). The mitigation is documentation: a comment in `QuarantineNotifyEmitter` noting that the stored procedures in V32+ build the same shape via `jsonb_build_object`, and a comment in V32+ noting the Java-side construction, so a future channel-contract change hits both sites.

**Reasoning:**

The two construction paths are an inherent consequence of NOTIFY emitters living in both Java (Collector-side eval pipeline) and PL/pgSQL (admin stored procedures). Making them identical-by-construction is not possible without a shared library, which does not exist across the Java/SQL boundary. The current state is safe; the risk is future drift.

**Trade-offs:**

None -- the fix is a comment-only addition.

---

### F2. TranslationProvider placement in infochat-messaging-adapter

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/TranslationProvider.java
- **Surface:** SPI

**Current code:**

```java
// TranslationProvider.java — lives in infochat-messaging-adapter
package app.zcat.infochat.messaging;

public interface TranslationProvider {
    String translate(String text, Locale from, Locale to);
}
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/llm.md` explicitly states: "Translation is a presentation-layer concern (decision D29) and the contract is model-agnostic ... The SPI stays specified in this file because section Translation flow and the TRANSLATOR routing are part of this file's surface." The SPI interface lives in `infochat-messaging-adapter`, but the LLM-backed implementation dispatches through the `LlmProvider` SPI's `TRANSLATOR` task, which lives in `infochat-llm-adapter`. This means:

- `infochat-provider` depends on both `infochat-messaging-adapter` (for the SPI) and `infochat-llm-adapter` (for the LLM-backed impl that the SPI delegates to).
- A future non-LLM translation provider (e.g., a deterministic dictionary) would correctly live in `infochat-messaging-adapter` alongside the SPI. But the only v1 implementation is LLM-backed, so the SPI placement in the messaging module creates a cross-module call chain (Provider -> messaging-adapter SPI -> llm-adapter impl) that could be a direct call if the SPI were in `infochat-llm-adapter` or `infochat-core`.

The spec's rationale ("presentation-layer concern") is sound, and the placement matches the spec's explicit instruction. This is not a spec-drift finding -- the code matches the spec. It is an architectural observation that the module boundary adds indirection with a single consumer, and the cost is low.

**Recommended fix:**

None for v1. If a second non-LLM translation provider materializes (v2), the current placement is vindicated. If it does not, the SPI could move to `infochat-core` to simplify the dependency chain.

**Reasoning:**

The placement is spec-correct and the cost is one extra module hop at startup wiring time (zero runtime overhead -- CDI resolves the bean once). Flagging it as a low-severity simplification observation, not a recommendation to change.

**Trade-offs:**

Moving the SPI now would be a premature refactor with no second consumer to justify it.

---

## Synthesizer-relevant observations

The architectural surface is well-maintained. The seven checked surfaces (SPI interfaces, schema/migrations, NOTIFY senders/consumers, capability flags, property-key surface, module DAG, trust-boundary placement) are consistent with the spec. Specific observations:

- **Module DAG** is enforced: Collector depends on (core, ssrf, llm-adapter) and is banned from depending on messaging-adapter. Provider depends on all four siblings. The three shared modules (ssrf, llm-adapter, messaging-adapter) do not depend on each other. The DAG matches `docs/design/09-reference.md` byte-for-byte.
- **NOTIFY channels** are exactly the spec's two-channel closed list (`new_post`, `quarantine_review`). The `new_price_snapshot` channel was dropped by M1-234. Producers and consumers agree on payload shape. The high-water-mark cursor mechanism is implemented correctly in both listeners with CAS updates and post-reconnect catch-up.
- **Capability flags** are validated at startup: `supportsMarkdownLinks == false` is enforced by AdapterRegistry Gate 3, `supportsMentionByContactId` + group-SPI wiring by Gate 4, production-exclusion (inmemory + others) by Gate 5, LOW-trust opt-in by Gate 6. All gates match the spec.
- **Schema invariants** are enforced at the trigger layer: last-admin protection (V5, hardened in V24/V35/V40) uses `LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE` and a custom SQLSTATE `IC001`. The stored procedures (`approve_quarantine`, `reject_quarantine`) are `SECURITY DEFINER` with `SET search_path = pg_catalog, public` and actor-admin verification.
- **Trust boundaries** are correctly placed: SSRF defense in `infochat-ssrf` (shared library), identity assertion at adapter wire-decode time, ban check before parsing, authorization in deterministic Java, LLM downstream of every security decision.
