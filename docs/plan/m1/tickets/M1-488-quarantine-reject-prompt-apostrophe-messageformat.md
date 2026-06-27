---
id: M1-488
title: "quarantine_reject prompt apostrophe breaks its own {0} MessageFormat token"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 3
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Rewriting the prompt text beyond the apostrophe escaping needed to restore {0} substitution; no other bundle keys."
acceptance:
  - >-
    The bundles/en.properties quarantine_reject value (line 521) renders with its
    {0} timeout token substituted: the unescaped apostrophe ("system's") is
    escaped per MessageFormat rules ('') so it no longer opens a literal region
    that swallows {0}. After the fix MessageFormat.format(value, timeout) yields
    the timeout value in place of {0}, not the literal "{0}".
  - >-
    The test that currently masks this (it builds its expected value by
    re-running the same MessageFormat.format, so it passes regardless) is
    corrected to assert against the literal expected rendered string with the
    timeout substituted, so a future apostrophe regression fails loudly.
  - "mvn -B verify is green from the repo root."
test_plan:
  modifies:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java — the quarantine_reject rendering assertion is changed from a self-referential MessageFormat.format expectation to a literal expected string with {0} substituted."
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

# M1-488: quarantine_reject prompt apostrophe breaks its own {0} MessageFormat token

## Context

From `/deep-code-review full` (2026-06-27), report
`16-main-infochat-provider-06.md#F1` (medium, verified at source). The
`quarantine_reject` value in `bundles/en.properties:521` contains an unescaped
apostrophe ("system's") and is rendered via `MessageFormat.format`
(`QuarantineCommandHandler.java:338-340`). A lone `'` opens a MessageFormat
literal region to end-of-pattern, so `{0}` is never substituted and renders as
the literal `{0}s`. The existing test masks the defect because it builds its
expected value by re-running the same `MessageFormat.format` — a tautology that
passes either way.

## Acceptance

See frontmatter. Escape the apostrophe (`''`) so `{0}` substitutes, and tighten
the masking test to a literal expectation.

## Out-of-scope

See frontmatter. No other prompt/key edits.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 16#F1 (+ the synthesizer
  note that the test is tautological).
- This pairs the production fix with the test-integrity fix that hid it; both are
  in scope because the test currently guarantees the bug ships silently.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-488-*.md
```
