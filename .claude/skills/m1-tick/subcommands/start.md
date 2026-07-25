# /m1-tick start

Begin work on a ticket: ticket-readiness pre-flight (mechanical linter + developer self-check), status flip, branch creation, optional Plan outline. Invocation: `start <id> [--parallel]`.

> **The clarity-reviewer subagent is gone (cutover 2026-07-19).** It FAILed on 133 tickets over two months at a rate that never improved and, by the end, rejected every ticket the process authored — while ~90% of its catches were mechanical (spec-anchor/forward-ref/empty-out_of_scope/prose-verb) and the rest were "the ticket is wrong about the code," which the developer catches for free the moment they open the code at implementation. The mechanical half is now `scripts/lint-ticket.py` (deterministic, instant, no escalate→refine→recommit cycle); the judgment half is a developer self-check in the main session's own context (step 1b). The one genuinely-valuable judgment catch — CLASS-COMPLETENESS — moved to where it is grounded: the census is authored as a body section + acceptance item, the developer re-runs its grep live at start, and the reviewer verifies the disposed sites against the diff.

Preconditions (refuse and explain if any fail):

- The ticket exists and `status: pending`.
- All `blocked_by` are `status: done`.
- The working tree is clean (`git status` shows no uncommitted changes), unless `--parallel` is set.
- If not `--parallel`: there is no other ticket with `status: in-progress` or `status: in-review`.
- If `--parallel`:
  - The new ticket and every in-flight ticket BOTH declare a non-empty `files_scope`, AND the new ticket's `files_scope` and `out_of_scope` entries are provably disjoint from every in-flight ticket's. Tickets without `files_scope` (purely numeric `files_budget`) cannot start in parallel — the skill cannot mechanically prove disjointness without a path list.
  - Neither the new ticket nor any in-flight ticket has `migration_touch: true`.

Steps:

0. **Ground on the ticket before any other tool call.** This step runs ALONE in its own turn — do NOT batch it with the lint run, the status edit, code surveys, Writes, or `mvn`. Those all depend on facts this step establishes; issuing them in the same batch means acting on a *guessed* ticket subject before the Read result is in context (the exact failure that motivated this step).
   - Read the ticket file in full (the whole frontmatter AND body — not just the title).
   - **Inventory worktrees.** Run `git worktree list`. If any per-ticket worktree already holds this ticket's branch (`m1/M1-NNN-*`), or another ticket is `in-progress` in a worktree, surface it before touching anything — a case-variant worktree name can hide where the real branch lives, and the sequential-start precondition ("no other ticket in-progress") is worktree-blind without this. (Observed: M1-054, 2026-05-24.)
   - Confirm each `files_scope` path's parent module/package resolves on disk (Glob/Bash `test -e`). A path under a module that doesn't exist is a sign the ticket subject was misread or the ticket is stale — stop and surface it.
   - Present ONE grounding line to the user via a **blocking `AskUserQuestion` call** — never as plain printed text, which can be buried among tool output and silently scrolled past (a printed line failed exactly this way once):
     ```
     GROUNDED M1-NNN · "<verbatim title>" · <N> acceptance items · module/scope: <top-level path(s)> · scope resolves on disk: yes|no
     ```
     The question shows the grounding line and asks the user to confirm it matches the ticket they intended. Options: "Confirmed — proceed" and "Wrong ticket — stop". The flow physically stops until the user answers; this is the user's checkpoint to *verify* the right ticket was read, not a courtesy print they have to trust.
   - On "Wrong ticket — stop" (or any non-confirmation answer): stop, surface what was read vs. what was expected, and wait for instructions. Do not proceed to step 1.
   - Only after the user confirms do you proceed to step 1. Discovery-then-act, never discovery-and-act in one batch. This applies for the rest of the flow too: never bundle a mutating or expensive call (Write/Edit/`mvn verify`) into the same batch as the Read/Grep/Glob calls whose results it depends on.

