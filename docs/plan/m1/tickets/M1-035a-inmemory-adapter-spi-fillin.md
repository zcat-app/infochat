---
id: M1-035a
title: InMemoryAdapter + SPI fill-in
status: done
created: 2026-05-17
last_updated: 2026-05-17
reviews:
  - round: 1
    date: 2026-05-17
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 1419
      removed: 117
  - round: 2
    date: 2026-05-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 1453
      removed: 117
clarity_check:
  date: 2026-05-17
  verdict: WARN
  warnings:
    - "[ACCEPTANCE-RUNNABLE item 16] Acceptance item 16 (supportsMentionByContactId = true / supportsMembershipEvents = true) has no 'Verify:' clause. Items 14 and 15, which make analogous boolean assertions about other CapabilityFlags fields, both provide 'Verify: an InMemoryAdapter unit test asserts...' clauses. Item 16 should add 'Verify: an InMemoryAdapter unit test asserts `adapter.capabilities().supportsMentionByContactId()` is true AND `adapter.capabilities().supportsMembershipEvents()` is true' for consistency and checkability."
    - "[FILES-BUDGET-PLAUSIBLE] files_scope does not list infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java, but the ticket authorizes modifying this file under 'Authorized test changes.' If the developer modifies it (required when CapabilityFlags.TrustLevel is referenced in the M1-007c test), the reviewer's negative-space check will flag it as a file outside files_scope, triggering a false-positive scope-drift escalation. Add this path to files_scope (with a comment that it is a conditional authorized modification) and increment files_budget to 13 if the entry is added."
    - "[ACCEPTANCE-RUNNABLE item 8] Acceptance item 8's stated Verify clause shows only one grep pattern ('grep -E supportsMentionByContactId CapabilityFlags.java') and relies on the parenthetical 'for each of the fourteen names listed above' to imply 14 separate runs. The stated verification command is technically incomplete; a developer following it literally would only verify one of 14 component names. Consider replacing with a single multi-name regex or listing all 14 grep invocations explicitly."
  blockers: []
blocked_by:
  - M1-007c
redteam_findings: []
redteam_audits:
  - date: 2026-05-17
    verdict: CLEAN
    base: 7a348c38^
    head: 7a348c38
    verdict_file: docs/plan/m1/redteam/M1-035a-2026-05-17.md
    out_of_model_count: 3
    note: |
      Audit ran post-/m1-tick-commit, pre-/m1-tick-merge. CLEAN — the diff
      delivers only the SPI surface + test-double InMemoryAdapter, so the
      Provider-intake / admin-gate / ban-check / audit-log / LLM threat
      surfaces aren't touched. Three OUT-OF-MODEL observations all
      describe forward dependencies on M1-035b's AdapterRegistry +
      startup-gate work: (1) LOW-trust-rejection on admin-bearing paths,
      (2) production-vs-inmemory adapter co-residency block (D46),
      (3) public unrestricted HIGH-trust constructor needs a registry-
      level gate. The InMemoryAdapter not enforcing its own declared
      maxInboundMessageBytes is acknowledged test-double posture and
      flagged so M1-035b/T3-A reviewers ensure SimpleX/Signal do honour
      the cap on production adapters.
