---
id: M1-061
title: chat_session + chat_memory + chat_message + summary_anchor DDL
status: pending
created: 2026-05-24
last_updated: 2026-05-24
blocked_by: []
files_budget: 5
files_scope:
  - infochat-core/src/main/resources/db/migration/V17__chat_tables.sql
  - infochat-core/src/main/resources/db/migration/V18__summary_anchor.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/scheduler/ChatMemoryPruner.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/scheduler/ChatMemoryPrunerTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - any Provider-side chat agent code, tool registry, or tool dispatcher — M1-062 territory
  - any InboundRouter change or chat-mode dispatch — M1-063 territory
  - any /clear, /compress, /stop, /retry, /forget, /export handler — M1-064, M1-065, M1-066, M1-067 territory
  - any change to existing migrations (V1–V16) — new migrations only
  - any post_reference table (v2-deferred per spec)
  - any summary_cache table — T2-F territory (periodic group digests)
  - any chat_memory row insertion logic or LLM compression — M1-064 territory; this ticket creates the table only
  - any modification to InboundRouter.java or any CommandHandler
acceptance:
  - "infochat-core/src/main/resources/db/migration/V17__chat_tables.sql exists and applies cleanly on a fresh DB. V17 creates three tables: chat_session, chat_memory, chat_message — matching the DDL in docs/design/02-schema.md §§2.6.2–2.6.4. Verify: the migration file exists AND grep -E 'CREATE TABLE chat_session' V17__chat_tables.sql returns >=1 match AND grep -E 'CREATE TABLE chat_memory' V17__chat_tables.sql returns >=1 match AND grep -E 'CREATE TABLE chat_message' V17__chat_tables.sql returns >=1 match"
  - "V17 creates the chat_message counter trigger (trg_chat_session_counters) that maintains chat_session.token_count and chat_session.next_seq on INSERT/DELETE of chat_message rows. Verify: grep -E 'CREATE OR REPLACE FUNCTION trg_chat_session_counters' V17__chat_tables.sql returns >=1 match AND grep -E 'CREATE TRIGGER' V17__chat_tables.sql returns >=1 match"
  - "V17 creates the chat_memory GIN index on keywords for the recallMemory tool's keyword search. Verify: grep -E 'CREATE INDEX.*chat_memory.*USING gin' V17__chat_tables.sql returns >=1 match"
  - "V17 creates the chat_memory cap-enforcement trigger (BEFORE INSERT, evicts oldest row by created_at ASC when count reaches 200 per (user_id, scope_kind, scope_id) per docs/design/02-schema.md §2.6.2). Verify: grep -E 'BEFORE INSERT' V17__chat_tables.sql returns >=1 match"
  - "V17 carries the per-role GRANT split per docs/spec/security.md §DB roles. Provider gets SELECT, INSERT, UPDATE, DELETE on chat_session, chat_memory, chat_message (it reads, writes, and purges via /clear and /forget). Collector gets no grants on these tables (chat is Provider-only). Verify: grep -E 'GRANT.*ON chat_session.*TO infochat_provider' V17__chat_tables.sql returns >=1 match AND grep -E 'GRANT.*ON chat_memory.*TO infochat_provider' V17__chat_tables.sql returns >=1 match AND grep -E 'GRANT.*ON chat_message.*TO infochat_provider' V17__chat_tables.sql returns >=1 match AND grep -E 'infochat_collector' V17__chat_tables.sql returns ZERO matches"
  - "infochat-core/src/main/resources/db/migration/V18__summary_anchor.sql exists and applies cleanly on a fresh DB. V18 creates the summary_anchor table matching docs/design/02-schema.md §2.6.5, with the two partial unique indexes (personal vs digest) and the generated_at index. Verify: the migration file exists AND grep -E 'CREATE TABLE summary_anchor' V18__summary_anchor.sql returns >=1 match AND grep -E 'CREATE UNIQUE INDEX summary_anchor_personal' V18__summary_anchor.sql returns >=1 match AND grep -E 'CREATE UNIQUE INDEX summary_anchor_digest' V18__summary_anchor.sql returns >=1 match"
  - "V18 carries the CHECK constraint enforcing the personal-vs-digest row shapes (user_id NOT NULL for personal, NULL for digest). Verify: grep -E 'CHECK' V18__summary_anchor.sql returns >=1 match AND grep -E 'command_kind.*personal.*user_id IS NOT NULL' V18__summary_anchor.sql returns >=1 match"
  - "V18 grants Provider SELECT, INSERT, UPDATE, DELETE on summary_anchor (Provider writes anchors on /summary and reads them on /retry; /forget deletes personal anchors). Collector gets no grants. Verify: grep -E 'GRANT.*ON summary_anchor.*TO infochat_provider' V18__summary_anchor.sql returns >=1 match AND grep -E 'infochat_collector' V18__summary_anchor.sql returns ZERO matches"
  - "ChatMemoryPruner.java exists as a @Scheduled bean that deletes chat_memory, chat_session, chat_message, and summary_anchor rows older than the profile-driven retention horizon (Invariant 9 per docs/spec/schema.md §Invariants). Verify: grep -E '@Scheduled' ChatMemoryPruner.java returns >=1 match AND grep -E 'chat_memory|chat_session|summary_anchor' ChatMemoryPruner.java returns >=1 match"
  - "ChatMemoryPrunerTest verifies that rows older than the retention horizon are deleted and rows within the horizon are preserved. Verify: grep -iE 'void.*prunesExpiredRows' ChatMemoryPrunerTest.java returns >=1 match AND grep -iE 'void.*preservesRecentRows' ChatMemoryPrunerTest.java returns >=1 match"
  - "mvn -pl infochat-core,infochat-provider verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/scheduler/ChatMemoryPrunerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Per-scope state (Chat memory, Chat session, Summary anchor)
  - docs/spec/schema.md §Invariants (Invariant 9 — Chat-memory TTL)
  - docs/design/02-schema.md §§2.6.2–2.6.5
