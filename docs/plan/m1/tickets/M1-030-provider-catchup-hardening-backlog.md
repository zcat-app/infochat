---
id: M1-030
title: Provider catch-up hardening backlog (3 redteam OUT-OF-MODEL advisories)
status: pending
created: 2026-05-15
last_updated: 2026-05-15
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostHandler.java
  - infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostListener.java
  - infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostReconciler.java
  - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostHandlerHardeningIT.java
  - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostListenerReconnectIT.java
  - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostReconcilerPagingIT.java
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to V9__provider_state.sql or the provider_state schema
    (the cursor row's shape is correct; this ticket changes only the
    code that reads/writes it)
  - any change to ProviderStateDao.java (the CAS update is correct;
    the gap is upstream of the DAO — in the handler's payload-validation
    and in the catch-up scan's paging)
  - any modification to the three M1-027 IT classes (ProviderStateDaoIT,
    NewPostReconcilerIT, NewPostListenerIT) — those pin the M1-027
    correctness contract verbatim; hardening assertions land in new
    sibling IT files so the M1-027 contract is not mutated
  - any spec or design doc edit (the threat model's "DB is internal —
    only the two services and the operator reach it" carve-out stands;
    this ticket adds defense-in-depth inside that carve-out, it does
    not amend the carve-out itself)
  - any infochat-collector or infochat-core module change
  - any Stage 1/2 / tagger / embedding-worker / messaging-adapter /
    CommandRouter work (out of T1-C scope)
acceptance:
  - "NewPostHandler.handle (infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostHandler.java:80-82) verifies that a `post` row exists with id=payload.post_id AND ready_at=payload.ready_at AND status='READY' BEFORE invoking ProviderStateDao.advanceCursor. If no such row exists, the handler logs at WARN, does NOT advance the cursor, and returns false — grep -E 'SELECT\\s+1\\s+FROM\\s+post|SELECT\\s+id\\s+FROM\\s+post' NewPostHandler.java returns at least one match AND the existence-check runs INSIDE the @Transactional method before the advanceCursor call"
  - "NewPostHandlerHardeningIT.java is a new @QuarkusTest IT asserting: (a) handle(non-existent UUID, future-ready_at) returns false and the cursor does NOT advance; (b) handle(existing post id, mismatched ready_at) returns false and the cursor does NOT advance; (c) handle(existing post id, matching ready_at, status=READY) advances the cursor as before — grep -E '@Test' NewPostHandlerHardeningIT.java returns at least three matches"
  - "NewPostReconciler.runCatchUp (infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostReconciler.java:102) pages the catch-up scan in batches of N rows (where N is a configurable property `infochat.provider.catchup.page-size` defaulting to 500), advancing the cursor incrementally between pages so a long-outage backlog does not block @Startup arbitrarily — grep -E 'LIMIT\\s*\\?|LIMIT\\s+\\$\\{|LIMIT\\s+\\d' NewPostReconciler.java returns at least one match AND the SELECT runs inside a loop that exits when fewer than page-size rows are returned"
  - "NewPostReconcilerPagingIT.java is a new @QuarkusTest IT seeding > page-size READY rows (e.g. 1200 rows with page-size=500) and asserting: (a) all 1200 rows are processed in exactly (ready_at, id) order; (b) the final cursor matches the last row's (ready_at, id); (c) re-running the reconciler with cursor at the last row is a no-op (zero handler invocations); (d) the cursor advances between pages (read the cursor mid-scan via a test hook OR assert the handler's call count crosses page boundaries) — grep -E '@Test' NewPostReconcilerPagingIT.java returns at least three matches"
  - "NewPostListener.runLoop (infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostListener.java:142-148) detects a closed/severed listenConnection in the SQLException catch branch and re-acquires the connection via dataSource.getConnection() + re-issues `LISTEN new_post` before continuing the loop. A bounded retry budget (e.g. exponential backoff up to 30s) prevents tight-loop reconnect on persistent failure — grep -E 'isClosed\\(\\)|isValid\\(|dataSource\\.getConnection|LISTEN\\s+new_post' NewPostListener.java returns at least two matches inside or adjacent to the runLoop method"
  - "NewPostListenerReconnectIT.java is a new @QuarkusTest IT that: (a) starts the listener; (b) force-closes the listener's underlying connection (or restarts the DevServices Postgres container if accessible from the test harness; if not, simulate via a wrapper DataSource that vends a closeable proxy); (c) emits a fresh NOTIFY via a separate JDBC connection AFTER the listener has had time to re-establish; (d) asserts the listener received and dispatched the post-reconnect notification within a 30s Awaitility window — grep -E '@Test' NewPostListenerReconnectIT.java returns at least one match"
  - "mvn -B -pl infochat-provider -am verify exits 0; failsafe reports show NewPostHandlerHardeningIT, NewPostReconcilerPagingIT, and NewPostListenerReconnectIT all executed (grep -rE 'Tests run: [1-9]' infochat-provider/target/failsafe-reports returns matches for each)"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-027 ITs (ProviderStateDaoIT, NewPostReconcilerIT, NewPostListenerIT) continue to pass unchanged"
test_plan:
  adds:
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostHandlerHardeningIT.java
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostReconcilerPagingIT.java
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostListenerReconnectIT.java
  preserves:
    - infochat-provider/src/test/java/io/infochat/provider/outbox/ProviderStateDaoIT.java (M1-027)
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostReconcilerIT.java (M1-027)
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostListenerIT.java (M1-027)
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
clarity_check: {}
---

# M1-030: Provider catch-up hardening backlog (3 redteam OUT-OF-MODEL advisories)

## Context

`/redteam M1-027` (round 1, 2026-05-15) returned **CLEAN** against
the threat model but surfaced three OUT-OF-MODEL advisories — gaps
that are out-of-scope of the documented threat model ("The DB is
internal — only the two services and the operator reach it") but
worth closing as defense-in-depth. This ticket bundles all three
into a single hardening backlog item so the findings stay visible
in STATUS.md without losing them to transcript decay.

**Back of the M1 queue.** This ticket should NOT compete with
remaining M1 implementation work for "next runnable" priority. The
intended ordering is: complete the rest of the M1 scope (T1-D
ingest pipeline, T1-E messaging adapters, T1-F first commands,
etc.); then, if the milestone has slack before tagging v1, pick up
this ticket. If not, defer it to M2 by setting `status: deferred`
with `deferred_reason: spec-amend` at milestone-close time (the
spec would then need to formally extend the threat model to
include DB-side adversaries, which is the right framing for these
defenses).

The three advisories, in order of acceptance items:

1. **Cursor poisoning via direct NOTIFY** (advisory #1, from
   redteam transcript 2026-05-15). The handler at
   `NewPostHandler.java:80-82` advances the cursor on any
   well-formed payload without verifying a corresponding `post`
   row exists. A poisoned NOTIFY with `ready_at` far in the future
   would cause the next reconciler pass to skip every real READY
   post. Currently out-of-model (requires DB-level access) but
   one row-existence check at the handler boundary closes the
   path entirely.

2. **Reconciler unbounded scan at startup** (advisory #2). The
   catch-up scan at `NewPostReconciler.java:102` does one
   un-paged SELECT and iterates all rows serially inside
   `@Startup @Priority(250)`. A long-outage backlog of millions
   of READY rows would delay startup arbitrarily. Paging the
   scan and advancing the cursor between pages bounds the work
   per page and lets other startup beans proceed if the catch-up
   genuinely takes minutes.

3. **Listener connection-loss silent stall** (advisory #3). The
   listener's SQLException catch at `NewPostListener.java:142-148`
   logs and `continue`s without re-acquiring the connection or
   re-issuing `LISTEN new_post`. After a Postgres restart or
   network blip the listener stays "alive" but receives no
   further notifications until the next process restart. The
   reconciler-on-restart catches up, so no posts are permanently
   lost — but live-NOTIFY availability silently degrades. A
   `isClosed`/`isValid` check + reconnect + re-LISTEN in the
   catch branch restores liveness.

## Definition of Done

- Handler verifies a real `post` row exists at the supplied
  `(post_id, ready_at)` with `status='READY'` before advancing
  the cursor. Mismatch returns false, logs at WARN, no advance.
- Catch-up scan is paged via a configurable page-size property
  (default 500); cursor advances between pages.
- Listener's runLoop reconnects on connection loss with bounded
  exponential backoff and re-issues `LISTEN new_post`.
- One new @QuarkusTest IT per advisory, covering the
  happy/sad/edge paths.
- All M1-027 ITs continue to pass unchanged.
- `mvn -B clean verify` exits 0 from the repo root.

## Implementation notes

- **Advisory #1.** The existence-check is a single SELECT inside
  the handler's `@Transactional` method, BEFORE the
  `advanceCursor` call. The check + advance share the same
  transaction so the row cannot disappear between check and
  advance. Suggested shape:
  ```java
  try (PreparedStatement ps = conn.prepareStatement(
      "SELECT 1 FROM post WHERE id = ? AND ready_at = ? AND status = 'READY'")) {
      ps.setObject(1, postId);
      ps.setObject(2, readyAt);
      try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
              LOG.warnf("payload (post_id=%s, ready_at=%s) does not match a READY row; not advancing", postId, readyAt);
              return false;
          }
      }
  }
  ```
- **Advisory #2.** Add `LIMIT ?` to the catch-up SQL, loop until
  fewer than `pageSize` rows return. Read the cursor at the start
  of each page so a concurrent NOTIFY mid-catch-up sees consistent
  state. Suggested property:
  `infochat.provider.catchup.page-size=500` in `application.properties`.
- **Advisory #3.** In the `SQLException` catch branch, check
  `listenConnection.isClosed()`; if closed, call `dataSource.getConnection()`,
  re-issue `LISTEN new_post`, swap the reference. Use bounded
  exponential backoff (e.g. 1s → 2s → 4s → 8s → 16s, cap at 30s)
  to avoid a tight reconnect loop on persistent failure. Reset
  the backoff after a successful `getNotifications` call.
- All three IT classes use Quarkus DevServices Postgres per the
  pattern established by the three M1-027 ITs.
- The NewPostListenerReconnectIT may need a custom DataSource
  wrapper to force a connection close from the test harness if
  DevServices doesn't expose a container-restart hook within the
  test process. A pragmatic alternative: have the listener bean
  expose a package-private `forceConnectionClosedForTest()`
  method gated by an `@IfBuildProfile("test")` annotation — the
  added surface is test-only and explicit, not a backdoor.

## Big-picture notes

- The threat model's DB-internal carve-out is deliberate, not an
  oversight. This ticket closes the gaps anyway because all three
  are cheap to fix and reduce blast radius if the DB-internal
  assumption is ever weakened (e.g., a future ticket that adds a
  third service with `LISTEN/NOTIFY` access; an operator psql
  session running an unintended pg_notify; a Postgres-level
  exploit that lets an attacker emit NOTIFY without write access
  to `post`).
- The hardening here is composable: advisory #1's existence-check
  protects against poisoned NOTIFY in the live path; advisory #2's
  paging keeps startup responsive when the backlog is real;
  advisory #3's reconnect keeps live-NOTIFY responsive across
  Postgres blips. Each is independently shippable.
- A future spec amendment could extend `docs/spec/security.md`
  §Trust boundaries to explicitly include "the cursor's read
  path validates against a real `post` row" — but that's a spec
  decision, not a precondition for this ticket. The ticket
  delivers the defense; the spec amendment is optional polish.

## Out-of-scope expansion

- **Schema changes.** V9 is final. The defenses here are
  pre-advance validation in the handler, paging in the
  reconciler, and reconnect in the listener — all code changes.
- **ProviderStateDao.** The CAS update is correct; the gap is
  upstream of the DAO. The DAO stays untouched.
- **M1-027 IT classes.** The three M1-027 ITs pin the M1-027
  contract verbatim. Hardening assertions land in three new IT
  files so the M1-027 contract is not mutated. (Per §Engineering
  rules: "Don't 'improve' adjacent code... Match existing style
  even if you'd write it differently.")
- **Threat-model amendment.** Out-of-scope for this ticket;
  optional follow-up after M1 close.
- **Other modules.** infochat-collector, infochat-core,
  infochat-ssrf are untouched.

## Authorized test changes

- (none — this ticket adds three NEW IT files; no pre-existing
  test is modified. If advisory #3's reconnect test genuinely
  requires a `@IfBuildProfile("test")`-gated test hook on
  NewPostListener, that hook is production code carrying a
  test-only profile annotation, not a test modification.)

## Alternatives considered

- **Three separate tickets, one per advisory.** Rejected as
  over-engineering. The three advisories share an origin (M1-027
  redteam transcript), a module (infochat-provider/.../outbox),
  and a "back-of-queue" priority. Bundling keeps the M1 ticket
  count from inflating.
- **Save the advisories as a project memory instead of a
  ticket.** Rejected. Memory doesn't surface on STATUS.md, so
  the advisories would not be visible when prioritising the M1
  finishing line.
- **`status: deferred` with `deferred_reason: spec-amend`.**
  Considered. The framing fits ("the threat model would need to
  extend before this is in-model") but flagging as `deferred` at
  authoring time misrepresents the work: it's not blocked on a
  spec amendment, it's just low-priority. `status: pending` with
  a body note is more honest. Re-evaluate at milestone close —
  if M1 ships without picking this up, transition to `deferred`
  with `deferred_reason: spec-amend` and the M2 plan picks it up.
- **Skip the existence-check (advisory #1) on grounds that DB
  access is out-of-model.** Rejected on defense-in-depth grounds.
  The check is one SELECT inside the same transaction as the
  advance; the cost is trivial and the blast-radius reduction is
  meaningful.
- **Implement advisory #3 without bounded backoff.** Rejected.
  A tight reconnect loop on persistent failure would burn CPU
  and log volume. Exponential backoff with a 30s cap is the
  conventional shape.
