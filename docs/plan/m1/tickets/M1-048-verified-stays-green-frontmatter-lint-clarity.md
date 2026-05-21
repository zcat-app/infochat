---
id: M1-048
title: "Process fix A: verified_stays_green frontmatter + lint + clarity + Plan"
status: done
created: 2026-05-21
last_updated: 2026-05-21
blocked_by: []
files_budget: 6
files_scope:
  - docs/process/ticket-template.md
  - scripts/lint-ticket.py
  - docs/process/plan-prompt.md
  - docs/process/clarity-prompt.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
decomposed_from: M1-047
out_of_scope:
  - any production code change — this ticket is process-only (template + linter + prompt instructions)
  - retroactive backfill of `verified_stays_green:` on existing tickets — out of scope; the new check fires only on tickets that re-enter `/m1-tick start` after this ticket lands. The known-bad M1-044b explicitly stays in its current shape until its post-A+D+E refine
  - test pyramid refactor (M1-049 territory) — A is the procedural backstop, D is the structural fix
  - parameter contract annotations (M1-050 territory)
  - any change to the `OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE` heuristic file list beyond the initial set named below — expansions land via subsequent `process:` commits as new dispatch surfaces emerge
  - changes to scripts/regen-status.py or scripts/m1-render-prompt.py — only lint-ticket.py is touched
acceptance:
  - "docs/process/ticket-template.md adds a new frontmatter field `verified_stays_green:` (default: empty list) with an inline schema comment naming the per-entry shape (`test_class:` fully-qualified, `rationale:` one-line audit note). Verify: `grep -cE '^verified_stays_green:' docs/process/ticket-template.md` returns ≥1 match AND `grep -E 'test_class:' docs/process/ticket-template.md` returns ≥1 match in the same vicinity"
  - "scripts/lint-ticket.py declares a module-level constant `SHARED_DISPATCH_SURFACE_FILES` listing the initial heuristic set: `InboundRouter.java`, `RateCapBucket.java`, `InviteCodeConsumer.java`, `BanCheck.java`, `AutoRegisterService.java`, plus the glob `*Command*.java` matching files under `provider/src/main/java/`. Verify: `grep -E '^SHARED_DISPATCH_SURFACE_FILES\\s*=' scripts/lint-ticket.py` returns ≥1 match"
  - "scripts/lint-ticket.py adds a new check named `OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE` that fires as a BLOCKER when `files_scope` contains any file matching `SHARED_DISPATCH_SURFACE_FILES` AND `verified_stays_green` is empty/missing. The check is silent (not skipped, just no finding) when `files_scope` matches none of the heuristic files. Verify: `grep -E 'OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE' scripts/lint-ticket.py` returns ≥1 match"
  - "scripts/lint-ticket.py's check-table in the docstring/help OR in `docs/process/ticket-template.md` §Pre-flight self-check is extended to include the new check name and the catch description. Verify: `grep -E 'OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE' docs/process/ticket-template.md` returns ≥1 match"
  - "Running `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-044b-*.md` reports the new `OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE` check as BLOCKER (M1-044b's files_scope contains InboundRouter.java + RateCapBucket.java but has no verified_stays_green field). This is the known-bad positive-control test: the check MUST fire on M1-044b in its current shape. Verify: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-044b-*.md 2>&1 | grep -cE 'OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE'` returns ≥1"
  - "Running `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-022-*.md` (or any ticket whose files_scope contains no shared-dispatch-surface file) does NOT report `OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE`. This is the negative-control test: the check MUST be silent when the heuristic doesn't match. Verify: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-022-*.md 2>&1 | grep -cE 'OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE'` returns 0"
  - "docs/process/plan-prompt.md adds a new audit instruction (e.g. as a new numbered audit dimension, or appended to §Audit coverage) that triggers when `files_scope` contains a shared-dispatch-surface file. The instruction tells Plan to: (a) enumerate every test under `provider/src/test/` exercising the path via `grep -rE 'adapter\\.deliverDm\\(|router\\.onMessage\\(|<handler>\\.handle\\('`, (b) classify each hit as stays-green / needs-edit / depends-on-superseded-behavior, (c) cross-check against the ticket's `verified_stays_green:` list, (d) FAIL if any test is misclassified. Verify: `grep -cE 'verified_stays_green' docs/process/plan-prompt.md` returns ≥1 match AND `grep -cE 'shared dispatch surface|dispatch surface' docs/process/plan-prompt.md` returns ≥1 match"
  - "docs/process/clarity-prompt.md adds a new clarity check (numbered consistent with existing checks — likely #12 given current #11 is ACCEPTANCE-ORDERING-CONSISTENT). The check, named `VERIFIED-STAYS-GREEN-PLAUSIBLE` (WARN level, not BLOCKER — LLM judgment, not mechanical), reads each `verified_stays_green:` entry's `rationale` line and confirms it is plausibly grounded in the cited test source (e.g., 'pre-seeds users with admin row' is plausible iff the test source contains a matching INSERT or @BeforeEach setup). Verify: `grep -cE 'VERIFIED-STAYS-GREEN-PLAUSIBLE' docs/process/clarity-prompt.md` returns ≥1 match"
  - "mvn -B clean verify exits 0 (no production code changed; this acceptance item exists to confirm the build stays green even though the ticket is process-only)"
