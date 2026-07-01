---
id: M1-537
title: "live-test: seed a deterministic synthetic post corpus into a running deployment's database"
status: draft
created: 2026-07-01
last_updated: 2026-07-01
blocked_by:
  - M1-536
files_budget: 4
files_scope:
  - prod/live-seed.sh
  - prod/sql/seed-synthetic-corpus.sql
  - docs/testing/USER_TEST_PLAN.md
complexity: medium
risk: low
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The RAW/pre-eval malicious-injection path (posts that flow through the real
    Stage-1/Stage-2 + real LLM pipeline for quarantine assertions). That needs
    the running eval pipeline and the adversarial-input-kit and is a distinct
    follow-up (see Notes); this ticket seeds ALREADY-EVALUATED terminal-state
    rows only.
  - >-
    The control-plane reset — M1-536. This ticket assumes a reset has run (or a
    fresh DB) and only adds content rows; the two compose in the live loop.
  - >-
    Re-implementing the M1-413 fixture. Reuse seed-ready-posts.sql's row shapes
    and deterministic UIDs where practical; this ticket's job is making an
    equivalent corpus loadable into a RUNNING deployment DB (not @QuarkusTest),
    with live-controllable timestamps.
  - >-
    A movable application Clock. Time-window behaviour is exercised by setting the
    seeded rows' published_at/ready_at relative to real now, not by mocking the
    prod Clock (which is hardcoded Clock.systemUTC()).
  - "Flyway / schema changes — data inserts into the existing schema only."
  - >-
    The InMemory dev terminal harness (M1-414) and its file-driven input — the
    seed is DB content, independent of how inbound is driven.
acceptance:
  - >-
    A prod-side script (prod/live-seed.sh) loads a synthetic corpus into a running
    deployment's database: at least one active non-deleted source, a small
    controlled-vocabulary tag set, and >=3 status='READY' posts with deterministic
    UIDs and Tier-1 tags (at least one with a NULL embedding to exercise the
    embedding-optional retrieval path), mirroring the M1-413 fixture shape.
  - >-
    Seeded posts' published_at (and ready_at) are set relative to real now so they
    fall inside a /summary -w 24h window at load time; the offset is a script
    parameter (default: within the last hour) so a tester can place rows in or
    out of a given window deterministically without touching the app clock.
  - >-
    The loader is idempotent: re-running upserts by (source (kind,identifier)) and
    by post uid so a second run neither duplicates rows nor errors; it exits 0 on
    a repeat run.
  - >-
    After load, a documented check (query or a /summary via the harness) shows the
    seeded READY posts are returned for a subscribed (user, scope) and that any
    seeded non-READY control rows (a RAW and a QUARANTINED post) are excluded from
    retrieval — proving the corpus is wired into the deterministic SQL path.
  - >-
    Runs under the schema-owner / superuser DB role (post/source/tag inserts are
    collector-owned tables; the provider role cannot INSERT them), obtained the
    same way setup.sh reaches Postgres.
  - "prod/live-seed.sh passes `bash -n`; the SQL is valid against the current schema."
  - >-
    USER_TEST_PLAN.md's existing "load the M1-413 seed fixture out of band" note
    is updated to point at this first-class live-seed script.
test_plan:
  adds:
    - prod/live-seed.sh (the seed entry point + post-load verification query)
    - prod/sql/seed-synthetic-corpus.sql (the corpus, upsert-shaped, parameterized timestamps)
  modifies:
    - docs/testing/USER_TEST_PLAN.md (replace the out-of-band note with the script)
  preserves:
    - all tests currently green on main (prod-side tooling; no src/main, no test change)
spec_refs:
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/verification.md §Spec-level invariants the tests must enforce
  - docs/testing/USER_TEST_PLAN.md §Phase 3 — Usage
decision_refs:
  - D22
---

# M1-537: seed a deterministic synthetic post corpus into a running deployment

## Context

A freshly-set-up (or freshly-reset, M1-536) deployment has **no posts**, so the
content commands (`/summary`, `/follow-tag`, `/save`→`/saved`, digests) return
empty — the "data problem" already documented in `USER_TEST_PLAN.md` (Phase 1 &
Phase 3). Today the workaround is to load the M1-413 test fixture
(`seed-ready-posts.sql`) "into your dev database out of band." For repeatable
live-transport runs we want that as a first-class, idempotent, timestamp-aware
loader against a *running* deployment (not a `@QuarkusTest`).

This is the deterministic "future"/synthetic half of the live-e2e data strategy
(`docs/plan/live-e2e/README.md` §3): predictable content for stable assertions,
seeded directly at the READY terminal state, with `published_at`/`ready_at` set
relative to real now so time-window behaviour is controlled by the DATA, not by
a movable clock (prod `Clock` is hardcoded `Clock.systemUTC()`). The real
"now" corpus (once-fetched real posts) is preserved in place by M1-536 and needs
no seeding.

## Acceptance

See frontmatter. An idempotent, owner-role prod script loads an M1-413-shaped
READY corpus (plus a RAW and a QUARANTINED control row) with parameterized,
window-relative timestamps into a running deployment DB, and a documented check
proves the READY rows are retrievable and the non-READY rows are excluded.

## Out-of-scope

See frontmatter. Not the RAW-stage real-pipeline malicious path, not the reset
(M1-536), not a new fixture design (reuse M1-413 shapes), not a movable clock,
not schema changes.

## Notes

- **RAW-stage / malicious-detection follow-up.** Verifying that a hostile post is
  QUARANTINED by the *real* Stage-1/Stage-2 + real LLM requires injecting the
  post at the pre-eval (RAW) stage so the running eval pipeline processes it,
  fed from `docs/testing/adversarial-input-kit.md`. That is a separate ticket
  (propose M1-538) because it depends on the live eval pipeline and real LLM,
  not just a direct row insert. This ticket deliberately seeds terminal-state
  rows only.
- **Reuse, don't rebuild.** `infochat-provider/src/test/resources/fixtures/
  seed-ready-posts.sql` (M1-413) is the canonical row shape and deterministic
  UID scheme (`sha256(source_id || '|' || upstream_identifier)`); derive the
  prod corpus from it rather than inventing new content, so the golden-path
  assertions and this live seed stay consistent.
- Owner-role requirement mirrors M1-163's finding: `post`/`source`/`tag` are
  collector-owned; the provider role has SELECT-only on them.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-537-*.md
```
