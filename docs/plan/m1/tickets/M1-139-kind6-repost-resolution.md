---
id: M1-139
title: "Kind-6 repost edge resolution"
status: done
created: 2026-06-02
last_updated: 2026-06-05
blocked_by: []
files_budget: 8
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-collector/src/test/java/app/zcat/infochat/collector
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6RepostResolutionIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6HandlerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6LinkingIT.java
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: true
out_of_scope:
  - the broader Nostr ingest pipeline beyond the repost-edge resolution path
  - changing post.id semantics for any post (Option B is rejected — see §Implementation approach)
  - GetReferencesTool and all provider-side readers — the existing p2.id = pr.to_post join works unchanged once edges resolve
acceptance:
  - "Kind6RepostResolutionIT.repostThenOriginalResolves: seed a kind-6 repost, then ingest the original kind-1 event; assert the edge's post_reference.to_post equals the original's post.id"
  - "Kind6RepostResolutionIT.originalThenRepostResolves: reverse arrival order (original ingested first, kind-6 second); same assertion"
  - "Kind6HandlerTest asserts an unresolved repost edge stores the original event id verbatim in to_upstream_identifier and NULL in to_post (replacing the deriveToPostUuid derivation pins)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6RepostResolutionIT.java
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6HandlerTest.java — re-pin the edge shape (to_upstream_identifier verbatim + NULL to_post) in place of the deriveToPostUuid derivation pins; the method itself is deleted by this ticket
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6LinkingIT.java — same re-pin where it asserts the old deriveToPostUuid-based to_post value
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/schema.md §Posts and derivatives
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 550
      removed: 124
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
escalations:
  - date: 2026-06-05
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      SELF-CONTAINED-CHECK: FAIL — The Notes section explicitly defers the
      fundamental implementation decision to a "Plan-writer pass": "decide A vs B
      with the architecture lens; Option B redefines post.id module-wide." The
      acceptance criteria do not specify which Option must be implemented; the
      diff shape is completely different for A vs B, so acceptance cannot be
      verified against a single concrete diff.
revisions:
  - date: 2026-06-05
    reason: clarity-fail rework (ticket deferred the Option A vs B design decision to the implementer; acceptance items lacked named tests; files_budget unverifiable until the option was fixed)
    snapshot:
      status: escalated
      escalation_reason: clarity-fail
      files_budget_at_snapshot: 8
      files_scope_at_snapshot:
        - infochat-core/src/main/resources/db/migration
        - infochat-collector/src/main/java/app/zcat/infochat/collector
        - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool
        - infochat-collector/src/test/java/app/zcat/infochat/collector
      acceptance_at_snapshot:
        - "A kind-6 repost edge resolves to a real post when the original event is also seen — the GetReferencesTool join returns the linked post (currently never, because to_post is a deterministic UUID-v3 of the event id while persisted posts use random UUIDs)"
        - "A test seeds a repost referencing a later-seen original and asserts the edge resolves"
        - "mvn -B clean verify from the repo root exits 0"
      out_of_scope_at_snapshot:
        - the broader Nostr ingest pipeline beyond the repost-edge resolution path
        - changing post.id semantics for non-Nostr posts
outline_file: target/m1-tick-outline-M1-139.md
clarity_check:
  date: 2026-06-05
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-139: Kind-6 repost edge resolution

## Context

`Kind6Handler.java:142-167` stores `to_post = nameUUIDFromBytes(eventId)`
(deterministic UUID-v3); `PostPersister.java:108-119` persists posts with
`id = gen_random_uuid()`. `GetReferencesTool.java:67-80` joins
`pr.to_post = post.id` — which can never match. Every kind-6 repost edge is
structurally unresolvable; M1-100's user-visible payoff is absent.
`architecture.md` §Source identity commits to resolving the link "if and when
the original event is also seen."

## Acceptance

See frontmatter.

## Implementation approach

Option A — store the original's `upstream_identifier` on the edge; resolve
`to_post` by UPDATE when the original is seen (either arrival order).
Mandated by `architecture.md` §Source identity: the link "is written as
`(kind-6 post UID) →repost→ (original upstream_identifier)`, resolved to a
post UID if and when the original event is also seen and stored", and
"Implementations MUST NOT use the derived UID as the join key".

Rejected alternatives:

- **Option B (deterministic `post.id` for Nostr posts):** PK collision —
  two Nostr sources with overlapping filter specs legally produce two `post`
  rows for the same event (unique key includes `source_id`); `id = f(event_id)`
  collides on the second arrival. Salting with `source_id` recreates exactly
  the cross-relay failure the spec's MUST-NOT clause names.
- **Option C (keep the v3-UUID placeholder in `to_post`, resolve by UPDATE
  matching the placeholder):** stores a lossy hash where the spec stores the
  identifier; never-resolved edges carry fake ids forever; the no-phantom-join
  guarantee rests on the subtle v3≠v4 version-bit argument.

Concrete shape:

(a) **Schema:** `post_reference.to_upstream_identifier TEXT NULL` (set iff
`link_type='repost'`); `to_post` becomes nullable (NULL = unresolved repost
edge; entity/semantic edges keep `to_post` always set).

(b) **Migration** (next free version assigned at start — do not hardcode):
add the column; drop the PK and `to_post`'s NOT NULL, replace with
`CREATE UNIQUE INDEX (from_post, to_post, link_type, created_at)` (partition
key included; `LinkingJob`'s INSERT has no ON CONFLICT, so the swap has no
arbiter dependency); partial index on `post_reference(to_upstream_identifier)
WHERE to_post IS NULL` for the resolver lookup; index on
`post(upstream_identifier)` for the original-already-present lookup;
`GRANT UPDATE ON post_reference TO infochat_collector` (resolution UPDATE
only; DELETE stays revoked per V29).

(c) **Classes:** `Kind6Handler` — write `to_upstream_identifier` verbatim;
look up an already-present original (`post` joined to `source` with
`kind='nostr'`, `ORDER BY fetched_at ASC, id ASC LIMIT 1`) and set the real
`to_post` if found, else NULL; delete `deriveToPostUuid`. New
`RepostEdgeResolver` — `UPDATE post_reference SET to_post = ? WHERE
link_type='repost' AND to_upstream_identifier = ? AND to_post IS NULL`
(first-wins; status-independent — RAW originals resolve, GetReferencesTool
already filters READY at read). `NostrStreamSource` registrar — invoke the
resolver after every successful Nostr persist (kinds 1 and 6 — a kind-6 can
itself be a repost target). `GetReferencesTool` is unchanged — the existing
`p2.id = pr.to_post` join works once edges resolve; NULL `to_post` rows drop
out of the inner join naturally.

Expected files (6–7 of budget 8): migration, `Kind6Handler`,
`RepostEdgeResolver` (new), `NostrStreamSource`, `Kind6HandlerTest`,
`Kind6LinkingIT`, `Kind6RepostResolutionIT` (new).

## Out-of-scope

See frontmatter. Migration version assigned at start (do not hardcode).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A19 (KIND6-REPOST, High);
  `opus-47-full-handout.md` §F-MAINT-48; `opus-47-only-handout.md` §M4.
- The repost edge stays unidirectional (kind-6 → original), matching the
  directional language in `architecture.md` §Source identity; "reposted by"
  reverse surfacing is not part of this ticket.
