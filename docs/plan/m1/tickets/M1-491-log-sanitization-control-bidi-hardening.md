---
id: M1-491
title: "Log-sanitization hardening: relay NOTICE control-strip + SafeLog bidi/line-sep"
status: done
created: 2026-06-27
last_updated: 2026-06-29
blocked_by: []
files_budget: 5
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "Changing the log format/levels or any non-log-sanitization behavior."
acceptance:
  - >-
    SafeLog.stripControls also neutralizes the bidi-override and line/paragraph
    separator codepoints that currently pass through (it filters only c < 0x20
    and 0x7F..0x9F at SafeLog.java:35-43, so U+202E, U+2028, U+2029 slip past);
    after the fix a string containing those codepoints is sanitized.
  - >-
    The Nostr relay NOTICE text is run through SafeLog.stripControls before
    logging (NostrRelayConnection.java:346-347 currently logs notice.message()
    raw), matching the control-stripping applied at the other relay-byte log
    sites.
  - >-
    Tests assert SafeLog neutralizes U+202E/U+2028/U+2029 and that a relay NOTICE
    carrying control/bidi codepoints is logged sanitized.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-core/src/test/java/app/zcat/infochat/core/log/SafeLogBidiTest.java"
  modifies:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnectionTest.java — assert NOTICE logging is control-stripped (assertion added, none removed); if no such test class exists, add one as a new file within budget."
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-29
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 196
      removed: 22
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-29
    verdict: CLEAN
    base: 98944d1bf32589952c249585a048e45df7430a90
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-491-2026-06-29.md
    out_of_model_count: 1
    note: >-
      CLEAN, no findings. One OUT-OF-MODEL: SafeLog.stripControls covers only
      U+202E among bidi reorderers (plus U+2028/U+2029); U+202A-U+202D and
      U+2066-U+2069 still pass through and can forge the same visual line
      reordering. Out-of-model (security.md makes no bidi-sanitization promise)
      and beyond M1-491's named codepoints — recommended as a follow-up
      ticket, not folded into this commit.
clarity_check:
  date: 2026-06-29
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-491: Log-sanitization hardening: relay NOTICE control-strip + SafeLog bidi/line-sep

## Context

From `/deep-code-review full` (2026-06-27), reports `06-main-infochat-core-00.md#F1`
and `05-main-infochat-collector-03.md#F1` (verified at source) — two
log-injection hardening gaps sharing one topic. **06#F1:** `SafeLog.stripControls`
strips ISO control ranges but lets bidi-override (U+202E) and line/paragraph
separators (U+2028/U+2029) through, so log lines can still be visually spoofed or
split. **05#F1:** the relay `NOTICE` text is logged raw (`notice.message()`,
`NostrRelayConnection.java:346-347`) while every other relay-byte log site
applies `SafeLog.stripControls` — a control-injection vector from untrusted
relay input (bites when debug logging is on).

## Acceptance

See frontmatter. Widen `SafeLog.stripControls` to cover bidi/line-separator
codepoints and route the relay NOTICE through it; cover both.

## Out-of-scope

See frontmatter. No log format/level change.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 06#F1 + 05#F1.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-491-*.md
```
