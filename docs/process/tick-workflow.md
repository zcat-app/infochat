# /tick — analysis-first ticket workflow (successor to /m1-tick)

This document specifies the **analysis-first** ticket flow, built alongside
`/m1-tick` rather than replacing it, so the two flows can be measured against
each other (see §Measurement). It is the single source of truth for the `/tick`
skill. `/m1-tick` and its docs are untouched by this flow by design.

**Why it exists.** The M1 corpus shows the failure mode of brief-driven
tickets: analysis happens at implementation time, deferral chains grow (7+
tickets deep), the security gate re-audits remediated diffs round after round
(M1-771: 6 rounds, M1-767: 6), and scope bookkeeping produces more refines
than substantive findings (78 of 133 refines + 34 budget-breach escalations
traced to file-count arithmetic). The `/tick` flow inverts the cost curve:
**the analysis happens at draft time, when it is free; implementation is
execution of that analysis; review is one merged gate with an adversarial
lens, and findings must survive falsification before they are reported.**

## Principles

1. **Spec is the contract.** Every design decision in a ticket traces to a
   `spec_refs` entry. A conflict between spec and implementation is a
   spec-amend, never a bend of the spec to the implementation. Tickets that
   cannot be grounded in the spec are analysis failures, not implementation
   discoveries.
2. **Analysis before ticket.** Every ticket is produced by a mandatory
   analysis (the `analyze` subcommand + the analyst gate agent). A ticket
   that omits root cause, pitfalls, or verification does not exist.
3. **Small tickets.** A problem decomposes into tickets whose implementation
   is execution, not discovery. A ticket whose analysis names 25 files is a
   decomposition failure, not a big ticket.
4. **Implementor is an executor, not a designer.** Divergence from the
   ticket's Approach is a **hurdle** — stop and report, never drift. The
   hurdle report carries root cause (evidence, `file:line`), suggested
   solutions, and options; the user decides.
5. **One merged review gate.** Bookkeeping is a script; judgment is one
   fresh-context gate agent applying a promise-vs-delivery lens over the
   threat model, the spec sections, the ticket acceptance, and the
   engineering rules in a single verdict.
6. **Findings must be true.** A finding is reported only when it cites
   reachable `file:line` evidence read from the actual code and survives its
   own falsification attempt. Dropped findings are recorded with the
   citation that killed them, so false negatives stay visible.
7. **Deletion over addition.** The flow ships with the gates that have
   evidence; bookkeeping gates that produced refines without findings
   (`files_budget`, `files_scope` membership, negative-space) are not
   re-invented here.
8. **Comments carry meaning.** Within classes a diff touches, the
   implementor removes stale or meaningless comments and proposes better
   names. Comments survive only when they carry business logic, a decision,
   or a trap that is not visible in the code.

## Surfaces

| Thing | Location |
|---|---|
| Tickets | `docs/plan/<milestone>/tick-tickets/M<N>-NNN-<slug>.md` |
| Analysis documents | `docs/plan/<milestone>/tick-analysis/<slug>.md` — written ONLY for a 2+ ticket decomposition; a single-ticket problem embeds the analysis in the ticket (`analysis_ref: self`) |
| Status board | `docs/plan/<milestone>/STATUS-TICK.md` (regenerated, never hand-edited) |
| Ticket schema | [`tick-ticket-template.md`](tick-ticket-template.md) |
| Analyst gate prompt | [`analyst-prompt.md`](analyst-prompt.md) |
| Reviewer gate prompt | [`tick-reviewer-prompt.md`](tick-reviewer-prompt.md) |
| Consistency script | `scripts/tick-lint.py` |
| Measurement script | `scripts/tick-measure.py` |
| Skill | `.agents/skills/tick/` (router + subcommands) |
| Gate agents | `analyst`, `tick-reviewer` (defs in `.opencode/agent/`, `.agents/agents/`) |

The active milestone for v1 is M1; ticket IDs continue the shared `M<N>-NNN`
sequence (next free ID, scanning both `tickets/` and `tick-tickets/`).

## Lifecycle

```
   pending ──> in-progress ──> in-review ──> done ──> (squash-merge into main)
      │            ▲               │
      │            └─── REWORK (in-band fix of named items, N≤round_cap)
      │                            │
      │                            ▼
      │                         escalated ──> refine | override | decompose |
      │                                        defer | spec-amend | abandon
      ▼
   (draft-time analysis failure → ticket never filed)
```

