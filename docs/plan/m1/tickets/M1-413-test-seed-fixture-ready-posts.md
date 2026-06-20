---
id: M1-413
title: "test: seed fixture of pre-evaluated READY posts for retrieval tests"
status: pending
created: 2026-06-20
last_updated: 2026-06-20
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/test/resources/fixtures
  - infochat-provider/src/test/java/app/zcat/infochat/provider/testing
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any src/main code — this ticket adds a TEST-scope fixture and loader only; the dev-app seeding path that reuses this fixture is M1-414's concern, not this ticket's.
  - The collector ingest pipeline — the fixture inserts already-evaluated READY rows directly; it does NOT run Stage 1/2, tagging, or embedding (that path is exercised by M1-416).
  - Flyway migrations — the fixture is data inserted into the existing schema, not a schema change.
  - TestLlmProvider — unchanged; this fixture is LLM-independent (the rows are pre-evaluated).
acceptance:
  - "A SQL fixture resource under infochat-provider/src/test/resources/fixtures creates at least one active, non-deleted source; a small controlled-vocabulary tag set; and at least three status='READY' posts carrying deterministic UIDs and Tier-1 tags (at least one post with a NULL embedding to exercise the embedding-optional retrieval path)."
  - "A package-private test helper under app.zcat.infochat.provider.testing applies the fixture to the test database from within an @QuarkusTest."
  - "A test (e.g. SeedFixtureIT) asserts that after the fixture loads, the deterministic /summary-style retrieval returns the seeded READY posts for a given (user, scope), and that seeded non-READY rows (a RAW and a QUARANTINED post) are excluded from that retrieval."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/resources/fixtures (seed SQL)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/testing (loader + SeedFixtureIT)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/verification.md §Spec-level invariants the tests must enforce
decision_refs:
  - D22
---

# M1-413: seed fixture of pre-evaluated READY posts for retrieval tests

## Context

The content-retrieval surface (`/summary`, `/follow-tag` results, `/save` →
`/saved`, digests) runs deterministic SQL over the `post` table and returns
nothing when the table is empty. Tests that want to assert retrieval behavior —
and, downstream, the dev terminal harness (M1-414) and the golden-path journey
(M1-415) — need realistic, **already-evaluated** content without paying for a
real LLM or network fetch. This ticket adds a reusable TEST-scope fixture of
`READY` posts and a loader, the foundation the other testing-tool tickets reuse.
Origin: `docs/testing/USER_TEST_PLAN.md` deliverable #2.

## Acceptance

See frontmatter. A SQL fixture inserts active sources, a tag set, and ≥3 `READY`
posts (one with a NULL embedding); a test helper applies it; a test proves the
deterministic retrieval returns the `READY` posts and excludes seeded `RAW` /
`QUARANTINED` rows. Full `mvn verify` green.

## Out-of-scope

See frontmatter. No `src/main` code, no ingest-pipeline execution, no migration.
The rows are inserted in their post-evaluation terminal state directly.

## Notes

- The fixture is the single source of seed content; M1-414 loads it into the dev
  app on startup, and M1-415 may reuse it so `/summary` returns prose. Keep UIDs
  and tags stable and documented so those tickets can assert against them.
- Deterministic UID per `schema.md` §UID derivation: `sha256(source_id || '|' ||
  upstream_identifier)`. The fixture may hard-code consistent values rather than
  recompute, since it bypasses the Fetcher boundary.
- Adjacent pattern: existing `@QuarkusTest` retrieval/command tests under
  `infochat-provider/src/test/java/app/zcat/infochat/provider/command` and the
  `app.zcat.infochat.provider.testing` package (where `TestLlmProvider` lives).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-413-*.md
```
