---
name: redteam
description: Run an adversarial security review — a fresh-context threat-actor subagent reads the project's threat model (docs/spec/security.md) and the diff, then flags gaps between what the model promises and what the diff delivers, bucketed by category and severity. Use when the user asks for a "red-team", "security review", "threat-model audit", "adversarial review", or "vulnerability check"; natural at milestone boundaries, on tickets flagged security_relevant: true, and before tagging a release. Invoke as `/redteam <ticket-id | milestone <name> | id-range <a..b> | release <tag>>`.
---

# /redteam — adversarial security review

This skill is the procedure. The prompt template is `docs/process/redteam-prompt.md`. The threat model the adversary reads is `docs/spec/security.md`. If either of those is absent, the skill refuses and explains.

This skill is milestone-agnostic — it accepts any ticket-ID matching the pattern `[A-Z]+\d+-\d+[a-z]*` (e.g. `M1-007`, `M2-012`, or umbrella-subtickets like `M1-008a`; see `docs/process/workflow.md` §Ticket-ID placeholder convention for the umbrella+subticket idiom) and infers the active milestone from the target argument. It is intentionally separate from `/m1-tick` because (a) its cadence is different (milestone-end / flagged-ticket / pre-release, not per-ticket), (b) its inputs and outputs are different (multi-ticket diff ranges; bucketed findings rather than APPROVE/REWORK), and (c) the adversary persona benefits from a focused skill description rather than being one of ten subcommands under a generic "drive the workflow" umbrella.

## Invocation forms

```
/redteam <ticket-id>              # single ticket; usually one with security_relevant: true
/redteam milestone <name>         # all `done` tickets in a named milestone (e.g. "milestone m1")
/redteam id-range <from>..<to>    # inclusive ticket-ID range (e.g. "id-range M1-005..M1-012")
/redteam release <tag>            # diff between previous release tag and main
```

If the args don't match, print the table above and stop.

## Preconditions

- `docs/spec/security.md` exists. Refuse if not (the adversary needs the threat model to compare against).
- `docs/process/redteam-prompt.md` exists. Refuse if not.
- For `<ticket-id>` form: the ticket exists and `status: done`, OR the audit runs against an in-progress ticket via `/redteam <id> --in-progress`. That form is not exotic: it is the standing gate `/m1-tick run` fires for every `security_relevant: true` ticket, ahead of review, so findings surface before the reviewer ever sees the diff.
- For `milestone <name>` form: the milestone directory `docs/plan/<name>/` exists and contains tickets.
- For `id-range <a..b>` form: every ticket in the range exists; all share a milestone prefix (refuse cross-milestone ranges with a clear error).
- For `release <tag>` form: `<tag>` matches the repo's tag pattern AND a previous release tag exists.

## Steps

### 1. Resolve the diff range

| Target form | Base | Head |
|---|---|---|
| `<ticket-id>` | depends on ticket state — see algorithm below | depends on ticket state — see algorithm below |
| `milestone <name>` | merge-base of `main` and the first ticket commit of the milestone | `main` (or the latest done-ticket commit in that milestone) |
| `id-range <a..b>` | commit before `a` landed | commit when `b` landed |
| `release <tag>` | previous release tag | `<tag>` (or `main` if not yet tagged) |

Capture via shell redirection so the diff bytes never enter the main-session transcript (`<target-slug>` per the slug rule in step 7). Which command depends on whether the target resolves to a commit range or to a `--in-progress` branch:

- **Commit range** (every target form except the `--in-progress` branch form below): `git diff <base>...<head> > target/redteam-diff-<target-slug>.diff`
- **`--in-progress` branch form** (see the single-ticket algorithm, step 2): `git diff $(git merge-base main HEAD) > target/redteam-diff-<target-slug>.diff`

**Refuse on an empty diff.** After capture, if the diff file is zero-length, do NOT render the prompt or spawn the adversary — an audit of an empty diff returns CLEAN, and that verdict is indistinguishable in the record from a real one. Print the resolved base/head and the form that produced them, and stop. Fail-closed is the whole point: a silent vacuous CLEAN is worse than no audit, because it is persisted to `redteam_audits:` as evidence the ticket was audited.

**Single-ticket diff-range algorithm.** Run in order; stop at the first form that succeeds:

1. **Try the merged form.** Run `git log --grep="^<ticket-id>: " --format=%H main` to find the squash-merge commit subject (commit messages start with `<ticket-id>: <imperative summary>` per the workflow's commit conventions). If exactly one commit hash returns, set `BASE = <hash>^` and `HEAD = <hash>`. Done.
2. **Try the branch form** (only if `--in-progress` was passed; otherwise skip to step 3 and refuse). The expected branch name is `m<N>/<ticket-id>-<slug>` (e.g. `m1/M1-007-rss-fetcher`). Compute the slug from the ticket title per the canonical rule in [`docs/process/workflow.md`](../../../docs/process/workflow.md) §"Naming conventions (slug, branch, ticket file)". Check whether the branch exists with `git rev-parse --verify --quiet refs/heads/m<N>/<ticket-id>-<slug>`; if no exact match, fall back to globbing `m<N>/<ticket-id>-*` per the workflow's slug-drift fallback (refuse if zero or multiple matches).

   **The diff on this path is ALWAYS working-tree-vs-fork-point, whatever the branch's commit count.** Run `git add -N <untracked-files-in-the-working-tree>` first so newly created files appear in the diff (intent-to-add; the `-N` entries are absorbed by the explicit `git add` at commit time and need no separate cleanup), then set `BASE = $(git merge-base main HEAD)` and `HEAD = <working tree>`. This mirrors what [`review.md`](../m1-tick/subcommands/review.md) step 1 already does for the reviewer, so both gates audit byte-identical input. **Not** `git diff main`: in a worktree pinned behind a moved `main`, diffing against `main` drags every since-landed ticket into the audit as phantom changes (observed for the reviewer as M1-096, 2026-05-30; the same topology reaches this skill).

   **Why there is no commit-count split here — do not reintroduce one (M1-768).** At the `/m1-tick run` gate the implementation is uncommitted by design (`run` audits *before* [`commit.md`](../m1-tick/subcommands/commit.md) lands anything on the branch) while [`escalate.md`](../m1-tick/subcommands/escalate.md) step 5's refine arm commits the ticket file on the branch after any round-1 FINDINGS — so a non-zero commit count does **not** imply the implementation is committed, and selecting a commit range on that count hands the round-2 adversary the ticket paperwork with none of the code (measured at M1-764's round-2 gate: one file versus five, zero code). The refuse-on-empty guard above does not catch this, which is why the split has to go rather than be guarded: a ticket-file-only diff is non-empty, so the resulting vacuous CLEAN is persisted to `redteam_audits:` indistinguishable from a real audit. Dropping the split costs nothing — working-tree-vs-fork-point already contains whatever the branch has committed, so it is correct for a committed branch too.

   Done.
3. **Otherwise refuse** with:
   ```
   /redteam <ticket-id>: cannot resolve diff range.
     - Searched main for a commit matching "<ticket-id>: ..." → not found.
     - Branch m<N>/<ticket-id>-<slug> → does not exist (or --in-progress was not passed).
   If the ticket is in-flight on a branch, re-invoke as
     /redteam <ticket-id> --in-progress
   to opt into auditing the branch tip before APPROVE.
   ```

Multiple commit matches in step 1 (the same `<ticket-id>: ` prefix appearing in more than one commit subject) means the no-amend / one-commit-per-ticket invariant has been violated; refuse and tell the user to investigate.

### 2. Identify the active milestone (for report-write path)

- `<ticket-id>` → parse the milestone prefix from the ID (e.g. `M1-007` → `m1`).
- `milestone <name>` → `<name>`.
- `id-range <a..b>` → the shared prefix from `a` (already enforced in preconditions).
- `release <tag>` → `release/<tag>`.

The active-milestone token determines where reports get written: `docs/plan/<active-milestone>/redteam/`. If the directory doesn't exist, create it.

### 3. Build the sensitive-surface inventory

The canonical pattern list — what to grep for and which prompt placeholder each match feeds — lives in [`docs/process/redteam-prompt.md`](../../../docs/process/redteam-prompt.md) §"Sensitive-surface patterns (canonical)". When that list changes, this step's behavior changes with it; do not duplicate the patterns here.

Mechanic:

