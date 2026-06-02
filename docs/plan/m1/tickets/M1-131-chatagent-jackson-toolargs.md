---
id: M1-131
title: "ChatAgent Jackson tool-arg parse + dispatcher catch widening + TOOL-LEAK"
status: done
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
  - "ChatToolDispatcher length/size validation recurses into nested Map and List values so the per-input length cap and list-size cap are enforced at the dispatch boundary for every value shape the Jackson parser can produce; a nested oversized string or oversized nested list yields a ValidationError before clampLimit, tool.execute, or any SQL (closes redteam DOS finding 2026-06-02)"
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
reviews:
  - round: 1
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 404
      removed: 67
  - round: 2
    date: 2026-06-02
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 604
      removed: 86
  - round: 2
    date: 2026-06-02
    verdict: OVERRIDE-APPROVE
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    override_ref: 0
overrides:
  - date: 2026-06-02
    objection: |
      SCOPE-DRIFT-CHECK: FAIL (round-N must-shrink). Round 2 grew along all three
      dimensions vs round 1 (files 6→7, +404→+604, −67→−86); round 1 was an
      APPROVE not a REWORK, so the must-shrink prior-REWORK exception does not
      cover this growth. "The new code itself (recursive validateValue + tests)
      is correct and in scope; the only blocker is the must-shrink dimension."
    user_justification: |
      Override approved by operator. The must-shrink rule is a rework-convergence
      heuristic for REWORK loops; it structurally cannot be satisfied by a
      redteam-finding refine on an already-APPROVED ticket, because the redteam
      process itself mandates new artifacts the round-1 diff never contained (the
      audit file docs/plan/m1/redteam/M1-131-2026-06-02.md forces files 6→7, and
      the redteam_findings/redteam_audits/escalations/revisions frontmatter adds
      ~90 lines) — independent of any code. Satisfying must-shrink here would
      require deleting correct, in-scope test coverage or the audit trail, which
      the engineering rules forbid. The reviewer passed every substantive check
      (acceptance, spec-conformance, test-integrity, out-of-scope, negative-space)
      and confirmed the round-2 code is correct and in scope; the sole blocker is
      the numeric must-shrink dimension, which is a false positive for a bounded
      single-feature addition (recursive Map/List validation, acceptance item 5).
      Not a TEST-INTEGRITY override.
aborted_attempts: []
reopens: []
escalations:
  - date: 2026-06-02
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RED-TEAM VERDICT: FINDINGS — DOS (low): ChatToolDispatcher.validateInputLengths
      does not recurse into nested Map values the Jackson parser can now produce;
      the spec-committed pre-SQL length cap is enforced only incidentally by the
      downstream type cast. Full verdict: docs/plan/m1/redteam/M1-131-2026-06-02.md
  - date: 2026-06-02
    reason: round-cap
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL (round-N must-shrink). Round 2 grew along all three
      dimensions vs round 1 (files 6→7, +404→+604, −67→−86), and round 1 was an
      APPROVE not a REWORK, so the must-shrink prior-REWORK exception does not
      cover this redteam-finding refine. All other checks PASS; the reviewer notes
      "the new code itself (recursive validateValue + tests) is correct and in
      scope; the only blocker is the must-shrink dimension." Recommended: operator
      override (must-shrink misfired on a legitimate redteam-refine scope add).
revisions:
  - date: 2026-06-02
    reason: redteam-finding (round-1 refine)
    note: |
      Added acceptance item 5 (dispatcher recursive length/size validation) to
      close the low-severity DOS defense-in-depth finding: a nested-Map value
      shape newly producible by the Jackson parser bypassed validateInputLengths.
      Acceptance grew from 5 to 6 items. Round-1 review APPROVE preserved under
      reviews[0]; this refine returns the ticket to in-progress for round 2.
redteam_findings:
  - date: 2026-06-02
    category: DOS
    severity: low
    promise: |
      All free-form string and list inputs across every tool below are
      length-bounded by a profile-driven cap; a call exceeding the cap is
      rejected by the tool dispatcher before any SQL runs and the LLM sees a
      typed validation-error reply. Every argument is type-checked and bound to
      enums, validated ranges, or length caps before the underlying SQL runs.
    gap: |
      The rewritten parseToolArgs/toJavaValue now produces a nested
      Map<String,Object> value shape for JSON objects that the old flat parser
      never emitted. ChatToolDispatcher.validateInputLengths only inspects
      top-level String values and String elements of a List; a top-level Map
      value falls through unchecked, so the dispatcher's pre-SQL length cap is
      not enforced for that shape. No SQL exposure today: every v1 tool casts to
      List<String>/String/Number, so a nested-map argument throws
      ClassCastException, which the widened catch converts to ValidationError
      before SQL. The bound is thus enforced incidentally by the downstream cast,
      not by the dispatcher as the spec commits.
    repro: |
      A prompt-injected post coaxes the agent to emit
      TOOL_CALL: recallMemory {"keywords":{"x":"AAAA…(50KB)…"}}. parseToolArgs
      builds {keywords -> Map{x -> 50KB String}}; validateInputLengths sees a Map,
      matches neither the String nor List branch, returns null (passes). The 50KB
      string is never length-checked by the dispatcher; today the (List<String>)
      cast in RecallMemoryTool aborts the call, but the spec-committed pre-SQL
      length gate did not fire.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-06-02
    verdict: FINDINGS
    base: 22c1077 (fork point / merge-base with main)
    head: working tree (M1-131 implementation, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-131-2026-06-02.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      One low-severity DOS defense-in-depth gap: validateInputLengths does not
      recurse into the nested-Map value shape the new Jackson parser can produce.
      No current SQL exposure (downstream type casts reject nested maps via the
      widened catch added by this ticket). Disposition: fixed in-ticket via
      redteam-finding refine (acceptance item 5, round 2) on user direction,
      rather than a deferred follow-up. Out-of-model recursion item falsified
      (Jackson StreamReadConstraints max nesting depth 1000).
outline_file: target/m1-tick-outline-M1-131.md
clarity_check:
  date: 2026-06-02
  verdict: WARN
  warnings:
    - "Acceptance item 1 is implementation-descriptive rather than behaviorally testable on its own; items 2-5 cover the behavioral consequences."
  blockers: []
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
