---
name: deep-code-review
description: Run a deep, honest, senior-engineer review of a target — uncommitted changes, a ticket diff, a commit range, a Maven module, an arbitrary path, the cross-module architecture surface, or "full" (architecture + every module in parallel with a consolidated summary). Writes comprehensive markdown reports under .reviews/deep-review/; advisory only — independent of /m1-tick gates and broader than /redteam's threat-model-only lens. Invoke as `/deep-code-review uncommitted | ticket <id> | range <a>..<b> | module <name> | architecture | full | path <path>`.
---

# /deep-code-review — senior-engineer ad-hoc audit

This skill is the procedure. The prompt templates are at:

- [`docs/process/deep-review-prompt-diff.md`](../../../docs/process/deep-review-prompt-diff.md) (diff lens — for `uncommitted`, `ticket`, `range` targets)
- [`docs/process/deep-review-prompt-module.md`](../../../docs/process/deep-review-prompt-module.md) (module lens — for `module`, `path` targets)
- [`docs/process/deep-review-prompt-architecture.md`](../../../docs/process/deep-review-prompt-architecture.md) (architecture lens — for `architecture` target and the architecture pass within `full`)
- [`docs/process/deep-review-synthesizer-prompt.md`](../../../docs/process/deep-review-synthesizer-prompt.md) (synthesizer — only used in `full` mode)

If any of those is absent, the skill refuses with an explicit error.

The reviewer subagent is `senior-developer` (see [`.claude/agents/senior-developer.md`](../../agents/senior-developer.md)) — tools: Read/Grep/Glob/Write.
The synthesizer subagent is `review-synthesizer` (see [`.claude/agents/review-synthesizer.md`](../../agents/review-synthesizer.md)) — tools: Read/Write.

## Model selection

