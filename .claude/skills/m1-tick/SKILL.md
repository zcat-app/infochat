---
name: m1-tick
description: Drive the M1 ticket workflow — pick the next runnable ticket, start work on a branch, run review via the code-reviewer subagent, commit on approval, or surface the five-way escalation menu when a round cap or trigger fires. Use when the user invokes `/m1-tick <subcommand>` (next | start <id> | review <id> | commit <id> | escalate <id> | abort <id> | show <id> | reopen <id> | status). For adversarial security review, see the separate `/redteam` skill. The universal workflow specification is in `docs/process/workflow.md`; M1-specific framing is in `docs/plan/m1/README.md`; the engineering rules are in `CLAUDE.md` §Engineering rules + §M1 workflow + `docs/process/engineering-rules-verbatim.md` — those are the source of truth; this skill is the procedure that applies them.
---

# /m1-tick — M1 ticket workflow

This skill is the procedure. The rules live in `CLAUDE.md` §Engineering rules + §M1 workflow and verbatim in [`engineering-rules-verbatim.md`](../../../docs/process/engineering-rules-verbatim.md). The universal workflow specification is [`docs/process/workflow.md`](../../../docs/process/workflow.md); M1-specific framing is [`docs/plan/m1/README.md`](../../../docs/plan/m1/README.md). If this skill conflicts with any of those, those win — flag the drift and stop.

Adversarial security review (formerly `/m1-tick redteam`) is now its own skill: [`/redteam`](../redteam/SKILL.md). The two skills are intentionally decoupled — redteam findings reach this workflow only via the user invoking `/m1-tick escalate <id> redteam-finding`.

## Subcommand routing

The user invokes the skill as `/m1-tick <subcommand> [args]`. Parse the args verbatim and dispatch:

| Args | Subcommand |
|---|---|
| `next` (or empty) | `next` — list runnable tickets |
| `start <id>` | `start` — begin work on a ticket |
| `start <id> --parallel` | `start --parallel` — start in a worktree |
| `review <id>` | `review` — spawn reviewer subagent |
| `commit <id>` | `commit` — finalize the per-ticket commit |
| `escalate <id> [reason]` | `escalate` — fire the five-way menu |
| `abort <id>` | `abort` — cancel an in-progress ticket and roll back |
| `show <id>` | `show` — read-only inspection of a ticket |
| `reopen <id>` | `reopen` — bring a deferred ticket back to pending |
| `status` | `status` — regenerate STATUS.md and print summary |

If the args don't match, print the table above and stop. For `redteam`, point the user at the separate [`/redteam`](../redteam/SKILL.md) skill.

---

## `next` — list runnable tickets

1. Read every file under `docs/plan/m1/tickets/M1-*.md`.
2. Parse YAML frontmatter from each.
3. A ticket is **runnable** iff `status: pending` AND every entry in `blocked_by` resolves to a ticket with `status: done`.
4. Sort runnable tickets by ID ascending.
5. Print:

```
Runnable now (N tickets):
  M1-NNN  <title>                     (complexity: <c>, risk: <r>, files_budget: <n>)
  M1-NNN  <title>                     ...

Currently in-progress: <id-or-none>
Currently in-review:   <id-or-none>
Currently escalated:   <id-or-none>

To start: /m1-tick start M1-NNN
```

If something is `in-progress` or `escalated`, recommend resolving it before starting another ticket (unless `--parallel` is intended).

---

## `start <id> [--parallel]`

Preconditions (refuse and explain if any fail):

- The ticket exists and `status: pending`.
- All `blocked_by` are `status: done`.
- The working tree is clean (`git status` shows no uncommitted changes), unless `--parallel` is set.
- If not `--parallel`: there is no other ticket with `status: in-progress` or `status: in-review`.
- If `--parallel`:
  - The new ticket and every in-flight ticket BOTH declare a non-empty `files_scope`, AND the new ticket's `files_scope` and `out_of_scope` entries are provably disjoint from every in-flight ticket's. Tickets without `files_scope` (purely numeric `files_budget`) cannot start in parallel — the skill cannot mechanically prove disjointness without a path list.
  - Neither the new ticket nor any in-flight ticket has `migration_touch: true`.

Steps:

