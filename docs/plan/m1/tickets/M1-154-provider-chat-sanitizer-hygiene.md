---
id: M1-154
title: "Provider chat/sanitizer hygiene (pattern caching, closed-list whitespace, dispatcher completeness)"
status: done
created: 2026-06-02
last_updated: 2026-06-05
blocked_by:
  - M1-131
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the parseToolArgs Jackson rewrite (M1-131 — this rebases onto it)
  - the InboundRouter bidi/body-cap items (M1-155)
acceptance:
  - "LlmOutputSanitizer compiles its 26 closed-list patterns once into a static final List<Pattern> instead of per call"
  - "Multi-word closed-list entries match internal whitespace as \\s+ (so /invite  create with two spaces does not evade); single-word entries unchanged"
  - "ChatToolDispatcher validates at construction that every system-prompt-advertised tool has a registered handler"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 107
      removed: 15
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-05
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: security_relevant: false may be under-claimed. ChatToolDispatcher touches the LLM tool-call wiring surface and acceptance item 2 patches an evasion in LlmOutputSanitizer. Consider changing to security_relevant: true."
  blockers: []
---

# M1-154: Provider chat/sanitizer hygiene

## Context

Three provider chat/sanitizer hygiene items: (C-SANITIZER-PERF)
`LlmOutputSanitizer` re-compiles 26 patterns per call (the `MARKDOWN_LINK` path
is already cached — the closed-list path is the outlier); (C-CLOSEDLIST-WS)
multi-word closed-list tokens are matched with `Pattern.quote` (literal single
space) so `/invite  create` evades — defense-in-depth only, real authorization
is deterministic Java; (C-CHATTOOL-COMPLETENESS) the dispatcher doesn't validate
its registry against the advertised tools.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. `blocked_by: M1-131` — both touch the chat package /
`ChatToolDispatcher`; rebase onto the Jackson rewrite.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-SANITIZER-PERF, §C-CLOSEDLIST-WS,
  §C-CHATTOOL-COMPLETENESS; `opus-47-full-handout.md` §F-PERF-03, F-SEC-16, F-MAINT-71;
  `opus-47-only-handout.md` §P3.
