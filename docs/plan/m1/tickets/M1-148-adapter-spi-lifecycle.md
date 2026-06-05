---
id: M1-148
title: "MessagingAdapter SPI lifecycle (finalize→finalizeMessage, start/stop) + low-level cleanup"
status: done
created: 2026-06-02
last_updated: 2026-06-05
blocked_by: []
files_budget: 26
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - capability-flag reconciliation / contract test (covered by M1-147)
  - the onMembershipEvent confirm-or-drop decision (covered by the M1-162 investigate-skeleton)
acceptance:
  - "MessagingAdapter SPI gains default void start()/stop() lifecycle methods (no-op defaults; transport adapters override what they need); MessagingStartup.startAllAdapters (infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/MessagingStartup.java:74-99) dispatches adapter.start() directly and drops the reflective Class.getMethod(\"start\") lookup and its NoSuchMethodException arm; the per-adapter failure-isolation catch (one adapter's start failure must not prevent the remaining adapters from starting, §6.7) is preserved"
  - "The finalize SPI method (MessagingAdapter.finalize(MessageHandle, String), MessagingAdapter.java:125) is renamed finalizeMessage across the SPI declaration, the three implementations (SimpleXAdapter.java:274, SignalAdapter.java:276, InMemoryAdapter.java:150), and every caller and javadoc {@link} reference, so it no longer overloads Object.finalize(). The rename target is finalizeMessage, NOT the audit's suggested shutdown/stop: the method marks a streaming message placeholder complete (spec docs/spec/messaging.md §Required SPI surface, 'Finalize'), and a shutdown-flavoured name would both misstate that semantic and collide with the new lifecycle stop(). The spec names the operation, not the Java method — no spec amendment"
  - "Low-level cleanup bundle, each sub-item independently checkable: (a) SimpleXConfig is @ApplicationScoped @Startup with @PostConstruct validate(), matching SignalConfig's eager-validation pattern (SignalConfig.java:28,70-71); (b) the adapter-level startup endpoint probes in SignalAdapter and SimpleXAdapter use exponential backoff capped at the startup deadline instead of fixed-interval sleep (the SUBPROCESS crash-restart loops already do full-jitter exponential backoff and are NOT in scope); (c) the SignalJsonRpcClient oversize-line drain is bulk-skip not per-char; (d) the SimpleXAdapter handles/finalized tables (SimpleXAdapter.java:90-91) are bounded with LRU eviction; (e) SimpleXMessageCodec.findFirstString call sites (SimpleXMessageCodec.java:528,536) read the known response field instead of an attacker-influenced breadth-first key search; (f) HttpClient instances in the SimpleX transport (SimpleXAdapter, SimpleXWebSocketClient) get explicit connect timeouts"
  - "The finalize→finalizeMessage rename's forced edits to pre-existing tests are mechanical (method name at the @Override definition or call site only; no assertion, setup, or expectation changes) and are authorized via test_plan.modifies: 9 infochat-provider test fakes implementing MessagingAdapter (AdapterRegistryTest, StartupGatesTest, ProductionAdapterActivationTest, DigestWorkerTest, InboundRouterIntakeOrderingTest, InboundRouterContactIdRedactionTest, InboundRouterConfirmCancelTest, InboundRouterNormalizeTest, InboundRouterProbationOrderingTest) plus in-module AdapterCapabilityContractTest (line 77) and InMemoryAdapterTest (lines 65, 86, 94)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging (SPI lifecycle defaults + cleanup-bundle coverage)
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRegistryTest.java (finalize→finalizeMessage rename in fakes, lines 261/305 — mechanical, no assertion change)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/StartupGatesTest.java (rename in fakes, lines 191/236/281 — mechanical)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/ProductionAdapterActivationTest.java (rename in fake, line 261 — mechanical)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java (rename in fake, line 278 — mechanical)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java (rename in fake, line 765 — mechanical)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java (rename in fakes, lines 341/393 — mechanical)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java (rename in fake, line 283 — mechanical)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java (rename in fake, line 343 — mechanical)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java (rename in fake, line 573 — mechanical)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/AdapterCapabilityContractTest.java (rename at call site, line 77 — mechanical)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java (rename at call sites, lines 65/86/94 — mechanical)
  preserves:
    - all OTHER tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 26
      added: 450
      removed: 171
escalations:
  - date: 2026-06-05
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (pre-implementation grounding). Developer escalation, two findings:
      (1) Acceptance item 1 requires modifying MessagingStartup, which lives at
      infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/
      MessagingStartup.java:79 (the reflective getMethod("start") call) —
      OUTSIDE files_scope (infochat-messaging-adapter/src/{main,test} only).
      (2) Acceptance item 2 (rename the finalize SPI method) breaks 9
      infochat-provider test files that implement MessagingAdapter with a
      `public void finalize(MessageHandle, String)` override:
      AdapterRegistryTest, StartupGatesTest, ProductionAdapterActivationTest,
      DigestWorkerTest, InboundRouterIntakeOrderingTest,
      InboundRouterContactIdRedactionTest, InboundRouterConfirmCancelTest,
      InboundRouterNormalizeTest, InboundRouterProbationOrderingTest — all
      outside files_scope, and test_plan has no `modifies` field authorizing
      any pre-existing test edit. Items 1+2 are therefore unsatisfiable
      simultaneously with acceptance item 4 (mvn -B clean verify exits 0) and
      test_plan.preserves ("all tests currently green on main").
      files_budget: 10 is also breached: ~8 in-module files + MessagingStartup
      + 9 provider test files ≈ 18+.
