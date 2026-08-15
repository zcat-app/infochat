---
id: M1-834
title: "Admin-notify on LLM circuit-breaker open transitions"
status: done
created: 2026-08-13
last_updated: 2026-08-15
flow: tick
reproduction: >-
  LlmCircuitBreakerRegistryTest.openedTransitionEmitsExactlyOneEventPerTripOrReopen
  (written and run RED at start — compile-failure RED against the absent
  event type and 5-arg sink seam, .scratch/tick-red-M1-834.log) — tripping an endpoint's
  breaker OPEN and re-opening it on a failed cooldown probe must each raise
  one operator-visible notification event while denied acquisitions raise
  none; today the only emissions are LOG.warnf lines inside the breaker
  (LlmCircuitBreakerRegistry.java:437, :447). Companion probe, run at
  analysis time: `grep -rn 'notifyOnce\|ADMIN-NOTIFY'
  infochat-llm-adapter/src/main` returns nothing — a persistently dead
  backend produced no operator-visible signal for ~19 h on 2026-08-13
  (.scratch/setup-hurdles.md item 13 LIVE INSTANCE addendum).
analysis_ref: docs/plan/m1/tick-analysis/backend-outage-surfacing.md
blocked_by: []
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmCircuitBreakerRegistry.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmCircuitBreakerOpenedEvent.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmCircuitBreakerRegistryTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/BreakerOpenedAdminNotifier.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/BreakerOpenedAdminNotifierTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/BreakerOpenedNotificationIT.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/BreakerOpenedAdminNotifier.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/BreakerOpenedAdminNotifierTest.java
  - docs/spec/security.md
  - infochat-provider/src/main/resources/application.properties
  - infochat-collector/src/main/resources/application.properties
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Breaker state on the readiness payload and the periodic LLM probe — both
    deliberately deferred (M1-818 out_of_scope; AdapterReadinessCheck.java:42-46);
    the readiness payload is an unauthenticated disclosure surface.
  - >-
    RECOVERY notifications — the breaker close keeps its LOG.infof; no
    notifier call on close.
  - >-
    Any breaker state-machine, threshold, cooldown, or decorator attribution
    change (M1-606/M1-769 semantics untouched).
  - >-
    The pre-existing raw-endpoint text in the breaker's own WARN label
    (LlmCircuitBreakerRegistry.java:318-320) — pre-existing, §1; noted as a
    follow-up observation in the commit message, not fixed inline.
  - >-
    error.chat.unavailable wording and every ChatAgent degrade path
    (spec-pinned, security.md:1653-1655); M1-818's shipped behavior; restart
    policies / why the backend was down (batch D owns lifecycle).