test_plan:
  adds: []
  modifies:
    - docs/process/ticket-template.md
    - scripts/lint-ticket.py
    - docs/process/plan-prompt.md
    - docs/process/clarity-prompt.md
  preserves:
    - all tests currently green on main
    - lint-ticket.py's existing eight checks (GREP-SHELL-PARSEABLE, GREP-EMBEDDED-QUOTE, REGEX-COMPILABLE, GREP-CROSS-LINE-NEWLINE, FILES-SCOPE-COVERAGE, HETEROGENEOUS-AGGREGATE-TEST-COUNT, PROSE-VERB-IN-VERIFY, IMPLEMENTATION-NOTES-ACCEPTANCE-CROSS-REF, ACCEPTANCE-ORDERING-CONSISTENT) — none of them change behavior; the new check is purely additive
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-05-21
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 258
      removed: 9
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-21
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 1: the second clause `grep -E 'test_class:' docs/process/ticket-template.md returns ≥1 match in the same vicinity` appends a prose qualifier (\"in the same vicinity\") that is not mechanically verifiable. Recommend splitting into two discrete acceptance items, or replacing with a chained grep (e.g., `grep -A3 '^verified_stays_green:' docs/process/ticket-template.md | grep -cE 'test_class:'`)."
  blockers: []
---

# M1-048: Process fix A — verified_stays_green frontmatter + lint + clarity + Plan

## Context

Subticket of [[M1-047]]. The smallest of the three process-fix subtickets and the procedural backstop for the defect class M1-044b's premise-fail #2 surfaced.

The defect class: a ticket asserts "M1-X tests stay green unchanged" for tests outside `files_scope`, but the claim is never verified because no pipeline layer (lint, clarity, Plan, reviewer) reads out-of-scope test source. The first layer that exercises out-of-scope code is `mvn verify` — the irreducibly-late detection point. This ticket pulls verification one layer earlier (to Plan time) AND adds an authoring-time forcing function (lint BLOCKER) requiring the author to enumerate the dependent tests explicitly.

The structural fix is [[M1-049]] (test pyramid — make handler tests not depend on router behavior). A is the backstop, not the cure; if D were 100% effective, A would be redundant. But D may not be 100% — new dispatch surfaces emerge over time (e.g. a future router-level translation layer, a future per-command authorization gate) — and A catches the next "I asserted stays green and was wrong" before it costs another round of mvn verify time.

## Definition of Done

- `verified_stays_green:` frontmatter field exists in `docs/process/ticket-template.md` with a schema comment naming per-entry shape (`test_class`, `rationale`).
- `scripts/lint-ticket.py` declares `SHARED_DISPATCH_SURFACE_FILES` constant + new `OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE` check (BLOCKER).
- The check fires on M1-044b (positive control) and is silent on M1-022 (negative control).
- `docs/process/plan-prompt.md` instructs Plan to audit dependent tests when `files_scope` contains a shared-dispatch-surface file.
- `docs/process/clarity-prompt.md` adds `VERIFIED-STAYS-GREEN-PLAUSIBLE` (WARN) check.
- `mvn -B clean verify` exits 0.

## Implementation notes

- **Heuristic file list.** Start narrow; expand only when the next defect surfaces. The initial set is the 5 explicit files plus the `*Command*.java` glob (per the handoff). Adding more is a future `process:` commit, not this ticket's concern.
- **Glob vs. explicit name.** Mix is fine. The lint check should handle both: a `files_scope` entry matches the heuristic if its basename equals one of the explicit names OR matches the `*Command*.java` glob (or any future glob added to the constant).
- **Per-entry rationale grounding.** The clarity check is LLM-judgment, not mechanical. The clarity subagent reads the cited test file and judges whether the rationale is plausibly true. WARN-level because false-positives are likely; BLOCKER-level would over-fire.
- **Plan-prompt change shape.** Plan already has an `### Audit coverage` enumeration in its outline structure. The new audit dimension slots in there as `### Dependent test coverage (shared-dispatch-surface gate)` with the grep + classification instruction. Plan FAILs the outline if it finds a misclassification.
- **No retroactive backfill.** Existing tickets in `done` state stay as-is. The check fires only on tickets that go through `/m1-tick start` after this ticket lands. M1-044b is `deferred` and will be refined post-A+D+E; the refine will populate `verified_stays_green:` per the new schema.
- **Test the linter on real tickets.** Acceptance items 5 + 6 are the positive- and negative-control tests; running the linter against M1-044b (BLOCKER expected) and M1-022 (silent expected) is the verification.

## Big-picture notes

- A alone is weak — the hand-maintained `SHARED_DISPATCH_SURFACE_FILES` list drifts; the per-entry rationales can themselves be wrong. The structural fix is [[M1-049]] (test pyramid). A's value is catching the next defect-class instance during the window between D landing and the next architectural shift that introduces a new dispatch surface D doesn't cover.
- The `OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE` check name is intentional — it mirrors the verdict name a reviewer would print. Keeps the lint output greppable from CI logs and consistent with the established naming.
- After this ticket lands, the umbrella [[M1-047]] will (in its own commit) update `feedback_out_of_scope_stays_green_verifiable.md` memory's "Future codification target" section to point at the landed paths (lint check name, prompt sections).

## Out-of-scope expansion

- **No production code changed.** Process tickets exit through mechanical lint/grep checks; no runtime behavior shifts.
- **No retroactive ticket backfill.** Adding `verified_stays_green: []` to every existing ticket is unnecessary; the check fires only when relevant.
- **Test pyramid + parameter contracts are sibling tickets.** [[M1-049]] and [[M1-050]] each own their own scope; nothing here pre-empts them.

## Authorized test changes

- (none — this ticket adds no tests; the lint script's behavior is verified by acceptance items 5 + 6 running it against real ticket files.)

## Alternatives considered

- **Skip A, do only D + E.** Rejected by user 2026-05-21 — see [[M1-047]] §Alternatives considered. A is the backstop for the inevitable next dispatch surface D doesn't anticipate.
- **Make the lint check WARN instead of BLOCKER.** Rejected — the whole point is an authoring-time forcing function. WARN would let the bad ticket through to clarity/Plan.
- **Skip the Plan instruction (lint + clarity only).** Rejected — Plan reads the actual test source, lint only reads frontmatter. Plan is the LLM-grade auditor; without it the check degrades to "did the author remember to write the field" rather than "is the field actually true."
