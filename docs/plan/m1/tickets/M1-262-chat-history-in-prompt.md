---
id: M1-262
title: "Chat agent: include conversation history in the prompt"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Compress/Clear/Export handlers — they already read chat_message correctly; behavior unchanged.
  - Auto-compress thresholds, session-cap accounting, and the compress transaction boundary (M1-264 owns those).
  - The chat_memory pre-fetch path — unchanged; history reuses its untrusted-content delimiter convention, not its retrieval logic.
  - System-prompt text changes beyond what history inclusion requires.
acceptance:
  - "ChatPromptBuilder.build assembles recent chat_message turns for the (user, scope) session into the prompt, newest-last, bounded by the existing session token budget; when the budget is exceeded, oldest turns are dropped first. A named ChatPromptBuilder test asserts a prior user/assistant turn appears in the built prompt and that an over-budget session drops oldest-first."
  - "History turns are wrapped in the same untrusted-content delimiters already used for chat_memory pre-fetch hits before inclusion; a named test asserts the delimiters surround history content."
  - "Per-(user, scope) isolation holds: a named test asserts turns from a different user or a different scope of the same user never appear in the built prompt."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
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

# M1-262: Chat agent: include conversation history in the prompt

## Context

Deep-review v4 verified HIGH **H1** (`deep-code-review/v4/UNIFIED-REPORT.md` §1;
source `deep-code-review/v4/fable5/07-module-infochat-provider.md#F1`):
`ChatPromptBuilder.build()` receives only the current `userMessage` plus
`chat_memory` pre-fetch hits — nothing assembles prior `chat_message` turns into
the prompt. The only readers of `chat_message` in the provider are the
Compress/Clear/Export handlers. The system prompt instructs the model to use
"the conversation history" that is never supplied, and the session-cap /
token-count / auto-compress machinery budgets a payload that is never built.
Every chat turn is effectively stateless today.

## Acceptance

See frontmatter. In prose: read recent `chat_message` turns for the session
into the prompt up to the existing token budget (oldest dropped first), wrap
them in the untrusted-content delimiters used for memory hits, and prove
per-(user, scope) isolation with a named test.

## Out-of-scope

The compress arm (transaction boundary, ceiling gate) is M1-264. The
chat_memory pre-fetch retrieval logic is untouched — only its delimiter
convention is reused. Compress/Clear/Export read paths are unchanged.

## Notes

- The token-budget machinery already exists (it was sized for exactly this
  payload); this ticket wires the read path it was budgeting for, it does not
  invent a new budget.
- History is untrusted user content entering an LLM prompt — hence
  `security_relevant: true` and the delimiter requirement. Source post bodies
  are NOT involved here; this is the user's own prior turns plus the
  assistant's replies.
- Check whether a chat_message read DAO already exists for the compress path
  that can be reused before adding a new query.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-262-*.md
```
