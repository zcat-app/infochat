# /tick review

Spawn the merged tick-reviewer gate. Invocation: `/tick review <id>`.

The gate agent is `tick-reviewer` (definition and spawn form per your
harness, `docs/process/harness-mapping.md` §2); the rendered prompt
template is `docs/process/tick-reviewer-prompt.md`. The
gate is the ONLY review in the tick flow — there is no separate security
re-audit loop and no code-reviewer.

## Steps

1. **Shape gate.** Run `python3 scripts/tick-lint.py
   docs/plan/m1/tick-tickets/M<N>-NNN-*.md`. BLOCKER → refuse review (fix
   the ticket first). WARNs → include in the mechanical report as notes.

2. **Collect the mechanical report.** Write to
   `.scratch/tick-mech-<ID>-r<round>.txt`. Every per-round artifact lives in
   `.scratch/`, never `target/` — a REWORK round runs `mvn verify` between
   reviews and the parent module's clean wipes `<repo-root>/target/`, so a
   round-N artifact written there is gone before round N+1 reads it.
    - `git add -A` on any untracked files, then `git diff $(git merge-base
      main HEAD) --stat` (files touched, added, removed). Full staging,
      NEVER `git add -N`: intent-to-add entries make the `git stash create`
      snapshot below fail with "Entry '…' not uptodate. Cannot merge", and
      the failure's empty output then silently trips the HEAD fallback on a
      DIRTY tree — round ≥ 2 would diff the full round diff, not the fix
      hunks (verified git 2.53.0; independently re-derived in the M1-847/
      865/866/915/917 rounds)
   - the ticket's files-to-touch list (from the Approach section) vs the
     diff's file set — both the untouched planned files and the unplanned
     touched files
   - any path matching an `out_of_scope` entry (grep the diff's paths)
   - the test-log path + mtime, and whether any staged-file mtime is newer
   - if the diff touches `docs/spec/**`: the user-approval evidence for
     the exact amendment wording (what was shown, when approved) — the
     gate's SPEC-TRUTHNESS style leg WARNs on a missing record
     (engineering-rules §12)
   - snapshot this round's working tree FIRST: `s=$(git stash create); echo
     "${s:-$(git rev-parse HEAD)}" > .scratch/tick-review-<ID>-r<round>.tree`.
     Rounds are not committed, so there is no prior HEAD to diff against;
     `stash create` records the tree as a dangling commit and does NOT touch
     the stash stack (which is shared across worktrees). It prints nothing
     when the tree is clean — the `HEAD` fallback keeps an empty file from
     silently turning the round-N diff into a `HEAD`-relative one
   - on rounds ≥ 2, the previous round's REWORK ITEMS block copied verbatim
     from `.scratch/tick-review-<ID>-r<round-1>.txt` — the reviewer
     dispositions every one of them, so a dropped item cannot pass by
     leaving no fix hunk behind
   - round-N stats: on rounds ≥ 2, the previous round's diff stats for the
     must-shrink check (growth beyond the named REWORK items is a WARN)
   - on rounds ≥ 2, the fix hunks — `git diff $(cat
     .scratch/tick-review-<ID>-r<round-1>.tree) $(cat
     .scratch/tick-review-<ID>-r<round>.tree)` written to
     `.scratch/tick-review-<ID>-r<round>-fix.diff`; that file, not the full
     diff, is what the reviewer evaluates this round
    - the round's diff file: round 1 → `git diff $(git merge-base main HEAD)
      > .scratch/tick-review-<ID>-r1.diff`; rounds ≥ 2 → the fix-hunks file
      above. Then the comment-cap report over it: `python3
      scripts/tick-comment-cap.py <that file>` — its WARN lines go in the
      report (they feed the reviewer's MAINTAINABILITY check)
    - placeholder scan over the round's added lines (the M1-949 vacuous-pin
      lesson: the fixture AND its validator constant both carried
      `ready=<redacted>;…` and were self-consistently green — no layer
      looked). Two tiers, both reported with file:line + matched text:
      tier 1 — fixture/resource paths, where the M1-949 class lives:
      `git diff $(git merge-base main HEAD) --
      ':(glob)**/src/*/resources/**' '*.jsonl' '*.sql' | grep -nE
      "^\+.*<(redacted|[a-z0-9-]+-pin|[a-z0-9-]+-secret)>"` — every hit
      needs explicit reviewer disposition: a placeholder in a VALUE
      position (a binding field, a constant) is FAIL material; prose
      adjacent to the real committed value (e.g. a rationale naming what
      a pin binds) is dispositioned with justification. Tier 2 — code and
      docs, where masking IS the correct §13 form in prose/javadoc: the
      same grep without the path filter, hits listed as masking-evidence
      for the same disposition.
    - probe finality (the M1-950 round-2 trap): when the ticket's mandated
      records (rework items, reviews entries) quote an EVALUATED-AS probe
      verbatim, re-run every such probe against the FINAL working tree
      AFTER all record appends and paste the actual result; if the quoted
      literal itself is the only remaining match, mask the literal in the
      record (`<replica-port>` style) and re-run. A reported PASS must
      describe the tree under review, never a pre-append snapshot.
    - the lint WARNs
   Read it back into the session (the report is small; it substitutes into
   `{{MECHANICAL_REPORT}}`).

3. **Render and spawn.** Render `docs/process/tick-reviewer-prompt.md` via
   `scripts/m1-render-prompt.py` with `TICKET_ID`, `CURRENT_ROUND`,
   `ROUND_CAP` (from frontmatter), `TICKET_FILE_PATH`,
   `DIFF_FILE_PATH` (round 1: the full-diff file from step 2; rounds ≥ 2:
   the fix-hunks file from step 2),
   `TEST_LOG_PATH`, `ANALYSIS_FILE_PATH` (from `analysis_ref:` — for a
   `self` ticket, substitute the ticket's own path: the analysis IS the
   ticket; for a 2+ decomposition, the tick-analysis/ path),
   `MECHANICAL_REPORT` (via `@file`), `VERDICT_FILE_PATH`
   (`.scratch/tick-review-<ID>-r<round>.txt`). ALL paths absolute
   (harness-mapping §6.1(d)). Spawn `tick-reviewer` fresh-context with the
   stub: `Read <rendered-prompt-path> and follow it exactly. It names every
   input file and the output path. Write the required artifact and reply
   only in the format the prompt specifies.` Foreground.

4. **Read back.** Parse the four-line chat reply (`VERDICT`, verdict path,
   rework count, critical/high count). Read the verdict file from disk —
   the on-disk artifact is the result, never the chat reply alone.
   Contamination check: `git status --porcelain` shows only the expected
   artifact paths.

5. **Dispatch** per the verdict semantics (tick-workflow.md §4 /
   tick-reviewer-prompt.md):
   - **APPROVE** → record under `reviews:` (round, date, verdict, checks,
     diff_stats); status stays `in-review`; prompt `/tick commit <id>`.
   - **APPROVE-WITH-FIXES** → record under `reviews:`; apply EXACTLY the
     verdict's FIX ITEMS. At apply time verify every changed line is a
     comment/javadoc line and no docs/spec, docs/design or root-level *.md
     file is touched (parity-test fixtures); if any fix cannot stay
     comment-only, treat the verdict as REWORK instead. Then run each
     item's EVALUATED-AS probe plus `./mvnw -B -pl <touched modules> -am
     test-compile` (a comment edit can still break the compile — Error
     Prone parses comments); the round's green log remains the log of
     record. Snapshot the fixed tree for commit's identity check:
     `git add -A; s=$(git stash create); echo "${s:-$(git rev-parse HEAD)}"
     > .scratch/tick-fixes-<ID>.tree`. Record the probe outputs in the
     `reviews:` entry, then prompt `/tick commit <id>`. No further round.
   - **REWORK** → record; status → `in-progress`; append the REWORK ITEMS
     verbatim to the ticket body under "Round N rework"; the developer
     fixes ONLY those items, re-runs `mvn verify`, re-invokes this
     subcommand (round+1).
   - **MANUAL** → record; status → `escalated`; **notify the user with
     the verdict's plain-English SUMMARY plus one bullet per critical/high
     finding** — WHAT, the WRONG output example, the EXPECTED output, and
     the SOLUTION (severity in parentheses only) — then fire
     `/tick escalate <id>`.
   - Round cap reached on REWORK → `escalated`, escalate menu, no round
     beyond the cap.
   - **RECOMMENDED-NEW-TICKET entries** get a driver disposition, not an
     automatic relay: an entry the verdict labels `TOUCHED-BY-THIS-DIFF:
     no` with no `DECIDE-BEFORE:` line is recorded — append it under a
     "Review observations" heading in the ticket body and carry a one-line
     version into the commit body — and no decision is requested; the user
     reads it there. Entries carrying `DECIDE-BEFORE: <ticket/event>` (an
     ordering interaction with planned or in-flight work) are relayed to
     the user now, one decision each. Filing a ticket is the user's call
     in every case — never file one unilaterally.

6. **Renames handoff.** Copy the reviewer's MAINTAINABILITY naming
   suggestions into the eventual commit's `Renames:` trailer material —
   they are implementor material, not additional REWORK items, unless the
   reviewer FAILed MAINTAINABILITY (then they ARE items).

Regenerate `STATUS-TICK.md` after the status change.
