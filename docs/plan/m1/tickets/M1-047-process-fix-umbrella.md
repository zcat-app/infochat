---
id: M1-047
title: "Process-fix umbrella: stays-green + pyramid + contracts"
status: done
created: 2026-05-21
last_updated: 2026-05-22
blocked_by:
  - M1-048
  - M1-049
  - M1-050
files_budget: 3
files_scope:
  - docs/plan/m1/process-fix-handoff.md
  - docs/plan/m1/tickets/M1-044b-inbound-router-intake-splice.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the actual M1-044b implementation — that ticket reopens on this umbrella's merge and re-enters its own lifecycle (/m1-tick start M1-044b after refine), with its own branch, its own reviewer pass, and its own commit on main
  - any production-code change — the umbrella's only repo-tree change is the transient handoff doc deletion + the M1-044b reopen edit; everything else (handler/router/IT decoupling, JSpecify contracts, verified_stays_green lint+prompts) landed under M1-049 / M1-050 / M1-048 respectively and is not re-touched here
  - changes to docs/process/test-pyramid.md, docs/process/engineering-rules-verbatim.md, CLAUDE.md, scripts/lint-ticket.py, docs/process/clarity-prompt.md, docs/process/plan-prompt.md, docs/process/ticket-template.md — those are M1-048/049/050 territory; the umbrella does not amend the landed work
  - any further M1-044b refinement decisions — those happen on M1-044b's own branch after reopen, not on the umbrella's branch
  - changes to other memory notes — only feedback_out_of_scope_stays_green_verifiable.md's "Future codification target" section is touched (out-of-band; the memory file is not in the repo and so does not appear in the git diff)
acceptance:
  - "The transient handoff doc docs/plan/m1/process-fix-handoff.md is deleted. Verify: `test ! -f docs/plan/m1/process-fix-handoff.md` exits 0 AND `git log --diff-filter=D --name-only --pretty=format: -1 -- docs/plan/m1/process-fix-handoff.md | grep -cF docs/plan/m1/process-fix-handoff.md` returns 1 (the umbrella commit is the one that deleted it)"
  - "M1-044b is reopened: its frontmatter has `status: pending`, `deferred_on:` is unset/empty, `deferred_reason:` is unset/empty, and the `reopens:` list has exactly one new entry dated 2026-05-22 with reason `umbrella-done`. Verify: `grep -cE '^status: pending$' docs/plan/m1/tickets/M1-044b-inbound-router-intake-splice.md` returns 1 AND `grep -cE '^deferred_on:\\s*$' docs/plan/m1/tickets/M1-044b-inbound-router-intake-splice.md` returns 1 AND `grep -cE '^deferred_reason:\\s*$' docs/plan/m1/tickets/M1-044b-inbound-router-intake-splice.md` returns 1 AND `grep -cE 'reason: umbrella-done' docs/plan/m1/tickets/M1-044b-inbound-router-intake-splice.md` returns 1"
  - "The memory note ~/.claude/projects/-home-ubuntu5-Projects-quarkus-projects-infochat/memory/feedback_out_of_scope_stays_green_verifiable.md no longer carries an aspirational `**Future codification target**:` section that promises unimplemented work. The section is replaced with a paragraph that names the three landed paths: `scripts/lint-ticket.py` (the OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE lint check + `verified_stays_green:` frontmatter validator), `docs/process/clarity-prompt.md` (clarity subagent's out-of-scope-stays-green check), and `docs/process/plan-prompt.md` (Plan subagent's pre-implementation dependent-tests audit). Verify (paths via env-expansion): MEM=~/.claude/projects/-home-ubuntu5-Projects-quarkus-projects-infochat/memory/feedback_out_of_scope_stays_green_verifiable.md; `grep -cE '\\*\\*Future codification target\\*\\*' \"$MEM\"` returns 0 AND `grep -cF 'scripts/lint-ticket.py' \"$MEM\"` returns ≥1 AND `grep -cF 'docs/process/clarity-prompt.md' \"$MEM\"` returns ≥1 AND `grep -cF 'docs/process/plan-prompt.md' \"$MEM\"` returns ≥1. (The memory file is outside the repo; this acceptance item is verified by reading the file at commit time, not by git diff.)"
  - "All three blockers are committed-done on main (gate for umbrella merge). Verify: `git log main --format=%s | grep -cE '^M1-048: '` returns ≥1 AND `git log main --format=%s | grep -cE '^M1-049: '` returns ≥1 AND `git log main --format=%s | grep -cE '^M1-050: '` returns ≥1. Ground-truthed against current main on 2026-05-22: M1-048's canonical subject is `M1-048: Process fix A — verified_stays_green frontmatter + lint + clarity + Plan` (em-dash after the leader, not a colon); M1-049's is `M1-049: Process fix D: test pyramid — handler/router/IT decoupling`; M1-050's is `M1-050: Process fix E: JSpecify parameter contracts (boundary classes + lint)`. (This gate is also enforced upstream by `blocked_by: [M1-048, M1-049, M1-050]`, but enumerated here so the umbrella's done state is fully self-verifying.)"
  - "mvn -B clean verify exits 0. The umbrella's only repo-tree changes are documentation + a ticket-file reopen; no production code or tests are touched, so the suite trivially stays green. The check is still required by the M1 workflow rules and catches the case where the handoff-doc deletion or M1-044b reopen edit accidentally breaks a Maven enforcer rule or doc-linter check"
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
deferred_on:
deferred_reason:
reviews:
  - round: 1
    date: 2026-05-22
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 26
      removed: 274
