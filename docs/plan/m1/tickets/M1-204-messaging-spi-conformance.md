---
id: M1-204
title: "Messaging SPI conformance: setTyping no-op, start() exception type, constants drift, jitter, dead stubs"
status: done
created: 2026-06-07
last_updated: 2026-06-08
clarity_check:
  date: 2026-06-08
  verdict: PASS
  warnings: []
blocked_by: [M1-177]
files_budget: 12
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalIdentity.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXIdentity.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/MessagingStartup.java
  - docs/design/06-messaging.md
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/ProductionAdapterBeans.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - inbound dispatch threading — M1-177's (this ticket is blocked on it; same adapter files)
  - reader/codec hardening — M1-184's; transport reconnect — M1-185's; group outbound — M1-186's; send-path bounding — M1-188's
  - maxInflightSends/maxSendsPerSecond ENFORCEMENT and the bounded inbound queue — M1-205's decision ticket; here only the declared CONSTANT VALUES are reconciled with the design table
  - the SPI interface itself (MessagingAdapter) — no signature changes; start() already declares throws MessagingException
  - InMemoryAdapter — its conformance items are UNIFIED.md T33 (lows batch, not yet filed)
acceptance:
  - "SimpleXAdapter.setTyping is a no-op: a named test asserts no transport command is issued when called (the SPI javadoc and design both say \"No-op for adapters with capabilities.supportsTypingIndicator = false\", the flag IS false, yet today the adapter still sends an apiSetContactTyping-shaped command and its class javadoc misreads the SPI as 'best-effort'); MultiAdapterProductionIT.signalCrashDoesNotAffectSimpleX, whose SimpleX liveness probe is the typing frame, switches the probe to a frame-producing call (e.g. a real send) so the IT keeps asserting post-crash liveness"
  - "SignalAdapter.start() surfaces transport startup failures as MessagingException per the SPI contract (\"@throws MessagingException on transport startup failure\"): named tests assert subprocess-spawn failure and JSON-RPC connect failure each throw MessagingException (today :211/:217/:233 throw IllegalStateException; the capability-only-constructor misuse guards may legitimately remain IllegalStateException — they are programming errors, not transport failures); MessagingStartup's catch-Exception javadoc note about Signal's runtime exceptions is updated since this change orphans it"
  - "The SimpleX capability constants and the design table agree: either code adopts design 06-messaging §6.4.2's values (minEditInterval 600ms, maxSendsPerSecond 5) or the design table is amended to the shipped values with the observation rationale — a named test pins the agreed values (today code says ZERO and 8 against the table's 600ms and 5)"
  - "SimpleXSubprocess backoff implements what its javadoc and the design say: either full jitter (matching SignalSubprocess) with a seeded-Random named test pinning the [0, exp] range, or the javadoc and design amended to equal-jitter with the same test pinning [exp/2, exp] — code, javadoc, and design agree after this ticket (today the code is equal-jitter while the javadoc claims full jitter and Signal implements full jitter)"
  - "The dead SignalIdentity.resolve / SimpleXIdentity.resolve stubs (UnsupportedOperationException citing shipped tickets M1-107/M1-103) are removed; the build stays green, proving no caller existed; the two javadoc references the removal orphans are updated (SignalAdapter's botAci @param citing SignalIdentity#resolve, ProductionAdapterBeans' \"long-term this Producer switches to SimpleXIdentity#resolve\" sentence — identity comes from config, the switch plan is dead with the stub)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java — signalCrashDoesNotAffectSimpleX's liveness probe swaps from the typing pulse to a frame-producing call (the typing frame disappears with the setTyping no-op fix; the post-crash-liveness assertion itself is preserved)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterSkeletonTest.java — maxSendsPerSecond / minEditInterval pins updated to the agreed values (acceptance item 3's named pinning test)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java — seeded-Random backoff test extended to pin both bounds of the agreed jitter range (acceptance item 4)
  preserves:
    - all other tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Capability flags (minimum set)
