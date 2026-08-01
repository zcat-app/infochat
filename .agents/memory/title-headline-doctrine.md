---
name: title-headline-doctrine
description: How post titles/headlines are represented for RSS vs social sources (nitter long titles, Bluesky/Nostr empty titles) — settled doctrine, do not re-derive per ticket
metadata:
  type: project
---

# Title/headline doctrine (settled — do not re-derive)

The "LLM-generated title vs trimmed content" question is DECIDED: deterministic
trimming, everywhere. No LLM-generated titles exist anywhere in the system.
Every new consumer surface reuses the pieces below instead of re-deciding.

- **Ingest owns the stored representation** (M1-693, `docs/spec/schema.md`
  §post §title normalization): the sole write path (`PostPersister` +
  `IngestTextNormalizer`) strips bidi/zero-width/control, substitutes the
  literal `untitled` sentinel for blank titles (the Bluesky/Nostr shape —
  all 729 corpus Bluesky titles are empty), and caps at
  `TITLE_MAX_LENGTH` = 200 chars + `…`. Every consumer sees the same value.
- **DisplayHeadline owns the per-post derivation for render surfaces**
  (M1-714, `provider/render/DisplayHeadline.java`): title, ELSE body
  fallback (titleless posts), then flatten → sanitize → truncate in that
  load-bearing order. Used by ClusterBlockRenderer, DegradedDigestRenderer,
  SummaryProseGenerator.degradedProseFor, SavedCommandHandler (M1-730).
- **LLM-prompt inputs are the one deliberate split.** The summarizer prompt
  gets the FULL untruncated title (one cluster — a cut title would have the
  model describe a fragment). The roll-up prompt feeds titles through
  DisplayHeadline (M1-728, revising M1-714's both-prompts rule) but passes a
  NULL body, so the body fallback is OFF there by design: the roll-up is
  told not to reproduce item detail.

Corpus facts (9,236-post live corpus, M1-714): RSS title = headline (avg
74); nitter title = the post itself (avg 334, max 24,776); Bluesky title =
empty (729/729, bodies avg 172).

Known open edge: a roll-up category whose posts are ALL titleless
(Bluesky-only) or fully budget-dropped yields an EMPTY prompt and the model
fabricates a synthesis (M1-728 redteam, out-of-model). If a follow-up is
filed, the fix is to skip the LLM call when no headline lines were emitted.
