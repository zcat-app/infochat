---
id: M1-395
title: "setup.sh --reset: also tear down the LLM backend services (ollama/llamacpp are under their own compose profiles, untouched by --profile prod down)"
status: pending
created: 2026-06-16
last_updated: 2026-06-16
blocked_by: []
files_budget: 1
files_scope:
  - prod/setup.sh
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The step-loop / state-file resume logic — unchanged; only do_reset's compose down scope changes.
acceptance:
  - "do_reset's `docker compose down` includes the ollama and llamacpp profiles (e.g. `--profile prod --profile ollama --profile llamacpp down`), so a reset stops every service the wizard may have started, not just the prod-profile ones. The optional -v drop likewise removes the ollama / llamacpp model-cache volumes."
  - "prod/setup.sh passes `bash -n`."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-395: --reset must tear down the LLM backend services

## Context

Re-verified at source 2026-06-16. `setup.sh` do_reset runs `docker compose
--profile prod down`, but the `ollama` and `llamacpp` services are gated under
their own compose profiles (`profiles: [dev, ollama]` and `profiles:
[llamacpp]`). A `--profile prod` down does not match them, so `--reset` leaves
the LLM backend container running and its model-cache volume intact — surprising
for an operator who expects `--reset` to return to a clean slate.

## Acceptance / Out-of-scope

See frontmatter.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-395-*.md
```
