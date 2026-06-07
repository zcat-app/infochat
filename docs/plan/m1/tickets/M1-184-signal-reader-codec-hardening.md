---
id: M1-184
title: "Signal reader/codec hardening against malformed frames"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by:
  - M1-177
files_budget: 5
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - moving inbound dispatch off the reader thread — M1-177 (this ticket is sequenced after it precisely because both touch SignalJsonRpcClient's read path)
  - SimpleX codec exception messages — already remediated to fixed messages; only the Signal codec still interpolates
  - reconnect-after-subprocess-restart (M1-185) — this ticket keeps the reader alive across bad frames; M1-185 revives the transport after process death
  - InboundHandler dispatch semantics, group handlers, mention stripping (M1-187)
acceptance:
  - "A structurally-malformed inbound frame (absent timestamp, wrong-typed params/envelope/timestamp fields) does not kill the reader loop: a named test pushes such frames followed by a valid frame and asserts the valid frame still delivers (today the typed-accessor phase throws NPE/CCE past handleLine's IllegalArgumentException-only catch, the reader loop catches only IOException, and the thread dies while the subprocess stays alive — the adapter goes permanently deaf with no restart trigger)"
  - "A frame missing a usable timestamp is dropped without throwing: a named codec test covers absent-in-both envelope/dataMessage and wrong-typed timestamp shapes (today SignalMessageCodec.extractDm calls getJsonNumber(...).longValueExact() unguarded)"
  - "Failures at the decode boundary keep D37 class-name-only logging: the named test asserts log output for a malformed frame carries neither the frame bytes nor the exception's message text"
  - "SignalMessageCodec exception messages no longer interpolate the raw line (today 'Malformed JSON-RPC envelope: ' + line and 'missing both method and id: ' + line) — a named test asserts the thrown message contains no frame content"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §User content in exceptions
decision_refs:
  - D37
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-184: Signal reader/codec hardening against malformed frames

## Context

The Signal reader thread dies permanently on structurally-malformed frames:
`SignalMessageCodec` uses typed JSON accessors that throw NPE when the
timestamp is absent from both envelope and dataMessage
(`getJsonNumber(...).longValueExact()`) and CCE when fields are wrong-typed;
`handleLine` catches only `IllegalArgumentException` (SignalJsonRpcClient.java:478)
and `readerLoop` only `IOException` (:438). An escaping NPE/CCE kills the
reader while signal-cli stays alive — the adapter is deaf, and the
supervisor's restart machinery never triggers because the subprocess is
healthy. Separately (latent), the codec interpolates the raw frame line into
its exception messages (SignalMessageCodec.java:97, :111); today's catch
logs class-name-only per D37, but any future logger of `e.getMessage()`
would leak user-bearing frame content. Unified findings M3 (high) + M14
(latent low), `deep-code-review/v2/UNIFIED.md` §2.

## Acceptance

See frontmatter. The boundary guarantee: no inbound frame shape can kill the
reader; no exception message carries frame bytes.

## Out-of-scope

See frontmatter. Sequenced after M1-177 because both rework
SignalJsonRpcClient's read path — land the dispatch change first, then
harden the (smaller) post-change surface.

## Notes

- Source: `UNIFIED.md` §3 T8 under `deep-code-review/v2/` (kimi-folder msg
  F2 — the wider, verified framing; opus-48 msg F4's "DM-path unguarded" was
  imprecise: both onMessage dispatch paths are guarded, the typed-accessor
  phase is not).
- The existing handleLine comment documents the D37 rationale — keep that
  posture when widening the catch (catch RuntimeException at the handleLine
  boundary, log class name only).
