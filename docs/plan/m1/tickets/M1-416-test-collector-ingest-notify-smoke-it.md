---
id: M1-416
title: "test: collector ingest + NOTIFY smoke IT"
status: pending
created: 2026-06-20
last_updated: 2026-06-20
blocked_by: []
files_budget: 5
files_scope:
  - infochat-collector/src/test/java/app/zcat/infochat/collector/smoke
  - infochat-collector/src/test/resources/fixtures
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - src/main collector code — the ingest pipeline, outbox, and NOTIFY emission already exist; this ticket adds a test that exercises them end to end, it does not modify them.
  - Production LLM providers — Stage 2 / tagger / embedding are stubbed via the test LLM provider; the smoke test does not require a real model.
  - Real network egress — the feed is a local/canned fixture served to the fetcher within the test boundary, not a live URL.
  - The existing per-stage unit/slice tests — unchanged; this is an additive end-to-end smoke test.
acceptance:
  - "An integration test under app.zcat.infochat.collector.smoke (e.g. IngestNotifySmokeIT) feeds a canned RSS fixture through the collector ingest path with the LLM stages stubbed, and asserts at least one fetched item is persisted and reaches status='READY' (outbox -> Stage 1 -> Stage 2 BENIGN -> tagger -> embedding-optional)."
  - "The test asserts the cross-service signal fires: the new_post NOTIFY is emitted for the READY post (observed via a LISTEN in the test, or via the provider_state new_post cursor advancing), proving the collector->provider event seam works."
  - "A malformed feed item lacking a usable upstream identifier is rejected at the Fetcher boundary and never produces a post row (per schema.md §UID derivation)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/smoke (IngestNotifySmokeIT)
    - infochat-collector/src/test/resources/fixtures (canned RSS feed)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/verification.md §Spec-level invariants the tests must enforce
  - docs/spec/schema.md §UID derivation
decision_refs:
  - D20
  - D38
---

# M1-416: collector ingest + NOTIFY smoke IT

## Context

The provider-side testing tools (M1-413..M1-415) all stop at the `post` table.
The *other* service — the Collector that fetches feeds, runs the eval pipeline,
and emits the `LISTEN/NOTIFY` events the Provider reacts to — has per-stage tests
but no single smoke test proving fetch → evaluate → store → NOTIFY works end to
end. This ticket adds that test, closing the collector-side gap in the user-test
plan. Origin: `docs/testing/USER_TEST_PLAN.md` deliverable #5.

## Acceptance

See frontmatter. A smoke IT feeds a canned RSS fixture through the collector with
the LLM stages stubbed, asserts a post reaches `READY`, asserts the `new_post`
NOTIFY fires (via test LISTEN or `provider_state` cursor advance), and asserts an
identifier-less item is rejected at the Fetcher boundary. Full `mvn verify` green.

## Out-of-scope

See frontmatter. No `src/main` changes, no real model, no live network. A product
defect surfaced by the smoke test is a follow-up ticket, not an inline fix.

## Notes

- The NOTIFY assertion is the load-bearing part — it is the only deliverable that
  proves the collector→provider seam (`architecture.md` §Inter-service
  communication). Prefer observing the `new_post` channel directly with a test
  LISTEN; falling back to the `provider_state` cursor advance is acceptable if a
  direct LISTEN is impractical in the test harness.
- The canned feed should include one well-formed item (drives the happy path) and
  one item lacking an upstream identifier (drives the boundary-rejection
  assertion).
- Adjacent pattern: existing collector fetcher/outbox ITs under
  `infochat-collector/src/test/java/app/zcat/infochat/collector` for the
  Testcontainers + stubbed-LLM setup this should match.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-416-*.md
```