Statuses and their meanings are identical to `docs/process/workflow.md`
§Status values (`pending` / `in-progress` / `in-review` / `done` /
`escalated` / `deferred` / `abandoned`). `deferred`/`abandoned` retain the
lineage fields (`deferred_on`, `deferred_reason`, `abandoned_reason`).

## The flow

### 0. Analyze — `/tick analyze <brief>`

Mandatory for **every** ticket. The driver takes a problem brief (a
live-test finding, a user report, a redteam finding, a hurdle that needs
decomposition) and spawns the **analyst** gate agent (fresh context) with
`docs/process/analyst-prompt.md` rendered.

The analyst produces:
1. An **analysis** of the problem: the problem statement with observed
   evidence; **ground truth** verified by reading code + git (never
   docs-as-fact); the **root cause**; an enumerated **pitfalls** list
   (P1..Pn) drawn from the engineering rules (§10 controls, §9 clock, §8
   test integrity), the threat model (`docs/spec/security.md`, read in
   full only when the surface touches it), and prior similar tickets;
   **solution options grounded in the spec**, the chosen approach with
   its spec refs; the **verification strategy** (every pitfall mapped to
   a test that would catch it — negative tests mandatory); and the
   **decomposition** into tickets with boundaries and ordering. The
   decomposition is the analyst's judgment, not the brief's: a problem
   splits into the smallest independently implementable tickets; a
   problem that is genuinely one change is one ticket with the analysis
   embedded (`analysis_ref: self`, no separate document).
2. One or more **ticket files** under `tick-tickets/`, each embedding its
   slice: Context (problem + evidence), Root cause (verified), Pitfalls
   (the subset, numbered consistently), Approach (spec-derived steps, the
   files-to-touch plan, implementation order), Definition of done,
   Verification (pitfall→test mapping), Out-of-scope.

The user reviews the analysis + ticket set before anything is filed (a
ticket file is never created without explicit user confirmation). If the
analyst cannot ground the solution in the spec, it must surface a
**SPEC-GAP** block (what the spec says, what the problem demands) — the
user then decides between spec-amend (new `spec:` amendment, then analysis)
and abandoning the problem. This replaces the old flow's
outline-fail-at-start: analysis failures happen at draft time or not at all.

### 1. Consistency — `scripts/tick-lint.py`

Run at author time (before filing), at `start` (pre-flight, BLOCKER
refuses), and at `review` (mechanical input for the reviewer). Checks:

| Check | Severity | What it catches |
|---|---|---|
| REQUIRED-SECTIONS | BLOCKER | Body missing Root cause / Pitfalls / Approach / Definition of done / Verification |
| SPEC-REFS-RESOLVABLE | BLOCKER | `spec_refs` empty, or an entry whose file or `§section` anchor does not resolve |
| SPEC-REFS-CITED-BY-DOD | BLOCKER | No acceptance item cites any `spec_refs` entry (the "why" is decoupled from the "what") |
| PITFALL-VERIFICATION | BLOCKER | A pitfall (Pn) with no matching Verification entry, or a Verification entry referencing a non-existent pitfall |
| ACCEPTANCE-VERIFIABLE | BLOCKER | An acceptance item that names no test method, no runnable command, and no probe (unverifiable prose) |
| NEGATIVE-TESTS | BLOCKER | Verification contains no failure-mode test (every test asserts the intended path; nothing tries to break it) |
| ANALYSIS-REF-RESOLVABLE | BLOCKER | `analysis_ref` missing, an unresolvable path, or not `self` for a single-ticket decomposition |
| OUT-OF-SCOPE-PRESENT | BLOCKER | Empty or circular `out_of_scope` |
| FORWARD-REFERENCE-RESOLVABLE | BLOCKER | Load-bearing ticket-ID reference with no file under `tickets/` or `tick-tickets/` |
| CENSUS-PRESENT-IF-CLASS-SCOPED | WARN | Class-scoped ticket (parity/reconcile/plural-site framing) with no §Census |
| PROSE-VERB-IN-VERIFY | WARN | Acceptance items using "by reading", "by inspection", "should be present" |

There is **no `files_budget` and no `files_scope` membership gate** in this
flow. Ticket size is bounded by the analysis decomposition and by
line-level traceability (every changed line traces to an acceptance item —
a reviewer FAIL, see §4). `files_scope` MAY be declared, but only as the
mechanical disjointness proof for `--parallel`; it carries no review
consequence. `out_of_scope` remains semantic and load-bearing.

### 2. Start — `/tick start <id>`

