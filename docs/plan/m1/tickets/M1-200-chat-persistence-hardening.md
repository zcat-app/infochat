---
id: M1-200
title: "Chat persistence: seq atomicity, pruner truncation, V42 duplicate-index drop"
status: done
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatSessionRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/scheduler/ChatMemoryPruner.java
  - infochat-core/src/main/resources/db/migration
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/test/java/app/zcat/infochat/provider/scheduler
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - the grants migration (V39) — M1-189's; the LOCK-scope migration (V40) — M1-190's; serialize in the MIG lane, keep all three migrations independent
  - chat-session token budgeting, /clear and /compress semantics — unchanged (but their tests guard the seq mechanism; see Notes)
  - chat_memory keyword extraction and recall — untouched
  - summary_anchor pruning semantics beyond the same toDays() truncation fix applied uniformly in the pruner
acceptance:
  - "Concurrent persistTurn callers for the same (user, scope) never collide on the chat_message primary key: a named DB-backed concurrency test runs parallel writers against one session and asserts every turn lands with a distinct seq and no SQLException (today ChatSessionRepository reads next_seq with a plain SELECT — no FOR UPDATE — before the INSERT, so two writers read the same value and the second hits the PK)"
  - "The /compress reset path still works: CompressCommandHandler resets next_seq to 0 directly via UPDATE chat_session — the chosen seq-allocation mechanism must coexist with that reset (existing CompressCommandHandlerTest and ClearCommandHandlerTest stay green)"
  - "A retention shorter than 24 hours prunes only rows older than the configured duration: a named test sets a sub-day retention and asserts younger rows survive (today the pruner computes int days = (int) retention.toDays(), so PT12H becomes 0 days and the DELETE removes every chat_memory/chat_session/summary_anchor row)"
  - "Migration V42 drops idx_chat_message_session_seq: a named IT asserts the index no longer exists after migration while the chat_message primary key remains (today V18:74-75 creates the index on exactly the PK's column list — a duplicate)"
  - "The chat_memory LRU cap's behavior under concurrent inserts is settled: either the V18 trigger race past the 200-row cap is fixed (e.g. in V42) with a named concurrency test, or the bounded overshoot is accepted and documented in a trigger comment with the rationale argued in the commit message"
  - "mvn -B clean verify from the repo root exits 0 (Flyway ITs prove V42 applies on a fresh DB and on a V41-migrated DB)"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
    - infochat-provider/src/test/java/app/zcat/infochat/provider/scheduler
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Per-scope state
  - docs/spec/commands.md §Conversation control
decision_refs:
  - D37
reviews:
  - round: 1
    date: 2026-06-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 251
      removed: 19
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-07
  verdict: WARN
  warnings: ["ACCEPTANCE-RUNNABLE item 5 (LRU trigger race): the accept-and-document branch of the fork is not mechanically verifiable — 'rationale argued in the commit message' cannot be checked by mvn verify; reviewer must manually inspect the commit message on that path"]
  blockers: []
---

# M1-200: Chat persistence: seq atomicity, pruner truncation, V42 duplicate-index drop

## Context

Four chat-persistence defects (unified findings P5, P6, D7, D8 —
`deep-code-review/v2/UNIFIED.md` §2):

1. **Seq race (P5, med).** ChatSessionRepository.persistTurn upserts the
   session, reads `next_seq` with a plain SELECT (:62-71), inserts the
   message, and lets the V18 AFTER-INSERT trigger increment `next_seq`.
   Two concurrent writers for the same (user, scope) read the same value
   and collide on PK (user_id, scope_kind, scope_id, seq).
2. **Pruner truncation (P6, med).** ChatMemoryPruner:34 computes
   `int days = (int) retention.toDays()`; a sub-day retention (PT12H)
   truncates to 0 and `created_at < now() - make_interval(days => 0)`
   deletes everything. Re-grounded 2026-06-07: the pruner ALREADY uses
   `make_interval(days => ?)` — the audit's `make_interval(secs => …)`
   suggestion targets only the remaining truncation at the Java side.
3. **Duplicate index (D8, low).** V18:74-75's
   idx_chat_message_session_seq indexes exactly the PK column list.
   Drop is assigned migration **V42**.
4. **LRU trigger race (D7, low-med).** V18's BEFORE-INSERT trigger runs
   a COUNT(*) per insert and can overshoot the 200-row cap under READ
   COMMITTED concurrency — settle: fix or accept-and-document.

Call-site sweep (draft time): persistTurn's callers are ChatAgent and
CompressCommandHandler; CompressCommandHandler also writes
`next_seq = 0` directly (its :74 UPDATE), so the seq mechanism must
tolerate an external reset. Tests pinning next_seq semantics:
ChatSessionRepositoryTest, CompressCommandHandlerTest,
ClearCommandHandlerTest, InboundRouterClearCompressIT.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T23 under `deep-code-review/v2/` (gpt R4,
  opus-47 prov F9 / core F4, kimi-folder core C-F4).
- Migration version: **V42** (re-swept 2026-06-07: the original V41
  claim double-booked with M1-182's redteam-refine claim, which was
  invisible to the draft-time sweep because it lived only on the
  M1-182 branch; V41 is now M1-182's approve_quarantine amendment on
  disk. V39/V40 remain reserved by pending M1-189/M1-190 frontmatter.
  Re-sweep at start per the migration-lane rule and bump if taken).

## Suggested direction (unverified hypothesis)

The audit suggested `UPDATE chat_session … RETURNING next_seq` (or
`SELECT … FOR UPDATE`) for the seq allocation, and `toMillis()`-based
interval binding for the pruner.

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
is there a simpler alternative meeting the same acceptance? — note the
V18 trigger currently owns the increment, and CompressCommandHandler's
reset writes next_seq directly). Adopting, adapting, or replacing it is
the implementer's call as long as every acceptance item holds; a
replacement that changes files_scope goes through the escalate path.
