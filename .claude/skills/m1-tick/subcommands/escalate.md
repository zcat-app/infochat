# /m1-tick escalate

Set the ticket to `escalated`, append an escalation entry, and print the six-way menu so the user picks the resolution (refine / override / decompose / defer / spec-amend / abandon). Invocation: `escalate <id> [reason]`.

Reasons (auto-set by `review`/`start` or passed explicitly):

- `round-cap` — the reviewer returned non-APPROVE at the round cap (round 2 by default, round 3 for `round_cap: 3`). (Must-shrink no longer forces an early exit — it is advisory as of the 2026-07-19 cutover.)
- `manual-verdict` — reviewer returned MANUAL.
- `outline-fail` — plan-writer subagent returned `OUTLINE FAILED` during `/m1-tick start` (only reachable for `complexity: high` tickets). The plan-writer subagent is defined at `.claude/agents/plan-writer.md`. (There is no `clarity-fail` reason: the ticket-readiness lint is a `start` precondition, not an escalation — a lint BLOCKER refuses the start and the ticket stays `pending`; see [`start.md`](start.md) step 1.)
- `budget-breach` — the ticket's declared scope is genuinely too narrow: the developer is about to touch a path outside `files_scope` (a hard SCOPE-DRIFT boundary) that the ticket really does need, so the scope must be widened via refine. (A mere `files_budget` file-count overage is advisory as of the 2026-07-19 cutover and does NOT warrant this trigger — only a genuine `files_scope`/`out_of_scope` boundary that needs to move does.)
- `premise-fail` — tests fail in a way that suggests the ticket's premise is wrong.
- `loop` — two consecutive failures with the same root cause.
- `redteam-finding` — [`/redteam`](../../redteam/SKILL.md) returned non-CLEAN and the user opened the lifecycle escalation for the affected ticket. **REFUSED if the operand ticket has `status: done`** — done commits are immutable (per the §M1 workflow rules section in this skill's SKILL.md, "never amend a passed commit"). The redteam SKILL prints the alternative recommendation in this case: draft a new remediation ticket with `remediates: <done-id>` pointing back at the done ticket, then run `/m1-tick start <new-id>`. The done ticket's `redteam_findings:` is still populated for traceability.

Steps:

1. Set ticket frontmatter `status: escalated`. Update `last_updated`. Set the **`escalation_reason:` scalar** — the single durable field the two functional readers below consult (the override-eligibility gate in step 5 arm 2, and the refine-arm dispatch in step 5 arm 1):
   ```yaml
   escalation_reason: <one of the reasons above>   # round-cap | manual-verdict | outline-fail | budget-breach | premise-fail | loop | redteam-finding
   ```
   `escalation_reason` is a **scalar state field**, not a history log — it holds the reason of the *currently open* escalation and is cleared when the escalation resolves (step 5). This is the M1-662 resolution: `escalations:` and `revisions:` are **not** frontmatter (the escalation/refine *history* lives in git commit messages — `M1-NNN: refine ticket spec (<reason>-rework)` — per `docs/process/workflow.md` §"Process doctrine" point 5), but the readers need the *current* reason at escalate time, before any refine commit exists, so it is stored as one scalar rather than reconstructed from git. The relevant reviewer verdict, when one exists, is already the most-recent `reviews:` entry — do not duplicate it here.
2. Regenerate `STATUS.md`.
3. Print the six-way menu (in chat — the user picks). The "trigger context" block adapts based on reason:
   - `round-cap` / `manual-verdict` → the verbatim verdict from the most recent `reviews:` entry.
   - `outline-fail` → the verbatim `## OUTLINE FAILED` block appended to the ticket body by `start`.
   - `redteam-finding` → the verbatim relevant entries from `redteam_findings:`.
   - `budget-breach` / `premise-fail` / `loop` → a one-line description from the developer ("about to touch file X outside files_budget", "test Y fails because spec invariant Z is violated", "second failure on root cause W").

```
M1-NNN: <title>  —  ESCALATED
Trigger: <reason>

Trigger context:
  <verbatim block per the table above; "N/A" only as a last resort>

Choose:
  1. refine     — acceptance criteria were ambiguous; rewrite the ticket
  2. override   — reviewer was too strict; record the override and approve
                  (NOT applicable to outline-fail, premise-fail,
                   budget-breach, loop, or redteam-finding)
  3. decompose  — split into N tickets; queue replacements. This ticket →
                  abandoned (fully replaced) or deferred (retains integration work)
  4. defer      — block on a new ticket the work surfaced; pause this one (deferred)
  5. spec-amend — the spec itself is wrong; raise an amendment ticket. This ticket →
                  deferred (reopens after) or abandoned (amendment obsoletes it)
  6. abandon    — decided against; the work will not be built and no split/amendment
                  carries it. This ticket → abandoned (terminal, not reopenable)

Reply with: <number> [optional notes]
```

4. STOP. Wait for the user's reply. The skill does not auto-proceed past escalation.

5. On user reply, dispatch:

   - **`1` (refine).** Print the path of the ticket file and the relevant trigger context (the `## OUTLINE FAILED` block, reviewer's last verdict, etc.); ask the user to edit the file directly and reply `done` when finished. The skill does NOT accept inline chat-format edits — file-edit + `done` is the single supported input mode (avoids the ambiguity of parsing free-form chat replies into YAML frontmatter and body sections). The refine is captured by the refine *commit* the arms below make (`M1-NNN: refine ticket spec (<reason>-rework)`) — git log is the audit trail, so there is no `revisions:` frontmatter snapshot. When the user replies `done`, re-read the file and dispatch on the **`escalation_reason:` scalar** (set in step 1; it survives a session resume because it is committed on disk with the escalated ticket):

     - **Refine after `outline-fail`** (branch was created at `start` step 4, but the plan-writer subagent returned `OUTLINE FAILED` before any implementation, so the branch is *expected* to have no commits beyond `main`): set `status: pending`. Clear `clarity_check:` (the readiness pre-flight passed for the old ticket; the rewritten ticket needs a fresh evaluation). **Verify-then-clean-up the empty branch.** Resolve the branch name per the branch resolution procedure in [`workflow.md`](../../../../docs/process/workflow.md). Run `git rev-list --count main..m1/M1-NNN-<slug>` to count commits on the branch beyond `main`:
       - If `0` (the canonical path): `git checkout main`, then `git branch -D m1/M1-NNN-<slug>` is safe.
       - If `>0` (the user manually committed on the branch between `OUTLINE FAILED` and choosing refine): **refuse**. Print:
         ```
         Branch m1/M1-NNN-<slug> has <n> commits beyond main:
           <git log --oneline main..m1/M1-NNN-<slug>>
         These would be silently discarded by branch deletion. Outline-fail
         refine assumes the branch is empty (plan-writer subagent failed before
         implementation). Either:
           - /m1-tick abort M1-NNN (explicit destructive path with archival)
           - drop the extra commits manually (git checkout, git reset) before
             re-running /m1-tick escalate M1-NNN outline-fail
         ```
         and STOP without writing the refine commit. The user re-issues a different resolution.

       After successful deletion, commit the refined ticket file on `main` as `M1-NNN: refine ticket spec (outline-fail rework)`. Tell the user to run `/m1-tick start M1-NNN` again — the next `start` will re-create a fresh branch and re-run the ticket-readiness pre-flight AND the plan-writer subagent against the rewritten ticket. Without this branch deletion, the next `start`'s `git checkout -b m1/M1-NNN-<slug>` would fail.

       **A re-run of Plan against the refined ticket is mandatory before implementation.** The path to that re-run is `/m1-tick start M1-NNN`, which re-spawns Plan in fresh context and produces a new OUTLINE block (or a new OUTLINE FAILED). Plan's prior `OUTLINE FAILED` is **not exhaustive** — its `### Audit coverage` enumeration names which dimensions it audited; dimensions marked `not audited` (or any dimension whose evidence depended on the now-changed acceptance text) may still hide blockers. The refinement only fixed what round-1 Plan named; it cannot prove the refined ticket implementable. Print a one-line reminder: `Re-run Plan via /m1-tick start M1-NNN — refining only fixes named blockers; a fresh Plan pass may surface new ones the prior pass did not audit.` Do NOT instruct the user to manually flip `status` to `in-progress` — that path skips Plan and is forbidden by the [`SKILL.md`](../SKILL.md) cross-cutting rules.
     - **Refine after `round-cap`, `manual-verdict`, `budget-breach`, `premise-fail`, `loop`, or `redteam-finding`** (branch exists, implementation is in progress or complete): set `status: in-progress`. **Commit the refine on the per-ticket branch immediately.** Stage the ticket file (and only the ticket file): `git add docs/plan/m1/tickets/M1-NNN-<slug>.md`. Commit subject: `M1-NNN: refine ticket spec (round <r> rework)` where `<r>` is the round number that just escalated. This makes the refine durable mid-attempt — a subsequent `git checkout` (e.g. via `abort`) will not silently lose the refined acceptance criteria. Remind the developer to re-implement against the new criteria on the existing branch. The ticket-readiness pre-flight does NOT re-run on refine in this arm (the criteria are new but the implementation context — branch, prior diff, prior `mvn verify` — is preserved); WARN if the refined ticket would trip a lint BLOCKER.

     Both refine arms clear the `escalation_reason:` scalar as part of the edit (the escalation is resolved). The refine *commit* each arm makes is the audit trail — there is no `revisions:` frontmatter snapshot.

     **Cross-worktree variant for the refine-to-`main` arm (outline-fail).** When the session runs inside a per-ticket worktree, the `git checkout main` this arm assumes is impossible — `main` is checked out in the primary and git refuses. Instead: commit the refined ticket file on the current (empty) per-ticket branch, fast-forward the primary's `main` — `git -C <main-host> merge --ff-only m1/M1-NNN-<slug>` (resolve `<main-host>` via `git worktree list` as in [`merge.md`](merge.md) step 2; the branch carries exactly the one refine commit beyond `main`, so `--ff-only` succeeds) — then `git checkout --detach` to free the branch ref while keeping the refined ticket on disk in this worktree, and finally `git branch -D m1/M1-NNN-<slug>`. If `main` has moved since the fork, `--ff-only` refuses — fall back to `git -C <main-host> checkout m1/M1-NNN-<slug> -- docs/plan/m1/tickets/M1-NNN-<slug>.md` and commit on the host (the defer-landing pattern, M1-224). (Observed: M1-125, 2026-06-02.)

     **Cross-worktree variant for the mid-implementation arms (`round-cap`, `manual-verdict`, `budget-breach`, `premise-fail`, `loop`, `redteam-finding`).** The line-79 `git add` + commit assumes the controlling session sits *inside* the per-ticket worktree, so the stage+commit lands on the branch. When instead the session runs in the primary (`main` checked out here) while the implementation lives in a separate per-ticket worktree (the `--parallel` topology, [`merge.md`](merge.md) step 2), do **NOT** edit `main`'s checkout of the ticket file or regenerate `STATUS.md` against `main` — those edits cannot ride the branch and they leave `main`'s working tree dirty, tripping every other session's `merge` clean-tree precondition ([`merge.md`](merge.md) step 8). Instead resolve the worktree via `git worktree list`, apply the refine to the worktree's ticket copy, and commit it there: `git -C <worktree> add docs/plan/m1/tickets/M1-NNN-<slug>.md && git -C <worktree> commit -m 'M1-NNN: refine ticket spec (round <r> rework)'`. Regenerate `STATUS.md` in the worktree, never against `main` — `commit`/`merge` regenerate it on `main` at land time, so a mid-flight regen against `main` is both unnecessary and dirties the shared checkout. (Observed: M1-284, 2026-06-12.)

   - **`2` (override).** **Eligibility gate (run first).** Read the **`escalation_reason:` scalar** (set in step 1). Override is reviewer-judgment-correction only — it applies iff `escalation_reason ∈ {round-cap, manual-verdict}`. For any other reason, refuse:

     ```
     Override is only applicable when a reviewer returned REWORK or MANUAL.
     The current escalation reason is <reason>. Other reasons have proper
     resolutions:
       - outline-fail                → 1 (refine) the ticket
       - budget-breach              → 1 (refine) to widen files_budget
       - premise-fail               → 5 (spec-amend) the wrong invariant
       - loop                       → 1 (refine) or 3 (decompose)
       - redteam-finding            → 1 (refine), 3 (decompose), 4 (defer),
                                      or 5 (spec-amend)
     Pick 1, 3, 4, or 5 instead.
     ```

     Re-print the menu (omitting option 2) and STOP. Do not write `overrides:` or `OVERRIDE-APPROVE`.

     **If eligible, proceed.** Append to ticket frontmatter:
     ```yaml
     overrides:
       - date: <YYYY-MM-DD>
         objection: |
           <verbatim text of the reviewer's REWORK item the user is overriding>
         user_justification: |
           <user's reason from the reply>
     ```
     Append to `reviews:` a synthesized verdict entry distinct from a real APPROVE so the audit trail preserves the difference:
     ```yaml
     reviews:
       - round: <same round number as the overridden REWORK>
         date: <YYYY-MM-DD>
         verdict: OVERRIDE-APPROVE
         checks:
           # carry through the actual checks from the overridden REWORK; they
           # remain FAIL/WARN as the reviewer reported them. The verdict alone
           # carries the override.
         override_ref: <index of the corresponding overrides[] entry>
     ```
     Set `status: in-review` and clear the `escalation_reason:` scalar (the escalation is resolved). The commit precondition accepts `verdict: OVERRIDE-APPROVE` exactly as it accepts `APPROVE`. Proceed to `commit`.

     Override is NOT permitted for `TEST-INTEGRITY-CHECK: FAIL` flowing through `MANUAL` — the user must explicitly acknowledge that they are overriding test integrity, and the override entry must include the literal text `"acknowledging-test-integrity-override"` in `user_justification`.

   - **`3` (decompose).** Ask the user how many replacement tickets and to provide one-line titles for each. Allocate IDs (`M1-AAA`, `M1-BBB`, ...) via the **ID allocation algorithm** below; do NOT ask the user for IDs (manual ID assignment risks collision with deferred or aborted tickets the user has forgotten about). Set the operand's terminal state per whether it retains residual work: ask the user "does the operand keep any integration/assembly work to run after the children ship, or do the children fully replace it?" — **fully replaced** → `status: abandoned` with `abandoned_reason: decomposed` (terminal; the children carry all the work); **retains integration work** (the umbrella pattern) → `status: deferred` with `deferred_reason: decomposed`, to be reopened after the children are `done`. Create N skeleton ticket files from `docs/process/ticket-template.md`, one per allocated ID, each placed under `docs/plan/m1/tickets/M1-AAA-<slug-of-AAA>.md` (and `M1-BBB-...`, etc.). Skeleton frontmatter rules:

     - `id: M1-AAA` (the allocated ID, distinct per skeleton)
     - `title:` from the user-supplied one-liner
     - `status: pending`
     - `created:` today; `last_updated:` today
     - `blocked_by: []` — the parent's `blocked_by` is NOT auto-inherited. Each child starts with no blockers. Ask the user once, after listing the new ticket paths: "Did the parent ticket M<N>-NNN have any `blocked_by` entries that should propagate to all/some/none of the children?" Apply their answer. Inter-skeleton dependencies (one child blocks another) are also the user's call — the skill does not infer them.
     - `decomposed_from: M1-NNN` (the operand)
     - **Sizing fields (`files_budget`, `files_scope`, `complexity`, `risk`, `round_cap`, `security_relevant`, `migration_touch`) are NOT inherited from the parent.** They are left at the template defaults (`files_budget: 8`, `files_scope: []`, `complexity: low`, `risk: low`, `round_cap: 2`, the rest `false`). The skeleton is a *placeholder*; the user must edit each new ticket to set sizing accurately for the smaller scope. The reasoning: a parent decomposed because it was too big; inheriting its sizing onto each child re-creates the original sizing problem distributed.
     - `out_of_scope: []`, `acceptance: []`, `test_plan: { adds: [], preserves: [...] }`, `spec_refs: []`, `decision_refs: []` — the user must fill these in. The ticket-readiness pre-flight blocks `start` until they do: the linter's OUT-OF-SCOPE-PRESENT BLOCKER fires on the empty `out_of_scope`, and the developer self-check catches empty `acceptance`. This is the intended forcing function.
     - All dynamic fields (`reviews`, `overrides`, `aborted_attempts`, `reopens`, `redteam_findings`, `clarity_check`) start empty; `escalation_reason` is unset (no open escalation on a fresh skeleton).

     Print the new ticket paths so the user can flesh them out, plus a one-line reminder: "Each skeleton needs acceptance criteria, sizing, and `out_of_scope` filled in before `/m1-tick start <id>` will pass the readiness pre-flight."

   - **`4` (defer).** Ask the user to name the blocking ticket — either an existing ID (`M1-XXX`) or "draft a new one". If existing, use that ID directly. If new, allocate an ID (`M1-AAA`) via the **ID allocation algorithm** and create the skeleton ticket file at `docs/plan/m1/tickets/M1-AAA-<slug>.md`. Set the operand ticket's `status: deferred`, `deferred_on: <M1-XXX or M1-AAA>`, `deferred_reason: blocked-on-new-ticket`. Print the deferred state and (if newly allocated) the new ticket path.

   - **`5` (spec-amend).** Ask the user which spec section is wrong (path + section heading) and what the amendment should change. Allocate the new amendment ticket's ID (`M1-AAA`) via the **ID allocation algorithm** below. Create the new ticket at `docs/plan/m1/tickets/M1-AAA-<slug>.md` from `docs/process/ticket-template.md`. Skeleton frontmatter rules (named exceptions first, all others fall through to template defaults):

     - `id: M1-AAA` (the allocated ID)
     - `title: "Amend <spec-path> §<section>"`
     - `spec_amend_for: <path>:§<section>`
     - `spec_amend_parent: M1-NNN` (the operand)
     - `acceptance:` derived from the user's amendment description (the new ticket's job is to land the spec change, not the implementation).
     - `status: pending`
     - `created:` today; `last_updated:` today
     - `blocked_by: []` — the parent's `blocked_by` is NOT auto-inherited; an amendment ticket gates only on what it itself needs.
     - **All sizing and policy fields take template defaults** (`files_budget: 8`, `files_scope: []`, `complexity: low`, `risk: low`, `round_cap: 2`, `security_relevant: false`, `migration_touch: false`). Spec amendments are typically small documentation-only changes; if the amendment turns out larger, the user edits these before `start`.
     - `out_of_scope: []`, `test_plan: { adds: [], preserves: [...] }`, `decision_refs: []` — the user fills these in. The empty `out_of_scope` is intentional: the linter's OUT-OF-SCOPE-PRESENT BLOCKER is the forcing function for the user to think about boundaries before `start`.
     - All dynamic fields (`reviews`, `overrides`, `aborted_attempts`, `reopens`, `redteam_findings`, `clarity_check`) start empty; `escalation_reason` is unset. Lineage fields other than `spec_amend_for` and `spec_amend_parent` are unset.

     Set the operand's terminal state per whether the amendment obsoletes it: ask the user "after this amendment lands, is the operand reopened to do the (now-corrected) work, or does the amendment drop the operand's requirement entirely?" — **reopened after** → `status: deferred`, `deferred_on: M1-AAA`, `deferred_reason: spec-amend`; **obsoleted** → `status: abandoned`, `abandoned_reason: obsoleted-by-spec-amend` (record the obsoleting ticket in `deferred_on: M1-AAA` for lineage; terminal). Print the new ticket path and a one-line reminder: "The amendment ticket needs `acceptance` and `out_of_scope` filled in before `/m1-tick start M1-AAA` will pass the readiness pre-flight." Add, for the deferred case only: "The amendment ticket must be `done` before the operand can be reopened."

   - **`6` (abandon).** The ticket is decided against outright — the work will not be built and no split (`decompose`) or amendment (`spec-amend`) carries it. Ask the user for the reason category — `superseded` (the work is absorbed by an existing/other named ticket) or `wont-do-infeasible` (evaluated and judged not worth building / not achievable as framed) — plus a one-line free-text justification and, for `superseded`, the ID(s) that absorb it. Set the operand ticket: `status: abandoned`, `abandoned_reason: <superseded | wont-do-infeasible>`, and (for `superseded`, if IDs were named) record them in `deferred_on:` for lineage. Do NOT delete the branch here — if a per-ticket branch exists (the escalation came mid-implementation), remind the user that `/m1-tick abort M1-NNN` is the destructive path to discard it; `abandon` only records the decision on the ticket. Abandoned is terminal — the driver's `reopen` refuses it; reviving requires a fresh, deliberate decision. Print the abandoned state and the recorded reason.

6. Clear the `escalation_reason:` scalar on the operand whenever the resolution moves it out of `escalated` (every arm except a re-print-and-STOP refusal does). The refine and override arms above already state this explicitly; for `decompose`/`defer`/`spec-amend`/`abandon` the operand moves to `deferred`/`abandoned`, so clear it there too — a resolved escalation must leave no open-reason marker behind.
7. Regenerate `STATUS.md` after the resolution applies.

### ID allocation algorithm

Used by `decompose` and `spec-amend` to allocate fresh ticket IDs.

**ID shape.** A ticket ID has the form `M<N>-<digits>[<suffix>]` where `<digits>` is one or more decimal digits (zero-padded to 3 in canonical form) and the optional `<suffix>` is one or more lowercase ASCII letters (`[a-z]+`). The suffix exists as a manual planning affordance for the **umbrella + subticket** idiom (see `docs/process/workflow.md` §Ticket-ID placeholder convention): a bare `M1-008` is the umbrella ticket holding the topic's shared context and the whole-topic integration test, while `M1-008a`, `M1-008b`, `M1-008c` are independent subtickets implementing one slice each. The umbrella and its subtickets are **distinct independent tickets** that share the digit slot by design; the umbrella's `blocked_by` lists the subtickets so it becomes runnable only after they ship. The skill itself never *generates* suffix-IDs — subticket IDs are authored by hand at planning time. For every other purpose (commit subjects, branch names, file paths, grep history) the ID is an opaque string, so the suffix is transparent to the rest of the workflow.

1. Glob `docs/plan/m1/tickets/M1-*.md` (or for other milestones, the corresponding directory).
2. Parse the `id:` field from every file's frontmatter. Include tickets in EVERY status — pending, in-progress, in-review, escalated, done, deferred, abandoned. IDs of `aborted_attempts:` are NOT separate IDs (they're attempts on existing tickets), so they don't enter this scan.
3. For each ID, extract the digit run as an integer (any optional letter suffix is ignored for slot accounting: `M1-007` → `7`, `M1-008a` → `8`). Take the maximum integer across all IDs.
4. Allocated ID = `M<N>-<max+1>`, zero-padded to 3 digits, no suffix (e.g. `M1-009` after a max of `8`). The skill always allocates primary IDs; suffix-IDs only enter the directory through hand-authored umbrella+subticket splits.
5. For multiple allocations in one operation (decompose into N), allocate sequentially: `M1-<max+1>`, `M1-<max+2>`, etc. — all primary IDs, no suffixes.
6. **IDs are never reused.** A `done` ticket's ID is reserved for that ticket forever; a `deferred` ticket's ID stays with it through reopen; an `abandoned` ticket's ID stays reserved (it is terminal, never revived under the same ID); even an aborted-and-restarted ticket keeps its original ID. To inspect history by ID, use the anchored prefix form to disambiguate umbrella from subtickets: `git log --grep "^M1-008: "` returns only the umbrella's commits, `git log --grep "^M1-008a: "` only that subticket's. The unanchored form `git log --grep "M1-008"` returns commits for the umbrella AND every subticket because `M1-008` is a substring of `M1-008a/b/c` — useful when you want the whole topic's history at once.
