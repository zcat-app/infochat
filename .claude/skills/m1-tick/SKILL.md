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
  - The new ticket's `files_budget` paths and `out_of_scope` entries are provably disjoint from every in-flight ticket.
  - Neither the new ticket nor any in-flight ticket has `migration_touch: true`.

Steps:

1. **Ticket-clarity pre-flight.** Read `docs/process/clarity-prompt.md`. Resolve every `spec_refs` entry by reading the cited file and locating the anchor heading; build the `{{SPEC_REF_RESOLUTIONS}}` block. Substitute placeholders. Spawn `Agent(subagent_type: "code-reviewer", prompt: <substituted>, description: "Clarity pre-flight M1-NNN")`. Foreground.
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
   - `FAIL` → print blockers, refuse the start, leave status as `pending`. Suggest the user run `/m1-tick escalate <id> refine` (the refine path edits the ticket).
4. Set the ticket's frontmatter `status: in-progress`. Update `last_updated` to today's date.
5. Compute slug from the title (lowercase, ASCII, hyphenated, ≤ 30 chars).
6. Branch:
   - **Sequential:** `git checkout -b m1/M1-NNN-<slug>` from `main`.
   - **Parallel:** create a worktree via `Agent(isolation: "worktree")` and run the rest of the flow inside it.
7. If `complexity: high`, spawn `Agent(subagent_type: "Plan")` with the ticket file content and require it to return an implementation outline before any code edit. The outline becomes an "Implementation outline" section appended to the ticket body. The developer (the main conversation) reads it before touching code.
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
   - Build the negative-space list: union of paths matched by `files_budget` (when expressed as paths/globs) minus paths actually present in the diff.
   - The tail of the most recent `mvn verify` output (last ~200 lines; full log persisted to `target/m1-tick-test-{{ID}}-r{{ROUND}}.log`).
   - The ticket file content.
3. Read `docs/process/reviewer-prompt.md` and substitute placeholders (`{{TICKET_ID}}`, `{{TICKET_FILE_CONTENT}}`, `{{DIFF_OUTPUT}}`, `{{R1_FILES}}`, `{{R1_ADDED}}`, `{{R1_REMOVED}}`, `{{R2_FILES}}`, `{{R2_ADDED}}`, `{{R2_REMOVED}}`, `{{NEGATIVE_SPACE_LIST}}`, `{{TEST_OUTPUT_TAIL}}`, `{{TEST_LOG_PATH}}`, `{{SLUG}}`, `{{ROUND}}`).
4. Spawn `Agent(subagent_type: "code-reviewer", prompt: <substituted>, description: "Review M1-NNN")`. Foreground (the verdict gates the next step).
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

   - **REWORK, round 2.** Read the ticket's `round_cap`. If `round_cap: 3`, set `status: in-progress` and append a `## Round 2 rework` section. Otherwise set `status: escalated` and fire `escalate` with `reason: round-cap`.

   - **REWORK, round 3.** Set `status: escalated`. Fire `escalate` with `reason: round-cap`.

   - **MANUAL.** Set `status: escalated`. Fire `escalate` with `reason: manual-verdict`.

8. Update `last_updated`. Regenerate `STATUS.md`.

---

## `commit <id>`

Preconditions:

- `status: in-review` and the most recent entry under `reviews:` has `verdict: APPROVE`.
- Working tree is clean except for the ticket file's frontmatter update (which the next steps will commit alongside the code).

Steps:

1. **Test-freshness safety check.**
   - For tickets with `complexity: high` OR `risk: high`: re-run `mvn verify` from the repo root. Refuse to commit if it does not exit zero.
   - For all other tickets: locate the most recent `target/m1-tick-test-{ID}-r*.log`. Read its mtime. Compute the latest mtime among the staged files (production + test code, but not the ticket file). If the test log is older than any staged file, refuse and require a fresh `mvn verify`.
2. Build the commit message:
   ```
   M1-NNN: <ticket title>

   <Context paragraph from the ticket body, wrapped at 72 chars>

   <If the ticket body has an "Alternatives considered" section with
   non-empty alternatives chosen against, transcribe them here:>
   Alternatives considered:
     - <alt 1>: <reason>
     - <alt 2>: <reason>

   Reviewed-by: code-reviewer (VERDICT: APPROVE; round <r>)
   ```
3. Set ticket frontmatter `status: done`. Update `last_updated`.
4. Stage the changed files (use specific names; never `git add -A`).
5. `git commit -m "<heredoc message>"` — single commit.
6. Regenerate `STATUS.md`.
7. If the ticket has `security_relevant: true`, remind the user that [`/redteam M1-NNN`](../redteam/SKILL.md) is recommended before merging.
8. Print:
   ```
   M1-NNN committed on branch m1/M1-NNN-<slug>.
   Branch is local; push and merge are your call.
   Recommended: squash-merge into main so main history stays one
   commit per ticket. `git revert <sha>` cleanly undoes this ticket.
   ```

Do NOT push. Do NOT merge. Do NOT amend the commit if a defect is found later — file a new ticket.

---

## `escalate <id> [reason]`

Reasons (auto-set by `review` or passed explicitly):

