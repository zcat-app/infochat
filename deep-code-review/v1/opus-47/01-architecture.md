# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-01 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [critical] SECURITY — cross-cutting (V2 + application.properties) — Collector and Provider both connect to Postgres as the `infochat` superuser; the DB-role least-privilege trust boundary committed by `docs/spec/security.md` §DB roles is decorative.
- [high] MAINTAINABILITY-RULES-DRIFT — `infochat-core/src/main/resources/db/migration/V25__quarantine_procedure_remediation.sql:46-65` — `approve_quarantine` / `reject_quarantine` stored procedures do not fire the `quarantine_review` NOTIFY for `APPROVED` / `REJECTED` transitions; the channel contract in `docs/spec/architecture.md` §Inter-service communication is broken.
- [medium] MAINTAINABILITY-RULES-DRIFT — `infochat-collector/src/main/java/app/zcat/infochat/collector/notify/QuarantineNotifyEmitter.java:39-51` — JSON payload built by string concatenation of caller-supplied `targetKind` and `newStatus` without escaping; an enum-shaped contract is enforced only by caller discipline rather than by code.
- [low] MAINTAINABILITY-RULES-DRIFT — `docs/design/09-reference.md:33-38` — DAG table claims `infochat-ssrf`, `infochat-llm-adapter`, and `infochat-messaging-adapter` depend on `infochat-core`; the actual sibling poms declare no such dependency.
- [low] MAINTAINABILITY-RULES-DRIFT — `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java:150-159` — adapter activation accepts duplicate names from the `infochat.adapters` CSV without dedup; a stutter (`simplex,simplex`) wires the same adapter twice.

## Detail

### F1. Application DB connections use the superuser, not the per-service roles

- **Category:** SECURITY
- **Severity:** critical
- **Location:** cross-cutting (see CURRENT-CODE)
- **Surface:** trust-boundary

**Current code:**

```properties
# infochat-collector/src/main/resources/application.properties:7-13
# V2__roles.sql creates infochat_collector / infochat_provider /
# infochat_admin as NOLOGIN principals. The JDBC username below stays
# the bootstrap `infochat` superuser until the named-datasource wiring
# ticket lands; that ticket switches this module to connect as
# infochat_collector.
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=infochat
```

```properties
# infochat-provider/src/main/resources/application.properties:12-14
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=infochat
```

```sql
-- infochat-core/src/main/resources/db/migration/V2__roles.sql:32-39
-- NOLOGIN is the v1 default for all three roles. Until the
-- named-datasource wiring ticket lands, the bootstrap `infochat`
-- superuser remains the connecting role; the application roles are
-- principals that future per-table GRANTs name. The named-datasource
-- wiring ticket re-evaluates LOGIN on infochat_admin (operator psql
-- path) and on infochat_collector / infochat_provider (Quarkus named
-- datasource JDBC connect path).
```

No `SET ROLE` is issued anywhere in either service's main sources (verified by grep across `infochat-collector/src/main` and `infochat-provider/src/main`).

**Why this is wrong / suboptimal / risky:**

`docs/spec/security.md` §DB roles commits the project to a three-role least-privilege model: `infochat_collector`, `infochat_provider`, `infochat_admin`. The spec is explicit about what this buys:

> The split means a SQL-injection bug in the Provider cannot delete posts, mutate price snapshots, alter quarantine entries, read unredacted audit rows, or read raw quarantine originals.

The spec further commits to specific carve-outs that depend on this split being real:

- `audit_log_view` exists "because granting `SELECT` directly on `audit_log` to the Provider would expose unredacted columns; the view is the single read path for the Provider role."
- The `approve_quarantine` / `reject_quarantine` SECURITY DEFINER stored procedures exist "so the Provider role can call them without raw quarantine table access — Provider only has SELECT on `quarantine_review_view` plus EXECUTE on these two procedures."
- `delete_preban_user` SECURITY DEFINER exists "so the Provider role can invoke the single permitted DELETE path on `users` without carrying raw DELETE privilege."
- `DELETE` on `source` "is revoked from both Collector and Provider roles; only the Admin role (operator psql) can hard-delete a source row" — Invariant 4 enforcement.
- Invariant 10 ("audit log is append-only") is explicitly two-layered: "(a) the DB role grant matrix gives `INSERT`-only to Collector and Provider roles ... (b) no application path in either service issues UPDATE or DELETE against audit_log."

