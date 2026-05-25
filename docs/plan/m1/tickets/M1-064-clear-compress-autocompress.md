---
id: M1-064
title: /clear + /compress + auto-compress
status: done
created: 2026-05-24
last_updated: 2026-05-25
blocked_by:
  - M1-063
files_budget: 14
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClearCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClearConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/CompressCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/AutoCompressTrigger.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClearCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/CompressCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/AutoCompressTriggerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterClearCompressIT.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - any InboundRouter change beyond handler registration (CDI auto-discovery handles it)
  - any chat_session or chat_message schema change — M1-061 created the tables
  - any /stop or /retry handler — M1-065 territory
  - any /forget or /export handler — M1-066, M1-067 territory
  - any group-scope specific behavior — T2-F territory; /clear and /compress work the same in DM and group per spec
  - any modification to existing CommandHandler implementations outside the files_scope list
  - any translation pipeline change — the one-line auto-compress system message uses bundleLoader (not LLM prose)
  - reconciling docs/design/05-llm-and-embeddings.md §5.7 threshold values with §3.9 — §3.9 is authoritative for this ticket; §5.7 cleanup is a separate follow-up
acceptance:
  - "ClearCommandHandler.java exists, implements CommandHandler with commandName() returning 'clear', and requires confirm per spec §Conversation control — /clear. Verify: ClearCommandHandlerTest.requiresConfirm passes"
  - "/clear confirm deletes all chat_message rows for the calling (user, scope) via the ON DELETE CASCADE from chat_session, and resets chat_session.token_count and next_seq to 0. chat_memory is NOT touched (D25). Verify: ClearCommandHandlerTest.wipesMessagesPreservesMemory passes"
  - "/clear confirm on a (user, scope) with no chat_session is a no-op with a friendly reply (idempotent). Verify: ClearCommandHandlerTest.noSessionIsNoOp passes"
  - "CompressCommandHandler.java exists, implements CommandHandler with commandName() returning 'compress'. On success it calls the LLM to compress the chat_message history into a chat_memory entry (summary + keywords + referenced posts), then truncates chat_message rows and resets chat_session counters. Verify: CompressCommandHandlerTest.compressesAndTruncates passes"
  - "/compress failure (LLM unreachable, timeout, schema-violating reply after retry) leaves the session unchanged and returns a localized 'memory checkpoint pending; please /compress manually or try again later' error (spec §Failure handling — Compression failure). Verify: CompressCommandHandlerTest.failurePreservesSession passes"
  - "AutoCompressTrigger.java fires between turns when chat_session.token_count exceeds the profile-driven percentage of the context-window ceiling (spec §Auto-compress; thresholds in docs/design/03-commands.md §3.9 Conversation control). On auto-compress, a one-line system message (localization-bundle string D43) is sent to the user confirming the checkpoint. Verify: AutoCompressTriggerTest.firesAtThreshold passes"
  - "Auto-compress runs between turns — after the current reply is delivered and before the next message is processed — so a reply is never interrupted mid-stream (spec §Auto-compress). Verify: AutoCompressTriggerTest.neverInterruptsReply passes"
  - "Auto-compress failure follows the same failure handling as manual /compress: session held at ceiling, friendly error on next chat-mode message (spec §Failure handling — Compression failure). Verify: AutoCompressTriggerTest.failureHoldsAtCeiling passes"
  - "ChatAgent.java is modified to call AutoCompressTrigger after delivering a chat reply and before accepting the next message. Verify: InboundRouterClearCompressIT.autoCompressFiringEndToEnd passes"
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClearCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/CompressCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/AutoCompressTriggerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterClearCompressIT.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  modifies_reason: "ChatAgentTest.java updated to pass the new AutoCompressTrigger constructor parameter to TestChatAgent — no assertion changes, purely mechanical orphan from ChatAgent constructor widening"
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
  - docs/spec/security.md §Failure handling
  - docs/spec/llm.md §Failure handling (recap)
  - docs/design/03-commands.md §3.9 Conversation control