**Default: the fast/cheap tier, not the session model.** On Claude Code that is
`sonnet`, passed as the `model` parameter on each `Agent(...)` call. Both agent
definitions keep `model: inherit` in their frontmatter on purpose — a hardcoded
model there would bind only Claude Code and be silently inert on
opencode/Codex/kimi (`docs/process/harness-mapping.md` §6, "`model: inherit`
has no cross-tool equivalent"), so the two paths would diverge with no visible
signal. The policy lives here, in the procedure every harness reads.

Non-Claude harnesses: use your tool's configured fast model. If you cannot
select one, run the session default and say so in the printed summary.

**Why cheap is correct here, when gates demand a strong model.** The
harness-mapping rule "use a strong model for gates — a weak reviewer is worse
than none, because it APPROVEs" governs `/m1-tick review` and `/redteam`, which
*emit a verdict that gates a commit*. This skill emits no verdict and gates
nothing; its failure mode is a missed finding, not a false APPROVE. The
binding constraint here is cost, because cost is what pushes a run toward the
sampling shape that does not work (see §5a). Do not "fix" this inconsistency by
raising the model — raise the coverage instead.

**Choosing at invocation:**

- Single-target forms (`uncommitted`, `ticket`, `range`, `module`, `path`,
  `architecture`): use the fast tier without asking. These are one spawn; a
  prompt costs more friction than the run costs money.
- `full`: offer the choice at the cost-confirm gate in §5b, with the fast tier
  marked `(recommended)`. One prompt already exists there, so this adds no new
  interruption.
- If the requested model is unavailable in the current harness, fall back to
  the session default, **announce the fallback in the printed summary and in
  the report header**, and continue. Never substitute a model silently.

The model actually used is substituted into every rendered prompt as
`{{REVIEWER_MODEL}}` and appears in each report's `**Reviewer:**` header line,
so a report always records what produced it.

This skill is intentionally decoupled from `/m1-tick` and `/redteam`. It does not gate commits, does not write to ticket frontmatter, does not invoke the workflow's escalation menu. The user reads the reports and decides what to act on.

## Invocation forms

```
/deep-code-review uncommitted              # diff lens — working tree + index vs HEAD
/deep-code-review ticket <id>              # diff lens — single ticket's diff range
/deep-code-review range <a>..<b>           # diff lens — arbitrary commit range
/deep-code-review module <name>            # module lens — one Maven module top-to-bottom
/deep-code-review path <path>              # module lens — arbitrary directory or file
/deep-code-review architecture             # architecture lens — cross-module contract surface
/deep-code-review full                     # architecture + every module, parallel + synthesizer
```

If the args don't match any row, print the table above and stop.

## Preconditions

Common (all forms):

- `docs/process/engineering-rules-verbatim.md` exists. Refuse if not.
- `docs/process/deep-review-prompt-*.md` exist for the form being invoked. Refuse if not.
- `.reviews/` is gitignored (the skill creates the directory if missing). The skill never writes outside `.reviews/`.

Per form:

- `uncommitted`: there is at least one uncommitted change (working tree or index). Refuse cleanly with "no uncommitted changes" if not.
- `ticket <id>`: the ticket file `docs/plan/<milestone>/tickets/<id>-*.md` exists. The diff range is resolved per the algorithm below.
- `range <a>..<b>`: both refs resolve to commits.
- `module <name>`: a Maven module with that name exists (directory `<name>/` with a `pom.xml`). The module has at least one file under `<name>/src/`. Refuse if module not yet implemented.
- `path <path>`: the path exists and contains at least one source file (`.java`, `.kt`, `.sql`, `.properties`, `.json`, `.xml`).
- `architecture`: at least one Maven module exists. If the module count is < 2, warn ("architecture review will be sparse — only N module(s) exist") but proceed; the agent will produce a short report.
- `full`: at least two Maven modules exist (otherwise it degrades to architecture-only — recommend invoking `architecture` directly instead).

## Steps

### 1. Resolve target and lens

| Target form | Lens | Notes |
|---|---|---|
| `uncommitted` | diff | BASE = HEAD; HEAD includes working tree + index (use `git diff HEAD` + `git status --short`) |
| `ticket <id>` | diff | Use the single-ticket diff-range algorithm below |
| `range <a>..<b>` | diff | BASE = `<a>`, HEAD = `<b>` |
| `module <name>` | module | MODULE_PATH = `<name>/` |
| `path <path>` | module | MODULE_PATH = `<path>` |
| `architecture` | architecture | (no diff range) |
| `full` | mixed | architecture + N module spawns in parallel + 1 synth spawn |

**Single-ticket diff-range algorithm** (mirrors `/redteam`'s, simplified):

1. Run `git log --grep="^<ticket-id>: " --format=%H main` to find the squash-merge commit. If exactly one hash returns, set `BASE = <hash>^`, `HEAD = <hash>`. Done.
2. Otherwise check `git rev-parse --verify --quiet refs/heads/m<N>/<ticket-id>-*` for an in-progress branch. If exactly one match, set `BASE = main`, `HEAD = <branch>`. Done.
3. Otherwise refuse with: "ticket <id>: cannot resolve a diff range (no merged commit, no in-progress branch matching `m<N>/<ticket-id>-*`)."

If step 1 returns multiple matches, refuse — the no-amend / one-commit-per-ticket invariant has been violated and the user must investigate before this skill can produce a meaningful report.

### 2. Determine the run directory

Generate a timestamp slug: `<YYYY-MM-DD-HHmm>` (local time, minute precision — collision risk for back-to-back runs is acceptable for an ad-hoc audit; the second run gets a `-2` suffix).

The run directory depends on the target:

| Target | Run directory |
|---|---|
| `uncommitted` | `.reviews/deep-review/uncommitted-<slug>/` |
| `ticket <id>` | `.reviews/deep-review/ticket-<id>-<slug>/` |
| `range <a>..<b>` | `.reviews/deep-review/range-<a>-to-<b>-<slug>/` (sanitize refs: replace `/` with `_`) |
| `module <name>` | `.reviews/deep-review/module-<name>-<slug>/` |
| `path <path>` | `.reviews/deep-review/path-<sanitized-path>-<slug>/` |
| `architecture` | `.reviews/deep-review/architecture-<slug>/` |
| `full` | `.reviews/deep-review/full-<slug>/` |

Create the directory, plus an `inputs/` subdirectory for workflow scratch (captured diff, inventory files, rendered prompts) — everything stays inside `.reviews/`, honoring the never-write-outside-`.reviews/` rule. For all single-target forms, the report file is `<run-dir>/report.md`. For `full`, the directory holds multiple reports (see step 5).

### 3. Capture inputs and render the prompt

Do NOT Read the prompt templates, the engineering rules, the diff, or the inventories into main-session context. Capture the big inputs to files via shell redirection, then render the template with `scripts/m1-render-prompt.py` (same pattern as `/m1-tick`: the script extracts the fenced template body, substitutes `{{KEY}}` args, and supports `@/path/file` values for multi-line content). The engineering rules are never substituted — each template instructs the agent to Read `docs/process/engineering-rules-verbatim.md` in its own fresh context.

Per lens:

- **diff lens** — capture the diff: `git diff <BASE_REF>...<HEAD_REF> > <run-dir>/inputs/diff.patch` (for `uncommitted`: `git diff HEAD > <run-dir>/inputs/diff.patch && git status --short >> <run-dir>/inputs/diff.patch`). Then render:

  ```
  python3 scripts/m1-render-prompt.py \
    docs/process/deep-review-prompt-diff.md \
    <run-dir>/inputs/prompt.txt \
    TARGET=<target> BASE_REF=<base> HEAD_REF=<head> \
    DIFF_FILE_PATH=<run-dir>/inputs/diff.patch \
    REPORT_PATH=<run-dir>/report.md \
    REVIEWER_MODEL=<the model this run will use>
  ```

**`REVIEWER_MODEL` is substituted on every render, in every lens.** The render script only WARNs on an unfilled placeholder, so omitting it does not fail the run — it ships a report whose header reads `senior-developer ({{REVIEWER_MODEL}})`. Treat that string appearing in a report as a skill bug, not a cosmetic one: it means the run cannot say what reviewed the code.

- **module lens** — capture the file inventory (command below) to `<run-dir>/inputs/inventory.txt`, then render `docs/process/deep-review-prompt-module.md` with `TARGET`, `MODULE_PATH`, `REPORT_PATH`, and `MODULE_FILE_INVENTORY=@<run-dir>/inputs/inventory.txt`.

- **architecture lens** — capture each of the six inventories (commands below) to `<run-dir>/inputs/<name>.txt`; if a file comes out empty, overwrite it with the literal `(none yet)` (`[ -s <file> ] || echo '(none yet)' > <file>`). Then render `docs/process/deep-review-prompt-architecture.md` with `TARGET`, `REPORT_PATH`, and each inventory as `@file`.

Inventory commands (run from repo root, results dedup'd, sorted, redirected to the `inputs/` file):

- SPI: `git ls-files '*/src/main/java/**/spi/*.java' '*/src/main/kotlin/**/spi/*.kt'`
- Migrations: `git ls-files '*/src/main/resources/db/migration/*.sql'`
- NOTIFY: `git grep -nE 'NOTIFY |LISTEN ' -- '*.java' '*.kt'`
- Capability: `git grep -nE 'supports(MarkdownLinks|CodeFormatting|MembershipEvents|MentionByContactId|MessageEdit)' -- '*.java' '*.kt'`
- Properties: `git ls-files '*application*.properties'` joined with `git grep -nE '@ConfigProperty' -- '*.java' '*.kt'`
- POMs: `git ls-files 'pom.xml' '*/pom.xml'`
- Module file inventory (module/path lens): `git ls-files '<MODULE_PATH>/**/*.java' '<MODULE_PATH>/**/*.kt' '<MODULE_PATH>/**/*.sql' '<MODULE_PATH>/**/*.properties' '<MODULE_PATH>/**/*.json' '<MODULE_PATH>/**/*.xml'`

If a required input file is empty for the lens being invoked (e.g. `<run-dir>/inputs/diff.patch` for diff lens, `inventory.txt` for module lens — check with `[ -s <file> ]`), the skill refuses rather than spawning an agent against an empty target. (The `(none yet)` fallback applies only to the architecture lens's individual seed inventories, which may legitimately be empty.)

### 4. Spawn the senior-developer subagent (single-target forms)

For `uncommitted`, `ticket`, `range`, `module`, `path`, `architecture`:

```
Agent(
  subagent_type: "senior-developer",
  description: "Deep-review <target>",
  prompt: "Read <run-dir>/inputs/prompt.txt and execute the instructions in that file. Everything you need (lens, input paths, report path) is in that file."
)
```

Foreground. Wait for completion. The agent writes its report directly to `{{REPORT_PATH}}` via the Write tool; the skill does not parse the agent's stdout.

After the agent returns:

1. Verify `{{REPORT_PATH}}` exists and is non-empty. If missing or empty, surface the failure to the user and stop.
2. Read the report's `## Headline findings` section (first H2 after the header block).
3. Print a one-screen summary in chat:
   ```
   /deep-code-review <target> complete.

   Report: <REPORT_PATH>

   Headline findings (<count>):
     <verbatim copy of the headline-findings bullets>

   The full report contains per-finding reasoning, current-code citations,
   recommended fixes, trade-offs, and alternative options. Open the report
   file to read the detail.
   ```
4. Done. No further action — this skill never auto-applies fixes, never files tickets, never escalates.

### 5. Full-mode flow (parallel + synthesizer)

When `target = full`:

#### 5a. Partition every reviewable file into slices (complete cover)

`full` is **exhaustive by construction, never sampling.** One agent per *module*
does not work: a 492-file module cannot be read in one context, so the agent
reads a cross-section and the report still reads like a verdict. Measured on
this repo (2026-06-27, same commit): the per-module shape produced **8 findings
/ 0 highs**; the partitioned shape below produced **~75 findings / 2 highs**,
including a stranded-RAW-post correctness bug and a future partition
build-break. The unit of fan-out is therefore a **slice**, sized so the module
template's "read every file in this list" is literally achievable.

1. **Enumerate the full reviewable set.** From the repo root:

   ```
   git ls-files '*/src/main/java/**/*.java' '*/src/main/kotlin/**/*.kt' > <run-dir>/inputs/all-prod.txt
   git ls-files '*/src/test/java/**/*.java' '*/src/test/kotlin/**/*.kt' > <run-dir>/inputs/all-test.txt
   git ls-files '*/src/main/resources/db/migration/*.sql' \
                '*/src/test/resources/**/*.sql' \
                '*/src/**/*.properties' '*/src/**/*.json' '*/src/**/*.xml' > <run-dir>/inputs/all-other.txt
   ```

   **Watch the resources glob.** `.sql` under `src/test/resources` is missed by a
   java/kt/properties/json/xml glob — it is listed above deliberately. Any glob
   you add must be added to the union check in step 4 as well, or the cover
   silently stops being complete.

2. **Slice.** Split each list into slices, grouped by module and by package
   where possible so each slice is coherent rather than alphabetically
   arbitrary:
   - production: **≤ 22 files** per slice
   - test: **≤ 40 files** per slice (test files are more repetitive per line)
   - other (migrations, resources): **≤ 40 files** per slice

   Write each slice to `<run-dir>/inputs/slice-<NN>-<module>-<prod|test|other>.txt`.

3. **Verify the partition is a complete cover BEFORE spawning.** This is the
   step that makes the run exhaustive rather than merely intended-to-be:

   ```
   cat <run-dir>/inputs/slice-*.txt | sort > <run-dir>/inputs/cover.txt
   cat <run-dir>/inputs/all-*.txt   | sort > <run-dir>/inputs/expected.txt
   comm -3 <run-dir>/inputs/cover.txt <run-dir>/inputs/expected.txt
   ```

   `comm -3` must print **nothing** — zero files missing, zero duplicated.
   Also check `wc -l` matches between the two. If either check fails, **refuse
   to spawn** and print the offending paths. A partial cover reported as a
   `full` run is the exact false-confidence failure this mode exists to
   prevent.

4. Modules declared in the parent pom with no file under `<module>/src/` are
   skipped and noted in the run manifest.

The architecture pass (one agent, the six seed inventories) is unchanged — it
is genuinely cross-cutting and does not partition.

#### 5b. Print cost estimate and confirm

```
/deep-code-review full — EXHAUSTIVE run (complete cover, no sampling)

  - 1 architecture-lens review
  - <S> slice reviews covering <total-file-count> files:
      <P> production slices (<prod-count> files, <=22 each)
      <T> test slices        (<test-count> files, <=40 each)
      <O> other slices       (<other-count> migrations/resources)
followed by 1 synthesizer pass.

Cover verified: <total-file-count>/<total-file-count> files, 0 missing, 0 duplicated.

Model: sonnet (recommended — this is what makes the exhaustive form
       affordable; the sampling form it replaces is what produced
       false-clean reports)
       Reply "yes" to accept, or "yes <model>" to override.

Reports written to .reviews/deep-review/full-<slug>/

Proceed? (yes/no)
```

Wait for the user to reply. `yes` (case-insensitive) proceeds with the recommended model. `yes <model>` proceeds with the named model. Any other response aborts cleanly without spawning agents. Print the cover-verification line only after step 5a's `comm` check has actually passed — never as a fixed string.

#### 5c. Render all prompts, then spawn all reviewer agents in parallel

First render S+1 prompt files per step 3's mechanics (inputs and rendered prompts all under `<run-dir>/inputs/`):

- 1 architecture prompt → `<run-dir>/inputs/prompt-01-architecture.txt` (REPORT_PATH = `<run-dir>/01-architecture.md`; the six inventories captured once, shared by this render)
- S slice prompts → `<run-dir>/inputs/prompt-<NN>-<module>-<prod|test|other>.txt` (REPORT_PATH = `<run-dir>/<NN>-<module>-<prod|test|other>.md` with NN starting at 02), each rendered from the module template with `MODULE_FILE_INVENTORY=@<run-dir>/inputs/slice-<NN>-....txt` and `MODULE_PATH` = the slice's module root

Every render also substitutes `REVIEWER_MODEL=<the model chosen at 5b>`.

Then spawn S+1 Agent calls — each a one-line stub pointing at its rendered prompt file (same stub form as step 4), each carrying the chosen model:

```
Agent(
  subagent_type: "senior-developer",
  model: "<chosen model>",
  description: "Deep-review slice <NN> <module>/<kind>",
  prompt: "Read <run-dir>/inputs/prompt-<NN>-....txt and execute the instructions in that file."
)
```

Batch them across as few messages as the harness allows; the harness queues beyond its concurrency cap, so a large S is fine. All agents share the same `senior-developer` subagent type. Each gets its own fresh-context spawn — they cannot see each other.

Wait for all to complete, then **verify every slice landed**: for each rendered prompt there must be a non-empty report file. Missing reports are the silent way a "complete cover" run degrades back into a sample, so this check is mandatory and its result is reported to the user, not just tracked internally. Re-spawn missing slices once; if a slice fails twice, record it in `failed-targets.txt` and state it in the final summary.

#### 5d. Build the synthesizer manifest

Write the two manifest lists to files so they ride the render script's `@file` form:

```
<run-dir>/inputs/report-files.txt   = newline-separated <role>:<path> for every successful report
<run-dir>/inputs/failed-targets.txt = newline-separated <role>:<reason> for every failure (empty file if all succeeded)
{{RUN_DIR}} = the full-mode run directory (inline substitution)
```

If `report-files.txt` is empty (all agents failed), do NOT spawn the synthesizer. Print the failure summary to the user, list all failed targets with reasons, suggest re-running individual targets, stop.

If `report-files.txt` contains exactly one report, do NOT spawn the synthesizer. Print "only one report succeeded — synthesis skipped, open the single report directly: <path>", stop.

Otherwise (≥ 2 reports succeeded), spawn the synthesizer:

#### 5e. Spawn the synthesizer (sequential, after all reviewers)

Render the synthesizer prompt:

```
python3 scripts/m1-render-prompt.py \
  docs/process/deep-review-synthesizer-prompt.md \
  <run-dir>/inputs/prompt-00-synth.txt \
  RUN_DIR=<run-dir> \
  REPORT_FILES=@<run-dir>/inputs/report-files.txt \
  FAILED_TARGETS=@<run-dir>/inputs/failed-targets.txt
```

```
Agent(
  subagent_type: "review-synthesizer",
  description: "Synthesize deep-review-full-<slug>",
  prompt: "Read <run-dir>/inputs/prompt-00-synth.txt and execute the instructions in that file. Everything you need (report list, failed targets, summary path) is in that file."
)
```

Foreground. Wait for completion. Verify `<run-dir>/00-summary.md` exists and is non-empty.

#### 5f. Print full-mode summary

```
/deep-code-review full complete (exhaustive).

Run directory: <RUN_DIR>
Model:         <model used><, or " (FALLBACK — <requested> unavailable)">

Coverage:      <files-reviewed>/<files-expected> files across <S> slices
               <"complete cover verified" | "INCOMPLETE — N slices failed, see below">

Reports:
  - 00-summary.md   ← read this first
  - 01-architecture.md
  - 02-<module>-prod.md
  - 03-<module>-test.md
  - ...

<if FAILED_TARGETS non-empty:>
Failed targets (re-run individually):
  - <role>: <reason>
  - ...

Top priority (from 00-summary.md):
  <verbatim copy of the "## Top priority" list from the summary>

Cross-cutting themes: <count, or "none">
Total findings by category:
  SECURITY: <count>
  PERFORMANCE: <count>
  SIMPLIFICATION: <count>
  MAINTAINABILITY-RULES-DRIFT: <count>

Open 00-summary.md for the consolidated view, or any per-target report
for line-precise detail.
```

## Cross-cutting rules this skill must obey

- **Read-only as far as code goes.** This skill never edits source code, never commits, never pushes, never invokes mvn. It only reads, spawns subagents, and writes under `.reviews/deep-review/` (workflow inputs — captured diffs, inventories, rendered prompts — under `<run-dir>/inputs/`; the agents write the reports).
- **No auto-escalation.** Findings are advisory. The skill does not file tickets, does not write to ticket frontmatter, does not call `/m1-tick escalate`. The user decides what becomes a ticket.
- **Fresh context per spawn.** Every senior-developer and review-synthesizer subagent runs in fresh context. Never pass conversation history, never reuse a prior agent's notes.
- **Honesty is enforced in the prompt, not by the skill.** The skill cannot verify whether a report is honest. The prompt templates and agent personas embed the "don't soften, don't invent" rule. If a future user complaint surfaces that reports are sycophantic or padded, fix the prompt template, not the skill.
- **Reports are local-only.** `.reviews/` is gitignored. Reports do not become part of the repo history. If the user wants a permanent record, they can copy specific reports into `docs/` themselves.
- **Never delete prior reports.** The `.reviews/deep-review/` directory accumulates. The user can `rm -rf .reviews/deep-review/` themselves if they want a clean slate; the skill never does so.
- **Cost-confirm only for `full`.** Single-target forms run without confirmation — they are cheap enough that prompting would be more friction than the actual cost. `full` is the only form that fans out S+1 spawns.
- **Never report a partial cover as `full`.** `full` means every reviewable file was in some agent's inventory. If the §5a `comm` check fails, refuse to spawn; if slices fail at runtime, the printed summary and the synthesizer header must both say `INCOMPLETE` and name the uncovered files. A `full` report that reads clean because nobody looked is the specific failure this mode was rebuilt to prevent — it is worse than no run, because it manufactures confidence.
- **If a per-target agent fails in `full` mode, the run continues.** The synthesizer runs on the successes and flags the failure in its header. The user re-runs the failed target individually.
- **Refuse rather than substitute empty.** If a required input file is empty (no diff for the diff lens, no module files for the module lens), refuse with a clear message rather than spawning an agent against nothing. Architecture seed inventories may be individually empty (`(none yet)` fallback per step 3); the architecture-too-thin case is governed by the preconditions, not this rule.
- **Never spawn the threat-actor or code-reviewer subagents.** Those belong to `/redteam` and `/m1-tick review` respectively. This skill spawns only senior-developer and review-synthesizer.

## When this skill is the right tool

- Ad-hoc audit you want a second opinion on, before or after a ticket.
- Sanity check before tagging a release (`full` mode).
- Investigating "is this module getting too messy?" (`module <name>` form).
- Verifying contracts after a refactor that crossed module boundaries (`architecture` form).
- Reviewing uncommitted work-in-progress without finishing the ticket first (`uncommitted`).

## When this skill is the WRONG tool

- Workflow-gated ticket review → use `/m1-tick review <id>`.
- Adversarial security audit against the documented threat model → use `/redteam <target>`.
- Spec or design review (no code) → just read the docs; this skill needs code to review.
- Quick "should I rename this variable?" question → just ask in chat; spawning a subagent for one line is over-engineering.
