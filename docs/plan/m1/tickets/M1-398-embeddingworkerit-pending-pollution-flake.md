---
id: M1-398
title: "flaky IT: EmbeddingWorkerIT.postAlreadyEmbeddedIsNotPickedUpByEnumeratePending fails when cross-class pollution leaves ≥10 pending posts in the shared DevServices DB"
status: pending
created: 2026-06-18
last_updated: 2026-06-18
blocked_by: []
files_budget: 1
files_scope:
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "EmbeddingWorker.enumeratePending production query (WHERE status='RAW' AND tagger_done=TRUE AND embedding_done=FALSE ORDER BY fetched_at, id LIMIT ?) — correct as written; the defect is the test's hidden assumption that the shared DevServices DB holds fewer than `limit` pending posts, not the SUT. Do not change production code."
  - "The cross-class cleanup of OTHER collector IT classes / any shared Postgres test base — only touch those if the chosen fix demonstrably requires it, and escalate (refine) before widening files_scope rather than editing them silently."
  - "The other five EmbeddingWorkerIT scenarios (@Order 1–4, 6) — unchanged."
acceptance:
  - "The root cause is recorded (a comment at the @Order(5) test, or the commit message) and the test `postAlreadyEmbeddedIsNotPickedUpByEnumeratePending` is made robust so it passes regardless of how many unrelated pending posts (status='RAW', tagger_done=TRUE, embedding_done=FALSE) other collector IT classes have left in the shared DevServices DB before it runs. Both existing assertions MUST be preserved with equal strength — (a) the already-embedded post is NOT returned by the pickup, and (b) a fresh tagger_done=true / embedding_done=false post IS reachable through enumeratePending. Neither assertion may be deleted, weakened, or made vacuous (test-integrity rule); e.g. raising the enumeratePending limit, or asserting on the test's own seeded posts rather than top-`limit` membership, is acceptable — silently dropping assertion (b) is not."
  - "`mvn -B verify` from the repo root exits 0. Because the failure is order-dependent (Failsafe's default run-order is not stable across runs), run `mvn -B verify` twice and confirm both exit 0 — the flake must not reappear under a different class order."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-398: EmbeddingWorkerIT pending-pollution flake

## Context

Discovered while running `mvn -B verify` for M1-393 (a docs+shell-only change
that cannot affect Java test behavior). The full suite failed on:

```
EmbeddingWorkerIT.postAlreadyEmbeddedIsNotPickedUpByEnumeratePending:236
  fresh tagger_done=true / embedding_done=false post MUST appear in pickup
  ==> expected: <true> but was: <false>
```

Reproduced 3/3 — twice on the M1-393 branch and once on a clean `main`
checkout — so it is **pre-existing on main**, not introduced by M1-393. (M1-392
landed with a green verify, so the failure is intermittent across runs, not a
permanent breakage.)

### Root cause

`EmbeddingWorkerIT` is a `@QuarkusTest` against the **shared** module-wide
DevServices Postgres. Its `@BeforeEach` cleanup is scoped to its own rows only:

```
DELETE FROM post WHERE uid LIKE 'embed-it/%'
```

The `@Order(5)` test seeds one pickup-ready post and asserts it appears in:

```
EmbeddingWorker.enumeratePending(10)
  → SELECT ... FROM post
     WHERE status='RAW' AND tagger_done=TRUE AND embedding_done=FALSE
     ORDER BY fetched_at, id
     LIMIT 10
```

Other collector IT classes that run before it (e.g. Stage-1 / Tagger ITs, and
EmbeddingWorkerIT's own `@Order(4)` dimension-mismatch scenario, which
deliberately leaves posts `embedding_done=false`) seed `RAW` posts that match
this `WHERE` clause and are **not** cleaned up by the `embed-it/%`-scoped
`@BeforeEach`. With earlier `fetched_at`, those rows sort ahead of the freshly
seeded post; once ≥10 accumulate, the `LIMIT 10` result no longer contains it
and assertion (b) fails. Whether the count crosses 10 depends on the
class execution order, which Failsafe does not fix across runs — hence the
intermittency.

The production `enumeratePending` query is correct; the test carries an
unstated "the pending queue is near-empty" assumption that the shared test DB
violates.

## Acceptance / Out-of-scope

See frontmatter.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-398-*.md
```
