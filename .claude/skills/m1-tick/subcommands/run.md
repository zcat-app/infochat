# /m1-tick run

Drive one ticket through the **entire** lifecycle unattended, stopping only at the points a human genuinely owns. Invocation: `run [id]`.

`run` is a **thin sequencer, not a decision-maker.** It re-derives no procedure: it invokes the existing subcommand files — [`start.md`](start.md), [`review.md`](review.md), [`commit.md`](commit.md), [`merge.md`](merge.md) — and the [`/redteam`](../../redteam/SKILL.md) skill *verbatim*, inheriting every internal gate they already define (the `start` grounding checkpoint, clarity pre-flight, plan-writer, must-shrink, branch resolution, commit-time safety re-run, STATUS regen, merge conflict refusal). The orchestrator (the main conversation) **is the developer** — `run` never spawns a developer-subagent. It **never pushes.** Every engineering rule applies at full force: a red `mvn verify` it cannot fix in-band is a halt, never a workaround.

Because it delegates, when a subcommand file changes, `run` changes with it. Read each subcommand file fresh at the step that invokes it (the SKILL.md dispatch rule "do NOT apply procedure from memory" binds `run` too).

## The two new policies `run` adds

Everything else is "stop typing the subcommand names." `run` introduces exactly two behaviors not already in the subcommands, both from a settled design decision:

1. **Grounding auto-confirm only when the id was named** (decision A). `start.md` step 0 fires a *blocking* grounding `AskUserQuestion`. When the user invoked `run M1-NNN` with an explicit id, that explicit choice **is** the confirmation: still perform step 0's on-disk scope-resolution falsification check, but skip the `AskUserQuestion` and proceed. When `run` auto-selected the ticket via `next` (no id given), the orchestrator did the choosing, so **keep the blocking confirm** — the user must still verify the right ticket was picked.
2. **Bounded self-refine of a clarity/outline FAIL** (decision C). See step 2. This is the *only* place `run` rewrites a ticket on its own, and it is tightly bounded.

## Entry — resume-aware dispatch

`run` is re-invocable after any halt: read the ticket's `status` and enter at the right phase, so resuming never repeats finished work.

| Ticket `status` | Enter at |
|---|---|
| `pending` | step 1 (select) / step 2 (start) |
| `in-progress`, branch exists | step 3 (implement), step 4 (redteam gate) or step 5 (review), per how far the last round got |
| `in-review` (APPROVE / OVERRIDE-APPROVE) | step 6 (commit) — the redteam gate runs at step 4, ahead of review. **Transitional check:** a ticket that reached `in-review` before this ordering landed never faced step 4. If `security_relevant: true` and `redteam_audits:` holds no entry covering the current diff, run step 4 before committing |
| `done` | step 7 (merge) |
| `escalated` | **refuse** — an escalation is open; the user resolves it via the escalation menu before `run` can resume |
| `deferred` | **refuse** — `run` does not reopen; point the user at `/m1-tick reopen <id>` |
| `abandoned` | **refuse** — the ticket was decided against and is terminal; `run` does not revive it. Reviving is a fresh, deliberate decision (a new ticket, or re-escalation with justification) |

## Steps

1. **Select the ticket.**
   - If an id was given: use it. (It carries the grounding auto-confirm per policy 1.)
   - If no id: run the [`next.md`](next.md) procedure and take the **lowest runnable ID**. State which ticket was picked. This path keeps the blocking grounding confirm in step 2.
   - If `next` reports nothing runnable: print that and stop.

2. **Start** — invoke [`start.md`](start.md) end to end, with the grounding behavior from policy 1.
   - **Clarity `PASS`/`WARN`** → proceed (WARN is notify-and-continue).
   - **Clarity `FAIL` (or `complexity:high` outline-fail) → bounded self-refine (decision C):**
     - **Eligibility — prose-defect blockers ONLY.** Self-refine is permitted *only* when every blocker is a ticket-*prose* defect the orchestrator can fix without changing scope or intent: unrunnable/ambiguous acceptance phrasing, an empty `out_of_scope`, a mistyped or unresolvable `spec_refs` anchor. Blockers that imply a **structural** resolution — "ticket is too big" (decompose), "acceptance is unachievable as framed" (defer), "the spec itself is wrong" (spec-amend), or anything requiring a wider `files_budget`/`files_scope` — are **NOT** self-refinable. `run` must not silently expand `files_budget`/`files_scope`/`out_of_scope` (the SKILL.md cross-cutting rule binds here).
     - **Verify before applying.** Ultrathink the blocker. Try to *falsify* the proposed prose fix ("what would make this the wrong correction?"). Apply it only if it survives.
     - **Cap: 1 self-refine attempt.** Apply the prose fix exactly as the `escalate → refine` clarity-fail arm would (snapshot under `revisions:`, commit `M1-NNN: refine ticket spec (clarity-fail rework)` on `main`, clear `clarity_check:`, status back to `pending`), record a one-line note of what was changed and why, then **re-run `start`**. Re-running `start` re-fires the clarity pre-flight fresh — that independent re-check is the backstop: a refine that did not truly fix the blocker simply FAILs again.
     - **Halt conditions:** any structural blocker, OR a second FAIL after one self-refine → stop and surface the **escalation menu** (`escalate` with the original `clarity-fail` / `outline-fail` reason). `run` does not self-refine twice and does not self-resolve structural blockers.

