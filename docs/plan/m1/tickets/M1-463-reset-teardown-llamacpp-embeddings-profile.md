---
id: M1-463
title: "setup.sh --reset: also tear down the llamacpp-embeddings service (its own compose profile is untouched by the current --profile prod/ollama/llamacpp down, leaving the container holding infochat_default open)"
status: pending
created: 2026-06-26
last_updated: 2026-06-26
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
  - "The step-loop / state-file resume logic — unchanged; only do_reset's compose down scope changes (same boundary M1-395 drew)."
  - "Adding the `dev` profile to the reset. `dev` gates the quarkus:dev path (ollama is `[dev, ollama]`), not the containerized prod wizard do_reset tears down; the wizard never starts dev-profile services, so it stays excluded."
  - "Any change to the unconditional `Also drop data volumes (-v)?` prompt or its default — the prompt firing every reset is by design; the bug is the incomplete teardown above it, not the prompt."
acceptance:
  - "do_reset's `docker compose down` and the optional `down -v` BOTH include `--profile llamacpp-embeddings` alongside the existing `--profile prod --profile ollama --profile llamacpp`, so a reset stops the pure-llama.cpp embeddings service (`profiles: [llamacpp-embeddings]`, docker-compose.yml) too. After a reset, no infochat compose container remains attached to `infochat_default`, so the network teardown no longer reports `Resource is still in use`, and the `-v` drop can remove the shared `infochat-llamacpp-models` volume (which the surviving embeddings container previously pinned)."
  - "Both echoed command-preview lines (the `echo \"+ docker compose ... down\"` and `... down -v\"`) are updated to match the real invocation, so the printed command stays a faithful copy of what runs."
  - "The do_reset comment that enumerates the LLM profiles a bare `--profile prod down` misses (currently naming only `[dev, ollama]` and `[llamacpp]`, M1-395) is extended to name `[llamacpp-embeddings]` as the M1-417 pure-llama.cpp embeddings shape (D49), so the next reader sees why all four profiles are listed."
  - "prod/setup.sh passes `bash -n`."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
decision_refs:
  - D49
---

# M1-463: --reset must also tear down the llamacpp-embeddings service

## Context

Observed at source 2026-06-26. After `./prod/setup.sh --reset`, the
`docker compose down` reports:

```
[+] down 1/1
 ! Network infochat_default Resource is still in use
```

and the only container left attached to `infochat_default` is
`infochat-llamacpp-embeddings-1` (`Up`, healthy).

`do_reset` (prod/setup.sh:113) runs:

```
docker compose ... --profile prod --profile ollama --profile llamacpp down
```

M1-395 widened this list from `--profile prod` to also cover the `ollama` and
`llamacpp` backend profiles. But the pure-llama.cpp embeddings shape added later
by M1-417 (D49 — generative llama.cpp + a second llama.cpp instance in
`--embeddings` mode) declares its service under its **own** profile,
`profiles: [llamacpp-embeddings]` (docker-compose.yml), NOT under `llamacpp`.
`docker compose down` only acts on services whose profile is active (plus
profile-less ones like `postgres`), so the `llamacpp-embeddings` container is
never selected: it survives the reset, holds `infochat_default` open (hence the
"Resource is still in use" warning), and pins the shared
`infochat-llamacpp-models` volume so even an operator who answers **y** to the
`-v` prompt cannot reach a clean slate.

This is the identical bug class M1-395 fixed; the embeddings profile was simply
not yet in the tree when M1-395 landed (M1-417 came after), so the reset's
profile list was never updated to match.

The fix is the same shape: add `--profile llamacpp-embeddings` to both the
`down` and the `down -v` invocations (and their echoed previews), and extend the
do_reset comment that lists the missed LLM profiles to name it.

## Acceptance / Out-of-scope

See frontmatter. Note the explicit out-of-scope: the `Also drop data volumes`
prompt firing on every reset is by design — the reported "strange behavior" is
the incomplete teardown above it, not the prompt.

## Notes

- Verify the profile name against the live compose file at implementation time
  (`grep -n 'profiles' docker-compose.yml`): the service must be the one whose
  profile is `[llamacpp-embeddings]`, distinct from the generative `[llamacpp]`.
- No automated test: the wizard subscripts have no shell test harness (cf.
  M1-395, also `bash -n`-verified only). Confirm the running
  `infochat-llamacpp-embeddings-1` container is gone after a reset as the manual
  check.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-463-*.md
```