escalations:
  - date: 2026-05-22
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      User-initiated escalation (no automated trigger fired). The
      ticket's frontmatter is incomplete and would FAIL clarity
      pre-flight at /m1-tick start:
        - acceptance: [] (no testable criteria — DoD lives only in
          the body and is not mirrored into runnable acceptance
          items)
        - out_of_scope: [] (clarity requires non-empty out_of_scope)
        - no files_scope declared (only a numeric files_budget)
        - clarity_check: {} (never run)
      User invoked /m1-tick escalate M1-047 refine to formally enter
      the refine flow so the missing fields can be authored before
      the next /m1-tick start.
revisions:
  - date: 2026-05-22
    reason: clarity-fail refine snapshot (acceptance/out_of_scope/files_scope authoring)
    summary: |
      Pre-refine snapshot. The umbrella ticket was created 2026-05-21
      as a coordinator with the DoD captured only in body prose; the
      runnable frontmatter fields acceptance, out_of_scope, and
      files_scope were left empty as placeholders. Now that all three
      subtickets (M1-048 A, M1-049 D, M1-050 E) are done, the umbrella
      is unblocked but cannot pass clarity until those fields are
      authored. User invoked /m1-tick escalate M1-047 refine on
      2026-05-22 to formally fill them in before the next /m1-tick
      start.

      Prior frontmatter values:
        - status: pending (now: escalated, will return to pending on refine commit)
        - acceptance: []
        - out_of_scope: []
        - files_scope: (absent)
        - test_plan.preserves: [all tests currently green on main]
        - clarity_check: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-22
  verdict: WARN
  warnings:
    - "FORWARD-REFERENCE-CHECK: M1-047A, M1-047D, and M1-047E appear in §Definition of Done body prose but do not correspond to any file under docs/plan/m1/tickets/. These were working labels used before the subtickets were allocated as M1-048/049/050 respectively. The Implementation notes §Subticket allocation section already clarifies the mapping. Low risk — informational labels in prose, not load-bearing references."
  blockers: []
---

# M1-047: Process-fix umbrella: stays-green + pyramid + contracts

## Context

Umbrella ticket for the three-pronged structural process fix the
M1-044b premise-fail #2 surfaced. The full handoff lives at
`docs/plan/m1/process-fix-handoff.md` (transient — delete once
this umbrella + M1-044b are done). User picked the full A+D+E
set on 2026-05-21 with these confirmed open-decision answers:

- **D (test pyramid)**: full decoupling (~10 handler test classes
  refactored to call `handler.handle()` direct + mocks; router
  tests stay router-only; ITs cover full chain).
- **E (parameter contracts)**: JSpecify `@NonNull` / `@Nullable`
  (NOT JetBrains — user override of the handoff's prior
  recommendation; type-use semantics, no runtime dependency).
- **M1-044b parking**: defer on this umbrella; M1-044b reopens
  after A+D+E all land.

The three subtickets to be allocated next (one per option) are
the blockers; this umbrella ships only the cross-cutting
coordination + cleanup commit (delete the handoff doc, update
[[feedback-out-of-scope-stays-green-verifiable]] memory's
"future codification target" section to point at the landed
paths).

M1-044b is `status: deferred`, `deferred_on: M1-047` until this
umbrella is done. Per the boot sequence, M1-044b will be
refined against the new architecture once A+D+E land (the
"M1-035c/M1-036/M1-037/M1-039/M1-040 tests stay green" claim
dissolves under D because those tests no longer exercise the
router).