1. **Ticket-clarity pre-flight.** Read `docs/process/clarity-prompt.md`. Resolve every `spec_refs` entry per the algorithm below; build the `{{SPEC_REF_RESOLUTIONS}}` block. Substitute placeholders. Spawn `Agent(subagent_type: "clarity-reviewer", prompt: <substituted>, description: "Clarity pre-flight M1-NNN")`. Foreground.

   **`spec_refs` anchor resolution algorithm.** For each `spec_refs` entry of the form `<file-path> §<section-title>`:
   1. Read `<file-path>`.
   2. Find every line beginning with `#`-markers (`#`, `##`, `###`, etc.).
   3. Strip the `#`-markers and surrounding whitespace from each candidate heading.
   4. Lowercase both the candidate and the searched section-title; do a substring match (the searched title must appear as a substring of the candidate, or vice-versa for partial titles).
   5. If exactly one heading matches, the resolution is `FOUND (line N: "<heading>")`.
   6. If zero match, the resolution is `ANCHOR-NOT-FOUND`.
   7. If multiple match, prefer the heading whose depth (count of `#` markers) is closest to the most recently resolved anchor's depth; tie-break by line number ascending. If still tied, the resolution is `AMBIGUOUS (lines: N, M, ...)` — the clarity reviewer treats AMBIGUOUS as FAIL.
2. Parse the clarity verdict. Append to ticket frontmatter:
   ```yaml
   clarity_check:
     date: <YYYY-MM-DD>
     verdict: <PASS | WARN | FAIL>
     warnings: [<warning-strings>]
     blockers: [<blocker-strings if FAIL>]
   ```
3. Branch on clarity verdict:
   - `PASS` → continue.
   - `WARN` → print warnings; continue.
   - `FAIL` → print blockers; refuse the start; fire `escalate` with `reason: clarity-fail` (this sets status to `escalated` and prints the five-way menu with `clarity_check.blockers:` as the trigger context). Status does NOT pass through `in-progress`.
4. Set the ticket's frontmatter `status: in-progress`. Update `last_updated` to today's date.
5. Compute slug from the title per the canonical rule in [`docs/process/workflow.md`](../../../docs/process/workflow.md) §"Naming conventions (slug, branch, ticket file)".
6. Branch:
   - **Sequential:** `git checkout -b m1/M1-NNN-<slug>` from `main`.
   - **Parallel:** create a worktree via `Agent(isolation: "worktree")` and run the rest of the flow inside it.
