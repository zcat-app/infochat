---
id: M1-073
title: LlmOutputSanitizer — word-boundary command matching
status: ready
created: 2026-05-25
last_updated: 2026-05-25
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerAuditRowIT.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
source: deep-code-review full-2026-05-25-1901 (07-module-infochat-provider.md#F5)
out_of_scope:
  - any change to the CLOSED_LIST of command tokens — only the matching regex is fixed
  - any new sanitizer rule or category
  - any ChatAgent change
acceptance:
  - "Sanitizer matches '/ban' followed by whitespace, end-of-string, or non-command-name character (not letters/digits/hyphens). Verify: LlmOutputSanitizerTest.matchesBanFollowedBySpace passes"
  - "Sanitizer does NOT match '/ban' when it is a substring of a longer word like '/bandwidth' or '/banning'. Verify: LlmOutputSanitizerTest.doesNotMatchBanInsideLongerWord passes"
  - "Same word-boundary rule applies to all tokens in CLOSED_LIST (/lang does not match /language, /audit does not match /auditing). Verify: LlmOutputSanitizerTest.noSubstringFalsePositives passes"
  - "Sanitizer still correctly redacts standalone command tokens at end of string. Verify: LlmOutputSanitizerTest.matchesTokenAtEndOfString passes"
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - LlmOutputSanitizerTest.doesNotMatchBanInsideLongerWord (new)
    - LlmOutputSanitizerTest.noSubstringFalsePositives (new)
    - LlmOutputSanitizerTest.matchesTokenAtEndOfString (new)
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
decision_refs: []
---

## Context

`LlmOutputSanitizer` uses `Pattern.quote(token)` to match command tokens like `/ban`, `/lang`, `/audit`. This matches them as substrings, so benign LLM output containing "/bandwidth" or "/language" gets falsely redacted. The security intent is to match command invocations, not arbitrary substrings.

## Fix approach

Add a lookahead `(?=\s|$|[^a-z0-9-])` after the quoted token so it only matches when followed by a word boundary (whitespace, end-of-string, or a character that cannot be part of a command name).