revisions:
  - date: 2026-06-05
    reason: budget-breach rework
    note: |
      Pre-implementation grounding found acceptance items 1+2 unsatisfiable
      inside the original frontmatter. Prior values: files_budget: 10;
      files_scope: infochat-messaging-adapter/src/{main,test} only;
      test_plan had no modifies field; item 2 read "renamed (shutdown/stop)".
      Verified facts the rewrite encodes:
        - MessagingStartup (the reflective getMethod("start") at :79) lives in
          infochat-provider, outside the old files_scope.
        - Renaming finalize forces mechanical @Override/call-site edits in 9
          provider test fakes + 2 in-module tests — now authorized in
          test_plan.modifies with line numbers.
        - Rename target pinned to finalizeMessage, not the audit's
          shutdown/stop: the method finalizes a streaming message placeholder
          (spec §Required SPI surface "Finalize"); shutdown would misstate the
          semantic and collide with the new lifecycle stop(). Spec names the
          operation, not the Java method — no spec amendment.
        - "Startup probes" pinned to the adapter-level endpoint probes in
          SignalAdapter/SimpleXAdapter (SignalSubprocess.java:124 "caller
          (SignalAdapter) probes the endpoint separately"); the subprocess
          crash-restart loops already do full-jitter exponential backoff
          (SignalSubprocess.java:238-245, SimpleXSubprocess backoffDelay)
          and are explicitly out of sub-item (b).
        - findFirstString call sites: SimpleXMessageCodec.java:528,536;
          handle tables: SimpleXAdapter.java:90-91; HttpClient sites:
          SimpleXAdapter + SimpleXWebSocketClient.
      File accounting: ~10 module main + ~4 module test (incl. new) +
      MessagingStartup + 9 provider test fakes ≈ 24; files_budget 26 with
      cascade headroom.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-05
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: findFirstString fix (§C-FINDFIRSTSTRING, attacker-influenced key search) and HttpClient connect-timeout addition are security-adjacent hardening on the adapter inbound surface. Consider setting security_relevant: true and risk: medium to trigger redteam review."
    - "COMPLEXITY-RISK-CALIBRATED: acceptance item 3 bundles eight distinct changes in one bullet; if any single change becomes unexpectedly non-trivial, it will not be easy to isolate which criterion was unmet. Consider splitting or labeling sub-items for reviewer traceability."
  blockers: []
---

# M1-148: MessagingAdapter SPI lifecycle + low-level cleanup

## Context

Module-scoped `infochat-messaging-adapter` SPI-shape + low-level hygiene bundle:
the SPI lacks `start()`/`stop()` (so `MessagingStartup` uses reflective
dispatch with `catch(Throwable)`); the `finalize` SPI method shadows
`Object.finalize()`; `SimpleXConfig` validates lazily vs `SignalConfig`'s eager
`@Startup`; adapter startup probes busy-wait; the SignalJsonRpcClient oversize
drain is per-char; the SimpleXAdapter handle table grows unbounded;
`findFirstString` does an attacker-influenced key search; adapter `HttpClient`
instances lack connect timeouts.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Refine decisions (pinned at budget-breach escalate, 2026-06-05)

The original acceptance named the intent but put the blast radius outside its
own `files_scope`. The refine pins, ground-truthed against the code:

**Rename target.** `finalize(MessageHandle, String)` → `finalizeMessage`. The
audit disposition (§C-FINALIZE-SHADOW: "Rename to `shutdown`/`stop`") misread
the method as lifecycle teardown; its javadoc and the spec
(`docs/spec/messaging.md` §Required SPI surface, "Finalize. Given a handle,
mark the message complete") define it as message-placeholder completion. A
shutdown-flavoured name would collide with the new lifecycle `stop()` this
same ticket introduces. The spec pins the operation name, not the Java
identifier — no spec amendment.

**Rename blast radius (all mechanical, authorized in `test_plan.modifies`):**

| Site | Files |
|---|---|
| SPI declaration + javadoc | MessagingAdapter (+ javadoc `{@link}` refs in MessageHandle, ProgressNotifier if present) |
| Implementations | SimpleXAdapter:274, SignalAdapter:276, InMemoryAdapter:150 |
| In-module test callers | AdapterCapabilityContractTest:77, InMemoryAdapterTest:65/86/94 |
| Provider test fakes (9) | AdapterRegistryTest, StartupGatesTest, ProductionAdapterActivationTest, DigestWorkerTest, InboundRouter{IntakeOrdering,ContactIdRedaction,ConfirmCancel,Normalize,ProbationOrdering}Test |

**Lifecycle dispatch.** `MessagingStartup.startAllAdapters` switches from
reflective `Class.getMethod("start")` + `NoSuchMethodException` arm to direct
`adapter.start()` (the SPI default makes the no-transport case a no-op). The
per-adapter failure-isolation catch stays — one adapter's start failure must
not stop the others (§6.7 resilience), and `ProductionAdapterActivationTest`
pins that behavior.

**"Startup probes" (sub-item b).** Means the adapter-level endpoint probe
loops in `SignalAdapter`/`SimpleXAdapter` (`SignalSubprocess.java:124`:
"caller (SignalAdapter) probes the endpoint separately"). The subprocess
crash-restart loops already implement full-jitter exponential backoff
(`SignalSubprocess.java:238-245`, `SimpleXSubprocess#backoffDelay`) and are
NOT in scope — their timing-pinned tests stay untouched.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-FINALIZE-SHADOW, §C-SPI-LIFECYCLE,
  §C-SIMPLEXCONFIG-LIFECYCLE, §C-ADAPTER-BACKOFF, §C-SIGNAL-DRAIN, §C-SIMPLEX-HANDLE-TABLE,
  §C-FINDFIRSTSTRING, §C-HTTPCLIENT-NOTIMEOUT; `opus-47-full-handout.md` §F-MAINT-41/47/50/51/85, F-PERF-02/11.
