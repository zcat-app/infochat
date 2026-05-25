---
id: M1-070
title: Chat agent tool instructions parameter alignment + final-call strip
status: ready
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
  - "The comment on the final-call path accurately describes the code's behavior (no misleading 'without tool instructions' comment when tool instructions are still present). Code inspection."
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
  - docs/spec/security.md §Chat-mode tool surface
  - docs/spec/commands.md §Chat mode
decision_refs: []
---

## Context

`ChatAgent.TOOL_INSTRUCTIONS` tells the LLM that `searchPosts` takes `{query}`, `recallMemory` takes `{query}`, and `listSaves` takes `{limit}`. The actual tool implementations read `{tags, window, limit}`, `{keywords}`, and `{tags, window}` respectively. This makes the entire chat-mode filtered-query surface non-functional — the LLM emits parameters the tools ignore.

Additionally, the final LLM call (after max iterations) claims in a comment to strip tool instructions but actually passes the augmented system prompt including them. This wastes tokens and allows the LLM to emit tool-call patterns in the final response.

## Fix approach

1. Update `TOOL_INSTRUCTIONS` to match the actual tool parameter names from each tool's `parseArgs` implementation.
2. Thread the base system prompt (without TOOL_INSTRUCTIONS) into the tool loop, and use it for the final call.
3. Update the comment to match the actual behavior.
