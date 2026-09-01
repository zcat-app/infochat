---
id: M1-962
title: "Enforce the partition-insert lint in the Maven build"
status: done
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction: >-
  Probe (instrument ticket — the M1-960 posture; a failing TEST cannot
  express "the guard does not run"): verified at analysis time 2026-09-01 —
  `python3 scripts/lint-partitioned-test-inserts.py` (default invocation)
  exits 0 printing "PASS no ambient-time partitioned-table INSERTs in
  infochat-provider/src/test, infochat-core/src/test" (DEFAULT_ROOTS,
  scripts/lint-partitioned-test-inserts.py:71-74 — collector is NOT
  scanned), while the same script pointed at the unscanned module,
  `python3 scripts/lint-partitioned-test-inserts.py
  infochat-collector/src/test`, exits 1 with FIVE
  PARTITION-KEY-OMITTED/AMBIENT violations (ReEvaluationJobCooldownTest:160,
  ReEvaluationJobInfraFailureFanOutIT:172, SchemaHardeningIT:128,
  LinkingJobSemanticProbeIT:213, NostrSinceCursorIT:111). Second leg:
  `grep -nE "lint-partitioned" pom.xml **/pom.xml` returns nothing and no
  build/hook step invokes the script (docstring :14-15 states the
  manual-only posture) — nothing enforces the D72 class rule, which is why
  the collector gaps stayed invisible until the 2026-09-01 partition error
  (analysis: tick-analysis/partition-month-boundary-test-timebomb.md).
analysis_ref: docs/plan/m1/tick-analysis/partition-month-boundary-test-timebomb.md
blocked_by: []
files_scope:
  - scripts/lint-partitioned-test-inserts.py
  - pom.xml
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobCooldownTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobInfraFailureFanOutIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/flyway/SchemaHardeningIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/linking/LinkingJobSemanticProbeIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSinceCursorIT.java
  - docs/spec/decisions.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any production change — PartitionCreator / PartitionDdl / PartitionPruner
    and their provisioning contract (design 02-schema.md §2.4.4) stay
    byte-identical; this ticket guards the TEST-side rule only.
  - >-
    Any new Flyway migration or DEFAULT partition (Invariant 6); the five
    fixture fixes pin into the migration-provisioned bootstrap months.
  - >-
    The lint's TRACE logic — this ticket changes only DEFAULT_ROOTS (and the
    docstring lines that describe them). Extending the trace through
    helper-parameter/static-field indirection is M1-964 (blocked_by this
    ticket only via ordering, not functionally); its four disposal files are
    NOT touched here.
  - >-
    PerSourceUnknownTrackerTest / AutoDisableStopBeforeNotifyIT /
    PerSourceUnknownTrackerUpgradeIT (M1-963's family; the current lint
    cannot see them — verified — so this ticket's enforced gate is green
    without them).
  - >-
    Provider/core fixture churn: the all-roots scan is clean there at
    analysis time (verified by running the lint over all three roots); any
    violation the widened DEFAULT_ROOTS surfaces at implementation in those
    modules is disposed in the commit per the census discipline, not waved
    through.
  - >-
    Wiring the lint into /tick gates or git hooks as the PRIMARY enforcement
    (rejected: covers only ticket-flow work; the build owns it — analysis,
    Solution options A). Reviewer re-runs remain welcome and free.
