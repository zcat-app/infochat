---
id: M1-220
title: "[INVESTIGATE] Bluesky source identifier: URL (per D38) vs bare DID/handle (per the fetcher)"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 7
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcher.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky
  - infochat-collector/src/test/resources/bootstrap/bootstrap-sources-fixture.json
  - docs/spec/decisions.md
  - docs/design/02-schema.md
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the actor URL-encoding and tolerant-parse legs on the same fetcher — M1-202's (same file; serialize)
  - cross-tick UID dedup — M1-179's
  - the bootstrap loader's (kind, identifier) upsert mechanics — only the documented identifier SHAPE for bluesky sources is in question, not the loader
  - other source kinds' identifier semantics (rss URLs, nostr filter specs) — unambiguous and untouched
acceptance:
  - "docs/design/02-schema.md §2.2.1 gains a short decision record on the bluesky identifier shape, grounded in the contradiction: decision D38 says \"identifier is the URL for HTTP-shaped sources and the filter spec for Nostr\"; the shipped bootstrap fixture carries a full XRPC URL as the bluesky identifier; BlueskyFetcher's javadoc and buildUri treat the identifier as a bare DID/handle appended to a hard-coded base — so a bootstrap-loaded source using the fixture's documented shape would issue ?actor=https://… and fetch nothing"
  - "The verdict is implemented end-to-end: EITHER (a) bare DID/handle is the blessed bluesky identifier — D38's identifier sentence is amended to cover protocol-native account identifiers for API-shaped sources, and the fixture (plus any operator-facing example) is corrected to the bare form; OR (b) the URL form is blessed — BlueskyFetcher derives the actor from the URL identifier (or fetches the URL directly) and its javadoc is fixed; in both directions a named test feeds the documented identifier shape through the fetch path and asserts a well-formed XRPC request results"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Sources and tags
decision_refs:
  - D38
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-220: [INVESTIGATE] Bluesky source identifier shape

## Context

Leftover from batch 2 (M1-202's out_of_scope excluded it as
investigate-tier; M1-161 is the format precedent). The audit leg
(opus-47 coll F3's identifier-semantics half) was never verified;
draft-time grounding 2026-06-07 confirmed a three-way contradiction:

- **D38 (spec):** "identifier is the URL for HTTP-shaped sources and
  the filter spec for Nostr."
- **Fixture:** the bundled bootstrap fixture's bluesky entry uses a
  full XRPC URL
  (`https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed?actor=example.dev`).
- **Code:** BlueskyFetcher's javadoc says "The source's identifier is
  the DID or handle of the account" and buildUri appends the
  identifier verbatim as `?actor=` onto its own hard-coded base.

Whichever two are right, the third breaks a real path: an operator
following the fixture's shape gets a fetcher querying
`?actor=https://…` (no posts, blamed on the source); an operator
following the fetcher's javadoc contradicts the spec's identifier
definition that the (kind, identifier) uniqueness key builds on.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: batch-2 leftover (M1-202 out_of_scope); audit trail in
  `UNIFIED.md` §2 K8's identifier-semantics remainder under
  `deep-code-review/v2/` (opus-47 coll F3, PARTIAL leg).
- Serialize against M1-202 (BlueskyFetcher in both scopes) and note
  M1-179 (fetcher call-shape) ordering if both run.
- Direction (a) is likely the smaller diff (spec/decision wording +
  fixture), but the D38 sentence is uniqueness-key load-bearing —
  hence investigate-tier with the verdict recorded before code.