## Definition of Done

- M1-047A (procedural backstop: `verified_stays_green` frontmatter
  + lint rule + Plan-prompt instruction + clarity check) is done.
- M1-047D (test pyramid refactor: handler tests call
  `handler.handle()` direct; router tests router-only; ITs full
  chain; ~10 test classes refactored) is done.
- M1-047E (parameter contracts: JSpecify `@NonNull`/`@Nullable`
  dependency + lint check + retroactive annotation pass +
  reviewer prompt update + engineering-rules section) is done.
- `feedback_out_of_scope_stays_green_verifiable.md` memory's
  "Future codification target" section is replaced with the
  actual landed paths (lint check name, clarity prompt section,
  Plan prompt section).
- `docs/plan/m1/process-fix-handoff.md` is deleted (single
  process: commit).
- M1-044b is reopened, refined against the new architecture,
  and lands per its normal flow.

## Implementation notes

This umbrella owns no production code. It exists to gate M1-044b's
reopen and to enforce that A+D+E land as a coherent set (each
fix is weak on its own; the structural payoff is in the
combination).

Subticket allocation (per ID allocation algorithm; max existing
digit is 46 + 1 for this umbrella = 47):

- M1-048: Option A — procedural backstop (1d)
- M1-049: Option D — test pyramid refactor (2d, full decoupling)
- M1-050: Option E — parameter contracts (3d, JSpecify)

`blocked_by` populated after subticket skeletons are drafted in
the next step of the boot sequence.

## Big-picture notes

- The defect class this umbrella eliminates: "ticket asserts
  out-of-scope tests stay green; mvn verify proves otherwise."
  The four-layer pipeline (lint → clarity → Plan → reviewer)
  has no layer that reads out-of-scope test sources before
  implementation; mvn verify is the irreducibly-late detection
  point under the current architecture. A makes Plan audit
  dependent tests; D makes test-class outcomes independent of
  router behavior; E makes API contracts explicit so downstream
  impact is visible at API-design time.
- A is the backstop; D is the structural fix; E is the
  positive complement to the existing "No defensive code"
  rule. None alone is sufficient.
- After the umbrella is done, M1-044b's refine will be
  noticeably smaller — the "stays green" out-of-scope claims
  about M1-035c/M1-036/M1-037/M1-039/M1-040 dissolve because
  those tests no longer exercise the router (D), and the
  handler-level ban-message expectations in
  AddSourceCommandHandlerTest et al. become explicit handler
  contracts (E) so the splice's impact is visible at the API
  level.

## Out-of-scope expansion

- **The M1-044b splice itself.** That ticket stays parked at
  `status: deferred, deferred_on: M1-047` until this umbrella
  is done. After done, M1-044b reopens via `/m1-tick reopen
  M1-044b`, refines against the new architecture, and lands
  per its normal flow.
- **Any production-code change.** This umbrella ships only
  process docs + lint + tests. M1-048/049/050 carry the
  individual implementations.
- **Backporting JSpecify annotations across the entire
  codebase in one pass.** M1-050 will scope its retroactive
  pass — likely boundary classes first (InboundRouter,
  MessagingAdapter, CommandHandler, BundleLoader, *Service),
  with a baseline-then-enforce-on-new-code lint stance.

## Authorized test changes

- (none — this umbrella's only commit is the handoff-doc
  deletion + memory-note update + STATUS.md regeneration.)

## Alternatives considered

- **A alone.** Rejected by user 2026-05-21. Too weak — it
  requires authors to enumerate every dependent test; the
  hand-maintained "shared dispatch surface" list drifts; the
  audit-note rationales can themselves be wrong. Useful as a
  backstop, not the structural fix.
- **D alone.** Rejected — D fixes the dependency direction
  but doesn't surface the dependency at API-design time
  (E's job) and doesn't catch ticket-authoring drift between
  D's landing and the next architecture shift (A's job).
- **JetBrains annotations for E (handoff recommendation).**
  Rejected by user 2026-05-21 in favor of JSpecify. Trade-off:
  less mature IDE/static-analyzer ecosystem in exchange for
  type-use semantics (e.g. `List<@Nullable String>`) and a
  cleaner long-term standard.
- **Additive D (keep old tests, add new direct-handler tests
  alongside).** Rejected by user 2026-05-21 in favor of full
  decoupling — the old tests stay a tripwire for future
  router changes if kept.
