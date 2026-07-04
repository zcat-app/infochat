---
id: M1-556
title: Stage1WatchdogIT upper wall-clock band widens to 50x cap
status: done
created: 2026-07-04
last_updated: 2026-07-04
clarity_check:
  date: 2026-07-04
  verdict: PASS
  warnings: []
reviews:
  - round: 1
    date: 2026-07-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 22
      removed: 14
blocked_by: []
files_budget: 1
files_scope:
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1WatchdogIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any production file — Stage1Pipeline, Stage1Watchdog, and the
    regex-timeout config surface are untouched; this ticket changes one
    test tolerance only
  - the lower-bound assertion (durationMs >= TEST_CAP_MS) — it proves the
    watchdog actually gated the matcher and is not a noise source
  - the side-effect assertions (post QUARANTINED, stage1_done=true,
    "[REDACTED:" body placeholder, the single quarantine row's
    rule_id/span/original_html/flagged_by/status fields) — they are the
    load-bearing correctness checks and stay byte-identical
  - every other timing-sensitive test in the suite — no suite-wide
    tolerance policy is introduced here
acceptance:
  - "The upper wall-clock assertion in
    Stage1WatchdogIT.watchdogFiresAndPostIsSealedAtQuarantined widens
    from TEST_CAP_MS * 10 to TEST_CAP_MS * 50 (100 ms → 500 ms with the
    test's 10 ms cap), and its failure-message text says \"50× cap\"
    instead of \"10× cap\". This pre-existing-test modification is
    explicitly authorized by this ticket (test-integrity rule §8)."
  - "The band's explanatory comment is extended with the 10× flake
    history as the widening rationale: 101 ms on 2026-07-03 (M1-552
    round-1 full-suite verify) and 102 ms on 2026-07-04 (M1-551 round-1
    full-suite verify), both on unmodified stage-1 code under full-suite
    host load."
  - "The lower-bound assertion and all side-effect assertions in the
    test are byte-unchanged; no file outside Stage1WatchdogIT.java is
    touched."
  - mvn verify is green.
test_plan:
  adds: []
  modifies:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1WatchdogIT.java
      (watchdogFiresAndPostIsSealedAtQuarantined: upper wall-clock band
      10× → 50×, authorized by this ticket; every other assertion
      unchanged)"
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/verification.md §Test layers
decision_refs: []
---

# M1-556: Stage1WatchdogIT upper wall-clock band widens to 50x cap

## Context

The upper wall-clock sanity band in
`Stage1WatchdogIT.watchdogFiresAndPostIsSealedAtQuarantined` has now
flaked at every width it has been set to. At 5× cap it flaked three
times in 2026-05 (51 ms, 78 ms, 52 ms) and M1-049 widened it to 10×
(100 ms). At 10× it flaked twice in two days — 101 ms during M1-552's
round-1 full-suite verify (2026-07-03) and 102 ms during M1-551's
round-1 full-suite verify (2026-07-04) — both on unmodified stage-1
code, both under full-suite host load, and both costing a ~30-minute
full-suite re-run. The band's own comment says the side-effect
assertions are the load-bearing checks and the band exists only to
catch gross drift ("the process took 100× the cap"); a 50× band
(500 ms) keeps that gross-drift catch while sitting ~5× above the
worst noise ever observed (102 ms).

## Acceptance

- The upper band assertion widens from `TEST_CAP_MS * 10` to
  `TEST_CAP_MS * 50` and the failure message says "50× cap". This is
  an explicitly authorized modification of a pre-existing test.
- The band comment gains the 10× flake history (101 ms 2026-07-03,
  102 ms 2026-07-04, full-suite host load) as the rationale.
- The lower-bound assertion and every side-effect assertion are
  byte-unchanged; the diff touches only `Stage1WatchdogIT.java`.
- `mvn verify` is green.

## Out-of-scope

No production code changes — the watchdog's behavior is correct; only
the test's CI tolerance is wrong. The lower bound and the side-effect
assertions stay byte-identical: they are the checks that prove the
watchdog fired and sealed the post, and they have never flaked. The
modified test is named here per the test-integrity rule: only the upper
band expression, its message string, and its comment change.

## Notes

- Alternative considered — drop the upper bound entirely: rejected for
  now; it still catches a real regression class (a pipeline stall where
  something other than the matcher becomes the bottleneck) at zero
  maintenance cost once the band is wide enough to clear host noise.
  If 50× ever flakes, dropping the bound (leaving the lower bound and
  side-effects) is the right next step rather than a fourth widening.
- Precedent: M1-049 performed the 5× → 10× widening with the same
  shape (band-only change, side-effects untouched).
- Anchor: the band lives at the end of
  `watchdogFiresAndPostIsSealedAtQuarantined`
  (`Stage1WatchdogIT.java` ~l.173–190); `TEST_CAP_MS = 10` at l.79.