acceptance:
  - "ENFORCEMENT (failure-mode): the root pom binds the lint via exec-maven-plugin (goal exec) to the `validate` phase with `<inherited>false</inherited>` — ONE execution, repo-root cwd, so the repo-relative scan roots resolve (analysis P5) — executable python3, failing the build on the script's non-zero exit (fail-closed: no `|| true`, no WARN-only mode; analysis P6). Probe: append a deliberate ambient INSERT (e.g. `INSERT INTO post (uid, source_id) VALUES ('x', gen_random_uuid())` omitting fetched_at) to a scratch test file under infochat-collector/src/test, run `mvn validate` from the repo root → BUILD FAILURE printing the violation; remove the scratch → green. The M1-445/M1-446 surefire/failsafe config in the same pom is untouched."
  - "FULL COVERAGE: DEFAULT_ROOTS becomes all six modules' test roots (infochat-core, infochat-ssrf, infochat-llm-adapter, infochat-messaging-adapter, infochat-collector, infochat-provider). Probe: `python3 scripts/lint-partitioned-test-inserts.py` (no args) scans and PASSES on the post-ticket tree, and `python3 scripts/lint-partitioned-test-inserts.py --self-test` stays green (no existing check weakened by the roots change)."
  - "FIVE FIXTURES disposed so the enforced gate lands green, assertions untouched (timestamp sourcing only): ReEvaluationJobCooldownTest and ReEvaluationJobInfraFailureFanOutIT pin the Clock (ReEvaluationJob.java:115-117 reads it) and bind fetched_at + last_reeval_at to fixed pin-relative instants (the ReEvaluationJobScheduledPathIT.java:53-58 precedent — 'pinning the clock — not relative-dating the fixture'); SchemaHardeningIT binds fetched_at to a fixed bootstrap-month constant; NostrSinceCursorIT pins the Clock (NostrStreamSource.java:640-650 reads it for the scan floor), binds the in-window fetched_at fixed-relative to the pin, keeps BELOW_FLOOR (2026-05-01) fixed, and flips its fixture-validity assertion to pin-relative; LinkingJobSemanticProbeIT names `created_at` in the post_reference INSERT bound to the file's existing fixed FETCHED_AT constant (LinkingJobSemanticProbeIT.java:44). The five named tests pass in the full suite."
  - "PIN HYGIENE (failure-mode): `grep -rn 'Instant.parse(\"2026-0'` over the five files shows ONLY bootstrap-month constants (analysis P3), and `grep -n 'now()'` over the touched seed statements returns nothing — every decision-participating column (status_changed_at / last_reeval_at) is pin-relative too, no DB clock where a pinned decision reads it (analysis P2, §9 no-two-clock)."
  - "D72 CORRECTION (engineering-rules §12; wording approved by the user at implementation): docs/spec/decisions.md row D72 is amended to (a) drop the falsified claim 'the collector module's tests are structurally immune (their container is provisioned at app boot)' — active+next provisioning does not cover the trailing month, which the 2026-09-01 defect proved — and (b) record that the lint runs inside `mvn verify` (build-enforced, fail-closed), not only author-side. Rule text in the register's existing style; history stays in the analysis document; the amendment cites this ticket per register convention. Probe: `grep -n \"structurally immune\" docs/spec/decisions.md` returns nothing."
  - "`mvn verify` from the repo root is green, and `git diff --name-only` names exactly the files_scope paths plus board/frontmatter regen."
test_plan:
  adds: []
  modifies:
    - >-
      ReEvaluationJobCooldownTest (AUTHORIZED: Clock pinned per
      ReEvaluationJobScheduledPathIT's pattern; fetched_at seeds become
      fixed pin-relative instants; the within/beyond last_reeval_at seeds
      (currently wall-clock `Instant.now().minus(cooldown)...`,
      :76-78) become pin-relative; the cooldown itself still reads the
      injected `infochat.reeval.cooldown` config. All cooldown-boundary
      assertions unchanged).
    - >-
      ReEvaluationJobInfraFailureFanOutIT (AUTHORIZED: Clock pinned;
      the deliberate `now()` fetched_at (:179, commented :168-169) becomes a
      fixed pin-relative instant and the comment states the pin rationale;
      assertions unchanged).
    - >-
      SchemaHardeningIT (AUTHORIZED: the `Timestamp.from(Instant.now())`
      fetched_at binding (:144) becomes a fixed bootstrap-month constant;
      no decision reads it; assertions unchanged).
    - >-
      NostrSinceCursorIT (AUTHORIZED: Clock pinned; the in-window
      `Instant.now().truncatedTo(SECONDS)` seed (:54) becomes a fixed
      pin-relative instant; the fixture-validity assertion (:68-70) compares
      BELOW_FLOOR against the PIN-relative floor; cursor-semantics
      assertions unchanged).
    - >-
      LinkingJobSemanticProbeIT (AUTHORIZED: the post_reference INSERT
      (:212-216) adds `created_at` to the column list bound to the existing
      fixed FETCHED_AT; ranking assertions unchanged).
  preserves:
    - >-
      Every assertion in the five modified files (verdict/ranking/cursor
      semantics byte-identical; only timestamp sourcing moves — the M1-740
      authorization shape).
    - >-
      The lint's existing checks and self-test cases (this ticket widens
      roots only).
    - all tests currently green on main.
