---
id: M1-692
title: "Pin the NULL-ordering property at the three uncovered sort sites"
status: done
created: 2026-07-25
last_updated: 2026-07-25
blocked_by:
  - M1-689
files_budget: 2
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The sort key itself. COALESCE(published_at, fetched_at) DESC, id DESC
    is delivered and correct at all four sites today — M1-689's round-5
    red-team verified that against the shipped bytes. This ticket adds
    regression coverage only; it changes no production SQL and no
    production Java.
  - >-
    SearchPostsToolTest. Its
    undatedPostSortsByFetchCeilingNotByPromotionInstant already pins the
    property for searchPosts, and pins it well — the fixture is built so
    ready_at and fetched_at DISAGREE, so it also covers M1-689's round-2
    finding that ready_at is the wrong fallback. It is the model to copy,
    not a file to touch.
  - >-
    The fallback-column choice (fetched_at vs ready_at) as a design
    question. Settled by M1-689 rounds 2 and 3: fetched_at is the
    immutable partition key and is exactly the ceiling the ingest clamp
    imposes on dated rows, whereas ready_at is stamped after fetch and
    re-stamped by approve_quarantine and re-evaluation.
  - >-
    Widening ChatToolAllowlistSpecParityTest to cover the Notes column of
    the security.md tool allowlist. That is a separate structural gap
    raised by M1-689's round-1 medium and carried through round 5; it is
    not an ordering test.
acceptance:
  - >-
    EligiblePostQuery's main window query is covered by a test seeding a
    NULL-published_at post alongside dated posts, asserting the undated
    post does NOT sort ahead of them. The existing
    deterministicOrderingByPublishedAtThenIdDesc seeds dated posts only,
    so the NULL leg is currently unasserted.
  - >-
    Both DigestPostCollector SQLs (POSTS_ALL_SQL and POSTS_EXPLICIT_SQL)
    are covered the same way. DigestPostCollectorIT's existing
    nullPublishedAtPostIsCollected asserts collection and null-survival
    but returns a SINGLE row, so it asserts no ordering at all, and the
    explicit-tag SQL has no ordering test whatsoever.
  - >-
    Each new test makes ready_at and fetched_at DISAGREE for the undated
    row — seeding fetched_at explicitly rather than letting it default —
    so the test would fail if the fallback regressed to ready_at, not
    only if it regressed to a bare published_at. This is what makes
    SearchPostsToolTest's version strong, and copying the shape is the
    point of the ticket.
  - >-
    Fixture instants stay inside the same monthly partition (post is
    partitioned on fetched_at), matching how M1-689's fixtures handle it.
  - mvn verify from the repo root is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/schema.md §Post
decision_refs:
  - D19
  - D21
reviews:
  - round: 1
    date: 2026-07-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 177
      removed: 8
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-25
    verdict: CLEAN
    base: 668864f7a0b1c0ee7f1eabe84c3931eabb90b16d
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-692-2026-07-25.md
    out_of_model_count: 0
    note: |
      Pre-review redteam gate (/m1-tick run step 4). Audited the uncommitted
      working-tree diff vs fork point 668864f7. Test-only diff (three NULL-
      ordering regression tests); no production SQL/Java changed, no security
      surface touched. CLEAN.
clarity_check:
  date: 2026-07-25
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-692: Pin the NULL-ordering property at the three uncovered sort sites

## Context

M1-689 moved the post-retrieval window predicate from source-supplied
`published_at` to pipeline-set `ready_at`, which made NULL-`published_at`
posts reachable by every window-bounded surface for the first time. Round 1
of that ticket's red-team found the consequence: with the sort key left as a
bare `published_at DESC`, Postgres sorts NULLs **first** under `DESC`, so an
undated post took the head of every LLM-fed result set — the exact
head-of-ordering position `docs/spec/schema.md` §"`published_at` clamp"
documents the ingest clamp as existing to deny a source. Omitting
`<pubDate>` was strictly easier than the future-dating the clamp already
refuses.

M1-689 remediated it by keying all four sites on
`COALESCE(published_at, fetched_at) DESC, id DESC`. Its round-5 audit
verified the property is delivered at all four sites against the shipped
bytes. **Nothing is broken.** What that audit also observed — recorded as an
out-of-model durability note rather than a finding — is that the property is
regression-pinned at only **one** of the four:

| Site | NULL-ordering coverage |
|---|---|
| `SearchPostsTool` | `SearchPostsToolTest.undatedPostSortsByFetchCeilingNotByPromotionInstant` — covered, and strongly |
| `EligiblePostQuery` (main window) | none — `EligiblePostQueryIT.deterministicOrderingByPublishedAtThenIdDesc` seeds dated posts only |
| `DigestPostCollector.POSTS_ALL_SQL` | none — `nullPublishedAtPostIsCollected` returns one row, so it asserts no order |
| `DigestPostCollector.POSTS_EXPLICIT_SQL` | none at all |

So a future change reverting `EligiblePostQuery.java:277` or
`DigestPostCollector.java:151`/`:173` to a bare `published_at DESC` would
reintroduce a **high**-severity finding and ship green.

## Why this is a separate ticket

M1-689's acceptance item reads "A test pins the ordering property directly",
singular, and that item is satisfied. Widening it to the other three sites
does not trace to that ticket's acceptance criteria, and M1-689 had already
taken seven refines by the time round 5 surfaced this. `CLAUDE.md`
§"Better alternatives surface as proposals, not scope expansion" directs the
widening here rather than into an eighth refine.

## Notes

- **Copy `SearchPostsToolTest.undatedPostSortsByFetchCeilingNotByPromotionInstant`.**
  Its value is that the undated row's `fetched_at` and `ready_at` are seeded
  to *disagree* (fetched earlier, promoted later), so it discriminates
  between the two candidate fallbacks rather than merely proving "not NULLs
  first". A test that lets `fetched_at` default to `now()` would pass under
  either fallback and would not have caught M1-689's round-2 finding.
- **`fetched_at` is the partition key**, so fixture instants must stay inside
  the partition the surrounding fixtures use. M1-689's fixtures keep both
  values inside the May 2026 partition; follow the same constraint.
- The existing `DigestPostCollectorIT.nullPublishedAtPostIsCollected` and
  `EligiblePostQueryIT.deterministicOrderingByPublishedAtThenIdDesc` are
  **not** to be weakened or repurposed — they assert different properties
  (reachability and dated-row determinism respectively). Add cases alongside
  them.
- Adjacent code: the four `ORDER BY COALESCE(p.published_at, p.fetched_at)
  DESC, p.id DESC` clauses, and the WHY comment at
  `EligiblePostQuery.java:262-276` that carries the fallback rationale.