1. For each placeholder in the canonical table (`{{AUTH_PATHS}}`, `{{AUTHZ_PATHS}}`, `{{INPUT_PATHS}}`, `{{BAN_PATHS}}`, `{{AUDIT_PATHS}}`), run `git grep -n` (or equivalent) of each pattern restricted to files appearing in the diff (`git diff --name-only <base>...<head>`), redirecting each placeholder's deduplicated `file:line` tuples to its own file: `target/redteam-inv-<auth|authz|input|ban|audit>-<target-slug>.txt`.
2. If a placeholder's file is empty (no matches), overwrite it with the literal string `(none touched)`: `[ -s <file> ] || echo '(none touched)' > <file>`.

Step 4 passes each file via the render script's `@file` form. The patterns are deliberately conservative; the adversary subagent's prompt reminds it not to treat the list as exhaustive.

### 4. Render the prompt

Pre-allocate the verdict path at `target/redteam-verdict-<target-slug>.txt`. Render via Bash — do NOT Read `docs/process/redteam-prompt.md` (or `docs/spec/security.md`, or the diff) into main-session context; the script extracts the fenced template body and substitutes only metadata, paths, and the small inventories. The subagent Reads the threat model and the diff in its own fresh context:

```
python3 scripts/m1-render-prompt.py \
  docs/process/redteam-prompt.md \
  target/redteam-prompt-<target-slug>.txt \
  TARGET=<literal target arg> \
  BASE_REF=<base> HEAD_REF=<head> \
  DIFF_FILE_PATH=target/redteam-diff-<target-slug>.diff \
  VERDICT_FILE_PATH=target/redteam-verdict-<target-slug>.txt \
  AUTH_PATHS=@target/redteam-inv-auth-<target-slug>.txt \
  AUTHZ_PATHS=@target/redteam-inv-authz-<target-slug>.txt \
  INPUT_PATHS=@target/redteam-inv-input-<target-slug>.txt \
  BAN_PATHS=@target/redteam-inv-ban-<target-slug>.txt \
  AUDIT_PATHS=@target/redteam-inv-audit-<target-slug>.txt
```

### 5. Spawn the adversary subagent

```
Agent(
  subagent_type: "threat-actor",
  description: "Red-team <target>",
  prompt: "Read target/redteam-prompt-<target-slug>.txt and execute the instructions in that file. Everything you need (threat-model path, diff path, verdict path, sensitive-surface inventories) is in that file."
)
```

The `threat-actor` agent is defined at `.claude/agents/threat-actor.md` (Read/Grep/Glob + Write constrained to the verdict file, opus model). It is intentionally distinct from `code-reviewer`: the framing is adversarial, the inputs are limited to the threat model + diff (no implementation context), and the verdict format is bucketed findings rather than APPROVE/REWORK.

Foreground. The verdict gates the next steps.

### 6. Parse the short chat reply

Expected four-line reply (the full structured verdict — FINDINGS entries with PROMISE/GAP/REPRO, OUT-OF-MODEL items — lives in the verdict file the subagent Wrote, NOT in the reply):

```
RED-TEAM VERDICT: <CLEAN | FINDINGS>
Verdict file: target/redteam-verdict-<target-slug>.txt
Findings: critical=<n> high=<n> medium=<n> low=<n>
Out-of-model: <n>
```

Verify the verdict file exists and is non-empty. If the reply doesn't parse or the file is missing/empty, treat as MANUAL: print the reply verbatim, ask the user how to proceed, do not persist anything.

On `FINDINGS`, Read the verdict file from disk — this is the single point where finding text enters main-session context, and only when there are findings to transcribe. On `CLEAN`, do NOT read the verdict file; the counts in the reply are everything steps 7–8 need.

### 7. Persist findings

**Every audit — regardless of target form and regardless of verdict (CLEAN or FINDINGS) — persists the verbatim verdict to a per-audit markdown file under `docs/plan/<active-milestone>/redteam/<target-slug>-<YYYY-MM-DD>.md`.** Assemble it via Bash so the verdict body never has to round-trip through main-session context: write the frontmatter block (fields below; values come from the short chat reply plus the resolved refs) to the destination with a heredoc, then append the verbatim verdict with `cat target/redteam-verdict-<target-slug>.txt >> <destination>`. Slug rule: target form's args, lowercased, hyphenated:

