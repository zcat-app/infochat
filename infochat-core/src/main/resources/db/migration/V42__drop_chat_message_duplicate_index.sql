-- V42: drop idx_chat_message_session_seq — duplicate of the PK index.
--
-- V18:74-75 created the index on (user_id, scope_kind, scope_id, seq),
-- column-for-column identical to the chat_message PRIMARY KEY's implicit
-- unique index. The planner never prefers it, and every chat_message
-- INSERT and DELETE (one row per chat turn, plus /clear cascade deletes)
-- maintains a second index for zero query benefit.
DROP INDEX idx_chat_message_session_seq;

-- LRU-cap concurrency: bounded overshoot accepted, not fixed. The
-- BEFORE-INSERT trg_chat_memory_cap counts committed rows only, so under
-- READ COMMITTED N concurrent inserts into one (user, scope) can each see
-- the same pre-insert count and leave up to N-1 rows past the 200-row cap.
-- The trigger deletes count-199 oldest rows on EVERY insert, so any
-- overshoot is trimmed by the next insert into the same scope — transient,
-- self-correcting, bounded by per-scope insert concurrency. Fixing it would
-- cost a per-insert serialization (advisory lock) on a soft LRU bound.
COMMENT ON TRIGGER trg_chat_memory_cap ON chat_memory IS
    'LRU cap (200 rows per user+scope) can transiently overshoot under '
    'concurrent inserts: the BEFORE-INSERT COUNT(*) sees committed rows '
    'only. Accepted as bounded: each insert deletes count-199 oldest rows, '
    'so the next insert into the scope trims any overshoot.';
