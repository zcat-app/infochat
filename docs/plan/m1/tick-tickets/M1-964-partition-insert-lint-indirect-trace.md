---
id: M1-964
title: "Partition lint: catch indirect ambient key bindings"
status: pending
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction: >-
  Probe (instrument ticket — the M1-960 posture; requires M1-962's widened
  roots and M1-963's fixture family to be in place so the full-tree scan is
  otherwise green): verified at analysis time 2026-09-01 —
  `python3 scripts/lint-partitioned-test-inserts.py
  infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobTest.java`
  exits 0 ("no violations") although the file seeds post.fetched_at from
  `Instant.now()` at the call sites ReEvaluationJobTest.java:322, :341,
  :342 (seedNeedsReviewPost's `fetchedAt` METHOD PARAMETER is bound at
  :527 `ps.setTimestamp(5, Timestamp.from(fetchedAt))`) — the exact
  helper-parameter shape of the 2026-09-01 bomb
  (PerSourceUnknownTrackerTest, RED that day) and the shape the lint
  documents as out of its reach (docstring :48-53 "anything deeper (method
  parameters, fields assigned in another helper) is not [traced]").
  Second leg: `UnresolvedRepostEdgeUniqueIT.java:40-41` initializes a
  static final Timestamp from `Instant.now()` feeding the
  post_reference.created_at partition key; the lint reports nothing.
  The wrong behavior: the enforced lint (M1-962) stays green while a
  calendar-armed fixture shape lands — the class the user directed closed
  "once for all" is not closed for the shape that actually fired.
analysis_ref: docs/plan/m1/tick-analysis/partition-month-boundary-test-timebomb.md
blocked_by:
  - M1-962
  - M1-963
