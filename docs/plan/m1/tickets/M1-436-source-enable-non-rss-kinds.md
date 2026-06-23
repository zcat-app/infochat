---
id: M1-436
title: "/source-enable must re-enable all HTTP-shaped source kinds, not just rss"
status: done
created: 2026-06-23
last_updated: 2026-06-23
blocked_by: []
files_budget: 4
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceEnableCommandHandler.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SourceEnableCommandHandlerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "nostr (the v1 stream kind) re-enable and its spec'd single-relay connection probe are NOT implemented; stream-shaped kinds keep the existing rejection because the HEAD probe cannot run against a filter-spec identifier. A stream probe is a separate, larger change."
  - "No change to the probe mechanism (`probeSourceUrl`, a HEAD on `source.identifier`) — it already operates on the URL identifier shared by every HTTP-shaped kind, so widening the gate needs no probe change."
  - "No change to /add-source, /remove-source, /source-disable, or the soft-deleted-revive confirm path."
  - "The ERROR_SOURCE_ENABLE_KIND_NOT_SUPPORTED_IN_V1 message text may be reworded to name stream sources rather than 'this kind'; if left unchanged it stays accurate for nostr. No new bundle key is introduced."
acceptance:
  - "The kind gate in SourceEnableCommandHandler.handle (SourceEnableCommandHandler.java:176) and the TOCTOU re-check in reactivateFailedOrDisabled (SourceEnableCommandHandler.java:208) reject only stream-shaped kinds (v1 stream-kind set = {\"nostr\"}); every HTTP-shaped kind (rss, reddit, bluesky, youtube, odysee, nitter) proceeds to the existing probeSourceUrl HEAD probe and, on a passing probe, is transitioned to active."
  - "A test in SourceEnableCommandHandlerTest asserts a `failed` source with kind='bluesky' is re-enabled to status='active' after a passing probe, mirroring the existing rss assertion (and exercising at least one more non-rss kind, e.g. 'reddit')."
  - "A test in SourceEnableCommandHandlerTest asserts a source with kind='nostr' is rejected with ERROR_SOURCE_ENABLE_KIND_NOT_SUPPORTED_IN_V1 and is NOT transitioned."
  - "The pre-existing rss re-enable test stays green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SourceEnableCommandHandlerTest.java (non-rss re-enable + nostr-rejected cases)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Source management
  - docs/design/00-mvp.md §Fetchers
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 71
      removed: 24
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-23
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-436: /source-enable must re-enable all HTTP-shaped source kinds

## Context

A 2026-06-23 documentation-vs-code audit found that `/source-enable`
rejects every source kind except `rss`
(`SourceEnableCommandHandler.java:176`, `:208` — `!"rss".equals(...)`),
with an inline comment claiming "the Collector's FetchScheduler only
schedules rss rows."

That comment is **false**. The collector registers six polled fetchers
via `@FetcherKind` — rss, reddit, bluesky, youtube, odysee, nitter — and
`FetchScheduler.enumerateActiveSourcesByKinds`
(`infochat-collector/.../fetch/FetchScheduler.java:291`) enumerates
`SELECT DISTINCT kind FROM source WHERE status='active'` and dispatches
by kind, each on its own configured interval
(`application.properties:195-200`: rss 5m, bluesky 10m, nitter 10m,
reddit 15m, odysee 30m, youtube 30m). So non-rss sources **are** actively
fetched, and `/add-source` already resolves all six kinds
(`KindResolver`).

The bug: when a non-rss source trips the failure threshold
(`infochat.fetch.failure-threshold=5`) and flips to `status='failed'`, an
admin has **no way to recover it** — `/source-enable` rejects it outright.
The only escape is direct DB surgery or `/remove-source` + re-add.

The spec is already correct: `docs/spec/commands.md §Source management`
describes `/source-enable` probing "HEAD for HTTP-shaped, single-relay
connection attempt for StreamSource-shaped." This ticket aligns the code
to the spec and to what the collector actually fetches.

## Acceptance

See frontmatter. The fix replaces the rss-only equality check with a
stream-kind *exclusion*: only stream-shaped kinds (the v1 set is
`{nostr}`) are rejected, because the existing `probeSourceUrl` HEAD probe
operates on the URL `identifier` that every HTTP-shaped kind shares and
cannot run against a stream filter-spec. Everything else flows through the
unchanged probe-and-reactivate path.

## Out-of-scope

See frontmatter. nostr stream re-enable (and the spec's single-relay
probe) stays unimplemented and keeps its rejection; the probe mechanism,
the soft-deleted-revive confirm path, and the sibling source commands are
untouched.

## Notes

- **Source map (verified 2026-06-23):**
  - Gate: `SourceEnableCommandHandler.java:176` (`!"rss".equals(source.kind)`)
    and the locked re-check at `:208`
    (`!"rss".equals(locked.kind)`).
  - Probe: `SourceEnableCommandHandler.java:195`
    `probeSourceUrl(source.identifier)` — kind-agnostic HEAD on the URL.
  - Collector reality: `@FetcherKind` on RssFetcher/RedditFetcher/
    BlueskyFetcher/YouTubeFetcher/OdyseeFetcher/NitterFetcher;
    `FetchScheduler.java:291` enumerates active sources of all bound
    kinds. `FetchScheduler` already isolates a stream-kind set (nostr).
- **security_relevant: false** — re-enabling a non-rss source issues the
  *same* SSRF-guarded HEAD probe already used for rss against a URL that
  was previously an active, fetched source. No new outbound-request
  surface and no new threat is introduced; the authorization (bot-admin
  only) and audit-before-effect paths are unchanged.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-436-source-enable-non-rss-kinds.md
```
