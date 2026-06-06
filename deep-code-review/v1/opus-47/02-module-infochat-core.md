# Deep code review: module infochat-core

**Target:** module infochat-core
**Lens:** module
**Module path:** infochat-core/
**Date:** 2026-06-01 12:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/resources/db/migration/V24__identity_audit_remediation.sql:44-53 — `delete_preban_user` audit-row INSERT drops the spec-mandated denormalized `actor_contact_id` / `actor_adapter` columns that V5 originally wrote; same regression in V25's `approve_quarantine` / `reject_quarantine`.
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/resources/db/migration/V7__joins_post.sql:212-214 — comment references an `infochat_listen` role that is never created by V2 (`docs/design/02-schema.md` §DB roles enumerates it but the role-creation migration omits it).
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/resources/db/migration/V16__admin_notification_state.sql:67-73 — GRANT-block comment claims "the notifier lives in infochat-collector" but M1-082 relocated `ThrottledAdminNotifier` into infochat-core and the Provider is now a writer (V21 corrects the grants but V16 still asserts the stale wiring).
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-core/src/main/resources/db/migration/V21__quarantine_admin.sql:74-75 (and V25:62-63) — `pg_notify` JSON payload built via raw `||` string concatenation of `v_ready_at::TEXT` and `v_post_id::TEXT`; correct today only because timestamp/UUID text forms happen not to contain `"` or `\`.
- [low] SIMPLIFICATION — infochat-core/src/test/java/app/zcat/infochat/core/ingest/IngestSpisLoadTest.java:20-39 — three `Class.forName` + `assertTrue(type.isInterface() / .isRecord())` checks restate what the compile-time imports already guarantee; the test exercises no behaviour.

## Detail

### F1. SECURITY DEFINER procedures drop the spec-mandated `actor_contact_id` / `actor_adapter` denormalization

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-core/src/main/resources/db/migration/V24__identity_audit_remediation.sql:44-53 and infochat-core/src/main/resources/db/migration/V25__quarantine_procedure_remediation.sql:58-60, 100-101

**Current code:**

```sql
-- V24: delete_preban_user (replaces the V5 body)
INSERT INTO audit_log (
    actor_user_id,
    action, target_kind, target_id, target_contact_id,
    scope_id, request_id, details_json
)
SELECT p_actor_id,
       'UNBAN_PREBAN_DELETE', 'user', u.id::TEXT, u.contact_id,
       NULL, current_setting('infochat.request_id', TRUE), '{}'::JSONB
  FROM users u
 WHERE u.id = p_user_id;
```

```sql
-- V25: approve_quarantine
INSERT INTO audit_log (actor_user_id, action, target_kind, target_id, details_json)
VALUES (p_actor_id, 'APPROVE_QUARANTINE', 'quarantine', p_quarantine_id::TEXT,
        jsonb_build_object('post_id', v_post_id::TEXT));

-- V25: reject_quarantine
INSERT INTO audit_log (actor_user_id, action, target_kind, target_id)
VALUES (p_actor_id, 'REJECT_QUARANTINE', 'quarantine', p_quarantine_id::TEXT);
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/schema.md` §Identity and access — Audit log commits the schema to denormalizing two columns at write time:

> `actor_contact_id` (denormalized at write time for redaction-free historical lookup; the FK target may rotate), `actor_adapter` (denormalized adapter name)

The original V5 body of `delete_preban_user` honoured this commitment (it joined `users a ON a.id = p_actor_id` and wrote `a.contact_id` / `a.adapter` into the row). The V24 rewrite removed both columns from the INSERT with the in-line justification "derivable from actor_user_id by any reader that needs them" — but that reasoning contradicts the spec's "redaction-free historical lookup" rationale, which is exactly the property that fails when `actor_user_id` no longer resolves (or when the row is read through `audit_log_view`, which projects `redact_contact_id(actor_contact_id) AS actor_contact_id` and therefore returns NULL for these procedure-written rows).

V25's `approve_quarantine` / `reject_quarantine` inherit the same shape — they were authored after V24 and copied the same omission. The three SECURITY-DEFINER procedures are the only Postgres-internal writers of `audit_log`; every Java-side write through `AuditLogWriter` correctly populates the two columns (`infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java:110-111`). The audit_log column set on procedure-written rows therefore drifts from every other row in the table.

Observable impact: a `SELECT actor_contact_id, actor_adapter FROM audit_log_view WHERE action IN ('UNBAN_PREBAN_DELETE','APPROVE_QUARANTINE','REJECT_QUARANTINE')` returns NULL on rows where the spec promises a (redacted) contact id and a non-NULL adapter name. The `audit_log` row-shape invariant in §Identity and access is observably violated.

**Recommended fix:**

```sql
-- V24 delete_preban_user (re-add the join + the two columns):
INSERT INTO audit_log (
    actor_user_id, actor_contact_id, actor_adapter,
    action, target_kind, target_id, target_contact_id,
    scope_id, request_id, details_json
)
SELECT p_actor_id,
       a.contact_id, a.adapter,
       'UNBAN_PREBAN_DELETE', 'user', u.id::TEXT, u.contact_id,
       NULL, current_setting('infochat.request_id', TRUE), '{}'::JSONB
  FROM users u
  JOIN users a ON a.id = p_actor_id
 WHERE u.id = p_user_id;
