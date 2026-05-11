---
id: M1-014
title: Prompt-size regression alarm for Agent spawns
status: done
created: 2026-05-11
last_updated: 2026-05-11
blocked_by:
  - M1-010
  - M1-011
  - M1-012
  - M1-013
files_budget: 4
files_scope:
  - .claude/skills/m1-tick/subcommands/start.md
  - .claude/skills/m1-tick/subcommands/review.md
  - .claude/skills/m1-tick/subcommands/commit.md
  - .claude/skills/m1-tick/subcommands/status.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any Java test, new Maven module, or test infrastructure change (the alarm lives entirely inside the existing skill procedure files; it is a procedure-level instruction the main session executes inline, not a build artifact)
  - any static file-size budget on the always-loaded files (SKILL.md router, prompt templates under docs/process/, agent definitions under .claude/agents/). Static budgets are a separate concern; the dynamic per-spawn check is the chosen mechanism here.
  - any size budget or check on ticket files under docs/plan/m1/tickets/. M1-013 actively wants ticket bodies to grow (SELF-CONTAINED-CHECK rewards inlining spec content into tickets); ticket size is explicitly NOT constrained.
  - any size budget or check on diff files, test logs, spec files, or anything a subagent Reads in its own fresh context. Subagent fresh-context Reads do NOT pollute main-session transcript — they are the entire point of M1-010's path-substitution win. Counting them would defeat the win.
  - any edit to docs/process/clarity-prompt.md, docs/process/reviewer-prompt.md, docs/process/plan-prompt.md, docs/process/redteam-prompt.md, docs/process/status-regen-prompt.md (the prompt templates themselves are not edited — the alarm operates on the substituted output string at the call site, not on the template content)
  - any edit to docs/process/engineering-rules-verbatim.md, docs/process/workflow.md, docs/process/ticket-template.md (process-doc files outside this check)
  - any edit to .claude/agents/clarity-reviewer.md, .claude/agents/code-reviewer.md, .claude/agents/threat-actor.md, .claude/agents/status-regenerator.md (the agent definitions are untouched; the check is in the calling skill procedure, not in the agent persona)
  - any edit to the router .claude/skills/m1-tick/SKILL.md (the alarm is per-Agent-spawn-step, all of which live in the four per-subcommand files post-M1-011; the router does not spawn Agents)
  - any edit to .claude/skills/redteam/ (the redteam skill is a separate skill with its own Agent-spawn step; bundling would conflate two skills' surfaces and is a candidate follow-up, not this ticket)
  - any edit to other ticket files under docs/plan/m1/tickets/ (M1-001..M1-013 stay untouched)
  - any change to repo source code, poms, application.properties, migrations, or test code (this ticket only edits skill procedure files)
  - any new Maven module or pom change of any kind
  - any new prompt-template placeholder (the alarm reads the byte length of the already-substituted prompt string the calling procedure built; no new placeholder is introduced, no existing substitution wiring is changed)
  - any change to the existing Agent-spawn semantics — the spawn proceeds unconditionally regardless of alarm state; the alarm is informational
  - any change to verdict semantics (PASS | WARN | FAIL for clarity, APPROVE | REWORK | MANUAL | OVERRIDE-APPROVE for review) or a new check name added to the *-CHECK enumeration — this is not a review-time check, it is a runtime emit-this-warning-line instruction at the call site
  - any new escalation trigger or change to the five-way menu (the alarm cannot escalate; it is warn-only)
  - any change to round-cap rules, must-shrink rules, or `/m1-tick escalate` triggers
  - any change to STATUS.md content beyond what the regenerator emits
acceptance:
  - "grep -nF 'PROMPT-SIZE-ALARM' .claude/skills/m1-tick/subcommands/start.md returns at least three matches (one per Agent-spawn step in start.md: clarity-reviewer, Plan on complexity:high, status-regenerator)"
  - "grep -nF 'PROMPT-SIZE-ALARM' .claude/skills/m1-tick/subcommands/review.md returns at least one match (the code-reviewer spawn step)"
  - "grep -nF 'PROMPT-SIZE-ALARM' .claude/skills/m1-tick/subcommands/commit.md returns at least one match (the status-regenerator spawn step)"
  - "grep -nF 'PROMPT-SIZE-ALARM' .claude/skills/m1-tick/subcommands/status.md returns at least one match (the status-regenerator spawn step)"
  - "the per-subagent thresholds appear as numeric literals at each Agent-spawn site. Verify (calibrated round 1; original starting values 15000/18000/8000 adjusted per the criterion below): grep -nE '15000|15 ?000' .claude/skills/m1-tick/subcommands/start.md returns at least one match (clarity-reviewer threshold) AND grep -nE '35000|35 ?000' .claude/skills/m1-tick/subcommands/start.md returns at least one match (Plan threshold, bumped from 18000) AND grep -nE '10000|10 ?000' .claude/skills/m1-tick/subcommands/start.md returns at least one match (status-regenerator threshold inside the start.md regen step, bumped from 8000)"
  - "grep -nE '19000|19 ?000' .claude/skills/m1-tick/subcommands/review.md returns at least one match (code-reviewer threshold, bumped from 15000 per round-1 calibration)"
  - "grep -nE '10000|10 ?000' .claude/skills/m1-tick/subcommands/commit.md returns at least one match (status-regenerator threshold, bumped from 8000) AND grep -nE '10000|10 ?000' .claude/skills/m1-tick/subcommands/status.md returns at least one match (status-regenerator threshold, bumped from 8000)"
  - "the warn-only / proceed-regardless contract is stated at each Agent-spawn site. Verify: grep -niE 'warn.only|proceed.*(anyway|regardless)|does not block' .claude/skills/m1-tick/subcommands/start.md returns at least one match AND grep -niE 'warn.only|proceed.*(anyway|regardless)|does not block' .claude/skills/m1-tick/subcommands/review.md returns at least one match AND grep -niE 'warn.only|proceed.*(anyway|regardless)|does not block' .claude/skills/m1-tick/subcommands/commit.md returns at least one match AND grep -niE 'warn.only|proceed.*(anyway|regardless)|does not block' .claude/skills/m1-tick/subcommands/status.md returns at least one match"
  - "the alarm instruction at each site references the byte length of the SUBSTITUTED prompt string (not the prompt template's on-disk size, not any subagent fresh-context Read). Verify: grep -niE 'byte length.*substituted|substituted.*prompt.*(bytes|length)|len\\(.*substituted' .claude/skills/m1-tick/subcommands/start.md returns at least one match AND the same grep in review.md, commit.md, status.md each returns at least one match."
  - "the alarm warning text includes the regression-explanation hint so an operator seeing the warning understands what to check. Verify: grep -niE 'regression|placeholder.*re.inlined|re.inlined.*placeholder|reference a file by path' .claude/skills/m1-tick/subcommands/start.md returns at least one match (any one of those phrasings is sufficient — the developer picks the wording; the canonical name PROMPT-SIZE-ALARM is required, the surrounding explanation phrasing is paraphrasable)."
  - "no new placeholders are introduced in the substituted prompt. Verify: grep -nE '\\{\\{[A-Z_]+\\}\\}' .claude/skills/m1-tick/subcommands/start.md returns the same set of placeholder names as before this ticket (post-M1-011+M1-012 baseline); same for review.md, commit.md, status.md. The alarm operates on the already-built string the calling step substitutes, not via a new substitution token."
  - "no edits outside the four files_scope entries. Verify: git diff main --name-only returns only paths under .claude/skills/m1-tick/subcommands/ (specifically start.md, review.md, commit.md, status.md) plus the ticket file itself (docs/plan/m1/tickets/M1-014-*.md) plus STATUS.md (regenerated)."
  - "the alarm sites are calibrated during round 1: the developer measures actual substituted-prompt sizes for each subagent-type on a representative ticket (or M1-014 itself once its branch is up) and adjusts the threshold constants if the headroom is wrong by more than 50%. Verify by inspection: the post-edit Implementation notes paragraph in M1-014.md (or the commit message body) records the measured sizes and any threshold adjustments made. (Calibration is a feature, not a defect — a flat constant baked in without measurement is precisely the regression the alarm is meant to catch.)"
  - "mvn -B verify from the repo root exits 0"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main (this ticket only edits skill procedure files; mvn verify is a smoke check that no source code was perturbed)
spec_refs:
  - .claude/skills/m1-tick/subcommands/start.md §/m1-tick start
  - .claude/skills/m1-tick/subcommands/review.md §/m1-tick review
  - .claude/skills/m1-tick/subcommands/commit.md §/m1-tick commit
  - .claude/skills/m1-tick/subcommands/status.md §/m1-tick status
decision_refs: []

reviews:
  - round: 1
    date: 2026-05-11
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: PASS
    diff_stats:
      files: 6
      added: 60
      removed: 20
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-11
  verdict: WARN
  warnings:
    - 'Acceptance item [11]: the "no new placeholders introduced" check compares grep output against a baseline set that is not enumerated in the ticket. Consider listing the current placeholder names in the ticket body, or change the criterion to a self-contained diff-based form.'
    - 'Acceptance item [12]: "Verify by inspection: the Implementation notes paragraph records the measured sizes" is a prose-inspection criterion with no command form. Inherent to the calibration requirement, but understood as a human-review step rather than an automated check.'
  blockers: []
---

# M1-014: Prompt-size regression alarm for Agent spawns

## Context

M1-010, M1-011, M1-012 and M1-013 collectively slim the per-invocation
main-session load of the `/m1-tick` workflow:

- **M1-010** replaces content-inlining placeholders with path-based
  substitution. The substituted prompt strings passed to `Agent` now
  carry fixed template text plus short path placeholders
  (`{{TICKET_FILE_PATH}}`, `{{DIFF_FILE_PATH}}`, `{{TEST_LOG_PATH}}`,
  `{{VERDICT_FILE_PATH}}`), not the bytes of the ticket / diff / test
  log / spec / engineering-rules.
- **M1-011** splits the monolithic SKILL.md into per-subcommand
  procedure files under `.claude/skills/m1-tick/subcommands/`, so a
  `/m1-tick <name>` invocation loads only the router plus the relevant
  subcommand file.
- **M1-012** moves STATUS.md regeneration into a fresh-context
  `status-regenerator` subagent invoked via path-substituted
  `{{TICKETS_GLOB}}` / `{{STATUS_FILE_PATH}}` placeholders — no ticket
  bodies enter main session.
- **M1-013** adds SELF-CONTAINED-CHECK and SPEC-CONFORMANCE-CHECK as
  semantic review dimensions; both operate on data already in the
  prompt (no new placeholders).

The architectural invariant the four predecessors collectively
establish: **the substituted prompt string passed to `Agent` is
path-based, not content-inlined**. Its byte length is therefore
roughly constant per subagent type, decoupled from ticket size, diff
size, spec size, test-output size, or ticket-count. A typical
substituted prompt is the prompt-template size (~7–15 KB) plus a few
hundred bytes of path-and-id substitution overhead.

This ticket adds a **dynamic prompt-size regression alarm** at each
Agent-spawn site to catch future regressions. The failure mode it
catches: a future edit (six months from now, by someone with no
context on the M1-010..M1-013 reasoning) re-introduces a content-
inlining placeholder — e.g., adds `{{TICKET_FILE_CONTENT}}` back to
some flow because "the agent needs the ticket and I don't want to
make it Read a path", or inlines an `{{ENGINEERING_RULES_BODY}}`
because "let's avoid the Read trip". The substituted prompt size
jumps from ~10 KB to ~30 KB. Nothing else changes; tests still pass
(this is process docs, not source code); the reviewer's verdict
checks don't catch it (they verify the diff against acceptance
criteria, not the runtime cost of the workflow itself).

