---
id: M1-376
title: "messaging: deterministic SimpleX adapterMessageId fallback and shared decode-ladder helper"
status: done
created: 2026-06-14
last_updated: 2026-06-19
clarity_check:
  date: 2026-06-19
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 2
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The Signal codec's adapterMessageId derivation — already deterministic (timestamp-based); unchanged, used here only as the pattern to mirror.
  - The set of fields each decode branch reads and the Ignored outcomes — unchanged; the ladder helper is a behavior-preserving extraction, not a semantics change.
acceptance:
  - "The adapterMessageId fallback for a SimpleX frame missing itemId (SimpleXMessageCodec.java:358-361 and :477-480) derives deterministically from the frame's stable fields (mirroring SignalMessageCodec's timestamp-based derivation) instead of System.nanoTime(). A test asserts two decodes of the same itemId-less frame yield the same adapterMessageId."
  - "decodeNewChatItem and decodeGroupNewChatItem share their common 'absent field → Ignored' guard ladder via one helper so the two ladders cannot drift independently; existing SimpleX codec decode tests stay green (behavior unchanged)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex (deterministic-fallback test)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-19
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 205
      removed: 75
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-376: SimpleX codec id-fallback + ladder dedup

## Context

Deep-review v7 (opus-48) messaging-adapter findings **F3** (drift) and **F2**
(simplification), bundled — same file, both low. Verified at source 2026-06-14:

- **F3:** `SimpleXMessageCodec` (`.../impl/simplex/SimpleXMessageCodec.java:358-361,
  477-480`) falls back to `"simplex-" + System.nanoTime()` for `adapterMessageId`
  when a frame has no `itemId`. `adapterMessageId` is the documented stable
  correlation key (`InboundMessage` javadoc — used for retry correlation and
  audit cross-references); a non-deterministic value defeats that for the
  itemId-less path. The Signal codec correctly derives a deterministic id from
  the timestamp. Low impact (rare missing-itemId frames; the id is never
  persisted across instances), but an avoidable consistency gap.
- **F2:** `decodeNewChatItem` and `decodeGroupNewChatItem` are parallel 12–14-guard
  ladders re-implementing the same "absent field → Ignored" shape and can drift
  independently.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- F3 is the load-bearing half (correctness-of-correlation); F2 is pure tidy-up.
  Do F2 only as a behavior-preserving extraction — if the two ladders turn out to
  diverge in a way that resists a clean shared helper, drop F2 and keep F3.
