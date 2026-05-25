---
id: M1-068
title: Chat agent redteam hardening (M1-063 remediation)
status: done
created: 2026-05-25
last_updated: 2026-05-25
started: 2026-05-25
blocked_by:
  - M1-063
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
remediates: M1-063
out_of_scope:
  - any ChatToolDispatcher or ChatToolRegistry change — M1-062 territory; this ticket wraps tool results, not the tool implementations
  - any ChatPromptBuilder change — the prompt builder already wraps correctly; this ticket wraps the tool-result append path in ChatAgent
  - any AuditAction enum addition or AuditLogWriter change — if a CHAT_MODE action is needed, add it here but do not modify the writer itself
  - any new rate-limit bean or infrastructure — the LLM rate-limit bucket is a cross-cutting concern deferred to a separate ticket; this ticket adds a per-user tool-loop iteration cap only
  - any chat_memory or compression pipeline change — M1-064 territory
acceptance:
  - "Tool results appended to the conversation in ChatAgent.runToolLoop are wrapped in UNTRUSTED_CONTENT delimiter blocks (same pattern as ChatPromptBuilder uses for user messages and memory hits). Verify: ChatAgentTest.toolResultsWrappedInDelimiters passes"
  - "ChatAgent.handle() writes one audit_log row with action CHAT_MODE recording (actor, scope) before the LLM call. No user-authored prose in the audit row. Verify: ChatAgentTest.chatModeIntentIsAuditLogged passes"
  - "The final LLM call after MAX_TOOL_ITERATIONS strips TOOL_CALL patterns from the response before returning to the user. Verify: ChatAgentTest.finalResponseStripsToolCallPatterns passes"
  - "ChatAgent persists the sanitized (post-LlmOutputSanitizer) text to chat_message, not the raw LLM output. The sanitize call runs BEFORE persistTurn. Verify: ChatAgentTest.persistsSanitizedOutput passes"
  - "The chat-mode dispatch path in InboundRouter checks an LLM-triggering rate cap (infochat.chat.llm-rate-cap-per-minute, profile-driven, default 10) before calling ChatAgent.handle(). Exceeding the cap returns a friendly error from the bundle. Verify: InboundRouterChatModeIT.llmRateCapRejectsExcessiveRequests passes"
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java (new test methods)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java (new test method)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/security.md §Failure handling
  - docs/spec/security.md §Rate limiting
  - docs/spec/security.md §Authorization model
decision_refs:
  - D21
  - D43
reviews:
  - round: 1
    date: 2026-05-25
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PARTIAL
    diff_stats:
      files: 11
      added: 261
      removed: 41
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
      files: 11
      added: 272
      removed: 42
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-05-25
    category: DOS
    severity: low
    promise: |
      Per-user token buckets bound LLM-triggering operations — its own bucket, capped lower, profile-driven.
    gap: |
      InboundRouter.java llmCallTimestamps ConcurrentHashMap never evicts entries for inactive users.
    repro: |
      Over extended deployment, map accumulates permanent entries for every user who ever sent a chat message.
    suggested_fix_class: other
  - date: 2026-05-25
    category: INFO-LEAK
    severity: low
    promise: |
      Deterministic outbound regex pass strips admin command strings from LLM output.
    gap: |
      ChatAgent.java TOOL_CALL_PATTERN requires JSON body to match; partial tool calls bypass the strip.
    repro: |
      LLM emits "TOOL_CALL: searchPosts" without JSON args after iteration cap; user sees internal protocol.
    suggested_fix_class: input-sanitization
  - date: 2026-05-25
    category: AUDIT-EVASION
    severity: low
    promise: |
      Audit log records intent (command name, actor, scope, target).
    gap: |
      writeAuditRow does not record scopeId; group-scope audit rows are ambiguous across groups.
    repro: |
      User in multiple groups sends chat message; audit row cannot identify which group was targeted.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-05-25
    verdict: FINDINGS
    base: "f553142^"
    head: f553142
    verdict_file: docs/plan/m1/redteam/M1-068-2026-05-25.md
    findings_count: 3
    out_of_model_count: 1
    note: |
      All three findings are low severity. Candidates for a follow-up
      hardening ticket; none block merge.
clarity_check:
  date: 2026-05-25
  verdict: WARN
  warnings:
    - "FILES-BUDGET-PLAUSIBLE: files_scope omits AuditAction.java; if CHAT_MODE is absent from enum, implementer must touch an unlisted file (within budget ceiling of 8)"
---

# M1-068: Chat agent redteam hardening (M1-063 remediation)

## Context

The M1-063 redteam audit (2026-05-25) surfaced 5 findings (2 high, 3
medium) in the chat-mode dispatch path. This ticket remediates all five
in a single pass since the fixes are small and concentrated in
ChatAgent.java + InboundRouter.java. The done commit on M1-063 is
immutable; these fixes land as a separate commit.

## Acceptance

See the YAML `acceptance:` list above. In summary:

1. **Tool result delimiter wrapping** (finding 1, INJECTION/high) — wrap
   tool results in `UNTRUSTED_CONTENT` delimiters before appending to the
   conversation, matching the ChatPromptBuilder pattern.
2. **Audit-log for chat-mode intent** (finding 2, AUDIT-EVASION/high) —
   write one CHAT_MODE audit row before the LLM call.
3. **Strip TOOL_CALL from final response** (finding 3, INFO-LEAK/medium)
   — prevent internal tool protocol from leaking to the user after max
   iterations.
4. **Persist sanitized output** (finding 5, INFO-LEAK/medium) — swap
   sanitize/persist order so admin commands never enter the DB.
5. **LLM rate cap** (finding 4, DOS/medium) — add a simple per-user
   rate check in InboundRouter before ChatAgent dispatch.

## Out-of-scope

- The cross-cutting `LlmRateCapBucket` bean that the spec envisions for
  all LLM-triggering paths (chat, summary, retry). This ticket adds a
  lightweight per-user check in the router only; the shared-bucket
  infrastructure is a follow-up.
- Any modification to ChatToolDispatcher, ChatToolRegistry, or the tool
  implementations.
- Any modification to the memory compression pipeline (M1-064).

## Notes

- Findings 1, 3, 4, 5 are ~15 lines of change in ChatAgent.java.
- Finding 2 needs a CHAT_MODE entry in AuditAction — check if M1-041
  already defined one; if not, add it (1 line).
- Finding 4's full fix (shared LLM bucket) spans /summary and /retry
  too; this ticket adds a simpler per-user cap in the router to close
  the immediate gap.
- Full redteam report: `docs/plan/m1/redteam/M1-063-2026-05-25.md`.