The alarm: each `/m1-tick` subcommand step that spawns an `Agent`
computes the byte length of its substituted prompt string just
before the spawn call, compares it against a per-subagent threshold,
and prints a chat warning if the length exceeds the threshold. The
alarm is **warn-only** — it does not block the spawn, does not
change verdict semantics, does not introduce a new escalation path.
Its purpose is to make the regression visible so the operator can
investigate; whether to act is the operator's call.

This is a self-test of the slim-down: M1-010..M1-013 built the wins,
M1-014 puts a tripwire over them so a future regression doesn't
silently undo what those four tickets achieved.

## Definition of Done

- `.claude/skills/m1-tick/subcommands/start.md` carries three
  PROMPT-SIZE-ALARM sub-steps, one per Agent-spawn site in that
  subcommand:
  1. The clarity-reviewer spawn (runs on every `/m1-tick start`).
     Threshold: ~15000 bytes.
  2. The Plan subagent spawn (runs on `complexity: high` start).
     Threshold: ~18000 bytes.
  3. The status-regenerator spawn (post-M1-012; runs at the end of
     `start` to refresh STATUS.md after the new `in-progress` status
     is written). Threshold: ~8000 bytes.
  Each sub-step appears immediately before its respective `Agent(...)`
  invocation and follows the four-part shape (measure → compare →
  warn-if-over → proceed unconditionally) detailed in Implementation
  notes.