None of these protections is in effect today. Both services connect as the `infochat` superuser (or at minimum the DB owner — same effect on application tables). The migration's `GRANT` / `REVOKE` matrix grants privileges to `infochat_collector` and `infochat_provider` roles that no live session ever assumes. SQL injection in a Provider command handler can:

- read raw `quarantine.original_html` (the verbatim attacker-crafted payload — exactly the column the spec calls out as a re-injection vector for admin clients);
- write `users.is_admin = TRUE` directly, bypassing the last-admin trigger only on a buggy path but bypassing the `/grant-admin` command path entirely;
- `UPDATE` or `DELETE` rows in `audit_log` (the append-only trigger fires but a superuser-equivalent session can `ALTER TABLE ... DISABLE TRIGGER`);
- hard-delete `source` rows (Invariant 4 broken);
- read unredacted `audit_log` rather than `audit_log_view`.

The role separation is not aspirational — it is one of the explicit defense-in-depth layers the spec commits to. Shipping with all of it effectively disabled is a critical architectural-trust-boundary violation. The "until the named-datasource wiring ticket lands" note has been load-bearing for many tickets; the work has not landed.

**Recommended fix:**

```properties
# infochat-collector/src/main/resources/application.properties
# Flyway runs as its own owner; the runtime application connection
# uses the least-privilege role per spec §DB roles.
quarkus.datasource.username=infochat_collector
quarkus.datasource.password=${POSTGRES_COLLECTOR_PASSWORD}

quarkus.flyway.datasource.username=infochat
quarkus.flyway.datasource.password=${POSTGRES_PASSWORD}
```

Same shape for the Provider with `infochat_provider`. The V2 migration must be updated to `ALTER ROLE infochat_collector LOGIN` / `infochat_provider LOGIN` (or create new V-prefixed migrations that do so), and the operator runbook in `docs/design/07-deployment.md` must surface the two per-role passwords as required operator inputs (item 5 of `deployment.md` §Operator inputs already names "DB credentials for the three Postgres roles").

Concretely, the work is:

1. New migration `V30__role_login.sql`: `ALTER ROLE infochat_collector WITH LOGIN`; same for `infochat_provider`. Keep `infochat_admin` NOLOGIN (operator psql will `SET ROLE` from the superuser session, or operator creates a separate password explicitly).
2. Quarkus named-datasource configuration: keep Flyway on the owner datasource (`quarkus.flyway.datasource.*`), runtime application traffic on the role datasource (`quarkus.datasource.*`).
3. Update CI / Testcontainers init so the test container provisions both roles with passwords, and the test application.properties points the runtime datasource at the role.
4. Sweep production code paths against the new GRANT matrix: every read against `audit_log` is now a SQL error and must be routed through `audit_log_view`; every direct DELETE / UPDATE on the protected tables is now a SQL error and must be routed through the stored procedures or escalated.

**Reasoning:**

The spec's trust-boundary commitments only protect what the runtime enforces. A grant matrix the application never connects under is a comment, not a security control. The right time to close this is before more code paths accumulate against the implicit "superuser at runtime" assumption; every additional handler that runs raw DML on a protected table is one more migration the eventual fix has to sweep.

The Testcontainers IT suite is also currently passing without role isolation — closing this hardens the test suite too, surfacing privilege-mismatched DML as build failures rather than runtime ones in production.

**Trade-offs:**

- Adds one operator input (a second DB password per service) and one more migration. Both are bounded.
- A class of IT failures may surface as the test suite starts running under the restricted role — those are real bugs the current setup hides, not false positives.
- `EXECUTE` on `approve_quarantine` / `reject_quarantine` and `delete_preban_user` is already granted to `infochat_provider` (V21 and V5 respectively), so the procedure-callsite paths work unchanged once the connection role flips.

---

