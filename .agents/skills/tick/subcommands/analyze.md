# /tick analyze

Turn a problem brief into a deep, spec-grounded analysis and a set of small
tickets. This is the mandatory entry door of the tick flow — a ticket that
did not come out of `analyze` does not exist.

Invocation: `/tick analyze <brief>` where `<brief>` is the problem statement
(observed defect with evidence, a user report, a live-test finding with its
report section, a redteam finding, or a hurdle that needs decomposition).
For a long brief, take a pointer (`/tick analyze ".scratch/V1.1.0-TEST-REPORT-CLEAN-RUN.md §F3"`).

## Steps

1. **Resolve the brief.** If the brief is a pointer (path + optional
   `§section`), Read that section. State the problem in one or two lines to
   the user and wait for confirmation that this is the problem to analyze
   (a misread brief produces a useless analysis — confirm cheaply, once).

2. **Allocate the ID set.** Compute `NEXT_ID` = 1 + max over every
   `M\d+-\d+` seen in filenames under BOTH `docs/plan/m1/tickets/` and
   `docs/plan/m1/tick-tickets/` (and, for safety, `git log --grep "^M1-"`).
   The analyst drafts tickets from `NEXT_ID` upward.

3. **Render and spawn the analyst.** Pre-allocate paths:
   - analysis: `docs/plan/m1/tick-analysis/<slug>.md` — pre-allocated,
     but the analyst writes it ONLY for a 2+ ticket decomposition; a
     single-ticket outcome embeds the analysis in the ticket with
     `analysis_ref: self` and no analysis file.
   - tickets: `docs/plan/m1/tick-tickets/M<N>-NNN-<slug>.md` (one path per
     ticket the analyst will draft; the analyst may draft fewer than the
     count you allocate — it writes what it writes)
   Render `docs/process/analyst-prompt.md` via
   `scripts/m1-render-prompt.py` with `PROBLEM_BRIEF`, `ANALYSIS_FILE_PATH`,
   `TICKET_FILE_PATHS` (newline-joined), `NEXT_ID`, then spawn the
   `analyst` gate agent (`.opencode/agent/analyst.md`; fresh context; stub
   `Read <rendered-prompt-path> and follow it exactly. It names every input
   file and the output paths. Reply only in the format the prompt specifies.`).

4. **Read back and present.** Read the ticket files (and the analysis
   document if one was written) from disk. Validate the shape: a
   single-ticket outcome must carry `analysis_ref: self` and no stray
   analysis file; a 2+ ticket outcome must carry a real `analysis_ref`
   path. Present the user: the root cause (one line), the pitfall count,
   the ticket set with one line each. Then ask explicitly:
   **approve the set, or request changes, or drop.** A ticket file is never
   created/kept without this explicit confirmation. On rejection with
   changes: re-spawn the analyst with the change note appended to the
   brief (fresh context, cheap).

5. **Land the set.** On approval: leave the files in the working tree and
   tell the user they are ready to commit (`process:` prefix) or to refine
   by hand first. `/tick` itself does not commit.

6. **SPEC-GAP path.** If the analyst returns `ANALYSIS: SPEC-GAP`: present
   the gap (spec says X, problem demands Y, amendment needed Z) and the
   two options — raise a `spec:` amendment first (user drives it), or drop
   the problem. No tickets exist in this case.

## The analyst writes; the driver does not

Never author ticket content in the main session. The main session's job is
ID allocation, path allocation, rendering, spawning, read-back, and the
human gate. If the analyst's output fails `scripts/tick-lint.py` (run it
against every drafted ticket before presenting), return it to the analyst
with the lint findings rather than patching by hand — the analysis quality
is the gate's product.
