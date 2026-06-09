# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-09 18:42
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — docs/spec/architecture.md:159-174 vs infochat-core/.../ingest/Fetcher.java:16-36 — spec commits the Fetcher SPI to carrying an "output-type discriminator" routing asset results, but the code implements asset ingest as a wholly separate `AssetDataSource` SPI and `Fetcher.java` explicitly disclaims the discriminator; the spec sentence describes a mechanism that does not exist.
- [medium] MAINTAINABILITY-RULES-DRIFT — V32__...sql:67-99, V41__...sql:55-90 vs V5__identity_audit.sql:363-394 — `approve_quarantine` / `reject_quarantine` write their `audit_log` row *after* the quarantine/post side effects, contradicting schema Invariant 7 ("audit-before-effect") which the sibling `delete_preban_user` procedure deliberately honors; the two privileged-procedure paths order audit-vs-effect inconsistently.

## Detail

### F1. Fetcher SPI output-type discriminator: spec commits a mechanism the code does not implement

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** docs/spec/architecture.md:159-174 (spec); infochat-core/src/main/java/app/zcat/infochat/core/ingest/Fetcher.java:16-36 (code); infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/AssetDataSource.java (parallel SPI)
- **Surface:** SPI / spec-internal

**Current code:**

```
docs/spec/architecture.md:159-168 (§Ingest SPIs — Output type):
  Asset Fetchers (decision D39) produce `price_snapshot` rows
  instead and write **directly** to the `price_snapshot` table —
  they never hit the post outbox, never go through Stage 1/2,
  tagger, or embedding. The Fetcher SPI carries an output-type
  discriminator so the Collector's per-tick dispatch routes the
  result to the right sink.
```

```java
// infochat-core/.../ingest/Fetcher.java:16-19, 20-36
 * Pagination, retry, backoff, and the asset-Fetcher output-type
 * discriminator are implementation concerns or follow-up tickets;
 * they are intentionally NOT method-shape commitments here.</p>
 */
public interface Fetcher {
    List<NormalizedPost> fetch(long sourceId, String identifier);
}
```

```java
// infochat-collector/.../assets/source/AssetDataSource.java:25-60
public interface AssetDataSource {
    String id();
    Set<String> supportedAssets();
    Set<String> supportedQuoteCurrencies(String asset);
    PriceSnapshot fetchSnapshot(String asset, String vs) throws FetchException;
    String attributionUrl(String asset, String vs);
}
```

**Why this is wrong / suboptimal / risky:**

The spec describes one polymorphic SPI (`Fetcher`) that dispatches output by an "output-type discriminator." The implementation instead has two unrelated SPIs: `Fetcher` returns `List<NormalizedPost>` for the outbox path, and `AssetDataSource` returns a single `PriceSnapshot` for the direct-to-`price_snapshot` path, driven by a separate `AssetSnapshotFetcher`. There is no discriminator on `Fetcher`; `Fetcher.java` explicitly states the discriminator is "intentionally NOT" part of its shape.

The implemented two-SPI split is the *better* design — the asset path has no `NormalizedPost`, no outbox, no Stage 1/2, and a different return cardinality, so forcing it through a discriminated `Fetcher` would have been a leaky union. The design note (`docs/design/10-asset-commands.md:54-57`) already documents `AssetDataSource` as the real SPI. The defect is therefore not the code — it is that the spec text still asserts a contract mechanism ("The Fetcher SPI carries an output-type discriminator") that no longer exists. Per the review contract, code contradicting a spec commitment is spec-drift, and since spec left the discriminator open while design + code chose a separate SPI, the spec sentence is the artifact to fix. Left as-is, a future ticket reading the spec literally could re-introduce a discriminator on `Fetcher` to "comply," undoing the cleaner split.

**Recommended fix:**

Amend `docs/spec/architecture.md` §Ingest SPIs "Output type" to describe the two-SPI shape the code commits to:

```
- **Output type.** A polled source is shaped around what it produces.
  Post-producing sources implement `Fetcher` and return normalized
  posts that flow into the post outbox. Asset sources (decision D39)
  implement a **separate** SPI (`AssetDataSource`) that returns a
  single `price_snapshot` row and writes **directly** to the
  `price_snapshot` table — they never hit the post outbox, never go
  through Stage 1/2, tagger, or embedding. The two SPIs are distinct
  rather than one discriminated interface because the asset path has
  no `NormalizedPost`, no outbox, and a different return cardinality;
  a shared discriminated `Fetcher` would be a leaky union. The
  Collector dispatches asset sources from `asset_config` and post
  sources from `source`; there is no shared per-tick dispatcher.
```

**Reasoning:**

The fix makes the spec match the implemented and design-documented shape, removes the phantom "discriminator" commitment, and records *why* the split exists so the rationale survives. It anchors the spec to `asset_config`-vs-`source` dispatch, which is what the code actually does.

**Trade-offs:**

None — the fix is strictly better. It is a pure spec edit (`spec:` prefix per the non-ticket commit rules); no code changes.

---

### F2. Quarantine stored procedures write audit after side effects, violating Invariant 7

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-core/src/main/resources/db/migration/V32__quarantine_review_notify_completeness.sql:67-99 and V41__approve_quarantine_clears_stage2_failed.sql:55-90 (current `approve_quarantine` / `reject_quarantine` bodies); contrast V5__identity_audit.sql:363-394 (`delete_preban_user`)
- **Surface:** schema / spec-internal

**Current code:**

```sql
-- V41 approve_quarantine (and V32 reject_quarantine identically):
    UPDATE quarantine
       SET status = 'APPROVED', updated_at = now(), reviewed_by = p_actor_id
     WHERE id = p_quarantine_id;
    ...
    UPDATE post
       SET body = replace(...), status = 'READY', ready_at = v_ready_at, ...
     WHERE id = v_post_id AND fetched_at = v_post_fetched_at;

    INSERT INTO audit_log (actor_user_id, actor_contact_id, actor_adapter,
                           action, target_kind, target_id, details_json)
    SELECT p_actor_id, a.contact_id, a.adapter,
           'APPROVE_QUARANTINE', 'quarantine', p_quarantine_id::TEXT, ...
      FROM users a WHERE a.id = p_actor_id;
```

```sql
-- V5 delete_preban_user — the sibling privileged procedure, ordered the other way:
--      (c) writes the UNBAN_PREBAN_DELETE audit row BEFORE the DELETE
--          (audit-before-effect, Invariant 7), (d) issues the DELETE FROM users.
    INSERT INTO audit_log (...) SELECT ... 'UNBAN_PREBAN_DELETE' ...;
    DELETE FROM users WHERE id = p_user_id AND registration_state = 'preban';
```

**Why this is wrong / suboptimal / risky:**

Schema Invariant 7 (`docs/spec/schema.md:714-715`) is unambiguous: "Privileged actions write to `audit_log` *before* their side effects, so an interrupted command leaves a record of intent." `/quarantine approve` and `/quarantine reject` are privileged actions (they appear in the closed audit verb catalogue, `V5__identity_audit.sql:294-295`, as `APPROVE_QUARANTINE` / `REJECT_QUARANTINE`). Both procedures perform the quarantine-row UPDATE and (for approve) the post-body restore + `READY` promotion *before* the `audit_log` INSERT — the reverse of the invariant.

The `delete_preban_user` procedure, on the same trust boundary, encodes the invariant correctly and even comments it (`V5:363-364`). So the contract surface contradicts itself: two SECURITY DEFINER privileged procedures order audit-vs-effect in opposite directions, and one of them violates a schema invariant the spec calls "non-negotiable."

The practical blast radius is bounded — both procedures run in a single transaction, so an interruption rolls back the effects and the audit together, and no half-applied state escapes. That is why this is medium, not high. But the invariant is stated as a hard ordering rule the schema "must enforce," it is tested in CI per `verification.md`, and the inconsistency is a maintainability hazard: a reviewer or a future verification test that pins audit-before-effect by statement order will flag (or should flag) the quarantine procedures, and a developer copying the quarantine pattern into a non-transactional path would silently lose the intent record the invariant exists to guarantee.

