---
id: M1-294
title: "SimpleX adapter: error classification, close race, bot-id validation, inbound cap"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 14
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - docs/design/06-messaging.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The Provider-side retry layer (M1-284) — classification fixes here feed it but do not implement it.
  - Signal behaviour beyond the U-30 second-literal touch (SignalAdapter:77 / SignalMessageCodec:60) — the rest of the Signal adapter is M1-293.
  - The edit/finalize fallback (M1-285).
  - CapabilityFlags record shape — values and sourcing only.
acceptance:
  - "U-23: SimpleXMessageCodec.classifyError (:650; substring matching incl. contains(\"temporary\") at :659) becomes an exact include-list of known-TRANSIENT simplex-chat error tags, verified against the tag vocabulary from the M1-105/109 integration work; unknown or unmatched tags classify PERMANENT per spec (messaging.md §Failure handling, verbatim: 'An adapter that cannot tell the two apart MUST default to permanent'); named tests: an unknown tag containing the substring 'temporary' is PERMANENT; each include-listed tag is TRANSIENT. The comment referencing the nonexistent 'Provider's uniform retry policy' is corrected (it lands with M1-284; reference the spec section, not the unbuilt class)."
  - "U-14: a terminal closedForGood flag is checked before the reconnecting flag in requireConnected, and the teardown-won-the-race branch of reconnect() clears reconnecting (today it returns without clearing, leaving a closed adapter classifying every post-close send TRANSIENT forever); a named test closes the adapter mid-reconnect and asserts subsequent sends classify PERMANENT."
  - "U-35: the configured bot queue address is validated with SimpleXIdentity.isWellFormed at startup (today blank-only at ~:216, while Signal validates well-formedness); a named test rejects a malformed address with a message naming the property key."
  - "U-30: maxInboundMessageBytes is single-sourced per adapter — the capability value reads the codec's enforcement constant (SimpleXAdapter:72 and SimpleXMessageCodec's cap; SignalAdapter:77 and SignalMessageCodec:60 MAX_INBOUND_TEXT_BYTES) so the 'MUST stay in lockstep' comment pair that nothing checks disappears; AND the design §6.2.2 profile commitment (laptop 16 KiB, vps/remote-llm 32 KiB, pi 8 KiB) is either threaded through (profile-driven value reaches codec + capability) or design §6.2.2 is amended to record v1 as fixed 16 KiB with rationale — one of the two, pinned by test or design text."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
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

# M1-294: SimpleX adapter: error classification, close race, bot-id validation, inbound cap

## Context

Deep-review v5 verified **U-23** (MEDIUM), **U-14** (LOW, shape-confirmed),
**U-35** (MEDIUM), **U-30** (MEDIUM)
(`deep-code-review/v5/UNIFIED-REPORT.md` §3; sources `opus-47/05#F3` (U-23,
unique), `fable-5/05#F5` + `gpt-55#L-06` (U-14), `opus-47/01#F2` (U-35,
unique), `deepseek/05#F1` (profile claim, unique) + `opus-48/05#F1` +
`fable-5/05#F8` + `gpt-55#L-07` (lockstep literals) — gitignored; all
load-bearing facts inlined; anchors verified 2026-06-11: classifyError at
SimpleXMessageCodec:650 with contains(\"temporary\") at :659; reconnecting
flag at SimpleXAdapter:122/:319-336; isWellFormed exists and is unused for
the bot id; 16_384 literals at SimpleXAdapter:72, SignalAdapter:77,
SignalMessageCodec:60).

U-23 is the security-posture headline: substring matching can classify
unknown/permanent tags TRANSIENT, inverting the spec's fail-closed default.
Signal's classifier is already exact-match — this restores parity.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- U-14's race was shape-confirmed, not executed — write the test against
  the flag semantics, not timing (drive the interleaving deterministically
  via the existing test seams).
- U-30 default direction: the minimal single-source step (capability ←
  codec constant) plus a design amendment recording v1 fixed-16KiB is
  acceptable if profile threading needs CDI wiring the adapters don't have;
  prefer threading if the existing InfochatProfile plumbing reaches the
  adapters cheaply.
- Coordination: M1-285 (edit fallback) and M1-287/M1-286 do not touch these
  files except SimpleXAdapter (M1-285 does). Check worktrees at start.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-294-*.md
```