```

```sql
-- V25 approve_quarantine / reject_quarantine (look up actor and inline):
DECLARE
    v_actor_contact_id TEXT;
    v_actor_adapter    TEXT;
BEGIN
    SELECT contact_id, adapter INTO v_actor_contact_id, v_actor_adapter
      FROM users WHERE id = p_actor_id;
    -- ... existing checks ...
    INSERT INTO audit_log (
        actor_user_id, actor_contact_id, actor_adapter,
        action, target_kind, target_id, details_json
    ) VALUES (
        p_actor_id, v_actor_contact_id, v_actor_adapter,
        'APPROVE_QUARANTINE', 'quarantine', p_quarantine_id::TEXT,
        jsonb_build_object('post_id', v_post_id::TEXT)
    );
```

The actor-existence check (`IF NOT EXISTS (SELECT 1 FROM users WHERE id = p_actor_id AND is_admin = TRUE)`) already runs before this code, so the SELECT is guaranteed to find a row; no defensive-code worry.

**Reasoning:**

Re-introducing the JOIN restores the schema-spec commitment: every `audit_log` row carries the denormalized actor contact id and adapter at write time. The `redact_contact_id()` projection in `audit_log_view` then has something to redact instead of passing NULL through, and `/audit` (the Provider's audit-read surface) shows consistent columns regardless of which write path produced the row. The "extra SELECT round-trip" the V24 comment cites is a single indexed PK lookup against `users` — negligible compared to the procedure's own quarantine row lock + post UPDATE + NOTIFY.

**Trade-offs:**

The fix adds one short SELECT (one row, PK lookup) per procedure call. Each procedure is invoked once per admin action (`/unban` against a preban, `/quarantine approve`, `/quarantine reject`) — these are human-rate, not batch — so the latency impact is unmeasurable. No other downside.

---

### F2. V7 comment references an `infochat_listen` role that the role-creation migration never created

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-core/src/main/resources/db/migration/V7__joins_post.sql:212-214 (consistent design-note reference at docs/design/02-schema.md:26)

**Current code:**

```sql
-- infochat_listen is the LISTEN/NOTIFY-only role; it gets no
-- privileges on any table created here (it polls the new_post and
-- quarantine_review channels, not the tables).
```

**Why this is wrong / suboptimal / risky:**

The V7 grant block names a role that does not exist. `V2__roles.sql` creates exactly three roles (`infochat_collector`, `infochat_provider`, `infochat_admin`); `infochat_listen` appears nowhere in any migration. A reader inferring the role's existence from the V7 comment will reach for `SET ROLE infochat_listen` or `GRANT … TO infochat_listen` and get a 42704 (undefined object) error. `docs/design/02-schema.md` §DB roles further reinforces the false signal by enumerating `infochat_listen` as one of the v1 roles, so the gap is documented as if intentional in two places without the implementation matching.

The functional code is fine (no GRANT to the non-existent role would fail anyway), but the comment-vs-reality drift is a real maintainability hazard: the next ticket that touches LISTEN/NOTIFY wiring will discover the missing role and have to decide whether to (a) add it and audit the LISTEN/NOTIFY paths the spec implied, or (b) delete the design-note reference. Right now both possibilities are equally consistent with the codebase, which is the failure mode "spec / design / code all disagree" the rules prohibit.

**Recommended fix:**

Either:
- Add `CREATE ROLE infochat_listen NOLOGIN` to V2 (or a fresh migration) plus the design's `LISTEN/NOTIFY`-only privilege story, and document the connecting-role wiring story for whichever service ends up using it, OR
- Delete the design-note line at `docs/design/02-schema.md:26` and the V7 comment, and treat LISTEN/NOTIFY as a capability of the Collector/Provider roles only (which is what the spec actually says: `security.md` §DB roles lists `LISTEN/NOTIFY` as a capability of both service roles, not a separate role).

The spec already endorses option B (`security.md` §DB roles names only three roles), so:

```sql
-- V7 — delete the dangling comment block:
-- (drop lines 212-214 entirely)
```

```markdown
<!-- docs/design/02-schema.md — drop the infochat_listen bullet -->
```

**Reasoning:**

The spec is the contract. `security.md` §DB roles is explicit about three roles; the `infochat_listen` design-note entry contradicts the spec and the V2 migration. Following the rule "spec wins over design notes on conflict," option B (delete the orphan reference) is the surgical fix; option A would require a spec amendment to add a fourth role.

**Trade-offs:**

None — the comment / design line do not back any code path.

---

### F3. V16 grant-block comment hard-codes a pre-relocation wiring assumption

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-core/src/main/resources/db/migration/V16__admin_notification_state.sql:67-73

**Current code:**

```sql
-- admin_notification_state is Collector-write: the notifier lives
-- in infochat-collector and UPSERTs on every notifyOnce call.
-- Provider is read-only — future admin commands (e.g. "show
-- notification state") may query the table to surface counters
-- to bot admins.
```

**Why this is wrong / suboptimal / risky:**

M1-082 (commit `fc8140e` in the history) relocated `ThrottledAdminNotifier` from infochat-collector into infochat-core, and the Provider now calls `notifyOnce` from at least four production call sites (`QuarantineReviewListener`, `GroupApprovalService`, `DigestScheduler`, `ConfirmStateService`). V21 correctly extends the GRANT matrix (`GRANT INSERT, UPDATE ON admin_notification_state TO infochat_provider`) but V16's comment block still claims "the notifier lives in infochat-collector" and "Provider is read-only." A reader auditing the role matrix by reading V16 alone sees a self-consistent statement that the next migration silently contradicts; the drift is the type of stale-comment hazard `CLAUDE.md` §"Coding style — Comment important, crucial, or complex code" warns against ("Don't reference the current ticket … it rots as the codebase evolves").

**Recommended fix:**

```sql
-- admin_notification_state is written by ThrottledAdminNotifier
-- (in infochat-core) from both services. The Collector writes
-- from the eval pipeline (Stage1 timeouts, Stage2 infra failures,
-- TaggerWorker / EmbeddingWorker fallbacks, asset fetcher
-- failures); the Provider writes from QuarantineReviewListener,
-- GroupApprovalService, DigestScheduler, ConfirmStateService.
-- V16 grants the Collector INSERT/UPDATE here; V21 extends the
-- same grant to the Provider for the post-M1-082 wiring.
--
-- Future admin commands ("show notification state") read the
-- table; that's the read-only Provider path the original v1
-- shape anticipated.
```

**Reasoning:**

The comment describes the actual code shape after M1-082, names V21's grant amendment so the reader can find the GRANT in one hop, and explicitly tags the Provider's read-only future use as distinct from the new write call sites. The cost is a five-line comment edit; the benefit is that the V16 GRANT block stops asserting code that doesn't exist.

**Trade-offs:**

None — comment-only edit.

---

### F4. `pg_notify` payload built by raw string concatenation in V21 / V25

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-core/src/main/resources/db/migration/V21__quarantine_admin.sql:74-75 and infochat-core/src/main/resources/db/migration/V25__quarantine_procedure_remediation.sql:62-63

**Current code:**

```sql
PERFORM pg_notify('new_post',
    '{"ready_at":"' || v_ready_at::TEXT || '","post_id":"' || v_post_id::TEXT || '"}');
```

**Why this is wrong / suboptimal / risky:**

The payload is constructed by raw concatenation of `v_ready_at::TEXT` and `v_post_id::TEXT` into a hand-written JSON template. This happens to be correct today because Postgres's `timestamptz::TEXT` format (`2026-05-15 12:00:00+00`) and `uuid::TEXT` format are both `"`-free and `\`-free — but the safety is incidental, not by construction. A future change to `v_ready_at`'s type, or a refactor that interpolates any other column into the payload, can silently produce malformed JSON. The Provider's NewPostListener parses this payload; a malformed message would surface as a parse exception (or worse, a structurally-valid but semantically-wrong cursor advance) at runtime.

This is precisely the failure mode `jsonb_build_object()::text` solves at zero cost — and it is the construction `approve_quarantine`'s own audit-log INSERT already uses one statement earlier in the same procedure.

**Recommended fix:**

```sql
PERFORM pg_notify('new_post',
    jsonb_build_object(
        'ready_at', v_ready_at,
        'post_id', v_post_id
    )::text);
```

**Reasoning:**

`jsonb_build_object` encodes timestamps and UUIDs to JSON-safe strings by construction; the cast to text gives the same payload `pg_notify` expects. The procedure already imports the function (the audit-log INSERT just above uses it) so the fix carries no new dependency. A reader changing the payload shape later can add a key without re-checking the manual quoting.

**Trade-offs:**

The JSONB build path serializes timestamps in ISO 8601 with a `T` separator (e.g. `2026-05-15T12:00:00+00:00`), whereas the current manual concat emits the Postgres-default format with a space separator (`2026-05-15 12:00:00+00`). Whichever the Provider's parser expects must match — pin the format on the receiver side (`Instant.parse` handles ISO 8601 natively, so the JSONB form is the easier ratchet). The receiver migration is a one-line code change in the listener.

**Alternative options:**

- **Option A** (the recommended fix above) — `jsonb_build_object()::text`.
- **Option B** — keep the concat but wrap `v_ready_at::TEXT` and `v_post_id::TEXT` in an explicit `to_jsonb(...)::text` for each interpolated value. Slightly less invasive but loses the structural-safety property when a third key is added later. Not worth the half-measure.

---

### F5. `IngestSpisLoadTest` checks only what the compiler already guarantees

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** infochat-core/src/test/java/app/zcat/infochat/core/ingest/IngestSpisLoadTest.java:20-39

**Current code:**

```java
@Test
void fetcherIsLoadableInterface() throws ClassNotFoundException {
    Class<?> type = Class.forName("app.zcat.infochat.core.ingest.Fetcher");
    assertNotNull(type);
    assertTrue(type.isInterface(), "Fetcher must be an interface");
}

@Test
void streamSourceIsLoadableInterface() throws ClassNotFoundException {
    Class<?> type = Class.forName("app.zcat.infochat.core.ingest.StreamSource");
    assertNotNull(type);
    assertTrue(type.isInterface(), "StreamSource must be an interface");
}

@Test
void normalizedPostIsLoadableRecord() throws ClassNotFoundException {
    Class<?> type = Class.forName("app.zcat.infochat.core.ingest.NormalizedPost");
    assertNotNull(type);
    assertTrue(type.isRecord(), "NormalizedPost must be a record");
}
```

**Why this is wrong / suboptimal / risky:**

The three SPI types are imported and used elsewhere in this same module — they cannot have failed to compile or to land on the classpath without the rest of the build also failing. The `Class.forName` reflective lookup adds no signal a missing import wouldn't have already produced at compile time. The `assertTrue(type.isInterface())` and `assertTrue(type.isRecord())` checks ratify a property the source declaration already commits to in unambiguous Java syntax (`public interface Fetcher`, `public record NormalizedPost(...)`) — if a future change converts `Fetcher` from an `interface` to an `abstract class`, every caller in the codebase fails first with compile errors, not this test.

The test isn't actively harmful, but it occupies the place a real load-test would have to occupy (the class's own javadoc anticipates that real load test as M1-007 territory). Better to remove it than leave a placeholder that future readers might mistake for a real load surface.

