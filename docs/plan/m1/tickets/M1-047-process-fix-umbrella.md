---
id: M1-047
title: "Process-fix umbrella: stays-green + pyramid + contracts"
status: pending
created: 2026-05-21
last_updated: 2026-05-21
blocked_by:
  - M1-048
  - M1-049
  - M1-050
files_budget: 8
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope: []
acceptance: []
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
deferred_on:
deferred_reason:
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