files_budget: 12
files_scope:
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/ScopeRef.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/Identity.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/InboundMessage.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/OutboundMessage.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/AdapterTrustLevel.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/FailureCategory.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/MessagingException.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/CapabilityFlags.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/MessagingAdapter.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/impl/inmemory/InMemoryAdapter.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/impl/inmemory/InMemoryMessageHandle.java
  - infochat-messaging-adapter/src/test/java/io/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java (the M1-035 umbrella's cross-cutting inbound→register→/help→outbound IT — reserved for the umbrella commit per docs/process/workflow.md §Ticket-ID placeholder convention; this subticket asserts SPI shape + InMemoryAdapter unit behavior only)
  - any change under infochat-provider/ (the Provider-side AdapterRegistry, InboundRouter, MessagingStartup, HelpCommandHandler, AutoRegisterService all land in M1-035b and M1-035c — this subticket lands the SPI + InMemoryAdapter concrete impl only, no Provider-side wiring, no command handler)
  - any concrete adapter beyond InMemoryAdapter (no SimpleXAdapter, no SignalAdapter — those are T3-A and out of T1-E entirely; the SPI surface here MUST be shaped so they land additively without re-shaping any existing record or method)
  - any Flyway migration under infochat-core/src/main/resources/db/migration/ (T1-E is migration-free per the T1-E handoff — the messaging surface reads and writes only columns the V5/V7 schema already provides; reaching for V12 is an escalation trigger, not an authoring choice)
  - any extension of ProgressNotifier.java or ProgressStage.java (the stubs from M1-007c stay untouched — /help is short, deterministic, and bypasses the notifier per docs/spec/messaging.md §Progress notifications; T1-F's /summary is the first notifier consumer)
  - any extension of TranslationProvider.java (deferred to T2-C; the stub from M1-007c stays untouched — MVP ships English bundle only, /lang is out of v1's first vertical slice)
  - any group `@mention` dispatch path or Provider-side group-SPI wiring (group scope is deferred to T2-F; this ticket declares `ScopeRef.Group(adapterGroupId)` for type completeness so T2-F's adapter evolution does not re-shape ScopeRef, but the Provider-side group dispatch is NOT wired here and InMemoryAdapter does NOT expose a `deliverGroupMention` helper)
  - any inbound back-pressure queue / per-user-fair scheduler / synchronous-throttle-reply path from docs/design/06-messaging.md §6.3.7 (the InMemoryAdapter delivers synchronously via a direct call-through to the handler; the bounded queue + drop-newest + synchronous-throttle-reply machinery is deferred to T2-G or whenever SimpleX/Signal land)
  - any transport-layer inbound size cap enforcement from docs/design/06-messaging.md §6.2.2 (the `maxInboundMessageBytes` field on `CapabilityFlags` is populated with a generous test value so the field exists on the record, but the synchronous-drop-and-friendly-reply enforcement machinery is deferred for the same reason as inbound back-pressure)
  - any audit_log row writer triggered from the adapter or the SPI layer (adapters do not write to audit_log directly per docs/design/06-messaging.md §6.11 — Provider records auditable events; MVP auto-register also skips audit_log per the T1-E handoff's audit-log carve-out)
  - any `supportsCodeFormatting=true` declaration on InMemoryAdapter (the MVP-vs-v1 capability conflict between docs/design/00-mvp.md §4 and docs/design/06-messaging.md §6.6 is resolved in favor of MVP §4 readability — see Alternatives considered; T3-A flips the flag when SimpleX lands)
  - any startup-validation logic for supportsMarkdownLinks=true rejection (the §6.2.1 startup gate lives in M1-035b's AdapterRegistry; this subticket only ensures InMemoryAdapter declares `supportsMarkdownLinks=false` so the gate has nothing to reject)
acceptance:
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/ScopeRef.java exists, declares `public sealed interface ScopeRef` in package io.infochat.messaging, and permits exactly two records `Dm` (with a single `String contactId` component) and `Group` (with a single `String adapterGroupId` component). Verify: `grep -E 'sealed interface ScopeRef' ScopeRef.java` returns ≥1 match AND `grep -E 'record Dm\\(String contactId\\)' ScopeRef.java` returns ≥1 match AND `grep -E 'record Group\\(String adapterGroupId\\)' ScopeRef.java` returns ≥1 match"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/Identity.java exists and declares `public record Identity(String contactId, String displayName, Instant lastSeen)` in package io.infochat.messaging. Verify: `grep -E 'public record Identity\\(\\s*String contactId\\s*,\\s*String displayName\\s*,\\s*Instant lastSeen\\s*\\)' Identity.java` returns ≥1 match"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/InboundMessage.java exists and declares `public record InboundMessage(Identity sender, ScopeRef scope, String text, Instant receivedAt, String adapterMessageId)` per docs/design/06-messaging.md §6.2. Verify: `grep -E 'public record InboundMessage\\(' InboundMessage.java` returns ≥1 match AND all five component names appear in the file"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/OutboundMessage.java exists and declares `public record OutboundMessage(ScopeRef scope, String text, Instant requestedAt, String correlationId)` per docs/design/06-messaging.md §6.2. Verify: `grep -E 'public record OutboundMessage\\(' OutboundMessage.java` returns ≥1 match AND all four component names appear in the file"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/AdapterTrustLevel.java exists and declares `public enum AdapterTrustLevel` with exactly two values HIGH and LOW. Verify: `grep -E 'public enum AdapterTrustLevel' AdapterTrustLevel.java` returns ≥1 match AND `grep -E '\\bHIGH\\b' AdapterTrustLevel.java` returns ≥1 match AND `grep -E '\\bLOW\\b' AdapterTrustLevel.java` returns ≥1 match"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/FailureCategory.java exists and declares `public enum FailureCategory` with exactly two values TRANSIENT and PERMANENT per docs/spec/messaging.md §Failure handling. Verify: `grep -E 'public enum FailureCategory' FailureCategory.java` returns ≥1 match AND both `TRANSIENT` and `PERMANENT` appear as enum constants in the file"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/MessagingException.java exists, declares `public class MessagingException extends Exception`, carries a `FailureCategory category()` accessor, and exposes constructors that REQUIRE setting the category at throw site. Verify: `grep -E 'public class MessagingException extends Exception' MessagingException.java` returns ≥1 match AND `grep -E 'FailureCategory category\\(\\)' MessagingException.java` returns ≥1 match"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/CapabilityFlags.java is extended with the design §6.2 field set. The record components MUST include all of: `supportsMentionByContactId` (boolean), `supportsMembershipEvents` (boolean), `supportsCodeFormatting` (boolean), `supportsMarkdownLinks` (boolean), `supportsMultilineCode` (boolean), `supportsAttachments` (boolean), `supportsThreading` (boolean), `maxMessageBytes` (int), `maxInboundMessageBytes` (int), `maxInflightSends` (int), `maxSendsPerSecond` (int), `supportsMessageEdit` (boolean), `supportsTypingIndicator` (boolean), `minEditInterval` (Duration). Verify: `grep -E 'supportsMentionByContactId' CapabilityFlags.java` returns ≥1 match for each of the fourteen names listed above (one acceptance fragment per name)"
  - "CapabilityFlags no longer carries a `trustLevel` component (per Locked decisions — the SPI evolution moves the trust-level concept to a top-level `AdapterTrustLevel` enum referenced by the new `MessagingAdapter.trustLevel()` method per docs/design/06-messaging.md §6.2). Verify: `grep -E '\\btrustLevel\\b' CapabilityFlags.java` returns ZERO matches"
  - "the nested `CapabilityFlags.TrustLevel` enum from M1-007c is removed (replaced by the new top-level AdapterTrustLevel). Verify: `grep -E 'enum TrustLevel' CapabilityFlags.java` returns ZERO matches"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/MessagingAdapter.java is extended with the design §6.2 method shapes. The interface MUST declare all of: `String name()` (the adapter-selection key for §6.7's AdapterRegistry); `AdapterTrustLevel trustLevel()`; `Identity assertIdentity(InboundMessage msg)`. The existing `InboundHandler.onMessage` (or equivalent) signature MUST evolve to take a single `InboundMessage` parameter instead of `(String scope, String contactId, String body)` per design §6.2 — the inbound type is the load-bearing carrier for Identity + ScopeRef + body. Verify: `grep -E 'String name\\(\\)' MessagingAdapter.java` returns ≥1 match AND `grep -E 'AdapterTrustLevel trustLevel\\(\\)' MessagingAdapter.java` returns ≥1 match AND `grep -E 'Identity assertIdentity\\(InboundMessage' MessagingAdapter.java` returns ≥1 match AND `grep -E 'void onMessage\\(InboundMessage' MessagingAdapter.java` returns ≥1 match"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/impl/inmemory/InMemoryAdapter.java exists, declares `public final class InMemoryAdapter implements MessagingAdapter`, and lives under the impl/inmemory/ package per docs/design/06-messaging.md §6.1. Verify: `grep -E 'public final class InMemoryAdapter implements MessagingAdapter' InMemoryAdapter.java` returns ≥1 match AND the file's package declaration is `package io.infochat.messaging.impl.inmemory;`"
  - "InMemoryAdapter.name() returns the literal string `\"inmemory\"` per docs/design/06-messaging.md §6.6 (the literal MUST match what AdapterRegistry registers under — cross-adapter isolation invariant from docs/spec/messaging.md §Per-adapter trust level uses `(adapter, contact_id)` as the join key). Verify: `grep -E 'return \"inmemory\";' InMemoryAdapter.java` returns ≥1 match"
  - "InMemoryAdapter declares `supportsCodeFormatting = false` in its CapabilityFlags accessor per the T1-E Locked decisions (MVP-vs-v1 capability conflict resolved in favor of docs/design/00-mvp.md §4 readability). Verify: an InMemoryAdapter unit test asserts `adapter.capabilities().supportsCodeFormatting()` is false"
  - "InMemoryAdapter declares `supportsMarkdownLinks = false` per docs/design/06-messaging.md §6.2.1. Verify: an InMemoryAdapter unit test asserts `adapter.capabilities().supportsMarkdownLinks()` is false"
  - "InMemoryAdapter declares `supportsMentionByContactId = true` and `supportsMembershipEvents = true` per docs/design/06-messaging.md §6.6 (the test-coverage rationale)"
  - "InMemoryAdapter declares `trustLevel() == AdapterTrustLevel.LOW` by default (the no-arg constructor), per docs/design/06-messaging.md §6.6. The HIGH-trust opt-in is via a test-only secondary constructor `InMemoryAdapter(AdapterTrustLevel)`. Verify: an InMemoryAdapter unit test asserts `new InMemoryAdapter().trustLevel() == AdapterTrustLevel.LOW` AND `new InMemoryAdapter(AdapterTrustLevel.HIGH).trustLevel() == AdapterTrustLevel.HIGH`"
  - "InMemoryAdapter exposes test helpers `deliverDm(String contactId, String text)`, `List<OutboundMessage> sentMessages()`, `List<UpdateEvent> updateHistory(MessageHandle handle)`, and `void reset()` per docs/design/06-messaging.md §6.6 (NOT on the SPI; accessed by casting to the concrete type in tests). Verify: `grep -E 'public void deliverDm\\(' InMemoryAdapter.java` returns ≥1 match AND `grep -E 'sentMessages\\(' InMemoryAdapter.java` returns ≥1 match AND `grep -E 'public void reset\\(' InMemoryAdapter.java` returns ≥1 match"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/impl/inmemory/InMemoryMessageHandle.java exists as the InMemoryAdapter's concrete MessageHandle carrier. Whether it remains a subtype of the existing `MessageHandle` record or replaces it with a sealed-interface refactor is impl-choice; the existing `MessageHandle` opacity invariant from docs/spec/messaging.md §Message handles MUST hold across whichever shape ships"
  - "infochat-messaging-adapter/src/test/java/io/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java exists, contains at least five `@Test` methods covering: (1) Identity stability — same `contactId` across multiple inbound messages resolves to the same Identity; (2) send→update→finalize sequence produces the expected history; (3) finalize exclusivity — any update after finalize on the same handle throws MessagingException; (4) setTyping toggles are recorded in order; (5) default trustLevel is LOW and the HIGH-trust constructor flips it. Verify: `grep -cE '^\\s*@Test\\b' InMemoryAdapterTest.java` returns ≥ 5"
  - "the pre-existing MessagingSpisLoadTest.java from M1-007c continues to compile and pass after the SPI evolution. If the M1-007c smoke test referenced the removed nested `CapabilityFlags.TrustLevel` class via Class.forName, the reference is updated to the new top-level `AdapterTrustLevel`. This is an authorized test modification (see Authorized test changes) and is the ONLY pre-existing test this ticket may touch"
  - "mvn -B -pl infochat-messaging-adapter test exits 0; surefire reports show at least two test classes executing (MessagingSpisLoadTest + InMemoryAdapterTest). Verify: `grep -rE 'Tests run: [1-9]' infochat-messaging-adapter/target/surefire-reports` returns at least two matches"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-003 @QuarkusTest stubs, M1-007 cross-module AllSpisLoadIT, every M1-008 schema test, every T1-B/C/D test continue to pass alongside the SPI evolution and the InMemoryAdapter impl tests"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java (≥5 @Test methods covering identity stability, edit lifecycle, finalize exclusivity, typing-event order, and trust-level constructor behavior)
  modifies:
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (only if it references the removed nested CapabilityFlags.TrustLevel — update the reference to the new top-level AdapterTrustLevel; no other change)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - all M1-008/008a/008b/008c schema tests
    - all M1-022/023/024/025/026 ingest-source tests
    - all M1-027/028 outbox/NOTIFY tests
    - all M1-032/033/034a/034b eval-pipeline tests
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/messaging.md §Per-adapter trust level and identity
  - docs/spec/messaging.md §Identity and groups
  - docs/spec/messaging.md §Failure handling
  - docs/spec/messaging.md §Output formatting (transport view)
  - docs/design/06-messaging.md §6.1 Module layout
  - docs/design/06-messaging.md §6.2 The SPI
  - docs/design/06-messaging.md §6.6 InMemoryAdapter
decision_refs:
  - D10
  - D30
  - D46
---

# M1-035a: InMemoryAdapter + SPI fill-in

## Context

First subticket of the M1-035 umbrella (per `docs/process/workflow.md`
§Ticket-ID placeholder convention — the umbrella + subticket idiom).
M1-035 splits "land the adapter + router slice MVP exit criterion §3
needs" into three substantively-disjoint subtickets plus a
whole-topic integration test on the umbrella. This subticket lands
the **SPI fill-in** the M1-007c stub deferred and the **concrete
`InMemoryAdapter`** that backs every T1-E test path: the
records the design enumerates (`ScopeRef`, `Identity`,
`InboundMessage`, `OutboundMessage`), the two enums
(`AdapterTrustLevel`, `FailureCategory`), the `MessagingException`
type with the `FailureCategory` accessor, the `CapabilityFlags`
field expansion, the `MessagingAdapter` method-shape evolution
(`name()`, `trustLevel()`, `assertIdentity(InboundMessage)`, plus
the `InboundHandler.onMessage(InboundMessage)` signature), and the
`InMemoryAdapter` concrete impl under `impl/inmemory/` with the
test-only helpers tests cast to the concrete type to use.

M1-007c shipped a deliberately-minimum SPI stub. Its Javadoc on
`MessagingAdapter` explicitly defers Identity assertion, typing,
membership events, inbound-message records, and the transport-
layer size cap to "the first concrete-adapter ticket so the
parameter shapes can be informed by a real transport rather than
guessed." T1-E's M1-035a is that ticket and DOES fill those in,
shaped against the v1-locked `InMemoryAdapter` (the only adapter
T1-E ships) plus the design's §6.2 record set.

This is an **SPI-and-InMemoryAdapter-only** ticket. No
Provider-side code. No AdapterRegistry, no InboundRouter, no
HelpCommandHandler, no AutoRegisterService — those land in M1-035b
and M1-035c. The SPI surface is the keystone the next two
subtickets bind to; getting the record set + method shape right
here is what makes M1-035b's registry/router bookkeeping a thin
wiring layer rather than a redesign exercise.

`security_relevant: true` — the SPI's `Identity` record is the
trust anchor for the entire authorization model per decision D10.
Getting the cryptographic-contact-id-vs-display-name distinction
wrong here propagates into every command's permission check. The
InMemoryAdapter's default-LOW trust level is the safety net
against accidental privilege escalation in a test harness; tests
that exercise admin paths must opt into HIGH explicitly via the
secondary constructor.

## Definition of Done

- Six new records / enums / exception classes exist under
  `io.infochat.messaging`, with the shapes design §6.2 enumerates:
  - `ScopeRef` — sealed interface with permitted records
    `Dm(String contactId)` and `Group(String adapterGroupId)`.
    `Group` is type-complete so T2-F (groups) does not re-shape the
    interface, but no MVP code path dispatches to a `Group` scope.
  - `Identity(String contactId, String displayName, Instant lastSeen)`
    — `contactId` is the cryptographic, stable, auth-bearing
    identifier (decision D10); `displayName` is informational only
    and may change without invalidating the identity; `lastSeen`
    is informational.
  - `InboundMessage(Identity sender, ScopeRef scope, String text,
    Instant receivedAt, String adapterMessageId)` — the inbound
    record the adapter delivers to Provider. `adapterMessageId` is
    adapter-local; Provider does not interpret it.
  - `OutboundMessage(ScopeRef scope, String text,
    Instant requestedAt, String correlationId)` — what Provider
    hands to the adapter. `correlationId` ties an outbound reply
    to its inbound trigger so adapters that deduplicate on retry
    (design §6.3.5) can do so deterministically.
  - `AdapterTrustLevel` — top-level enum `HIGH` / `LOW`. Top-level
    (not nested inside CapabilityFlags) because design §6.2 makes
    trust level an adapter-instance property accessed via
    `MessagingAdapter.trustLevel()`, NOT a static capability flag.
    The existing M1-007c `CapabilityFlags.TrustLevel` nested enum
    is REMOVED in this evolution (see Alternatives considered for
    the surgical-refactor rationale).
  - `FailureCategory` — top-level enum `TRANSIENT` / `PERMANENT`
    per docs/spec/messaging.md §Failure handling. An adapter that
    cannot tell the two apart MUST default to PERMANENT.
- `MessagingException extends Exception` with a `FailureCategory
  category()` accessor. Every constructor REQUIRES setting the
  category at throw site; there is no zero-arg or category-less
  constructor (forces the throw-site discipline the spec mandates).
- `CapabilityFlags` is extended with the design §6.2 field set
  (14 components). The pre-existing M1-007c fields are preserved
  in concept; the field name is preserved where it already matched
  the design (e.g. `supportsCodeFormatting`, `supportsMarkdownLinks`,
  `supportsMessageEdit`, `minEditInterval`, `supportsTypingIndicator`,
  `supportsMentionByContactId`, `supportsMembershipEvents`). New
  fields added in this ticket: `supportsMultilineCode`,
  `supportsAttachments`, `supportsThreading`, `maxMessageBytes`,
  `maxInboundMessageBytes`, `maxInflightSends`, `maxSendsPerSecond`.
  The previous `trustLevel` component is removed — trust level is
  now an adapter-instance property accessed via
  `MessagingAdapter.trustLevel()`.
- `MessagingAdapter` is extended with the design §6.2 method shapes:
  - `String name()` — the stable adapter-selection key used by
    M1-035b's AdapterRegistry to match the configured
    `infochat.adapters` list against bean instances.
  - `AdapterTrustLevel trustLevel()` — per-instance trust level.
  - `Identity assertIdentity(InboundMessage msg)` — strongly-typed
    identity assertion; NEVER trust display name per decision D10.
  - The nested `InboundHandler` functional interface's `onMessage`
    signature evolves to `void onMessage(InboundMessage msg)` from
    the M1-007c stub's `(String scope, String contactId, String body)`.
    Provider-side dispatch (M1-035b's router) consumes the
    `InboundMessage` rather than re-deriving the parts.
  - The existing `send` / `update` / `finalize` /
    `setInboundHandler` methods are preserved with minor signature
    cleanups — `send` evolves to accept the new `OutboundMessage`
    record (or `(ScopeRef scope, String body)` — either shape
    meets acceptance; the InMemoryAdapter helpers cast to the
    concrete type for richer assertions). Pick whichever shape is
    simpler given the design's `SentMessage` record can ship in
    T3-A when SimpleX needs it.
- `InMemoryAdapter` under `impl/inmemory/` per design §6.1:
  - `package io.infochat.messaging.impl.inmemory;`
  - Default no-arg constructor → `trustLevel() = LOW` (the test-
    harness default per design §6.6).
  - Test-only secondary constructor `InMemoryAdapter(AdapterTrustLevel)`
    for admin-path tests that need HIGH.
  - `name()` returns the literal `"inmemory"`. The literal MUST
    match what M1-035b's AdapterRegistry registers under;
    cross-adapter isolation in docs/spec/messaging.md uses
    `(adapter, contact_id)` as the join key, so a mismatch
    between this string and the property value would silently
    split the test harness's users across two adapter rows.
  - `capabilities()` returns a CapabilityFlags instance with the
    MVP-correct shape: `supportsCodeFormatting=false` (Locked
    decision; see Alternatives considered),
    `supportsMarkdownLinks=false` (§6.2.1 gate),
    `supportsMentionByContactId=true` (§6.6 test-coverage
    rationale), `supportsMembershipEvents=true` (§6.6 — tests
    drive group join/leave directly when T2-F lands),
    `supportsMultilineCode=true`, `supportsAttachments=false`,
    `supportsThreading=false`, `maxMessageBytes=100_000`,
    `maxInboundMessageBytes=100_000`, `maxInflightSends=1000`,
    `maxSendsPerSecond=10_000`, `supportsMessageEdit=true`,
    `supportsTypingIndicator=true`, `minEditInterval=Duration.ZERO`.
  - `send()` records the OutboundMessage on a thread-safe
    `sentMessages()` list and returns an
    `InMemoryMessageHandle`. `update()` and `finalize()` append
    to a per-handle history (with an `isFinal` marker).
  - `update()` after `finalize()` on the same handle throws
    `MessagingException` (FailureCategory.PERMANENT — the handle
    is exhausted).
  - `setTyping()` records typing events in order.
  - `assertIdentity(InboundMessage)` returns the embedded
    `sender` Identity verbatim (in-memory adapter has no
    cryptographic verification step; the test driver is the
    identity authority).
  - Test helpers (NOT on the SPI; only reachable via cast to the
    concrete type): `deliverDm(String contactId, String text)` —
    synthesises an `InboundMessage` with `ScopeRef.Dm` + a fresh
    Identity and synchronously calls the registered
    `InboundHandler.onMessage`; `sentMessages()` snapshot;
    `updateHistory(MessageHandle)` snapshot; `typingEvents()`
    snapshot; `reset()` clears all state.
- `InMemoryMessageHandle` — the InMemoryAdapter's concrete
  `MessageHandle` carrier. Either a subtype of the existing
  M1-007c `MessageHandle` record (if it stays a single concrete
  record), or part of a sealed-interface refactor where the
  handle becomes `sealed interface MessageHandle permits
  InMemoryMessageHandle, ...future` per design §6.2. Either shape
  meets acceptance; the opacity invariant from docs/spec/messaging.md
  §Message handles MUST hold across whichever shape ships
  (callers MUST NOT inspect, persist, or pass between service
  instances).
- One unit-test class
  `infochat-messaging-adapter/src/test/java/io/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java`
  with ≥5 `@Test` methods covering the assertion list in
  acceptance.
- The pre-existing `MessagingSpisLoadTest.java` (M1-007c)
  continues to compile and pass; the only authorized
  modification is updating the reference to the removed nested
  `CapabilityFlags.TrustLevel` class to point at the new
  top-level `AdapterTrustLevel` (if the M1-007c test did
  reference it via `Class.forName`; if it did not, leave the
  file unchanged).
- `mvn -B clean verify` from the repo root exits 0. Every prior
  test continues to pass; the new InMemoryAdapter tests execute
  and pass.

## Implementation notes

- **One file per type for the SPI records, one file for the
  exception.** The handoff's `files_budget: 12` allocates one
  file per record / enum / exception / extended existing-file /
  concrete impl / concrete handle / test class. Bundling several
  records into one file is technically allowed by Java but reads
  worse and makes the diff harder to review. Each record's
  Javadoc cites the corresponding spec/design anchor.
- **`MessagingAdapter` evolution is a minimal-diff extension.**
  The interface gains three new methods (`name()`, `trustLevel()`,
  `assertIdentity(InboundMessage)`) and the `InboundHandler`
  nested interface's `onMessage` signature changes. The existing
  `send` / `update` / `finalize` / `setInboundHandler` methods
  keep their semantics; if the implementer chooses to change
  `send`'s signature to take `OutboundMessage` (closer to the
  design), that is acceptable but optional. Pick whichever is
  the smaller diff.
- **Why the trustLevel migration to a top-level enum.** Design
  §6.2 makes `trustLevel()` an adapter-instance method, NOT a
  capability flag. The M1-007c stub put `TrustLevel` on
  `CapabilityFlags` as a nested enum because at stub time the
  smaller diff was attractive. M1-035a is where the design's
  shape lands: trust level moves off the static-capability
  surface and becomes a per-instance property. The nested enum
  becomes unreachable and is removed in favor of the top-level
  `AdapterTrustLevel`. The migration is small (one usage in
  CapabilityFlags's component list, plus possibly one
  `Class.forName` reference in `MessagingSpisLoadTest`) and
  obviously surgical — every change traces directly to design
  §6.2's method signature.
- **`InMemoryAdapter` is a TEST DOUBLE.** Per design §6.6 it is
  a CDI bean that lives in the production classpath (so M1-035b's
  AdapterRegistry can discover it) but is only ever activated by
  the test-time deployment shape (`infochat.adapters=inmemory`
  exclusively). The §6.6 production-exclusion gate (which lands
  in M1-035b) prevents it from ever running alongside
  SimpleX/Signal — but that gate is M1-035b's responsibility. This
  ticket only ships the adapter; the gate that protects production
  ships next.
- **No CDI annotations YET.** The handoff puts the
  AdapterRegistry's CDI bean discovery in M1-035b. This ticket
  ships `InMemoryAdapter` as a plain Java class without
  `@ApplicationScoped` annotations because the
  `infochat-messaging-adapter` module is a plain library jar
  with no Quarkus extensions (per M1-007c's pom posture). M1-035b
  authors the CDI-bean producer that exposes `InMemoryAdapter`
  to the Provider's CDI graph — that is the natural seam for the
  test-only HIGH-trust constructor opt-in too. Keep this ticket's
  diff small and Quarkus-free.
- **Test helpers belong on the concrete class, not the SPI.**
  `deliverDm`, `sentMessages`, `updateHistory`, `reset` exist
  for tests that have a reference to the concrete
  `InMemoryAdapter` and cast to it. They are NOT on
  `MessagingAdapter`. A test that needs these helpers obtains the
  `InMemoryAdapter` directly (constructor in unit tests; CDI
  injection in M1-035b's integration tests).
- **`MessageHandle` opacity invariant.** Whichever shape ships
  (record-with-string vs. sealed-interface-with-permitted-subtypes),
  the Javadoc-stated invariants from docs/spec/messaging.md
  §Message handles MUST hold: callers MUST NOT persist, MUST
  NOT pass between service instances, MUST NOT inspect contents.
  If the implementer chooses the sealed-interface refactor, the
  permitted subtypes list MUST include `InMemoryMessageHandle`;
  if a record-with-subclass shape is chosen, `InMemoryMessageHandle`
  is a separate record carrying the in-memory id integer. Either
  shape is fine; the invariant is the spec promise.
- **`MessagingException` constructor discipline.** The category
  MUST be set at throw site — no zero-arg, no category-less
  constructor. Adapters that cannot classify a failure MUST
  default to `PERMANENT` per docs/spec/messaging.md §Failure
  handling. Encoding this in the constructor surface (no
  category-less constructor) prevents the "I forgot to set it"
  drift. If a future ticket needs a category-less constructor for
  some pattern (e.g., wrapping at a boundary), it adds the
  constructor with the explicit `PERMANENT` default in the body
  — but this ticket does not author one preemptively.
- **`assertIdentity` for InMemoryAdapter.** Returns
  `msg.sender()` verbatim. The in-memory adapter has no
  cryptographic verification step; the test driver hands it the
  Identity it wants. This matches the design §6.6 stub. A future
  HIGH-trust assertion path (verifying a queue address against a
  cached bot identity material) lives in the SimpleX/Signal
  adapter beans, NOT here.
- **No `groupExists(String adapterGroupId)` method in MVP.** The
  design §6.2 SPI lists `groupExists` for the group-admin
  auto-promote flow. That flow is deferred to T2-F (groups). T1-E
  does NOT wire group dispatch, and adding a method on the SPI
  with no consumer is speculative surface (engineering rule
  §"No defensive code for impossible scenarios" — corollary: no
  speculative API for nonexistent callers). T2-F is the right
  ticket to add it; the adapter additively gets the method then.
- **No `lifecycle start(InboundHandler)` / `stop()` methods in
  MVP.** Design §6.2 shows these as the SimpleX/Signal lifecycle
  shape (start connection, attach handler, stop on shutdown).
  InMemoryAdapter has no transport to start; its existing
  `setInboundHandler` is sufficient. M1-035b's `MessagingStartup`
  calls `setInboundHandler` directly on each activated adapter.
  T3-A may evolve the SPI to add proper `start(InboundHandler)` /
  `stop()` for SimpleX — that evolution is in scope for T3-A,
  not T1-E.
- **`InMemoryAdapter` is thread-safe.** Tests may dispatch from
  the test thread while the handler does work. Use
  `CopyOnWriteArrayList` for `sent` / `typingEvents`,
  `ConcurrentHashMap` for `updateHistory`, an `AtomicLong` for
  the handle ID generator. The concurrency cost is negligible
  for tests and prevents a flaky test from masking a real bug.

## Big-picture notes

- **The SPI evolution here is the keystone for the next two
  subtickets.** M1-035b's AdapterRegistry calls `adapter.name()`,
  `adapter.trustLevel()`, `adapter.capabilities()`, and
  `adapter.setInboundHandler(router)` — every one of those is
  shipped by M1-035a. M1-035c's HelpCommandHandler and
  AutoRegisterService consume `InboundMessage` and `Identity`
  — both shipped here. If M1-035a's record/method shapes drift
  from what M1-035b and M1-035c need, those subtickets balloon
  into SPI evolution work too. Reviewer scrutiny of this
  ticket's surface matters out of proportion to its diff size.
- **MVP-vs-v1 capability conflict for InMemoryAdapter.**
  docs/design/00-mvp.md §4 says: "the `InMemoryAdapter` reports
  `supportsCodeFormatting=false` so the test transcripts stay
  readable." docs/design/06-messaging.md §6.6 says:
  "InMemoryAdapter declares `supportsCodeFormatting = true` so
  tests exercise the code-formatting render path; the SimpleX
  adapter declares it false so tests of the plain-text fallback
  also run." These conflict because design §6.6 was written for
  the v1 build where SimpleX exists; MVP doesn't have SimpleX,
  so InMemoryAdapter is the only adapter and the MVP §4
  readability argument wins. T1-E ships
  `supportsCodeFormatting = false`; T3-A flips it to `true` when
  SimpleX lands (SimpleX itself declares it false per §6.4.2 so
  the two-adapter test-coverage argument is restored). See
  Alternatives considered for the breadcrumb the reader at T3-A
  time finds.
- **The pre-existing `CapabilityFlags.TrustLevel` migration is
  the only non-surgical move.** Every other change is additive
  (new file, new component, new method). Removing the nested
  enum + the `trustLevel` component is the one
  non-additive operation. It traces directly to design §6.2's
  shape — `trustLevel()` is a method on the adapter, not a
  static capability flag — and the migration is the smallest
  possible change to align the M1-007c stub with that shape.
  The reviewer should expect this and not flag it as scope drift;
  the alternative ("keep the nested enum so the stub stays
  untouched") would leave two TrustLevel-like types in the SPI,
  which is worse than the small migration.
- **`CapabilityFlags` is renamed by a separate `spec:` commit
  later.** docs/design/06-messaging.md §6.2 uses the name
  `AdapterCapabilities` for the record. Per the §Engineering
  rules surgical-changes rule, this ticket EXTENDS
  `CapabilityFlags` with the missing fields and does NOT rename
  the type. The design doc's `AdapterCapabilities` name is
  updated by a separate `spec:` commit at the end of the T1-E
  ticket chain (after M1-035 umbrella ships) so the design doc
  stays in sync with the impl name. T1-E does NOT author the
  spec edit; whoever lands the post-T1-E `spec:` commit picks
  whichever rename direction is the smaller diff (likely
  renaming the design's `AdapterCapabilities` to `CapabilityFlags`
  rather than vice versa, but that is a `spec:` decision, not a
  ticket decision).
- **Subticket isolation against M1-035b and M1-035c.** This
  subticket touches only files under
  `infochat-messaging-adapter/`. M1-035b's files all live under
  `infochat-provider/src/main/java/io/infochat/provider/messaging/`
  and `infochat-provider/src/test/java/io/infochat/provider/messaging/`.
  M1-035c's files all live under
  `infochat-provider/src/main/java/io/infochat/provider/messaging/`
  and `infochat-provider/src/main/java/io/infochat/provider/bundle/`
  plus test/bundle siblings. The three subtickets' `files_scope`
  lists are disjoint by construction. The umbrella M1-035 fans
  in via `blocked_by: [M1-035a, M1-035b, M1-035c]`.
- **The umbrella's whole-topic IT lives in Provider.**
  `infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java`
  is reserved for M1-035's commit. This ticket's `out_of_scope`
  list pins that path explicitly so a stray IT pre-emption here
  is caught by the reviewer.

## Out-of-scope expansion

- **The umbrella's whole-topic integration test.**
  `infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java`
  is reserved for M1-035. The umbrella + subticket idiom exists
  precisely so the cross-cutting verification ships as its own
  reviewable unit. Pre-empting it here — for example by writing
  an IT in `infochat-messaging-adapter` that wires up
  AdapterRegistry + InboundRouter — would erase the umbrella's
  reason to exist and entangle this ticket with Provider-side
  code that belongs to M1-035b.
- **Any change under `infochat-provider/`.** The Provider-side
  AdapterRegistry, InboundRouter, MessagingStartup,
  HelpCommandHandler, AutoRegisterService, BundleLoader, and
  BundleKeys all land in M1-035b / M1-035c. Touching Provider
  here is scope drift.
- **Concrete adapters beyond InMemory.** SimpleXAdapter and
  SignalAdapter are T3-A. The SPI surface shipped here is shaped
  to accept them additively (design §6.2 method set, `name()`
  return string convention, `trustLevel()` enum) — no rework
  required when T3-A lands.
- **Flyway migrations.** T1-E is migration-free. If implementation
  reveals a needed schema column (it should not — the MVP
  auto-register path reads/writes only V5/V7 columns), escalate.
  Adding a V12 here is an automatic SCOPE-DRIFT-CHECK fail.
- **`ProgressNotifier` / `ProgressStage` extension.** /help is
  short, deterministic, and bypasses the notifier per
  docs/spec/messaging.md §Progress notifications. The M1-007c
  stubs stay untouched. T1-F's `/summary` is the first notifier
  consumer.
- **`TranslationProvider` extension.** Deferred to T2-C; the
  M1-007c stub stays untouched. MVP ships English bundle only.
- **Group `@mention` dispatch.** Group scope is deferred to T2-F.
  `ScopeRef.Group(adapterGroupId)` exists for type completeness so
  T2-F can land without re-shaping the sealed interface, but
  the InMemoryAdapter does NOT expose a `deliverGroupMention`
  helper and the Provider-side group dispatch is NOT wired here.
- **Inbound back-pressure / per-user-fair scheduler / synchronous
  throttle-reply path.** Design §6.3.7's bounded-queue machinery
  is deferred to T2-G or whenever SimpleX/Signal land. The
  `maxInboundMessageBytes` field on CapabilityFlags is populated
  so the field exists on the record, but the enforcement
  machinery is not authored here.
- **Transport-layer inbound size cap enforcement.** Same
  rationale — the field exists; the synchronous-drop-and-friendly-
  reply enforcement is deferred. InMemoryAdapter delivers
  synchronously via direct call-through to the handler.
- **Audit-log row writer triggered from the adapter.** Adapters
  do not write to audit_log directly per design §6.11. MVP
  auto-register also skips audit_log per the T1-E handoff's
  audit-log carve-out (T2-A's invite-gating adds the
  `INVITE_CONSUME` audit row at registration time).
- **`supportsCodeFormatting=true` on InMemoryAdapter.** Locked
  decision — MVP §4 readability wins over design §6.6's
  two-adapter test-coverage argument because MVP has only one
  adapter. T3-A flips this to `true` when SimpleX lands.
- **Startup-validation logic for supportsMarkdownLinks=true
  rejection.** The §6.2.1 startup gate is M1-035b's
  AdapterRegistry. This subticket ensures InMemoryAdapter
  declares `supportsMarkdownLinks=false` so the gate has nothing
  to reject; the gate's enforcement code lives in M1-035b.
- **`MessagingAdapter.groupExists(String adapterGroupId)`.**
  Deferred to T2-F (groups). No MVP caller exists; adding the
  method now is speculative API.
- **`MessagingAdapter.start(InboundHandler) / stop()` lifecycle
  methods.** Design §6.2 shows these for SimpleX/Signal; T3-A
  adds them when the transport lifecycle requires them.
  InMemoryAdapter has no transport to start; its existing
  `setInboundHandler` is sufficient. M1-035b's MessagingStartup
  calls `setInboundHandler` directly.

## Authorized test changes

- `infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java`
  (from M1-007c) — only if the test references the removed
  nested `CapabilityFlags.TrustLevel` class via `Class.forName`
  or by qualified name in a Java assertion. The change is
  exactly: update the reference to the new top-level
  `AdapterTrustLevel`. New behavior expected: the test continues
  to pass (the type set it loads has a new top-level
  `AdapterTrustLevel` instead of the nested
  `CapabilityFlags.TrustLevel`). If the M1-007c smoke test did
  not reference the nested type explicitly (it loaded the
  outer `CapabilityFlags` record only), this file is unchanged.
  No other pre-existing test may be modified.

## Alternatives considered

- **Keep the nested `CapabilityFlags.TrustLevel` enum and add a
  top-level `AdapterTrustLevel` too (so two TrustLevel-like
  types coexist).** Rejected: leaves the SPI with two
  semantically-identical types and forces every future reader to
  ask "which TrustLevel does this method/field use." Design
  §6.2 makes `trustLevel()` an adapter-method, so the static
  capability slot is the wrong home. The migration is small and
  surgical; doing it once now beats accumulating ambiguity for
  the duration of v1.
- **Rename `CapabilityFlags` to `AdapterCapabilities` in this
  ticket.** Rejected per the §Engineering rules surgical-
  changes rule and the T1-E handoff's "Naming-drift carve-out"
  Locked decision. The design doc's name is updated by a
  separate `spec:` commit after T1-E merges, not inside any
  T1-E ticket. Bundling the rename here would force a
  cross-module signature change with no functional payoff and
  would mask the surgical-changes intent.
- **Declare `InMemoryAdapter.supportsCodeFormatting = true` per
  design §6.6.** Rejected per the T1-E Locked decision: MVP has
  only InMemoryAdapter, so the design's two-adapter test-coverage
  rationale doesn't apply. MVP §4 explicitly says `false` so
  test transcripts stay readable. T3-A's SimpleX ticket flips
  this to `true` (SimpleX itself declares `false` per §6.4.2 so
  the two-adapter coverage is restored). The breadcrumb here is
  what T3-A's reviewer will find when checking why InMemoryAdapter
  needs flipping.
- **Inline `ScopeRef`, `Identity`, `InboundMessage`,
  `OutboundMessage`, `AdapterTrustLevel`, `FailureCategory`,
  `MessagingException` as nested types inside `MessagingAdapter`.**
  Rejected: a single mega-file with seven nested types is
  harder to read, harder to diff, and forces every type's
  Javadoc into a single file's javadoc namespace. Design §6.2
  shows them as siblings; one file each is the right shape.
- **Make `ScopeRef` a class with `kind` field instead of a
  sealed interface.** Rejected: sealed interfaces are
  exhaustiveness-checked by the compiler against `switch`
  expressions (per the project §Coding style preference for
  switch expressions), and the closed `Dm` / `Group` set is
  exactly the case the sealed-interface pattern serves. The
  class-with-kind shape would let a future ScopeRef value be
  invented at runtime, which is exactly what we don't want.
- **Bundle `update(handle, body)` and `finalize(handle, body)`
  into a single `applyUpdate(handle, body, isFinal)` method.**
  Rejected: docs/design/06-messaging.md §6.2 separates them so
  `finalize`'s exclusivity invariant (no `update` after
  `finalize` on the same handle) can be expressed at the
  signature layer. Bundling them would require a runtime
  parameter check and obscure the lifecycle. The InMemoryAdapter
  test for `finalize` exclusivity asserts the
  separate-method shape.
- **Make `MessagingException` carry the cause and a string
  message but NOT the `FailureCategory` accessor (consumers
  derive category from the wrapped cause).** Rejected:
  docs/spec/messaging.md §Failure handling explicitly says "An
  adapter that cannot tell the two apart MUST default to
  permanent." Encoding this as a throw-site discipline (the
  category is required at constructor) prevents the consumer
  from re-deriving it (and possibly getting it wrong). The
  category lives on the exception type because the spec puts
  it there.
- **Skip the `MessagingException` class entirely and use
  `RuntimeException` with a custom message.** Rejected: a
  checked exception with a typed `category()` accessor is the
  shape design §6.2 commits to. RuntimeException would let
  callers forget to handle PERMANENT failures, and the type
  system would not catch it.
- **Add `MessagingAdapter.start(InboundHandler)` and `stop()`
  lifecycle methods now (per design §6.2).** Rejected:
  InMemoryAdapter has no transport to start; the existing
  `setInboundHandler` is sufficient for MVP. Adding empty
  `start` / `stop` methods on InMemoryAdapter that delegate to
  `setInboundHandler` / a no-op is speculative API for the
  T3-A SimpleX/Signal tickets, which will add them with the
  shape their transports actually need. The §"No defensive
  code for impossible scenarios" rule (and its corollary against
  speculative API surface) applies.
- **Add `MessagingAdapter.groupExists(String adapterGroupId)`
  now.** Rejected: no MVP caller. T2-F (groups) is the right
  ticket; group dispatch lands there in one focused diff.

## Round 1 rework

Reviewer verdict 2026-05-17: REWORK (round 1, 2 items, all other
checks PASS). Fix only the named items, then re-run `mvn verify`
and `/m1-tick review M1-035a`.

1. Remove the unused `import java.util.ArrayList;` from
   `infochat-messaging-adapter/src/main/java/io/infochat/messaging/impl/inmemory/InMemoryAdapter.java`.
   `ArrayList` is never referenced — `sent` / `typingEvents` /
   per-handle history all use `CopyOnWriteArrayList`; the maps
   use `ConcurrentHashMap`. Engineering rule §1 (Surgical changes)
   requires cleaning up imports that this ticket's changes made
   unused; the file is brand-new in this diff.
2. Remove the unused `import io.infochat.messaging.InboundMessage;`
   from
   `infochat-messaging-adapter/src/test/java/io/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java`.
   `InboundMessage` is not referenced by name in the test — the
   lambda `msg -> seen.add(adapter.assertIdentity(msg))` infers
   the parameter type from the `InboundHandler` functional
   interface, so the explicit import is unused. Same §1
   surgical-changes rule applies.
