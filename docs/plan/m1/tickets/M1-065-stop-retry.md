---
id: M1-065
title: /stop cancellation + /retry anchor-based replay
status: pending
created: 2026-05-24
last_updated: 2026-05-24
blocked_by:
  - M1-063
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/StopCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/CancellationService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/SummaryAnchorRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/InFlightTracker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/StopCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/CancellationServiceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/SummaryAnchorRepositoryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterStopRetryIT.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any /retry --digest handling — T2-F territory (periodic group digests); this ticket implements personal /retry only (command_kind = 'personal')
  - any periodic group digest scheduler or summary_cache — T2-F territory
  - any modification to the chat agent loop beyond hooking the cancellation signal — M1-063 owns the loop
  - any /clear, /compress handler — M1-064 territory
  - any /forget, /export handler — M1-066, M1-067 territory
  - any new migration — summary_anchor table exists from M1-061
  - any group-scope @mention dispatch — T2-F territory
  - any modification to existing CommandHandler implementations outside files_scope
acceptance:
  - "StopCommandHandler.java exists, implements CommandHandler with commandName() returning 'stop'. It cancels the calling (user, scope)'s currently in-flight interruptible request via CancellationService. Verify: StopCommandHandlerTest.cancelsInFlightChatRequest passes"
  - "/stop cancels a pending confirmation as a side effect (spec §/stop — 'is also the cancel verb for a pending destructive-command confirmation'). Verify: StopCommandHandlerTest.cancelsPendingConfirmation passes"
  - "/stop is idempotent — no-op with a friendly reply when nothing is in flight and no confirmation is pending. Verify: StopCommandHandlerTest.idempotentWhenNothingInFlight passes"
  - "CancellationService.java closes the in-flight LLM stream and issues pg_cancel_backend(pid) on any in-flight tool-call DB connection (spec §/stop cancellation primitive). Best-effort — the worker discards the result regardless. Verify: CancellationServiceTest.closesStreamAndCancelsPgBackend passes"
  - "Every interruptible read-only query runs under a profile-driven statement_timeout (spec §/stop: 'bounds the worst case even when pg_cancel_backend fails'; values in docs/design/03-commands.md §3.9). Verify: CancellationServiceTest.statementTimeoutApplied passes"
  - "SummaryCommandHandler.java is modified to write a summary_anchor row (command_kind = 'personal') after a successful /summary run. The anchor captures the frozen UID set, cluster mapping, command name, and arg hash (D19, D36). Any non-/retry input from the same (user, scope) clears the anchor. Verify: SummaryAnchorRepositoryTest.writesAndClearsAnchor passes"
  - "RetryCommandHandler.java exists, implements CommandHandler with commandName() returning 'retry'. It reads the summary_anchor for the calling (user, scope), re-runs the LLM stage with the frozen post selection, and delivers the re-generated prose. Verify: RetryCommandHandlerTest.retriesFromAnchor passes"
  - "/retry enforces the profile-driven retry cap (spec §/retry — retry cap table in docs/design/03-commands.md §3.9). When exhausted, a friendly error is returned and the anchor is left intact. Verify: RetryCommandHandlerTest.rejectsWhenCapExhausted passes"
  - "/retry filters the frozen UID set against current post.status at retry time — UIDs no longer READY are excluded. If the filtered set drops to empty, a friendly error is returned and no LLM call is made (spec §/retry — Status filter). Verify: RetryCommandHandlerTest.filtersNonReadyUids passes"
  - "/retry with no eligible anchor, a cleared anchor, or after /stop cancellation returns a friendly error (spec §/retry — no effect cases). Verify: RetryCommandHandlerTest.noAnchorReturnsError passes"
  - "/retry passes the re-generated prose through LlmOutputSanitizer before delivery (spec §LLM output sanitizer — 'applies to /retry re-rolls'). Verify: RetryCommandHandlerTest.outputPassesThroughSanitizer passes"
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/StopCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/CancellationServiceTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/SummaryAnchorRepositoryTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterStopRetryIT.java
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
  - docs/spec/schema.md §Per-scope state
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/security.md §LLM output sanitizer
  - docs/design/03-commands.md §3.9 Conversation control
  - docs/design/02-schema.md §2.6.5 `summary_anchor`
decision_refs:
  - D19
  - D31
  - D35
  - D36
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-065: /stop cancellation + /retry anchor-based replay

## Context

`/stop` and `/retry` are the two conversation-control commands that interact
with in-flight state. `/stop` cancels a running chat-mode reply, `/summary`,
or `/retry` re-roll (D35). `/retry` replays the prose layer of the last
summary-producing command using the frozen post selection and cluster mapping
stored in `summary_anchor` (D19, D36). Both require the in-flight tracking
infrastructure introduced in M1-063 and the `summary_anchor` table from
M1-061.

This ticket also modifies `SummaryCommandHandler` to write the anchor row
after a successful `/summary` — that is the prerequisite for `/retry` to
have anything to replay.

## Acceptance

See the YAML `acceptance:` list above. In summary:

1. **`/stop`** cancels in-flight interruptible requests (LLM stream close +
   `pg_cancel_backend`), cancels pending confirmations, is idempotent.
2. **CancellationService** handles the mechanics of stream closure and
   backend cancellation with a profile-driven `statement_timeout` safety net.
3. **SummaryCommandHandler** writes a `summary_anchor` row on success; any
   non-`/retry` input from the same `(user, scope)` clears it.
4. **`/retry`** reads the anchor, filters the frozen UID set against current
   status, re-runs the LLM, enforces the retry cap, and sanitizes output.
5. `mvn verify` is green.

## Out-of-scope

- **No `/retry --digest`.** T2-F territory. This ticket implements personal
  `/retry` only (`command_kind = 'personal'`). The `--digest` flag, per-group
  serialization, and digest-anchor routing are T2-F's responsibility.
- **No periodic digest scheduler or `summary_cache`.** T2-F.
- **No `/clear` or `/compress`.** M1-064.

## Notes

- The anchor-clear-on-non-retry-input logic must be wired into InboundRouter
  or ChatAgent so that any user action (command or chat message) clears the
  personal anchor. The implementation choice (InboundRouter step vs. ChatAgent
  hook) is design-tier.
- The retry cap values: `laptop` 3, `vps` 3, `pi` 2, `remote-llm` 5
  (from `docs/design/03-commands.md` §3.9).
- The status-drift threshold: 25% across all profiles.
- `pg_cancel_backend` is best-effort — the worker discards the in-flight
  result regardless of whether Postgres completes the query before the
  cancel takes effect.
- Adjacent pattern: `SummaryCommandHandler` for the existing LLM-call +
  degraded-fallback pattern that `/retry` reuses.
- Authorized test changes to `SummaryCommandHandlerTest.java`: add
  `SummaryAnchorRepository` dependency wiring (mock/stub); assert anchor
  row is written on successful `/summary`; assert no anchor write on
  degraded-fallback path. Existing test assertions must remain green.
- `/stop` during probation: allowed, returns the idempotent no-op (probation
  users can't have in-flight LLM jobs since chat mode and `/retry` are
  blocked).
