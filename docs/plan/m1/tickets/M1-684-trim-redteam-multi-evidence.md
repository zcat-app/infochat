---
id: M1-684
title: "Stop committing regenerable redteam-multi evidence bulk"
status: pending
created: 2026-07-24
last_updated: 2026-07-24
blocked_by: []
files_budget: 4
files_scope:
  - .gitignore
  - .claude/skills/redteam-multi/SKILL.md
  - .agents/skills/redteam-multi/SKILL.md
  - docs/process/harness-mapping.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The reviewer-prompt lifecycle-path exemption
    (docs/process/reviewer-prompt.md): the exemption is by directory glob and
    does not care which files inside the directory are present, so trimming the
    committed contents needs no reviewer-prompt change.
  - >-
    The redteam-multi.sh script's WRITING of these files. The script may keep
    emitting prompt/reply/inv scratch into the run directory as working state;
    this ticket only stops that scratch from being COMMITTED. Changing what the
    script writes to disk is a separate concern.
acceptance:
  - >-
    A .gitignore rule excludes the regenerable bulk under
    docs/plan/m1/redteam-multi/**/ from commits — the rendered prompts
    (prompt-*.txt), raw auditor replies (reply-*.txt), investigation scratch
    (inv-*.txt), the redundant diff copy (diff.patch), contamination porcelain
    (porcelain-*.txt), and preflight.txt — while KEEPING the durable audit
    record tracked: verdict-*.txt, cross-examination.md, and any
    disposition.md. The single-auditor /redteam evidence
    (docs/plan/m1/redteam/*.md) is a single markdown file and is unaffected.
  - >-
    Both redteam-multi SKILL.md files (Claude-Code and .agents) and
    harness-mapping.md §7 update their "commit the evidence directory" step to
    "commit the durable subset" and name exactly which files are the durable
    record, so future runs are consistent with the .gitignore rule rather than
    fighting it.
  - >-
    A one-time cleanup removes the already-committed regenerable bulk from the
    ~25 existing docs/plan/m1/redteam-multi/*/ directories in a single
    git rm --cached sweep, so the repo is consistent (no old-fat/new-clean
    split). The durable files stay. This is a working-tree deletion, not a
    history rewrite — the bulk remains recoverable from prior commits if ever
    needed.
  - >-
    A note preserves the one case where a raw reply carries signal its verdict
    file does not: when an auditor times out (exit 124) its verdict file is the
    UNAVAILABLE stub, so its actual conclusion must be captured in disposition.md
    (or the cross-examination report) before the raw reply is dropped —
    the M1-672 r2 kimi run is the worked precedent.
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-684: Stop committing regenerable redteam-multi evidence bulk

## Context

Filed as a follow-up from M1-672 (2026-07-24), where the user flagged that the
committed redteam-multi evidence directories are large. Each `/redteam-multi`
run commits ~830 KB, dominated by regenerable scratch: three rendered
`prompt-*.txt` (≈139 KB each — they embed the full diff + threat model), the
verbose `reply-*.txt` (kimi's runs 80–100 KB), `inv-*.txt` investigation notes,
a redundant `diff.patch`, and `porcelain-*.txt`. The durable audit record —
`verdict-*.txt`, `cross-examination.md`, and the hand-written `disposition.md` —
is only tens of KB. Across the ~25 committed run directories the regenerable
bulk is tens of MB of permanent history with near-zero audit value: the verdict
distills the reply, and the prompt is fully regenerable from the template + diff.

M1-672 already trimmed its own two run directories to the durable subset as a
one-off (the user's direct request). This ticket makes that the standing policy
so the repo stays consistent: update the two skill files + harness-mapping §7,
add the `.gitignore` rule, and GC the existing directories.

The lifecycle-path exemption in `docs/process/reviewer-prompt.md` already
exempts the whole `docs/plan/m1/redteam-multi/{{TICKET_ID}}-*{{DATE}}[-rN]/`
directory by glob, so trimming its contents is invisible to the review gate and
needs no reviewer change.

## Acceptance

See the frontmatter. Add a `.gitignore` rule for the regenerable bulk, keep the
durable record, align both SKILL.md files + harness-mapping §7 with that split,
GC the existing directories, and document the timed-out-auditor carve-out
(capture the conclusion in disposition before dropping the raw reply).

## Out-of-scope

The reviewer-prompt exemption (unchanged — it is directory-glob scoped) and the
redteam-multi.sh script's on-disk writes (it may keep emitting scratch as
working state; this ticket only stops that scratch from being committed). See
the frontmatter.