7. If `complexity: high`: read `docs/process/plan-prompt.md`, substitute `{{TICKET_ID}}`, `{{TICKET_FILE_CONTENT}}`, and the same `{{SPEC_REF_RESOLUTIONS}}` block built for clarity. Spawn `Agent(subagent_type: "Plan", prompt: <substituted>, description: "Implementation outline M1-NNN")`. Foreground.
   - If the response begins with `## OUTLINE FAILED`, append the OUTLINE FAILED block to the ticket body and fire `escalate` with `reason: outline-fail`. The branch created in step 6 is left in place (it'll be deleted by `abort` if the user chooses to abandon, or reused after `refine`).
   - Otherwise, append the outline to the ticket body under a new `## Implementation outline` section. The developer (the main conversation) reads it before touching code.
8. Regenerate `STATUS.md`.
9. Print:

```
Started M1-NNN on branch m1/M1-NNN-<slug>.
Clarity pre-flight: <PASS | WARN with N warnings>
files_budget: <n>; out_of_scope: <list>; round_cap: <n>.
Implement the ticket per its Definition of Done, then run `mvn verify`,
then `/m1-tick review M1-NNN`.
```

After this point, the main conversation is the developer. Implementation happens in normal Edit/Write/Bash calls. Do NOT spawn a developer-subagent.

---

## `review <id>`

Preconditions:

- The ticket exists and `status: in-progress` (or `status: in-review` if this is a re-review after rework).
- The current branch matches `m1/M1-NNN-<slug>`.
- `mvn verify` was run after the most recent edit and exited zero. (Re-run it if uncertain — the reviewer requires fresh test output.)

Steps:

1. Determine round number from `reviews:` length + 1.
2. Capture inputs:
   - `git diff main...HEAD` (full diff).
   - Diff stats: files touched, net lines added, net lines removed. (For round 2, also surface round-1 diff stats from frontmatter.)
   - Build the negative-space list: if the ticket has a non-empty `files_scope`, take the union of paths matched by those globs minus paths actually present in the diff. If `files_scope` is empty or absent, the negative-space list is the literal sentinel string `(no path-level scope declared — files_budget is purely numeric, no negative-space evaluation applicable)` and the reviewer reports PASS on `NEGATIVE-SPACE-CHECK` by definition.
   - The tail of the most recent `mvn verify` output (last ~200 lines; full log persisted to `target/m1-tick-test-{{ID}}-r{{ROUND}}.log`).
   - The ticket file content.
3. Read `docs/process/reviewer-prompt.md` and substitute placeholders (`{{TICKET_ID}}`, `{{TICKET_FILE_CONTENT}}`, `{{DIFF_OUTPUT}}`, `{{R1_FILES}}`, `{{R1_ADDED}}`, `{{R1_REMOVED}}`, `{{R2_FILES}}`, `{{R2_ADDED}}`, `{{R2_REMOVED}}`, `{{NEGATIVE_SPACE_LIST}}`, `{{TEST_OUTPUT_TAIL}}`, `{{TEST_LOG_PATH}}`, `{{BRANCH}}` (the per-ticket branch name, e.g. `m1/M1-NNN-<slug>`), `{{ROUND}}`).
4. Spawn `Agent(subagent_type: "code-reviewer", prompt: <substituted>, description: "Review M1-NNN")`. Foreground (the verdict gates the next step). The `code-reviewer` agent is defined at `.claude/agents/code-reviewer.md` (read-only tool allowlist, opus model).
5. Parse the structured verdict.
6. Append to ticket frontmatter under `reviews:`:
   ```yaml
   reviews:
     - round: <1|2|3>
       date: <YYYY-MM-DD>
       verdict: <APPROVE|REWORK|MANUAL>
       checks:
         scope_drift: <PASS|FAIL>
         test_integrity: <PASS|FAIL>
         out_of_scope: <PASS|FAIL>
         negative_space: <PASS|WARN>
         acceptance: <PASS|PARTIAL|FAIL>
       diff_stats:
         files: <n>
         added: <n>
         removed: <n>
   ```
7. Branch on verdict:

   - **APPROVE.** Set `status: in-review`. Update `last_updated`. If `negative_space: WARN`, print the negative-space note to chat as informational. Print:
     ```
     M1-NNN: APPROVE (round <r>).
     Run `/m1-tick commit M1-NNN` to finalize.
     ```

   - **REWORK, round 1.** Set `status: in-progress`. Append the rework items to the ticket body under a new `## Round 1 rework` section. Print the rework items in chat. Remind the developer to address only the named items, then re-run `mvn verify`, then `/m1-tick review M1-NNN`.

   - **REWORK, round N (N≥2).**
     - If the verdict's `SCOPE-DRIFT-CHECK: FAIL` reason includes a must-shrink violation (round-N diff grew along all three dimensions vs round-(N−1) without an authorized refactor citation), set `status: escalated` and fire `escalate` with `reason: round-cap`. Must-shrink failures do NOT consume a round-cap allowance — they exit immediately because the rework is no longer convergent. This applies even when `round_cap: 3`.
     - Otherwise, if `round_cap: 3` AND this is round 2: set `status: in-progress` and append a `## Round 2 rework` section. The next review will be round 3 and will be compared to round 2 for must-shrink.
     - Otherwise (round 2 with default `round_cap: 2`, or round 3 with any `round_cap`): set `status: escalated` and fire `escalate` with `reason: round-cap`.

   - **MANUAL.** Set `status: escalated`. Fire `escalate` with `reason: manual-verdict`.

8. Update `last_updated`. Regenerate `STATUS.md`.

---

## `commit <id>`

Preconditions:

- `status: in-review` and the most recent entry under `reviews:` has `verdict: APPROVE` OR `verdict: OVERRIDE-APPROVE` (the latter is written by the override escalation path).
- Working tree contains the implementation diff to be committed; the ticket file's frontmatter update is the only mutation step 4 below adds.

Steps:

1. **Identify the files about to be committed.** Run `git diff --name-only HEAD` to list modified files in the working tree. Exclude the ticket file itself (the ticket file's frontmatter mutation in step 4 happens *after* this check; we're checking the freshness of the test result against the source/test code that produced it). Call this set the *commit candidates*.
2. **Test-freshness safety check.**
   - For tickets with `complexity: high` OR `risk: high`: re-run `mvn verify` from the repo root. Refuse to commit if it does not exit zero. Persist a fresh log to `target/m1-tick-test-{ID}-rcommit.log`.
   - For all other tickets: locate the most recent `target/m1-tick-test-{ID}-r*.log`. Read its mtime. Compute the latest mtime among the *commit candidates* from step 1. If the test log is older than any commit candidate, refuse and tell the user to re-run `mvn verify` (the test result is stale relative to the code about to be committed).
3. Build the commit message:
   ```
   M1-NNN: <ticket title>

   <Context paragraph from the ticket body, wrapped at 72 chars>

   <If the ticket body has an "Alternatives considered" section with
   non-empty alternatives chosen against, transcribe them here:>
   Alternatives considered:
     - <alt 1>: <reason>
     - <alt 2>: <reason>

   Reviewed-by: code-reviewer (VERDICT: <APPROVE|OVERRIDE-APPROVE>; round <r>)
   ```
4. Set ticket frontmatter `status: done`. Update `last_updated`. (This mutation produces a working-tree modification on the ticket file in addition to the commit candidates from step 1.)
5. Stage explicitly: `git add` each commit candidate from step 1, plus the ticket file. Never `git add -A`.
6. `git commit -m "<heredoc message>"` — single commit.
7. Regenerate `STATUS.md`.
8. If the ticket has `security_relevant: true`, remind the user that [`/redteam M1-NNN`](../redteam/SKILL.md) is recommended before merging.
9. Print:
   ```
   M1-NNN committed on branch m1/M1-NNN-<slug>.
   Branch is local; push and merge are your call.
   Recommended: squash-merge into main so main history stays one
   commit per ticket. `git revert <sha>` cleanly undoes this ticket.
   ```

Do NOT push. Do NOT merge. Do NOT amend the commit if a defect is found later — file a new ticket.

---

## `escalate <id> [reason]`

Reasons (auto-set by `review`/`start` or passed explicitly):

- `round-cap` — round-cap returned non-APPROVE, or a must-shrink violation forced an early exit from the rework loop.
- `manual-verdict` — reviewer returned MANUAL.
- `clarity-fail` — clarity pre-flight returned FAIL during `/m1-tick start`.
- `outline-fail` — Plan subagent returned `OUTLINE FAILED` during `/m1-tick start` (only reachable for `complexity: high` tickets).
- `budget-breach` — developer is about to exceed `files_budget`.
- `premise-fail` — tests fail in a way that suggests the ticket's premise is wrong.
- `loop` — two consecutive failures with the same root cause.
- `redteam-finding` — [`/redteam`](../redteam/SKILL.md) returned non-CLEAN and the user opened the lifecycle escalation for the affected ticket. **REFUSED if the operand ticket has `status: done`** — done commits are immutable (per `CLAUDE.md` §M1 workflow "never amend a passed commit"). The redteam SKILL prints the alternative recommendation in this case: draft a new remediation ticket with `remediates: <done-id>` pointing back at the done ticket, then run `/m1-tick start <new-id>`. The done ticket's `redteam_findings:` is still populated for traceability.

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

     - **Refine after `clarity-fail` or `outline-fail`** (no branch ever existed; the ticket never reached `in-progress`): set `status: pending`. Clear `clarity_check:` (it described the *old* ticket; the rewritten ticket needs a fresh evaluation). Tell the user to run `/m1-tick start M1-NNN` again — the next `start` will re-run clarity against the rewritten ticket, which is the correct behavior because the previous FAIL means the ticket was never validated.
     - **Refine after `round-cap`, `manual-verdict`, `budget-breach`, `premise-fail`, `loop`, or `redteam-finding`** (branch exists, implementation is in progress or complete): set `status: in-progress` and remind the developer to re-implement against the new criteria on the existing branch. The clarity pre-flight does NOT re-run on refine in this arm (the criteria are new but the implementation context — branch, prior diff, prior `mvn verify` — is preserved); WARN if the refined ticket would have failed clarity.

     The two arms differ only in destination status (`pending` vs `in-progress`) and whether `clarity_check:` is cleared. Both arms preserve the snapshot under `revisions:` for the audit trail.

   - **`2` (override).** Append to ticket frontmatter:
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

   - **`5` (spec-amend).** Ask the user which spec section is wrong (path + section heading) and what the amendment should change. Allocate the new amendment ticket's ID (`M1-AAA`) via the **ID allocation algorithm** below. Create the new ticket at `docs/plan/m1/tickets/M1-AAA-<slug>.md` with:
     - `id: M1-AAA`
     - `title: "Amend <spec-path> §<section>"`
     - `spec_amend_for: <path>:§<section>`
     - `spec_amend_parent: M1-NNN` (the operand)
     - `acceptance:` derived from the user's amendment description (the new ticket's job is to land the spec change, not the implementation).

     Set the operand ticket: `status: deferred`, `deferred_on: M1-AAA`, `deferred_reason: spec-amend`. Print the new ticket path. The amendment ticket must be `done` before the operand can be reopened.

