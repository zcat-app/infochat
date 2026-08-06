# AGENTS.md — entry point for non-Claude coding agents

<!-- This file contains POINTERS, never rule text. The canonical sources are
     listed below — if you find rule prose in this file, it is drift: delete
     it here and follow the canonical source. -->

This repo is developed by coding agents under a gate-driven, ticket-based
workflow. Claude Code is the native harness (it loads `CLAUDE.md` and
`.claude/**` directly and does not need this file). If you are any other
agent — opencode, Codex CLI, or something else — this file is your entry
point. Both routes lead to the same canonical sources; neither tool's config
overrides the other's.

## Orientation

Two-service Quarkus 3.33 / Java 25 application (news + social-media
aggregator chatbot): a Collector (feed ingest + LLM evaluation pipeline, no
user-facing API) and a Provider (messaging-app adapters, commands, chat).
Maven multi-module; PostgreSQL + pgvector. Build/verify: `mvn verify` from
the repo root. Setup: [DEVELOPER.md](DEVELOPER.md).

## Canonical sources (read these, in this order of need)

| Topic | Canonical source |
|---|---|
| Engineering rules (binding on every change) | [docs/process/engineering-rules-verbatim.md](docs/process/engineering-rules-verbatim.md) — read in full before changing code |
| Project conventions + coding style | [CLAUDE.md](CLAUDE.md) §Key conventions and §Coding style — apply both; IGNORE its Claude-harness sections (§Context budget heuristics, §M1 workflow pointers into `.claude/`) |
| Ticket workflow (lifecycle, escalation, review) | [docs/process/workflow.md](docs/process/workflow.md) |
| Analysis-first ticket flow (the flow for NEW work; supersedes m1-tick, which is deprecated but still invocable for its existing board) | [docs/process/tick-workflow.md](docs/process/tick-workflow.md) — driven by the [`/tick` skill](.agents/skills/tick/SKILL.md) — one procedure under `.agents/skills/tick/subcommands/`, behind two routers split by discovery surface: `.agents/skills/tick/SKILL.md` (yours) and `.claude/skills/tick/SKILL.md` (Claude Code); tickets in `docs/plan/m1/tick-tickets/`, analyses in `tick-analysis/`, board `STATUS-TICK.md`, comparison via `scripts/tick-measure.py`. Do NOT drive tick-flow tickets with `/m1-tick` or vice versa |
| Runnable workflow procedures | `.agents/skills/{m1-tick,redteam,redteam-multi,deep-code-review}/SKILL.md` — thin wrappers over the single-sourced procedures in `.claude/skills/`; `/tick` inverts that direction — its procedure is single-sourced under `.agents/skills/tick/subcommands/`, and `.claude/skills/tick/SKILL.md` is Claude Code's router over it |
| Harness bindings (how YOUR tool runs the gates) | [docs/process/harness-mapping.md](docs/process/harness-mapping.md) |
| Ticket format | [docs/process/ticket-template.md](docs/process/ticket-template.md) |
| Contribution walk-through | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Domain spec | [docs/SPEC.md](docs/SPEC.md) (map) → `docs/spec/*` |

Kimi Code launch note: start interactive sessions as
`kimi --skills-dir .agents/skills`. kimi auto-discovers skills from BOTH
`.claude/skills/` and `.agents/skills/` with no kill switch, and the pin
guarantees the wrapper copies (not the raw Claude procedures) win — see
harness-mapping §6.3. Gate agents are spawned headlessly via
`scripts/run-gate.sh` (harness-mapping §3).

One convention worth a literal line because every agent commits: commit
prefixes are `M1-NNN:` (ticket work), `spec:` (pure spec/design edit),
`process:` (process/tooling/plan edit) — full rules in
[docs/process/workflow.md](docs/process/workflow.md) §Non-ticket commits.

## Memory

Accumulated project knowledge lives in two stores. Neither is auto-loaded —
reading them at session start IS the mechanism:

1. `.agents/memory/MEMORY.md` (committed) — durable, portable project
   knowledge: build/test gotchas, process rules, config knowledge. Read the
   index; open only the entries relevant to your task.
2. `.agents/memory-local/MEMORY.md` (gitignored; absent in fresh clones) —
   machine/deployment facts for THIS checkout: prod state, live-test
   procedures. Read it too if it exists.

Write convention: a durable, portable project fact → `.agents/memory/`
(committed with a `process:` prefix; NEVER include bot addresses, live-user
data, host paths, or prod runtime state — that class of fact belongs in the
local store). A machine/deployment fact → `.agents/memory-local/` (never
committed). A quirk of your own harness → your tool's own memory mechanism,
not these stores.

## Hard boundaries

- Never modify `.claude/**` or `CLAUDE.md` — that is Claude Code's config
  surface. Your tool's equivalents live in `.agents/`, `.opencode/`,
  `.codex/`, and this file. One exception: `.claude/skills/tick/SKILL.md` is
  Claude Code's router over the `/tick` procedure you own, so a change to a
  ROUTER rule (dispatch table, cross-cutting rules) must be made in both it
  and `.agents/skills/tick/SKILL.md`. Subcommand edits stay in `.agents/`.
- The quality gates (code review, red-team) are fresh-context agents by
  design. Run them per
  [docs/process/harness-mapping.md](docs/process/harness-mapping.md) §2–§3 —
  never inline in your main session, and always apply the §6 contamination
  check after a gate returns.
