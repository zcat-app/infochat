---
id: M1-383
title: "wizard 6b: 3-postgres + 4-llm + 5-bootstrap subscripts"
status: done
created: 2026-06-15
last_updated: 2026-06-15
clarity_check:
  date: 2026-06-15
  verdict: WARN
  warnings:
    - "Acceptance item 2 (4-llm.sh): the clause \"(manual: each branch run reaches a usable model endpoint — commit-message evidence)\" is not a verifiable acceptance criterion — it delegates the key behavioral invariant to trust-the-commit-message rather than a diff/command check. Consider removing it or replacing with a concrete reachability check."
  blockers: []
blocked_by:
  - M1-382
  - M1-379
  - M1-380
  - M1-381
files_budget: 3
files_scope:
  - prod/scripts/3-postgres.sh
  - prod/scripts/4-llm.sh
  - prod/scripts/5-bootstrap.sh
complexity: high
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
outline_file: target/m1-tick-outline-M1-383.md
out_of_scope:
  - The orchestrator and steps 0-2 (M1-382), the adapter step (M1-384), the apps-up/verify steps (M1-385).
  - Declaring compose services — the postgres/ollama/llamacpp services already exist (M1-378/379/380); these subscripts only start them and provision models/data.
acceptance:
  - "3-postgres.sh runs `docker compose --profile prod up -d postgres` and waits until the container is healthy; re-running is idempotent (no error when already up)."
  - "4-llm.sh branches on the operator's backend choice: ollama → starts the ollama service (`--profile prod --profile ollama`) and `ollama pull`s the active profile's chat/security/embedding models; llamacpp → starts the llamacpp service (`--profile prod --profile llamacpp`) and fetches the configured GGUF; remote → collects base-url + API key only; in every branch it writes infochat.llm.* and infochat.embeddings.* into the runtime config (manual: each branch run reaches a usable model endpoint — commit-message evidence)."
  - "5-bootstrap.sh copies prod/config/bootstrap-sources.json into the runtime directory if none is present and optionally enables a bootstrap-assets.json; it does not overwrite an existing runtime sources file."
  - "All three obey the §7.7.1 shape (set -euo pipefail, echo, exit-code passthrough, -h/--help) and pass `bash -n`. mvn -B verify from the repo root exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/design/07-deployment.md §7.6.1
decision_refs:
  - D27
  - D38
reviews:
  - round: 1
    date: 2026-06-15
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 344
      removed: 10
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-383: wizard 6b — postgres + llm + bootstrap steps

## Context

Middle slice of the wizard (`07-deployment.md` §7.7.2 steps 3–5). Brings up
Postgres via the prod profile, provisions the chosen LLM backend (Ollama model
pulls / llama.cpp GGUF fetch / remote API keys), and seeds the runtime
bootstrap-sources file from the committed template.

Blocked on the orchestrator + state machine (M1-382) and on the compose
services and template these subscripts drive (M1-379 apps, M1-380 LLM services,
M1-381 sources template).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- The model list per profile comes from `05-llm-and-embeddings.md` §5.7; read
  the active profile recorded by 1-profile.sh rather than hard-coding one.
- Idempotency matters: re-running after a partial setup must not re-pull models
  already present or clobber an existing runtime sources file.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-383-*.md
```