spec_refs:
  - docs/design/02-schema.md §2.4.4
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
reviews:
  - round: 1
    date: 2026-09-01
    verdict: REWORK
    checks: >-
      SPEC-TRUTHNESS FAIL; SECURITY PASS; TEST-ADEQUACY PASS;
      MAINTAINABILITY FAIL; SCOPE PASS
    diff_stats: 10 files, +160/-37
    rework_items: 2
  - round: 2
    date: 2026-09-01
    verdict: APPROVE
    checks: >-
      SPEC-TRUTHNESS PASS; SECURITY PASS; TEST-ADEQUACY PASS;
      MAINTAINABILITY PASS; SCOPE PASS
    diff_stats: fix hunks only (ticket record + 1-word docstring fix);
      full diff 10 files, +187/-39
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  2026-09-01: >-
    Pass. All file:line citations spot-checked true (lint DEFAULT_ROOTS :71-74,
    docstring posture :12-15, ReEvaluationJob Clock seam :114-118,
    ScheduledPathIT pin precedent :54-77, NostrStreamSource scan floor :637-650,
    all five fixture sites). Census re-run at start returned exactly the five
    cited collector violations; provider/core clean. One execution judgment,
    not an ambiguity: SchemaHardeningIT's touched INSERT also carries
    status_changed_at = now() (not named in test_plan.modifies) — bound to the
    same fixed bootstrap-month constant so acceptance-4's no-now()-in-touched-
    seed-statements grep holds; assertions untouched; no schema CHECK reads
    the column (V50:119 is an UPDATE, not a constraint).
escalation_reason:
---

# M1-962: Enforce the partition-insert lint in the Maven build

## Context

D72's rule — "test fixtures bind partition-key columns to FIXED instants
inside migration-provisioned months; ambient-now inserts into partitioned
tables are a lint error" — has been on the books since M1-740, but its
guard has two verified holes: the lint's DEFAULT_ROOTS never included
infochat-collector (the module where the 2026-09-01 partition error fired;
running the lint on collector returns FIVE violations the default posture
never saw), and nothing in the Maven build or any hook invokes the script,
so the gaps stayed invisible until a partition error surfaced mid-month.
This ticket closes the guard: all-module coverage + build enforcement
(fail-closed) + disposal of the five violations the widened scan returns +
the D72 correction (its "collector structurally immune" clause is
falsified by the same defect). M1-963 fixes the observed red fixture
family (invisible to the current lint); M1-964 closes the trace blind spot
itself.

## Root cause

`scripts/lint-partitioned-test-inserts.py:71-74` hardcodes
DEFAULT_ROOTS = [infochat-provider/src/test, infochat-core/src/test];
its docstring (:14-15) states the manual, author-side posture; M1-740's
out_of_scope declared collector "structurally immune" via
PartitionCreator's active+next test boot — falsified by any `now − offset`
seed, since month(now − Δ) is the trailing month for the first Δ of every
month (the observed defect; full chain in the analysis document, Root
cause). The five lint-visible collector sites bind the partition key from
`Instant.now()` / SQL `now()` / an omitted `created_at` (DEFAULT now());
all are partition-SAFE today only because the binding resolves to the
active month, which PartitionCreator provisions — the same sites would be
live bombs in provider/core, which is why the class rule bans them.

