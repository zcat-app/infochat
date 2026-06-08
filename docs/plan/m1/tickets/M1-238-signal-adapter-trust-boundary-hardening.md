---
id: M1-238
title: "Signal adapter: constant-time mention compare + total timestamp parse"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 6
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMentionParser.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMentionConstantTimeTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupTimestampGuardTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The SimpleX mention parser — it already uses the constant-time MessageDigest.isEqual compare this ticket brings Signal in line with; unchanged.
  - The SignalMessageCodec DM-path timestamp handling (usableTimestamp/integralLong) — it is the correct, total reference implementation; this ticket REUSES it, it does not change it (beyond making it reachable from the group handler if needed).
  - The capability flags, group-membership handling, and the reconnect-window send-classification divergence — out of scope (the latter is an architecture-lens cross-adapter question).
acceptance:
  - "M-F1: SignalMentionParser compares the inbound mention uuid against the bot ACI with a constant-time comparison (MessageDigest.isEqual over UTF-8 bytes), matching the SimpleX sibling's documented D10 constant-time discipline, instead of String.equals which short-circuits on the first differing char; the existing case-insensitive (Locale.ROOT lowercase) match behavior is preserved."
  - "A named test asserts the Signal mention gate still matches the bot ACI case-insensitively and rejects non-matching uuids (behavior preserved; the comparison is now constant-time)."
  - "M-F2: SignalGroupHandler extracts the group-message timestamp via the codec's total helper (usableTimestamp / a thin @Nullable wrapper) and DROPS the frame when no usable numeric timestamp is present, instead of envelope.getJsonNumber(\"timestamp\").longValueExact() which NPEs on a present-but-null/non-number field and throws ArithmeticException on a fractional/out-of-range value."
  - "A named test asserts a group frame with a missing/null/non-numeric timestamp is dropped cleanly (no exception out of handleReceive) while a well-formed frame is delivered."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMentionConstantTimeTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupTimestampGuardTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Per-adapter trust level and identity
decision_refs:
  - D10
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-238: Signal adapter — constant-time mention compare + total timestamp parse

## Context

Two findings on the Signal adapter's inbound trust boundary, grouped (same
adapter, both wire-frame robustness on the signal-cli daemon stream):

- `deep-code-review/v2.5/opus-48/05-module-infochat-messaging-adapter.md#F1`
  (SECURITY, low): `SignalMentionParser` (line 60) gates the bot-ACI
  mention with `String.equals`, non-constant-time over attacker-controlled
  wire data. The sibling `SimpleXMentionParser` uses `MessageDigest.isEqual`
  and documents that the D10 group-mode trust anchor "must not leak
  byte-by-byte." The two anchor the identical decision and should not
  diverge.
- `#F2` (DRIFT, low): `SignalGroupHandler.handleReceive` (157-159) extracts
  the timestamp with `getJsonNumber("timestamp").longValueExact()` guarded
  only by `containsKey`, so a present-but-null/non-number/fractional value
  NPEs/throws out of the handler. The codec's DM path deliberately guards
  the same field (`usableTimestamp`/`integralLong`) and drops the frame,
  documenting that "an NPE/CCE escaping here used to kill the thread that
  processes inbound frames." The outer dispatch catch contains the blast
  radius to one dropped message, but the two inbound paths should treat the
  same untrusted field identically.

## Acceptance

See frontmatter. In prose: make the Signal mention compare constant-time
(preserving case-insensitive matching); route the group-timestamp through
the codec's total helper and drop unusable frames; named tests pin both;
`mvn verify` is 0.

## Out-of-scope

See frontmatter. SimpleX, the codec's DM path, capability flags, and the
reconnect-window classification question are untouched.

## Notes

- Recommended fixes (incl. the byte-array constant-time compare and reusing
  `usableTimestamp`) are in the source findings.
- `SignalGroupHandler` is not currently constructed with the codec; either
  pass it in or lift `usableTimestamp` to a package-private static the
  handler can call — implementer's choice, both noted in the finding.
- `security_relevant: true` (M-F1 is the D10 trust anchor) → a `/redteam`
  pass is appropriate.
