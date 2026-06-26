---
id: M1-456
title: Accept nitter kind in /add-source via operator host-config
status: pending
created: 2026-06-26
last_updated: 2026-06-26
blocked_by: []
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/KindResolver.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source/KindResolverTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  - infochat-provider/src/main/resources/application.properties
  - docs/spec/commands.md
  - docs/design/03-commands.md
  - USER_GUIDE.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-collector/**                # NitterFetcher + bootstrap already support kind='nitter'; no collector change
  - any Flyway migration                 # source.kind has no DB CHECK; bootstrap already inserts 'nitter'
  - the cross-kind duplicate guard        # made unnecessary for nitter by deterministic host resolution; separate concern
  - StreamSource kinds (nostr) in the resolver
  - the SSRF/UrlProbe path                # the HTTP probe is unchanged; nitter takes the existing HTTP-shaped probe
acceptance:
  - KindResolverTest.explicitTypeNitterResolvesNitter passes
  - KindResolverTest.nitterHostResolvesNitterWithoutExplicitType passes
  - KindResolverTest.nonNitterHostRssUrlStillResolvesRss passes
  - KindResolverTest.nitterHostWithExplicitRssTypeIsRejected passes
  - AddSourceCommandHandlerTest.addSourceTypeNitterCreatesNitterKindSource passes
  - "`/add-source <url> --type nitter` persists a source row with `kind='nitter'` (parsed by the existing NitterFetcher)"
  - "A URL whose host is in `infochat.sources.nitter-hosts` resolves to `nitter` with no `--type`; the same host with `--type rss` is rejected with a friendly 'configured Nitter instance' error"
  - "With `infochat.sources.nitter-hosts` empty (the default), behaviour is unchanged except that explicit `--type nitter` is now accepted"
  - docs/spec/commands.md §Source management and docs/design/03-commands.md document nitter as an explicit-`--type` + operator-host-configured kind (not auto-detected by URL shape)
  - USER_GUIDE.md §"Add a news source" corrected — Nitter is a selectable type, not "ordinary RSS"
  - mvn verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/source/KindResolverTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Source management
decision_refs:
  - D38
---

# M1-456: Accept nitter kind in /add-source via operator host-config

## Context

`nitter` is already a first-class source kind: the collector ships a
dedicated `@FetcherKind("nitter")` `NitterFetcher`, the bootstrap loader
accepts `"kind":"nitter"`, and the shipped `bootstrap-sources.json` seeds
one. But the provider's `/add-source` cannot create one — `KindResolver.SourceKind`
is `{RSS, NOSTR, BLUESKY, REDDIT, YOUTUBE, ODYSEE}`, with no `NITTER`. So
an operator can seed a `nitter` source but a user `/add-source`-ing the
same Nitter feed gets it filed as `rss`. That asymmetry is confusing and,
because `source` is `UNIQUE (kind, identifier)`, lets the *same* feed exist
twice (once `nitter`, once `rss`) — duplicate fetching and duplicate posts.

This ticket closes the gap by making Nitter feeds resolve **deterministically**
to `nitter`: `--type nitter` is accepted, and URLs whose host the operator
declares in `infochat.sources.nitter-hosts` auto-resolve to `nitter` (Nitter
has no canonical host, so the allowlist is operator-supplied). Retrieval is
kind-agnostic, so this changes provenance/labelling and fetch routing only —
both Nitter and RSS parse identically via `SingleGetFetch`.

## Acceptance

- `KindResolver.SourceKind` gains `NITTER`; `wire()` yields `"nitter"` and
  `fromString("nitter")` resolves it. Test: `explicitTypeNitterResolvesNitter`.
- A new provider config `infochat.sources.nitter-hosts` (comma-separated host
  allowlist, default empty) is consumed by `KindResolver`. A URL whose host
  (IDN-folded, lower-cased, subdomain-aware — matching the existing host
  rules) is in the list resolves to `NITTER` **before** the generic `/rss`
  path rule, with no `--type`. Test: `nitterHostResolvesNitterWithoutExplicitType`.
- A non-Nitter RSS URL still resolves `rss` exactly as today (no regression).
  Test: `nonNitterHostRssUrlStillResolvesRss`.
- A nitter-host URL passed with an explicit non-nitter `--type` (e.g.
  `--type rss`) is rejected with a friendly error naming the host as a
  configured Nitter instance — so a feed cannot be forced into the wrong kind
  and duplicated. Test: `nitterHostWithExplicitRssTypeIsRejected`.
- `/add-source <url> --type nitter --tags …` creates a `source` row with
  `kind='nitter'`, taking the existing HTTP probe (not the Nostr relay probe;
  explicit `--type` skips the RSS content-type contradiction gate). Test:
  `addSourceTypeNitterCreatesNitterKindSource`.
- `docs/spec/commands.md` §Source management and `docs/design/03-commands.md`
  are amended: the closed auto-detect host table additionally carries a
  config-driven nitter-host rule; `nitter` is a valid explicit `--type`; and
  the "explicit `--type` always wins" note is qualified for nitter-hosts.
- `USER_GUIDE.md` §"Add a news source" is corrected: Nitter is a distinct,
  selectable type, replacing the current "treats them as ordinary RSS feeds
  rather than a separate type" line.
- `mvn verify` is green.

## Out-of-scope

No collector change — `NitterFetcher` and the bootstrap loader already
handle `kind='nitter'`. No Flyway migration — `source.kind` has no DB CHECK
constraint (bootstrap already inserts `nitter`). The general cross-kind
**duplicate guard** (reject/redirect when an identifier already exists under
a different kind) is intentionally not built here: deterministic host
resolution removes the *accidental* duplicate for Nitter, which is the case
that motivated this work; a general guard is a separate concern. Stream
kinds (`nostr`) and the SSRF/`UrlProbe` path are untouched.

## Notes

- Resolution precedence in `KindResolver.resolve()`: explicit `--type` is
  read first today and wins. The nitter-host rule slots into the host-pattern
  block, **before** the `/rss`-path rule, so `https://<instance>/<user>/rss`
  on a configured host resolves `nitter`, not `rss`. The one new wrinkle is
  the `--type rss` rejection on a nitter-host, which qualifies the
  "explicit wins" invariant — call this out explicitly in the spec amendment.
  Implementation choice for surfacing the rejection (a new `Resolution`
  variant vs. a handler-side check) is left to the implementer; the
  observable contract is the friendly error, not the mechanism.
- Why a host allowlist and not URL auto-detection: Nitter is self-hosted on
  arbitrary, churning domains — there is no canonical host to match (unlike
  `bsky.app`/`reddit.com`/…), and a Nitter RSS URL is structurally identical
  to a generic RSS URL. The operator naming their instance host(s) is the only
  reliable signal. This is the same trust model as bootstrap (the operator
  declares the kind).
- Security posture: kind resolution does not touch authorization, the ban
  wall, isolation, or the SSRF guard — the HTTP probe runs identically for
  `rss` and `nitter`. Hence `security_relevant: false`.
- Adjacent code: `KindResolver.java` (host rules + `SourceKind` enum),
  `AddSourceCommandHandler.java:160-195` (resolve → probe → upsert; note the
  content-type gate at 182-187 only fires when `typeOverride().isEmpty()`,
  so it never affects explicit `--type nitter`).
- Complements M1-457 (which makes soft-deleted nitter sources revivable);
  neither blocks the other.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-456-add-source-nitter-kind.md
```
