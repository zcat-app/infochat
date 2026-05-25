---
id: M1-063
title: Chat-mode dispatch in InboundRouter + session persistence
status: done
created: 2026-05-24
last_updated: 2026-05-25
blocked_by:
  - M1-062
files_budget: 14
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatSessionRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/InFlightTracker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/CommandPermissions.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatSessionRepositoryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/InFlightTrackerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any tool registry or tool implementation change — M1-062 territory; this ticket consumes the registry
  - any /clear, /compress, /stop, /retry, /forget, /export handler — M1-064, M1-065, M1-066, M1-067 territory
  - any auto-compress trigger — M1-064 territory; this ticket persists turns but does not trigger compression
  - any group-scope dispatch or @mention handling — T2-F territory; this ticket wires DM chat-mode only
  - any periodic group digest — T2-F territory
  - any summary_anchor write — M1-065 territory (/retry); the chat agent does NOT write anchors per spec schema §Summary anchor
  - any modification to existing CommandHandler implementations
  - any LLM output sanitizer change — the sanitizer is called, not modified
  - any translation pipeline change — M1-059 territory; chat-mode translation wiring is noted as T2-D territory in M1-059's out_of_scope but this ticket uses the existing TranslationPipeline bean if the scope has a non-en language
acceptance:
  - "InboundRouter.java's non-slash branch no longer returns the static CHAT_MODE_REPLY constant. Instead it dispatches to ChatAgent for the calling (user, scope). The test sends a non-slash message via InMemoryAdapter and asserts the reply is LLM-generated (not the static sentinel). Verify: InboundRouterChatModeIT.chatModeDispatchesToAgent passes"
  - "ChatAgent.java exists as @ApplicationScoped and orchestrates: (1) pre-fetch memory, (2) build prompt with random-marker wrapper, (3) call LLM via CHAT_AGENT ModelTask, (4) dispatch tool calls via ChatToolDispatcher, (5) persist turns in chat_message, (6) sanitize output via LlmOutputSanitizer, (7) run through TranslationPipeline if scope language is non-en. Verify: ChatAgentTest.orchestrationSequenceIsCorrect passes"
  - "ChatSessionRepository.java persists each user turn and assistant turn as chat_message rows, incrementing chat_session.next_seq via the DB trigger. A new session is created (INSERT into chat_session) on first message for a (user, scope) pair. Verify: ChatSessionRepositoryTest.persistsTurnsAndCreatesSession passes"
  - "The chat-mode body cap is enforced BEFORE the message reaches the chat agent. Messages exceeding the profile-driven cap (spec §Input length limits: profile.context_window / 8 chars) receive a friendly error and no LLM call. Verify: InboundRouterChatModeIT.rejectsOversizedChatMessage passes"
  - "InFlightTracker.java enforces at most one in-flight interruptible request per (user, scope). A second chat-mode message while one is in-flight returns a localized 'request already in progress; use /stop to cancel' reply (spec §One in-flight interruptible request per (user, scope)). Verify: InFlightTrackerTest.rejectsConcurrentRequest passes"
  - "When the chat-agent LLM is unreachable, the reply is a localized 'chat assistant is unavailable, try again later' friendly error from the bundle (D43). No chat_session advance, no chat_memory write, no tool invocation (spec §Failure handling — Chat-mode replies). Verify: ChatAgentTest.llmUnreachableReturnsFriendlyError passes"
  - "Probation users (D45) cannot use chat mode — CommandPermissions already blocks the chat-mode sentinel; this ticket does not bypass that gate. Verify: InboundRouterChatModeIT.probationUserBlockedFromChatMode passes"
  - "The chat agent passes the LLM reply through LlmOutputSanitizer before delivery (spec §LLM output sanitizer — 'applies to the full set of LLM-authored output surfaces: chat-mode replies'). Verify: ChatAgentTest.outputPassesThroughSanitizer passes"
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatSessionRepositoryTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/InFlightTrackerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/security.md §Failure handling
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/schema.md §Per-scope state
  - docs/design/03-commands.md §One in-flight interruptible request per (user, scope)
  - docs/design/03-commands.md §Input length limits
decision_refs:
  - D21
  - D24
  - D25
  - D35
  - D43
  - D45
reviews:
  - round: 1
    date: 2026-05-25
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 1132
      removed: 16
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
      files: 15
      added: 1187
      removed: 18
revisions:
  - date: 2026-05-25
    reason: budget-breach refine — adding 5 missing paths to
      files_scope (BundleKeys.java, application.properties,
      en.properties, cs.properties, InboundRouterTest.java) and
      raising files_budget from 10 to 14.
    prior_files_budget: 10
    prior_files_scope:
      - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
      - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
      - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatSessionRepository.java
      - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/InFlightTracker.java
      - infochat-provider/src/main/java/app/zcat/infochat/provider/command/CommandPermissions.java
      - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
      - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatSessionRepositoryTest.java
      - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/InFlightTrackerTest.java
      - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
