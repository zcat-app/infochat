# /tick — analysis-first ticket workflow (successor to /m1-tick)

This document specifies the **analysis-first** ticket flow and is the single
source of truth for the `/tick` skill. It supersedes `/m1-tick` for new work.
`/m1-tick` is deprecated and stays invocable for the tickets already on its
board; its docs are untouched by this flow by design.

**Shape.** The analysis happens at draft time, when it is free; implementation
is execution of that analysis; review is one merged gate with an adversarial
lens, and findings must survive falsification before they are reported.

## Principles

1. **Spec is the contract for spec-bearing tickets.** A ticket that changes
   what the system promises traces every design decision to a `spec_refs`
   entry; a conflict between spec and implementation is a spec-amend, never
   a bend of the spec to the implementation. A defect ticket — one making
   the code do what the spec already says — is grounded by its reproduction
   instead, and `spec_refs` may be empty.
2. **Reproduction, then analysis — both mandatory.** Every ticket carries an
   executable statement of the wrong behavior (§0) AND an analysis (§0b).
   The order is the point: the reproduction is evidence, so the analyst
   explains something observed instead of predicting the behavior of unread
   code. A ticket missing either does not exist. Both gates sit before
   implementation, where a rewrite costs a ticket rather than a diff.
3. **Small tickets.** A problem decomposes into tickets whose implementation
   is execution, not discovery. A ticket whose analysis names 25 files is a
   decomposition failure, not a big ticket.
4. **Implementor executes the contract, not the route.** The contract is the
   ticket's reproduction and acceptance; the Approach is a route proposed
   before the code was read. A better route inside the same behavior —
   calling an existing helper the Approach missed, replacing instead of
   patching — is execution, not drift. A **hurdle** is one of four: the
   reproduction proves the ticket's premise wrong; the fix needs another
   Maven module or a file another in-flight ticket holds; the fix needs a
   spec change; the change would drop a control the replaced path carried
   (engineering-rules §10). On a hurdle: STOP, report root cause with
   `file:line` evidence, suggested solutions and options; the user decides.
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
8. **Comments carry meaning, and are budgeted.** Code self-documents
   first; a comment earns its place only where genuinely complex logic
   cannot be simplified away (engineering-rules §11). Within classes a
   diff touches, the implementor removes stale or meaningless comments
   and proposes better names. Comments survive only when they carry
   business logic, a decision, or a trap that is not visible in the
   code, stated briefly and as current truth — history and detail move
   to the ticket, the analysis, or the decisions log and are cited by
   one stable pointer (spec-section anchor, decision ID, ticket ID, or
   `docs/plan/...` path — engineering-rules §11; a ticket ID is the
   weakest form, a frozen premise — in code comments only for business
   weight or to resolve a ticket-internal reference, never as pure
   provenance). New rationale is
   capped at 3 lines per call site.

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
| Skill | `.agents/skills/tick/subcommands/` (the procedure); routers `.claude/skills/tick/SKILL.md` (Claude Code) and `.agents/skills/tick/SKILL.md` (every harness discovering under `.agents/skills/`) |
| Gate agents | `analyst`, `tick-reviewer` (defs in `.opencode/agent/`, `.agents/agents/`, `.claude/agents/`) |

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
   (no reproduction, or draft-time analysis failure → ticket never filed)
```

Statuses and their meanings are identical to `docs/process/workflow.md`
§Status values (`pending` / `in-progress` / `in-review` / `done` /
`escalated` / `deferred` / `abandoned`). `deferred`/`abandoned` retain the
lineage fields (`deferred_on`, `deferred_reason`, `abandoned_reason`).

## The flow

### 0. Reproduce — the entry gate

Before a ticket is filed, the wrong behavior is made executable. The driver
writes the failing test that names it — asserting the behavior the system
should have — and runs it. It must fail, and the failure must name the
actual wrong output, not a missing symbol. The test's fully-qualified name
goes in the ticket's `reproduction:` field and its assertion becomes the
first acceptance item.

For a diff `mvn verify` cannot cover (docs, shell, compose), the
reproduction is a runnable probe: the exact command plus its observed wrong
output, pasted. Same field, same role.

- A reproduction that cannot be written does not skip the gate: the brief
  goes to §0b, whose first job is to establish why it could not be written.
- A reproduction that passes on `main` falsifies the ticket's premise. Stop
  and report; do not file.
- The test is written against the behavior, never against the planned
  implementation. A test that only compiles once the fix exists is a design
  sketch, not a reproduction.

For a multi-ticket decomposition, §0's mandatory RED run is the FAMILY's
reproduction — it exists before `analyze`. A child ticket then either
names a test that resolves in-tree, or carries an explicit marker:
`to-be-written` (plus the intended `Class#method`) for a test only that
child can make writable, or `parked: <path>` for a test written and run
RED but deliberately held out of the source tree. `start` converts the
marker before any fix code: write (or restore) the test, run it RED,
replace the marker with the real name. tick-lint resolves named tests
in-tree and accepts only these two markers as alternatives — an invented
test name is a BLOCKER, not a promise.