- Pre-flight: `scripts/tick-lint.py` on the ticket (BLOCKER → refuse);
  the `analysis_ref` resolves (`self`, or a real analysis doc matching the
  ticket); and the developer self-check (every acceptance item
  implementable without guessing; every ticket claim about existing code
  verified; the §Census enumeration re-runs clean). A genuine ambiguity
  raises one blocking question. Result recorded under `clarity_check:`.
- Set `status: in-progress`, create branch `m<N>/M<N>-NNN-<slug>` off
  `main`, regenerate `STATUS-TICK.md`.
- No plan-writer at start: the analysis IS the plan, and it was approved at
  draft time.

### 3. Implement — execution, not discovery

- Follow the ticket's Approach. Touched files should match the
  files-to-touch plan; the plan is guidance, not an allowlist, but any
  departure from it (new file, skipped file, changed order with a reason)
  is a **hurdle** unless it is purely mechanical.
- **Hurdle rule.** The implementor diverges from the plan only in response
  to an extra hurdle found in the code. On a hurdle: STOP, write a hurdle
  report (see `hurdle.md`): what was planned, what was found, root cause
  with `file:line` evidence, suggested solutions (≥1, each carrying the
  falsification note — the alternative tried and why it lost),
  options (refine the ticket via `escalate → refine`; file a new ticket;
  spec-amend; drop), a recommendation, and a plain-English summary. The
  user decides. No silent drift, no in-flight scope expansion, no
  popup-menu with "fold it in" as a cheap option.
