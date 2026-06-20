---
id: M1-404
title: "collector: dedup the Stage 1 fail-closed whole-body write"
status: done
created: 2026-06-19
last_updated: 2026-06-20
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The per-handler WARN log lines (handleWatchdogAbort logs cap_ms, handleMatchOverflow logs cap) — stay inline at each call site, NOT folded into the shared helper.
  - handleSanitizerException's extra cause-log branch and distinct structure — may reuse the shared helper if its write is byte-identical, but reshaping it is not required by this ticket.
  - The fail-closed semantics — one quarantine row spanning [0, normalized.length()), updatePostQuarantined, both writes in one transaction, NO auto-release, body overwritten with a single whole-body placeholder — MUST be preserved byte-for-byte; this is a pure mechanical extraction.
acceptance:
  - "handleWatchdogAbort and handleMatchOverflow delegate their identical fail-closed write (insert one whole-body quarantine row spanning [0, normalized.length()) keyed by the rule-id, then updatePostQuarantined, in one TransactionHelper.inTransaction block, returning new Stage1Result(normalized, placeholderMarker, true, true)) to a single shared parameterized helper; the rule-id is the only per-handler input passed into the helper."
  - "Each handler keeps its own distinguishing WARN log line inline (cap_ms for the watchdog, cap for the overflow)."
  - "The fail-closed behavior is unchanged: Stage1WatchdogIT, Stage1MatchOverflowIT, and Stage1PipelineIT remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Failure handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-20
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 29
      removed: 22
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-20
    verdict: CLEAN
    base: 1679de85682aa9d01b89c168d319c35f5e78da57
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-404-2026-06-20.md
    out_of_model_count: 0
    note: |
      In-progress audit on the branch tip between review-APPROVE and commit.
      Pure mechanical extraction of the Stage 1 fail-closed whole-body quarantine
      write into a shared helper parameterized only by rule-id; threat-actor
      confirmed no quarantine property shifted. CLEAN, no remediation.
clarity_check:
  date: 2026-06-20
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-404: dedup the Stage 1 fail-closed whole-body write

## Context

Deep-review full (2026-06-19) collector finding **F3** (SIMPLIFICATION). Verified at
source 2026-06-19:

`Stage1Pipeline.handleWatchdogAbort` (lines 465-481) and `handleMatchOverflow`
(501-517) are byte-for-byte identical except the rule-id constant, the error-class
constant, and the WARN log message (the watchdog logs `cap_ms`, the overflow logs
`cap`). The shared body — generate a placeholder, then in one transaction insert one
quarantine row spanning `[0, normalized.length())` and `updatePostQuarantined`,
returning the same `Stage1Result` — is a single safety-critical fail-closed idiom
repeated; `handleSanitizerException` is a near-third copy. The per-site comments
already say "parallel shape to handleWatchdogAbort", which is the
maintenance-burden signal: a future change to the fail-closed write (e.g. a new
quarantine-row column) must be applied to all copies or risk drift on the
security-critical quarantine path.

`security_relevant: true` because the extracted code is the Stage 1 fail-closed
quarantine write (`docs/spec/security.md` §Failure handling) — the refactor must be
behavior-preserving and is worth a threat-actor pass to confirm no quarantine
property shifted.

## Acceptance

See frontmatter. Extract one helper parameterized by the rule-id; keep the
distinguishing log line inline at each handler so the dispatch in `process` and the
per-cap diagnostics stay visible.

## Out-of-scope

See frontmatter. No behavior change — this is readability/maintainability on a
currently-correct duplication. The fail-closed contract is preserved exactly.

## Notes

- The class javadoc already documents the fail-closed contract once, which mitigates
  the "helper hides the transaction body one level down" readability cost.
- handleSanitizerException may opt into the helper if its write matches, but its
  extra cause-log branch is left to the implementer's judgement and is not a gate.
