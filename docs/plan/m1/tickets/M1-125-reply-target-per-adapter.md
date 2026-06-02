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
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
  preserves:
    - all tests currently green on main EXCEPT the four files in test_plan.modifies (see §Notes "Pre-existing test authorization")
spec_refs:
  - docs/spec/messaging.md §Per-adapter trust level and identity
  - docs/spec/security.md §Per-adapter admin threat profile
decision_refs:
  - D46
reviews: {}
escalations:
  - date: 2026-06-02
    reason: outline-fail
    reviewer_verdict_excerpt: |
      ## OUTLINE FAILED — escalation recommended

      REASON: The ticket's chosen approach — "Replace the single volatile field
      with per-name resolution (thread adapterName through sendReply)" — forces
      modification of four pre-existing, currently-green plain-JUnit harness tests
      that the ticket does not authorize touching, and which
      `test_plan.preserves: all tests currently green on main` explicitly promises
      to keep green. Ground truth: these harnesses bind their reply target via
      InboundRouter.setReplyTarget(...) and then deliver inbound with
      adapterName = "inmemory", but the bound fakes report a DIFFERENT name() —
      InboundRouterProbationOrderingTest (CapturingAdapter.name() = "capturing",
      :564), InboundRouterNormalizeTest ("capturing", :310),
      InboundRouterIntakeOrderingTest ("capturing", :659),
      InboundRouterContactIdRedactionTest (NoopAdapter -> "noop" :355,
      FailingAdapter -> "failing" :307) — all delivering inbound "inmemory".
      Any reply resolution keyed off the registered adapter's name() (the pattern
      DigestWorker.findAdapter / ApproveGroupCommandHandler.findAdapter demonstrate)
      will fail to resolve "inmemory" to these mismatched-name fakes and route to
      the no-target drop branch, breaking ~25 assertions across the four files plus
      the dedicated null-target test (noReplyTargetPathDoesNotLeakFullContactId).
      Only InboundRouterConfirmCancelTest survives unchanged (its CapturingAdapter
      name() = "inmemory", :274). The implementer cannot satisfy the acceptance
      criteria and test_plan.preserves simultaneously: the only test-preserving
      alternative is to retain a last-wins fallback reference for the name-miss
      case, which contradicts acceptance item 1 ("not a single volatile
      replyTarget field") and the security_relevant redteam plus the
      no-backwards-compatibility-shim rule — a rule-vs-acceptance conflict the
      developer must escalate rather than resolve unilaterally. The ticket must
      decide one of: (a) authorize updating the four affected harness files in
      §Notes/§Out-of-scope, naming the new wiring (rebind each fake under name
      "inmemory", or thread a matching name through each onMessage call) and
      stating the new expected behavior; or (b) explicitly sanction a
      name-keyed-with-fallback design and reword acceptance item 1 accordingly.

      SUGGESTED ESCALATION: refine
revisions:
  - date: 2026-06-02
    reason: outline-fail-refine (round 1) — authorize the four name-mismatched harness fakes to be rebound under the inbound adapterName
    snapshot: |
      test_plan (pre-refine):
        adds:
          - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
        preserves:
          - all tests currently green on main
      The body carried NO authorization to modify pre-existing tests; §Out-of-scope
      listed only the sister-ticket findings (M1-138 /stop, M1-155 hygiene), the
      messaging SPI freeze, and the DigestWorker / ApproveGroupCommandHandler
      findAdapter freeze. Plan-writer (round 1) found that the name-keyed reply
      resolution required by acceptance item 1 breaks four currently-green
      plain-JUnit harnesses whose reply-target fake reports name() != the inbound
      adapterName "inmemory" they deliver — verified on disk:
        InboundRouterProbationOrderingTest (:564 "capturing"),
        InboundRouterNormalizeTest        (:310 "capturing"),
        InboundRouterIntakeOrderingTest   (:659 "capturing"),
        InboundRouterContactIdRedactionTest (:308 "failing", :356 "noop").
      Only InboundRouterConfirmCancelTest survives unchanged (:274 name()="inmemory").
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

- **Pre-existing test authorization (added by the outline-fail refine).**
  Acceptance item 1 keys the outbound adapter off the inbound `adapterName`.
  Four currently-green plain-JUnit harnesses bind a reply-target fake whose
  `name()` does NOT equal the `"inmemory"` adapterName they deliver via
  `onMessage(..., "inmemory")`, so name-keyed resolution would route them to the
  no-target drop branch and break their assertions. These four files — listed in
  `test_plan.modifies` — ARE authorized to change, with this expected behavior:
  rebind the reply-target fake each test registers so it reports
  `name() == "inmemory"` (the inbound adapterName the test already passes),
  matching the surviving `InboundRouterConfirmCancelTest` pattern
  (`CapturingAdapter.name()` returns `"inmemory"`). The change is mechanical: the
  assertions about routing / ordering / normalization / probation / contact-id
  redaction behavior MUST remain semantically unchanged — only the reply-target
  fake's reported name is corrected so resolution finds it. In
  `InboundRouterContactIdRedactionTest` (which carries both a `FailingAdapter` →
  `"failing"` and a `NoopAdapter` → `"noop"`), whichever fake is bound as the
  reply target for the assertion under test must report `"inmemory"`. This
  authorization does NOT extend to any other test file, nor to retaining a
  last-wins fallback field (acceptance item 1 forbids it).
- Source: `docs/plan/audit/opus-48-handout.md` §A3 (REPLY-TARGET, Critical, GROUNDED) +
  C-ADAPTER-DUP-NAME; `opus-47-full-handout.md` §F-SEC-02, F-MAINT-37;
  `opus-47-only-handout.md` §TP2, M28.
- Plan-writer pass recommended (medium-high structural change to the reply path).
