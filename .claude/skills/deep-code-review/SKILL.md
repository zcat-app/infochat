---
name: deep-code-review
description: Run a deep, honest, senior-engineer review of a target — uncommitted changes, a specific ticket diff, a commit range, a single Maven module, an arbitrary path, the cross-module architecture surface, or "full" (architecture + every module, in parallel, with a consolidated summary). Produces comprehensive markdown reports under .reviews/deep-review/. Independent of /m1-tick — does not gate commits, does not write to ticket frontmatter, can be invoked anytime. Distinct from /redteam (broader than the documented threat model, four categories not one, includes simplification/perf/maintainability lenses) and from the code-reviewer (no ticket scope, no APPROVE/REWORK verdict). Invoke as `/deep-code-review uncommitted | ticket <id> | range <a>..<b> | module <name> | architecture | full | path <path>`.
---

# /deep-code-review — senior-engineer ad-hoc audit

This skill is the procedure. The prompt templates are at:

- [`docs/process/deep-review-prompt-diff.md`](../../../docs/process/deep-review-prompt-diff.md) (diff lens — for `uncommitted`, `ticket`, `range` targets)
- [`docs/process/deep-review-prompt-module.md`](../../../docs/process/deep-review-prompt-module.md) (module lens — for `module`, `path` targets)
- [`docs/process/deep-review-prompt-architecture.md`](../../../docs/process/deep-review-prompt-architecture.md) (architecture lens — for `architecture` target and the architecture pass within `full`)
- [`docs/process/deep-review-synthesizer-prompt.md`](../../../docs/process/deep-review-synthesizer-prompt.md) (synthesizer — only used in `full` mode)

If any of those is absent, the skill refuses with an explicit error.

The reviewer subagent is `senior-developer` (see [`.claude/agents/senior-developer.md`](../../agents/senior-developer.md)) — model: opus, tools: Read/Grep/Glob/Write.
The synthesizer subagent is `review-synthesizer` (see [`.claude/agents/review-synthesizer.md`](../../agents/review-synthesizer.md)) — model: opus, tools: Read/Write.

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

Create the directory. For all single-target forms, the report file is `<run-dir>/report.md`. For `full`, the directory holds multiple reports (see step 5).

### 3. Build the prompt-template substitutions

Read the appropriate prompt template from `docs/process/`. Substitute the placeholders per each template's substitution checklist:

- **diff lens** — `{{TARGET}}`, `{{BASE_REF}}`, `{{HEAD_REF}}`, `{{DIFF_OUTPUT}}`, `{{REPORT_PATH}}`, `{{ENGINEERING_RULES_VERBATIM}}`.
- **module lens** — `{{TARGET}}`, `{{MODULE_PATH}}`, `{{MODULE_FILE_INVENTORY}}`, `{{REPORT_PATH}}`, `{{ENGINEERING_RULES_VERBATIM}}`.
- **architecture lens** — `{{TARGET}}`, `{{REPORT_PATH}}`, `{{ENGINEERING_RULES_VERBATIM}}`, plus the six inventories (`{{SPI_INVENTORY}}`, `{{MIGRATION_INVENTORY}}`, `{{NOTIFY_INVENTORY}}`, `{{CAPABILITY_INVENTORY}}`, `{{PROPERTY_INVENTORY}}`, `{{POM_INVENTORY}}`).

