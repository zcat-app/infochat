---
name: reddit-rss-transport-not-json
description: Reddit fetches ride the /.rss endpoint with the shared app UA; www .json is edge-blocked (403, UA- and feed-token-independent) so RedditFetcher's .json URL was prod-dead code caught by NO review gate
metadata:
  type: project
---

Reddit transport, decided 2026-08-23 after live probes from prod's egress:

- `https://www.reddit.com/<listing>/.rss` + the shared outbound UA
  (`infochat/1.0 (news aggregator)`, M1-704) answers 200. This is the
  ONLY endpoint shape that works anonymously from prod's network. The
  operator added `.rss` identifiers originally as a deliberate
  bot-protection workaround — that context was unwritten and its loss
  cost a full wrong-premise migration ticket (M1-915).
- `www.reddit.com/*.json` returns 403 for EVERY UA, including browser
  UAs and logged-in feed tokens (`?feed=…&user=…` works only inside the
  owning browser's session). `old.reddit.com` 302s everything to login.
  So RedditFetcher's `identifier + ".json"` (and the JSON parser path)
  never worked against prod; no review gate caught it because gates
  verify code-vs-spec, and "the endpoint answers from prod's network"
  was nobody's question. Lesson: for any ticket whose premise is "the
  remote endpoint works", a live probe from prod's egress belongs in the
  analysis BEFORE decomposition.
- `oauth.reddit.com` IS reachable (401 without credentials = real API,
  not edge block) and returns the exact JSON shape RedditResponseParser
  parses. Operator deferred OAuth on 2026-08-23 (app-creation process
  too painful). Consequence: reddit posts/reposts/comments stay NULL —
  the D71 social ranking terms remain starved for reddit until an OAuth
  ticket lands. Not a data bug; the .rss Atom payload carries no
  engagement numbers (verified: no score/slash:comments/counts anywhere
  in the feed).
- The .rss Atom entries DO carry `<id>t3_…</id>` fullnames — the same
  upstream identifier the JSON/OAuth path derives. Ingesting via .rss
  with t3_ ids therefore pre-aligns uids with any future OAuth switch:
  no re-ingest churn when engagement arrives ([[title-headline-doctrine]]
  is untouched; uid derivation per docs/spec/schema.md).