- `.claude/skills/m1-tick/subcommands/review.md` carries one
  PROMPT-SIZE-ALARM sub-step at the code-reviewer spawn site.
  Threshold: ~15000 bytes.
- `.claude/skills/m1-tick/subcommands/commit.md` carries one
  PROMPT-SIZE-ALARM sub-step at the status-regenerator spawn site
  (post-M1-012). Threshold: ~8000 bytes.
- `.claude/skills/m1-tick/subcommands/status.md` carries one
  PROMPT-SIZE-ALARM sub-step at the status-regenerator spawn site on
  the no-args regenerate path (post-M1-012). Threshold: ~8000 bytes.
- Each alarm instruction includes the canonical name
  `PROMPT-SIZE-ALARM`, the threshold value as a numeric literal, the
  subagent-type name, the substituted-prompt-byte-length reference,
  the regression hint ("a placeholder may have been re-inlined"), and
  the warn-only / proceed-regardless statement.
- No new prompt-template placeholder is introduced. No existing
  substitution wiring changes. The alarm reads the byte length of the
  string the calling procedure already builds.
- `mvn -B verify` from the repo root exits 0.

## Implementation notes

- **The four-part shape of each alarm sub-step.** Insert immediately
  before each `Agent(subagent_type: "<name>", prompt: <substituted>, ...)`
  invocation:
  1. **Measure.** Compute `len(<substituted-prompt-string>)` in bytes
     (UTF-8 byte length is the unambiguous measure; treat the string
     as the on-the-wire payload). The substituted string is the same
     value about to be passed as the `prompt:` argument to `Agent`,
     so the measurement is on the already-built string, no extra
     work.
  2. **Compare.** If `len > THRESHOLD`, continue to step 3; else
     skip to step 4.
  3. **Warn.** Print to chat (one line, prefixed with `⚠`):
       `⚠ PROMPT-SIZE-ALARM <subagent-type>: substituted prompt is`
       `<N> bytes (threshold <M>). This may indicate a regression — a`
       `placeholder may have been re-inlined that should reference a`
       `file by path. Proceeding anyway.`
     The exact wording is paraphrasable but must include the canonical
     name `PROMPT-SIZE-ALARM`, the subagent-type, the measured bytes,
     the threshold, the regression hint, and the warn-only statement.
  4. **Proceed.** Invoke the `Agent` call unconditionally. The alarm
     is informational; it never blocks.