6. Regenerate `STATUS.md` after the resolution applies.

### ID allocation algorithm

Used by `decompose` and `spec-amend` to allocate fresh ticket IDs.

1. Glob `docs/plan/m1/tickets/M1-*.md` (or for other milestones, the corresponding directory).
2. Parse the `id:` field from every file's frontmatter. Include tickets in EVERY status — pending, in-progress, in-review, escalated, done, deferred. IDs of `aborted_attempts:` are NOT separate IDs (they're attempts on existing tickets), so they don't enter this scan.
3. Extract the numeric suffix from each ID (e.g. `M1-007` → `7`). Take the maximum.
4. Allocated ID = `M1-<max+1>`, zero-padded to 3 digits (e.g. `M1-008`).
5. For multiple allocations in one operation (decompose into N), allocate sequentially: `M1-<max+1>`, `M1-<max+2>`, etc.
6. **IDs are never reused.** A `done` ticket's ID is reserved for that ticket forever; a `deferred` ticket's ID stays with it through reopen; even an aborted-and-restarted ticket keeps its original ID. This way `git log --grep "M1-007"` returns the full history of that ID's work.

---

## `abort <id>`

Cancel an in-progress ticket. Roll back the branch and reset the ticket to `pending`.

Preconditions:

- The ticket exists.
- `status: in-progress` OR `status: in-review` (with the most recent review NOT being `APPROVE`). Refuse if the most recent review is `APPROVE` — at that point the ticket is one commit away from done; the right path is `/m1-tick commit` or `/m1-tick escalate ... override`.
- Refuse if `status: done`. Done tickets are immutable; defects → new ticket.
- Refuse if `status: deferred`. Deferred tickets do not have a branch to abort; use `/m1-tick reopen` if the user wants to resume them.

