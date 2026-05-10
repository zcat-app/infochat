---
id: M1-002
title: "m1-tick: fix STATUS.md order and review diff capture"
status: done
created: 2026-05-10
last_updated: 2026-05-10
blocked_by: []
files_budget: 5
files_scope:
  - .claude/skills/m1-tick/SKILL.md
  - docs/process/reviewer-prompt.md
  - docs/process/workflow.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any change to review verdict semantics (APPROVE / REWORK / MANUAL / OVERRIDE-APPROVE)
  - any change to the round-cap rules or the round-N must-shrink rules
  - any change to the clarity pre-flight, the Plan subagent step, or the `/redteam` skill
  - any edit to `docs/process/engineering-rules-verbatim.md` (these are skill-procedure bugs, not engineering-rule changes)
  - any edit (including deletion) of `docs/process/m1-tick-calibration-findings.md` — it is the source backlog motivating this ticket; archival or removal is a separate concern, deliberately not in scope here so the diff stays focused on the three procedural fixes
  - any edit to `docs/plan/m1/tickets/M1-001-set-up-two-module-maven-build.md` (M1-001 is done and immutable)
  - any change to `docs/plan/m1/STATUS.md` content beyond what the regenerator emits (no hand edits)
  - any change to repo source code, poms, `.gitignore`, or test code (this ticket only edits skill/process docs)
acceptance:
  - "grep -nE 'STATUS\\.md' .claude/skills/m1-tick/SKILL.md inside the `## commit <id>` section shows STATUS.md listed among the paths excluded from the commit-candidate set in step 1, alongside the ticket file"
  - "within the `## commit <id>` section of .claude/skills/m1-tick/SKILL.md, exactly one line matches the regex 'Regenerate `STATUS.md`', AND that line appears BEFORE the line matching 'git commit -m' (i.e. the post-commit trailing regen step is gone, replaced by a pre-commit regen step)"
  - "grep -rn 'main\\.\\.\\.HEAD' .claude/skills/m1-tick/SKILL.md docs/process/workflow.md docs/process/reviewer-prompt.md returns zero matches"
  - "within the `## review <id>` section of .claude/skills/m1-tick/SKILL.md (lines between the `## review <id>` and `## commit <id>` headings), grep matches `git add -N` at least once AND matches `git diff main` (with no `...HEAD`) at least once"
  - "grep -n 'git diff main' docs/process/reviewer-prompt.md returns at least one match at the `## Diff` heading line (no longer cites `main...HEAD`; subsumed by item 3 on the negative side, asserted positively here)"
  - "mvn -B verify from the repo root exits 0"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main (suite is trivially green; this ticket only edits skill/process markdown)
spec_refs:
  - .claude/skills/m1-tick/SKILL.md §commit
  - .claude/skills/m1-tick/SKILL.md §review
  - docs/process/reviewer-prompt.md §Diff
  - docs/process/workflow.md §The flow
decision_refs: []

reviews:
  - round: 1
    date: 2026-05-10
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 20
      removed: 17
escalations:
  - date: 2026-05-10
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL

      SPEC-REFS-VALID: FAIL
        - .claude/skills/m1-tick/SKILL.md §commit: PASS (line 180)
        - .claude/skills/m1-tick/SKILL.md §review: PASS (line 117)
        - docs/process/reviewer-prompt.md §Diff: PASS (line 28)
        - docs/process/workflow.md (the line citing `git diff main...HEAD`
          as the reviewer input): ANCHOR-NOT-FOUND — entry has no
          §<section> anchor; parenthetical is prose, not a heading.
        - docs/process/m1-tick-calibration-findings.md (source backlog
          of the three findings): ANCHOR-NOT-FOUND — entry has no
          §<section> anchor; parenthetical is prose, not a heading.

      ACCEPTANCE-RUNNABLE: WARN (items 2, 4, 5 are narrative/prose
      checks; would be stronger as grep assertions)

      BLOCKERS:
        1. spec_refs entry 'docs/process/workflow.md (the line citing
           `git diff main...HEAD`...)' has no §<section> anchor.
           Fix: replace with 'docs/process/workflow.md §The flow' (or
           similar real heading) — drop the parenthetical.
        2. spec_refs entry
           'docs/process/m1-tick-calibration-findings.md (source backlog
           of the three findings)' has no §<section> anchor.
           Fix: replace with a real anchor (e.g. §Finding 1) or remove
           the entry — the findings file is scratchpad, not durable spec.