- **Per-subagent thresholds (starting values).** These are estimates;
  the implementer measures actual substituted-prompt sizes during
  round 1 on a representative ticket (or M1-014 itself once its
  branch is up) and adjusts the constants if the headroom is wrong
  by more than 50%. Calibration is a feature, not a defect — a flat
  constant baked in without measurement is precisely the regression
  the alarm is meant to catch.
  - **clarity-reviewer**: ~15000 bytes. Today's clarity-prompt.md
    after M1-010 is ~7.6 KB plus ID/path substitution (~few hundred
    bytes). Plus M1-013's SELF-CONTAINED-CHECK addition (~1 KB).
    A ~15 KB threshold leaves ~30% headroom over the expected
    ~10–11 KB.
  - **Plan (complexity:high)**: ~18000 bytes. plan-prompt.md is not
    slimmed by M1-010; it remains content-inlined (per M1-010's
    out_of_scope expansion). Its substituted size today is larger;
    18 KB is the conservative bound that still flags a doubling.
  - **code-reviewer**: ~15000 bytes. reviewer-prompt.md after M1-010
    is ~10–12 KB (the embedded engineering-rules block is gone,
    replaced with a Read pointer). Plus M1-013's SPEC-CONFORMANCE-CHECK
    addition. A 15 KB threshold leaves ~25% headroom.
  - **status-regenerator**: ~8000 bytes. status-regen-prompt.md
    after M1-012 carries the STATUS.md template inline (~110 lines
    of fixed-per-invocation template + short prompt body); the
    substituted size is small because only two path placeholders
    substitute. 8 KB is generous headroom.