**Recommended fix:**

Reorder both procedure bodies so the `audit_log` INSERT precedes the state mutations. The audit row for `approve_quarantine` references `v_post_id`, which is read from the `SELECT ... FOR UPDATE` before any UPDATE, so it is available to the INSERT without reordering the read:

```sql
-- approve_quarantine, after the FOR UPDATE read and status guard:
    -- Audit-before-effect (Invariant 7): record intent before mutating.
    INSERT INTO audit_log (actor_user_id, actor_contact_id, actor_adapter,
                           action, target_kind, target_id, details_json)
    SELECT p_actor_id, a.contact_id, a.adapter,
           'APPROVE_QUARANTINE', 'quarantine', p_quarantine_id::TEXT,
           jsonb_build_object('post_id', v_post_id::TEXT)
      FROM users a WHERE a.id = p_actor_id;

    UPDATE quarantine SET status = 'APPROVED', updated_at = now(),
           reviewed_by = p_actor_id WHERE id = p_quarantine_id;

    v_ready_at := now();
    UPDATE post SET body = replace(...), status = 'READY', ready_at = v_ready_at,
           status_changed_at = v_ready_at, stage2_failed = FALSE
     WHERE id = v_post_id AND fetched_at = v_post_fetched_at;

    PERFORM pg_notify('new_post', ...);
    PERFORM pg_notify('quarantine_review', ...);
```

Ship it as a new migration (`CREATE OR REPLACE FUNCTION` for both procedures) so the ACL and the SECURITY DEFINER / `SET search_path` pin carry forward, exactly as V32/V41 did. Keep `v_ready_at := now()` where the post UPDATE needs it; only the audit INSERT moves up.

**Reasoning:**

Reordering aligns the quarantine procedures with the invariant and with the `delete_preban_user` precedent, making the audit-before-effect rule uniform across every privileged stored procedure. Because `v_post_id` is read before any mutation, the `details_json` payload is unaffected. The NOTIFY emits stay last (they are the wake-up signal and must fire after the row is in its final state, which is consistent with "effect then notify").

**Trade-offs:**

None of substance. The audit row now commits in the same transaction slightly earlier in statement order; on rollback nothing changes (the whole transaction discards either way). One extra migration file.

**Alternative options:**

- **Option A** (the recommended fix above) — reorder so audit precedes effect in both procedures.
- **Option B** — leave the procedures as-is and instead amend Invariant 7 to carve out "single-transaction stored procedures may order audit after effect because rollback is atomic." This is *worse*: it weakens a security-relevant invariant to match drifted code, splits the invariant's meaning across contexts, and leaves `delete_preban_user` doing extra ordering work the invariant no longer requires. The invariant's value is its uniformity; carving exceptions erodes that. Reject unless the user explicitly prefers minimizing migration churn.

## Synthesizer-relevant observations

The cross-module contract surface is otherwise sound where inspected and does not warrant findings: the 6-module DAG is enforced at build time (`infochat-collector/pom.xml:141-164` bans `infochat-messaging-adapter`; `infochat-core/pom.xml` carries no JAX-RS/Hibernate, keeping it transport-free); the `new_post` and `quarantine_review` NOTIFY producer/consumer payload formats agree and the ISO-8601 timestamp mismatch was already remediated in V32 (`Instant.parse`-compatible `jsonb_build_object` rendering on both Java and SQL emit paths); the `supportsMarkdownLinks == false` v1 invariant is validated at startup in `AdapterRegistry` (gate 3, line 182-189) and all three adapters declare it false; the audit log is INSERT-only for both service roles with `UPDATE`/`DELETE` blocked by trigger and the Provider reading only the redacted `audit_log_view`; and the quarantine raw-original trust boundary is correctly placed (Provider has `SELECT` only on `quarantine_review_view`, which omits `original_html`, with restore performed via SECURITY DEFINER procedures). No padding findings were manufactured for these areas.
