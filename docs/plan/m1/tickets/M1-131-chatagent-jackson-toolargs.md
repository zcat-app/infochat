---
id: M1-131
title: "ChatAgent Jackson tool-arg parse + dispatcher catch widening + TOOL-LEAK"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - the sanitizer pattern caching / closed-list whitespace (covered by M1-154)
  - unbounded conversation growth bound (a separate FIX-LOW; may be noted but not required here)
  - any LLM SPI change
acceptance:
  - "parseToolArgs/splitTopLevel/TOOL_CALL_PATTERN are replaced with a Jackson ObjectMapper parse (already on the classpath) that produces typed List<String> for array args, nested objects, and escaped quotes"
  - "A test drives recallMemory end-to-end with a keywords array and asserts it works (previously ClassCastException); searchPosts/listSaves with a tags array also pass"
  - "ChatToolDispatcher translates type/parse failures (ClassCastException, DateTimeParseException, NumberFormatException) at the dispatch boundary into a ValidationError the LLM can self-correct on, rather than letting them escape to ERROR_CHAT_UNAVAILABLE"
  - "A malformed multi-line TOOL_CALL fragment no longer leaks to the user (balanced-brace extraction / DOTALL-safe strip)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/commands.md §Chat mode
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-131: ChatAgent Jackson tool-arg parse + dispatcher catch widening + TOOL-LEAK

## Context

The hand-rolled `parseToolArgs`/`splitTopLevel`/`TOOL_CALL_PATTERN` in
`ChatAgent` only emits `String`/`Integer`: a `["bitcoin"]` value is stored as a
raw `String`, and the consuming tools cast `(List<String>)` → `ClassCastException`.
`recallMemory` is entirely broken; tag-filtered `searchPosts`/`listSaves` always
fail; no test covers the array path. Two more defects in the same parser: the
reluctant `\{.*?\}` truncates nested JSON, and the one-char escape check
mis-flips `inQuote` on `\\\"`. The dispatcher catches only
`IllegalArgumentException`/`SQLException`, so the `ClassCastException` and the
`DateTimeParseException` from `Duration.parse` escape the turn as the generic
chat-unavailable error, denying the model a structured retry signal. The
residual malformed-multi-line TOOL_CALL leak (D-TOOL-LEAK) dissolves under the
same Jackson + balanced-brace rewrite.

## Acceptance

See frontmatter. Replace the parser with `ObjectMapper.readTree`; coerce/validate
arg types at the dispatcher boundary; make the strip DOTALL/balanced-brace aware.

## Out-of-scope

See frontmatter. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A8 + §A8b + §D-TOOL-LEAK;
  `opus-47-full-handout.md` §F-SEC-11, F-MAINT-13, F-MAINT-21; `opus-48-audit-handout.md` §A2, A3, D7, D8.
- Loci: `ChatAgent.java:251-305`, `TOOL_CALL_PATTERN:43`; consumers
  `SearchPostsTool.java:45-46`, `RecallMemoryTool.java:38-39`, `ListSavesTool.java:44-45`;
  dispatcher `ChatToolDispatcher.java:137-145`.
- Plan-writer pass recommended — four reporters disagree on which subset to fix
  first; the Jackson rewrite is the single move that covers all of them.