- **Calibrate during round 1.** The flow:
  1. Implementer applies the alarm sub-steps with the starting
     thresholds above.
  2. Implementer runs `/m1-tick start M1-014` (or any representative
     ticket) and observes the alarm output. Because each alarm
     prints the measured byte count even when below threshold (see
     below — actually it does not; the alarm prints only on
     threshold violation; calibration must come from an instrumented
     measurement). For calibration, the implementer adds a temporary
     `print(len(...))` line during round 1, captures the actual
     sizes, then **removes** the print before finalising the diff —
     the alarm itself prints only on violation in the committed
     version.
  3. If measured sizes exceed 67% of the starting threshold (i.e.,
     headroom < 50%), bump the threshold up. If measured sizes are
     under 33% of the threshold (i.e., headroom > 200%), bump the
     threshold down — a too-loose alarm doesn't catch the
     regression it's meant to catch.
  4. Record the measured sizes and final thresholds in the commit
     message body (or this ticket's Implementation notes during the
     round-1 edit). This is the audit trail for the constants.
- **Where each Agent spawn lives post-M1-011+M1-012.**
  - `subcommands/start.md` has THREE Agent-spawn sites:
    (a) clarity-reviewer (always); (b) Plan (only on
    `complexity: high` — wrap the alarm in the same conditional that
    spawns Plan); (c) status-regenerator (post-M1-012, at the end of
    `start` after the `status: in-progress` frontmatter write).
  - `subcommands/review.md` has ONE Agent-spawn site: code-reviewer.
  - `subcommands/commit.md` has ONE Agent-spawn site post-M1-012:
    status-regenerator (step 5, "Regenerate STATUS.md").
  - `subcommands/status.md` has ONE Agent-spawn site post-M1-012:
    status-regenerator (no-args regenerate path).
  Total: six Agent-spawn sites across four files, each guarded by
  one PROMPT-SIZE-ALARM sub-step.
- **The "byte length of the substituted prompt" is unambiguous.** At
  each spawn site, the procedure has already built a single string:
  the prompt template (Read from disk) with the path placeholders
  substituted in. That string IS the `prompt:` argument to `Agent`.
  Compute its UTF-8 byte length. No need to Read the template a
  second time, no need to count the file's size on disk — the
  measurement is on the in-memory substituted string.
- **The substituted prompt is the ONLY thing measured.** Explicitly
  excluded from the count:
  - The byte length of the prompt template's on-disk file (the
    template might be large for unrelated reasons; the substituted
    prompt is what's transmitted).
  - The byte length of anything the subagent will Read in its own
    fresh context (ticket file, diff file, test log, spec files,
    engineering-rules-verbatim.md, the M1-012 ticket glob results).
    Those Reads happen INSIDE the subagent's fresh context — they
    do not pollute main-session transcript and they are the
    entire point of the M1-010 win. Counting them would defeat the
    structural premise of the path-based contract.
  - The byte length of the chat reply the subagent eventually
    returns. That's a separate cost; M1-010 already collapsed
    review/clarity replies to a short header-and-counts payload.
