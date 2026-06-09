---
id: M1-264
title: "Compress: no JDBC tx across LLM call; ceiling gate"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/CompressCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/AutoCompressTrigger.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - ChatPromptBuilder / history assembly (M1-262).
  - The compress summarization prompt content and the summary format.
  - /clear and /export handlers.
  - Auto-compress trigger thresholds (when compress fires) — only what happens around the LLM call and after a failure changes.
acceptance:
  - "CompressCommandHandler performs the LLM summarization with no open JDBC transaction or borrowed connection held across the call: turns are read and the max summarized seq recorded in a first transaction, the LLM call runs with no connection held, and the summary-write + delete run in a second transaction."
  - "The post-summarization delete is bounded by the max seq actually summarized (seq <= bound): a named test asserts a turn persisted concurrently while the LLM call is in flight survives compression un-deleted."
  - "Failure handling preserves today's contract: if the LLM call fails, no turns are deleted and no summary is written (the prior rollback guarantee, now achieved without an open tx)."
  - "After a failed auto-compress with the session at its ceiling, further chat turns are gated: a named test asserts a new turn on a ceiling-stuck session is rejected with the existing failure notice (not silently appended) until a compress succeeds."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-264: Compress: no JDBC tx across LLM call; ceiling gate

## Context

Deep-review v4 verified HIGH **H3** plus medium **M-P6**
(`deep-code-review/v4/UNIFIED-REPORT.md` §1/§2; source
`deep-code-review/v4/fable5/07-module-infochat-provider.md#F4/#F5`):

- **H3:** `CompressCommandHandler.compress` (~:137-183) opens
  `conn.setAutoCommit(false)`, reads messages, then makes the **LLM call
  inside the open transaction** (the rollback-on-LLM-failure path proves the
  connection is held), then `deleteMessages` with no `seq <=` bound. Two
  defects in one shape: (a) a multi-second LLM call pins a pool connection —
  auto-compress drives this on the hot chat path, so the pool starves under
  load; (b) turns persisted concurrently during the LLM call are deleted
  without ever being summarized — silent data loss.
- **M-P6:** `AutoCompressTrigger.checkAndCompress`'s failure arm just returns
  a notice string; `ChatAgent` appends it and continues. The intended
  "session held at the ceiling" gate after a failed compress is unimplemented,
  so a session can grow past its ceiling indefinitely while compress keeps
  failing.

## Acceptance

See frontmatter. The two-transaction split must keep the existing safety
property (LLM failure ⇒ nothing deleted, nothing written) while removing the
held connection, and the delete must be seq-bounded so concurrent turns
survive.

## Out-of-scope

See frontmatter. If an existing test pins the single-transaction rollback
shape (e.g. asserts a rollback call), modifying it to the new
two-transaction equivalent is authorized under `test_plan.modifies`.

## Notes

- The seq bound and the two-transaction split are one design: tx 1 returns
  (turns, maxSeq); tx 2 inserts the summary and deletes `seq <= maxSeq` —
  idempotent if tx 2 is retried.
- The ceiling gate needs a small piece of state ("compress failed at
  ceiling") consulted on the turn-intake path; per-(user, scope) like all chat
  state. Clear it on successful compress or /clear.
- Pairs with M1-262 (both touch ChatAgent); start them serially or in
  separate worktrees with a fork-distance check before the second merge.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-264-*.md
```
