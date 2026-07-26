-- V65: summary_anchor render_form — the typed /retry dispatch axis (D70).
--
-- /retry replays the last anchored /summary in the render form /summary
-- produced (M1-696). Pre-V65 it discovered that form by string-matching the
-- anchor's command_name (RetryCommandHandler.isFullFormAnchor did
-- hasFlag(commandName, "--full")), a path that already smelled: command_name
-- was never normalized, so pre-existing rows read '/summary' with a leading
-- slash and the boolean check papered over it by treating anything without
-- the exact --full marker as the default form. M1-700 widens /summary from
-- two render forms to four; extending the string-match inherits that
-- fragility at a wider surface. render_form is the typed dispatch axis;
-- command_name stays the human-readable/audit string. See
-- docs/design/02-schema.md §"summary_anchor render_form decision".
--
-- Column shape mirrors V37's scope_kind on this same table: TEXT+CHECK (not a
-- Postgres enum — a future form would need ALTER TYPE ADD VALUE under enum,
-- but only an altered CHECK under TEXT+CHECK). The CHECK lists all four
-- values now (bare, short, full, flat) so M1-700 adds no migration — only
-- code that writes and dispatches on short/full.

ALTER TABLE summary_anchor ADD COLUMN render_form TEXT;

-- Backfill from command_name. --full is the only form flag today, always
-- meaning flat per-cluster; everything else (including the unnormalized
-- '/summary' leading-slash variant) maps to the default 'bare'. Exact under
-- the current command_name population; anchors are ephemeral replay state
-- (cleared by any non-/retry input), so a misclassified pre-existing row
-- costs at most one stale /retry replay — the same imperfection tolerance V37
-- applied to scope_kind.
UPDATE summary_anchor
   SET render_form = CASE
       WHEN command_name LIKE '%--full%' THEN 'flat'
       ELSE 'bare'
   END;

ALTER TABLE summary_anchor ALTER COLUMN render_form SET DEFAULT 'bare';
ALTER TABLE summary_anchor ALTER COLUMN render_form SET NOT NULL;

-- Naming follows summary_anchor_scope_kind_chk (V37) / users_registration_state_chk (V27).
ALTER TABLE summary_anchor ADD CONSTRAINT summary_anchor_render_form_chk
    CHECK (render_form IN ('bare','short','full','flat'));