decision_refs:
  - D19
  - D25
  - D36
  - D37
  - D40
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-061: chat_session + chat_memory + chat_message + summary_anchor DDL

## Context

The chat agent (T2-D) requires four database tables that do not yet exist:
`chat_session`, `chat_memory`, `chat_message`, and `summary_anchor`. V7's
migration comment explicitly defers them ("later T1 tickets land … chat_memory,
chat_session, summary_anchor"). This ticket creates the Flyway migrations and
the scheduled TTL pruner, unblocking M1-062 (tool registry), M1-063 (chat
dispatch), M1-064 (/clear + /compress), M1-065 (/stop + /retry), M1-066
(/forget), and M1-067 (/export). The tables implement `docs/spec/schema.md`
§Per-scope state and Invariant 9 (chat-memory TTL).

## Acceptance

See the YAML `acceptance:` list above. In summary:

1. **V17** creates `chat_session`, `chat_memory`, and `chat_message` with the
   counter trigger, GIN keyword index, LRU cap trigger, and Provider-only
   GRANTs matching `docs/design/02-schema.md` §§2.6.2–2.6.4.
2. **V18** creates `summary_anchor` with the two partial unique indexes
   (personal vs digest), the CHECK constraint, and Provider-only GRANTs
   matching `docs/design/02-schema.md` §2.6.5.
3. **ChatMemoryPruner** is a `@Scheduled` bean that removes rows past the
   profile-driven retention horizon across all four tables.
4. `mvn verify` is green.

## Out-of-scope

This ticket creates the DDL and the pruner only. No application code reads or
writes these tables — that is the responsibility of subsequent tickets:

- **M1-062** — tool registry (reads `chat_memory` via `recallMemory`)
- **M1-063** — InboundRouter dispatch (writes `chat_session` + `chat_message`)
- **M1-064** — `/clear` + `/compress` (deletes/writes across chat tables)
- **M1-065** — `/stop` + `/retry` (reads/writes `summary_anchor`)
- **M1-066** — `/forget` (deletes across chat tables + `summary_anchor`)
- **M1-067** — `/export` (reads across chat tables + `summary_anchor`)

No changes to existing migrations, no changes to InboundRouter or any handler.

## Notes

- Two separate migrations (V17, V18) rather than one because `summary_anchor`
  is logically independent of the chat session/memory surface and may need to
  exist for `/summary`'s anchor-write path independently.
- The migration version numbers (V17, V18) assume M1-055b (in-flight) does not
  consume V17 first. If it does, renumber at `/m1-tick start` time per the
  standard "next-free V<N>" convention.
- The pruner runs on the Provider because all four tables are Provider-owned
  (Collector has no grants). The pruner cadence is design-tier (in
  `docs/design/02-schema.md` §2.6.2).
- `chat_message` uses a composite FK to `chat_session(user_id, scope_kind,
  scope_id)` with `ON DELETE CASCADE` so `/clear`'s single DELETE on
  `chat_session` cascades to messages.
- Relevant design: `docs/design/02-schema.md` §§2.6.2–2.6.5.
- Adjacent pattern: V15 (`saved_post`) for the GRANT style; V10 (`quarantine`)
  for the partial unique index pattern.