| Target form | Slug |
|---|---|
| `<ticket-id>` | the ticket ID verbatim (e.g. `M1-033`) |
| `milestone <name>` | `milestone-<name>` (e.g. `milestone-m1`) |
| `id-range <a..b>` | `id-range-<a>-to-<b>` (e.g. `id-range-M1-005-to-M1-012`), lowercased |
| `release <tag>` | `release-<tag>`, lowercased, dots → hyphens (e.g. `release-v0-1-0`) |

The verdict file's frontmatter carries `target`, `date`, `base`, `head`, `verdict`, `findings_count` (per-severity), `out_of_model_count`, and a free-form `disposition:` note for any follow-up reasoning (e.g. which findings were fixed on the branch before squash-merge, which were deferred to a remediation ticket). The body is the verbatim verdict the threat-actor Wrote to the `target/` scratch path (appended via `cat`, per the assembly mechanic above — `target/` is wiped by `mvn clean`, which is why the durable copy lives here). This file is the durable audit record; ticket frontmatter pointers index into it.

**Single-ticket targets ALSO update the ticket frontmatter** in two ways:

1. Append each FINDING (not OUT-OF-MODEL items) to `redteam_findings:`:
   ```yaml
   redteam_findings:
     - date: <YYYY-MM-DD>
       category: <CATEGORY>
       severity: <severity>
       promise: |
         <verbatim>
       gap: |
         <verbatim>
       repro: |
         <verbatim>
       suggested_fix_class: <fix-class>
   ```
   On a CLEAN verdict, set `redteam_findings: []` (the empty list is the explicit "audit ran, no findings" signal).

2. Append an audit-record entry to `redteam_audits:`:
   ```yaml
   redteam_audits:
     - date: <YYYY-MM-DD>
       verdict: <CLEAN | FINDINGS>
       base: <BASE_REF>
       head: <HEAD_REF>
       verdict_file: docs/plan/<active-milestone>/redteam/<ticket-id>-<YYYY-MM-DD>.md
       findings_count: <integer>          # omit when CLEAN; redundant when 0
       out_of_model_count: <integer>
       note: |
         <one-paragraph summary of disposition / what feeds future tickets>
   ```
   `verdict_file:` MUST point at the persistent markdown file (NOT at any path under `target/`, which is git-ignored and wiped by `mvn clean` — using a `target/` path here produces a broken pointer the moment `mvn clean` runs).

**Why both pointers and a separate file:** `redteam_findings:` is structured data the milestone-driver and future remediation tickets can query mechanically (e.g. "list all critical findings across the milestone"). `redteam_audits:` is the audit index — one entry per audit run, regardless of finding count, so a CLEAN audit is still discoverable from the ticket. The separate markdown file is the verbatim record (full PROMISE / GAP / REPRO text, OUT-OF-MODEL observations) that survives `mvn clean` and ages well alongside the ticket.

**Lifecycle-path exemption alignment.** The verdict file at `docs/plan/<active-milestone>/redteam/<slug>-<date>.md` is a workflow-byproduct of `/redteam`, NOT a developer choice — analogous to STATUS.md and the ticket file being byproducts of `/m1-tick`. When a single-ticket audit runs at the `/m1-tick run` gate — after implementation, ahead of `/m1-tick review` — the new audit file appears in the working tree, is visible to the reviewer in the diff it then evaluates, and gets folded into the eventual ticket commit. The exemption is what makes that ordering safe: without it the reviewer would read its own gate's byproduct as an out-of-scope file. The reviewer's lifecycle-path exemption (see `docs/process/reviewer-prompt.md` §"Lifecycle-path exemption") MUST include this directory; the reviewer prompt template lists the exempt paths and is updated in lockstep with this rule.

### 8. Escalate findings to the lifecycle workflow

Findings DO NOT auto-rewrite tickets or auto-fire the milestone-driver's escalate. Cross-skill auto-invocation creates state-machine coupling that is hard to reason about. Instead:

- Print a one-screen summary in chat (count by severity, count by category).
- The recommendation templates below use placeholders that MUST be substituted before printing to chat. None of the literal placeholder strings (`/<driver>`, `M<N>-NNN`, `<milestone>`, `<new-id>`) may appear in user-facing chat output.

  | Placeholder | Substitute with |
  |---|---|
  | `/<driver>` | The milestone-driver skill command. Extract the milestone token from the ticket ID's prefix (e.g. `M1-007` → token `m1`), then form `/m<token>-tick` (so `/m1-tick` for M1, `/m2-tick` for a future M2). |
  | `M<N>-NNN` | The affected ticket's actual ID (e.g. `M1-007`). |
  | `<milestone>` | The active milestone token (e.g. `m1`). |
  | `<new-id>` | The allocated ID per the milestone-driver's ID allocation algorithm; if allocation has not yet run (the user has not opted in to drafting the new ticket), use the literal phrase `the new ID once allocated`. |

  Substitutions are mechanical; the skill performs them at print time, not the subagent.
