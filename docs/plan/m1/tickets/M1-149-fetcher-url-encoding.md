---
id: M1-149
title: "Fetcher pagination cursor URL-encoding"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 5
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the SSRF wrapper itself (covered by M1-135)
  - other collector fetchers beyond Bluesky/Reddit
acceptance:
  - "BlueskyFetcher and RedditFetcher URL-encode the upstream-supplied cursor/after value before concatenating into the next URL (URLEncoder.encode), so a cursor containing & / # / ? cannot inject or truncate the query"
  - "A test asserts a crafted cursor is encoded, not interpreted"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
  - docs/spec/security.md §Per-source trust boundaries
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-149: Fetcher pagination cursor URL-encoding

## Context

`cursor` (Bluesky) and `after` (Reddit) come from upstream JSON — untrusted per
`security.md` §Threat model — and are concatenated into the next URL with
`?after=` / `&cursor=` directly (`BlueskyFetcher.java:110-117`,
`RedditFetcher.java:108-114`). A cursor containing `&actor=evil` or `#`
injects/truncates the URL; the SSRF guard checks host+IP, not path/query.
`CoingeckoSnapshotSource` already uses `URLEncoder.encode` — local precedent.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-FETCHER-URLENCODE;
  `opus-47-full-handout.md` §F-MAINT-69; `opus-47-only-handout.md` §S7.