Inventory commands (run from repo root, results dedup'd, sorted, joined with newlines; `(none yet)` substitution if the result is empty):

- SPI: `git ls-files '*/src/main/java/**/spi/*.java' '*/src/main/kotlin/**/spi/*.kt'`
- Migrations: `git ls-files '*/src/main/resources/db/migration/*.sql'`
- NOTIFY: `git grep -nE 'NOTIFY |LISTEN ' -- '*.java' '*.kt'`
- Capability: `git grep -nE 'supports(MarkdownLinks|CodeFormatting|MembershipEvents|MentionByContactId|MessageEdit)' -- '*.java' '*.kt'`
- Properties: `git ls-files '*application*.properties'` joined with `git grep -nE '@ConfigProperty' -- '*.java' '*.kt'`
- POMs: `git ls-files 'pom.xml' '*/pom.xml'`
- Module file inventory (module/path lens): `git ls-files '<MODULE_PATH>/**/*.java' '<MODULE_PATH>/**/*.kt' '<MODULE_PATH>/**/*.sql' '<MODULE_PATH>/**/*.properties' '<MODULE_PATH>/**/*.json' '<MODULE_PATH>/**/*.xml'`

If any required substitution is empty for the lens being invoked (e.g. `{{DIFF_OUTPUT}}` empty for diff lens), the skill refuses rather than spawning an agent against an empty target.

### 4. Spawn the senior-developer subagent (single-target forms)

For `uncommitted`, `ticket`, `range`, `module`, `path`, `architecture`:

```
Agent(
  subagent_type: "senior-developer",
  prompt: <substituted prompt>,
  description: "Deep-review <target>"
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

#### 5a. Enumerate modules

Read the parent `pom.xml`. Extract `<module>` entries from `<modules>`. Build the list `[infochat-core, infochat-ssrf, infochat-llm-adapter, ...]` (whatever exists at the time).

Filter to modules that have at least one source file under `<module>/src/`. Modules declared in the parent pom but not yet implemented are skipped (and noted in the run's manifest).

#### 5b. Print cost estimate and confirm

```
/deep-code-review full will spawn N+1 subagents in parallel
  - 1 architecture-lens review
  - <N> module-lens reviews (one per implemented module: <list>)
followed by 1 synthesizer pass.

Estimated work:
  - <total-file-count> source files will be read across all agents
  - approximate completion: <slowest agent's expected duration>
  - reports written to .reviews/deep-review/full-<slug>/

Proceed? (yes/no)
```

Wait for the user to type `yes` (case-insensitive, exact match) before continuing. Any other response aborts cleanly without spawning agents.

#### 5c. Spawn all reviewer agents in parallel

In ONE message, spawn N+1 Agent calls in a single tool-use batch:

- 1 architecture agent (using the architecture prompt template, REPORT_PATH = `<run-dir>/01-architecture.md`)
- N module agents (one per implemented module, using the module prompt template, REPORT_PATH = `<run-dir>/<NN>-module-<name>.md` with NN starting at 02)

All agents share the same `senior-developer` subagent type. Each gets its own fresh-context spawn — they cannot see each other.

Wait for all to complete. Track which succeeded (report file exists and is non-empty) and which failed (no report, or empty report).

#### 5d. Build the synthesizer manifest

```
{{REPORT_FILES}} = newline-separated <role>:<path> for every successful report
{{FAILED_TARGETS}} = newline-separated <role>:<reason> for every failure (empty if all succeeded)
{{RUN_DIR}} = the full-mode run directory
```

If `{{REPORT_FILES}}` is empty (all agents failed), do NOT spawn the synthesizer. Print the failure summary to the user, list all FAILED_TARGETS with reasons, suggest re-running individual targets, stop.

If `{{REPORT_FILES}}` contains exactly one report, do NOT spawn the synthesizer. Print "only one report succeeded — synthesis skipped, open the single report directly: <path>", stop.

Otherwise (≥ 2 reports succeeded), spawn the synthesizer:

#### 5e. Spawn the synthesizer (sequential, after all reviewers)

```
Agent(
  subagent_type: "review-synthesizer",
  prompt: <substituted synthesizer prompt>,
  description: "Synthesize deep-review-full-<slug>"
)
```

Foreground. Wait for completion. Verify `<run-dir>/00-summary.md` exists and is non-empty.

#### 5f. Print full-mode summary

```
/deep-code-review full complete.

Run directory: <RUN_DIR>

Reports:
  - 00-summary.md   ← read this first
  - 01-architecture.md
  - 02-module-<name>.md
  - 03-module-<name>.md
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

- **Read-only as far as code goes.** This skill never edits source code, never commits, never pushes, never invokes mvn. It only reads, spawns subagents, and writes reports under `.reviews/deep-review/`.
- **No auto-escalation.** Findings are advisory. The skill does not file tickets, does not write to ticket frontmatter, does not call `/m1-tick escalate`. The user decides what becomes a ticket.
- **Fresh context per spawn.** Every senior-developer and review-synthesizer subagent runs in fresh context. Never pass conversation history, never reuse a prior agent's notes.
- **Honesty is enforced in the prompt, not by the skill.** The skill cannot verify whether a report is honest. The prompt templates and agent personas embed the "don't soften, don't invent" rule. If a future user complaint surfaces that reports are sycophantic or padded, fix the prompt template, not the skill.
- **Reports are local-only.** `.reviews/` is gitignored. Reports do not become part of the repo history. If the user wants a permanent record, they can copy specific reports into `docs/` themselves.
- **Never delete prior reports.** The `.reviews/deep-review/` directory accumulates. The user can `rm -rf .reviews/deep-review/` themselves if they want a clean slate; the skill never does so.
- **Cost-confirm only for `full`.** Single-target forms run without confirmation — they are cheap enough that prompting would be more friction than the actual cost. `full` is the only form that fans out N+1 spawns.
- **If a per-target agent fails in `full` mode, the run continues.** The synthesizer runs on the successes and flags the failure in its header. The user re-runs the failed target individually.
- **Refuse rather than substitute empty.** If any required placeholder is empty (no diff, no module files, no SPI inventory at all), refuse with a clear message rather than spawning an agent against nothing.
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
