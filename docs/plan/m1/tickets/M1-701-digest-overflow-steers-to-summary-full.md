---
id: M1-701
title: "digest overflow line steers to /summary --full, not @mention"
status: pending
created: 2026-07-26
last_updated: 2026-07-26
blocked_by: [M1-700]
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - docs/spec/commands.md
  - docs/spec/decisions.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The digest closing affordance (reply.digest.closing_affordance =
    "@mention me to go deeper on any story, or ask about a topic you don't
    see here"). It stays as-is: it steers to chat-mode RAG for EXPLORATORY
    questions ("go deeper on a story", "ask about a topic"), which is a
    legitimate chat-mode use — NOT the capped-overflow promise the
    reply.digest.category.more line was making. Only the overflow line lied.
  - >-
    The digest's per-section item cap (infochat.digest.category-item-cap,
    default 12) and whether capped clusters are retrievable byte-faithfully.
    The reworded line steers to /summary <tag> --full, which RE-DERIVES the
    category's clusters over the /summary window (not a byte-faithful replay
    of the digest's exact truncated set). Byte-faithful digest replay needs a
    digest-scope anchor ("T2-F territory" per SummaryAnchorRepository.java:22)
    and is out of scope.
  - >-
    The /summary command itself. /summary <tag> --full is implemented by
    M1-700 (blocked_by); this ticket only changes what the digest PRINTS.
  - >-
    Chat-mode RAG (ChatAgent, searchPosts, semanticSearch). Untouched. The
    original "got 8 week-old news" symptom was chat-mode retrieval triggered
    by the @mention this line used to invite; the reword removes that
    invitation but does not change chat mode.
  - >-
    TranslationPipeline, CategoryRollupGenerator, and the per-cluster prose
    path. The change is to a deterministic bundle-formatted footer line only.
acceptance:
  - >-
    reply.digest.category.more is reworded to steer to /summary <tag> --full
    for real categories: the en value becomes
    "+{0} more — /summary {1} --full to see them" (token {1} = the category's
    controlled-vocabulary tag, e.g. "ai"). DigestRenderer.java:143-147 passes
    section.tag() as the second MessageFormat argument. The tag is the raw
    controlled-vocab string (the exact token /summary parses), matching how
    sectionHeader (DigestRenderer.java:320-322) already uses it.
  - >-
    A new key reply.digest.category.more_other handles the Other bucket
    (section.tag() == null, DigestRenderer.java:300-304): en value
    "+{0} more — /summary --full to see them" (no tag — "other" is not in the
    controlled vocabulary, so /summary other would hit error.summary.unknown_tag).
    DigestRenderer selects more_other when section.tag() == null, more
    otherwise.
  - >-
    cs.properties carries twin values for both keys (D43 bilateral
    completeness; BundleLoaderTest green).
  - >-
    DigestRendererTest.overflowLineNowSteersToSummaryFull (retargeted from the
    current assertion at DigestRendererTest:114) passes: a capped real-category
    section's rendered text contains
    "+2 more — /summary ai --full to see them" and does NOT contain
    "@mention me to see them". A capped Other section's text contains
    "+N more — /summary --full to see them".
  - >-
    The digest's persisted/replayed section bytes (DigestSectionRepository,
    /retry --digest replay) carry the reworded line unchanged — the line is
    composed inside render() before persistence (DigestRenderer.java:145), so
    a slot rendered after this ticket ships the new wording and a /retry
    --digest replays it byte-faithfully (D65). Pre-reword persisted sections
    still carry the old wording (D64 at-least-once; no rewrite of stored bytes).
  - >-
    docs/spec/commands.md §Periodic group digests is amended: the "+N more"
    line now steers to /summary <tag> --full (real categories) / /summary
    --full (Other) rather than to @mention. docs/spec/decisions.md D62's
    "localized '+N more' line" clause is amended to match.
  - >-
    mvn -pl infochat-provider verify is green.
test_plan:
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
decision_refs:
  - D62
---