### 0b. Analyze — `/tick analyze <brief>`

Mandatory for **every** ticket, and it runs on §0's evidence: the analyst is
handed the reproduction and explains it, rather than predicting what unread
code does. Where §0 could not produce a reproduction, the analysis says so
and its first job is to establish why.

The driver takes a problem brief (a
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
3. A **placement classification** for every artifact the plan names:
   committed (instance-free procedure or fixture, engineering-rules §13)
   vs operator-local (instance-bound scripts, dumps, run records, and any
   deployment-identifying fact — real-instance names, ports, non-fixture
   fingerprints, aggregates over real users' rows). A files-to-touch plan
   that routes deployment-identifying material into the repo is a
   draft-time failure, corrected before the user sees the ticket set;
   the reviewer's placement leg catches anything that slips through.

The user reviews the analysis + ticket set before anything is filed (a
ticket file is never created without explicit user confirmation). If the
analyst cannot ground the solution in the spec, it must surface a
**SPEC-GAP** block (what the spec says, what the problem demands) — the
user then decides between spec-amend (new `spec:` amendment, then analysis)
and abandoning the problem. Analysis failures happen at draft time or not at
all.

### 1. Consistency — `scripts/tick-lint.py`

Run at author time (before filing), at `start` (pre-flight, BLOCKER
refuses), and at `review` (mechanical input for the reviewer). Checks:

| Check | Severity | What it catches |
|---|---|---|
| REPRODUCTION-PRESENT | BLOCKER | `reproduction:` empty; naming neither a test method nor a probe with observed output; or naming a test that does not resolve in-tree and carries no `to-be-written` / `parked:` marker (§0) |
| ACCEPTANCE-VERIFIABLE | BLOCKER | An acceptance item that names no test method, no runnable command, and no probe (unverifiable prose) |
| FORWARD-REFERENCE-RESOLVABLE | BLOCKER | Load-bearing ticket-ID reference with no file under `tickets/` or `tick-tickets/` |
| SPEC-REFS-RESOLVABLE | BLOCKER | A **present** `spec_refs` entry whose file or `§section` anchor does not resolve |
| ANALYSIS-REF-RESOLVABLE | BLOCKER | `analysis_ref` missing, or a path that does not resolve (`self` / `none` are legal) |
| STATUS-VALUE | BLOCKER | `status` outside the allowed set |
| REQUIRED-SECTIONS | WARN | Body missing Root cause / Pitfalls / Approach / Definition of done / Verification |
| SPEC-REFS-CITED-BY-DOD | WARN | No acceptance item cites any `spec_refs` entry |
| PITFALL-VERIFICATION | WARN | A pitfall (Pn) with no matching Verification entry, or a Verification entry referencing a non-existent pitfall |
| NEGATIVE-TESTS | WARN | Verification contains no failure-mode test beyond the reproduction |
| OUT-OF-SCOPE-PRESENT | WARN | Empty or circular `out_of_scope` |
| CENSUS-PRESENT-IF-CLASS-SCOPED | WARN | Class-scoped ticket (parity/reconcile/plural-site framing in the title or acceptance items — body prose does not trigger) with no §Census |
| PROSE-VERB-IN-VERIFY | WARN | Acceptance items using "by reading", "by inspection", "should be present" |

A BLOCKER means the flow would rather file nothing than file this: no
executable statement of the wrong behavior, an acceptance criterion nobody
can check, or a dangling reference. Everything else is a WARN — visible in
the mechanical report, never a refusal.

There is **no `files_budget` and no `files_scope` membership gate** in this
flow. Ticket size is bounded by the analysis decomposition and by
line-level traceability (every changed line traces to an acceptance item —
a reviewer FAIL, see §4). `out_of_scope` remains semantic and load-bearing.

**Parallel start requires different Maven modules.** Two tickets may run
concurrently only when their changes land in disjoint modules — a boundary
the build enforces, unlike a declared path list, which is a promise made
before the code was read. `start` checks this mechanically (never from
memory): enumerate the tick tickets with `status: in-progress` /
`in-review`, take the Maven-module root of each one's `files_scope` paths
(or its worktree's changed modules when the scope is absent or stale), and
refuse `--parallel` on any overlap with the candidate's modules; a ticket
whose module cannot be determined this way runs sequentially. `files_scope`
MAY be declared as supporting evidence; it carries no review consequence
and does not by itself qualify a ticket for `--parallel`.
`migration_touch: true` still serializes. (M1-790 and M1-796 overlapped in
infochat-provider while both in flight; their semantic collision — a test
flip against a new degrade rule, no shared file — reached main red.)

### 2. Start — `/tick start <id>`

- Pre-flight: `scripts/tick-lint.py` on the ticket (BLOCKER → refuse);
  the `analysis_ref` resolves (`self`, or a real analysis doc matching the
  ticket); and the developer self-check (every acceptance item
  implementable without guessing; every ticket claim about existing code
  verified; the §Census enumeration re-runs clean). A genuine ambiguity
  raises one blocking question. Result recorded under `clarity_check:`.
- Set `status: in-progress`, create branch `m<N>/M<N>-NNN-<slug>` off
  `main`, regenerate `STATUS-TICK.md`. All ticket work happens in that
  git worktree, under the repo-root `.worktree/` directory; the primary
  checkout is never a workspace.
- No plan-writer at start: the §0b analysis IS the plan and was approved at
  draft time. The contract is the §0 reproduction plus acceptance; the
  Approach is a route, not a gate (§Principles 4).

### 3. Implement — execution, not discovery

- **Make the §0 reproduction green** — that is the contract. Follow the
  Approach where it holds; take a better route inside the same behavior
  where it does not (§Principles 4). Departures from the files-to-touch plan
  are reported at review as a diff-shape line, not gated at implement time.
- **Hurdle rule — the four triggers** (§Principles 4): premise wrong;
  another Maven module or another in-flight ticket's file; a spec change; a
  control of the replaced path would be dropped. Anything else is
  execution — proceed, and let the merged gate judge the result against the
  contract. On a hurdle: STOP, write a hurdle
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
  decision, or a trap — brief, current-truth, javadoc included, with
  detail and history cited by one stable pointer (spec-section anchor,
  decision ID, ticket ID, or `docs/plan/...` path — engineering-rules
  §11; a ticket ID is a frozen-premise pointer, so in code comments it
  carries business weight or resolves a ticket-internal reference —
  pure provenance stays in the commit message), never retold; and
  records suggested renames of methods,
  variables, parameters, fields, and classes in the commit body under a
  `Renames:` trailer. Renames of identifiers NOT already in the diff are
  out of scope (suggest, don't move).
- **Spec edits land by approval (standing rule, engineering-rules
  §12).** A diff touching `docs/spec/**` shows the user the exact
  proposed text with plain-English reasoning BEFORE the edit lands —
  even when the ticket's acceptance lists the amendment: the ticket
  authorizes the work, the user approves the wording. The driver records
  the approval in the round's mechanical report so the gate can see it.
  New spec prose is rule-text only: no dates, ticket IDs, or report
  citations.
- `mvn verify` from the repo root, full suite, captured to
  `target/tick-test-<ID>-r<round>.log` — written to `.scratch/` first and
  copied to `target/` after the build, because `mvn clean` deletes the
  repo-root `target/` early in the run.
- Premise-fail, loop indicator and scope-path violation surface as hurdle
  reports, never as menu items.

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
  - all findings LOW, every fix comment/javadoc-only (zero executable
    lines; no docs/spec, docs/design or root-md files — parity-test
    fixtures), every finding carrying a mechanical EVALUATED-AS probe →
    verdict **APPROVE-WITH-FIXES**: the driver applies exactly the named
    fixes, verifies each probe plus a `test-compile` of the touched
    modules, and proceeds to commit. The round's green log remains the log
    of record; no further round runs. A fix that cannot stay comment-only
    demotes the verdict to REWORK.
  - medium or low with a named fix class → verdict **REWORK**: fixed
    in-band (only the named items), `mvn verify`, re-review once.
  - medium or low without a fix the diff can absorb → **MANUAL**.
- Round cap: 2 (3 for `complexity: high` or `risk: high`). Round-N growth
  beyond the named REWORK items is a WARN — advisory, never a FAIL. The
  round cap is the bound on non-convergent rework.
- On REWORK, re-review works from **round N's REWORK items** — every one
  gets a disposition of SATISFIED / NOT-ADDRESSED / DECLINED against the
  `EVALUATED-AS` probe its finding named, with the fix hunks (round N to
  N+1) as the evidence. Enumerating items rather than hunks is what stops a
  silently dropped item: where its probe is a test the rework was to add,
  dropping the item leaves the suite green and produces no hunk to inspect.
  Any NOT-ADDRESSED is a FAIL; any DECLINED is MANUAL. APPROVE is the
  expected verdict and is explicitly permitted once every item is
  SATISFIED. Observations outside the fix hunks go to
  RECOMMENDED-NEW-TICKET, never into this round's REWORK items; the
  driver dispositions them — pre-existing and untouched by the diff →
  recorded in the ticket and commit body with no user decision requested;
  carrying a DECIDE-BEFORE ordering constraint → relayed to the user now.
  Filing any ticket stays the user's call. No
  separate security re-audit loop: the gate IS the security review.

### 5. Commit & merge — `/tick commit <id>`, `/tick merge <id>`

- One commit per branch; subject `M<N>-NNN: <imperative summary>`; body =
  Context + `Alternatives considered:` + `Renames:` trailers +
  `Reviewed-by:` (reviewer verdict line, round, agent run id).
- `commit` first checks tree identity against the last verified snapshot
  (docs/plan excluded — it holds only board/frontmatter bookkeeping):
  identical → the green log stands and no re-run happens; else it re-runs
  `mvn verify` for `complexity: high` / `risk: high`, and checks test-log
  freshness (mtime vs staged files) otherwise.
- `merge` first refuses a stale verified tree: current `main` must be an
  ancestor of the branch tip (`git merge-base --is-ancestor main <branch>`).
  If main advanced after the branch's green verify, that log attests a main
  that no longer exists — cross-ticket semantic collisions survive every
  file-level check (the two diffs need share no path at all) and only a
  full-suite run against CURRENT main catches them (M1-790 merged green
  against a pre-M1-796 main and landed a red suite). Recovery: rebase the
  branch onto fresh `main` (the STATUS-board regen is the expected
  pseudo-conflict), re-run the full verify, then re-run `merge`; a rebase
  that changed the diff beyond the board regen goes through `review`
  again first.
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
  §10's "enumerate controls in acceptance at authoring time" is mandatory
  inside the Approach section whenever the change reroutes or replaces an
  existing path; the analyst enumerates them at §0b. A dropped
  control is also a hurdle trigger (§Principles 4) and a SECURITY-CHECK
  item at review — the duty never depends on which door the ticket came
  through.
- §"Run the full test suite before declaring done": amended for two
  byte-identity cases — an APPROVE-WITH-FIXES apply whose diff is
  comment-only keeps the round's green log as the log of record
  (test-compile of the touched modules is the required proof — a comment
  edit can still break the compile), and `/tick commit` skips the safety
  re-run when the tree is identical (docs/plan excluded) to the tree the
  last green log verified. Both cases record what was reused and why in
  the commit body. Everything else in that rule applies verbatim.
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
  matching the id)
- per-ticket diff size (files touched, lines) from `reviews[].diff_stats`

Run it before drawing any conclusion about the flows; the M1 board is the
baseline.

## Harness bindings

The flow has one procedure and two entry points, split by **discovery
surface, not by product**. Every subcommand lives under
`.agents/skills/tick/subcommands/` and is the single source of truth; the
routers — `.claude/skills/tick/SKILL.md` for Claude Code,
`.agents/skills/tick/SKILL.md` for every harness that discovers skills
under `.agents/skills/` — hold only the dispatch table and the
cross-cutting rules, and a change to either router must be made in both. A
new harness therefore needs no edit here: it reads one of the two surfaces,
and which products those are is `harness-mapping.md`, the only document
that names tools. Gate agents (`analyst`, `tick-reviewer`) are defined in
`.opencode/agent/`, `.agents/agents/` and `.claude/agents/`; the persona
lives in the rendered prompt (analyst-prompt.md / tick-reviewer-prompt.md)
exactly as the M1 gates do, so the per-harness definitions carry only the
role and the tool grant.
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
