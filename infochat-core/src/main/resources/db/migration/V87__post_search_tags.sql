-- V87: post.search_tags — free-tag array, retrieval-only (category/tag split).

-- The tagger's EXISTING single LLM call gains a best-effort second output
-- field ("search_tags"): specific retrieval topics (places, coins,
-- companies, models) that the bounded category tree deliberately does not
-- carry. Canonical free tags (TagNormalizer: NFC + Locale.ROOT lower-case
-- + ^[a-z0-9][a-z0-9-]{0,47}$, capped, non-normalizable dropped+counted)
-- persist here; categories stay in post.tags untouched. search_tags is
-- never digest-counted, never a /follow-tag/bootstrap/tag-tree surface.
--
-- Sweep interaction (one-time bounded backfill): extending the tagger
-- prompt changes the sweep fingerprint (the prompt-template sha is part
-- of TaggerWorker.sweepFingerprint), bumping the M1-736 sweep generation.
-- Eligibility ORs search_tags='{}' into the existing tags='{}' set, so
-- tagger-done non-fallback posts are re-driven once within the existing
-- caps (batch-size 4/tick, max-attempts 3) — bounded, one-time, expected;
-- fallback rows stay excluded (their tags are bootstrap by design).
--
-- No GIN index: every chartered reader is prefix-LIKE or
-- unnest-aggregation shaped and window-bounded; a text[] GIN serves
-- &&/@>, which none of them uses (§7 — no machinery ahead of need, V83
-- precedent). A future &&-shaped reader adds the index in one migration.
--
-- post is PARTITION BY RANGE (fetched_at); ALTER on the parent propagates
-- to every child partition (V66/V83 precedent). No new GRANTs: the column
-- rides post's existing per-role grants (V7). Atomic Flyway migration.

ALTER TABLE post ADD COLUMN search_tags TEXT[] NOT NULL DEFAULT '{}';
