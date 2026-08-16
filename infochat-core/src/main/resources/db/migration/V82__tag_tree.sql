-- V82: v2 tag-tree shape on tag (M1-865; docs/design/05-llm-and-embeddings.md
-- §5.4.2). Mechanism only — no data rows; the v2 seed and the v1 lookup
-- migration are a later ticket. Numbered V82 not V81: M1-848 landed
-- V81__scope_preferences_reply_mode.sql on main before this branch merged.

-- node_kind DEFAULT 'leaf': every pre-V82 row keeps today's semantics, so the
-- window until the v2 seed behaves byte-identically (a parentless leaf is its
-- own identity branch).
ALTER TABLE tag ADD COLUMN node_kind TEXT NOT NULL DEFAULT 'leaf'
    CHECK (node_kind IN ('top', 'leaf'));

-- parent_name links a leaf to its parent row; Java derives a leaf's top by
-- walking it, so leaf names must stay globally unique across branches — that
-- top-derivation invariant rides the existing UNIQUE(name), no new constraint.
ALTER TABLE tag ADD COLUMN parent_name TEXT REFERENCES tag(name);

-- No GRANT changes: the columns ride the tag table's existing per-role grants
-- (Collector SELECT/INSERT/UPDATE, Provider SELECT+INSERT — V6, V31). Both ALTERs apply in
-- one atomic Flyway transaction.
