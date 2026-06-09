---
id: M1-250
title: "Core lows: Redactor scan/cadence + notifier phantom javadoc"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 5
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java
  - infochat-core/src/test/java/app/zcat/infochat/core/log/RedactorTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The redaction patterns themselves and what Redactor masks — unchanged; T13/T14 are pure performance/cadence cleanups, the masked output is byte-identical.
  - ThrottledAdminNotifier's throttle/notify behavior — unchanged; T28 is a javadoc-only correction.
acceptance:
  - "T13: Redactor scans each pattern once instead of twice (no find() followed by a separate replaceAll over the same input for the same pattern); RedactorTest asserts the redacted output is byte-identical to the pre-change behavior across the existing redaction cases (behavior-preserving optimization)."
  - "T14: InterruptibleCharSequence.charAt checks nanoTime() on a sampled cadence (every Nth char) rather than every char; RedactorTest (or a focused test) asserts the interruption/timeout semantics still hold — a long input is still interruptible — while the per-char nanoTime cost is removed."
  - "T28: the ThrottledAdminNotifier javadoc that documents a phantom xmax discriminator is corrected to describe the actual mechanism (no reference to a field/column the code does not use); no behavioral change."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/log/RedactorTest.java
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

# M1-250: Core lows

## Context

Three low-severity `infochat-core` findings bundled by module. Source:
`deep-code-review/v3/` UNIFIED-REPORT.md T13 (opus `02#F2`), T14 (mimo `02#F1`),
T28 (opus `02#F1`).

- **T13.** `Redactor` scans each pattern twice — `find()` then `replaceAll` —
  over the same input.
- **T14.** `InterruptibleCharSequence.charAt` calls `nanoTime()` on every char;
  a sampled cadence (every Nth char) keeps interruptibility at a fraction of the
  cost.
- **T28.** `ThrottledAdminNotifier` javadoc documents a phantom `xmax`
  discriminator that the code does not use.

## Acceptance

See frontmatter. In prose: scan each redaction pattern once; sample the
interrupt-timeout check; correct the phantom-`xmax` javadoc. Tests pin the
behavior-preserving redaction output and the still-interruptible semantics; `mvn
verify` is 0.

## Out-of-scope

See frontmatter. Redaction patterns, masked output, and the notifier's throttle
behavior are unchanged.

## Notes

- If a `RedactorTest` already covers the redaction cases, extend it rather than
  duplicating; the file-scope entry is the add-or-extend target.
- T14's sampling cadence (the Nth) should be chosen so a pathological input is
  still interrupted within the same order-of-magnitude wall budget the current
  per-char check guarantees — name the constant and comment the why.
</content>
</invoke>
