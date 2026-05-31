---
id: M1-100
title: "Nostr kind-6 cross-source linking"
status: done
created: 2026-05-26
last_updated: 2026-05-31
blocked_by:
  - M1-098
  - M1-093
files_budget: 5
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrEvent.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/Kind6Handler.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6HandlerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6LinkingIT.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes (NormalizedPost record shape unchanged; only its rawMetadata map gains two kind-6-specific keys, which is the existing extensibility mechanism, not an SPI change)
  - infochat-provider/** — no provider changes
  - post_reference DDL — M1-093 is frozen
  - LinkingJob's entity/semantic linking — M1-093 handles that; this ticket adds the 'repost' link_type
  - signature verification or dedup — M1-097 and M1-098 are frozen
  - fetching or resolving the original event from relays — explicitly out of v1 per D38
  - existing NostrStreamSource tests (NostrStreamSourceTest, NostrDedupIT, NostrDegradationIT, NostrStreamSourceIT, NostrStreamSourceVerificationIT) — the NostrStreamSource public constructor signature is preserved; dispatch happens in the Registrar's deliver lambda and in NostrEvent.toNormalizedPost, so existing tests neither compile-break nor behavior-change
  - any to_post UUID encoding other than UUID.nameUUIDFromBytes — alternative encodings (UUID v5, raw-bytes truncation, etc.) are out of scope; the v3 (MD5-based) derivation produced by Java's UUID.nameUUIDFromBytes is the durable choice
acceptance:
  - "Kind-6 events with non-empty content field store the commentary text as the post body and write a post_reference edge with link_type='repost' keyed by the original event's upstream_identifier"
  - "Kind-6 events with empty content field store an empty post body and still write the post_reference edge"
  - "The post_reference join key is the original event's upstream_identifier (Nostr event id), NOT the derived post UID — per architecture.md §Ingest SPIs"
  - "post_reference.to_post for repost edges is UUID.nameUUIDFromBytes(originalEventId.getBytes(StandardCharsets.UTF_8)) — a deterministic UUID v3 derived from ONLY the upstream_identifier. The derivation is source-independent so any future arrival of the original event from any relay re-derives the same to_post UUID, allowing downstream queries (LinkingJob extensions, ClusterTraversal, GetReferencesTool) to resolve the link by computing the same derivation from the original post's upstream_identifier."
  - "Kind-6 dispatch happens via NormalizedPost.rawMetadata: NostrEvent.toNormalizedPost populates the rawMetadata map with key 'nostr.kind' = '6' for kind-6 events (and key 'nostr.repost-target' = <original event id> when the kind-6 carries a NIP-18 ['e', event_id, ...] tag), and the Registrar's deliver lambda routes posts whose rawMetadata.'nostr.kind' equals '6' to Kind6Handler. NostrStreamSource's public constructor and start() SPI are unchanged."
  - "Kind-6 events referencing a disallowed kind (kind 4, 7, etc.) still write the post_reference edge — the edge is a cryptographic event id hash and reveals no content about the original"
  - "The original event is NOT auto-resolved (no extra fetches, no relay round-trips) — per D38"
  - "Kind6HandlerTest.nonEmptyContent_storesBodyAndReference passes — a kind-6 event with commentary stores body + post_reference with link_type='repost'"
  - "Kind6HandlerTest.emptyContent_storesEmptyBodyAndReference passes — a kind-6 event with empty content stores empty body + post_reference edge"
  - "Kind6HandlerTest.joinKeyIsUpstreamIdentifier passes — the post_reference.to_post equals UUID.nameUUIDFromBytes(originalEventId.getBytes(UTF_8)) (deterministic, source-independent)"
  - "Kind6LinkingIT.kind6FlowsToPostReference passes — a kind-6 event processed through the Registrar's deliver path produces a post row and a post_reference row in the DB"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6HandlerTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6LinkingIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/security.md §Nostr (StreamSource, v1)
decision_refs:
  - D38
reviews:
  - round: 1
    date: 2026-05-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 868
      removed: 18
escalations:
  - date: 2026-05-31
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — escalation triggered before round-1 implementation. Two design
      questions surfaced during code-surface survey:
      (1) kind-6 dispatch must happen where NostrEvent.kind() is in scope;
          the three viable designs touch 0, 1, or 5 files outside the
          declared files_scope. Design A (setter injection, 0 out-of-scope)
          carries a DI smell; Design B (encode kind in NormalizedPost.
          rawMetadata via NostrEvent.toNormalizedPost, 1 out-of-scope file)
          is cleanest but expands files_scope; Design C (constructor change,
          5 mechanical test-file updates) cleanest DI but largest scope
          expansion.
      (2) post_reference.to_post UUID NOT NULL doesn't naturally hold a
          Nostr event_id (64-char hex string), but the spec mandates
          "join key is the upstream_identifier, NOT the derived post UID"
          and the schema is frozen. The only fit is
          to_post = UUID.nameUUIDFromBytes(originalEventId.getBytes(UTF_8))
          — a deterministic UUID derived from ONLY the upstream_identifier
          (independent of source). When the original arrives later, downstream
          queries can re-derive the same UUID to resolve. The ticket
          does not pin this representation explicitly.
overrides: []
revisions:
  - date: 2026-05-31
    reason: budget-breach rework
    snapshot:
      files_budget: 5
      files_scope:
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/Kind6Handler.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6HandlerTest.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6LinkingIT.java
      out_of_scope:
        - infochat-core/** — no SPI changes
        - infochat-provider/** — no provider changes
        - post_reference DDL — M1-093 is frozen
        - LinkingJob's entity/semantic linking — M1-093 handles that; this ticket adds the 'repost' link_type
        - signature verification or dedup — M1-097 and M1-098 are frozen
        - fetching or resolving the original event from relays — explicitly out of v1 per D38
      acceptance_at_snapshot:
        - "Kind-6 events with non-empty content field store the commentary text as the post body and write a post_reference edge with link_type='repost' keyed by the original event's upstream_identifier"
        - "Kind-6 events with empty content field store an empty post body and still write the post_reference edge"
        - "The post_reference join key is the original event's upstream_identifier (Nostr event id), NOT the derived post UID — per architecture.md §Ingest SPIs"
        - "Kind-6 events referencing a disallowed kind (kind 4, 7, etc.) still write the post_reference edge — the edge is a cryptographic event id hash and reveals no content about the original"
        - "The original event is NOT auto-resolved (no extra fetches, no relay round-trips) — per D38"
        - "Kind6HandlerTest.nonEmptyContent_storesBodyAndReference passes — a kind-6 event with commentary stores body + post_reference with link_type='repost'"
        - "Kind6HandlerTest.emptyContent_storesEmptyBodyAndReference passes — a kind-6 event with empty content stores empty body + post_reference edge"
        - "Kind6HandlerTest.joinKeyIsUpstreamIdentifier passes — the post_reference edge uses the original event id, not a derived UID"
        - "Kind6LinkingIT.kind6FlowsToPostReference passes — a kind-6 event processed through the pipeline produces a post row and a post_reference row in the DB"
        - "mvn -B clean verify from the repo root exits 0"
      escalation_reason: budget-breach
      refinement_summary: |
        Pin the dispatch mechanism (NostrEvent.toNormalizedPost populates
        rawMetadata 'nostr.kind' / 'nostr.repost-target'; Registrar's deliver
        lambda routes by rawMetadata key). Pin to_post UUID derivation
        (UUID.nameUUIDFromBytes(originalEventId.getBytes(UTF_8))). Add
        NostrEvent.java to files_scope (files_budget unchanged at 5).
        Out-of-scope expanded to declare: (a) the existing NostrStreamSource
        tests are NOT touched because the public constructor is preserved,
        and (b) alternative to_post UUID encodings (UUID v5, raw-bytes
        truncation, etc.) are foreclosed.
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-31
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-100: Nostr kind-6 cross-source linking

## Context

Kind-6 Nostr events are reposts. `security.md` §Nostr and
`architecture.md` §Ingest SPIs define the handling: commentary as body,
`post_reference` edge keyed by the original event's
`upstream_identifier`. This ticket depends on M1-093 (post_reference
DDL) and M1-098 (cross-relay dedup — a kind-6 repost from multiple
relays must be deduped before linking).

## Acceptance

See frontmatter.

## Out-of-scope

- **Auto-resolving the original event** — explicitly out of v1.
- **LinkingJob entity/semantic linking** — M1-093.
- **post_reference DDL** — M1-093 is frozen.

## Notes

- **Link type.** M1-093's V29 CHECK constraint includes `'repost'`
  alongside `'entity'` and `'semantic'`. No schema amendment needed.
- **Original event id extraction.** NIP-01 kind-6 reposts carry the
  original event id in the `tags` array as `["e", event_id, ...]`.
  A private helper in `NostrEvent` (added in this ticket — the same
  class already owns NIP-01 field parsing) scans for the first such
  tag and returns the `event_id`. Kind-6 events without an `e` tag
  are persisted as posts but skip the `post_reference` edge (the
  `nostr.repost-target` rawMetadata key is absent).
- **Forward reference.** If the original event is later seen as a
  kind-1 event, normal entity/semantic linking (M1-093's LinkingJob)
  applies. The `upstream_identifier`-keyed `post_reference` edge
  from this ticket is a separate, Nostr-specific link. A future
  ticket (out of scope here) can extend LinkingJob or
  ClusterTraversal to resolve `post_reference.to_post` UUIDs against
  newly-arriving posts by recomputing
  `UUID.nameUUIDFromBytes(arriving.upstream_identifier.getBytes(UTF_8))`
  — the derivation pinned by acceptance item 4 is the contract that
  makes this resolution mechanical.
- **Dispatch mechanism (Design B from the budget-breach refinement).**
  `NostrEvent.toNormalizedPost` populates `NormalizedPost.rawMetadata`
  with `"nostr.kind"` = `"6"` (and, when present, `"nostr.repost-target"`
  = the original event id from the first `["e", id, ...]` tag) for
  kind-6 events; for kind-1 events `rawMetadata` stays empty as today.
  The `NostrStreamSource.Registrar` builds the deliver lambda to
  dispatch on the `nostr.kind` rawMetadata key: kind-6 events go to
  `Kind6Handler.handle(NormalizedPost, UUID sourceUuid)`; everything
  else goes to the existing `postPersister.persist(...)` path.
  Rationale: NostrEvent.java already documents (line 17) that
  `kind` / `tags` feed M1-100, and `rawMetadata` is the existing
  per-kind extensibility side-channel on NormalizedPost. The two
  rawMetadata keys are exposed as named constants on `Kind6Handler`
  (or NostrEvent — whichever side first introduces them) so the
  producer/consumer share one source of truth.
- **`NostrStreamSource` constructor and SPI preserved.** The Registrar
  inner class's deliver wiring is the only NostrStreamSource change;
  the public constructor and `start()` SPI are not modified, so
  none of the existing Nostr tests (NostrStreamSourceTest,
  NostrDedupIT, NostrDegradationIT, NostrStreamSourceIT,
  NostrStreamSourceVerificationIT) need to be touched — they remain
  outside files_scope. The negative-space check at review time
  confirms this.
- **`to_post` UUID derivation.** Acceptance item 4 pins
  `UUID.nameUUIDFromBytes(originalEventId.getBytes(StandardCharsets.UTF_8))`.
  This is Java's idiomatic deterministic-UUID-from-bytes (UUID v3,
  MD5-based). The MD5 is NOT used as a security primitive — it is
  the deterministic mapping between the Nostr event id (already a
  SHA-256 hash of canonical JSON, per NIP-01) and a valid UUID
  with correct variant bits. Source-independent: the same event_id
  delivered from any relay (with any source_id) derives the same
  to_post UUID, satisfying the architecture.md §Ingest SPIs rule
  that "Implementations MUST NOT use the derived UID as the join
  key in the post_reference edge for kind-6 reposts." Collision
  risk is irrelevant for any Nostr-scale deployment.