- **Comment hygiene (standing rule, replaces engineering-rules §1's
  "don't improve comments").** Within classes the diff already touches, the
  implementor removes comments that state the obvious, restate the code,
  or are stale; adds comments ONLY for business logic, a non-obvious
  decision, or a trap; and records suggested renames of methods,
  variables, parameters, fields, and classes in the commit body under a
  `Renames:` trailer. Renames of identifiers NOT already in the diff are
  out of scope (suggest, don't move).
- `mvn verify` from the repo root, full suite, captured to
  `target/tick-test-<ID>-r<round>.log` (redirect through `.scratch/` — the
  mvn clean hazard from the M1 flow applies identically).
- Immediate escalations mirror the M1 triggers (premise-fail, loop
  indicator, scope-path violation) — except they surface as hurdle reports
  rather than menu items.

### 4. Review — `/tick review <id>` (one merged gate)

- The driver runs `scripts/tick-lint.py` (shape gate) and collects the
  mechanical report: diff vs fork point, files touched vs the ticket's
  files-to-touch plan, test-log path + freshness, negative-space = files
  in the plan NOT touched.
- Spawns the **tick-reviewer** gate agent (fresh context) with
  `docs/process/tick-reviewer-prompt.md` rendered. The reviewer Reads the
  threat model, the spec_refs sections, the engineering rules, the ticket,
  and the diff in its own context, and Writes the structured verdict.
- Checks: **SPEC-TRUTHNESS** (diff ↔ spec ↔ ticket; the spec is not bent),
  **SECURITY** (threat-model promise-vs-delivery, adversarial lens),
  **TEST-ADEQUACY** (boundary siting, non-vacuity, every pitfall has a
  test that would catch it), **MAINTAINABILITY** (naming, comment hygiene,
  structure), **SCOPE** (untraceable lines, out_of_scope violations —
  mechanical parts come from the script report).
- **Falsification duty (binding).** Every finding must: cite reachable
  `file:line` evidence read from the actual code (the diff alone is not
  evidence of reachability); state the falsification attempt made against
  it; and survive it. A finding dropped during falsification is recorded
  in the verdict with the citation that defeated it (FALSIFIED-AND-DROPPED
  entries), so false negatives remain part of the record. The reviewer
  must not drop a finding on a hunch — only on a cited guard, check, or
  invariant that demonstrably blocks the claim.
- **Plain-English verdicts (binding).** The verdict is written for a
  human: a short plain-English SUMMARY first, then one bullet per finding
  carrying WHAT / WRONG (the concrete wrong output the current code
  produces) / EXPECTED (the output it must produce) / SOLUTION /
  EVALUATED-AS (the exact probe that will verify the fix next round).
  Content-free language ("robustness", "hardening", "could be improved",
  "potential issue") is forbidden as a finding's substance — no
  wrong-output example, no finding. Check names and severity labels are
  machine bookkeeping; they never replace the plain-English sentence.
- **Severity disposition.** Findings are graded critical/high/medium/low.
  - critical or high → verdict **MANUAL**: the ticket escalates and the
    user is notified with the finding summary. Fix is decided by the user.
  - medium or low with a named fix class → verdict **REWORK**: fixed
    in-band (only the named items), `mvn verify`, re-review once.
  - medium or low without a fix the diff can absorb → **MANUAL**.
- Round cap: 2 (3 for `complexity: high` or `risk: high`). Round-N growth
  beyond the named REWORK items is a FAIL (must-shrink is load-bearing
  again, not advisory).
- On REWORK, re-review re-runs the whole gate once — one verdict per
  round, all findings in it. No separate security re-audit loop: the gate
  IS the security review.

### 5. Commit & merge — `/tick commit <id>`, `/tick merge <id>`

Identical mechanics to the M1 flow (cherry-picked as-is):
- One commit per branch; subject `M<N>-NNN: <imperative summary>`; body =
  Context + `Alternatives considered:` + `Renames:` trailers +
  `Reviewed-by:` (reviewer verdict line, round, agent run id).
- `commit` re-runs `mvn verify` for `complexity: high` / `risk: high`,
  and checks test-log freshness (mtime vs staged files) otherwise.
- `merge` squash-merges into `main` with the canonical-subject idempotency
  precheck and the conflict set rule (STATUS board regen = pseudo-conflict,
  auto-resolved; anything else = refuse).
- Never push; never amend a passed commit.

### 6. Escalation — `/tick escalate <id>`

The six-way menu (refine / override / decompose / defer / spec-amend /
abandon) is retained for terminal escalations: review round-cap, MANUAL
verdicts, critical/high findings. The `decompose` arm routes through
`/tick analyze` (the analysis produces the children) instead of
title-only skeletons.

## Rules of record

The tick flow adopts `docs/process/engineering-rules-verbatim.md` §1–§10
with these deltas:

- §1 Surgical changes: **amended** — comment hygiene within touched classes
  is in scope (§3 above); everything else stands (untraceable changed
  lines are a FAIL; no adjacent "improvements").
- §2–§7, §7a, §8 (test integrity + assertion adequacy), §9 (injectable
  time), §10 (preserve controls of a replaced path): apply verbatim.
  §10's "enumerate controls in acceptance at authoring time" becomes
  mandatory inside the Approach section (the analyst enumerates them).
- The M1 flow's `files_budget`/`files_scope`/negative-space gates do NOT
  apply (§1 of this document). `text:` and `fix:` commit prefixes from
  `docs/process/workflow.md` §Non-ticket commits apply unchanged.

## Measurement

`scripts/tick-measure.py` reads both flows' ticket directories and the
redteam evidence directory and prints a comparison table:

- tickets filed, done, deferred (by `deferred_reason`), abandoned (by
  `abandoned_reason`), pending
- review rounds distribution (`reviews:` entries per ticket) and
  rework rate (rounds ≥ 2)
- escalations per ticket (`git log --grep "<id>:"`-derived refine/esc
  commits + frontmatter `escalation_reason`)
- security audits per ticket (files under `docs/plan/m1/redteam/`
  matching the id) — the A/B question is whether merged-gate tickets
  still need standalone audits
- per-ticket diff size (files touched, lines) from `reviews[].diff_stats`

Run it before drawing any conclusion about the flows; the M1 board is the
baseline.

## Harness bindings

The `/tick` skill lives under `.agents/skills/tick/` — this flow is
opencode-native and does not depend on Claude Code's surface. Gate agents
(`analyst`, `tick-reviewer`) are defined in `.opencode/agent/` with thin
pointers in `.agents/agents/`; the persona lives in the rendered prompt
(analyst-prompt.md / tick-reviewer-prompt.md) exactly as the M1 gates do.
Harness primitives (fresh-context spawn, verdict-file readback, blocking
menu, worktree parallelism, contamination check) bind per
`docs/process/harness-mapping.md` §2–§6, with the same absolute-path rule
(§6.1(d)) and the post-gate `git status --porcelain` check.

## Conflicts with the M1 flow

Both flows share the `M<N>-NNN` ID sequence, `main`, and the branch/commit
mechanics. They do not share ticket directories or boards. A ticket filed
by one flow is driven by that flow only; do not start a `tick-tickets/`
ticket with `/m1-tick` or vice versa. If a conflict between this document
and `docs/process/workflow.md` matters for a ticket in THIS flow, this
document wins for that ticket; the M1 doc is untouched.
