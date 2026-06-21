---
id: M1-425
title: "collector: drop over-matching bare function in Stage 1 tool-call regex"
status: done
created: 2026-06-21
last_updated: 2026-06-22
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1RegexSet.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1RegexSetTest.java
  - docs/design/04-security.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The other six Stage 1 rules (ignore-previous-instructions, role-redefinition, impersonation-prefix, secrets-leak, html-comment-hide, delimiter-injection) — unchanged.
  - The bare `tool` alternative (matches `tool:` / `tool(`) — it is design-pinned in docs/design/04-security.md §4.2 step 3; narrowing it is a separate spec decision, not this drift fix.
  - The rule_id string `stage1.tool_call_simulation` — stable audit key, unchanged (renames are spec-amendment-class per the Stage1RegexSet javadoc).
  - The Stage 1 match semantics (scrub matched span + record a quarantine row + leave status=RAW for Stage 2) — unchanged; only WHAT matches narrows.
acceptance:
  - "Stage1RegexSet RULE_TOOL_CALL_SIMULATION pattern drops the bare `function` alternative, keeping only the `function[_-]?call`, `tool[_-]?call`, and `tool` alternatives (the precise reconciled regex is given in the body), so ordinary code-bearing prose like `function(x)` no longer matches."
  - "docs/design/04-security.md §4.2 step 3 tool-call-simulation entry is reconciled with the implementation: it documents `function[_-]?call`, `tool[_-]?call`, and `tool` and does NOT list a bare `function` form."
  - "Stage1RegexSetTest asserts a body containing `function(` (and `function foo()`) does NOT match RULE_TOOL_CALL_SIMULATION, while `function_call:`, `function-call(`, `tool_call:`, and `tool:` still DO match."
  - "All other Stage1 tests (Stage1PipelineIT, Stage1RegexSetTest existing cases) remain green; mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1RegexSetTest.java (function( no-match + injection-form still-matches cases)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Ingest pipeline
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-21
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 26
      removed: 12
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-21
    verdict: CLEAN
    base: 72d1f0280009a7de603811f2b3cabcb1afcc486c
    head: WORKING-TREE
    verdict_file: docs/plan/m1/redteam/M1-425-2026-06-21.md
    out_of_model_count: 0
    note: |
      Pre-commit --in-progress audit. CLEAN, no findings, no out-of-model
      observations. The change narrows the Stage 1 tool-call-simulation regex
      (removes the over-broad bare `function` alternative) and reconciles the
      design note; it removes match coverage rather than widening attack surface,
      and leaves the scrub+quarantine+RAW Stage 1 semantics untouched.
clarity_check:
  date: 2026-06-21
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-425: drop over-matching bare `function` in Stage 1 tool-call regex

## Context

Deep-review full (2026-06-21) collector finding **F2**, reframed after
verify-at-source 2026-06-21 as an implementation-vs-design drift.

`docs/design/04-security.md` §4.2 step 3 pins the tool-call-simulation patterns
verbatim as `\bfunction[_-]?call\s*[:(]` and `\btool\s*[:(]`. The implementation
(`Stage1RegexSet.java:148-152`) is
`\b(?:function[_-]?call|tool[_-]?call|tool|function)\s*[:(]` — it adds two
alternatives the design does not list:

- `tool[_-]?call` — benign (adds `tool_call:` / `tool-call:` coverage that the
  design-pinned bare `tool` misses, since `\btool` cannot consume the `_call`
  before `[:(]`); worth keeping and documenting.
- bare `function` — the defect: `\bfunction\s*[:(]` matches `function(`, which
  appears in virtually any post quoting JavaScript/code. A Stage 1 match scrubs the
  matched span (replacing it with a placeholder) and records a `quarantine` row
  (`Stage1Pipeline` — "scrubs and routes to review", status stays RAW). So every
  code-bearing feed post gets its `function(` mangled and a spurious quarantine row
  recorded — content corruption plus quarantine-table noise, partially defeating
  Stage 1's coarse-filter purpose. The design never sanctioned this alternative.

The fix is a drift correction, not a new design decision: narrow the impl to the
injection-relevant forms and reconcile the design note to document the
`tool[_-]?call` form already in use. The design-pinned bare `tool` is intentional
and left as-is (out of scope).

## Acceptance

See frontmatter. The shape: remove the bare `function` alternative from the
RULE_TOOL_CALL_SIMULATION pattern, update `04-security.md` §4.2 step 3 to match the
implemented set, and pin the new behavior with `Stage1RegexSetTest` cases.

## Out-of-scope

See frontmatter. Only the tool-call-simulation pattern and its design entry change;
the rule_id, the match semantics, and the other six rules are untouched. The bare
`tool` over-match is design-pinned and deliberately left for a separate decision.

## Notes

- This touches both code and a design note (the design pins the regex verbatim), so
  per CLAUDE.md commit-prefix rules it is a ticket, not a `spec:` doc edit.
- `function[_-]?call` already covers `functioncall:` / `function_call:` /
  `function-call(`; dropping bare `function` loses only the over-broad `function(`
  match, which is the goal.
