---
id: M1-491
title: "Log-sanitization hardening: relay NOTICE control-strip + SafeLog bidi/line-sep"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
