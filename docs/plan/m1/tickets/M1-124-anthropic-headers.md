---
id: M1-124
title: "Anthropic header names + test alignment + narrow catch + unused import"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 4
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderTest.java
complexity: low
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - OpenAiCompatibleProvider and any other LLM provider — Anthropic only
  - the response-body size cap (covered by M1-141)
  - any routing/SPI change
acceptance:
  - "AnthropicProvider emits anthropic-version (no x- prefix) and x-api-key (with x- prefix), matching the documented Anthropic Messages API"
  - "AnthropicProviderTest asserts the corrected header names — the prior assertions that pinned the wrong names are fixed in the same diff (§8 test-integrity: code fixed to match the contract, not the test rewritten to match the bug)"
  - "extractErrorMessage catches IOException (the only legitimate JSON.readTree failure), not Exception; the parsed message branch is truncated via preview()"
  - "The unused Optional import is removed"
  - "The class javadoc header-name documentation is corrected"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/security.md §Secrets handling
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-124: Anthropic header names + test alignment + narrow catch + unused import

## Context

`AnthropicProvider` emits `x-anthropic-version` (`:139`) and `anthropic-api-key`
(`:142`); the Anthropic Messages API requires `anthropic-version` (no `x-`) and
`x-api-key` (with `x-`). Every production Anthropic call 401s, and the API key
ships in a header Anthropic discards. The test was written against the wrong
names, so it **pins the bug** — a §8 test-integrity violation that must be
corrected in the same diff. AnthropicProvider is a v1 deliverable (M1-085 /
M1-120). Bundled: the `extractErrorMessage` `catch (Exception)` should narrow to
`IOException`, and the unused `Optional` import (the file is touched here, so its
own dead import is in-scope per the surgical-changes rule).

## Acceptance

See frontmatter. Two header strings + the test assertions + the class javadoc +
the narrowed catch + the import. Confirm the Anthropic public reference hasn't
drifted again before locking the names.

## Out-of-scope

See frontmatter. The four test assertions that currently encode the wrong names
are corrected here — naming them is required (they are pre-existing-test edits
authorized by this ticket per §8).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A2 + §A2b + §C-OPTIONAL-IMPORT;
  `opus-47-full-handout.md` §F-SEC-01, F-MAINT-15, F-SIM-10; `opus-47-only-handout.md` §TP1.
- Loci: `AnthropicProvider.java:139,142`, javadoc `:50-51`, `extractErrorMessage:195-205`,
  import `:24`; test `AnthropicProviderTest.java:133-134,154-157`.
- `mimo-audit L8` (error message not truncated) folds into the `extractErrorMessage`
  fix — the fall-through already truncates via `preview(body)`; apply `preview()` to
  the parsed `message` branch too.
