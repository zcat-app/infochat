---
id: M1-070
title: Chat agent tool instructions parameter alignment + final-call strip
status: done
created: 2026-05-25
last_updated: 2026-05-25
blocked_by: []
files_budget: 4
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolDispatcherTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
source: deep-code-review full-2026-05-25-1901 (07-module-infochat-provider.md#F2, 07-module-infochat-provider.md#F4)
out_of_scope:
  - any ChatToolDispatcher or ChatToolRegistry structural change — only the TOOL_INSTRUCTIONS string and final-call prompt are fixed
  - any new tool implementation — existing tools' parameter parsing is already correct; this ticket aligns the LLM prompt to match
  - any chat memory or compression change — M1-064 territory
  - any InboundRouter routing change — M1-069 territory
acceptance:
  - "TOOL_INSTRUCTIONS describes searchPosts with parameters {tags, window, limit} matching SearchPostsTool's actual parsing. Verify: ChatAgentTest.toolInstructionsMatchSearchPostsParams passes"
  - "TOOL_INSTRUCTIONS describes recallMemory with parameter {keywords} matching RecallMemoryTool's actual parsing. Verify: ChatAgentTest.toolInstructionsMatchRecallMemoryParams passes"
  - "TOOL_INSTRUCTIONS describes listSaves with parameters {tags, window} matching ListSavesTool's actual parsing. Verify: ChatAgentTest.toolInstructionsMatchListSavesParams passes"
  - "The final LLM call after MAX_TOOL_ITERATIONS uses the base system prompt WITHOUT tool instructions appended. Verify: ChatAgentTest.finalCallOmitsToolInstructions passes"
  - "The comment on the final-call code path in ChatAgent.java matches the actual behavior (base prompt without tool instructions). Verify: the ChatAgent.java diff shows comment and code are consistent on the final-call path"
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - ChatAgentTest.toolInstructionsMatchSearchPostsParams (new)
    - ChatAgentTest.toolInstructionsMatchRecallMemoryParams (new)
    - ChatAgentTest.toolInstructionsMatchListSavesParams (new)
    - ChatAgentTest.finalCallOmitsToolInstructions (new)
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/commands.md §Chat mode
decision_refs: []
clarity_check:
  date: 2026-05-25
  verdict: WARN
  warnings:
    - "FILES-BUDGET-PLAUSIBLE: ChatToolDispatcherTest.java and InboundRouterChatModeIT.java in files_scope but not referenced by acceptance items"
    - "SECURITY-FLAG-CONSISTENT: final-call strip is LLM call-site wiring per security.md; consider security_relevant: true"
  blockers: []
revisions:
  - date: 2026-05-25
    reason: clarity-fail refine
    changes:
      - "Acceptance item 5: replaced 'Code inspection.' with diff-checkable assertion"
      - "spec_refs: fixed anchor from §Chat-mode tool surface to §Prompt-injection defenses (LLM call sites)"
escalations:
  - date: 2026-05-25
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      BLOCKERS:
        1. ACCEPTANCE-RUNNABLE item 5: "Code inspection." is not a testable acceptance criterion.
        2. SPEC-REFS-VALID: docs/spec/security.md §Chat-mode tool surface does not exist.
reviews:
  - round: 1
    date: 2026-05-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 117
      removed: 27
---

## Context

`ChatAgent.TOOL_INSTRUCTIONS` tells the LLM that `searchPosts` takes `{query}`, `recallMemory` takes `{query}`, and `listSaves` takes `{limit}`. The actual tool implementations read `{tags, window, limit}`, `{keywords}`, and `{tags, window}` respectively. This makes the entire chat-mode filtered-query surface non-functional — the LLM emits parameters the tools ignore.

Additionally, the final LLM call (after max iterations) claims in a comment to strip tool instructions but actually passes the augmented system prompt including them. This wastes tokens and allows the LLM to emit tool-call patterns in the final response.

## Fix approach

1. Update `TOOL_INSTRUCTIONS` to match the actual tool parameter names from each tool's `parseArgs` implementation.
2. Thread the base system prompt (without TOOL_INSTRUCTIONS) into the tool loop, and use it for the final call.
3. Update the comment to match the actual behavior.