### F2. `approve_quarantine` and `reject_quarantine` do not fire `quarantine_review` NOTIFY

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** `infochat-core/src/main/resources/db/migration/V25__quarantine_procedure_remediation.sql:46-65`, same file `:67-104`
- **Surface:** NOTIFY

**Current code:**

```sql
-- V25 lines 46-65 (approve_quarantine body, post-remediation):
    UPDATE quarantine
       SET status = 'APPROVED', updated_at = now(), reviewed_by = p_actor_id
     WHERE id = p_quarantine_id;

    v_ready_at := now();
    UPDATE post
       SET body = replace(body, '[REDACTED:' || v_placeholder_id || ']', v_original_html),
           status = 'READY',
           ready_at = v_ready_at,
           status_changed_at = v_ready_at
     WHERE id = v_post_id AND fetched_at = v_post_fetched_at;

    INSERT INTO audit_log (actor_user_id, action, target_kind, target_id, details_json)
    VALUES (p_actor_id, 'APPROVE_QUARANTINE', 'quarantine', p_quarantine_id::TEXT,
            jsonb_build_object('post_id', v_post_id::TEXT));

    PERFORM pg_notify('new_post',
        '{"ready_at":"' || v_ready_at::TEXT || '","post_id":"' || v_post_id::TEXT || '"}');
END;
```

```sql
-- V25 lines 96-102 (reject_quarantine body):
    UPDATE quarantine
       SET status = 'REJECTED', updated_at = now(), reviewed_by = p_actor_id
     WHERE id = p_quarantine_id;

    INSERT INTO audit_log (actor_user_id, action, target_kind, target_id)
    VALUES (p_actor_id, 'REJECT_QUARANTINE', 'quarantine', p_quarantine_id::TEXT);
END;
```

Neither stored procedure emits `pg_notify('quarantine_review', ...)`.

**Why this is wrong / suboptimal / risky:**

`docs/spec/architecture.md` §Inter-service communication defines the `quarantine_review` channel:

> `quarantine_review` — fires on quarantine state-machine transitions reachable by Provider (`PENDING` insert, `BENIGN_CLOSED`, `APPROVED`, `REJECTED`) and on a `post.status → NEEDS_REVIEW` transition

The spec then explains why all five transitions are on the channel even though only `PENDING` and `NEEDS_REVIEW` are actionable:

> `BENIGN_CLOSED`, `APPROVED`, and `REJECTED` transitions advance the Provider's cursor (so the high-water mark stays accurate) ... keeping the channel comprehensive lets v2 add behavior to a transition without a schema-level NOTIFY change.

The consumer side honors the contract: `QuarantineReviewListener.handleEvent` advances the cursor unconditionally and only fires admin notification when `isActionable` (PENDING / NEEDS_REVIEW). The producer side does not. Today:

- `BENIGN_CLOSED` is fired by `Stage2VerdictHandler` via `QuarantineNotifyEmitter`. Correct.
- `PENDING` is fired by the same emitter. Correct.
- `NEEDS_REVIEW` (post side) is fired by `ReEvaluationJob` via the same emitter. Correct.
- Admin-review TTL `REJECTED` is fired by `AdminReviewTtlJob`. Correct.
- **Admin-driven `APPROVED` and `REJECTED` are NOT fired** — the only writers are the SECURITY DEFINER stored procedures, and neither calls `pg_notify('quarantine_review', ...)`.

Practical consequences:

1. The `quarantine_review` channel's `provider_state` cursor will not advance for these transitions while the Provider is running. The cursor stays pinned at the `(reviewed_at, target_kind, target_id)` of the most recent NON-admin-driven event.
2. On Provider restart, `QuarantineReviewReconciler` will replay everything past that cursor — including admin-driven APPROVED / REJECTED transitions that fired weeks ago. The cursor will advance through them harmlessly (they are non-actionable), but the catch-up reads more rows than necessary forever.
3. The spec's stated rationale — "keeping the channel comprehensive lets v2 add behavior to a transition without a schema-level NOTIFY change" — is broken: v2 code attaching a side effect to APPROVED via the channel would silently never fire.
4. Schema-level versus application-level fidelity: today every other quarantine state-machine writer fires NOTIFY via `QuarantineNotifyEmitter`. The stored procedures are the only writer that doesn't. This is asymmetry inside the same logical surface.