escalations:
  - date: 2026-05-25
    reason: budget-breach
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL — diff touches 13 non-exempt files but
      ticket declares files_budget: 10 and files_scope of 9 paths.
      Five files outside files_scope: BundleKeys.java,
      application.properties, bundles/cs.properties,
      bundles/en.properties, InboundRouterTest.java.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-05-25
    category: INJECTION
    severity: high
    promise: |
      Every prompt that includes user-derived text is wrapped in a delimiter
      block whose marker contains a per-call random value.
    gap: |
      ChatAgent.java:178-183 — tool results appended to conversation as bare
      text without untrusted-content delimiter wrapping.
    repro: |
      Adversary-controlled RSS post body injected into tool result; LLM sees
      it outside any delimiter wrapper.
    suggested_fix_class: input-sanitization
  - date: 2026-05-25
    category: AUDIT-EVASION
    severity: high
    promise: |
      Authorization evaluation order step 8: audit-log the intent.
    gap: |
      InboundRouter.java:457-479 — chat-mode dispatch has no audit-log write.
    repro: |
      User sends chat-mode message; no audit_log row written for intent.
    suggested_fix_class: audit-log-coverage
  - date: 2026-05-25
    category: INFO-LEAK
    severity: medium
    promise: |
      The LLM tool surface is an internal implementation detail.
    gap: |
      ChatAgent.java:186-190 — final LLM call after MAX_TOOL_ITERATIONS can
      emit raw TOOL_CALL text that leaks to the user.
    repro: |
      Complex query triggers 10 tool iterations; 11th response leaks tool
      protocol to the user.
    suggested_fix_class: input-sanitization
  - date: 2026-05-25
    category: DOS
    severity: medium
    promise: |
      LLM-triggering operations have their own bucket, capped lower.
    gap: |
      InboundRouter.java:458-470 — no LLM-triggering rate limit check.
    repro: |
      60 chat messages/min × 11 LLM calls each = 660 LLM calls/min.
    suggested_fix_class: rate-limit
  - date: 2026-05-25
    category: INFO-LEAK
    severity: medium
    promise: |
      LLM output sanitizer strips admin commands before delivery.
    gap: |
      ChatAgent.java:129-136 — unsanitized LLM output persisted to
      chat_message before sanitizer runs; propagates via memory pipeline.
    repro: |
      LLM emits admin commands; stored unsanitized in DB; compressed into
      memory summaries fed to future prompts.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-05-25
    verdict: FINDINGS
    base: 678641ac
    head: m1/M1-063-chat-mode-dispatch-session-persistence
    verdict_file: docs/plan/m1/redteam/M1-063-2026-05-25.md
    findings_count: 5
    out_of_model_count: 2
    note: |
      Pre-merge audit. 2 high (tool-result delimiter wrapping, audit-log
      coverage), 3 medium (tool protocol leak, LLM rate limit, persist-
      before-sanitize). Done commit is immutable; fixes land as new tickets.
outline_file: target/m1-tick-outline-M1-063.md
clarity_check:
  date: 2026-05-25
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-063: Chat-mode dispatch in InboundRouter + session persistence

## Context

InboundRouter currently returns a static `CHAT_MODE_REPLY` constant for
non-slash input (line 455). This ticket replaces that with real dispatch to
the ChatAgent — the core runtime loop that pre-fetches memory, builds the
prompt, calls the LLM, dispatches tools, persists turns, sanitizes output,
and translates if the scope language is non-en. It also introduces session
persistence (`chat_session` + `chat_message` writes) and the in-flight
request tracker that gates concurrent requests per `(user, scope)`.

This is the ticket that makes chat mode work end-to-end.

## Acceptance

See the YAML `acceptance:` list above. In summary:

1. **InboundRouter** dispatches non-slash input to **ChatAgent** instead of
   returning the static sentinel.
2. **ChatAgent** orchestrates the full loop: pre-fetch → prompt → LLM →
   tool dispatch → persist turns → sanitize → translate.
3. **ChatSessionRepository** persists user and assistant turns as
   `chat_message` rows with session creation on first message.
4. **InFlightTracker** enforces one-in-flight-per-(user,scope).
5. Body cap, probation gate, LLM-unavailable fallback, and sanitizer
   pass-through are all wired and tested.
6. `mvn verify` is green.

## Out-of-scope

- **No /clear, /compress, /stop, /retry.** Those are separate tickets
  (M1-064, M1-065). This ticket persists turns but does not implement the
  commands that manipulate the session.
- **No auto-compress.** M1-064 wires the threshold trigger; this ticket's
  session writes do not check the ceiling.
- **No summary_anchor writes.** Chat-mode tool calls that internally query
  posts do NOT write anchors (spec: chat-mode interactions are not replayable
  via `/retry`).
- **No group-scope dispatch.** T2-F territory.

## Notes

- The `CHAT_MODE_REPLY` constant and its `CHAT_MODE_REPLY` usage at
  InboundRouter line 455 is the exact line being replaced.
- The InFlightTracker is a ConcurrentHashMap keyed by `(userId, scopeKind,
  scopeId)`. It is consumed by both this ticket (chat-mode dispatch) and
  M1-065 (/stop cancellation). It must be a standalone bean so /stop can
  read the in-flight state independently.
- The chat agent loop is LLM-streaming: the LLM reply arrives as a stream
  and tool calls may interleave. The loop terminates when the LLM produces
  a final text reply with no further tool calls.
- Relevant design: `docs/design/03-commands.md` §3.1 (conventions),
  `docs/design/04-security.md` §4.3 (chat agent defenses).
- Adjacent code: `SummaryCommandHandler` for the existing LLM call +
  sanitizer + translation pipeline pattern.

## Round 1 rework

1. **SCOPE-DRIFT-CHECK FAIL**: The diff touches 13 non-exempt files but the
   ticket declares `files_budget: 10` and a `files_scope` of 9 paths. Five
   files outside `files_scope` are legitimately required: `BundleKeys.java`,
   `application.properties`, `bundles/cs.properties`, `bundles/en.properties`,
   and `InboundRouterTest.java`. Resolution: escalate → refine to add the 5
   missing paths to `files_scope` and raise `files_budget` to at least 14.
