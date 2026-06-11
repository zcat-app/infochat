---
id: M1-296
title: "LLM config/routing: deterministic order, typo fail-fast, base-url validation, structure"
status: done
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 14
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Task routing semantics and the provider SPI — only ordering, validation, and code structure change.
  - The assertAllTasksResolve forces-both-services-carry-every-task-config issue (cross-lens observation; backlogged).
  - LlmHttpSupport's logging surface (M1-292).
acceptance:
  - "U-26: LlmRouter.buildFromCdi sorts entries by provider name at construction (today CDI discovery order decides the language-tie first-match and the entries.get(0) fallback at :207-209), so the same config routes identically across services and restarts; a named test registers providers in two different orders and asserts identical routing."
  - "U-27: an explicitly configured but unknown infochat.llm.default.provider fails assertAllTasksResolve at startup (today: WARN + arbitrary first-entry fallback for the JVM lifetime — a typo silently reroutes SECURITY_JUDGE et al.); the implicit-default path (key absent) keeps the current WARN+fallback for test fixtures; named tests pin both branches (the per-task-override typo already fails boot — keep that test green)."
  - "U-28: a requireHttpBaseUrl config-boundary check (URI parses, scheme http/https, host present) runs at startup for every resolved provider config including the embedding provider (today configFor resolves strings only and URI.create throws IllegalArgumentException per-call at AnthropicProvider:163 / OpenAiCompatibleProvider:187 / OpenAiCompatibleEmbeddingProvider:140, where workers' broad catches absorb it as a permanent 'transient' outage); a named test boots with a malformed base-url and asserts startup failure naming the property."
  - "U-52: LlmCallFailedException becomes a top-level type in impl/ (today nested inside OpenAiCompatibleProvider while AnthropicProvider imports its sibling's inner type and LlmHttpSupport references it); all references updated."
  - "U-53: the three identical private static final ObjectMapper JSON fields in the impl package collapse to one shared package-private constant."
  - "U-72 rider: LlmRouter.Entry's null-tolerant constructor exists only to serve its own pin test (fable-5/04#F4); it is removed or made honest (constructor enforces the real contract; the pin test updates)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-11
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 452
      removed: 79
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-11
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-296: LLM config/routing: deterministic order, typo fail-fast, base-url validation, structure

## Context

Deep-review v5 verified **U-26** (MEDIUM), **U-27** (MEDIUM, 3-model
agreement), **U-28** (MEDIUM), **U-52**, **U-53**, plus the Entry-ctor rider
(`deep-code-review/v5/UNIFIED-REPORT.md` §3/§4; sources `fable-5/04#F1/#F2/#F3`,
`opus-47/04#F1/#F2/#F3`, `gpt-55#M-06/#M-07/#M-08`, `mimo/4#F1` —
gitignored; all load-bearing facts inlined; anchors verified 2026-06-11:
buildFromCdi at LlmRouter:298 with no sort; entries.get(0) at :207-209;
URI.create call sites as listed in acceptance).

Theme: config mistakes that should die at startup instead degrade routing
silently for the JVM lifetime, and the impl package carries structural debt
(nested shared exception, triplicated mappers) that every new provider
copies.

## Acceptance

See frontmatter. U-27's two branches matter: explicit-and-unknown fails,
absent-and-defaulted warns — the fixture path that relies on the implicit
default must keep working.

## Out-of-scope

See frontmatter.

## Notes

- Deterministic routing is a project pillar ("Deterministic SQL retrieval;
  LLM only for…") — U-26's sort is one line; the test is the value.
- Coordination: M1-292 edits LlmHttpSupport (logging); this ticket touches
  it only if the U-52 exception move updates its import. Check worktrees at
  start.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-296-*.md
```