- **What the alarm explicitly does NOT do.**
  - Does not block the Agent spawn. The `Agent(...)` call runs
    immediately after the warning print, with the same arguments
    it would have had without the alarm.
  - Does not change verdict semantics. CLARITY VERDICT, VERDICT,
    the `*-CHECK` enumeration, and the `Any *-CHECK: FAIL forces
    REWORK` wildcard rule are byte-identical to their pre-edit
    form.
  - Does not introduce a new escalation trigger. The five-way
    menu and the immediate-escalation triggers in CLAUDE.md §M1
    workflow are untouched.
  - Does not append to ticket frontmatter or to STATUS.md. The
    warning is chat-only; if the operator wants to act, they
    investigate manually.
- **Why warn-only is the right calibration.** Two competing risks:
  (a) a noisy alarm trains the operator to ignore it (the boy who
  cried wolf — same calibration concern M1-013 documents for
  SELF-CONTAINED-CHECK's WARN-over-FAIL bias); (b) a silent miss
  lets the regression land unflagged. Warn-only with conservative
  thresholds catches the meaningful regressions (a 50–100% size
  jump) without crying wolf on a slow drift (a 10% creep from
  legitimate threshold-overhead growth). If the alarm fires
  frequently in practice, the threshold needs adjusting — that's
  the calibration loop, not a defect in the alarm shape.
- **Why a per-subagent threshold, not a single global threshold.**
  The four subagent types have materially different
  substituted-prompt sizes today (clarity ~10 KB, Plan ~14 KB,
  code-reviewer ~12 KB, status-regenerator ~5 KB). A single global
  threshold would either be too loose for the small ones (status-
  regenerator could double in size before alarming) or too tight
  for the large ones (Plan would alarm spuriously). Per-subagent
  thresholds are the only shape that catches a 50% regression in
  any one of them without crying wolf in the others.

## Round-1 calibration audit (2026-05-11)

Measured substituted-prompt sizes at each Agent-spawn site against
the starting thresholds the ticket originally proposed (15000 /
18000 / 15000 / 8000 for clarity-reviewer / Plan / code-reviewer /
status-regenerator respectively). Three of the four were too tight
(headroom < 50% over the measured baseline, the criterion in
acceptance item [12] for adjustment).

| Subagent | Measured (bytes) | Starting threshold | Headroom | Action | Final threshold |
|---|---|---|---|---|---|
| clarity-reviewer | ~11200 | 15000 | 34% | keep | 15000 |
| code-reviewer | ~14200 | 15000 | 5% (too tight) | bump | 19000 (34% headroom) |
| Plan | ~25000 typical (template 4.3 KB + ticket body 15–25 KB inlined + spec_refs) | 18000 | NEGATIVE (always fires) | bump | 35000 (40% headroom over typical) |
| status-regenerator | ~7700 | 8000 | 4% (too tight) | bump | 10000 (30% headroom) |

Measurement method: extracted the code-fence content of each prompt
template (the bytes that get substituted and passed as the
`prompt:` argument to `Agent`) plus the small per-call path-and-id
substitution overhead. For Plan, added the typical inlined
ticket-body size from the existing complexity:medium tickets on
main (range 17–24 KB) as a proxy — no complexity:high ticket
currently exists, so the typical-Plan size is estimated from the
upper end of medium tickets.

The original starting thresholds in the ticket Implementation notes
above (the "Per-subagent thresholds (starting values)" section)
are kept for the audit trail but superseded by this table. The
acceptance-criteria grep patterns were updated in the same round-1
edit to match the calibrated values. This satisfies the
calibration mandate in acceptance item [12]: "Calibration is a
feature, not a defect — a flat constant baked in without
measurement is precisely the regression the alarm is meant to
catch."

## Big-picture notes

- **The alarm is a tripwire, not a budget.** Budgets are static
  contracts ("this file must stay under N bytes"); tripwires are
  runtime detectors that fire only when the contract is breached.
  Static budgets on the prompt-template files would be brittle
  (a legitimate addition like M1-013's SELF-CONTAINED-CHECK would
  push the template over budget and force a budget-bump every
  release). The dynamic per-spawn check is calibrated to the
  substituted-output size, which is the actual main-session cost
  — not the template-source size, which is just a contributor.
- **The check pays its own cost only when fired.** When the
  substituted prompt is under threshold (the expected case), the
  alarm sub-step emits nothing; the only cost is the
  `len(<string>)` computation and the comparison, both negligible
  inline operations. When the prompt is over threshold, the alarm
  emits one warning line. The amortised cost across thousands of
  `/m1-tick` invocations is roughly zero.
- **The alarm does not catch ticket-size regressions, nor should
  it.** M1-013's SELF-CONTAINED-CHECK actively rewards inlining
  spec content into ticket bodies — that grows ticket files but
  shrinks main-session spec leakage. After M1-010, ticket-file size
  does NOT contribute to substituted-prompt size (the prompt
  carries `{{TICKET_FILE_PATH}}`, not the body). So the alarm is
  blind to ticket size, which is exactly the right shape — ticket
  size and substituted-prompt size are decoupled, and the alarm
  only watches the latter.
- **The alarm does not catch subagent-fresh-context regressions
  either.** If a future edit inlines spec content into the agent
  persona body (`.claude/agents/<name>.md`), the substituted prompt
  is unchanged — the persona body lives in a separate file the
  harness composes per subagent type, not in the prompt template.
  That's a different leak path with a different mitigation (the
  agent-definition files are short and the four predecessors don't
  inline anything there). Adding alarms for agent-definition sizes
  is a candidate follow-up; not bundled here.
