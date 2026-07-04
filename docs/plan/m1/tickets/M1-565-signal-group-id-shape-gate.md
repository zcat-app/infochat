---
id: M1-565
title: Base64 shape gate on the Signal group-id scope key
status: done
created: 2026-07-04
last_updated: 2026-07-04
reviews:
  - round: 1
    date: 2026-07-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 228
      removed: 16
clarity_check:
  date: 2026-07-04
  verdict: PASS
  warnings: []
  blockers: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-04
    verdict: CLEAN
    base: main@07c32745
    head: working-tree (pre-commit, branch m1/M1-565-signal-group-id-shape-gate)
    verdict_file: docs/plan/m1/redteam/M1-565-2026-07-04.md
    out_of_model_count: 2
    note: |
      In-cycle audit of the uncommitted branch diff (user-approved
      working-tree resolution). CLEAN, no in-model findings. Two advisory
      out-of-model items, both gated on a future signal-cli trust-boundary
      redraw: (1) the gate validates but does not canonicalize the base64
      spelling, so a distrusted channel could fragment one group into
      multiple scope keys — canonicalize if the boundary is redrawn;
      (2) the shape-gate WARN is per-frame and upstream of all rate caps —
      revisit log-rate bounding on the same redraw. No tickets filed.
blocked_by: []
files_budget: 4
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupInboundRobustnessTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the DM-side exclusion guard in SignalMessageCodec.extractDm (stays a
    presence check — its job is routing, not admission; a malformed
    group stanza must still be excluded from the DM route and then
    rejected by the group route's gate, never fall back to DM scope)
  - SimpleX and InMemory group-id handling (different id domains)
  - every existing groupV2/groupInfo test fixture — the gate MUST pass
    the current fixture id (Z3JvdXBJZEJhc2U2NEVuY29kZWQ=, 20 decoded
    bytes) unchanged; a gate that forces fixture churn is over-pinned
  - the sender-ACI gate (isAcceptableAci) and mention parsing
acceptance:
  - "SignalMessageCodec gains isAcceptableGroupId(String): strict
    java.util.Base64 decode succeeds AND the decoded length is within
    [16, 64] bytes. The bounds are deliberately a band, not an exact
    pin: the live-observed group v2 id is 32 bytes and the existing
    test fixtures decode to 20 — an exact-length gate is the F-live-10
    overstrict-assumption failure mode in reverse. A WHY comment
    records this."
  - "SignalGroupHandler.extractGroupId applies the gate to whichever
    spelling matched; a rejected id drops the frame WITH a WARN that
    carries only the encoded id's LENGTH and the adapterMessageId-style
    timestamp token, never the id value itself (it names a private
    group) — rejection must be observable, not an F-live-10-style
    silent drop. Named test:
    SignalGroupHandlerTest.malformedGroupIdDroppedWithObservableWarn."
  - "SignalGroupInboundRobustnessTest gains cases: non-base64 garbage
    id, empty id, and an over-64-byte id all drop without throwing and
    without dispatch; the existing well-formed fixtures still
    dispatch (fixtures byte-identical)."
  - mvn verify is green.
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupInboundRobustnessTest.java
  preserves:
    - all tests currently green on main, in particular every existing
      groupInfo/groupV2 fixture unmodified
spec_refs:
  - docs/spec/security.md §Trust boundaries
  - docs/spec/messaging.md §Identity and groups
decision_refs:
  - D37
---

# M1-565: Base64 shape gate on the Signal group-id scope key

## Context

Origin: M1-562 redteam out-of-model item 2
(docs/plan/m1/redteam/M1-562-2026-07-04.md, advisory; user accepted as
a nice-to-have 2026-07-04). `SignalGroupHandler.extractGroupId` accepts
any JSON string as the `ScopeRef.Group` scope key, while the sender ACI
in the same envelope must pass `isAcceptableAci`. Not adversary-
reachable in-model — the value comes from the co-located signal-cli
daemon over a loopback-only channel the threat model trusts — so this
is defense-in-depth: if that boundary is ever redrawn (remote daemon,
shared host), the scope key is already shape-gated, mirroring the ACI
precedent.

## Acceptance

Mirrors the YAML list: a band-bounded strict-base64 gate in the codec
(so both group-handler spellings share one predicate, like
isAcceptableAci); applied in extractGroupId with an observable,
value-free WARN on rejection; robustness cases for garbage/empty/
oversize ids; existing fixtures untouched; `mvn verify` green.

## Out-of-scope

See frontmatter. In particular the DM-route exclusion guard stays a
presence check: admission control lives in ONE place (the group route),
and a stanza rejected there must drop, not leak into DM scope.

## Notes

- Why a band and not exactly 32 bytes: the live wire is 32 (group v2),
  the fixture corpus is 20, and hypothetical v1-style ids are 16. The
  gate's purpose is rejecting garbage and unbounded input, not pinning
  a protocol constant we've observed exactly once — an exact pin is
  how F-live-10-class silent drops get built.
- Why WARN and not silent: F-live-10 stayed invisible precisely because
  the drop path was spec-permitted silence. A shape-gate rejection is
  an anomaly worth surfacing; D37 discipline means the log carries no
  id value, only its length and the frame's timestamp token.
- Adjacent pattern: `SignalMessageCodec.isAcceptableAci` (the gate this
  one mirrors, including where it lives and how tests pin it).
