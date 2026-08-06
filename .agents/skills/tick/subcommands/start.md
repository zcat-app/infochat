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
  A genuine ambiguity → one blocking question (never an escalation). Record
  the result under `clarity_check:` in the frontmatter.

## Step 2 — branch

Set `status: in-progress` (frontmatter) and `last_updated`. Create branch
`m<N>/M<N>-NNN-<slug>` off `main`. For `--parallel`: preconditions are
provably-disjoint `files_scope` vs every in-flight ticket (no in-flight
`migration_touch: true`), and the work happens in a git worktree; degrade to
sequential if the tool cannot operate in another working directory. A ticket
without `files_scope` cannot run `--parallel` (nothing to prove disjoint
with).

## Step 3 — implement (execution, not discovery)

- Follow the ticket's Approach: steps in order, files per the plan, controls
  preserved (§10 enumeration). The files-to-touch plan is guidance, not an
  allowlist — but ANY departure from it is a hurdle unless purely mechanical.
- **Hurdle rule.** On any extra hurdle found in the code (a claim in the
  plan that does not hold, a new constraint, a spec conflict, an
  unintended-path discovery): STOP, run `/tick hurdle <id>`, and wait for
  the user's decision. Never drift, never "fold it in", never solve it
  silently.
- **Comment hygiene.** Inside classes the diff touches: remove stale or
  meaningless comments; do NOT add comments that restate the code; add
  comments only for business logic, a non-obvious decision, or a trap.
  Collect suggested renames (methods, variables, parameters, fields,
  classes) — they land in the commit body under `Renames:`.
- **Verify.** `mkdir -p .scratch && scripts/verify-serialized.sh > .scratch/tick-test-<ID>-r<round>.log 2>&1 ; ec=$? ; mkdir -p target && cp .scratch/tick-test-<ID>-r<round>.log target/tick-test-<ID>-r<round>.log ; exit $ec`
  from the repo root (full suite; the round number matches the upcoming
  review round). A red `mvn verify` from your own diff is fixed in-band;
  a failure suggesting the ticket's premise is wrong is a hurdle.

Regenerate `STATUS-TICK.md` after the status change
(`scripts/regen-status.py 'docs/plan/m1/tick-tickets/M1-*.md' docs/plan/m1/STATUS-TICK.md`).