Steps:

1. Print a confirmation prompt:
   ```
   ABORT M1-NNN: <title>
   Branch m1/M1-NNN-<slug> will be deleted (uncommitted work on it will be lost).
   Ticket frontmatter on `main` will be updated: status reset to pending,
   prior attempt archived under aborted_attempts:. The skill commits the
   archive on main BEFORE deleting the branch.

   Confirm with: yes
   ```
2. Wait for the user's literal `yes`. Any other reply aborts the abort.
3. **Snapshot from the branch** (we are currently on the branch). Read the ticket file's current frontmatter into memory: capture `status`, `reviews:`, `clarity_check:`, anything else in dynamic fields. This is the data that will become the `aborted_attempts:` archive entry.
4. **Switch to main** with `git checkout main`. This discards any uncommitted working-tree changes on the branch (including the ticket file's in-progress modifications) — which is the intended destructive behavior of abort.
5. **Read the ticket file on main.** This is the older state (typically with `status: pending` from before `start` ran, or `status: in-progress` if a prior abort committed an in-progress reset — either way, the persistent main-branch state).
6. **Build the new frontmatter on main:**
   - Append the snapshot from step 3 to `aborted_attempts:`:
     ```yaml
     aborted_attempts:
       - date: <YYYY-MM-DD>
         prior_status: <captured status from step 3>
         reviews_at_abort: <captured reviews from step 3>
         clarity_check_at_abort: <captured clarity_check from step 3>
         reason: <user's optional reason from the abort args>
     ```
   - Set `status: pending`.
   - Clear `reviews:`, `clarity_check:` (these belonged to the aborted attempt; they're now archived).
   - Update `last_updated` to today.
   - Keep `created`, `blocked_by`, `files_budget`, `files_scope`, `complexity`, `risk`, `round_cap`, `security_relevant`, `migration_touch`, `out_of_scope`, `acceptance`, `test_plan`, `spec_refs`, `decision_refs`, lineage fields untouched.
7. **Commit the archive on main:**
   - Stage only the ticket file: `git add docs/plan/m1/tickets/M1-NNN-<slug>.md`.
   - Commit subject: `M1-NNN: aborted attempt #<N> (reason: <reason-or-no-reason-given>)` where `<N>` is the new length of `aborted_attempts:` after appending. This makes aborts visible in `git log --oneline` and `git bisect`.
8. **Delete the branch:** `git branch -D m1/M1-NNN-<slug>`. (Use `-D` because the branch may have local commits the user is intentionally discarding.)
9. Regenerate `STATUS.md`.
10. Print:
    ```
    M1-NNN aborted. Branch m1/M1-NNN-<slug> deleted.
    Archive committed on main as attempt #<N> under aborted_attempts:.
    Status reset to pending. To resume work, run `/m1-tick start M1-NNN`.
    ```

---

## `show <id>`

Read-only inspection of a ticket. No state changes.

1. Read the ticket file.
2. Print:
   - Frontmatter, formatted.
   - The ticket body, verbatim.
   - A short audit trail summary derived from `reviews:`, `escalations:`, `revisions:`, `overrides:`, `aborted_attempts:`, `redteam_findings:` — one line each.
3. Stop.

---

## `reopen <id>`

Bring a `deferred` ticket back to `pending`. Requires that the blocker (if any) is now resolved.

Preconditions:

- The ticket exists and `status: deferred`.
- If `deferred_on:` is set, that ticket is `status: done`. Refuse if not.
- For `deferred_reason: spec-amend`, additionally require that the spec amendment ticket landed AND the user re-affirms that the original ticket's `spec_refs` are still correct (the spec text changed; the ticket's references may need updating).

Steps:

1. Ask the user for an optional one-line reason ("why now?").
2. Append to ticket frontmatter under a `reopens:` list:
   ```yaml
   reopens:
     - date: <YYYY-MM-DD>
       prior_deferred_reason: <copy of deferred_reason>
       prior_deferred_on: <copy of deferred_on>
       reason: <user reason>
   ```
3. Clear `deferred_on:` and `deferred_reason:`. Set `status: pending`. Update `last_updated`.
4. Regenerate `STATUS.md`.
5. Print:
   ```
   M1-NNN reopened (status: pending).
   Run `/m1-tick start M1-NNN` to begin (clarity pre-flight will run).
   ```

---

## `status` — regenerate STATUS.md

1. Read every file under `docs/plan/m1/tickets/M1-*.md`.
2. Parse frontmatter.
3. Compute:
   - Counts per status.
   - Runnable now (pending + all blocked_by done).
   - In flight (in-progress, in-review).
   - Blocked (pending + at least one blocked_by not done).
   - Escalated.
   - Done.
   - Deferred (with deferred_reason breakdown).
   - Dependency DAG (ASCII; nodes are ticket IDs, edges are blocked_by AND deferred_on).
4. Render `docs/plan/m1/STATUS.md` using the template below verbatim. Set the `Last updated` line to today's date. Substitute the computed sections; for any empty section, emit the literal `_(none)_` line shown in the template. The template intentionally matches the existing committed `docs/plan/m1/STATUS.md` style so the first `/m1-tick status` invocation produces minimal diff against the placeholder.

   ```markdown
   # M1 status board

   > **Auto-generated by `/m1-tick status`.** Do not hand-edit. Source of truth is the frontmatter of the files under `docs/plan/m1/tickets/`. If this file disagrees with frontmatter, frontmatter wins; re-run `/m1-tick status` to regenerate.

   **Last updated:** <YYYY-MM-DD>

   ---

   ## Summary

   | Status | Count |
   |---|---|
   | pending | <n> |
   | in-progress | <n> |
   | in-review | <n> |
   | escalated | <n> |
   | done | <n> |
   | deferred | <n> |
   | **total** | **<n>** |

   ---

   ## Runnable now

   Tickets where `status: pending` AND every entry in `blocked_by` has `status: done`.

   - M1-NNN — <title> (complexity: <c>, risk: <r>)
   - ...

   _(or `_(none — all pending tickets are blocked)_` when empty)_

   ---

   ## In flight

   | ID | Title | Status | Last review |
   |---|---|---|---|
   | M1-NNN | ... | in-progress | round <r> <verdict> on <date> |

   _(or `_(none)_` when empty)_

   ---

   ## Blocked

   Tickets with `status: pending` AND at least one `blocked_by` entry not yet done.

   - M1-NNN — blocked_by: M1-XXX (status), M1-YYY (status)

   _(or `_(none)_` when empty)_

   ---

   ## Escalated (awaiting user resolution)

   | ID | Title | Trigger | Date |
   |---|---|---|---|
   | M1-NNN | ... | round-cap | <date> |

   _(or `_(none)_` when empty)_

   ---

   ## Done

   Showing the 10 most recently `done` tickets (full history is git-log-derivable via `git log --grep "^M1-"`).

   | ID | Title | Done date | Verdict |
   |---|---|---|---|
   | M1-NNN | ... | <date> | round <r> APPROVE |

   _(or `_(none)_` when empty)_

   ---

   ## Deferred

   Grouped by `deferred_reason`. Emit only subsections with non-zero entries.

   ### decomposed (<n>)
   - M1-NNN → replaced by M1-AAA, M1-BBB, M1-CCC

   ### spec-amend (<n>)
   - M1-NNN → blocked on M1-AAA (amends docs/spec/X.md §Y)

   ### blocked-on-new-ticket (<n>)
   - M1-NNN → blocked on M1-XXX

   _(or `_(none)_` under the section header when all subsections are empty)_

   ---

   ## Dependency graph

   ASCII DAG: nodes are ticket IDs (with status in parens), edges are `blocked_by` AND `deferred_on` relationships. Mark runnable tickets with `←`.

   ```
   M1-001 (done)
     ├── M1-005 (done)
     │     └── M1-012 (in-progress)
     └── M1-007 (pending) ← runnable
   M1-002 (escalated)
   M1-008 (deferred — spec-amend → M1-009)
     └── M1-009 (pending) ← runnable
   ```

   _(or `_(none — will render once tickets exist)_` when there are no tickets)_
   ```
5. Print a one-screen summary of the same content (counts + runnable list + in-flight) so the user sees it without opening the file.

Optional flags:
- `/m1-tick status --deferred` — show only deferred tickets, ordered by `deferred_reason` then ID.
- `/m1-tick status --escalated` — show only escalated tickets with their last reviewer-verdict excerpt.

---

## Cross-cutting rules this skill must obey

- **Never push or merge.** That's the user's call.
- **Never amend a passed commit.** Defects → new ticket.
- **Never skip `mvn verify`** before review. If the developer claims tests pass without running them, refuse and re-run.
- **Never skip the commit-step safety re-run** for high-complexity / high-risk tickets. The cost of re-running is small; the cost of shipping a faked review is large.
- **Never spawn a developer-subagent.** The main conversation is the developer; subagents this skill spawns are: the reviewer (always at `review`), the planner (only on `complexity: high` at `start`), and the clarity pre-flight (always at `start`). The threat-actor subagent lives in the separate [`/redteam`](../redteam/SKILL.md) skill — this skill never spawns it directly.
- **Never edit `STATUS.md` by hand.** Always regenerate from frontmatter.
- **Never silently expand a ticket's `files_budget`, `files_scope`, or `out_of_scope`.** Frontmatter changes go through `escalate → refine`.
- **Never use destructive shortcuts** (`--no-verify`, `git reset --hard`, `--skip-tests`, force-push) to make obstacles disappear. Escalate instead.
- **`abort` is destructive and requires explicit user confirmation.** Branch deletion uses `git branch -D` only after the user types `yes`.
- **If this skill's procedure conflicts with `CLAUDE.md` §Engineering rules, §M1 workflow, `docs/process/workflow.md`, `docs/plan/m1/README.md`, or `docs/process/engineering-rules-verbatim.md`, those win.** Stop and surface the conflict; do not proceed.
