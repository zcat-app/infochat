---
id: M1-109
title: "Multi-adapter production shape IT"
status: done
created: 2026-05-26
last_updated: 2026-06-01
blocked_by:
  - M1-108
  - M1-105
files_budget: 6
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
  - infochat-provider/src/test/resources/application.properties
  - infochat-provider/pom.xml
  - infochat-messaging-adapter/pom.xml
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/FakeSimpleXProcess.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/FakeSignalCli.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/** — no collector changes
  - any change to SimpleXAdapter or SignalAdapter internals — frozen
  - any change to InMemoryAdapter — unchanged
  - any change to MessagingAdapter SPI — not modified
  - any change to AdapterRegistry — M1-105 is frozen
acceptance:
  - "MultiAdapterProductionIT runs with SimpleX + Signal adapters simultaneously enabled (using test doubles for the subprocesses)"
  - "Cross-adapter blast radius: a SimpleX adapter failure (subprocess crash) does not affect the Signal adapter — messages on Signal continue flowing"
  - "Cross-adapter blast radius: a Signal adapter failure does not affect SimpleX"
  - "(adapter, contact_id) isolation across SimpleX and Signal: a SimpleX user and a Signal user with coincidentally identical contact_id strings are distinct users with independent state"
  - "Last-admin protection is global across adapters: cannot leave zero admins even when revoking from one adapter while the other has no admins"
  - "/grant-admin on SimpleX does not elevate on Signal and vice versa"
  - "MultiAdapterProductionIT.simpleXCrashDoesNotAffectSignal passes — SimpleX subprocess crashes; Signal messages continue flowing"
  - "MultiAdapterProductionIT.signalCrashDoesNotAffectSimpleX passes — Signal subprocess crashes; SimpleX messages continue flowing"
  - "MultiAdapterProductionIT.crossAdapterIsolation passes — same contact_id on both adapters produces distinct user rows"
  - "MultiAdapterProductionIT.lastAdminGlobalAcrossAdapters passes — revoking the sole admin on SimpleX is blocked when Signal also has zero admins"
  - "MultiAdapterProductionIT.grantAdminIsAdapterScoped passes — /grant-admin on a SimpleX user does not flip is_admin on a Signal user with coincidentally identical contact_id, and vice versa"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
  preserves:
    - all tests currently green on main
    - MultiAdapterIsolationIT from M1-105 passes unchanged
spec_refs:
  - docs/spec/messaging.md §Per-adapter trust level and identity
  - docs/spec/security.md §Per-adapter admin threat profile
decision_refs:
  - D46
reviews:
  - round: 1
    date: 2026-06-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 886
      removed: 48
overrides: []
aborted_attempts: []
reopens:
  - date: 2026-05-31
    prior_deferred_reason: blocked-on-new-ticket
    prior_deferred_on: M1-120
    reason: M1-120 landed (production CDI ready)
redteam_findings: []
redteam_audits:
  - date: 2026-06-01
    verdict: CLEAN
    base: 5f6300f
    head: a374907
    verdict_file: docs/plan/m1/redteam/M1-109-2026-06-01.md
    out_of_model_count: 4
    note: |
      CLEAN — no spec/diff gap. M1-109 is a test-only ticket; the IT
      faithfully exercises the cross-adapter security promises in
      security.md (per-(adapter, contact_id) identity isolation, global
      last-admin protection, inbound-adapter-scoped /grant-admin,
      blast-radius isolation across adapters). 4 OUT-OF-MODEL
      observations recorded in the verdict file (fake-vs-prod drift on
      multi-connect, audit-trigger disable in cleanup, acceptance
      asymmetry on grantAdminIsAdapterScoped vice-versa direction,
      bootstrap-admin cleanup prefix mismatch). None block merge; all
      are test-side or in-trust-boundary concerns. The acceptance
      asymmetry could feed a small follow-up test-coverage ticket if
      desired — production code is symmetric so it is not a security
      gap today.
escalations:
  - date: 2026-05-31
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (developer-initiated escalation before implementation)

      Trigger: ticket Notes name FakeSimpleXProcess (from M1-103) and
      FakeSignalCli (from M1-107) as the test doubles for the
      subprocesses. Both classes live in
      infochat-messaging-adapter/src/test/java/... and are NOT
      accessible from infochat-provider/src/test/... where the IT is
      required to live (verified via grep: infochat-provider/pom.xml
      has only a plain compile-time dep on infochat-messaging-adapter,
      no <type>test-jar</type>). The minimum honest plumbing to bridge
      this is two pom.xml edits (~3 lines each), but both files are
      outside the listed files_scope and would trip the reviewer's
      SCOPE-DRIFT-CHECK.

      Alternatives considered and rejected:
      - In-test fake @ApplicationScoped MessagingAdapter beans (the
        AdapterRegistryTest pattern): blast-radius tests would be
        tautological — two separate POJOs obviously share no state, so
        a crash on one demonstrably can't affect the other. Proves
        nothing about real SimpleXAdapter ↔ SignalAdapter coupling
        vectors (shared thread pools, HTTP clients, CDI graph,
        subprocess lifecycle handlers).
      - Inline-duplicated WS/JSON-RPC stubs: ~250 lines of protocol
        code duplicated from M1-103 and M1-107. Maintenance hazard.
      - No-op binaries (/bin/sleep): both adapters end up degraded;
        no one-up scenario.
      - Move IT to infochat-messaging-adapter/src/test/: loses access
        to Provider beans (GrantAdminCommandHandler, InboundContext,
        AdapterRegistry) that acceptance items 6, 9, 12 require.
  - date: 2026-05-31
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (developer-initiated escalation mid-implementation, after the
      prior budget-breach refine landed the test-jar Maven plumbing).

      Trigger: post-refine survey of the production CDI graph surfaced
      two deeper structural gaps the prior escalation didn't capture.

      Gap 1 — Production CDI wiring for SimpleX/Signal is absent.
      Verified via grep: no @Produces method for SimpleXAdapter or
      SignalAdapter exists anywhere in infochat-provider/src/main/ or
      infochat-messaging-adapter/src/main/. Only InMemoryAdapter has
      a producer (AdapterRegistry.java:106). AdapterRegistry Gate 2
      ("every name in CSV resolves to a registered bean") would
      reject `infochat.adapters=simplex,signal` today because
      Instance<MessagingAdapter> only resolves to InMemoryAdapter.
      The v1 D46 production deployment shape (SimpleX + Signal
      simultaneously enabled) is not actually deployable from main as
      of this point.

      Gap 2 — MessagingStartup doesn't call adapter.start().
      MessagingStartup.java:60 only logs, with a comment confirming
      the design defers the actual start() call to "T3-A's
      SimpleX/Signal beans". That landing was never explicitly
      scheduled as its own ticket.

      Gap 3 — FakeSimpleXProcess and FakeSignalCli are package-private.
      Both fakes are declared `final class` (no public modifier) with
      package-private constructors. The test-jar bridge gives the IT
      classpath access but Java's package visibility still blocks
      cross-package construction. Either the IT lives in the Fakes'
      package (violates files_scope path) or the Fakes are made
      public (out of files_scope).

      Combined impact: M1-109 as scoped cannot be implemented as
      "test-only" — it would need to land production CDI wiring +
      MessagingStartup integration + Fake visibility + the IT itself,
      ~9 files, materially exceeding the budget-6 refine and
      contradicting "test-only ticket — no production code changes"
      framing.

      Resolution: defer M1-109 on a new prerequisite ticket (M1-120)
      that lands the production CDI wiring for SimpleX + Signal +
      MessagingStartup integration. When M1-120 ships, M1-109 reopens
      with the IT-only scope and a small additional refine for Fake
      visibility (2 single-line edits).
  - date: 2026-05-31
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (developer-initiated escalation before implementation, post-reopen
      after M1-120 landed).

      Trigger: M1-109 IT must construct FakeSimpleXProcess (M1-103) and
      FakeSignalCli (M1-107) from infochat-provider's test classpath, but
      both fakes are declared `final class` (no public modifier) with
      package-private constructors. The IT lives at
      `app.zcat.infochat.provider.messaging.MultiAdapterProductionIT` per
      files_scope; the fakes live at
      `app.zcat.infochat.messaging.impl.simplex.FakeSimpleXProcess` and
      `app.zcat.infochat.messaging.impl.signal.FakeSignalCli`. Cross-package
      access to package-private types/constructors is impossible without
      visibility changes — verified via grep:
        infochat-messaging-adapter/src/test/.../FakeSimpleXProcess.java:38:
          final class FakeSimpleXProcess implements AutoCloseable {
        infochat-messaging-adapter/src/test/.../FakeSimpleXProcess.java:52:
          FakeSimpleXProcess() throws IOException {
        infochat-messaging-adapter/src/test/.../FakeSignalCli.java:33:
          final class FakeSignalCli implements AutoCloseable {
        infochat-messaging-adapter/src/test/.../FakeSignalCli.java:43:
          FakeSignalCli() throws IOException {

      The two visibility edits (add `public` modifier on class + constructor
      for each fake) target files NOT in the current files_scope:
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/FakeSimpleXProcess.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/FakeSignalCli.java

      This was anticipated. The prior reopens note (2026-05-31, recorded
      when M1-120 landed) explicitly said: "When M1-120 ships, M1-109
      reopens with the IT-only scope and a small additional refine for
      Fake visibility (2 single-line edits)." files_budget was set to 6
      (= 4 current files_scope + 2 visibility edits) for exactly this
      refine.

      Alternatives considered and rejected:
      - In-package wrapper in app.zcat.infochat.messaging.impl.simplex/.signal:
        same scope expansion (2 new files in the fakes' packages),
        plus a new abstraction with no production purpose.
      - Move the IT to the fakes' package: violates files_scope path
        for MultiAdapterProductionIT.java and loses access to Provider
        beans (GrantAdminCommandHandler, InboundContext, AdapterRegistry,
        DataSource) that acceptance items 4-6, 9-11 require.
      - Reflection from cross-package: package-private constructors are
        also blocked by JPMS-style access checks unless setAccessible
        is used, which would be a fragile and reviewer-unfriendly
        workaround for what was already planned as a visibility refine.

      Resolution recommendation: refine to widen files_scope to 6 entries
      (current 4 plus the two fake source files); files_budget already 6
      remains unchanged. The risk: low rating could also be revisited per
      the still-open clarity_check warning (recommend medium given
      security_relevant: true and authorization-invariant coverage).
revisions:
  - date: 2026-05-31
    reason: budget-breach rework — extend files_scope to include the test-jar Maven plumbing required to expose FakeSimpleXProcess (M1-103) and FakeSignalCli (M1-107) on the Provider test classpath; bump files_budget 4→6 for headroom
    prior_values: |
      files_budget: 4
      files_scope:
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
        - infochat-provider/src/test/resources/application.properties
  - date: 2026-05-31
    reason: budget-breach rework (round 1) — widen files_scope to the two fake source files so the public-visibility edits required to construct FakeSimpleXProcess + FakeSignalCli from infochat-provider's test classpath are in-scope; bump risk low→medium per the standing clarity_check WARN (security_relevant ticket covering authorization invariants)
    prior_values: |
      risk: low
      files_scope:
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
        - infochat-provider/src/test/resources/application.properties
        - infochat-provider/pom.xml
        - infochat-messaging-adapter/pom.xml
clarity_check:
  date: 2026-05-31
  verdict: WARN
  warnings:
    - "COMPLEXITY-RISK-CALIBRATED: risk: low is under-calibrated for a security_relevant ticket whose acceptance criteria directly test global last-admin protection and adapter-scoped /grant-admin elevation invariants; risk: medium would be more appropriate"
  blockers: []
---

# M1-109: Multi-adapter production shape IT

## Context

The final v1 integration test: SimpleX + Signal running simultaneously
in the same Provider. Proves the D46 multi-adapter commitment with
both production adapters, not just SimpleX + InMemory (M1-105).

`security_relevant: true` — cross-adapter blast radius and admin
isolation are security-load-bearing.

## Acceptance

See frontmatter. This is a test-only ticket — no production code
changes.

## Out-of-scope

- Adapter internals — all adapter tickets are frozen.
- AdapterRegistry — M1-105 is frozen.
- InMemoryAdapter — this IT exercises production adapters only.

## Notes

- **Test doubles for subprocesses.** The IT uses FakeSimpleXProcess
  (from M1-103) and FakeSignalCli (from M1-107) to avoid needing
  real adapter binaries in CI. The test doubles speak the correct
  protocol subsets to satisfy the adapters' startup and messaging
  paths.
- **Test-jar Maven bridge.** Both fakes live in
  `infochat-messaging-adapter/src/test/java/...` and are NOT on the
  Provider test classpath by default. The refined `files_scope`
  includes two pom.xml edits to bridge them: (1) publish
  `infochat-messaging-adapter`'s tests as a test-jar by adding a
  `<goal>test-jar</goal>` execution to `maven-jar-plugin` in
  `infochat-messaging-adapter/pom.xml`; (2) consume that test-jar
  by adding a `<type>test-jar</type><scope>test</scope>` dependency
  in `infochat-provider/pom.xml`. No adapter internals change — the
  fakes remain in their original test package; the bridge only
  changes packaging metadata so the existing test classes become
  importable from the Provider test classpath.
- **Profile wiring (no @QuarkusTestResource).** The IT's nested
  `QuarkusTestProfile` instantiates `FakeSimpleXProcess` and
  `FakeSignalCli` as static fields (their constructors bind
  ephemeral loopback ports). `getConfigOverrides()` returns the
  bound ports as `infochat.adapters.simplex.ws-port` and
  `infochat.adapters.signal.daemon-endpoint` so the real
  `SimpleXAdapter` and `SignalAdapter` CDI beans dial the fakes
  on startup. Static-field initialization is sufficient — no
  lifecycle manager required because the fakes' constructors are
  cheap and the test profile runs once per @QuarkusTest class.
- **Blast radius shape.** The IT forces one adapter's subprocess to
  crash (close the fake's listener) and verifies the other adapter
  continues accepting messages. This is the D46 "at-least-one-up
  readiness" commitment under failure. Closing the fake's
  ServerSocket severs the adapter's WebSocket/JSON-RPC connection;
  the adapter detects the disconnect and enters its degraded state
  while the other adapter remains untouched.
- **Adjacent code:** M1-105's MultiAdapterIsolationIT exercises the
  `(adapter, contact_id)` isolation boundary and the V5 last-admin
  trigger via two virtual adapter names ("adapter-a", "adapter-b")
  seeded by direct INSERT — it does not activate any production
  adapter CDI bean. M1-109 is the first IT to wire SimpleXAdapter +
  SignalAdapter simultaneously, exercising the same isolation
  invariants under the production adapter names.
- **D47 impact.** If D47 tickets (M1-110..M1-114) have landed before
  this ticket runs, group @mentions routed through InboundRouter hit
  the approval gate at step 3.5. The IT must pre-approve any test
  groups (INSERT a groups row with approval_status='approved') in
  test setup so the blast-radius and isolation tests reach command
  dispatch. The acceptance criteria themselves are unchanged — they
  test cross-adapter blast radius, identity isolation, and last-admin
  protection, none of which are affected by D47.
