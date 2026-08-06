---
name: tick
description: Analysis-first ticket flow (successor to /m1-tick). Analyze a problem into deep, spec-grounded, small tickets via the analyst gate; implement = execute the plan (divergence = hurdle report, stop); review = ONE merged gate (spec-truthness + security + test-adequacy + maintainability) with falsification duty; commit/merge like m1-tick. Use when the user invokes `/tick <subcommand>` (analyze | next | start <id> | hurdle <id> | review <id> | commit <id> | merge <id> | status | measure | show <id> | escalate <id> | abort <id> | reopen <id>). Supersedes /m1-tick for new work (/m1-tick is deprecated but still invocable for its existing board); tickets live in docs/plan/<milestone>/tick-tickets/, analyses in tick-analysis/, board STATUS-TICK.md.
---

# /tick — analysis-first ticket flow

The flow spec is [`docs/process/tick-workflow.md`](../../../docs/process/tick-workflow.md) — this skill is the procedure; that document is the rules. If this skill conflicts with it, the spec wins — flag the drift and stop. The ticket schema is [`docs/process/tick-ticket-template.md`](../../../docs/process/tick-ticket-template.md). The engineering rules of record are [`docs/process/engineering-rules-verbatim.md`](../../../docs/process/engineering-rules-verbatim.md) with the tick-flow deltas named in tick-workflow.md §Rules of record.

## Subcommand routing

The user invokes the skill as `/tick <subcommand> [args]`. Each subcommand's full procedure lives in its own file under `.agents/skills/tick/subcommands/`. This router holds only the dispatch table and the cross-cutting rules; per-subcommand wording is the source of truth.

**Dispatch.** When the user invokes `/tick <subcommand> [args]`:

1. Parse the args. Identify the subcommand.
2. Read `.agents/skills/tick/subcommands/<subcommand>.md` fresh.
3. Apply that file's procedure verbatim — never from memory or a stale copy.

| User invocation | Procedure file |
|---|---|
| `/tick analyze <brief>` | [subcommands/analyze.md](subcommands/analyze.md) — mandatory analysis (workflow §0b), run on §0's reproduction: analyst gate turns a brief into an analysis doc + small tickets |
| `/tick next` | [subcommands/next.md](subcommands/next.md) — list runnable tick tickets |
| `/tick start <id>` | [subcommands/start.md](subcommands/start.md) — begin work; lint pre-flight; no plan-writer (the §0 reproduction is the contract) |
| `/tick start <id> --parallel` | [subcommands/start.md](subcommands/start.md) — worktree; needs a different Maven module from every in-flight ticket |
| `/tick hurdle <id>` | [subcommands/hurdle.md](subcommands/hurdle.md) — the implementor's stop-and-report: one of the four hurdle triggers |
| `/tick review <id>` | [subcommands/review.md](subcommands/review.md) — spawn the merged tick-reviewer gate |
| `/tick commit <id>` | [subcommands/commit.md](subcommands/commit.md) — finalize the per-ticket commit |
| `/tick merge <id>` | [subcommands/merge.md](subcommands/merge.md) — squash-merge into main |
| `/tick status` | [subcommands/status.md](subcommands/status.md) — regenerate STATUS-TICK.md |
| `/tick measure` | [subcommands/status.md](subcommands/status.md) §/tick measure — A/B comparison vs the m1 flow |
| `/tick show <id>` | [subcommands/show.md](subcommands/show.md) — read-only inspection |
| `/tick escalate <id> [reason]` | [subcommands/escalate.md](subcommands/escalate.md) — six-way menu for terminal escalations |
| `/tick abort <id>` | [subcommands/abort.md](subcommands/abort.md) — cancel and roll back |
| `/tick reopen <id>` | [subcommands/reopen.md](subcommands/reopen.md) — deferred → pending |

## Cross-cutting rules this skill must obey

- **The analysis is mandatory.** No ticket without `analysis_ref:` — a
  real `tick-analysis/` document for a 2+ ticket decomposition, or `self`
  (the ticket body IS the analysis) for a single-ticket one. `analyze` is
  the only door into `tick-tickets/`.
- **Spec is the contract** for a ticket that changes what the system promises. Implementation never bends the spec; a conflict is a hurdle with the spec-amend option, or the analyst's SPEC-GAP at draft time. A defect ticket is grounded by its reproduction and may carry empty `spec_refs`.
- **Every ticket carries a reproduction.** A failing test (or a probe with its observed output) naming the wrong behavior, run RED before the problem's tickets are filed — the family's reproduction runs BEFORE `analyze`, so the analyst explains evidence rather than predicting unread code (workflow §0). A child of a decomposition may instead carry a `to-be-written` / `parked:` marker, converted at `start`: write or restore the test and run it RED before any fix code. tick-lint's REPRODUCTION-PRESENT BLOCKER resolves named tests in-tree and accepts only those two markers — it cannot attest the RED run itself; that duty stays with the driver.
- **Implementation is execution.** `/tick hurdle` fires on one of four triggers only — premise wrong, another Maven module or another in-flight ticket's file, a spec change, or a dropped control of a replaced path. A better route inside the same behavior is execution, not drift. Comment hygiene inside touched classes is in scope (new rationale ≤3 lines per call site); renames go in the commit body's `Renames:` trailer.
- **One merged gate.** Review = the tick-reviewer agent only. No separate security re-audit loop; findings must survive falsification; critical/high → MANUAL + notify the user; medium/low with named fix → REWORK; all-low comment-only findings with mechanical probes → APPROVE-WITH-FIXES (driver applies the fixes, probes + test-compile verify, no extra round). On rounds ≥ 2 the gate dispositions every one of the previous round's REWORK items (SATISFIED / NOT-ADDRESSED / DECLINED) rather than reading the fix hunks alone.
- **Never push.** Merge is local; push is the user's call.
- **Never skip `mvn verify`** on a testable diff, and never silently reuse a stale log. Capture to `target/tick-test-<ID>-r<round>.log` via `.scratch/` (mvn clean deletes `target/` early — the redirect-through-.scratch pattern is load-bearing). Per-round review artifacts live in `.scratch/` only, for the same reason.
- **Never amend a passed commit.** Defects after APPROVE → new ticket → new commit.
- **Never edit `STATUS-TICK.md` by hand.** Regenerate from frontmatter.
- **Never create a ticket file without explicit user confirmation** (repo rule; `analyze` presents the set for approval before anything lands).
- **No shortcuts** (`--no-verify`, `-DskipTests`, `git reset --hard`). Hurdle or escalate instead.
- **Gate hygiene.** Gate agents run fresh-context; use absolute paths in the stub and every rendered placeholder (harness-mapping §6.1(d)); after each gate, run `git status --porcelain` and confirm only the expected artifact changed.
- **Sweep background work before reporting.** Before reporting a gate result or declaring any step complete, enumerate the session's outstanding background jobs and reap or report each. Never poll a build with `pgrep -f` — the pattern matches its own command line and spins forever; use the harness's task-completion notification and the captured log.
- **Two entry points, one procedure — split by discovery surface, not by product.** This file is the router for every harness that discovers skills under `.agents/skills/`; `.claude/skills/tick/SKILL.md` is Claude Code's. Both dispatch into the same `.agents/skills/tick/subcommands/` files, which are the single source of truth. A change to a subcommand needs no counterpart edit; a change to a ROUTER (dispatch table, cross-cutting rules) must be made in both. Which products read which surface, and how each spawns a gate agent, is `docs/process/harness-mapping.md` — the only document that names tools.
