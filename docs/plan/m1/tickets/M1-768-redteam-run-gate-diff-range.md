---
id: M1-768
title: "Redteam run-gate must always diff working tree vs fork point"
status: done
created: 2026-08-04
last_updated: 2026-08-04
blocked_by: []
files_budget: 2
files_scope:
  - .claude/skills/redteam/SKILL.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY CODE, TEST OR MIGRATION CHANGE. This ticket edits skill procedure
    text only. Nothing under `infochat-*/` is touched, so `mvn verify` has
    no testable surface and the inert-diff gate applies.
  - >-
    THE OTHER THREE TARGET FORMS. `milestone <name>`, `id-range <a..b>`
    and `release <tag>` all audit landed history, where the commit-range
    resolution is correct. Only the single-ticket `--in-progress` form is
    wrong, and only at a `/m1-tick run` gate. Do not "harmonise" the four.
  - >-
    `escalate.md`'s refine arm. Committing the refine on the per-ticket
    branch is deliberate and load-bearing — it makes refined acceptance
    criteria survive a later `git checkout`/`abort`. The defect is that
    `redteam` mis-reads that commit, not that the commit exists. Do not
    "fix" this by making escalate stop committing.
  - >-
    THE SENSITIVE-SURFACE INVENTORY GREPS. Step 3's whole-file grep floods
    the inventory with unchanged-line noise on large files and
    `.properties` bundles. Real, separate, not this ticket.
  - >-
    THE REVIEWER PROMPT AND `review.md`. `review.md` step 1 already
    resolves working-tree-vs-fork-point correctly; it is the reference
    implementation this ticket makes `redteam` match, not something to
    edit.
acceptance:
  - >-
    `.claude/skills/redteam/SKILL.md` step 1's single-ticket algorithm
    states that when the audit is running at a `/m1-tick run` gate (i.e.
    `--in-progress`), the diff is ALWAYS working-tree-vs-fork-point —
    `git diff $(git merge-base main HEAD)` — regardless of how many
    commits the branch carries. The existing count-based split
    (`git rev-list --count main..<branch>`; count==0 -> working tree,
    count>0 -> `main...<branch>`) must no longer be able to select the
    commit-range form on the `--in-progress` path.
  - >-
    The rewritten step names WHY in one sentence, so a future editor does
    not "simplify" the special case back out: at the run gate the
    implementation is uncommitted by design (commit runs after redteam),
    and `escalate.md`'s refine arm commits the ticket file on the branch,
    so a non-zero commit count does NOT imply the code is committed.
  - >-
    The step notes that the refuse-on-empty guard does not protect this
    case — the ticket-file-only diff is non-empty, so a docs-only audit
    would be persisted to `redteam_audits:` looking legitimate. This is
    the reason the fix is worth making rather than relying on the guard.
  - >-
    `git grep -n "rev-list --count" .claude/skills/redteam/SKILL.md`
    returns no site that still routes an `--in-progress` audit to the
    commit-range form. Run it and record the output in the commit message.
test_plan:
  modifies:
    - .claude/skills/redteam/SKILL.md
  preserves:
    - >-
      The refuse-on-empty behaviour, the four invocation forms, the
      threat-actor spawn, and steps 2-8 (milestone resolution, inventory,
      render, parse, persist, escalate) unchanged.
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 17
      removed: 14
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check:
  date: 2026-08-04
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-768: Redteam run-gate must always diff working tree vs fork point

## Context

`/redteam <id> --in-progress` is the standing gate `/m1-tick run` fires for
every `security_relevant: true` ticket, ahead of review. Its step-1
algorithm resolves the diff by asking how many commits the branch carries:

- `count == 0` → working-tree-vs-fork-point. Correct, and the common case,
  because `/m1-tick commit` runs *after* the redteam gate by design.
- `count > 0` → commit range `main...<branch>`.

The second arm mis-fires on the `escalate → refine` path.
[`escalate.md`](../../../../.claude/skills/m1-tick/subcommands/escalate.md)
step 5, refine arm (line 76) says, for a `redteam-finding` escalation:

> **Commit the refine on the per-ticket branch immediately.** Stage the
> ticket file (and only the ticket file)

So after any round-1 FINDINGS the branch carries exactly one commit,
containing exactly one file: the ticket `.md`. At the round-2 re-audit gate
the count is 1, the algorithm picks the commit range, and the adversary is
handed the ticket paperwork with **none of the implementation**.

## Measured, not argued (M1-764, 2026-08-04)

At M1-764's round-2 gate, HEAD was the refine commit `a0a23ab4` and the
fork point was `2722b68c`. What each form resolves to:

```
$ git diff --name-only 2722b68c...a0a23ab4        # what the skill would pick
docs/plan/m1/tickets/M1-764-llm-transport-interrupt-contract.md

$ git diff --name-only $(git merge-base main HEAD)   # what was actually audited
docs/plan/m1/STATUS.md
docs/plan/m1/redteam/M1-764-2026-08-04.md
docs/plan/m1/tickets/M1-764-llm-transport-interrupt-contract.md
infochat-llm-adapter/src/test/java/.../HttpProviderSharedPipelineTest.java
```

One file versus four; zero code versus the entire implementation.

## Why this is worse than the empty-diff case it replaced

The skill already refuses on an empty diff, precisely so a vacuous CLEAN
cannot be persisted as evidence. That guard does **not** fire here: a
ticket-file-only diff is large and non-empty. The adversary reads a
document, correctly reports no security problems in that document, and the
verdict lands in `redteam_audits:` indistinguishable from a real one — with
a `verdict_file:` pointer, a base, a head, and a plausible note.

A gate that fails loudly is a nuisance. A gate that passes for the wrong
reason is worse than no gate, because it also stops anyone looking again.

## Occurrences

- **M1-763**, 2026-08-04 — first observed.
- **M1-764**, 2026-08-04 — same shape one day later; the operator knew to
  override the resolution by hand and recorded the deviation in the audit
  file's `disposition:`. That workaround depends entirely on the operator
  already knowing, which is what this ticket removes.

Every future `security_relevant` ticket whose first audit returns FINDINGS
takes this path, because the refine commit is unconditional for that arm.

## Notes

- `review.md` step 1 already does the right thing for the reviewer, and
  says why: *"at `review` time the implementation lives in the working tree
  (no commit on the branch yet ...)"*. The two gates are supposed to read
  byte-identical input; today they can silently diverge.
- Both gates share a second reason to prefer the merge-base form: in a
  worktree pinned behind a moved `main`, `git diff main` drags every
  since-landed ticket in as phantom changes (M1-096).
- The diff is inert for `mvn verify` (skill text only), so the run's round
  log is the inert-N/A note per SKILL.md §M1 workflow rules.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-768-redteam-run-gate-diff-range.md`