revisions:
  - date: 2026-05-10
    reason: clarity-fail rework
    snapshot:
      status: escalated
      clarity_check:
        date: 2026-05-10
        verdict: FAIL
        blockers:
          - "spec_refs entry 'docs/process/workflow.md (the line citing `git diff main...HEAD` as the reviewer input)' has no §<section> anchor and resolves to ANCHOR-NOT-FOUND."
          - "spec_refs entry 'docs/process/m1-tick-calibration-findings.md (source backlog of the three findings)' has no §<section> anchor and resolves to ANCHOR-NOT-FOUND."
        warnings:
          - "ACCEPTANCE-RUNNABLE WARN: items 2, 4, 5 are narrative/prose checks rather than runnable commands"
          - "Findings file disposition (kept vs deleted) not explicitly classified in out_of_scope or acceptance"
      escalation_reason: clarity-fail
      spec_refs_at_snapshot:
        - .claude/skills/m1-tick/SKILL.md §commit
        - .claude/skills/m1-tick/SKILL.md §review
        - docs/process/reviewer-prompt.md §Diff
        - docs/process/workflow.md (the line citing `git diff main...HEAD` as the reviewer input)
        - docs/process/m1-tick-calibration-findings.md (source backlog of the three findings)
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-10
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-002: m1-tick: fix STATUS.md order and review diff capture

## Context

M1-001 ("Set up two-module Maven build") was the explicit calibration
run for the `/m1-tick` skill. M1-001 itself shipped clean (round-1
APPROVE), but exercising the full lifecycle — `start` → clarity →
implementation → `review` → reviewer subagent → `commit` →
STATUS.md regen — surfaced three real bugs in the skill's procedure
(not in the M1-001 implementation). They are catalogued in
`docs/process/m1-tick-calibration-findings.md`. This ticket lands all
three fixes so future M1 tickets do not hit the same friction. The
skill is repository code; like any other procedure, changes go through
the same lifecycle they govern.

## Definition of Done

- The `commit` step in `.claude/skills/m1-tick/SKILL.md` excludes
  `docs/plan/m1/STATUS.md` from the commit-candidate set used by the
  test-freshness check, alongside the ticket file. The freshness
  check then runs only against real source candidates.
- The `commit` step regenerates STATUS.md *before* `git commit` and
  stages it explicitly with the rest of the commit, so the working
  tree is clean immediately after `/m1-tick commit M1-NNN` returns
  and the committed STATUS.md reflects the final `done` state. The
  old "regenerate STATUS.md" trailing step is removed (not
  duplicated).
- The `review` step in `.claude/skills/m1-tick/SKILL.md` documents
  the actual diff-capture procedure used in M1-001:
  `git add -N <untracked-files-in-the-working-tree>` followed by
  `git diff main` (working-tree-vs-main), instead of the misleading
  `git diff main...HEAD` (which is empty pre-commit). `--shortstat`
  capture for current and previous round uses the same diff command.
- `docs/process/reviewer-prompt.md`'s `## Diff` header (and the
  trailing reproduction note around line 275) reflect the
  working-tree-vs-main capture — no remaining `main...HEAD` phrasing.
- `docs/process/workflow.md`'s mention of `git diff main...HEAD` (the
  bullet at ~line 142 describing the reviewer input) is updated to
  match the actual capture.
- `mvn -B verify` from the repo root remains green (the suite is
  trivially empty post-M1-001 and stays so; this is a smoke check
  only).

## Implementation notes

Source backlog: `docs/process/m1-tick-calibration-findings.md`. Each
finding has a "Fix" or "Recommendation" section that names the exact
edit. The three findings are independent in their edit surface but
finding 2's fix depends on finding 1's fix landing first (otherwise
the STATUS.md mtime, now bundled into the pre-commit set, makes the
freshness check noisier).

Concrete pointers:

- Finding 1 (`commit` stale-log false positive) — `.claude/skills/m1-tick/SKILL.md`
  `## commit <id>` step 1. Today: "Exclude the ticket file itself."
  Required: also exclude `docs/plan/m1/STATUS.md`. One-line change to
  the prose plus, if helpful, a short rationale sentence about why
  STATUS.md is a workflow-artifact regeneration rather than a source
  edit and so does not invalidate test freshness.
- Finding 2 (STATUS.md is one step behind the commit) —
  `.claude/skills/m1-tick/SKILL.md` `## commit <id>` steps 4–7.
  Required reorder: identify candidates → freshness check → build
  message → set `status: done` → **regenerate STATUS.md** → stage
  candidates + ticket file + STATUS.md → `git commit` → (delete the
  old trailing "regenerate STATUS.md" step). Step numbering should
  remain consistent; renumber the rest as needed.
- Finding 3 (review captures empty diff pre-commit) — three call sites
  found by `grep -n 'main\.\.\.HEAD'`:
  - `.claude/skills/m1-tick/SKILL.md:129` (review step 2 — `git diff main...HEAD` as the full diff)
  - `docs/process/workflow.md:142` (the bullet describing the reviewer input)
  - `docs/process/reviewer-prompt.md:28` (`## Diff` header) and `:275` (reproduction note)
  All three must be updated together. The canonical replacement is
  `git add -N <untracked-files>` then `git diff main` (working tree
  vs main), matching what M1-001's review actually used. The
  reviewer-prompt header should read e.g. `## Diff (working tree vs
  main, on branch {{BRANCH}})`. The `--shortstat` invocation in
  step 2 must use the same diff command (today it implicitly mirrors
  the full-diff command, so updating one updates both).

