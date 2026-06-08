---
id: M1-226
title: "Clamp source-claimed published_at to fetched_at at the ingest boundary"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterIT.java
  - docs/spec/schema.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Nostr's existing inline clamp in NostrEvent (createdAtInstant.isAfter(fetchedAt) ? fetchedAt : createdAtInstant) — kept as harmless redundancy once the persistence-boundary clamp double-covers it; this ticket does NOT remove it.
  - Offset-less / unparseable source timestamps that yield a null published_at and so become invisible to searchPosts' window filter — a separate gap that gets its own ticket; this ticket only clamps future-dated NON-null values.
  - SearchPostsTool's window/ordering binding to published_at — settled by M1-219; this ticket is ingest-side only and does not touch the provider query.
  - Backfilling or repairing already-persisted future-dated post rows — this clamps new ingests only; no migration, no data-repair job.
acceptance:
  - "PostPersister clamps the source-claimed publish time at the persistence boundary: in the INSERT bind path (currently `Instant publishedAt = normalized.publishedAt(); ps.setTimestamp(8, publishedAt == null ? null : Timestamp.from(publishedAt));`), when `publishedAt` is non-null AND `publishedAt.isAfter(normalized.fetchedAt())`, the value bound to the `published_at` column is `normalized.fetchedAt()` instead; a non-future `publishedAt` is bound unchanged; a null `publishedAt` is bound as SQL NULL unchanged."
  - "A named PostPersisterIT test persists a NormalizedPost whose publishedAt is strictly after its fetchedAt (e.g. fetchedAt + 48h) and asserts the persisted row's published_at column equals fetched_at (the future claim was clamped), not the original future instant."
  - "A named PostPersisterIT test persists a NormalizedPost whose publishedAt is before fetchedAt and asserts the persisted published_at is stored unchanged; and a NormalizedPost whose publishedAt is null is persisted and the row's published_at column is NULL."
  - "docs/spec/schema.md §Posts and derivatives records the invariant that published_at is clamped to <= fetched_at at the ingest boundary (a source cannot claim a future publish time), with a one-line cross-reference to the searchPosts ordering rationale in security.md §Prompt-injection defenses."
  - "mvn -B clean verify from the repo root exits 0; all tests currently green on main stay green."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/security.md §Prompt-injection defenses
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-226: Clamp source-claimed published_at to fetched_at at ingest

## Context

`published_at` is source-controlled: RSS `<pubDate>`, Atom `<published>`,
and Bluesky `indexedAt` are parsed (honouring their offset) into an
absolute `Instant`, but nothing bounds that instant to the present. A
source can claim a *future* publish time, and because `SearchPostsTool`
filters `WHERE published_at >= ?` and orders `ORDER BY published_at DESC`
(the binding M1-219 pinned in the spec), a future-dated post is always
inside every window and sorts to the very top of every `searchPosts`
result fed to the chat LLM — a source-controlled ordering-manipulation
lever in an LLM tool surface (security.md §Prompt-injection defenses).
A far-future value also pushes the per-source reconnect cursor
`MAX(published_at)` past now.

Nostr already defends against exactly this: `NostrEvent` clamps
`published_at` to `LEAST(created_at, fetched_at)` inline
(`NostrEvent.java`, "one future-dated event would otherwise push the
per-source reconnect cursor past now"). RSS and Bluesky have no
equivalent clamp, so the protection is asymmetric across sources.

This ticket closes the gap by centralising the clamp at the persistence
boundary (`PostPersister`), where every source's `published_at` has
already been normalised to a uniform `Instant` — so the clamp is a
format-agnostic `Instant`-vs-`Instant` comparison and covers RSS,
Bluesky, Nostr, and any future fetcher by construction.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: follow-up split out of M1-219 (searchPosts window/ordering
  decision). M1-219 deliberately bound the window/ordering to
  published_at and pinned today's implementation with NO code change;
  this future-date hardening is the separable, ingest-side remainder
  flagged in M1-219's commit message ("a published_at <= now() clamp …
  left to a separate follow-up ticket").
- Clamp site decision: centralise in `PostPersister` (one place, every
  source covered) rather than per-parser. Nostr's inline clamp then
  becomes a harmless double-clamp (the second comparison is a no-op);
  it is intentionally left in place — see out_of_scope.
- Alternatives considered: reject/quarantine a future-dated post instead
  of clamping its timestamp — rejected to match Nostr's existing clamp
  precedent and to avoid dropping otherwise-valid content over a single
  bad metadata field.
- The clamp uses `normalized.fetchedAt()` (the wall-clock receipt time),
  which the NormalizedPost SPI contract declares non-null, so the
  comparison is total and needs no null-guard on the fetched side.
