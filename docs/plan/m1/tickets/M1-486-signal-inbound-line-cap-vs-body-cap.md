---
id: M1-486
title: "Signal inbound line cap equals body cap, collapsing two layers"
status: done
created: 2026-06-27
last_updated: 2026-06-29
blocked_by: []
files_budget: 4
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Changing the body-cap value or the OversizeDm metric/handling itself — only the line cap is decoupled so it no longer pre-empts the body cap."
acceptance:
  - >-
    The Signal inbound JSON-RPC line cap (SignalJsonRpcClient.java:122,
    MAX_INBOUND_LINE_CHARS) is sized independently of and above the body cap
    (SignalMessageCodec.java:63, MAX_INBOUND_TEXT_BYTES) so an ASCII body near
    the body cap is NOT line-dropped before reaching the body-cap / OversizeDm
    path. The two layers (transport-line framing vs message-body budget) are no
    longer the same constant.
  - >-
    A test feeds a body just under the body cap whose enclosing JSON-RPC line
    exceeds the old shared cap and asserts it is processed through the body-cap
    path (OversizeDm metric where applicable), not silently dropped at the line
    layer.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundLineCapTest.java"
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
      files: 5
      added: 132
      removed: 18
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-29
    verdict: CLEAN
    base: 4edfef0507abb73cb42cf45a463fbb651bcf3dec
    head: m1/M1-486-signal-inbound-line-cap-vs-body-cap
    verdict_file: docs/plan/m1/redteam/M1-486-2026-06-29.md
    out_of_model_count: 1
    note: |
      Pre-commit audit run despite security_relevant:false, to honor the clarity
      WARN that MAX_INBOUND_LINE_CHARS is an inbound intake/DoS guard. CLEAN: no
      gap between the threat model and the diff. One out-of-model robustness note
      (control-char bodies escaping to 6x can still exceed the 4x line cap) —
      advisory only, not a defended-promise gap; no follow-up ticket recommended.
clarity_check:
  date: 2026-06-29
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: MAX_INBOUND_LINE_CHARS is an inbound-message intake guard; consider setting security_relevant: true even though the change is a correctness fix, so the redteam pass is triggered automatically."
  blockers: []
---

# M1-486: Signal inbound line cap equals body cap, collapsing two layers

## Context

From `/deep-code-review full` (2026-06-27), report
`09-main-infochat-messaging-adapter-01.md#F1` (medium, verified at source).
`MAX_INBOUND_LINE_CHARS = 16_384` (`SignalJsonRpcClient.java:122`) equals
`MAX_INBOUND_TEXT_BYTES = 16_384` (`SignalMessageCodec.java:63`). Because the
JSON-RPC envelope line is always longer than the body it wraps, an ASCII body
near the cap is dropped at the line layer before it can reach the body-cap /
`OversizeDm` path — contradicting the javadoc claim that the two are
independent. The two caps must be sized as two distinct layers.

## Acceptance

See frontmatter. Decouple the line cap from the body cap (line cap > body cap)
so the body-cap path is reachable; cover with a test.

## Out-of-scope

See frontmatter. Body-cap value and OversizeDm handling unchanged.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 09#F1.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-486-*.md
```