# M1-701: digest overflow line steers to /summary --full, not @mention

## Context

This is the ticket for the original live complaint that started the
render-forms work. A morning digest grouped by category showed "AI NEWS"
with many records and ended the section with
"+13 more — @mention me to see them" (`reply.digest.category.more`,
`en.properties:833`). The user @mentioned the bot and got 8 week-old
posts — because @mention drops into chat-mode RAG
(`searchPosts`/`semanticSearch` with an LLM-chosen window), which
retrieves semantically-relevant posts independent of the digest's
truncated clusters. The affordance promised the capped clusters; chat
mode delivered something else entirely.

M1-700 (blocked_by) lands `/summary <tag> --full` — categorized-uncapped,
all clusters under headers, no 12-cap — which is an HONEST destination
for "see the rest of this category". Once it exists, this ticket rewires
the digest's overflow line to point there instead of to a bare @mention.
Small change: two bundle keys (real category with tag, Other without),
one composition branch in `DigestRenderer` where `section.tag()` is
already in scope (`DigestRenderer.java:143-147`).

Cites `docs/spec/commands.md §Periodic group digests` and amends D62's
"+N more line" clause.

## Acceptance

The YAML `acceptance:` list is the contract. "Done" means:

1. **Real-category overflow** — `+{0} more — /summary {1} --full to see
   them` ({1}=tag), composed where the renderer already holds
   `section.tag()`.
2. **Other-bucket overflow** — new key, `+{0} more — /summary --full to
   see them` (no tag; "other" isn't addressable).
3. **en+cs twins** — D43 parity, BundleLoaderTest green.
4. **Test retargeted** — the line-114 assertion moves to the new wording
   and asserts the @mention phrase is gone.
5. **Replay consistency** — persisted sections carry the new line;
   `/retry --digest` replays byte-faithfully (D65); pre-reword stored
   sections keep old wording (D64, no rewrite).
6. **Spec + D62** amended.

## Out-of-scope

See the YAML list. The key boundary: **the closing affordance stays.** It
invites chat-mode exploration ("go deeper on a story", "ask about a
topic") — a legitimate RAG use — and is NOT the broken "see the capped
clusters" promise the overflow line was making. Only the overflow line
lied; only the overflow line is reworded.

### Authorized test retargeting (test-integrity §8 disclosure)

`DigestRendererTest` (the assertion at line 114 that pins
`"+2 more — @mention me to see them"`) is RETARGETED to the new wording
and adds a negative assertion (no "@mention me to see them").
`DigestRendererSectionsTest` overflow assertions, if any, are retargeted
likewise. The change tracks the documented bundle-value change.
Disclosed per engineering-rules §8.

## Notes

**Why blocked_by M1-700.** The reworded line steers to
`/summary <tag> --full`. Today `--full` means flat per-cluster (still
uncapped, so technically an honest "see them all" — just flat). After
M1-700 it means categorized-uncapped (nicer). Sequencing this ticket
after M1-700 lands the destination in its final form; landing it earlier
would be honest-but-uglier (flat). The blocked_by is conservative, not
strictly required for honesty — drop it if you want the reword sooner.

**Re-derivation, not byte-faithful replay.** `/summary <tag> --full`
RE-DERIVES the category's clusters over the `/summary` window (default
24h), which is wider than a digest slot. So it shows "all AI clusters in
the last 24h", not "the exact 13 the digest truncated". The set can drift
if posts went READY between the slot and the follow-up. Byte-faithful
replay of the digest's exact truncated set needs a digest-scope anchor
(T2-F territory) and is out of scope here and in M1-700. The reword is
honest about "see all posts in this category", which is what a reader
wants from the overflow line.

**The tag is the raw controlled-vocab string.** `section.tag()` is the
exact token `/summary` parses (e.g. `ai`), so `/summary {1} --full`
produces a directly runnable command — no display-name-to-token guessing
for the reader. This matches how `sectionHeader`
(`DigestRenderer.java:320-322`) already uses the tag for the
`{0} NEWS` header.