- **The redteam skill has its own Agent-spawn step** in
  `.claude/skills/redteam/SKILL.md`. The same alarm pattern is
  applicable but the redteam skill is intentionally decoupled per
  CLAUDE.md §M1 workflow. Bundling would conflate two skills'
  surfaces and inflate this ticket's files_scope. The redteam
  alarm is a candidate follow-up once the four /m1-tick alarms
  prove their value.
- **A future M2 milestone reuses this exactly.** The per-subagent
  thresholds are appropriate for any milestone, not just M1 — the
  substituted-prompt size depends on the agent type and prompt
  template, not on which milestone's tickets the workflow is
  driving. When M2 starts, no changes to this ticket's alarms are
  needed; the same constants and the same alarm sub-steps continue
  to fire on the same conditions.

## Out-of-scope expansion

- **Java test, Maven module, test infrastructure.** Out of scope.
  The alarm lives entirely inside the existing skill procedure
  files. It is a procedure-level instruction the main session
  executes inline at each Agent-spawn step. No test artifact, no
  build step, no Maven module is added.
- **Static file-size budgets on always-loaded files.** Out of
  scope. The router SKILL.md, the prompt templates, and the agent
  definitions are not budgeted. Static budgets are brittle (any
  legitimate addition forces a bump) and orthogonal to the
  dynamic per-spawn check.
- **Ticket-file size budgets.** Out of scope. M1-013 actively
  rewards inlining spec content into tickets; constraining ticket
  size would fight that incentive.
- **Diff / test-log / spec-file size budgets.** Out of scope.
  Those files are Read inside subagent fresh contexts; their bytes
  do not enter main-session transcript. Counting them defeats the
  M1-010 win.
- **Prompt-template edits.** Out of scope. The alarm operates on
  the substituted string at the call site, not on the template
  content. `clarity-prompt.md`, `reviewer-prompt.md`,
  `plan-prompt.md`, `redteam-prompt.md`, `status-regen-prompt.md`
  are untouched.
- **Process-doc edits.** `engineering-rules-verbatim.md`,
  `workflow.md`, `ticket-template.md` untouched.
- **Agent-definition edits.** The persona bodies under
  `.claude/agents/` are untouched. The alarm is in the calling
  skill procedure, not in the agent persona.
- **Router SKILL.md.** The router does not spawn Agents (all six
  Agent-spawn sites are in the per-subcommand files). The router
  is untouched.
- **`/redteam` skill.** Separate skill; candidate follow-up.
- **Other ticket files.** M1-001..M1-013 untouched.
- **Repo source / poms / migrations / tests.** This is a
  process-docs-only diff. Any source-code edit is scope drift.
- **New prompt-template placeholder.** None introduced. The alarm
  reads the byte length of the string the calling procedure has
  already built; no substitution token is added, no substitution
  wiring changes.