**Recommended fix:**

Add the NOTIFY to both stored procedures, immediately after the `quarantine` table UPDATE and before the procedure end:

```sql
-- approve_quarantine, immediately before the new_post NOTIFY:
PERFORM pg_notify('quarantine_review',
    '{"target_kind":"quarantine","target_id":"' || p_quarantine_id::TEXT
    || '","new_status":"APPROVED"}');

-- reject_quarantine, immediately before the procedure end:
PERFORM pg_notify('quarantine_review',
    '{"target_kind":"quarantine","target_id":"' || p_quarantine_id::TEXT
    || '","new_status":"REJECTED"}');
```

Ship this as `V30__quarantine_review_notify.sql` with `CREATE OR REPLACE FUNCTION` redeclarations of both procedures (same shape as V25 superseded V21).

**Reasoning:**

The NOTIFY's only side effect on the consumer is a cursor advance (and the admin notifier, which only fires on actionable statuses — APPROVED and REJECTED are not actionable, so no spam). The same-transaction rule already applies because the stored procedures run in a single implicit transaction; the `pg_notify` commits with the UPDATE. The fix is mechanical and closes the channel-contract drift without touching consumer code.

This also makes the `QuarantineNotifyEmitter` semantically the unique "code in the codebase that knows the channel's payload format" — currently the format is duplicated in three places (two stored procedures' inline strings via `pg_notify('new_post', ...)`, the emitter, and the V21 lift of the new_post format). Treating the emitter as the lone source-of-truth in design notes is a follow-up clean-up, not part of this fix.

**Trade-offs:**

- One extra NOTIFY per admin approve / reject. Negligible cost; admin operations are rare and the consumer handles them without notification spam.
- The cursor advances faster, so the reconciler's catch-up scan after restart reads fewer rows. Strictly an improvement.

---

### F3. NOTIFY payload string-concatenation in `QuarantineNotifyEmitter` does not escape inputs

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/notify/QuarantineNotifyEmitter.java:39-43`
- **Surface:** NOTIFY

**Current code:**

```java
public void emit(@NonNull Connection conn, @NonNull String targetKind,
                 @NonNull UUID targetId, @NonNull String newStatus) throws SQLException {
    String payload = "{\"target_kind\":\"" + targetKind
        + "\",\"target_id\":\"" + targetId
        + "\",\"new_status\":\"" + newStatus + "\"}";
    try (PreparedStatement ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
        ps.setString(1, CHANNEL);
        ps.setString(2, payload);
```

Compare with `PriceSnapshotStore.store` (lines 99-100) which does escape:

```java
String payload = "{\"asset\":\"" + jsonEscape(snapshot.asset())
    + "\",\"source\":\"" + jsonEscape(snapshot.subVerb()) + "\"}";
```

**Why this is wrong / suboptimal / risky:**

The contract in `docs/spec/architecture.md` constrains `target_kind ∈ {'quarantine','post'}` and `new_status ∈ {'PENDING','BENIGN_CLOSED','APPROVED','REJECTED','NEEDS_REVIEW'}` — a closed enum on both fields. Today the emitter takes `String` and trusts the caller to pass an enum-shaped literal. Every caller in the codebase does (literals at `Stage2VerdictHandler:257,277`, `AdminReviewTtlJob:126`, `ReEvaluationJob:162`), so this is not currently exploitable. Engineering-rules §7 ("no defensive code") does not require escaping for impossible inputs.

The hazard is the SPI shape itself, not the missing escape. A future caller that needs to surface a more dynamic value (e.g., a v2 admin action that wants a verb expansion in the payload) would naturally use the emitter signature it sees and pass arbitrary text, at which point any `"` in the value tears the payload's JSON structure and crashes the consumer's regex parser. Comparing with `PriceSnapshotStore` — which DOES escape — shows the codebase is internally inconsistent on this question, and the inconsistency is itself a maintainability hazard.

A second, related concern: the `QuarantineReviewListener.parsePayload` consumer side uses regex `"\"target_kind\"\\s*:\\s*\"([^\"]+)\""`, which will mis-parse anything that contains a `"` regardless of emitter behavior. Hardening the emitter without hardening the parser would leave the round-trip brittle in the other direction. The pair should be aligned.

**Recommended fix:**

Tighten the emitter's signature to closed enums and let the type system enforce the contract; treat the emitter as the canonical owner of the channel's payload shape:

```java
public enum QuarantineNotifyKind { quarantine, post }

public enum QuarantineNotifyStatus {
    PENDING, BENIGN_CLOSED, APPROVED, REJECTED, NEEDS_REVIEW
}

public void emit(@NonNull Connection conn,
                 @NonNull QuarantineNotifyKind targetKind,
                 @NonNull UUID targetId,
                 @NonNull QuarantineNotifyStatus newStatus) throws SQLException {
    String payload = "{\"target_kind\":\"" + targetKind.name()
        + "\",\"target_id\":\"" + targetId
        + "\",\"new_status\":\"" + newStatus.name() + "\"}";
    // ... rest unchanged
}
```

Update the four call sites accordingly. The parser on the Provider side becomes more defensible because the producer mechanically cannot emit anything outside the closed set. This is the engineering-rules §7a positive: make the contract explicit in types instead of paranoia at the seam.

**Reasoning:**

The emitter is the right place to own the channel's wire format. Today it accepts a wider input type than the spec permits, so the type signature does not encode the contract; readers have to consult the spec to know which `String` values are legal. Switching to enums (a) makes the legal set self-documenting, (b) removes the asymmetry between this emitter and `PriceSnapshotStore`, (c) eliminates the future-caller hazard, and (d) does not weaken the §7 stance: the emitter is a system boundary (it produces NOTIFY payloads consumed by another connection-scoped session via a SQL string), so closed-set typing at this seam is contract enforcement, not defensive paranoia.

**Trade-offs:**

- Touches four caller sites for the type change. Mechanical.
- The Provider-side parser remains regex-based and would still mis-handle a value with `"` in it if such a value ever appeared. The enum-on-producer-side fix makes that hypothetical unreachable through this emitter; an even tighter fix replaces the regex parser with a Jackson `ObjectMapper` round-trip. The regex tightening is a follow-up, not part of this fix.

---

### F4. DAG documentation in `docs/design/09-reference.md` §9.1 disagrees with the actual sibling-module poms

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `docs/design/09-reference.md:31-38`
- **Surface:** DAG

**Current code:**

```
| Module | Depends on | Purpose |
|---|---|---|
| `infochat-core` | (none) | ... |
| `infochat-ssrf` | `infochat-core` | ... |
| `infochat-llm-adapter` | `infochat-core` | ... |
| `infochat-messaging-adapter` | `infochat-core` | ... |
```

Actual poms:

```xml
<!-- infochat-ssrf/pom.xml: no dependency on infochat-core -->
<dependencies>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

`infochat-llm-adapter/pom.xml` and `infochat-messaging-adapter/pom.xml` likewise declare no `infochat-core` dependency. Grep for `app\.zcat\.infochat\.core` under each of the three sibling modules' `src/` returns zero hits — they do not consume any infochat-core type at compile time.

**Why this is wrong / suboptimal / risky:**

The doc says the three sibling modules sit one row down from `infochat-core` in the DAG; the code says they are siblings of `infochat-core` at the top (no incoming edge). The actual shape is strictly tighter than the documented one (fewer dependencies, never a cycle risk), so this is documentation drift in the safe direction. But it confuses anyone reading the DAG to understand layering. Specifically, an engineer planning a new SPI type in `infochat-core` and expecting `infochat-ssrf` to be able to consume it would find that the dep edge they assumed does not exist; conversely, an engineer reading the DAG to verify "no sibling-to-sibling deps" gets the right answer for a wrong reason. The note at line 45 explicitly says siblings must not depend on each other; the cleanest enforcement is to say `(none)` for all three sibling modules in the table.

**Recommended fix:**

Update the table:

```
| `infochat-ssrf` | (none) | SSRF-gated outbound HTTP/WS client. ... |
| `infochat-llm-adapter` | (none) | `LlmProvider`, `EmbeddingProvider` SPIs and impls. ... |
| `infochat-messaging-adapter` | (none) | `MessagingAdapter` SPI plus v1 impls. ... |
```

And update the ASCII diagram at lines 18-29 so the three sibling modules no longer branch out of `infochat-core` — they belong at the same level as `infochat-core`.

If the project's stance is that the three sibling modules *should* depend on `infochat-core` (i.e. consume shared types in core's package), then the fix is on the code side: add the dep in each pom and use a core type from each, with the design note correct as written. As things stand, the code is correct and the doc is wrong — `infochat-core` is not on the runtime classpath of the three sibling-module jars.

**Reasoning:**

The §9.1 doc carries normative force: "This file is a reference, not a design doc. It is normative for module dependencies (the build enforces the DAG)." A normative doc that contradicts the code it documents is a hazard whenever a reader uses it to design a new module or to audit a layering question.

**Trade-offs:**

None — the fix is strictly better.

---

### F5. `AdapterRegistry` parses `infochat.adapters` CSV without duplicate-name detection

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java:130-159`
- **Surface:** capability-flag

**Current code:**

```java
public void start(String csv) {
    // Idempotent: starting twice in the same JVM (e.g. across tests
    // that exercise different csv configurations) clears the prior
    // activated set so the new activation does not double-up.
    activatedAdapters.clear();

    List<String> requested = parseAdaptersList(csv);

    // Gate 1: infochat.adapters non-empty.
    if (requested.isEmpty()) { ... }

    // Gate 2: every name resolves to a registered bean.
    Map<String, MessagingAdapter> byName = new LinkedHashMap<>();
    for (MessagingAdapter adapter : discoveredAdapters) {
        byName.put(adapter.name(), adapter);
    }
    List<MessagingAdapter> activating = new ArrayList<>();
    for (String name : requested) {
        MessagingAdapter adapter = byName.get(name);
        if (adapter == null) { throw new IllegalStateException(...); }
        activating.add(adapter);
    }
```

**Why this is wrong / suboptimal / risky:**

A misconfigured `infochat.adapters=simplex,simplex` runs through gates 2-6 with the same adapter instance appearing twice in `activating`. The wire-up loop at line 254 then runs twice:

```java
for (MessagingAdapter adapter : activating) {
    inboundRouter.setReplyTarget(adapter);
    String adapterName = adapter.name();
    adapter.setInboundHandler(msg -> inboundRouter.onMessage(msg, adapterName));
    adapter.setMembershipEventHandler(event -> membershipEventHandler.handle(event, adapterName));
    log.info("activating adapter: ...");
    activatedAdapters.add(adapter);
}
```

`setInboundHandler` is declared "replaceable" in the SPI ("Provider sets exactly one handler per adapter instance at startup; replacing a handler is undefined for v1"), so the second call clobbers the first with an identical lambda — harmless but contract-violating. `setReplyTarget` likewise gets called twice. `activatedAdapters` ends up with two references to the same instance, so the `MessagingStartup.startAllAdapters` driver may call the same lifecycle hook twice. None of this is catastrophic, but the configuration shape is meaningless and a clear error in operator intent that the registry could surface as an `IllegalStateException` for free.

**Recommended fix:**

Add a duplicate check as gate 1.5, before resolution:

```java
List<String> requested = parseAdaptersList(csv);
if (requested.isEmpty()) { throw ... }

Set<String> seen = new LinkedHashSet<>();
for (String name : requested) {
    if (!seen.add(name)) {
        throw new IllegalStateException(
            "infochat.adapters: duplicate adapter name \"" + name
            + "\" (value=\"" + csv + "\")");
    }
}
```

**Reasoning:**

Closing the duplicate path at the registry rather than at config-parse time keeps the validation co-located with the other six gates and named in §6.7's error vocabulary. The operator gets the same fail-fast IllegalStateException shape as gates 2-6. The fix is six lines and a test.

**Trade-offs:**

None — the fix is strictly better.

---
