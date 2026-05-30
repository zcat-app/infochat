---
id: M1-100
title: "Nostr kind-6 cross-source linking"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-098
  - M1-093
files_budget: 5
files_scope:
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
  - infochat-core/** — no SPI changes
  - infochat-provider/** — no provider changes
  - post_reference DDL — M1-093 is frozen
  - LinkingJob's entity/semantic linking — M1-093 handles that; this ticket adds the 'repost' link_type
  - signature verification or dedup — M1-097 and M1-098 are frozen
  - fetching or resolving the original event from relays — explicitly out of v1 per D38
acceptance:
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
  The handler extracts this tag.
- **Forward reference.** If the original event is later seen as a
  kind-1 event, normal entity/semantic linking (M1-093's LinkingJob)
  applies. The `upstream_identifier`-keyed `post_reference` edge
  from this ticket is a separate, Nostr-specific link.