files_scope:
  - scripts/lint-partitioned-test-inserts.py
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobWindowTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerPickupFloorIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/UnresolvedRepostEdgeUniqueIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any production change — ReEvaluationJob and EmbeddingWorker already
    read the injected Clock (ReEvaluationJob.java:115-117;
    EmbeddingWorker.java:212-219); NostrStreamSource's scan floor likewise
    (:640-650). Only fixture timestamp SOURCING and the lint script change.
  - >-
    Cross-file trace resolution (test-support helpers in another file
    feeding a partition key) — none exists today (census grep over all six
    modules' test trees, analysis Ground truth); the trace stays
    same-file and the docstring's known-limits section states the new
    boundary precisely. Unresolvable bindings stay UNFLAGGED (no
    deny-by-default) — analysis P8: flagging unproven bindings would flood
    provider/core fixed-literal helper params.
  - >-
    Non-key ambient columns (ready_at / published_at / saved_at / digest
    slots) — D72-legal, lint-blind by design; the strengthened trace must
    never flag them (verified by the clean self-test fixture).
  - >-
    Provider/core fixture churn: the strengthened lint must return zero new
    findings there (probe below); any surprise finding is disposed in the
    commit per census discipline, not bypassed.
  - >-
    The M1-962 pom binding and roots (already landed; this ticket only
    extends the script's check_* logic and self-test fixtures).
acceptance:
  - "TRACE EXTENDED, self-tested (failure-mode fixtures): `python3 scripts/lint-partitioned-test-inserts.py --self-test` prints all cases OK, including FOUR NEW cases — (a) VIOLATING helper-parameter fixture replicating the observed bomb shape (INSERT partition key bound from a method parameter whose same-file call sites pass `Instant.now()`-derived arguments; expected ≥1 violation, kind PARTITION-KEY-AMBIENT-INDIRECT), (b) VIOLATING static-field fixture (`static final` Timestamp from `Instant.now()` bound to a partition key), (c) CLEAN helper-parameter fixture whose call sites pass fixed-literal-derived args (the AdminReviewTtlJobTest shape — 0 findings), (d) CLEAN fixture with an ambient value on a NON-key column (the provider ready_at shape — 0 findings). EVERY pre-existing self-test case stays in place and green (no weakened check; M1-651 non-vacuity — each new rule ships the fixture that fails without it)."
  - "NO-FLOOD property on the whole tree: on the pre-fix tree (M1-962/M1-963 landed), `python3 scripts/lint-partitioned-test-inserts.py` (all roots) returns EXACTLY the four disposal files' violations — ReEvaluationJobTest, ReEvaluationJobWindowTest, EmbeddingWorkerPickupFloorIT, UnresolvedRepostEdgeUniqueIT — and NOTHING in provider/core or elsewhere (probe: the run output pasted in the commit message; analysis P8)."
  - "FOUR DISPOSALS fixed with the named tests' assertions untouched (timestamp sourcing only): ReEvaluationJobTest and ReEvaluationJobWindowTest pin the Clock (ReEvaluationJob.java:115-117 reads it — the ReEvaluationJobScheduledPathIT PINNED_NOW/FETCHED_AT precedent) with in-window seeds fixed-relative to the pin and the `isBefore(Instant.now().minus(...))` fixture-validity assertions flipped pin-relative; EmbeddingWorkerPickupFloorIT pins the Clock (EmbeddingWorker.java:212-219) the same way; UnresolvedRepostEdgeUniqueIT binds CREATED_AT to a fixed bootstrap-month instant (collision semantics preserved — both INSERTs share the constant; `duplicateUnresolvedRepostEdgeIsRejected` keeps its SQLSTATE 23505 assertion) and its stale pruner-fear comment (:31-36) is rewritten to current truth (schedulers halted under %test since M1-535; analysis P4)."
  - "PIN HYGIENE (failure-mode): `grep -rn 'Instant.parse(\"2026-0'` over the four files shows ONLY bootstrap-month constants (analysis P3), and no DB `now()` remains where a pinned decision reads a column (analysis P2, §9 no-two-clock)."
  - "END-STATE calibration (analysis P9): post-fix, `python3 scripts/lint-partitioned-test-inserts.py` (all roots) reports ZERO violations — proving M1-962's and M1-963's fixtures satisfy the stricter trace (probe included in the same run as acceptance-2)."
  - "`mvn verify` from the repo root is green."
test_plan:
  adds:
    - >-
      (script-internal) self-test fixtures for the two new violation shapes
      and the two clean shapes — asserted by the `--self-test` probe in
      acceptance-1 (no new JUnit test; the lint IS the guard and its
      self-test is its test harness, per the M1-740 shape).
  modifies:
    - >-
      ReEvaluationJobTest (AUTHORIZED: Clock pinned; seedNeedsReviewPost /
      seedInfraFailurePost / seedUnknownQuarantinedPost fetched_at bindings
      become fixed pin-relative instants; the depth-count fixture-validity
      assertions (:338, :343 area) compare against the PIN-relative floor;
      depth-alert and scan-window assertions unchanged).
    - >-
      ReEvaluationJobWindowTest (AUTHORIZED: Clock pinned; the in-window
      seed (:66) becomes a fixed pin-relative instant; BELOW_FLOOR stays
      fixed; its validity assertion (:71) flips pin-relative; window
      boundary assertions unchanged).
    - >-
      EmbeddingWorkerPickupFloorIT (AUTHORIZED: Clock pinned; the in-window
      seed (:59) becomes fixed pin-relative; validity assertion (:64) flips
      pin-relative; pickup-floor assertions unchanged).
    - >-
      UnresolvedRepostEdgeUniqueIT (AUTHORIZED: CREATED_AT (:40-41) becomes
      a fixed bootstrap-month constant; the duplicate-rejection test and
      SQLSTATE assertion unchanged; the :31-36 comment rewritten to current
      truth).
  preserves:
    - >-
      Every lint check and self-test case M1-740 shipped and M1-962's roots
      widening (this ticket only ADDS trace rules + fixtures).
    - >-
      Every assertion in the four modified files; all tests currently green
      on main.
spec_refs: []
decision_refs:
  - D72
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-964: Partition lint: catch indirect ambient key bindings

## Context

The 2026-09-01 bomb (`PerSourceUnknownTrackerTest`, RED — live-verified in
the analysis) binds `post.fetched_at` from `Instant.now()` at ten call
sites through a seed-helper METHOD PARAMETER — and the D72 lint cannot see
that shape even when collector is scanned (verified empirically: the file
is absent from the lint's five collector findings). A static-field
initializer from `Instant.now()` feeding
`post_reference.created_at` (`UnresolvedRepostEdgeUniqueIT.java:40-41`)
escapes the same way. M1-962 makes the lint build-enforced over all
modules; M1-963 fixes the tracker family. Until the TRACE itself covers
the indirect shapes, the enforced gate stays green while the exact shape
that fired remains landable — the class is not closed "once for all".
This ticket extends the trace (helper-parameter call-site resolution +
static-field initializers), proves it with self-test fixtures, and
disposes the four newly-visible collector sites.

## Root cause

`scripts/lint-partitioned-test-inserts.py` traces a partition-key setter
argument to a LOCAL variable assignment, one hop (docstring :48-53:
"anything deeper (method parameters, fields assigned in another helper) is
not"). The observed bomb's `seedStage2Post(UUID, String, boolean, String,
Instant fetchedAt)` receives its instant from call sites like
`seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN",
now.minusSeconds(60))` where `now = Instant.now()` — proven ambient
propagation into the key that the heuristic skips. Same for a static field
initialized from ambient time. Both shapes are intra-file, which bounds the
trace: resolve a bare-identifier setter argument that is (a) a parameter of
the enclosing method by scanning the same file's call sites of that method
and inspecting the argument expression at that ordinal (ambient directly,
or a local assigned from ambient — the existing one-hop logic applied at
the call site), or (b) a field whose initializer contains an ambient call.

## Pitfalls

Analysis-document numbering:

- P8: Regress-or-flood — every existing self-test case stays green; each
  new rule ships its own violating fixture (M1-651 non-vacuity); only
  PROVEN ambient propagation flags (call-site arg ambient directly or via
  one local hop); unresolvable bindings stay unflagged; non-key columns
  never flag. Verified by the acceptance-2 whole-tree probe.
- P9: Family end-state calibration — M1-962's five fixes and M1-963's
  three files must already satisfy this stricter trace (they pin fixed
  instants that call-site resolution resolves to literals); acceptance-5
  proves it, so no earlier sibling's pin breaks here.
- P4: Stale pruner-fear comments (`UnresolvedRepostEdgeUniqueIT.java:31-36`)
  justify wall-clock seeding that M1-535 made unnecessary — rewrite the
  comment to current truth when touching the constant (§11).
- P2: Pinning the re-eval/embedding Clocks while seeds keep DB `now()` on
  decision columns — seeds move pin-relative wholesale.
- P3: Pins outside May–July 2026 — acceptance-4 grep.
- P12: §8 authorization — `test_plan.modifies` names the four files,
  sourcing-only.

## Approach

**Files to touch:** the five `files_scope` entries.

Order:

1. Extend `scripts/lint-partitioned-test-inserts.py`: (a) in
   `check_segment`, when the partition-key setter argument is (or
   reduces via `Timestamp.from(...)` to) a bare identifier that is a
   parameter of the enclosing method, scan the same file for call sites of
   that method and evaluate the argument expression at the parameter's
   ordinal with the existing ambient checks (direct `AMBIENT_JAVA_RE`, or
   one local-assignment hop); (b) when it is a field identifier, check the
   field's initializer for ambient calls; emit
   PARTITION-KEY-AMBIENT-INDIRECT with the call-site/field line; (c) add
   the four self-test fixtures; (d) update the docstring's known-limits
   paragraph (now: cross-file helpers and deeper chains are untraced).
2. Run the whole-tree scan (all roots): expect exactly the four disposal
   files (acceptance-2). Paste the output in the commit message.
3. Fix the four files (acceptance-3 details; ScheduledPathIT pin pattern
   for the three clock-reading components, plain fixed constant for
   UnresolvedRepostEdgeUniqueIT).
4. Full probes: `--self-test`; whole-tree scan green; repo-root
   `mvn verify`.

**Controls to preserve (§10):** the depth-alert / scan-window / pickup-floor
/ duplicate-edge assertions are byte-identical; the lint's existing checks
byte-identical; M1-689 ordering families intact.

**Pitfall→mitigation:** P8 → acceptance-1's four fixtures + acceptance-2's
exact-set probe; P9 → acceptance-5; P4 → the comment rewrite in the same
hunk; P2/P3 → acceptance-4 greps; P12 → test_plan.modifies.

## Definition of done

Mirror of `acceptance:`: the lint flags both indirect shapes (self-test
OK) without flagging fixed-literal params or non-key ambient columns; the
whole-tree scan is green post-fix having flagged exactly the four files
pre-fix; the four files are pinned with assertions untouched and pins
inside bootstrap months; repo-root `mvn verify` green.

## Verification

- P8 → FAILURE-MODE: acceptance-1's four self-test fixtures feed the
  strengthened lint hostile input — the exact bomb shape (helper-parameter
  ambient) and the static-field shape — and assert it is REJECTED, while
  the two clean fixtures assert fixed-literal params and non-key ambient
  columns are NOT flagged; acceptance-2's exact-set whole-tree probe is the
  second failure-mode leg (any EXTRA finding is a false positive → fix
  the trace, not the census; any MISSING finding means the trace missed a
  shape → fix the trace).
- P9 → acceptance-5's post-fix zero-violation run over M1-962's and
  M1-963's files (the siblings' fixtures satisfy the stricter trace).
- P4 → `git diff` over UnresolvedRepostEdgeUniqueIT shows the :31-36
  comment rewritten to current truth in the same hunk as the constant;
  `duplicateUnresolvedRepostEdgeIsRejected` stays green (23505 control).
- P2 → acceptance-4's grep: no DB `now()` where a pinned decision reads a
  column; PerSourceUnknownTrackerClockIT /
  ReEvaluationJobScheduledPathIT stay green (both-clock posture).
- P3 → acceptance-4's `grep -rn 'Instant.parse("2026-0'` shows only
  bootstrap-month constants.
- P12 → test_plan.modifies IS the §8 authorization; `git diff` review
  shows sourcing-only changes in the four named tests.
- acceptance-3 → the four named tests green in the full suite.
- acceptance-6 → `mvn verify` from the repo root green.

## Out of scope

Frontmatter `out_of_scope` carries the exclusions. Load-bearing: the trace
stays SAME-FILE (no cross-file helper exists today; the docstring states
the boundary), unresolvable bindings stay unflagged (no deny-by-default —
P8), non-key columns stay lint-blind, production code untouched, M1-962's
pom binding untouched. The four modified pre-existing tests are authorized
for timestamp sourcing only (§8); any assertion edit is unauthorized.

## Census

Class = partition-key ambient bindings the current lint cannot trace
(helper-parameter, static-field). Mechanical enumeration (re-runnable,
after M1-962/M1-963 land, BEFORE this ticket's script change):

```
# the strengthened lint IS the enumerator; at analysis time the
# grep-equivalent that finds the shape is:
grep -rn "Instant.now()" \
  infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobTest.java \
  infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobWindowTest.java \
  infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerPickupFloorIT.java \
  infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/UnresolvedRepostEdgeUniqueIT.java
```

Analysis-time census: ReEvaluationJobTest (:322, :341, :342 + its helper
bindings), ReEvaluationJobWindowTest (:66), EmbeddingWorkerPickupFloorIT
(:59), UnresolvedRepostEdgeUniqueIT (:40-41) → all FIX here. Provider/core:
zero key-column indirect ambient bindings (verified — their ambient hits
ride ready_at/published_at/saved_at, D72-legal; keys are fixed constants).
Tracker family → M1-963; lint-visible five → M1-962. All partitioned-table
INSERTs in the other three modules: none (grep verified).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-964-partition-insert-lint-indirect-trace.md
```
