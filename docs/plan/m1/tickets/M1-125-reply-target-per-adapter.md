---
id: M1-125
title: "Per-adapter reply target + AdapterRegistry duplicate-name dedup"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - the other InboundRouter findings (/stop scope M1-138, body-cap/bidi/lookupGroupId M1-155) — they share the file but are separate tickets and serialize after this one
  - any messaging SPI change
  - DigestWorker / ApproveGroupCommandHandler findAdapter — they already demonstrate the correct per-name lookup; do not refactor them
acceptance:
  - "Replies are routed to the adapter that delivered the inbound message (resolved by adapterName), not a single volatile replyTarget field"
  - "A test with two activated adapters asserts a message inbound on adapter A is replied through adapter A and never through adapter B"
  - "The banned-user fixed-reply is delivered through the correct inbound adapter (not silently dropped)"
  - "AdapterRegistry rejects a duplicate adapter name in infochat.adapters (e.g. simplex,simplex) with a fail-fast error rather than double-wiring"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Per-adapter trust level and identity
  - docs/spec/security.md §Per-adapter admin threat profile
decision_refs:
  - D46
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-125: Per-adapter reply target + AdapterRegistry duplicate-name dedup

## Context

`InboundRouter` holds a single `private volatile MessagingAdapter replyTarget`
(`:284`, read at `:604`); `AdapterRegistry.start` calls `setReplyTarget(adapter)`
once per activated adapter (`:254-266`), so the **last-registered adapter wins**.
In a SimpleX+Signal deployment every reply ships through the last adapter
regardless of which adapter delivered the inbound — cross-adapter outbound to an
unrelated identity space, and the banned-user fixed-reply silently becomes a
drop. D46 + `security.md` §Per-adapter admin threat profile commit to
per-adapter isolation, which is exactly the multi-adapter shape v1 ships
(Signal must remain in v1). `onMessage(msg, adapterName)` already carries the
discriminator, and `DigestWorker.findAdapter` / `ApproveGroupCommandHandler.findAdapter`
already demonstrate the correct per-name lookup. Bundled: `AdapterRegistry`
accepts a duplicate adapter name in the CSV (`simplex,simplex`) and double-wires
— a fail-fast dedup closes the same file's adjacent operator-error path.

## Acceptance

See frontmatter. Replace the single volatile field with per-name resolution
(thread `adapterName` through `sendReply`); add the dedup gate to
`AdapterRegistry`.

## Out-of-scope

See frontmatter. The other `InboundRouter.java` findings are deliberately NOT
here — they share the file and serialize after this ticket in the PROV-ROUTER
lane. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A3 (REPLY-TARGET, Critical, GROUNDED) +
  C-ADAPTER-DUP-NAME; `opus-47-full-handout.md` §F-SEC-02, F-MAINT-37;
  `opus-47-only-handout.md` §TP2, M28.
- Plan-writer pass recommended (medium-high structural change to the reply path).