acceptance:
  - "LlmCircuitBreakerRegistryTest.openedTransitionEmitsExactlyOneEventPerTripOrReopen (the reproduction, written and run RED at start) passes — feed `failure-threshold` consecutive unreachable records for the embeddings key, assert exactly one event carrying kind=EMBEDDINGS and the endpoint; advance the fixed clock past the cooldown, acquire (the HALF-OPEN probe), record unreachable again, assert exactly one more event flagged probe-reopen; interleave denied `tryAcquireForEmbeddings` calls inside the cooldown and assert they emit NOTHING (analysis P4)."
  - "LlmCircuitBreakerRegistryTest.recoveryAndHealthyCallsEmitNoEvent passes (failure-mode, analysis P4) — recordReachable after a trip, and failure-free calls throughout, emit zero events; a mutation that fires on close or on every acquire fails this."
  - "The 4-arg seam constructor keeps its signature (analysis P3) — Verify: `git diff --name-only` shows none of the 17 existing `new LlmCircuitBreakerRegistry(` sites nor QueryAnchorTranslatorTest.BreakerStub edited (census grep in the ticket body re-run at start), and `./mvnw -B -pl infochat-llm-adapter,infochat-provider -am test-compile` is green with the new 5-arg seam carrying the sink."
  - "BreakerOpenedAdminNotifierTest.notifierReceivesCoalescingKeyNamingKindAndEndpoint passes (provider; collector twin mirrors it) — a stubbed ThrottledAdminNotifier (RecordingAdminNotifier pattern) receives exactly one notifyOnce per observed event with key `llm-breaker-open:<kind>:<redacted-endpoint>`, constant error_class `llm-breaker-open`, and a message naming the endpoint, the SPI, and the user-facing degrade (PerSourceUnknownTracker.java:180-190 key-shape precedent: per-instance key, constant error_class scrape token)."
  - "BreakerOpenedAdminNotifierTest.userinfoInEndpointIsRedactedFromKeyAndMessage passes (failure-mode, analysis P6) — an event carrying `https://user:pass@example-host:11434/v1` yields key and message with the credential redacted via LlmHttpSupport.redactUserInfo; assert the literal `user:pass` substring appears in neither (M1-401 redaction convention)."
  - "BreakerOpenedNotificationIT.firedEventPersistsAdminNotificationRow passes (boundary siting, engineering-rules §8 assertion adequacy) — a %test Provider boot fires the CDI event through the real observer into the real notifier against the Testcontainers DB and asserts `ThrottledAdminNotifier.getState(key)` returns the row; a mutation that leaves the observer unregistered (missing @ApplicationScoped / wrong event type) fails it."
  - "The collector carries the identical observer bean with a sync-note comment naming its provider twin (analysis P7; the M1-818 sanctioned-duplication precedent) — Verify: BreakerOpenedAdminNotifierTest in infochat-collector passes and `diff` of the two observer bodies shows only package/comment differences; the shared DB-row throttle coalesces the two services' emissions under one key (ThrottledAdminNotifier.java:39-46), stated in each class javadoc."
  - "The fire happens outside the synchronized breaker monitor (analysis P2) — Verify: `grep -n 'sink[.]' infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmCircuitBreakerRegistry.java` shows the sink invocation in `recordUnreachable` on a line below the synchronized block's closing brace (EndpointBreaker.recordUnreachable returns the transition outcome and the registry fires after it returns), and `grep -c 'notifyOnce' infochat-provider/src/main/java/app/zcat/infochat/provider/chat/BreakerOpenedAdminNotifier.java infochat-collector/src/main/java/app/zcat/infochat/collector/eval/BreakerOpenedAdminNotifier.java` prints `1` for each file — the observer body is exactly one notifyOnce call, which never propagates (ThrottledAdminNotifier.java:245-279; analysis P5)."
  - "Spec amendment recorded, exact wording approved by the user at implementation time (engineering-rules §12; M1-779 rides-the-diff shape — analysis P8): docs/spec/security.md §Failure handling's circuit-breaker paragraph gains a rule-text sentence stating that a trip and each failed-probe re-open raise a throttled admin notification under error_class `llm-breaker-open` naming the endpoint and SPI, and that recovery needs none. Verify: git diff shows rule-text only — no dates, ticket IDs, or report citations in spec prose."
  - "Truth-sites follow (engineering-rules §11) — Verify: grep -n 'notification\\|notify' infochat-provider/src/main/resources/application.properties shows the breaker comment block (:443-459) naming the admin notification, likewise the collector twin block; the registry's state-machine javadoc names the event emission."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmCircuitBreakerRegistryTest.java (new cases: openedTransitionEmitsExactlyOneEventPerTripOrReopen, recoveryAndHealthyCallsEmitNoEvent, deniedCallsDuringOpenEmitNoEvent)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/BreakerOpenedAdminNotifierTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/BreakerOpenedNotificationIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/BreakerOpenedAdminNotifierTest.java
  preserves:
    - >-
      All existing LlmCircuitBreakerRegistryTest cases and the 17
      `new LlmCircuitBreakerRegistry(` construction sites plus
      QueryAnchorTranslatorTest.BreakerStub — the 4-arg seam is unchanged.
    - >-
      The decorator attribution pins (BudgetedLlmProviderTest and siblings):
      short-circuits never advance the counter, application errors record
      reachable.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Failure handling
decision_refs:
  - D22
  - D56
