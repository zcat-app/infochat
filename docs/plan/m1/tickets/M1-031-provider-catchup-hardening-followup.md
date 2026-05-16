---
id: M1-031
title: Provider catch-up hardening followup (3 M1-030 OUT-OF-MODEL advisories)
status: deferred
created: 2026-05-16
last_updated: 2026-05-16
deferred_reason: post-mvp-hardening
deferred_on: []
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostHandler.java
  - infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostListener.java
  - infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostReconciler.java
  - infochat-provider/src/main/java/io/infochat/provider/outbox/ProviderStateDao.java
  - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostHandlerHardeningIT.java
  - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostReconcilerPagingIT.java
  - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostListenerReconnectIT.java
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to V9__provider_state.sql or the provider_state schema
    (the row shape is correct; this ticket changes only the
    code that reads/writes it)
  - any modification to ProviderStateDaoIT, NewPostReconcilerIT, or
    NewPostListenerIT — those pin the M1-027 + M1-030 correctness
    contracts verbatim; the hardening here lands as additions to the
    three NEW IT files M1-030 added (NewPostHandlerHardeningIT,
    NewPostReconcilerPagingIT, NewPostListenerReconnectIT) plus
    surgical production changes
  - any spec or design doc edit (the threat model's "DB is internal"
    carve-out stands; this ticket extends defense-in-depth inside that
    carve-out, it does not amend the carve-out itself — a v2 spec
    amendment to formally extend the threat model is the right framing
    for that work, not this ticket)
  - any infochat-collector or infochat-core module change
  - any Stage 1/2 / tagger / embedding-worker / messaging-adapter /
    CommandRouter work
  - widening the test-helper DELETE pattern to other test classes
    (advisory #3 is scoped to the three M1-030-authored IT classes)
acceptance:
  - "NewPostHandler.handle merges the existence check and the cursor advance into a single atomic Postgres operation — either (a) a single SQL statement combining `UPDATE provider_state SET cursor_post_id=?, cursor_ready_at=? WHERE id=1 AND (cursor_ready_at, cursor_post_id) < (?, ?) AND EXISTS (SELECT 1 FROM post WHERE id=? AND ready_at=? AND status='READY')` (preferred), or (b) the existing existence-check SELECT and the advanceCursor UPDATE on the SAME `java.sql.Connection` instance without an intervening close — grep -E 'try\\s*\\(\\s*Connection\\s+conn\\s*=\\s*dataSource\\.getConnection\\(\\)' infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostHandler.java returns AT MOST one match (the existence-check Connection is no longer separately acquired then closed before advanceCursor runs)"
  - "NewPostHandlerHardeningIT adds one @Test asserting atomicity: after the existence-check returns a row, a concurrent `DELETE FROM post WHERE id = ?` running in a separate transaction does NOT cause the cursor to advance — the test seeds a READY row, opens a sibling JDBC connection, begins a transaction that DELETEs the row, calls handle() in the main thread, then commits the DELETE; asserts handle() either returned false OR the cursor still matches the row's (ready_at, id). grep -E '@Test' NewPostHandlerHardeningIT.java returns at least four matches (the original three plus the new atomicity test)"
  - "NewPostListener invokes NewPostReconciler.runCatchUp (or an equivalent catch-up entry point exposed as a package-private method on NewPostReconciler) after every successful reconnect — i.e. immediately after openListenConnection succeeds inside the SQLException catch branch and BEFORE the next getNotifications poll resumes. grep -E '(runCatchUp|catchUp)' NewPostListener.java returns at least one match inside or adjacent to the runLoop method's reconnect path"
  - "NewPostListenerReconnectIT adds one @Test asserting catch-up-after-reconnect: with the listener running, INSERT a READY post via a sibling JDBC connection WITHOUT emitting a NOTIFY, force-close the listener's underlying connection via closeListenConnectionForTest(), then assert that within a 30s Awaitility window the cursor has advanced to the inserted row's (ready_at, id). The catch-up scan triggered by the reconnect must be the only path that could have advanced the cursor (no NOTIFY was emitted). grep -E '@Test' NewPostListenerReconnectIT.java returns at least two matches (the original one plus the new catch-up-after-reconnect test)"
  - "NewPostReconciler exposes its catch-up entry point as a package-private method (e.g. `runCatchUp()` already exists from M1-030; keep it package-private and idempotent so the listener can call it without competing with the @Startup invocation). grep -E '\\srunCatchUp\\s*\\(' NewPostReconciler.java returns at least one match AND the method is NOT declared `public`"
  - "clearAllItPosts in NewPostHandlerHardeningIT, NewPostReconcilerPagingIT, and NewPostListenerReconnectIT narrows its WHERE clause to a test-class-specific uid prefix (e.g. NewPostHandlerHardeningIT uses `uid LIKE 'hardening-it/%'`, NewPostReconcilerPagingIT uses `uid LIKE 'paging-it/%'`, NewPostListenerReconnectIT uses `uid LIKE 'reconnect-it/%'`). grep -E 'DELETE\\s+FROM\\s+post\\s+WHERE\\s+uid\\s+LIKE\\s+''[a-z-]+-it/' returns at least three matches across the three files AND grep -E '%-it/' across the three files returns ZERO matches (the broad pattern is fully replaced)"
  - "mvn -B -pl infochat-provider -am verify exits 0; failsafe reports show NewPostHandlerHardeningIT (≥4 tests), NewPostReconcilerPagingIT (≥4 tests), NewPostListenerReconnectIT (≥2 tests) all passing"
  - "mvn -B clean verify from the repo root exits 0; ProviderStateDaoIT, NewPostReconcilerIT, and NewPostListenerIT (the three M1-027 + M1-030 contracts) continue to pass unchanged"
test_plan:
  modifies:
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostHandlerHardeningIT.java (M1-030 — add atomicity @Test; narrow clearAllItPosts WHERE clause)
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostReconcilerPagingIT.java (M1-030 — narrow clearAllItPosts WHERE clause)
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostListenerReconnectIT.java (M1-030 — add catch-up-after-reconnect @Test; narrow clearAllItPosts WHERE clause)
  preserves:
    - infochat-provider/src/test/java/io/infochat/provider/outbox/ProviderStateDaoIT.java (M1-027)
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostReconcilerIT.java (M1-027)
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostListenerIT.java (M1-027 + M1-030)
    - all prior M1 tests
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/security.md §Trust boundaries
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: ""
  verdict: ""
  warnings: []
  blockers: []
---

# M1-031: Provider catch-up hardening followup (3 M1-030 OUT-OF-MODEL advisories)

## Context

`/redteam M1-030 --in-progress` (audit archive
[docs/plan/m1/redteam/M1-030-2026-05-16.md](../redteam/M1-030-2026-05-16.md))
returned **CLEAN** against the threat model but recorded three
OUT-OF-MODEL advisories — defense-in-depth gaps inside the
DB-internal trust zone the threat model carves out ("The DB is
internal — only the two services and the operator reach it"). This
ticket bundles all three into a follow-on hardening item, mirroring
the M1-030 pattern (which itself bundled three M1-027 redteam
advisories).

**Deferred at authoring time.** Same back-of-queue framing as M1-030:
these are not in-model security findings, none of them bite in
steady state today (the ingest pipeline is not yet producing READY
posts), and none of them block T1-D/E/F critical-path work. The
ticket sits under `deferred_reason: post-mvp-hardening` alongside
M1-019 / M1-020 so it surfaces in STATUS.md as a known follow-up
rather than rotting in transcript history. If M1 has slack before
v1 tag, run `/m1-tick reopen M1-031` and pick it up; otherwise it
carries forward to M2 with the same `deferred_reason`.

The three advisories, in order of acceptance items:

1. **Existence-check ↔ cursor-advance atomicity** (M1-030 audit
   advisory #1). `NewPostHandler.handle()` opens a separate
   `try-with-resources` `Connection` for the existence-check SELECT
   inside the `@Transactional` method; that `Connection` closes
   before `ProviderStateDao.advanceCursor` runs on its own
   connection. Even with Agroal JTA enrollment, the SELECT's
   snapshot is not preserved past the close, so the "same
   transactional boundary" javadoc claim is weaker than stated. The
   conservative fix is a single combined `UPDATE … WHERE …
   EXISTS (SELECT 1 FROM post …)` statement that performs the
   existence check and the cursor advance atomically — no
   between-statement window for a concurrent DELETE on `post` to
   sneak through.

2. **Missed NOTIFYs after listener reconnect** (M1-030 audit
   advisory #3). Postgres `LISTEN/NOTIFY` does not buffer for
   absent sessions; the catch-up reconciler currently only runs at
   `@Startup`. A post made READY during a Postgres restart (or any
   transient listener disconnect long enough to lose NOTIFYs) stays
   user-invisible until the next Provider restart. The fix is to
   invoke `NewPostReconciler.runCatchUp` after every successful
   reconnect inside the listener's SQLException catch branch — the
   same code path that already re-acquires the connection and
   re-issues `LISTEN new_post`.

3. **Broad test-helper DELETE** (M1-030 audit advisory #2). The
   three new IT classes' `clearAllItPosts()` helpers issue
   `DELETE FROM post WHERE uid LIKE '%-it/%'`. Test-only under
   DevServices, but the pattern is footgun-shaped — narrow each
   helper to a test-class-specific uid prefix so the pattern is
   non-portable to prod tooling.

## Definition of Done

- `NewPostHandler.handle()` atomically checks post existence AND
  advances the cursor in a single Postgres operation (preferred:
  combined `UPDATE … WHERE … EXISTS (…)` statement). The separate
  try-with-resources Connection for the existence-check SELECT is
  gone.
- `NewPostListener` invokes `NewPostReconciler.runCatchUp` after
  every successful reconnect inside the `SQLException` catch
  branch, before the next `getNotifications` poll resumes.
- `clearAllItPosts` in the three M1-030-authored IT classes uses a
  test-class-specific uid prefix instead of the broad `%-it/%`
  pattern.
- One new `@Test` in `NewPostHandlerHardeningIT` asserts the
  atomicity guarantee (concurrent DELETE between
  existence-check-time and advance-time does not cause an erroneous
  advance).
- One new `@Test` in `NewPostListenerReconnectIT` asserts
  catch-up-after-reconnect (a READY row inserted without a NOTIFY
  is picked up after the listener reconnects).
- ProviderStateDaoIT, NewPostReconcilerIT, and NewPostListenerIT
  continue to pass unchanged.
- `mvn -B clean verify` exits 0 from the repo root.

## Implementation notes

- **Advisory #1.** The preferred shape is a single combined SQL
  statement in `ProviderStateDao` (or wherever the existing
  `advanceCursor` lives — extend the method or add
  `advanceCursorIfPostReady(postId, readyAt)`):
  ```sql
  UPDATE provider_state
  SET cursor_post_id = ?, cursor_ready_at = ?, updated_at = NOW()
  WHERE id = 1
    AND (cursor_ready_at, cursor_post_id) < (?, ?)
    AND EXISTS (
      SELECT 1 FROM post
      WHERE id = ? AND ready_at = ? AND status = 'READY'
    )
  RETURNING 1
  ```
  `RETURNING 1` so the DAO knows whether the row was updated (CAS
  miss vs existence miss are both "did not update"; the handler
  treats both as "do not log success" and the existence-vs-CAS
  distinction is a LOG.warn / LOG.debug taste choice — both safe
  outcomes). `NewPostHandler.handle` becomes:
  ```java
  @Transactional
  public boolean handle(UUID postId, OffsetDateTime readyAt) {
      return providerStateDao.advanceCursorIfPostReady(postId, readyAt);
  }
  ```
  The existing `advanceCursor(postId, readyAt)` method may be kept
  (used by the reconciler's catch-up path where every row read
  from `post WHERE status='READY'` already proves existence) OR
  collapsed into the new method with a no-op `EXISTS` clause —
  implementer's call. The simplification is fine as long as
  ProviderStateDaoIT keeps passing.

- **Advisory #2.** After `openListenConnection()` succeeds inside
  the catch branch (the existing reconnect path from M1-030), call
  `reconciler.runCatchUp()` once before resuming
  `getNotifications`. `runCatchUp` is already idempotent (paged
  scan, CAS-only advance, exits on partial page) so a redundant
  invocation during a benign reconnect is a no-op. The catch-up
  scan reads from the cursor's current position; if no missed
  NOTIFYs occurred during the disconnect window, it reads zero
  rows and returns immediately.

- **Advisory #3.** Each IT class picks a stable uid prefix:
  - `NewPostHandlerHardeningIT` → `hardening-it/`
  - `NewPostReconcilerPagingIT` → `paging-it/`
  - `NewPostListenerReconnectIT` → `reconnect-it/`
  Every seeded `post.uid` carries the class's prefix; every
  `clearAllItPosts` helper uses `WHERE uid LIKE '<prefix>%'` (NOT
  `%<suffix>`). The IT seed helpers' formatting strings change
  in lockstep with the DELETE narrowing.

## Big-picture notes

- The threat model's DB-internal carve-out is deliberate, not an
  oversight (mirrors the M1-030 framing). This ticket closes
  additional defense-in-depth gaps inside that carve-out without
  amending the threat model. A future v2 spec amendment could
  formally extend `docs/spec/security.md` §Trust boundaries to
  include "the catch-up cursor advances only against a present
  READY row in the same DB transaction" and "the listener catches
  up after every reconnect" — but that's a spec decision, not a
  precondition for this ticket.
- The three fixes are composable and independently shippable. If
  M1 has very limited slack, the ticket could be decomposed (run
  `/m1-tick escalate M1-031 decompose` at start-time) into
  M1-031a (advisory #1 — atomicity), M1-031b (advisory #2 —
  catch-up after reconnect), M1-031c (advisory #3 — test helper).
  At authoring time the bundle stays together for the same
  reason M1-030's bundle did: shared origin, shared module,
  shared trust zone.

## Out-of-scope expansion

- **Schema changes.** V9 is final. The defenses here are
  code-only.
- **M1-027 IT classes.** ProviderStateDaoIT, NewPostReconcilerIT,
  and NewPostListenerIT pin the M1-027 + M1-030 contracts verbatim
  and stay untouched. The atomicity assertion and the
  catch-up-after-reconnect assertion land in the M1-030-authored
  IT files (NewPostHandlerHardeningIT, NewPostListenerReconnectIT)
  alongside the original M1-030 assertions.
- **Threat-model amendment.** Out-of-scope for this ticket;
  optional follow-up at M2-open.
- **Other modules.** infochat-collector, infochat-core,
  infochat-ssrf are untouched.
- **Test-helper DELETE narrowing scope.** Only the three
  M1-030-authored IT classes are in scope for advisory #3. Other
  test classes in the codebase using `DELETE FROM post WHERE …`
  are NOT touched — those were authored under different
  conventions and a sweep would expand the ticket's blast radius
  without clear benefit.

## Alternatives considered

- **Three separate tickets, one per advisory.** Rejected for the
  same reason M1-030's bundle was: shared origin (M1-030 redteam
  audit), shared module (infochat-provider/.../outbox), shared
  back-of-queue priority. Decomposition remains available at
  start-time via `/m1-tick escalate M1-031 decompose` if M1 has
  unusually tight slack.
- **Address advisory #2 separately and sooner.** Considered. It
  is the only one of the three that is operationally visible (a
  real Postgres restart would expose it). Rejected at authoring
  time because the ingest pipeline (T1-D) is not yet producing
  READY posts, so there is no live workload exposed to the
  missed-NOTIFY window. If T1-D lands first and a Postgres
  restart becomes a realistic operational event before M1 tag,
  reopen M1-031 and either decompose or pull the whole bundle
  forward.
- **Accept advisory #1 as residual risk.** Considered. The TOCTOU
  window between the existence-check SELECT and the advance UPDATE
  is microseconds wide and the threat model has no in-model
  adversary that could exploit it (the Collector is the only
  writer; an operator-side DELETE on `post` is out-of-model).
  Rejected because the combined-statement fix is trivial (one SQL
  statement) and removes a category of confusion ("does Agroal
  enroll? does the snapshot survive close?") rather than relying
  on a subtle JTA invariant. Defense-in-depth at near-zero cost.
- **`status: pending` with a body note instead of `status:
  deferred`.** Considered. The framing question is "is this
  blocked or just low priority?" — pure low-priority. The
  M1-030 ticket itself was authored `pending` for the same
  reason. The difference here: M1-030 was the FIRST hardening
  bundle and benefited from STATUS.md surfacing it as a runnable
  candidate; M1-031 is the SECOND hardening bundle following a
  CLEAN audit, with no in-model finding driving urgency, so the
  honest default is `deferred` from the start. `/m1-tick reopen
  M1-031` is the explicit "we have slack, pick this up" signal.