3. **Implement.** The orchestrator writes the diff in normal Edit/Write/Bash calls. For `complexity:high`, read the plan-writer sidecar (`outline_file:`) before touching code. Run `mvn verify` capturing to the fixed log path per the SKILL.md "Capture `mvn verify` output" rule before moving to review.
   - **Surface an incidental finding at discovery, not in the summary.** When implementation turns up a real defect outside this ticket's acceptance — most often in a file the diff already has open — report it in the turn you find it, in one or two lines: what it is, and the cost of each path. Folding it in is **not** free and must not be presented as free: an extra changed line that traces to no acceptance item is a `SCOPE-DRIFT-CHECK` fail, so folding in means `escalate → refine` to add the acceptance item first. Deferring means a follow-up ticket. Both are the user's call, and the call is only cheap while the file is still open — batching these into the closing summary removes the choice and reads as ticket-padding. Then **continue implementing**; this is notify-and-continue, not a blocking menu, and the default on silence is defer.
   - **Halt to an option menu** on any documented immediate-escalation trigger — `files_budget` about to be exceeded, a path outside `files_scope`/`out_of_scope`, a test failure suggesting the ticket's *premise* is wrong, two consecutive failures with the same root cause — or on a genuine design decision only the user can make. Each halt presents a brief summary and verified options, the recommended one first with its reasoning. **Every recommended option is verified before it is offered** (CLAUDE.md verify-before-recommending). A red `mvn verify` from the orchestrator's own diff is fixed in-band, not escalated, unless it reveals a premise-fail.

4. **Redteam gate (conditional on `security_relevant`).** This runs **before** review, not after. A finding forces a code change, and a code change invalidates a review that already passed — so auditing second guarantees the ticket pays for a second review round whenever the adversary finds anything. Auditing first means review sees the remediated diff. The M1 corpus bears the asymmetry out: the redteam gate returns FINDINGS on ~30% of audits (102 of 335) while review returns REWORK or MANUAL on ~12% of tickets (80 of 670), and 13 tickets recorded an `APPROVE, APPROVE` pair whose second round existed *only* because a post-review audit found something (M1-045, 051, 302, 506, 515, 528, 542, 561, 563, 597, 632, 636, 658). Running the more selective gate first is strictly cheaper.
   - If `security_relevant: false` → skip directly to step 5.
   - If `security_relevant: true` → invoke `/redteam <id> --in-progress` (audits the uncommitted branch tip; the redteam skill's step-1 algorithm resolves the working-tree diff for a zero-commit branch and refuses outright on an empty one).
     - **`CLEAN`** → proceed to step 5. (The audit file lands in the working tree and folds into the eventual commit. It does not count against `files_budget` or `files_scope` — `reviewer-prompt.md` §"Lifecycle-path exemption" already exempts `docs/plan/m1/redteam/<id>-*.md`, so it is safe for review to see it.)
     - **`FINDINGS`** → **halt.** Surface the per-severity summary and recommend `/m1-tick escalate <id> redteam-finding`. Do not review, do not commit.
     - **Out-of-model items** (either verdict) → report each with a one-line recommendation on whether it warrants a follow-up ticket *and why*, but never auto-file one (the redteam skill is advisory-only on these).

5. **Review loop** — invoke [`review.md`](review.md) for each round.
   - **APPROVE** → proceed to step 6.
   - **REWORK, round 1** → address only the named items, re-run `mvn verify`, re-invoke `review`. (Round-1 REWORK is notify-and-continue.) **If the rework changed any file under a module's `src/`, re-run step 4 before proceeding** — the ordering above buys nothing if a post-audit code change ships unaudited, and the invalidation runs in both directions. A rework that only touches the ticket file, `files_scope`/`files_budget` declarations, or docs does not re-trigger the gate.
   - **Round-cap reached, must-shrink violation, or MANUAL** → `review.md` already sets `escalated` and fires `escalate`; `run` **halts** to that escalation menu. It does not loop past the round cap.

6. **Commit** — invoke [`commit.md`](commit.md). Its user-gated test-freshness menu and (for `complexity:high`/`risk:high`) full-suite safety re-run fire as written. `run` goes *through* `commit.md`; it never hand-rolls a commit.

7. **Merge** — invoke [`merge.md`](merge.md) to squash-merge into `main` (decision B: `run` does cross the commit→merge line automatically). `merge.md`'s existing refusal on a **substantive conflict** is the safety stop — on that refusal, `run` **halts** and surfaces the rebase-and-retry guidance. **Never push.**

8. **Summary.** Print what happened across the cycle (phases run, review rounds, redteam verdict if any, merge SHA) and a suggested next step. `run` does **not** auto-chain to the next ticket — its scope is one ticket. The user re-invokes `run` for the next.

## Stop taxonomy

- **Hard stop (end this run, hand to the user):** grounding confirm in the auto-picked case; a structural or cap-exceeded clarity/outline FAIL; a genuine design decision; any immediate-escalation trigger; round-cap / must-shrink / MANUAL from review; redteam FINDINGS; substantive merge conflict.
- **Notify-and-continue (log it, keep going):** clarity WARN; negative-space WARN; an applied bounded self-refine; round-1 REWORK; redteam out-of-model items; an incidental finding surfaced at discovery per step 3.

## Cross-cutting rules this skill must obey

- **Delegates, never re-derives.** Read each subcommand file fresh at its step; inherit its gates untouched.
- **Never pushes.** Merge is local; the push remains the user's call.
- **No developer-subagent.** The orchestrator is the developer (same as every other `/m1-tick` path).
- **No shortcuts.** A blocker is a halt or an escalation, never `--no-verify` / `-DskipTests` / `git reset --hard` / a weakened test.
- **One self-refine, prose-only.** The single autonomy `run` adds over the subcommands is bounded exactly as step 2 states; structural blockers and a second FAIL always surface to the human.
