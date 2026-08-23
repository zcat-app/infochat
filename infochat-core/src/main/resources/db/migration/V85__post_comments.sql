-- V85: post.comments — the reddit reply count as a typed ranking input
-- (D71's fifth prominence term). Nullable INT: NULL is the documented
-- no-signal state for every kind but reddit (an RSS article has no reply
-- count; a reddit post with num_comments: 0 was seen and reported zero —
-- the ranking keeps the two apart). Only RedditResponseParser writes a
-- non-NULL value; the column rides the existing engagement-column family
-- (V7) and joins no index, constraint or generated column, so the ADD is
-- metadata-only on the TTL-partitioned parent — no table rewrite.
-- Grants: none new — the collector role's table-level INSERT on post (V7)
-- and the provider role's SELECT cover the added column.

ALTER TABLE post ADD COLUMN comments INT;
