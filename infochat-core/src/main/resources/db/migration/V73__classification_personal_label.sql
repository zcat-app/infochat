-- V73: post.classification closed set gains `personal` (M1-727).
--
-- Social sources mix registers: one account carries CVE analysis, a joke, a
-- pet photo and a friend's birthday, all tagged from that source's
-- vocabulary. The V57 set {factual, opinion, technical, urgent, ongoing,
-- unknown} cannot express KIND-versus-topic for those posts — `opinion` is a
-- view ABOUT the subject matter, not a post about the author's weekend — so
-- a correctly-tagged joke rendered under a topic header between two CVEs.
-- `personal` closes that gap; the digest routes all-personal clusters to the
-- D62 Other bucket (docs/spec/commands.md §Periodic group digests).
--
-- Widening a CHECK cannot invalidate an existing row, so there is no
-- backfill and no re-classification job: already-classified posts keep their
-- labels and age out with their partition (D33) — the argument V57 made for
-- its own rollout. The non-empty CHECK (post_classification_non_empty) is
-- untouched.
--
-- post is PARTITION BY RANGE (fetched_at); ALTER TABLE on the parent
-- propagates to every child partition (same mechanism as V66's tagger sweep
-- columns). Atomic Flyway migration: DROP + ADD apply in one transaction.

ALTER TABLE post
    DROP CONSTRAINT post_classification_closed_set;

ALTER TABLE post ADD CONSTRAINT post_classification_closed_set
    CHECK (classification <@ ARRAY['factual','opinion','technical','urgent','ongoing','personal','unknown']::TEXT[]);
