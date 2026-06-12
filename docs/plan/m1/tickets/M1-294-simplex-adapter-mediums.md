---
id: M1-294
title: "SimpleX adapter: error classification, close race, bot-id validation, inbound cap"
status: done
created: 2026-06-11
last_updated: 2026-06-12
blocked_by: []
files_budget: 14
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - docs/design/06-messaging.md
  # Added by escalate→refine (round 1, budget-breach): U-35's isWellFormed
  # gate on SimpleXAdapter.start() breaks this provider IT, which calls
  # start() with a 33-char (<43) test bot-queue-address. The fix is one
  # well-formed-value literal; the IT lives outside the messaging-adapter
  # module so the path must be in files_scope.
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
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
reviews:
  - round: 1
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 333
      removed: 75
escalations:
  - date: 2026-06-12
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (pre-review). U-35's new SimpleXIdentity.isWellFormed gate on
      SimpleXAdapter.start() rejects bot-queue-address values < 43 chars.
      MultiAdapterProductionIT (infochat-provider, outside files_scope)
      calls sx.start() with new SimpleXIdentity("m1-109-simplex-bot-identity-queue")
      (33 chars) and asserts start() succeeds, so the IT would fail.
      Resolution: refine — add the one IT file to files_scope and change
      its test identity to a well-formed >=43-char value.
revisions:
  - date: 2026-06-12
    reason: budget-breach rework
    snapshot:
      files_scope: |
        (pre-refine) simplex main + signal main (SignalAdapter,
        SignalMessageCodec) + simplex/signal test dirs + docs/design/06-messaging.md
        — did NOT include infochat-provider's MultiAdapterProductionIT.java
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-12
    verdict: CLEAN
    base: 45040c0c2211acbb188122f89b6a15eb1df4b4f4
    head: 1fd84056a65d2db881c3b85f9d864278557eb0a9
    verdict_file: docs/plan/m1/redteam/M1-294-2026-06-12.md
    out_of_model_count: 0
    note: |
      Pre-merge adversarial audit of the done branch tip (run between
      /m1-tick commit and /m1-tick merge per the security_relevant
      reminder). CLEAN: no gap between docs/spec/security.md and the diff.
      U-23's fail-closed classifier, U-35's isWellFormed bot-id gate,
      U-30's single-sourced inbound cap, and U-14's closed-adapter
      terminal guard all reviewed; nothing to remediate.
clarity_check:
  date: 2026-06-12
  verdict: WARN
  warnings:
    - "U-30 acceptance item offers two equally-acceptable implementation paths without preference; reviewer must infer post-hoc which was chosen (ticket Notes do state a preference: thread profile if cheap, else codec-constant + design amendment)."
    - "test_plan.modifies includes the Signal test directory but does not enumerate which Signal test files/methods change or their new assertion."
    - "U-30 amendment path references design §6.2.2 without inlining the current text that would be replaced; amendment branch requires reading docs/design/06-messaging.md §6.2.2."
  blockers: []
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
