# /m1-tick escalate

Set the ticket to `escalated`, append an escalation entry, and print the five-way menu so the user picks the resolution (refine / override / decompose / defer / spec-amend). Invocation: `escalate <id> [reason]`.

Reasons (auto-set by `review`/`start` or passed explicitly):

- `round-cap` — round-cap returned non-APPROVE, or a must-shrink violation forced an early exit from the rework loop.
- `manual-verdict` — reviewer returned MANUAL.
- `clarity-fail` — clarity pre-flight returned FAIL during `/m1-tick start`.
- `outline-fail` — plan-writer subagent returned `OUTLINE FAILED` during `/m1-tick start` (only reachable for `complexity: high` tickets). The plan-writer subagent is defined at `.claude/agents/plan-writer.md`.
- `budget-breach` — developer is about to exceed `files_budget`.
- `premise-fail` — tests fail in a way that suggests the ticket's premise is wrong.
- `loop` — two consecutive failures with the same root cause.
- `redteam-finding` — [`/redteam`](../../redteam/SKILL.md) returned non-CLEAN and the user opened the lifecycle escalation for the affected ticket. **REFUSED if the operand ticket has `status: done`** — done commits are immutable (per the §M1 workflow rules section in this skill's SKILL.md, "never amend a passed commit"). The redteam SKILL prints the alternative recommendation in this case: draft a new remediation ticket with `remediates: <done-id>` pointing back at the done ticket, then run `/m1-tick start <new-id>`. The done ticket's `redteam_findings:` is still populated for traceability.

Steps:

1. Set ticket frontmatter `status: escalated`. Update `last_updated`. Append:
   ```yaml
   escalations:
     - date: <YYYY-MM-DD>
       reason: <one of the above>
       reviewer_verdict_excerpt: |
         <the relevant verbatim block from the most recent review,
          or "N/A" if escalation is from budget-breach/loop/premise-fail>
   ```
2. Regenerate `STATUS.md`.
3. Print the five-way menu (in chat — the user picks). The "trigger context" block adapts based on reason:
   - `round-cap` / `manual-verdict` → the verbatim verdict from the most recent `reviews:` entry.
   - `clarity-fail` → the verbatim `clarity_check.blockers:` list.
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
                  (NOT applicable to clarity-fail, outline-fail, premise-fail,
                   budget-breach, loop, or redteam-finding)
  3. decompose  — split into N tickets; defer this one and queue replacements
  4. defer      — block on a new ticket the work surfaced; pause this one
  5. spec-amend — the spec itself is wrong; raise an amendment ticket and pause

Reply with: <number> [optional notes]
```

4. STOP. Wait for the user's reply. The skill does not auto-proceed past escalation.

5. On user reply, dispatch:

   - **`1` (refine).** Snapshot current frontmatter under `revisions:` with date + reason. Print the path of the ticket file and the relevant trigger context (clarity blockers, reviewer's last verdict, etc.); ask the user to edit the file directly and reply `done` when finished. The skill does NOT accept inline chat-format edits — file-edit + `done` is the single supported input mode (avoids the ambiguity of parsing free-form chat replies into YAML frontmatter and body sections). When the user replies `done`, re-read the file, verify the snapshot under `revisions:` is still present, and dispatch on the *prior* escalation reason (read from the most recent `escalations:` entry):

     - **Refine after `clarity-fail`** (no branch ever existed; the ticket never reached `in-progress`): set `status: pending`. Clear `clarity_check:` (it described the *old* ticket; the rewritten ticket needs a fresh evaluation). The refined ticket lives on `main`; while currently on `main`, commit the edit there as `M1-NNN: refine ticket spec (clarity-fail rework)`. Tell the user to run `/m1-tick start M1-NNN` again — the next `start` will re-run clarity against the rewritten ticket, which is the correct behavior because the previous FAIL means the ticket was never validated.
     - **Refine after `outline-fail`** (branch was created at `start` step 6, but the plan-writer subagent returned `OUTLINE FAILED` before any implementation, so the branch is *expected* to have no commits beyond `main`): set `status: pending`. Clear `clarity_check:` (clarity passed for the old ticket; the rewritten ticket needs a fresh evaluation). **Verify-then-clean-up the empty branch.** Resolve the branch name per the branch resolution procedure in [`workflow.md`](../../../../docs/process/workflow.md). Run `git rev-list --count main..m1/M1-NNN-<slug>` to count commits on the branch beyond `main`:
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

       After successful deletion, commit the refined ticket file on `main` as `M1-NNN: refine ticket spec (outline-fail rework)`. Tell the user to run `/m1-tick start M1-NNN` again — the next `start` will re-create a fresh branch and re-run clarity AND the plan-writer subagent against the rewritten ticket. Without this branch deletion, the next `start`'s `git checkout -b m1/M1-NNN-<slug>` would fail.

       **A re-run of Plan against the refined ticket is mandatory before implementation.** The path to that re-run is `/m1-tick start M1-NNN`, which re-spawns Plan in fresh context and produces a new OUTLINE block (or a new OUTLINE FAILED). Plan's prior `OUTLINE FAILED` is **not exhaustive** — its `### Audit coverage` enumeration names which dimensions it audited; dimensions marked `not audited` (or any dimension whose evidence depended on the now-changed acceptance text) may still hide blockers. The refinement only fixed what round-1 Plan named; it cannot prove the refined ticket implementable. Print a one-line reminder: `Re-run Plan via /m1-tick start M1-NNN — refining only fixes named blockers; a fresh Plan pass may surface new ones the prior pass did not audit.` Do NOT instruct the user to manually flip `status` to `in-progress` — that path skips Plan and is forbidden by the [`SKILL.md`](../SKILL.md) cross-cutting rules.
     - **Refine after `round-cap`, `manual-verdict`, `budget-breach`, `premise-fail`, `loop`, or `redteam-finding`** (branch exists, implementation is in progress or complete): set `status: in-progress`. **Commit the refine on the per-ticket branch immediately.** Stage the ticket file (and only the ticket file): `git add docs/plan/m1/tickets/M1-NNN-<slug>.md`. Commit subject: `M1-NNN: refine ticket spec (round <r> rework)` where `<r>` is the round number that just escalated. This makes the refine durable mid-attempt — a subsequent `git checkout` (e.g. via `abort`) will not silently lose the refined acceptance criteria. Remind the developer to re-implement against the new criteria on the existing branch. The clarity pre-flight does NOT re-run on refine in this arm (the criteria are new but the implementation context — branch, prior diff, prior `mvn verify` — is preserved); WARN if the refined ticket would have failed clarity.

     All three arms preserve the snapshot under `revisions:` for the audit trail.

   - **`2` (override).** **Eligibility gate (run first).** Read the most recent entry in `escalations:` and inspect its `reason`. Override is reviewer-judgment-correction only — it applies iff `reason ∈ {round-cap, manual-verdict}`. For any other reason, refuse:

     ```
     Override is only applicable when a reviewer returned REWORK or MANUAL.
     The current escalation reason is <reason>. Other reasons have proper
     resolutions:
       - clarity-fail / outline-fail → 1 (refine) the ticket
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
     Set `status: in-review`. The commit precondition accepts `verdict: OVERRIDE-APPROVE` exactly as it accepts `APPROVE`. Proceed to `commit`.

     Override is NOT permitted for `TEST-INTEGRITY-CHECK: FAIL` flowing through `MANUAL` — the user must explicitly acknowledge that they are overriding test integrity, and the override entry must include the literal text `"acknowledging-test-integrity-override"` in `user_justification`.

   - **`3` (decompose).** Ask the user how many replacement tickets and to provide one-line titles for each. Allocate IDs (`M1-AAA`, `M1-BBB`, ...) via the **ID allocation algorithm** below; do NOT ask the user for IDs (manual ID assignment risks collision with deferred or aborted tickets the user has forgotten about). Set the operand ticket's `status: deferred` with `deferred_reason: decomposed`. Create N skeleton ticket files from `docs/process/ticket-template.md`, one per allocated ID, each placed under `docs/plan/m1/tickets/M1-AAA-<slug-of-AAA>.md` (and `M1-BBB-...`, etc.). Skeleton frontmatter rules:

     - `id: M1-AAA` (the allocated ID, distinct per skeleton)
     - `title:` from the user-supplied one-liner
     - `status: pending`
     - `created:` today; `last_updated:` today
     - `blocked_by: []` — the parent's `blocked_by` is NOT auto-inherited. Each child starts with no blockers. Ask the user once, after listing the new ticket paths: "Did the parent ticket M<N>-NNN have any `blocked_by` entries that should propagate to all/some/none of the children?" Apply their answer. Inter-skeleton dependencies (one child blocks another) are also the user's call — the skill does not infer them.
     - `decomposed_from: M1-NNN` (the operand)
     - **Sizing fields (`files_budget`, `files_scope`, `complexity`, `risk`, `round_cap`, `security_relevant`, `migration_touch`) are NOT inherited from the parent.** They are left at the template defaults (`files_budget: 8`, `files_scope: []`, `complexity: low`, `risk: low`, `round_cap: 2`, the rest `false`). The skeleton is a *placeholder*; the user must edit each new ticket to set sizing accurately for the smaller scope. The reasoning: a parent decomposed because it was too big; inheriting its sizing onto each child re-creates the original sizing problem distributed.
     - `out_of_scope: []`, `acceptance: []`, `test_plan: { adds: [], preserves: [...] }`, `spec_refs: []`, `decision_refs: []` — the user must fill these in. The clarity pre-flight will FAIL on `start` until they do, which is the intended forcing function.
     - All dynamic fields (`reviews`, `escalations`, `revisions`, `overrides`, `aborted_attempts`, `reopens`, `redteam_findings`, `clarity_check`) start empty.

     Print the new ticket paths so the user can flesh them out, plus a one-line reminder: "Each skeleton needs acceptance criteria, sizing, and `out_of_scope` filled in before `/m1-tick start <id>` will pass clarity."

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
     - `out_of_scope: []`, `test_plan: { adds: [], preserves: [...] }`, `decision_refs: []` — the user fills these in. The empty `out_of_scope` is intentional: clarity-FAIL is the forcing function for the user to think about boundaries before `start`.
     - All dynamic fields (`reviews`, `escalations`, `revisions`, `overrides`, `aborted_attempts`, `reopens`, `redteam_findings`, `clarity_check`) start empty. Lineage fields other than `spec_amend_for` and `spec_amend_parent` are unset.

     Set the operand ticket: `status: deferred`, `deferred_on: M1-AAA`, `deferred_reason: spec-amend`. Print the new ticket path and a one-line reminder: "The amendment ticket needs `acceptance` and `out_of_scope` filled in before `/m1-tick start M1-AAA` will pass clarity. The amendment ticket must be `done` before the operand can be reopened."

6. Regenerate `STATUS.md` after the resolution applies.

### ID allocation algorithm

Used by `decompose` and `spec-amend` to allocate fresh ticket IDs.

**ID shape.** A ticket ID has the form `M<N>-<digits>[<suffix>]` where `<digits>` is one or more decimal digits (zero-padded to 3 in canonical form) and the optional `<suffix>` is one or more lowercase ASCII letters (`[a-z]+`). The suffix exists as a manual planning affordance for the **umbrella + subticket** idiom (see `docs/process/workflow.md` §Ticket-ID placeholder convention): a bare `M1-008` is the umbrella ticket holding the topic's shared context and the whole-topic integration test, while `M1-008a`, `M1-008b`, `M1-008c` are independent subtickets implementing one slice each. The umbrella and its subtickets are **distinct independent tickets** that share the digit slot by design; the umbrella's `blocked_by` lists the subtickets so it becomes runnable only after they ship. The skill itself never *generates* suffix-IDs — subticket IDs are authored by hand at planning time. For every other purpose (commit subjects, branch names, file paths, grep history) the ID is an opaque string, so the suffix is transparent to the rest of the workflow.

1. Glob `docs/plan/m1/tickets/M1-*.md` (or for other milestones, the corresponding directory).
2. Parse the `id:` field from every file's frontmatter. Include tickets in EVERY status — pending, in-progress, in-review, escalated, done, deferred. IDs of `aborted_attempts:` are NOT separate IDs (they're attempts on existing tickets), so they don't enter this scan.
3. For each ID, extract the digit run as an integer (any optional letter suffix is ignored for slot accounting: `M1-007` → `7`, `M1-008a` → `8`). Take the maximum integer across all IDs.
4. Allocated ID = `M<N>-<max+1>`, zero-padded to 3 digits, no suffix (e.g. `M1-009` after a max of `8`). The skill always allocates primary IDs; suffix-IDs only enter the directory through hand-authored umbrella+subticket splits.
5. For multiple allocations in one operation (decompose into N), allocate sequentially: `M1-<max+1>`, `M1-<max+2>`, etc. — all primary IDs, no suffixes.
6. **IDs are never reused.** A `done` ticket's ID is reserved for that ticket forever; a `deferred` ticket's ID stays with it through reopen; even an aborted-and-restarted ticket keeps its original ID. To inspect history by ID, use the anchored prefix form to disambiguate umbrella from subtickets: `git log --grep "^M1-008: "` returns only the umbrella's commits, `git log --grep "^M1-008a: "` only that subticket's. The unanchored form `git log --grep "M1-008"` returns commits for the umbrella AND every subticket because `M1-008` is a substring of `M1-008a/b/c` — useful when you want the whole topic's history at once.
