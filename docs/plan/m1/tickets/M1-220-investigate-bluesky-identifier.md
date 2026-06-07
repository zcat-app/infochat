---
id: M1-220
title: "[INVESTIGATE] Bluesky source identifier: URL (per D38) vs bare DID/handle (per the fetcher)"
status: done
created: 2026-06-07
last_updated: 2026-06-07
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
  blockers: []
escalations:
  - date: 2026-06-07
    reason: budget-breach
    reviewer_verdict_excerpt: |
      Pre-implementation developer escalation (no review round ran):
      investigation verdict is direction (b) — URL is the blessed bluesky
      identifier (AddSourceCommandHandler stores args.url().toString()
      verbatim; all five sibling HTTP-shaped fetchers treat identifier as
      URL; fixture and design/07 already carry the URL form; D38 stands
      unamended). Every direction-(b) implementation must modify the
      pre-existing BlueskyFetcherTest.java (4 construction sites pass the
      3-arg (client, pageCap, xrpcBase) constructor; all tests feed bare
      handles as identifiers), but test_plan carries only adds: +
      preserves: — no modifies: entry. Modifying an unauthorized
      pre-existing test is a test-integrity violation; widening test_plan
      requires escalate → refine.
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
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcherTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Sources and tags
decision_refs:
  - D38
reviews:
  - round: 1
    date: 2026-06-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 153
      removed: 53
revisions:
  - date: 2026-06-07
    reason: budget-breach-refine (test_plan lacked modifies authorization for the direction-(b) verdict; pre-implementation, no review round ran)
    snapshot: |
      test_plan:
        adds:
          - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky
        preserves:
          - all tests currently green on main
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

## Investigation verdict (2026-06-07, pre-implementation)

**Direction (b): the URL form is the blessed bluesky identifier.**
Grounding: AddSourceCommandHandler stores `args.url().toString()`
verbatim as the identifier, and the whole /add-source pipeline is
URL-shaped (host-based kind inference, SSRF-guarded URL probe) — a
bare DID/handle cannot enter it, so direction (a) would not resolve
the contradiction, only relocate it to user-added sources. All five
sibling HTTP-shaped fetchers (rss, youtube, odysee, nitter, reddit)
already treat the identifier as a URL; BlueskyFetcher is the sole
outlier. Consequences: D38 stands unamended; the fixture and
design/07's operator example are already correct (untouched
files_scope entries — justified negative space). Implementation form:
fetch the identifier URL directly (sibling precedent), paginate by
appending the encoded cursor; the hard-coded XRPC base, its config
knob, and the constructor's xrpcBase parameter go away. The
pre-existing BlueskyFetcherTest asserts the bare-handle semantics this
verdict declares wrong; rewriting it to the URL shape is the
authorized modification recorded in test_plan.modifies.
