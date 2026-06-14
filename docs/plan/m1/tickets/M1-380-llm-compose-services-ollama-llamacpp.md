---
id: M1-380
title: "deploy: add ollama + llama.cpp compose services, profile-gated so only the chosen backend starts"
status: pending
created: 2026-06-15
last_updated: 2026-06-15
blocked_by:
  - M1-379
files_budget: 1
files_scope:
  - docker-compose.yml
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Model provisioning (ollama pull / GGUF download) — that is the wizard's 4-llm.sh step (M1-383); this ticket only declares the services.
  - The remote-LLM path — no service is needed for it (the app talks to the remote API directly); the wizard collects the base-url/key.
  - Any infochat.llm.* application.properties change — service declaration only.
acceptance:
  - "docker-compose.yml declares an `ollama` service (image ollama/ollama at a pinned tag, named volume for the model cache) tagged `profiles: [dev, ollama]`, and a `llamacpp` service (a llama.cpp server image exposing an OpenAI-compatible endpoint, named model volume) tagged `profiles: [llamacpp]`."
  - "Neither LLM service starts under a bare `--profile prod`: `docker compose --profile prod --profile ollama config` includes ollama and excludes llamacpp; `docker compose --profile prod --profile llamacpp config` includes llamacpp and excludes ollama (grep on the two config outputs)."
  - "The ollama service is available to the dev profile too (`docker compose --profile dev config` includes ollama), matching §7.7's dev shape."
  - "`docker compose config` across all profiles exits 0."
  - "mvn -B verify from the repo root exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7 Local and containerized stack
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs:
  - D27
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-380: ollama + llama.cpp compose services (profile-gated)

## Context

The prod stack needs a local LLM, and the operator chooses Ollama or llama.cpp
(`07-deployment.md` §7.7.2 wizard — both offered). This ticket adds both as
compose services, each on its own Compose profile so the wizard starts exactly
the chosen one alongside `--profile prod`: `--profile prod --profile ollama` or
`--profile prod --profile llamacpp`. Ollama is also exposed under the `dev`
profile (the developer inner loop uses it). llama.cpp speaks the
`openai-compatible` provider the LLM adapter already supports.

The current `docker-compose.yml` has no LLM service at all; M1-379 establishes
the `prod` app services in the same file, so this ticket is sequenced after it
to avoid colliding compose edits.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter — model pulls/downloads belong to the wizard (M1-383).

## Notes

- A service may carry multiple profiles; it starts if ANY enabled profile
  matches. That is how ollama joins both `dev` and the prod-time `ollama`
  selection without a second service block.
- Pin both images to explicit tags (M1-004 precedent).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-380-*.md
```
