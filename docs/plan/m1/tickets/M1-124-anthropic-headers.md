---
id: M1-124
title: "Anthropic header names + test alignment + narrow catch + unused import"
status: done
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
reviews:
  - round: 1
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 22
      removed: 21
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-02
    verdict: CLEAN
    base: main
    head: cd2ced7
    verdict_file: docs/plan/m1/redteam/M1-124-2026-06-02.md
    out_of_model_count: 1
    note: |
      Clean audit of the committed branch tip (cd2ced7). Four-part
      correctness change; no threat-model commitment unfulfilled, no
      auth/authz/ban/audit surface touched, no header-injection surface
      (constant + trusted-config header values). One OUT-OF-MODEL advisory:
      the pre-existing LOG.warnf at AnthropicProvider.java:159-160 logs
      remote-LLM-derived error text through the plain JBoss logger rather
      than the §Secrets-handling redactor/SafeLog — not introduced or
      regressed here (preview() caps it at 200 bytes). Candidate for a
      separate redactor-coverage ticket; not blocking merge.
clarity_check:
  date: 2026-06-02
  verdict: PASS
  warnings: []
  blockers: []
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
