-- V81: per-scope chat-reply pipeline mode override (decision D79,
-- commands.md §Conversation control /reply-mode). NULL inherits the
-- deployment default; the registry gates which pairs native resolves for.

ALTER TABLE scope_preferences
    ADD COLUMN reply_mode TEXT CHECK (reply_mode IN ('translate', 'native'));