reviews:
  - round: 1
    date: 2026-08-15
    verdict: REWORK
    checks: "SPEC-TRUTHNESS WARN (rule-text OK, user-approval record for exact wording missing — record next round), SECURITY PASS, TEST-ADEQUACY FAIL, MAINTAINABILITY FAIL, SCOPE PASS"
    diff_stats: "13 files, +413/-22"
    rework_items: 2
    verdict_file: .scratch/tick-review-M1-834-r1.txt
  - round: 2
    date: 2026-08-15
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS (approved wording recorded, .scratch/tick-spec-approval-M1-834.md), SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS; rework items 1+2 SATISFIED"
    diff_stats: "13 files, +475/-23 (fix hunks 5 files, +71/-10)"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-834-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  lint: "tick-lint: 0 findings, 0 BLOCKERs (after copying the gitignored analysis doc into the worktree)"
  self_check: >-
    PASS. Census re-run clean: grep returns exactly the enumerated sites,
    none new; the prose count "18 sites / 17 direct" undercounts the same
    complete enumeration by 2 (it lists 19 `new` + 1 subclass = 20) —
    non-blocking, the preserve-set is identical either way. Citations
    spot-checked green: registry :437/:447 WARN lines, :144-145 stale
    javadoc claim, ThrottledAdminNotifier :39-46/:132-139/:245-279/:372,
    PerSourceUnknownTracker :56/:179/:185-190 (actual path eval/reeval/),
    LlmHttpSupport.redactUserInfo :382, provider application.properties
    :443-459 breaker block, security.md :1684 breaker paragraph. Analysis
    P1-P8 all landed in Pitfalls. blocked_by empty: no cross-ticket tests
    to trace. No replaces:.
---

# M1-834: Admin-notify on LLM circuit-breaker open transitions

## Context

Live, 2026-08-13 (.scratch/setup-hurdles.md item 13 addendum): the stack came
back from a host restart without the ollama profile; the configured embeddings
endpoint was unresolvable, the per-endpoint circuit breaker tripped and
re-OPENED on every 30 s cooldown probe, chat turned `error.chat.unavailable`
on some requests while answering degraded on others — and for ~19 h NOTHING
reached the operator. The breaker's only emissions are WARN lines inside
provider logs (LlmCircuitBreakerRegistry.java:437, :447); every analogous
sustained infrastructure failure in security.md §Failure handling (Stage-2
infra failure :1563, fetcher ladder :1575, tagger :1603, sustained-no-tags
:1607-1612) raises a throttled admin notification — the breaker paragraph
(:1684-1705) is the gap. Shared analysis:
docs/plan/m1/tick-analysis/backend-outage-surfacing.md.

## Root cause

`LlmCircuitBreakerRegistry.EndpointBreaker.recordUnreachable` logs the
CLOSED→OPEN trip and the HALF_OPEN→OPEN re-open and nothing more; no admin
notification exists anywhere on the breaker surface (`grep -rn 'notifyOnce'
infochat-llm-adapter/src/main` → empty). The notifier that would carry it
(ThrottledAdminNotifier, infochat-core) is unreachable from the registry's
module: infochat-core and infochat-llm-adapter are both leaf modules with
`(none)` dependencies (docs/design/09-reference.md §9.1), and only the two
services see both. (Discrepancy noted in the analysis: the registry javadoc
at :144-145 claims core depends on llm-adapter; core/pom.xml declares no such
edge.)

## Pitfalls