The findings file also flags an open question: whether `git add -N`
needs explicit cleanup. In M1-001 it had no observable side effect —
the `-N` entries were absorbed by the explicit `git add` at commit
time. The skill update should call this out in a one-liner so future
sessions don't worry about it.

## Big-picture notes

- The skill is the *procedure* layer over `docs/process/workflow.md`
  (the universal spec) and `CLAUDE.md` §M1 workflow (the always-loaded
  summary). Keep all three consistent. Finding 3's call site in
  `workflow.md` is the only universal-spec edit needed; the skill and
  reviewer prompt are the procedural reflections of it.
- Future milestones (M2+) will instantiate the same workflow with a
  different ticket dir and slightly different skill wrapper. Anything
  this ticket fixes in `workflow.md` must read as milestone-agnostic
  (no hard-coded "m1" path); anything in the m1-tick skill is M1-only
  by design.
- The findings file (`docs/process/m1-tick-calibration-findings.md`)
  is process-internal scratchpad, not durable spec, so it is NOT in
  `spec_refs` and is explicitly listed in `out_of_scope` (no edit or
  deletion as part of this ticket). Archival or removal is a separate
  follow-up, deliberately deferred so this diff stays focused on the
  three procedural fixes.
- Don't try to also fix unrelated friction observed in M1-001 (e.g.
  the "approximate placeholder STATUS.md will be overwritten on first
  /m1-tick status" footnote). Those aren't in the calibration backlog
  and folding them in would expand scope.

## Out-of-scope expansion

The findings document explicitly fences out several adjacent changes;
they are repeated in `out_of_scope` and elaborated here so the
reviewer can rule on scope-drift without consulting the findings file:

- **Review verdict semantics** (`APPROVE` / `REWORK` / `MANUAL` /
  `OVERRIDE-APPROVE`) and the round-cap / must-shrink rules are
  load-bearing for the whole workflow. They are unaffected by these
  three findings; touching them here would couple unrelated risk into
  a low-risk procedure cleanup.
- **Clarity pre-flight, Plan subagent step, `/redteam` skill** are
  separate procedures that didn't surface in the M1-001 calibration.
  Don't preemptively reshape them.
- **`docs/process/engineering-rules-verbatim.md`** is the canonical
  rule text. These are skill-procedure bugs, not rule changes.
- **`M1-001-set-up-two-module-maven-build.md`** is `done` and
  immutable per `CLAUDE.md` §M1 workflow ("never amend a passed
  commit"). Nothing about this ticket should require editing it.
- **STATUS.md content** is regenerator output. The fix moves the
  regeneration earlier in the commit step but does NOT change what
  `/m1-tick status` produces. Do not hand-edit STATUS.md; let the
  regenerator emit it.
- **Repo source / poms / tests / `.gitignore`** are off-surface — this
  ticket only edits skill/process markdown. Any pom or Java edit is
  scope drift.

## Authorized test changes

- (none — this ticket adds no tests and modifies none. The suite is
  trivially empty post-M1-001 and remains so; `mvn verify` is the
  smoke check, not a behavioral assertion of these fixes. The
  acceptance criteria are checked by `grep` against the edited files
  and by reading the post-edit procedure narrative.)

## Alternatives considered

- **Alt A: split into three tickets, one per finding.** Rejected
  because findings 1 and 2 are tightly coupled (finding 2's commit
  reorder depends on finding 1's STATUS.md exclusion), and finding 3
  is a single grep-and-replace across three files. Three tickets
  would triple lifecycle overhead with no isolation benefit. The
  findings file itself recommends a single ticket and flags the
  coupling.
- **Alt B (Finding 1): move STATUS.md regen in `review` to BEFORE
  `mvn verify` is captured, instead of excluding STATUS.md from the
  commit-candidate set.** Rejected per the findings file: it requires
  reordering the review step and doesn't generalize — any future
  workflow-artifact regenerated post-test would re-trip the same
  false positive. The exclusion-list fix is local and stable.
- **Alt C: leave the M1-001 workaround in place (post-commit STATUS.md
  regen + a follow-up `regenerate STATUS.md (post-done)` commit on
  the branch, collapsed by squash-merge).** Rejected because squash
  merge isn't mandatory in `workflow.md`, the trailing commit is ugly
  and confuses `git log --grep "M1-NNN"` reading, and the broken
  ordering will keep producing the same friction on every ticket.
- **Alt D: rewrite the `commit` step to detect "post-test workflow
  mutations" generically (e.g. anything under `docs/plan/<m>/STATUS.md`
  or `docs/plan/<m>/`) and exclude them dynamically.** Rejected as
  premature generalization — STATUS.md is the only such artifact
  today; a one-line exclusion is simpler and easy to extend later.
