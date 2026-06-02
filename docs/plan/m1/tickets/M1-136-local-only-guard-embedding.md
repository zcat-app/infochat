---
id: M1-136
title: "local-only startup guard covers embedding endpoint + remote-embedding log"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 4
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the LlmRouter instanceof decoupling (covered by M1-141)
  - moving the guard to a Provider-side hook beyond a spec-reconciliation note
acceptance:
  - "When infochat.llm.local-only=true the guard scans infochat.embeddings.base-url and provider-name override keys (not only per-task base-urls), and fails startup if any points off-host"
  - "A test with local-only=true and a remote embedding base-url asserts startup fails"
  - "Switching the embedding provider to a remote service emits the spec-promised explicit confirmation log line on startup"
  - "The Collector-vs-Provider placement is reconciled with docs/spec/llm.md (a comment/spec note clarifying embedding generation runs in the collector ingest pipeline)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/llm.md §Embedding pipeline
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-136: local-only startup guard covers embedding endpoint + remote-embedding log

## Context

`LlmRouterStartupGuard` scans per-task base-urls when
`infochat.llm.local-only=true`, but not `infochat.embeddings.base-url` or the
provider-name override keys. A local-only deployment with a remote embedding
endpoint silently ships post title+summary off-host with no startup failure —
the local-only commitment is the privacy invariant the spec sells operators. The
promised "explicit confirmation log line on startup" for a remote-embedding
switch is also missing. deepseek adds: the guard runs on Collector startup while
the spec text says Provider — reconcile (embedding runs in the collector ingest
pipeline, so placement matches code; the spec wording needs the note).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A12 (LOCAL-ONLY-GUARD, High);
  `opus-47-full-handout.md` §F-SEC-07; `opus-47-only-handout.md` §obs.1.
- Loci: `LlmRouterStartupGuard.java:96-103,183-204`; spec `docs/spec/llm.md:132-134`.
- Default profiles point embedding at loopback Ollama, so the gap is dormant
  until an operator sets a remote base-url.
