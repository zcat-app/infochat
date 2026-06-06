---
id: M1-176
title: "Clamp Nostr created_at to now() before it becomes published_at"
status: pending
created: 2026-06-06
last_updated: 2026-06-06
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
remediates: M1-152
out_of_scope:
  - the idx_post_source_published index and the since-cursor query themselves (landed by M1-152; this ticket fixes the input, not the read)
  - any change to PostPersister's INSERT shape (the clamp belongs upstream in NostrEvent's normalization, not at persist)
  - SimpleX / RSS / Bluesky published_at handling (only the Nostr created_at→published_at mapping is attacker-controlled in the way the advisory describes)
acceptance:
  - "NostrEvent's created_at→published_at mapping (NostrEvent.java:85, `Instant.ofEpochSecond(createdAt)`) clamps the result to the lesser of the event timestamp and wall-clock now (LEAST(created_at, now()) semantics), so a future-dated event cannot set published_at beyond now"
  - "A NostrEvent unit test asserts a far-future created_at produces a published_at no greater than now (the clamp), while a normal past created_at is preserved unchanged"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Failure handling
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-176: Clamp Nostr created_at to now() before it becomes published_at

## Context

M1-152's red-team audit raised an OUT-OF-MODEL advisory (verdict file
`docs/plan/m1/redteam/M1-152-2026-06-06.md`). M1-152 added
`idx_post_source_published ON post(source_id, published_at DESC)` to serve the
Nostr reconnect since-cursor query
`SELECT MAX(published_at) FROM post WHERE source_id = ?`
(`NostrStreamSource.java:461`). For Nostr sources `published_at` is the
attacker-controlled event `created_at`, mapped verbatim with no wall-clock
clamp at `NostrEvent.java:85` (`Instant.ofEpochSecond(createdAt)`) and persisted
at `PostPersister.java:135`.

A single malicious relay event with `created_at` far in the future poisons
`MAX(published_at)` for that source, pushing the reconnect `since` filter past
now; the relay then replays nothing and the bot goes blind to that source — an
ingest-availability DoS. The clamp (`LEAST(created_at, now())`) belongs in the
NostrEvent normalization path, upstream of both persist and the cursor read,
not in the migration.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: M1-152 red-team OUT-OF-MODEL advisory
  (`docs/plan/m1/redteam/M1-152-2026-06-06.md`), 2026-06-06.
- The threat model makes no explicit per-source Nostr ingest-availability
  commitment today; if the implementer judges the clamp warrants a spec
  sentence in `docs/spec/security.md §Failure handling`, raise it via the
  escalate→spec-amend path rather than widening this ticket.