- For each finding that maps to an existing ticket (single-ticket targets always; multi-target findings the user can opt in to mapping), the recommendation depends on the ticket's status:
  - **Ticket is `in-progress` or `in-review`** — recommend the lifecycle escalation on the same ticket:
    ```
    M<N>-NNN has 2 findings (1 critical, 1 medium). To open the lifecycle
    escalation, run:
      /<driver> escalate M<N>-NNN redteam-finding
    ```
  - **Ticket is `done`** — do NOT recommend escalating the original; per `CLAUDE.md` and `docs/process/workflow.md`, a `done` ticket's commit is never amended. Instead, recommend creating a new remediation ticket whose `remediates:` field points back at the done ticket:
    ```
    M<N>-NNN (done) has 2 findings (1 critical, 1 medium). The done
    commit is immutable; the fix lands as a new remediation ticket.
    Suggested next step:
      1. Draft a new ticket under docs/plan/<milestone>/tickets/ with
         frontmatter `remediates: M<N>-NNN` and acceptance criteria
         derived from the GAP/REPRO blocks above.
      2. Run `/<driver> next` to confirm it appears as runnable, then
         `/<driver> start <new-id>`.
    ```
- For findings without a clear owning ticket (architectural gaps, missing audit coverage spanning many files), recommend the user file a new ticket OR raise a spec amendment via `/<driver> escalate <some-related-ticket> spec-amend`.

The adversary subagent never edits files, runs commands, or fires escalations on its own.

## Cross-cutting rules this skill must obey

- **Read-only on implementation surfaces.** This skill never edits code, commits, or pushes. It only reads, spawns the subagent, writes workflow scratch under `target/` (captured diff, inventory files, rendered prompt; the subagent Writes the raw verdict there), and writes audit artifacts: the assembled verdict file under `docs/plan/<active-milestone>/redteam/<slug>-<date>.md` (always, per step 7), and on single-ticket targets the `redteam_findings:` + `redteam_audits:` frontmatter blocks on the ticket file. No source-code, test, or property-file writes.
- **Fresh context for the adversary.** The subagent must NOT be given conversation history, design notes (`docs/design/**`), or ticket bodies. It sees only the threat model and the diff. Anchoring it on implementer rationale defeats the point.
- **No auto-escalation.** Recommend, don't execute. The user (or Claude in the next turn) decides which findings become tickets.
- **If `docs/spec/security.md` does not exist or is empty, REFUSE.** The threat model is the system's commitments; without it the audit has nothing to compare against.
- **Severity language is canonical.** `critical | high | medium | low`, no synonyms. The redteam subagent's prompt enforces this; if it returns "blocker" or "info" or similar, treat as parse failure.
- **Out-of-model findings are advisory only.** They flag potential threat-model gaps but are not failures. The user decides whether to extend the model.
- **A remediation invalidates the audit that prompted it — re-audit the new diff.** Once findings are fixed in-branch, the diff the adversary read no longer exists, and the fix itself is new, unaudited surface. Never merge on the strength of a superseded verdict. Re-run the audit against the CURRENT diff, and say in the prompt that this IS a re-audit: list the prior findings and instruct the adversary not to assume they are closed because remediation was attempted, and not to re-report them if they genuinely are closed. Without that framing you get either false-closure or duplicates. Persist each round separately (`<slug>-<date>-rN.md`, per step 7).
- **Explicitly authorize a CLEAN verdict on a re-audit.** Add: "Be willing to return CLEAN if the diff genuinely warrants it. An audit that manufactures a finding to justify itself is worse than no audit." A later round under momentum tends to invent a finding to justify having run; saying this is what lets an honest CLEAN terminate the loop. Budget for N+1 rounds on any `security_relevant` ticket whose first audit returns FINDINGS — M1-651 took three (findings → fix → new findings against the fix → fix → CLEAN).