**Recommended fix:**

Delete `IngestSpisLoadTest.java`. If the M1-007 cross-module load surface ever materialises, it lives in whichever module bundles the LLM + Messaging + Ingest SPIs together — not in the core module that defines only one of the three.

**Reasoning:**

`CLAUDE.md` §Coding style ("Simplify aggressively. Three similar lines beats a premature abstraction") and §"No defensive code for impossible scenarios" point the same direction here: the test guards against a scenario the compiler already prevents. Removing it shrinks the surface a reader needs to understand without losing any safety property.

**Trade-offs:**

The module loses one test file. The actual coverage the test contributes is zero (every assertion would have to have already passed for the test source itself to compile). No real signal is lost.

## Synthesizer-relevant observations

- The `infochat-listen` reference under F2 spans the V7 migration comment AND `docs/design/02-schema.md` §DB roles. The design-note edit is cross-module and likely belongs to the architecture pass; the migration comment edit lives inside this module.
- The `approve_quarantine` / `reject_quarantine` carve-out path (Provider has `EXECUTE` on these stored procedures, no `SELECT` on raw `quarantine.original_html`) is a cross-module trust-boundary surface — the procedures live in infochat-core but the consumer is infochat-provider. The architecture pass should verify the Provider-side call sites bind `current_setting('infochat.actor_id')` before invoking the procedures so the F1 fix (re-adding actor lookups) is consistent with how the GUC flows.