- `round-cap` — round-cap returned non-APPROVE.
- `manual-verdict` — reviewer returned MANUAL.
- `budget-breach` — developer is about to exceed `files_budget`.
- `premise-fail` — tests fail in a way that suggests the ticket's premise is wrong.
- `loop` — two consecutive failures with the same root cause.
- `redteam-finding` — [`/redteam`](../redteam/SKILL.md) returned non-CLEAN and the user opened the lifecycle escalation for the affected ticket.

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
3. Print the five-way menu (in chat — the user picks):

```
M1-NNN: <title>  —  ESCALATED
Trigger: <reason>

Reviewer's last verdict (or trigger context):
  <verbatim block>

Choose:
  1. refine     — acceptance criteria were ambiguous; rewrite the ticket
  2. override   — reviewer was too strict; record the override and approve
  3. decompose  — split into N tickets; defer this one and queue replacements
  4. defer      — block on a new ticket the work surfaced; pause this one
  5. spec-amend — the spec itself is wrong; raise an amendment ticket and pause

Reply with: <number> [optional notes]
```

4. STOP. Wait for the user's reply. The skill does not auto-proceed past escalation.

5. On user reply, dispatch:

   - **`1` (refine).** Snapshot current frontmatter under `revisions:` with date + reason. Prompt the user for the new acceptance criteria / out_of_scope / files_budget / etc. Apply the edits. Set `status: in-progress` and remind the developer to re-implement against the new criteria. The clarity pre-flight does NOT re-run on refine (the user has already written the new ticket); WARN if the refined ticket would have failed clarity.

   - **`2` (override).** Append to ticket frontmatter:
     ```yaml
     overrides:
       - date: <YYYY-MM-DD>
         objection: |
           <verbatim text of the reviewer's REWORK item the user is overriding>
         user_justification: |
           <user's reason from the reply>
     ```
     Set `status: in-review` (with the prior APPROVE-equivalent verdict synthesized — the user's override IS the approval). Proceed to `commit`.

     Override is NOT permitted for `TEST-INTEGRITY-CHECK: FAIL` flowing through `MANUAL` — the user must explicitly acknowledge that they are overriding test integrity, and the override entry must include the literal text `"acknowledging-test-integrity-override"` in `user_justification`.

   - **`3` (decompose).** Ask the user to name the N replacement ticket IDs and one-line titles. Set this ticket's `status: deferred` with `deferred_reason: decomposed`. Create N skeleton ticket files from `docs/process/ticket-template.md` with `blocked_by: []`, `decomposed_from: M1-NNN`, and the user-supplied titles, placed under `docs/plan/m1/tickets/`. Print the new ticket paths so the user can flesh them out.

   - **`4` (defer).** Ask the user to name the blocking ticket (existing ID) or to draft a new one. Set this ticket's `status: deferred`, `deferred_on: <blocker-id>`, `deferred_reason: blocked-on-new-ticket`. If new blocker, create the skeleton ticket file. Print the deferred state.

   - **`5` (spec-amend).** Ask the user which spec section is wrong (path + section heading) and what the amendment should change. Create a new ticket with:
     - `id: M1-NNN+1` (or the next free ID)
     - `title: "Amend <spec-path> §<section>"`
     - `spec_amend_for: <path>:§<section>`
     - `spec_amend_parent: M1-NNN`
     - `acceptance:` derived from the user's amendment description (the new ticket's job is to land the spec change, not the implementation).

     Set the ORIGINAL ticket: `status: deferred`, `deferred_on: <new-amendment-ticket-id>`, `deferred_reason: spec-amend`. Print the new ticket path. The amendment ticket must be `done` before the original can be reopened.

6. Regenerate `STATUS.md` after the resolution applies.

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
   Ticket frontmatter will be reset to status: pending.
   Reviews and clarity-check history will be archived under aborted_attempts:.

   Confirm with: yes
   ```
2. Wait for the user's literal `yes`. Any other reply aborts the abort.
3. Snapshot the current frontmatter under a new `aborted_attempts:` list:
   ```yaml
   aborted_attempts:
     - date: <YYYY-MM-DD>
       prior_status: <in-progress | in-review>
       reviews_at_abort: <copy of the reviews list>
       clarity_check_at_abort: <copy of clarity_check>
       reason: <user's optional reason from the abort args>
   ```
4. Reset frontmatter: `status: pending`, clear `reviews:`, clear `clarity_check:`, update `last_updated`. Keep `created` untouched.
5. Switch to `main`, then delete the branch: `git branch -D m1/M1-NNN-<slug>`. (Use `-D` because the branch may have local commits the user is intentionally discarding.)
6. Regenerate `STATUS.md`.
7. Print:
   ```
   M1-NNN aborted. Status reset to pending. Branch m1/M1-NNN-<slug> deleted.
   Prior attempts archived under aborted_attempts:.
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
4. Render `docs/plan/m1/STATUS.md`. Set its `Last updated` line to today's date.
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
- **Never silently expand a ticket's `files_budget` or `out_of_scope`.** Frontmatter changes go through `escalate → refine`.
- **Never use destructive shortcuts** (`--no-verify`, `git reset --hard`, `--skip-tests`, force-push) to make obstacles disappear. Escalate instead.
- **`abort` is destructive and requires explicit user confirmation.** Branch deletion uses `git branch -D` only after the user types `yes`.
- **If this skill's procedure conflicts with `CLAUDE.md` §Engineering rules, §M1 workflow, `docs/process/workflow.md`, `docs/plan/m1/README.md`, or `docs/process/engineering-rules-verbatim.md`, those win.** Stop and surface the conflict; do not proceed.