## Pitfalls

Analysis-document numbering:

- P5: Build wiring — bind ONCE in the ROOT pom with
  `<inherited>false</inherited>` (repo-root cwd); a module-scoped or
  inherited binding runs per-module and breaks the repo-relative roots.
  Accepted corollary: `mvn -pl <module>` runs bypass a root-bound
  execution; the gate that matters is repo-root `mvn verify` (§5).
- P6: Fail closed — non-zero lint exit must fail the build; no smoothing.
- P7: python3 becomes a build-time dependency — accepted (the repo's
  process tooling already requires it: tick-lint.py, tick-measure.py,
  lint-ticket.py); the pom comment says so.
- P2: Pinning decisions while seeds keep DB `now()` on decision-bearing
  columns (`last_reeval_at`, `status_changed_at`) — §9 no-two-clock; seeds
  move pin-relative with the key.
- P3: Pins outside May–July 2026 re-arm the bomb — acceptance-4 grep.
- P8/P9: Roots-only change here; the trace strengthening is M1-964's, and
  the five fixes land in the pin/fixed shape its stricter trace resolves
  cleanly (fixtures calibrated to the family's end state).
- P12: §8 authorization — `test_plan.modifies` names all five files,
  sourcing-only.

## Approach

**Files to touch:** the eight `files_scope` entries.

Order (each step keeps the tree green):

1. `scripts/lint-partitioned-test-inserts.py` — widen DEFAULT_ROOTS to all
   six module test roots; update the docstring's invocation posture lines;
   run it: it now exits 1 on the five collector sites (they are this
   ticket's disposal list) — leave it red until step 3.
2. `pom.xml` — add the exec-maven-plugin execution (validate,
   `<inherited>false</inherited>`, python3 + script path, fail-closed) with
   a comment stating the posture and the python3 dependency.
3. The five fixture fixes (acceptance-3 details; the ScheduledPathIT
   pin pattern where the component reads the injected Clock, plain fixed
   constants where nothing does).
4. `docs/spec/decisions.md` — the D72 correction (rule text; user approves
   wording at implementation).
5. Full probes: `--self-test`; default invocation green; the
   deliberate-violation `mvn validate` failure-mode probe (acceptance-1);
   repo-root `mvn verify`.

**Controls to preserve (§10):** no production path rerouted; the five
modified tests' assertions (cooldown boundary, fan-out verdicts, index/
verdict schema checks, cursor semantics, semantic-ranking order) are
byte-identical; M1-689 ordering families stay consistent; the M1-445/M1-446
pom tripwires untouched.

**Pitfall→mitigation:** P5 → single root binding + pom comment;
P6 → acceptance-1 probe asserts BUILD FAILURE; P7 → pom comment + D72
amendment records the posture; P2 → acceptance-4 grep; P3 → acceptance-4
grep; P9 → fixes use pin/fixed shapes only; P12 → test_plan.modifies.

## Definition of done

Mirror of `acceptance:`: the lint runs in `mvn validate` from the root pom
and fails the build on a deliberate violation; DEFAULT_ROOTS covers all six
modules and the default invocation is green; the five collector sites are
disposed with assertions untouched and pins inside bootstrap months; D72 no
longer claims collector immunity and records build enforcement; repo-root
`mvn verify` green.

## Verification

- acceptance-1 → the scratch-violation probe: `mvn validate` FAILS with the
  violation printed, then green after removal (failure-mode; also proves
  fail-closed against a missing python3 — no script, no pass).
- acceptance-2 → `python3 scripts/lint-partitioned-test-inserts.py` prints
  all six roots and exits 0; `--self-test` green.
- acceptance-3 → the five named tests green in the full suite;
  `git diff` review shows sourcing-only changes.
- acceptance-4 → the two greps (bootstrap-month constants; no `now()` in
  the touched seed statements).
- acceptance-5 → `grep -n "structurally immune" docs/spec/decisions.md`
  returns nothing; the amended row records build enforcement.
- acceptance-6 → repo-root `mvn verify` green; `git diff --name-only`
  matches files_scope.
- P5 → acceptance-1's probe doubles as the cwd check (the scratch file
  lives under collector; the root-bound execution must find it via the
  repo-relative root).