decision_refs:
  - D24
  - D25
  - D37
  - D40
  - D43
reviews:
  - round: 1
    date: 2026-05-25
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: FAIL
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 16
      added: 1644
      removed: 23
  - round: 2
    date: 2026-05-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 16
      added: 1672
      removed: 24
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
escalations:
  - date: 2026-05-25
    reason: budget-breach
    reviewer_verdict_excerpt: "N/A"
  - date: 2026-05-25
    reason: budget-breach
    reviewer_verdict_excerpt: "SCOPE-DRIFT-CHECK: FAIL — ChatAgentTest.java outside files_scope, 14 files vs budget 13"
revisions:
  - date: 2026-05-25
    reason: budget-breach — files_scope missing 4 required support files (BundleKeys.java, en.properties, cs.properties, application.properties); files_budget 10 insufficient for 13 files
  - date: 2026-05-25
    reason: budget-breach — round 1 review SCOPE-DRIFT-CHECK FAIL; ChatAgentTest.java orphan from ChatAgent constructor widening not in files_scope; files_budget 13 insufficient for 14 files
outline_file: target/m1-tick-outline-M1-064.md
clarity_check:
  date: 2026-05-25
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-064: /clear + /compress + auto-compress

## Context

With chat mode live (M1-063), users need conversation control: `/clear` to
wipe the active context window, `/compress` to force a memory checkpoint, and
auto-compress to prevent the session from exceeding the context-window ceiling.
These are the three mechanisms that manage the `chat_session` ↔ `chat_memory`
lifecycle per `docs/spec/commands.md` §Conversation control and `docs/spec/
security.md` §Failure handling — Compression failure.

## Acceptance

See the YAML `acceptance:` list above. In summary:

1. **`/clear`** (confirm-required) deletes `chat_message` rows via cascade,
   resets `chat_session` counters, does NOT touch `chat_memory` (D25).
2. **`/compress`** calls the LLM to compress the message history into a
   `chat_memory` entry, then truncates. Failure leaves the session unchanged.
3. **Auto-compress** fires between turns at the profile-driven threshold,
   sends a one-line bundle string, and follows the same failure handling as
   manual `/compress`.
4. `mvn verify` is green.

## Out-of-scope

- **No `/stop` or `/retry`.** M1-065.
- **No `/forget` or `/export`.** M1-066, M1-067.
- **No schema changes.** The tables exist from M1-061.

## Notes

- `/clear` is a destructive command requiring the confirm flow. The existing
  `ConfirmStateService` + `PendingConfirm` infrastructure (M1-057) is reused.
  `ClearConfirm` is a new `PendingConfirm` variant.
- `/compress`'s LLM call uses `ModelTask.CHAT_AGENT` (or a dedicated
  `COMPRESSOR` task — design-tier decision; the spec says "LLM unreachable"
  in the compression failure path, pointing at the same provider).
- Auto-compress thresholds: `laptop` 75%, `vps` 75%, `pi` 60%,
  `remote-llm` 80% (from `docs/design/03-commands.md` §3.9).
- Adjacent pattern: `SummaryCommandHandler` for the existing LLM-call +
  failure-fallback pattern; `BanConfirm` / `RemoveSourceConfirm` for the
  confirm variant pattern.

## Round 1 rework

1. **SCOPE-DRIFT-CHECK: FAIL** — ChatAgentTest.java is outside `files_scope` and pushes diff to 14 files (budget 13). Fix: add `ChatAgentTest.java` to `files_scope` and bump `files_budget` from 13 to 14 via escalate→refine.
2. **TEST-INTEGRITY-CHECK: FAIL** — ChatAgentTest.java was modified (orphan from ChatAgent constructor change) without explicit test-modification authorization in the ticket body. Fix: add authorization text to the ticket body: "ChatAgentTest.java updated to pass the new AutoCompressTrigger constructor parameter to TestChatAgent — no assertion changes."