- P1: the notifier call cannot live in llm-adapter (module DAG, 09-reference
  §9.1; llm-adapter's enforcer bans sibling edges) — the registry fires a CDI
  `Event`; observer beans in provider and collector call the notifier
  (PerSourceUnknownTracker.java:56/179/185 precedent).
- P2: never fire inside the synchronized EndpointBreaker monitor — notifyOnce
  does JDBC; the transition method returns the outcome, the registry fires
  after it returns.
- P3: 17 direct `new LlmCircuitBreakerRegistry(` sites + one subclass
  (QueryAnchorTranslatorTest.BreakerStub:59) use the 4-arg seam — keep its
  signature, delegate with a no-op sink, add a 5-arg seam for tests.
- P4: notify transitions only (CLOSED→OPEN, HALF_OPEN→OPEN), never per-denied
  acquisition and never on close — a persistent outage yields one ADMIN-NOTIFY
  per throttle window, not per chat turn.
- P5: CDI fire is synchronous on the failing call's thread and observer
  exceptions propagate — the observer body is exactly one `notifyOnce` call,
  whose contract never propagates (ThrottledAdminNotifier.java:245-279); no
  other logic (§7).
- P6: base-urls can embed `user:pass@` — key and message redact via
  `LlmHttpSupport.redactUserInfo` (M1-401 convention, pinned by
  LlmRouterStartupGuardRedactionTest). Pre-existing raw endpoint in the
  breaker's own WARN label is out of scope (§1).
- P7: the observer bean exists twice (provider + collector — no shared module
  can hold it), identical with a sync note; the DB-row throttle coalesces
  cross-service under one key.
- P8: the security.md amendment is a rides-the-diff record (§12) — the section
  already commits to throttled admin notification for analogous failures; the
  user approves the exact wording.

## Approach

Derived from spec_refs: security.md §Failure handling's admin-notification
pattern extended to the breaker paragraph it already contains.

- **Files to touch** (guidance, not an allowlist): LlmCircuitBreakerRegistry
  (outcome-returning `recordUnreachable`, sink field + 5-arg seam, CDI
  constructor wires `Event<LlmCircuitBreakerOpenedEvent>::fire`, javadoc);
  new `LlmCircuitBreakerOpenedEvent` record + sink functional interface in
  `app.zcat.infochat.llm.routing`; new observer +
  unit test in each service; new provider wiring IT; security.md §Failure
  handling; the breaker comment blocks in both services'
  application.properties (provider :443-459 and the collector twin).
- **Steps in order**: (1) event record + sink + registry emission with the
  registry-test cases green (mechanism, plain JUnit — llm-adapter has no
  Quarkus harness, the fixed-Clock seam pattern already in the file);
  (2) provider observer + unit test + wiring IT (boundary proof);
  (3) collector twin + unit test (P7); (4) spec amendment (user-approved
  wording) + application.properties comment sentences — last, they record the
  landed shape.
- **Controls to preserve (§10)**: the breaker state machine (threshold,
  cooldown, single probe, M1-769 probe-ownership release); the existing
  WARN/INFO log lines — unchanged and still first, the notification is
  additive; decorator attribution rules (short-circuits never advance the
  counter; application errors record reachable); every existing registry test
  and construction site (P3).
- **Pitfall→mitigation**: P1→event + service-side observers; P2→outcome
  return, fire outside; P3→additive 5-arg seam; P4→transition-only emission
  pinned by the two new registry cases; P5→one-call observer + the IT;
  P6→redaction test; P7→twin + sync note; P8→acceptance item 9.

## Definition of done

Mirror of the YAML `acceptance:` list: the reproduction passes (one event per
trip and per failed-probe re-open, silence for denials); recovery and healthy
calls emit nothing; the 4-arg seam and its 17 call sites are untouched; both
services' observers notify with the per-endpoint key and constant error_class;
the userinfo-redaction case passes; the provider IT persists the
admin_notification_state row through the real wiring; the collector twin ships
with its sync note; the fire site is outside the synchronized monitor and the
observer is one notifyOnce call; the security.md sentence lands as
user-approved rule text; the application.properties breaker comments and
registry javadoc name the signal; `mvn verify` from the repo root is green.

## Verification

- P1 → the build's module-DAG enforcers stay green; the observer classes live
  in the service modules by construction.
- P2 → grep probes (acceptance item 8): the sink invocation sits below the
  synchronized block's closing brace in the registry, and each observer file
  contains exactly one notifyOnce call — outcome returned from the
  synchronized method, sink fired after.
- P3 → acceptance item 3's zero-diff census probe + green test-compile.
- P4 → LlmCircuitBreakerRegistryTest.deniedCallsDuringOpenEmitNoEvent feeds
  repeated acquisitions inside the cooldown after a trip and asserts zero
  events; .recoveryAndHealthyCallsEmitNoEvent feeds close/healthy evidence
  and asserts zero events.
- P5 → BreakerOpenedNotificationIT.firedEventPersistsAdminNotificationRow —
  the real CDI wiring, real notifier, real (Testcontainers) DB row.
- P6 → BreakerOpenedAdminNotifierTest.userinfoInEndpointIsRedactedFromKeyAnd-
  Message — hostile `user:pass@` input, asserts absence of the credential in
  both sinks.
- P7 → the two BreakerOpenedAdminNotifierTest twins pin the identical
  key/error_class shape; sync-note comment in both classes.
- P8 → acceptance item 9's git-diff probe (rule-text only).
- acceptance item 11 → `mvn verify` from the repo root (engineering-rules §5).

## Out-of-scope

Prose mirror of the YAML list. No breaker state on the readiness payload and
no periodic LLM probe (both deliberately deferred — M1-818's out_of_scope and
AdapterReadinessCheck.java:42-46; the payload is an unauthenticated disclosure
surface). No recovery notification. No change to breaker parameters, state
machine, decorators, ChatAgent degrade paths, or the spec-pinned
`error.chat.unavailable` string. The pre-existing raw-endpoint text in the
breaker's WARN label is noted in the commit message as a follow-up
observation, not fixed inline (§1). M1-818's shipped behavior is untouched.
No pre-existing test is modified by this ticket.

## Census

Constructor-call census (the 4-arg seam this ticket must NOT break) —
`grep -rn 'new LlmCircuitBreakerRegistry(\|extends LlmCircuitBreakerRegistry'`
at analysis time returned 18 sites, all disposed as PRESERVE-UNCHANGED:
LlmCircuitBreakerRegistryTest.java:57/:227/:246/:304; BudgetedLlmProviderTest
.java:57/:127; LlmChainFixtures.java:95; ChatAgentRefusalInterceptionTest
.java:222; ChatAgentRefusalInterceptTest.java:273; ChatAgentProvenanceTest
.java:332; ChatAgentTest.java:1590; ChatAgentReplyLanguageTest.java:254;
SemanticSearchToolIT.java:111; RetrievalWorldPredicateIT.java:109;
SemanticSearchToolHybridIT.java:119; InboundRouterChatPersistFailureTest
.java:72; InboundRouterAcquisitionCountTest.java:72;
InboundRouterChatProgressTest.java:276/:286; QueryAnchorTranslatorTest.java:59
(the BreakerStub subclass). Re-run at `start`; any new site joins the
preserve list.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-834`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-834-backend-outage-surfacing-1.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.

## Round 1 rework

1. Finding 1: extend BreakerOpenedNotificationIT with a container-wiring
   probe — inject the real LlmCircuitBreakerRegistry bean, trip the %test
   embeddings endpoint (3x recordUnreachableForEmbeddings), assert
   ThrottledAdminNotifier.getState("llm-breaker-open:EMBEDDINGS:" +
   endpoint) returns a row with errorClass llm-breaker-open — evaluated via
   the method failing under the no-op-sink mutation at
   LlmCircuitBreakerRegistry.java:172 and passing in round 2's
   `mvn verify` (full BreakerOpenedNotificationIT green).
2. Finding 2: delete "; M1-834" from LlmCircuitBreakerOpenedEvent.java:5 —
   evaluated via `grep -n 'M1-834' infochat-llm-adapter/src/main/java/\
app/zcat/infochat/llm/routing/LlmCircuitBreakerOpenedEvent.java` returning
   nothing.

## Review observations

- Round 1 RECOMMENDED-NEW-TICKET (recorded, no decision requested; verbatim
  from the verdict): the breaker's own WARN label logs the raw configured
  endpoint, so a base-url carrying `user:pass@` puts the credential into
  provider and collector logs at WARN on every trip and re-open.
  `BreakerKey.label()` interpolates the unredacted base-url
  (LlmCircuitBreakerRegistry.java:333-336), used by both WARN lines
  (:453-455, :464-466). Pre-dates this diff; declared out of scope (§1) with
  a commit-message follow-up note. Fix shape: redact via
  `LlmHttpSupport.redactUserInfo` (M1-401 convention).
