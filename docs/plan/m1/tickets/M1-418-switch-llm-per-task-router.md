---
id: M1-418
title: Per-task LLM backend switcher (remote/ollama/llamacpp)
status: pending
created: 2026-06-21
last_updated: 2026-06-21
blocked_by: [M1-417]
files_budget: 6
files_scope:
  - prod/switch-llm.sh
  - SETUP_GUIDE.md
  - docs/spec/security.md
  - prod/scripts/**
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  # The llama.cpp backend wiring is M1-417 (this ticket's blocker); do NOT change
  # compose or the wizard's backend provisioning here. This is a standalone
  # POST-setup operator tool, not a wizard step.
  - docker-compose.yml
  - prod/scripts/4-llm.sh
  - prod/setup.sh
  # No app-code changes; per-task config is already independent (the %remote-llm
  # profile is itself a mix), so routing is pure config generation.
  - infochat-collector/src/main/java/**
  - infochat-provider/src/main/java/**
  - infochat-llm-adapter/src/main/java/**
  # Embeddings routing is LOCKED (768-dim nomic, allow-model-change=false): the
  # switcher never offers to change infochat.embeddings.*.
acceptance:
  - The `prod/switch-llm.sh` tool prompts, per generative task
    (`security tagger entity summarizer chat translator`), for a backend —
    `remote | ollama | llamacpp`, default = the task's CURRENT value (read from
    `prod/runtime/application.properties`, classified by base-url). It never
    prompts for embeddings.
  - Running it and accepting every default (all-Enter) is a no-op — the resulting
    `application.properties` is byte-identical to the input (empty diff).
  - Routing a task to `remote` writes `infochat.llm.<task>.base-url=<url>` and
    `infochat.llm.<task>.api-key=${INFOCHAT_LLM_API_KEY}`, prompts for the model,
    and records the key in `secrets.env` dotenv-escaped (reusing an existing
    `INFOCHAT_LLM_API_KEY` if present); routing to `ollama`/`llamacpp` points the
    task's base-url at that service and clears the api-key line.
  - The `infochat.embeddings.*` block is byte-identical before/after every run.
  - Before writing, the script backs up `application.properties` + `secrets.env`
    to timestamped copies and prints the rollback command.
  - The script prints a DYNAMIC privacy disclosure naming exactly which tasks are
    now remote and what each exposes — `chat` flagged loudest (private user
    messages), ingest tasks (`security`/`tagger`/`entity`) as topic-interest
    exposure; if no task is remote, no warning.
  - The script prints the recreate command
    (`docker compose --env-file ... --profile prod up -d collector provider`,
    NOT `restart`) and ensures the embeddings backend + any llamacpp/ollama
    service a routed task needs is up.
  - The `SETUP_GUIDE.md` gains a "Switching your AI backend later" section with
    the command, a sample session, the printed privacy disclosure, and rollback
    plus two worked examples (generative-only remote; Pi all-but-embeddings).
  - The `docs/spec/security.md` gains one cross-reference line to the privacy
    disclosure (the per-task exposure statement).
test_plan:
  adds:
    # - prod/scripts/test/switch-llm.bats or equiv (approach TBD by plan-writer)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/deployment.md §Operator inputs
decision_refs:
  # - carries the locked-embeddings (768-dim) + privacy-disclosure decisions

reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-418: Per-task LLM backend switcher (remote/ollama/llamacpp)

## Context

Operators need to re-route the LLM after initial setup — most commonly to a
remote API on a low-power host (e.g. a Raspberry Pi), but also back to local or
to any mix. The wizard's `4-llm.sh` configures a backend at install time but
offers no post-setup re-route (re-running resumes past the done step), so this
is a dedicated maintenance tool. Per-task routing is already a first-class
design: each `infochat.llm.<task>.*` family is independent and the shipped
`%remote-llm` profile is itself a mix, so routing is pure config generation — no
app-code change. Embeddings is LOCKED local (768-dim nomic,
`allow-model-change=false`): changing it would corrupt pgvector retrieval, so the
switcher never touches it. This ticket depends on M1-417, which makes the
`llamacpp` backend actually serve — without it, routing a task to `llamacpp`
would point at a non-functional endpoint.

## Acceptance

The behavioral contract (mirrors the YAML `acceptance:`):

- Per generative task, prompt `remote | ollama | llamacpp`, default = current
  value read from runtime config; embeddings never prompted.
- All-Enter is a byte-identical no-op (empty `application.properties` diff).
- `remote` writes base-url + `${INFOCHAT_LLM_API_KEY}` + a prompted model and
  records the key dotenv-escaped in `secrets.env`; `ollama`/`llamacpp` set the
  base-url at that service and clear the api-key.
- `infochat.embeddings.*` is byte-identical before/after every run.
- Timestamped backup of `application.properties` + `secrets.env` with a printed
  rollback command before any write.
- A DYNAMIC privacy disclosure names exactly the now-remote tasks and their
  exposure (`chat` loudest; ingest tasks = topic interests); none if nothing is
  remote.
- Prints the `up -d` recreate command (not `restart`) and ensures the needed
  backend services are up.
- `SETUP_GUIDE.md` "Switching your AI backend later" section with command,
  sample session, the disclosure, rollback, and two worked examples.
- One `docs/spec/security.md` cross-reference line to the disclosure.

## Out-of-scope

The llama.cpp backend wiring lives in M1-417 (the blocker) — do not change
`docker-compose.yml`, `4-llm.sh`, or `setup.sh`. This is a standalone post-setup
tool, not a wizard step. No app-code changes (per-task config is already
independent). The switcher MUST NOT offer to change `infochat.embeddings.*`
(locked: 768-dim nomic). It reuses the wizard's secret-handling conventions
(`dotenv_escape`, `secrets.env`, `INFOCHAT_LLM_API_KEY`) rather than inventing
new ones.

## Notes

- **Why `up -d`, not `restart`:** the API key reaches the container via
  `--env-file secrets.env` at container-CREATE time; `docker compose restart`
  reuses the old environment, so a new key needs a recreate. The mounted
  `application.properties` is re-read on recreate (no rebuild — it's a runtime
  bind-mount per M1-386).
- **Privacy framing (verified against the code):** the ingest tasks
  (`security`/`tagger`/`entity`, collector Stage-2 over fetched PUBLIC posts)
  expose *topic interests / source list*, not private user data; `chat` (provider,
  private user messages) is the genuinely sensitive one. The disclosure text must
  reflect this, not a blanket "privacy sacrificed" line.
- **`security_relevant`:** the script writes `secrets.env` (the API key) and the
  privacy disclosure must be CORRECT — a wrong exposure claim is a security
  defect. Warrants `/redteam`.
- **Plan-writer (complexity:high):** settle the test approach for a shell tool
  (scripted-input run + diff assertions on generated config is the mechanically
  checkable path; check whether the repo already has a prod-script test harness).
- Adjacent patterns: `4-llm.sh` `set_prop` / `dotenv_escape` / the
  `INFOCHAT_LLM_API_KEY` reuse logic (lines 64-96, 231-265) are the conventions
  to mirror.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-418-*.md
```