decision_refs:
  - D46
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 255
      removed: 105
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
revisions:
  - date: 2026-06-08
    reason: budget-breach refine (pre-implementation call-site sweep — acceptance item 1 breaks MultiAdapterProductionIT.signalCrashDoesNotAffectSimpleX, which is outside files_scope; item 5 orphans a ProductionAdapterBeans javadoc sentence; in-scope plan already used all 10 of files_budget)
    summary: |
      Pre-refine snapshot: files_budget 10; files_scope had 9 entries
      (6 main Java, 1 design doc, 2 test dirs — no infochat-provider
      test paths, no ProductionAdapterBeans); test_plan had only adds
      (the two messaging-adapter test dirs) and preserves.

      The draft-time call-site sweep grepped adapter.start() callers
      and start()-ISE test pins but missed src/test probes of
      setTyping (the M1-175/M1-160 sweep-miss pattern):
      MultiAdapterProductionIT.signalCrashDoesNotAffectSimpleX:441
      probes SimpleX liveness via sx.setTyping then awaitFrame +
      assertNotNull — the typing pulse is the ONLY frame that test
      produces after the WS handshake, so the no-op fix makes the IT
      fail. It also missed the ProductionAdapterBeans:58 javadoc
      sentence planning a "long-term switch" to
      SimpleXIdentity#resolve, orphaned by the stub removal.

      Changes applied in this refine:
        - files_budget: 10 → 12
        - files_scope: +MultiAdapterProductionIT.java (provider test),
          +ProductionAdapterBeans.java (provider main, javadoc-only)
        - test_plan.modifies: new section — MultiAdapterProductionIT
          (liveness probe swapped to a frame-producing call),
          SimpleXAdapterSkeletonTest (constant pins to agreed values),
          SimpleXSubprocessTest (jitter test extended to pin both
          bounds of [exp/2, exp])
        - acceptance item 1: + IT probe-swap clause
        - acceptance item 5: + ProductionAdapterBeans javadoc clause
escalations:
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation call-site sweep found acceptance item 1
      (setTyping no-op) breaks a pre-existing IT outside files_scope:
      infochat-provider/src/test/.../MultiAdapterProductionIT.java
      signalCrashDoesNotAffectSimpleX probes SimpleX liveness via the
      typing frame ("sx.setTyping(...)" then awaitFrame + assertNotNull);
      with setTyping a no-op the only frame in that test never arrives.
      Fixing it requires touching a path outside files_scope and
      modifying a pre-existing test not authorized by test_plan, and the
      in-scope plan already uses all 10 of files_budget. Secondary:
      removing SimpleXIdentity.resolve orphans a javadoc sentence in
      ProductionAdapterBeans.java (also outside files_scope).
---

# M1-204: Messaging SPI conformance: setTyping no-op, start() exception type, constants drift, jitter, dead stubs

## Context

Five conformance drifts (unified findings M8, M9, M11, M12, M13 —
`deep-code-review/v2/UNIFIED.md` §2):

1. **setTyping vs capability flag (M8, med).** Re-anchoring settled the
   direction: the SPI javadoc itself says "No-op for adapters with
   CapabilityFlags#supportsTypingIndicator false" and design
   06-messaging agrees — SimpleXAdapter's class javadoc claims
   "best-effort by the SPI's own contract", which misreads its own SPI.
   Fix the behavior AND the javadoc.
2. **start() exception type (M9, med-low).** The SPI declares
   `throws MessagingException` for transport startup failure;
   SignalAdapter throws IllegalStateException on spawn/connect failure.
   MessagingStartup's catch(Exception) javadoc explicitly documents
   working around this — that note becomes an orphan of the fix and is
   updated (the catch itself stays: per-adapter resilience).
3. **Constants drift (M11, low).** minEditInterval ZERO vs design 600ms;
   maxSendsPerSecond 8 vs design 5. The in-code comment calls them
   "best-guess defaults" — reconcile one way or the other.
4. **Jitter drift (M12, low).** SimpleX backoffDelay is equal-jitter
   (`half + nextLong(half+1)`); its javadoc says full jitter; Signal IS
   full jitter.
5. **Dead resolve() stubs (M13, low).** Both Identity classes throw
   UnsupportedOperationException citing tickets that shipped; identity
   comes from config; zero callers (draft-time grep).

Call-site sweep (draft time): `adapter.start()` is called from
MessagingStartup:60 inside catch(Exception) — no behavior change there;
no test asserts IllegalStateException from start() (SignalConfigTest's
ISE pins are config.validate(), untouched).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T27 under `deep-code-review/v2/` (opus-47
  msg F2, kimi-folder msg F9, mimo msg #2/#3/D1/D2, multi-model M13).
- blocked_by M1-177: same adapter files (SignalAdapter, SimpleXAdapter
  are in M1-177's files_scope and its worktree is in flight); rebase
  this ticket's view after M1-177 lands.