1. **Ticket-readiness pre-flight.** Two parts, both in the main session — no subagent, no rendered prompt.

   **1a. Mechanical lint (deterministic gate).** Run:

   ```
   python3 scripts/lint-ticket.py <repo-relative path to the ticket file>
   ```

   The linter is the machine-checkable half of the old clarity gate: `SPEC-REFS-RESOLVABLE`, `OUT-OF-SCOPE-PRESENT`, `FORWARD-REFERENCE-RESOLVABLE`, `SECURITY-FLAG-INFERENCE`, `CENSUS-PRESENT-IF-CLASS-SCOPED`, `FILES-SCOPE-COVERAGE`, `PROSE-VERB-IN-VERIFY` (full list in the script's docstring). Exit 1 = at least one BLOCKER; exit 0 = clean or WARN-only.
   - **Exit 1 (BLOCKER)** → refuse the start. Print the blocker lines verbatim and tell the user to fix the ticket file directly and re-run `/m1-tick start {{ID}}`. This is a mechanical fix (a missing `out_of_scope`, an unresolved `spec_ref`, an unfiled `blocked_by` ID) — the user edits the file and re-runs; there is NO `escalate → refine` ceremony, because there is no design judgment to escalate. Status stays `pending` (it never reached `in-progress`). For the `run` orchestrator, this is the bounded-self-refine path — the orchestrator may fix a pure-mechanical blocker in the ticket file itself and re-run, per [`run.md`](run.md) step 2.
   - **Exit 0 with WARN lines** → print them; continue. WARNs are advisory (a `SECURITY-FLAG-INFERENCE` nudge, a census-missing nudge, a prose-verb acceptance item). The developer weighs each in step 1b.
   - **Exit 0 clean** → continue.

   **1b. Developer self-check (judgment, in-context).** You (the main session) have already Read the whole ticket at step 0. Before flipping status, apply this checklist against the ticket AND the code it names — this is the judgment half the subagent used to perform, done now by the party that will actually implement it:
   - **Implementable-as-written.** Can you name, for every `acceptance:` item, the concrete edit or test that satisfies it, without guessing at an ambiguous phrase? If an item is genuinely ambiguous in a way that changes what you would build, that is the one case to raise — see below.
   - **Ticket-vs-code truth.** For every factual claim the ticket makes about *existing* code ("X already does Y", "the handler at Z omits W"), spot-check the cited `path:line` or run the grep. A ticket built on a false premise about current code is the single largest refine cause in the corpus (25 of 133); the developer catches it for free here by opening the code. If a claim is false, the ticket's premise may be wrong — treat it as the ambiguity case.
   - **Census truth (only if the ticket is class-scoped** — it carries a `## Census` section, or lint raised `CENSUS-PRESENT-IF-CLASS-SCOPED`**).** Re-run the census enumeration grep NOW and confirm the sites it returns match the section's disposition table (or, if lint flagged a missing census, decide whether the ticket really is class-scoped and needs one). This is the CLASS-COMPLETENESS catch, grounded in a live grep instead of a subagent's prose reading.
   - **Control preservation (only if the ticket reroutes, replaces, or re-parameterizes an existing code path).** Open the path the diff will displace and enumerate what it does that is NOT in the new path's job description — `sanitize`/redaction calls, audit-log emissions, authorization checks, validation, and the **unit** each operates on (one field? one record? a concatenation?). Do the same for the tests that will be retargeted: which of their assertions pin a *security* property rather than a functional one? Anything you find that the ticket's `acceptance:` does not already require must be added to it now. This is the §10 rule in [`engineering-rules-verbatim.md`](../../../../docs/process/engineering-rules-verbatim.md), and it is a ticket-authoring step by design: these obligations are invisible at implementation time precisely because the control being displaced usually had no test of its own. A grep for the security-relevant collaborators on the old path (`sanitize`, `AuditLogger`, `is_admin`) against the new one is most of the work. (M1-694: three redteam rounds, the first two findings being one relocated `sanitize` call and the audit row that rode on it.)
   - **Resolution.** A pure-mechanical or prose defect you can fix without changing scope or intent (fix a typo, tighten an acceptance phrase, add the census table) → fix the ticket file inline, note it in one line, continue. A genuine ambiguity or a wrong-premise that changes *what* gets built, or *whether* it should → **one blocking `AskUserQuestion`** stating the ambiguity and the options, the recommended one first. This is a question, not an escalation: it costs one turn, not a refine→recommit→re-gate cycle. Do NOT invent ambiguity to be safe — silence on a clear ticket is correct.

   **Record the outcome** under `clarity_check:` in ticket frontmatter (the field keeps its name for now; it records ticket-readiness, not a subagent verdict):
   ```yaml
   clarity_check:
     date: <YYYY-MM-DD>
     verdict: <PASS | WARN | FAIL>   # FAIL only if you are refusing the start (unresolved lint BLOCKER)
     warnings: [<lint WARN strings + any self-check note>]
     blockers: [<lint BLOCKER strings, if refusing>]
   ```
   On a refused start (lint BLOCKER unresolved), stop here — status stays `pending`, do not proceed to step 2.
2. Set the ticket's frontmatter `status: in-progress`. Update `last_updated` to today's date.
3. Compute slug from the title per the canonical rule in [`docs/process/workflow.md`](../../../../docs/process/workflow.md) §"Naming conventions (slug, branch, ticket file)".
4. Branch:
   - **Sequential:** `git checkout -b m1/M1-NNN-<slug>` from `main`.
   - **Parallel:** create a worktree via `Agent(isolation: "worktree")` and run the rest of the flow inside it.
   - **Session already inside a worktree:** if this session's own cwd is a per-ticket worktree rather than the primary checkout (`git rev-parse --show-toplevel` differs from where `main` is checked out — compare against `git worktree list`), the sequential `git checkout -b` creates the branch *in that worktree*, leaving `main` checked out in the primary. This is fine for `start`/`review`/`commit` (none of them touch `main`), but `/m1-tick merge` must then drive the squash against wherever `main` lives — it is worktree-aware for exactly this case (see [`merge.md`](merge.md) step 2).
   - **Absolute paths inside a worktree:** every Write/Edit absolute path must carry the worktree prefix (`<repo-root>/.claude/worktrees/<id>/...`), never the primary checkout's root — a primary-root absolute path silently writes to the main checkout instead of the branch. Verify with `git rev-parse --show-toplevel` before the first Write. (Hit twice on M1-124, 2026-06-02.)
5. If `complexity: high`: pre-allocate the outline sidecar path at `target/m1-tick-outline-{{ID}}.md` and the substituted-prompt path at `target/m1-tick-prompt-plan-{{ID}}.txt`. Render the prompt via Bash (the render-script pattern — the main session never Reads `docs/process/plan-prompt.md`):

   ```
   python3 scripts/m1-render-prompt.py \
     docs/process/plan-prompt.md \
     target/m1-tick-prompt-plan-{{ID}}.txt \
     TICKET_ID={{ID}} \
     TICKET_FILE_PATH=<repo-relative path to the ticket file> \
     OUTLINE_FILE_PATH=target/m1-tick-outline-{{ID}}.md
   ```

   The script substitutes three placeholders only: `{{TICKET_ID}}`, `{{TICKET_FILE_PATH}}`, `{{OUTLINE_FILE_PATH}}`. Spawn the subagent with the stub:

   ```
   Agent(
     subagent_type: "plan-writer",
     description: "Implementation outline M1-NNN",
     prompt: "Ultrathink. Read target/m1-tick-prompt-plan-{{ID}}.txt and execute the instructions in that file. Everything you need (ticket path, outline sidecar path, the full procedure) is in that file. This is a complexity:high ticket — spend the thinking budget on cross-cutting consequences, API-surface audits of every class cited in acceptance items, and ground-truth verification of every claim the outline would make about existing code."
   )
   ```

   Foreground. The `plan-writer` agent is defined at `.claude/agents/plan-writer.md` and has `Read, Grep, Glob, Write` capability — Write is required so the agent can author the outline sidecar directly. The built-in `Plan` subagent type is read-only (Claude Code harness enforces no Write/Edit) and would fail at the sidecar-Write step; do NOT substitute `subagent_type: "Plan"` here.
   - If the chat reply begins with `## OUTLINE FAILED`, fire `escalate` with `reason: outline-fail`. The commit message records the escalation; git log is the audit trail. The plan-writer subagent did not Write the sidecar in this branch; do NOT set an `outline_file:` pointer on the ticket. The branch created in step 4 is left in place (deleted by `abort` if the user chooses to abandon, or reused after `refine`).
   - Otherwise the chat reply is the three-line success form (`OUTLINE: PASS` / `Outline file: <path>` / `Risks: <integer>`). Parse it to confirm the success verdict and capture the risk count. Set ticket frontmatter `outline_file: target/m1-tick-outline-M1-NNN.md` as a one-line pointer to the sidecar the subagent Wrote. Do NOT append the outline body to the ticket — the sidecar IS the outline. The developer (the main conversation) reads the sidecar before touching code.
6. Regenerate `STATUS.md` via `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md' docs/plan/m1/STATUS.md` (Bash tool). The script writes only the destination path; if it exits non-zero, surface stderr and refuse to proceed.
7. Print:

```
Started M1-NNN on branch m1/M1-NNN-<slug>.
Ticket-readiness pre-flight: <PASS | WARN with N warnings>
out_of_scope: <list>; round_cap: <n>.
Implement the ticket per its §Acceptance, then run `mvn verify`,
then `/m1-tick review M1-NNN`.
```

After this point, the main conversation is the developer. Implementation happens in normal Edit/Write/Bash calls. Do NOT spawn a developer-subagent.
