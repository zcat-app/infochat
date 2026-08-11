# /tick start

Begin work on a tick-flow ticket. Invocation: `/tick start <id>` (optionally
`--parallel`). The main conversation IS the developer from this point — no
developer-subagent, no plan-writer (the analysis document already is the
plan and was approved at draft time).

## Preconditions

- The ticket file is under `docs/plan/m1/tick-tickets/`. If the id resolves
  under `docs/plan/m1/tickets/`, refuse with a pointer to `/m1-tick` — each
  flow drives its own tickets.
- `status: pending` and every `blocked_by` entry is `done`.

## Step 1 — ticket-readiness pre-flight (both parts in the main session)

1a. **Lint.** `python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M<N>-NNN-*.md`.
  Any BLOCKER refuses the start (the user fixes the ticket and re-runs; the
  fix goes through `/tick escalate <id> refine` if it changes scope).
  WARNs are notify-and-continue.

1b. **Developer self-check** — your judgment against the ticket AND the
  code it names:
  - Every acceptance item is implementable without guessing.
  - No ticket claim about existing code is false (spot-check the `file:line`
    citations in Root cause / Approach).
  - The §Census enumeration re-runs clean (grep it; every returned path has
    a row).
  - The analysis's pitfalls all landed in the ticket. For a 2+
    decomposition, cross-read `analysis_ref:` (a pitfall missing from the
    ticket is a defect in the draft, fix it before starting); for
    `analysis_ref: self` the ticket IS the analysis — skip the
    cross-read.
  - If the ticket carries `replaces:` (or a worktree holds a superseded
    implementation of this surface), READ the prior attempt's redteam and
    review artifacts before any code — they are the cheapest complete list
    of the surface's known failure modes (M1-784's round-1 hurdle was
    pre-documented in the superseded M1-776 attempt's redteam report).
  - Enumerate the tests each `blocked_by` ticket ADDED on the seam this
    ticket changes, and trace every one under the planned change.
    `test_plan.preserves` is a falsifiable claim — falsify it now, not at
    `mvn verify` (M1-785's P10 pin was calibrated to the pre-M1-784
    representation; the collision cost a full verify plus a hurdle).
  A genuine ambiguity → one blocking question (never an escalation). Record
  the result under `clarity_check:` in the frontmatter.

## Step 2 — branch

Set `status: in-progress` (frontmatter) and `last_updated`. Create branch
`m<N>/M<N>-NNN-<slug>` off `main`. For `--parallel`: the ticket's changes
must land in a different Maven module from every in-flight ticket's (no
in-flight `migration_touch: true`), and the work happens in a git worktree;
degrade to sequential if the tool cannot operate in another working
directory. Check the module boundary MECHANICALLY, never from memory: list
the tick tickets with `status: in-progress` or `in-review` (frontmatter
under `docs/plan/m1/tick-tickets/`), take the Maven-module root of each
one's `files_scope` paths (or its worktree's changed modules when the scope
is absent or stale), and refuse `--parallel` on any overlap with this
ticket's modules. A ticket whose module cannot be determined this way runs
sequentially — a declared `files_scope` is supporting evidence, not a
substitute for the module boundary (M1-790 and M1-796 overlapped in
infochat-provider while both in flight; their semantic collision reached
main red).

## Step 3 — implement (execution, not discovery)

- **Reproduction first.** If `reproduction:` carries a `to-be-written`
  marker, write that test now and run it RED before any fix code, then
  replace the marker with the real name. A `parked:` marker means restore
  the named file into its source-tree location and run it RED. The
  reproduction turning green is the ticket's contract.
- Follow the ticket's Approach where it holds; take a better route inside
  the same behavior where it does not (workflow §Principles 4), preserving
  the enumerated controls (§10). The files-to-touch plan is guidance, not
  an allowlist — departures surface at review as a diff-shape line, never
  as a stop at implement time.
- **Hurdle rule — the four triggers only** (workflow §Principles 4,
  hurdle.md): the reproduction proves the premise wrong; the fix needs
  another Maven module or a file another in-flight ticket holds; the fix
  needs a spec change; the change would drop a control the replaced path
  carried. On one of these: STOP, run `/tick hurdle <id>`, and wait for
  the user's decision. Anything else — an Approach claim that does not
  hold in the code, a better helper, a rewrite instead of a patch — is
  execution: proceed, and the merged gate judges the result. Never drift
  silently, never "fold in" new scope.
- **Comment hygiene.** Inside classes the diff touches: remove stale or
  meaningless comments; do NOT add comments that restate the code; add
  comments only for business logic, a non-obvious decision, or a trap —
  brief, current-truth, javadoc included; detail and history are cited
  by one stable pointer (spec-section anchor, decision ID, ticket ID,
  or `docs/plan/...` path — engineering-rules §11), never retold. A
  ticket ID is a frozen-premise pointer: in code comments only for
  business weight or to resolve a ticket-internal reference — pure
  provenance stays in the commit message.
  Collect suggested renames (methods, variables, parameters, fields,
  classes) — they land in the commit body under `Renames:`. Before the
  verify, self-check the comment cap: `git diff $(git merge-base main HEAD)
  > .scratch/tick-cap-<ID>.diff && python3 scripts/tick-comment-cap.py
  .scratch/tick-cap-<ID>.diff` — fix any run of more than 3 consecutive
  added comment lines now; the reviewer FAILs what you leave.
- **Spec edits land by approval.** If the diff will touch `docs/spec/**`,
  present the exact proposed text with plain-English reasoning and wait
  for the user's explicit yes BEFORE editing (engineering-rules §12) —
  even when `acceptance:` lists the amendment. New spec prose is
  rule-text only: no dates, ticket IDs, or report citations.
- **Verify.** `mkdir -p .scratch && scripts/verify-serialized.sh > .scratch/tick-test-<ID>-r<round>.log 2>&1 ; ec=$? ; mkdir -p target && cp .scratch/tick-test-<ID>-r<round>.log target/tick-test-<ID>-r<round>.log ; exit $ec`
  from the repo root (full suite; the round number matches the upcoming
  review round). A red `mvn verify` from your own diff is fixed in-band;
  a failure suggesting the ticket's premise is wrong is a hurdle.

Regenerate `STATUS-TICK.md` after the status change
(`scripts/regen-status.py 'docs/plan/m1/tick-tickets/M1-*.md' docs/plan/m1/STATUS-TICK.md`).
