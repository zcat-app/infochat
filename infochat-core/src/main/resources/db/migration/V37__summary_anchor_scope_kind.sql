-- V37: summary_anchor scope_kind discriminator.
-- Implements docs/design/02-schema.md §"summary_anchor scope_kind decision".
--
-- V19 keyed personal anchors on (user_id, scope_id, command_kind) and
-- digest anchors on (scope_id, command_kind) without the scope_kind
-- discriminator every other per-(user, scope) table carries (V7, V15,
-- V18). DM scope_id is the actor's own users.id; group scope_id is
-- groups.id — both populations are uniform random UUIDv4 (column-default
-- gen_random_uuid() plus the app-side UUID.randomUUID() of the preban
-- /ban INSERT), so nothing structural prevents a users.id == groups.id
-- collision that would alias the same user's DM and group anchors
-- (Invariant 1 breach). The column shape mirrors V18's chat tables.

ALTER TABLE summary_anchor ADD COLUMN scope_kind TEXT;

-- Backfill: a personal DM anchor satisfies scope_id = user_id by
-- derivation (DM scope_id IS the actor's users.id); digest anchors are
-- written for group scopes only. Exact under the current derivation
-- modulo the very collision this migration forecloses — anchors are
-- ephemeral replay state (cleared by any non-/retry input), so a
-- misclassified pre-existing colliding row costs at most one stale
-- /retry replay.
UPDATE summary_anchor
   SET scope_kind = CASE
       WHEN user_id IS NOT NULL AND scope_id = user_id THEN 'dm'
       ELSE 'group'
   END;

ALTER TABLE summary_anchor ALTER COLUMN scope_kind SET NOT NULL;

-- Naming follows users_registration_state_chk (V27) / post_stage2_verdict_chk (V36).
ALTER TABLE summary_anchor ADD CONSTRAINT summary_anchor_scope_kind_chk
    CHECK (scope_kind IN ('dm','group'));

-- Widen both partial unique indexes to carry the discriminator so a DM
-- anchor and a group anchor with the same scope_id coexist.
DROP INDEX summary_anchor_personal;
CREATE UNIQUE INDEX summary_anchor_personal
    ON summary_anchor(user_id, scope_kind, scope_id, command_kind)
    WHERE user_id IS NOT NULL;

DROP INDEX summary_anchor_digest;
CREATE UNIQUE INDEX summary_anchor_digest
    ON summary_anchor(scope_kind, scope_id, command_kind)
    WHERE user_id IS NULL AND command_kind = 'digest';
