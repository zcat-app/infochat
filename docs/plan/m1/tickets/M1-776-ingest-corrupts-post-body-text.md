---
id: M1-776
title: "Ingest corrupts post body: escaped punctuation and unstripped HTML"
status: pending
created: 2026-08-06
last_updated: 2026-08-06
blocked_by: []
files_budget: 10
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/IngestTextNormalizer.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/**
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterBodyTextTest.java
  - infochat-collector/src/test/java/**
  - infochat-core/src/test/java/**
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ANY PROVIDER FILE. The corruption is in stored `post.body`; every
    render surface reads that column. Fixing the column fixes all of
    them. A provider-side unescape would be a second, drifting
    decoder and is explicitly forbidden here.
  - >-
    BACKFILL OF THE EXISTING CORPUS. Rewriting already-stored bodies
    (and the embeddings derived from them) is a separate migration
    decision with its own cost. This ticket fixes the INGEST path so
    new posts are clean; file a follow-up if backfill is wanted.
  - >-
    Stage 1's regex set, its watchdog, the redaction format, or the
    quarantine ladder. Only the TEXT THAT GETS PERSISTED is in scope.
acceptance:
  - >-
    DIAGNOSE THE ESCAPE SITE FIRST, AND NAME IT IN THE COMMIT. Upstream
    is proven NOT to be the source: a 100-post Bluesky payload
    (`app.bsky.feed.getAuthorFeed?actor=bsky.app`) contains ZERO `&#NN;`
    sequences, yet 101 of 248 stored posts from that same source carry
    them. There is no `escapeHtml`/`htmlEscape` call in any fetcher, so
    the encoder is somewhere else on the persist path. Find it before
    changing anything.
  - >-
    THE ESCAPE MAY BE A DELIBERATE CONTROL — ESTABLISH THAT BEFORE
    REMOVING IT. The escaped set is exactly `=` `'` `"` `@` `+` and
    backtick (`&#61; &#39; &#34; &#64; &#43; &#96;`), and NOT `< > &`.
    That is the formula/command-injection character set, not an HTML
    set. If it is defending something, the defense must be preserved at
    the boundary that needs it (per engineering-rules §10 "Preserve the
    controls of a path you replace") — not silently deleted. State which
    it is.
  - >-
    A post ingested from a plain-text source stores the publisher's
    characters verbatim. Pinned by a test asserting a fetched body
    containing `We're working on it!!` persists with the apostrophe,
    not `We&#39;re working on it!!`.
  - >-
    HTML markup from HTML-bearing feeds does not reach `post.body`. A
    test asserts an RSS item whose content is
    `<p>Hello <a href="https://x.test">link</a></p>` persists as
    `Hello link` (tags removed, text and URL preserved). Current rates
    on live data: rss 221/530 bodies carry raw tags, nitter 28/28.
  - >-
    A URL inside a body survives intact: `?id=coldcard-hardware-wallet-flaw`
    stays a working URL rather than `?id&#61;coldcard-hardware-wallet-flaw`.
  - "mvn -B -pl infochat-collector -am verify is green"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterBodyTextTest.java
  preserves:
    - >-
      Stage 1's entity PRE-DECODE for scanning (Stage1Pipeline.java:273,
      `unescapeHtml4`) exists to close the entity-bypass vector in
      redteam finding M1-032-2026-05-16 Finding 1. The scanner must keep
      seeing a decoded form no matter what this ticket changes about
      storage.
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Pipelines
  - docs/spec/security.md §Ingest pipeline (security side)
decision_refs: []
reviews: []
overrides: []
---

## Why

`post.body` is not just display text — it is the input to the tagger, the
classifier, the body-summarizer and the embedding vector. Corrupted body text
degrades retrieval quality and every LLM verdict, not only what the reader sees.

Found during the v1.1.0 live test (`.scratch/V1.1.0-TEST-REPORT-CLEAN-RUN.md` §F1).

## Observed

User-visible, from `/saved`:

```
- [b9e43f84…] <p>V každém projektu, ke kterému se připojím, slyším stejnou větu…
https://www.web3isgoinggreat.com/?id&#61;coldcard-hardware-wallet-flaw
```

Entity histogram over posts fetched in one 8-hour window:

| entity | char | count |
|---|---|---|
| `&#61;` | `=` | 517 |
| `&#39;` | `'` | 313 |
| `&#34;` | `"` | 92 |
| `&#64;` | `@` | 69 |
| `&#43;` | `+` | 31 |
| `&#96;` | `` ` `` | 10 |

Raw-tag and entity rates by fetcher over the same window:

| kind | posts | raw HTML | entities |
|---|---|---|---|
| rss | 530 | 221 | 329 |
| bluesky | 248 | **0** | 101 |
| nitter | 28 | 28 | 15 |
| odysee | 1 | 1 | 1 |

Bluesky is the decisive row: no HTML upstream at all, yet 41 % of stored bodies
carry entities. The two problems are separable — punctuation is being *encoded*
by us, and HTML tags are *not being stripped* — but they land on one column and
one user-visible surface, so they are fixed together.

## Expected

```
- [b9e43f84…] V každém projektu, ke kterému se připojím, slyším stejnou větu…
https://www.web3isgoinggreat.com/?id=coldcard-hardware-wallet-flaw
```
