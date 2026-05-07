# Data model

This file describes the entities, the relationships between them, and the
invariants the schema is required to uphold. Concrete DDL — column types,
indices, partitioning strategy, triggers, denormalized counters — lives in
`docs/design/02-schema.md`.

The two services share one schema; they differ only in the DB role they
connect with (see decision D34 and `security.md`).

## Entities

### Identity and access

- **User.** A person, identified by the messaging adapter's cryptographic
  contact ID. Carries the bot-admin flag, the ban flag (with reason and
  audit), and informational metadata (display name, last-seen, save count).
- **Group.** A messaging-adapter group the bot is a member of. Has a
  per-group timezone for digest scheduling.
- **Group membership.** The (user, group) join, with a per-group admin flag.
  Schema must enforce *at most one* group admin per group at any time
  (decision D9).
- **Audit log.** Append-only record of every privileged action and
  bootstrap. Indexed for time-range and per-actor lookup.

### Sources and tags

- **Source.** A globally-unique feed, keyed by (fetcher type, URL). Carries
  category, bootstrap tags (the tagger's deterministic fallback per decision
  D22), status, and soft-delete state. Hard delete is forbidden in v1 so
  saved-post references always resolve.
- **Source subscription.** A (scope, source) link. DM scope is per user;
  group scope is shared.
- **Tag.** A row in the controlled vocabulary (Tier 1, decision D5). Seeded
  by the bootstrap loader and extended by `/add-source --tags`.
- **Scope tag.** Per-scope follow / unfollow preference for digest content.

### Posts and derivatives

- **Post.** The fetched, sanitized, evaluated unit. Carries status (`RAW`,
  `READY`, `QUARANTINED`), Stage-1 / Stage-2 / tagger / embedding outcome
  flags, body, title, URL, timestamps, and Tier-1 tags.
- **Post entity.** Named entities extracted from a post; used for Tier-2
  cross-source linking.
- **Post embedding.** Vector for a post; profile-driven index type
  (decision D27).
- **Post reference.** Edges in the cross-source link graph
  (`link_type`, `score`). TTL'd by partition drop (decision D33).
- **Quarantine.** One row per Stage-1 or Stage-2 hit, holding span offsets,
  the verbatim original, and review status. Original content is reachable
  only by the admin DB role (decision D34).

### Per-scope state

- **Scope preferences.** Per-(scope) language, subscription versions
  (counters used to invalidate cached digests on subscription changes),
  digest-related preferences.
- **Saved post.** Per-user library entries with personal tags. Snapshotted
  so retention TTL on the underlying post does not break the bookmark
  (decisions D13, D33).
- **Chat memory.** Per-(user, scope) compressed memory entries created by
  `/compress` and consumed by the chat agent's recall path. Subject to a
  fixed TTL (decision D37); `/save`d posts are independent and not
  affected.
- **Chat session / context window.** Per-(user, scope) live context state.
  `/clear` wipes only this; chat memory is independent (decision D25).

### Operational

- **Provider state.** Singleton(s) holding catch-up high-water marks for the
  `LISTEN/NOTIFY` reconciler (see `architecture.md`).
- **Summary cache.** Pre-generated periodic-digest output keyed by group,
  slot, and subscription versions, with a short TTL (decision D17).
- **Admin notification state.** Backing store for the throttled admin
  notifier (decision D22).

## Invariants

These are non-negotiable; the schema, triggers, and queries must enforce
them together. They are tested in CI (see `verification.md`).

1. **Per-(user, scope) isolation.** Every row that holds user state carries a
   scope discriminator (`'dm'` or `'group'`) and a scope id (or equivalent
   FKs). Every query against user state filters on both.
2. **Last-admin protection.** It is impossible to leave the system with zero
   bot admins. Enforced at the trigger layer, not just the command layer.
3. **At most one group admin per group.** Enforced by a partial unique index
   so the "first @mention wins" auto-promote path is race-safe (decision D9).
4. **Soft-delete only for sources.** `source` is never hard-deleted; FKs from
   `post` and `saved_post` rely on this.
5. **Outbox.** Posts are persisted before they are enqueued for evaluation.
   A startup rehydrator picks up any post left in `RAW` (or an intermediate
   evaluating state) after a crash.
6. **TTL by partitioning.** `post_reference`, `post_embedding`, and similar
   bulk-derived rows are partitioned and aged out by partition drop, not row
   delete.
7. **Audit-before-effect.** Privileged actions write to `audit_log` *before*
   their side effects, so an interrupted command leaves a record of intent.
8. **No LLM-writable rows.** Tables that influence authorization
   (`users.is_admin`, `users.is_banned`, `group_membership.is_group_admin`)
   are not reachable from any LLM tool surface. Enforced at the SPI boundary
   (see `security.md`).
9. **Chat-memory TTL.** `chat_memory` rows carry a fixed retention horizon
   (value in design notes) after which they are removed by a scheduled
   pruner. `/save`d posts are stored separately (decision D13) and are not
   affected. `/forget` (decision D37) is a user-initiated immediate purge
   of the caller's `(user, scope)` chat memory and saved-list and is
   audit-logged like any other privileged action against user state.

## What lives in design notes

- Every column type, default, and index
- Trigger bodies and partial-index predicates
- Partition cadence and pruner schedule
- The exact `chat_memory` TTL value and pruner cadence
- Denormalized counters and the triggers that maintain them
- Profile-specific vector index choices and their build parameters
- Migration-file layout

If a question is "what column type is `body`?" the answer is in
`docs/design/02-schema.md`. If a question is "do post bodies survive a
source soft-delete?" — that's an invariant and lives here.