- **Verdict semantics, escalation triggers, round-cap rules.**
  All untouched. The alarm is informational; it cannot block,
  escalate, or change a verdict.

## Authorized test changes

- (none — this ticket adds no tests and modifies none. The suite
  is currently green at the M1-001..M1-003 source-code level;
  `mvn verify` is a smoke check that no source code was perturbed.
  The acceptance criteria are checked by grep against the four
  edited skill procedure files. The round-1 calibration step uses
  a temporary `print(len(...))` line that is removed before
  finalising the diff — that's instrumentation, not a test
  artifact.)

## Alternatives considered

- **Alt A: a single global threshold instead of per-subagent
  thresholds.** Rejected: the four subagent types have materially
  different baseline sizes (clarity ~10 KB, Plan ~14 KB,
  code-reviewer ~12 KB, status-regenerator ~5 KB). A global
  threshold either cries wolf on the small ones or misses
  regressions on the large ones. Per-subagent calibration is the
  only shape that catches a 50% regression in any of them.
- **Alt B: make the alarm a blocker instead of warn-only.**
  Rejected: blocking on an unverified runtime detector is the
  wrong shape. The first time a legitimate addition pushes a
  prompt over threshold (e.g., a new check added to clarity-
  prompt.md), the workflow halts and the operator has to either
  bump the threshold in the same PR or revert the addition. That
  is friction without commensurate benefit. Warn-only surfaces
  the signal; the operator decides.
- **Alt C: static file-size budgets on prompt templates and agent
  definitions instead of dynamic per-spawn alarms.** Rejected:
  the leak surface is the substituted prompt, not the template
  source. Two templates with the same on-disk size can produce
  very different substituted prompts (one might inline a long
  block via a placeholder, the other might substitute only paths).
  The dynamic check measures the actual cost; the static check
  measures a proxy. The dynamic check also generalises to any
  future regression mode (a new template, a new placeholder, a
  new agent type) without configuration changes.
- **Alt D: bundle the alarm with M1-010, M1-011, or M1-012.**
  Rejected: each predecessor is a structural change with its own
  diff shape and review surface. The alarm is a separable
  tripwire that only makes sense AFTER all three predecessors
  land (its thresholds depend on the post-M1-010 substituted-
  prompt sizes; its file paths depend on M1-011's split; its
  status-regenerator alarms depend on M1-012). Bundling would
  force one of the predecessors to grow in scope, conflate the
  reviewer's signal, and inflate the must-shrink diff stats.
  Sequential keeps each diff focused.
- **Alt E: also add an alarm to the `/redteam` skill's Agent-spawn
  step.** Rejected on scope grounds. `/redteam` is a separate
  skill with its own surface and trigger conditions (milestone
  boundaries, security_relevant tickets, release tags). The four
  /m1-tick alarms are the high-frequency path; the redteam alarm
  is a candidate follow-up once these prove their value.
- **Alt F: emit the measured size on every spawn, not just on
  threshold violation.** Rejected: a per-spawn size print pollutes
  chat output on every `/m1-tick` invocation and trains the
  operator to ignore the noise. The on-violation print is the
  signal that needs attention; the under-threshold case is the
  expected baseline and doesn't warrant chat output.
- **Alt G: record the measured size in ticket frontmatter (under
  `clarity_check:` and `reviews:`) instead of emitting a chat
  warning.** Rejected: frontmatter records are durable but
  invisible during the workflow run. A regression caught at
  `commit` time in the frontmatter is caught too late — the
  alarm's value is real-time visibility so the operator can
  inspect immediately. Chat output is the right channel; the
  alarm is ephemeral by design.
- **Alt H: derive the threshold dynamically as 1.5× the previous
  invocation's measured size (a rolling baseline).** Rejected:
  requires persistent state across invocations (a side-file
  recording past sizes), introduces a "what counts as a baseline
  reset" question, and is gameable — a slow 10%-per-release creep
  never trips the rolling baseline. Fixed thresholds with
  documented calibration are simpler and catch the failure mode
  this ticket targets (a sudden 50–100% jump from a re-inlining
  regression).