- P6 → acceptance-1 also proves fail-closed against a missing interpreter:
  with python3 unavailable `mvn validate` FAILS (no script, no pass — never
  a skip).
- P7 → the pom comment names the python3 build dependency; D72's amendment
  records the build-enforced posture.
- P2 → acceptance-4's `grep -n 'now()'` over the touched seed statements
  returns nothing (decision-bearing seeds all pin-relative).
- P3 → acceptance-4's `grep -rn 'Instant.parse("2026-0'` shows only
  bootstrap-month constants.
- P8 → `python3 scripts/lint-partitioned-test-inserts.py --self-test` green
  (acceptance-2 — no existing check weakened by the roots change).
- P9 → the five fixes use only pin/fixed shapes; cross-checked at M1-964's
  whole-tree run (zero findings on these files — end-state calibration).
- P12 → test_plan.modifies IS the §8 authorization; `git diff` review
  shows sourcing-only changes in the five named tests.

## Out of scope

Frontmatter `out_of_scope` carries the exclusions. Load-bearing: NO trace-
logic changes (M1-964's — this ticket must not grow a parser), NO
PerSourceUnknownTracker-family edits (M1-963's, and invisible to this lint
anyway), NO production code, NO provider/core churn beyond what the widened
scan factually returns (dispose-in-commit if any appears). The five
modified pre-existing tests are authorized in `test_plan.modifies` only for
timestamp sourcing; any assertion edit is an §8 violation.

## Census

Class = D72 violations visible to the current lint once collector is
scanned. Mechanical enumeration (re-runnable):

```
python3 scripts/lint-partitioned-test-inserts.py \
  infochat-collector/src/test infochat-core/src/test infochat-provider/src/test
```

Analysis-time output (2026-09-01): five violations, all collector —
ReEvaluationJobCooldownTest:160, ReEvaluationJobInfraFailureFanOutIT:172,
SchemaHardeningIT:128, LinkingJobSemanticProbeIT:213 (OMITTED),
NostrSinceCursorIT:111 → all FIX here. provider/core: clean (verified).
infochat-ssrf / infochat-llm-adapter / infochat-messaging-adapter: zero
partitioned-table INSERTs in tests (grep verified) — roots added for
coverage, trivially green. The lint-INVISIBLE remainder (helper-parameter
and static-field shapes: ReEvaluationJobTest, ReEvaluationJobWindowTest,
EmbeddingWorkerPickupFloorIT, UnresolvedRepostEdgeUniqueIT; plus M1-963's
tracker family) is OUT OF SCOPE here → M1-964 / M1-963.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-962-partition-insert-lint-full-coverage-enforced.md
```

## Round 1 rework

REWORK ITEMS (verbatim from `.scratch/tick-review-M1-962-r1.txt`):

1. Finding 1: obtain and record the user's explicit §12 approval of the
   landed D72 withdrawal sentence (or re-land the user-approved verbatim
   quote and adjust acceptance-5's probe under the same approval), evaluated
   via the round-2 mechanical report quoting the user's yes on the final
   text — or `grep -n "structurally immune" docs/spec/decisions.md`
   returning exactly the approved quoted withdrawal with the probe change
   recorded in the same approval.
2. Finding 2: drop "author-side" from
   scripts/lint-partitioned-test-inserts.py:50, evaluated via `grep -n
   "author-side" scripts/lint-partitioned-test-inserts.py` returning
   nothing (exit 1) and `python3 scripts/lint-partitioned-test-inserts.py
   --self-test` printing "self-test PASSED".
