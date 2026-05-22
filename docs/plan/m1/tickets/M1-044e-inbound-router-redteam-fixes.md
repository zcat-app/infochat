---
id: M1-044e
title: InboundRouter splice red-team fixes (DM-gate pre-dispatch, rate-cap precedence, lookupUser redaction, non-UUID counter)
status: pending
created: 2026-05-22
last_updated: 2026-05-22
blocked_by:
  - M1-044b
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteCodeConsumerTest.java
verified_stays_green:
  - test_class: app.zcat.infochat.provider.messaging.InboundRouterTest
    rationale: "the size-cap-precedence and DM-gate-pre-dispatch swaps move ordering boundaries inside @Test methods that this ticket modifies in-place; the 6 pre-existing M1-035b/M1-044b @Test methods (emptyAndWhitespace, leadingWhitespace, chatMode, unknownCommand, commandHandlerException, rateCapOverflowDropsSilentlyWithoutOutbound) keep their behavioral assertions"
  - test_class: app.zcat.infochat.provider.messaging.InboundRouterNormalizeTest
    rationale: "M1-035b normalize tests use a NoopRateCapBucket / NoopInviteCodeConsumer / NoopBanCheck setup; the new rate-cap-precedence ordering moves the size-cap branch AFTER the no-op rate-cap, but the no-op always returns true so the size-cap branch still fires for the one onMessage @Test method (bodyAtExactlyTheCapIsAcceptedAndNormalizeRuns) and produces the same MESSAGE_TOO_LARGE_REPLY"
  - test_class: app.zcat.infochat.provider.messaging.InboundRouterContactIdRedactionTest
    rationale: "M1-038 redaction tests construct the router with NoopRateCapBucket / NoopInviteCodeConsumer / NoopBanCheck; the lookupUser-redaction fix only affects the SQLException catch path (not exercised by these tests, which run against happy-path SELECTs); ordering swaps do not change which log sites the redaction runs against"
  - test_class: app.zcat.infochat.provider.messaging.AdapterRouterIT
    rationale: "M1-008c/M1-044b wiring round-trip pre-seeds users rows with registration_state='invited' — the DM-gate-pre-dispatch fix only short-circuits for 'group_only'; 'invited' users pass through dispatch unchanged"
  - test_class: app.zcat.infochat.provider.command.AddSourceIT
    rationale: "M1-036/M1-044b MVP exit-criterion pre-seeds users row with registration_state='invited'; same rationale as AdapterRouterIT — only 'group_only' DMs are short-circuited by the DM-gate-pre-dispatch fix"
  - test_class: app.zcat.infochat.provider.command.SummaryIT
    rationale: "M1-037 IT pre-seeds users row with registration_state='vouched' — 'vouched' is not 'group_only', so the DM-gate-pre-dispatch fix does not affect this test's dispatch path"
  - test_class: app.zcat.infochat.provider.command.SummaryAdapterScopeIT
    rationale: "M1-040 adapter-scoped IT pre-seeds users row with registration_state='vouched' for both adapters; same rationale as SummaryIT"
  - test_class: app.zcat.infochat.provider.command.AddSourceAdapterScopeIT
    rationale: "M1-040 adapter-scoped IT pre-seeds users row with registration_state='invited' for the shared contact-id on both adapters; same rationale as AddSourceIT"
  - test_class: app.zcat.infochat.provider.messaging.RateCapBucketTest
    rationale: "M1-044a/M1-044b RateCapBucket tests exercise the bucket internals directly (tryAcquire, evictIdleBuckets) — InboundRouter ordering changes do not touch RateCapBucket"
  - test_class: app.zcat.infochat.provider.messaging.AdapterRegistryTest
    rationale: "M1-049 RecordingInboundRouter @Alternative intercepts onMessage before any of the changed code paths run; the splice's intake-step body is unobservable from this test"
  - test_class: app.zcat.infochat.provider.command.AddSourceCommandHandlerTest
    rationale: "M1-049 calls handler.handle() directly with mocked collaborators; the router's intake-step body is not exercised"
  - test_class: app.zcat.infochat.provider.command.SummaryCommandHandlerTest
    rationale: "M1-049 calls handler.handle() directly with mocked collaborators; the router's intake-step body is not exercised"
  - test_class: app.zcat.infochat.provider.messaging.HelpCommandHandlerTest
    rationale: "M1-049 calls handler.handle() directly with mocked collaborators; the router's intake-step body is not exercised"
  - test_class: app.zcat.infochat.provider.command.AddSourceBanCheckOrderingTest
    rationale: "M1-049 calls handler.handle() directly with mocked collaborators; the router's intake-step body is not exercised"
  - test_class: app.zcat.infochat.provider.messaging.BanCheckTest
    rationale: "M1-044a service-tier test exercises BanCheck.isBanned directly; InboundRouter changes do not touch BanCheck"
  - test_class: app.zcat.infochat.provider.messaging.AutoRegisterServiceTest
    rationale: "M1-044a service-tier test exercises AutoRegisterService.resolveOrRegisterGroup directly; InboundRouter changes do not touch AutoRegisterService"
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
remediates: M1-044b
out_of_scope:
  - any change to the spec — §Authorization model + §Invite-code registration + §Secrets handling are the source of truth; this ticket lands code already required by those sections
  - any change to BanCheck / AutoRegisterService / RateCapBucket — out of scope; the four findings sit inside InboundRouter and InviteCodeConsumer only
  - any change to ContactIds.redact in infochat-core — M1-038's helper is consumed unchanged
  - any change to BundleKeys / bundles/en.properties — M1-044b authored the four fixed-reply bundle keys; the fix re-uses them
  - any change to application.properties — M1-044b shipped the per-profile rate-cap / probation config; the fix re-uses the existing keys
  - any change to AdapterRegistry / AdapterRouter / InMemoryAdapter — messaging-adapter SPI surfaces stay
  - any new admin command handler — M1-044c territory
  - any change to the V12 migration or any V<N> migration — this ticket modifies code only
  - any change to AddSourceCommandHandler / SummaryCommandHandler / HelpCommandHandler bodies — M1-044c/M1-045 own those handlers; the AUTH-BYPASS fix lives at the router caller side, not by hardening each handler
  - any test outside the five files in files_scope — every test enumerated in `verified_stays_green:` stays green under the surgical fixes (see per-entry rationale)
acceptance:
  - "InboundRouter.onMessage swaps the size-cap branch and the rate-cap branch so the rate-cap check runs FIRST (step 1.5) and the size-cap branch runs AFTER. Verify by reading InboundRouter.java: the call to rateCapBucket.tryAcquire appears in source order BEFORE the `raw.getBytes(UTF_8).length > maxInboundBodyBytes` check AND BEFORE the MESSAGE_TOO_LARGE_REPLY sendReply. grep -nE 'rateCapBucket\\.tryAcquire' InboundRouter.java AND grep -nE 'maxInboundBodyBytes' InboundRouter.java both return ≥1 match; the line number of the FIRST tryAcquire match is LESS than the line number of the FIRST maxInboundBodyBytes match"
  - "InboundRouter.onMessage moves the step-7 DM-gate carve-out so it fires BEFORE the parse-and-dispatch block (step 6), not after. Verify by reading InboundRouter.java: the `registrationState == 'group_only'` predicate AND its `sendReply(ERROR_INVITE_REQUIRED) + return` branch appear in source order BEFORE the handleSlash call. grep -nE 'REGISTRATION_STATE_GROUP_ONLY' InboundRouter.java returns ≥1 match; the line number of the FIRST REGISTRATION_STATE_GROUP_ONLY match is LESS than the line number of the FIRST `handleSlash\\(` match in onMessage"
  - "InboundRouter.onMessage no longer assigns to a `body` variable inside step 7. The post-dispatch body-override pattern (`body = bundleLoader.get(ERROR_INVITE_REQUIRED)`) is removed; the DM-gate's reply is emitted via a direct sendReply + return BEFORE dispatch. Verify: grep -nE 'body\\s*=\\s*bundleLoader\\.get\\(BundleKeys\\.ERROR_INVITE_REQUIRED' InboundRouter.java returns ZERO matches"
  - "InboundRouter.lookupUser's IllegalStateException wraps contactId via ContactIds.redact. Verify: grep -nE 'ContactIds\\.redact\\(contactId\\)' InboundRouter.java returns ≥1 match in the lookupUser method body. The string `contact_id=\" + contactId` (raw concatenation) returns ZERO matches inside lookupUser's catch block"
  - "InboundRouter imports app.zcat.infochat.core.log.ContactIds (carried forward from M1-038 — verify the import still exists after the lookupUser fix). Verify: grep -E 'import\\s+app\\.zcat\\.infochat\\.core\\.log\\.ContactIds' InboundRouter.java returns ≥1 match"
  - "InboundRouter.onMessage no longer parses the UUID-shape of the body before invoking InviteCodeConsumer.consume; the parseInviteCode helper is removed (or the call-site is removed). InboundRouter always calls inviteCodeConsumer.consume(adapter, contactId, normalizedBody) in step 2's DM-unknown branch, passing the normalized string body. Verify: grep -nE 'parseInviteCode' InboundRouter.java returns ZERO matches AND grep -nE 'inviteCodeConsumer\\.consume\\(' InboundRouter.java returns ≥1 match. The argument to consume is the normalized String body, not a UUID — grep -nE 'inviteCodeConsumer\\.consume\\([^)]*UUID' InboundRouter.java returns ZERO matches"
  - "InviteCodeConsumer.consume's signature changes from (String adapter, String contactId, UUID candidateCode) to (String adapter, String contactId, String normalizedBody). The method internally attempts to parse the body as a UUID; on parse failure it returns Rejected AND increments the brute-force counter (via the same insertAudit + counter row that an invalid-but-UUID-shaped code increments). Verify: grep -nE 'public\\s+Outcome\\s+consume\\(\\s*@?NonNull?\\s*String\\s+adapter,\\s*@?NonNull?\\s*String\\s+contactId,\\s*@?NonNull?\\s*String\\s+\\w+\\s*\\)' InviteCodeConsumer.java returns ≥1 match. The old UUID-typed signature returns ZERO matches: grep -nE 'public\\s+Outcome\\s+consume\\(\\s*[^)]*UUID' InviteCodeConsumer.java returns ZERO matches"
  - "InviteCodeConsumerTest gains a new @Test method whose name contains `nonUuidBodyIncrementsBruteForceCounter` (case-insensitive) that asserts: calling consume() with a non-UUID String body returns Rejected AND inserts a brute-force-counter row (same shape as the existing invalid-UUID test seeds), so N+1 non-UUID attempts past threshold trip BruteForceThresholdBreached. Verify: grep -iE 'void\\s+\\w*nonUuidBodyIncrementsBruteForceCounter\\w*\\s*\\(' InviteCodeConsumerTest.java returns ≥1 match"
  - "InviteCodeConsumerTest's 7 M1-044a pre-existing @Test methods continue to pass with the signature change applied (each callsite updated from `consume(adapter, contactId, uuid)` to `consume(adapter, contactId, uuid.toString())`). No assertion semantics change; the only edit is the call-site shape. Verify: grep -cE '^\\s*@Test\\b' InviteCodeConsumerTest.java returns 8 (7 pre-existing + 1 new nonUuidBody test)"
  - "InboundRouterIntakeOrderingTest's scenario (g) `groupOnlyDmGateReplacesHandlerReplyWithInviteRequired` is renamed and rewritten to assert the NEW ordering: when the inbound is a DM AND snapshot.registrationState='group_only', InboundRouter sends ERROR_INVITE_REQUIRED and does NOT call handleSlash. The new method name contains `groupOnlyDmGateShortCircuitsBeforeDispatch` (case-insensitive). The expected collaborator-order log changes: handleSlash is REMOVED from the expected sequence; bundleLoader.get(error.invite.required) appears AFTER banCheck.isBanned, not after a handler invocation. Verify: grep -iE 'void\\s+\\w*groupOnlyDmGateShortCircuitsBeforeDispatch\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java returns ≥1 match AND grep -iE 'void\\s+\\w*groupOnlyDmGateReplacesHandlerReply\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java returns ZERO matches"
  - "InboundRouterIntakeOrderingTest gains a new @Test method whose name contains `oversizedBodyDropsAfterOverRateCap` (case-insensitive) that asserts the rate-cap-precedence fix: when rateCapBucket.tryAcquire returns false AND the inbound body is OVERSIZE, the over-cap silent-drop fires FIRST and no MESSAGE_TOO_LARGE_REPLY is emitted (the bucket's silent-drop dominates the size-cap reply). Verify: grep -iE 'void\\s+\\w*oversizedBodyDropsAfterOverRateCap\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java returns ≥1 match"
  - "InboundRouterIntakeOrderingTest's scenario (a) `overSizeCapDropsBeforeAnyCollaboratorIsConsulted` is renamed and rewritten: under the new ordering, an oversize body that PASSES the rate cap still drops with MESSAGE_TOO_LARGE_REPLY, but the rateCapBucket.tryAcquire call MUST appear FIRST in the collaborator sequence. The new method name contains `overSizeCapDropsAfterRateCapPassesNoOtherCollaborator` (case-insensitive). The expected collaborator-order log starts with rateCapBucket.tryAcquire, then the size-cap MESSAGE_TOO_LARGE_REPLY sendReply; users lookup, inviteCodeConsumer, banCheck must NOT be consulted. Verify: grep -iE 'void\\s+\\w*overSizeCapDropsAfterRateCapPassesNoOtherCollaborator\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java returns ≥1 match AND grep -iE 'void\\s+\\w*overSizeCapDropsBeforeAnyCollaboratorIsConsulted\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java returns ZERO matches"
  - "InboundRouterIntakeOrderingTest's scenario (e) `unknownContactInvalidInviteRejectsWithFixedReplyAndStopsBeforeBanCheck` is updated for the new InviteCodeConsumer.consume signature: the test now passes a non-UUID String body (not a UUID.toString()), and the FakeInviteCodeConsumer in the test file is updated to accept a String parameter (not a UUID) — its consume() returns the canned outcome regardless of body shape. The renamed test method continues to assert that Rejected outcome → ERROR_INVITE_REQUIRED reply → no banCheck call. Verify: the test file's FakeInviteCodeConsumer.consume method signature contains `String\\s+\\w+` for the third parameter (not `UUID`). grep -nE 'consume\\(String\\s+\\w+,\\s*String\\s+\\w+,\\s*String\\s+\\w+\\)' InboundRouterIntakeOrderingTest.java returns ≥1 match"
  - "InboundRouterTest's `rateCapOverflowDropsSilentlyWithoutOutbound` @Test method continues to pass under the new ordering. The 5 M1-035b/M1-044b pre-existing @Test methods (emptyAndWhitespaceAndInvisibleOnlyBodies, leadingWhitespaceBeforeSlashCommand, chatModeBodyProducesDeterministic, unknownCommandProducesFriendlyUnknownCommandReply, commandHandlerExceptionProducesInternalErrorReplyWithoutLeakingMessage) stay green unchanged; no @Test annotation count change. Verify: grep -cE '^\\s*@Test\\b' InboundRouterTest.java returns 6 (5 pre-existing + 1 M1-044b rate-cap test); git diff main -- InboundRouterTest.java shows ZERO removed @Test annotation lines"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass: all M1-035c/M1-036/M1-037/M1-038/M1-039/M1-040/M1-043 tests, M1-044a's per-service tests (RateCapBucketTest's 5 methods, BanCheckTest, AutoRegisterServiceTest, InviteCodeConsumerTest's 8 methods after the +1 non-UUID test), M1-044b's per-file tests (InboundRouterTest's 6 methods, InboundRouterIntakeOrderingTest's 8 methods after the 3 renames), M1-049's handler-tier tests, M1-050's JSpecify lint"
test_plan:
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InviteCodeConsumer.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteCodeConsumerTest.java
  preserves:
    - all tests currently green on main (enumerated in verified_stays_green)
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Invite-code registration
  - docs/spec/security.md §Secrets handling
  - docs/spec/security.md §User ban
decision_refs:
  - D44
  - D45
---

# M1-044e: InboundRouter splice red-team fixes (DM-gate pre-dispatch, rate-cap precedence, lookupUser redaction, non-UUID counter)

## Context

`/redteam M1-044b` (2026-05-22, verdict file `docs/plan/m1/redteam/M1-044b-2026-05-22.md`)
returned FINDINGS with four defects against the M1-044b implementation
commit (0523710):

- **AUTH-BYPASS (high)** — the step-7 DM-gate carve-out for `group_only`
  users fires AFTER `handleSlash`, so the handler's side effects (URL
  probe + `sourceUpsertService.upsert` for `/add-source`; LLM call for
  `/summary`) already landed before the body override replaces the
  outbound. The spec sequences permission (step 7) BEFORE execute
  (step 9): "blocked commands return a friendly reply and never reach
  execution" (`docs/spec/security.md` §Authorization model). A
  `group_only` DM-sender can drive arbitrary handler side effects from
  a state the spec explicitly bars from DM dispatch.
- **DOS (medium)** — the body-size cap fires BEFORE `rateCapBucket.tryAcquire`,
  so oversize inbound emits `MESSAGE_TOO_LARGE_REPLY` without consuming
  a bucket token. The spec promises (`docs/spec/security.md`
  §Authorization model step 1.5): "Over-cap inbound is dropped silently
  for the rest of the cap window — no reply (including no fixed ban
  reply, no fixed invite-required reply, no friendly error). The cap
  runs after step 1 ... and before every application-level check
  below." The M1-044b rationale for size-cap-first ("adversarial
  payloads cannot drive bucket arithmetic on un-bounded inputs") is
  incorrect — bucket arithmetic is O(1) hash + atomic decrement and
  cares nothing about payload size. The cost the rationale meant to
  bound is NFKC's amplification, which runs AFTER both checks; swapping
  size-cap and rate-cap leaves NFKC's cost-bounding intact while
  honoring the spec promise.
- **INFO-LEAK (low)** — `InboundRouter.lookupUser`'s `IllegalStateException`
  wrapper concatenates the raw `contactId` into its message. `onMessage`
  does not catch the exception; it bubbles to the adapter dispatch
  surface where any uncaught-handler log emits the unredacted id —
  bypassing M1-038's contact-id redaction at the three explicit log
  sites in the same method. Same fix shape as M1-044d's six redaction
  sites: wrap `contactId` via `ContactIds.redact(contactId)`.
- **AUDIT-EVASION (low)** — `InboundRouter.parseInviteCode` short-circuits
  when the normalized body is not a UUID — the router emits
  `ERROR_INVITE_REQUIRED` without invoking `InviteCodeConsumer.consume`.
  The brute-force counter does not increment on non-UUID probes, so an
  adversary can issue many non-UUID probes interleaved with a few
  below-threshold UUID guesses; the threshold-breach audit row never
  fires. The fix moves the UUID-parse decision INTO
  `InviteCodeConsumer.consume` — the consumer becomes the single owner
  of "is this a valid invite + counter management," and the router
  stops second-guessing it.

M1-044b is `done` and its commit (0523710) is immutable per the
workflow ("Never amend a passed commit"). This ticket lands all four
fixes in a single bundled commit because they share the same file
surface (InboundRouter.java + InviteCodeConsumer.java) and the same
intake-step ordering boundary. Splitting four 1-method fixes across
four tickets is process drift.

`complexity: high` and `risk: high` because the intake-step ordering
is a security-critical invariant — every fix here re-shapes the
ordering. `round_cap: 3` accommodates the ordering-test rewrites
(scenarios a, e, g) plus the new non-UUID counter test which exercises
the brute-force budget under a new input shape.

`security_relevant: true`.

## Definition of Done

- `InboundRouter.onMessage` swaps the size-cap and rate-cap branches:
  rate-cap fires FIRST (step 1.5), size-cap fires AFTER. Under flood,
  rate-cap drops silently before the size-cap reply path is ever
  reached.
- `InboundRouter.onMessage` moves the step-7 DM-gate carve-out
  BEFORE the parse-and-dispatch block. A `group_only` DM-sender's
  inbound short-circuits with `ERROR_INVITE_REQUIRED` BEFORE
  `handleSlash` runs; the handler's side effects never land.
- `InboundRouter.lookupUser`'s `IllegalStateException` wrapper
  redacts the `contactId` via `ContactIds.redact(contactId)`.
- `InboundRouter` no longer parses the UUID-shape of the body before
  invoking `InviteCodeConsumer.consume`. The router calls
  `inviteCodeConsumer.consume(adapter, contactId, normalizedBody)`
  with the normalized String, and the consumer owns the UUID-parse
  decision.
- `InviteCodeConsumer.consume`'s signature changes from
  `(String, String, UUID)` to `(String, String, String)`. On non-UUID
  body, the method increments the brute-force counter (same shape as
  the existing invalid-UUID counter row) and returns `Rejected`. The
  three Outcome cases (Accepted / Rejected / BruteForceThresholdBreached)
  are unchanged.
- `InviteCodeConsumerTest` gains one new `@Test` method that exercises
  the non-UUID counter-increment path: N+1 non-UUID probes past
  threshold trip `BruteForceThresholdBreached`.
- `InviteCodeConsumerTest`'s 7 M1-044a pre-existing methods stay green
  with the call-site update from `consume(adapter, contactId, uuid)`
  to `consume(adapter, contactId, uuid.toString())`.
- `InboundRouterIntakeOrderingTest`'s scenarios (a) and (g) are renamed
  and rewritten to reflect the new orderings:
  - (a) becomes `overSizeCapDropsAfterRateCapPassesNoOtherCollaborator`
    (rate-cap fires first; size-cap-reply fires only when rate-cap
    passes; users-lookup / invite-consume / ban-check are not consulted).
  - (g) becomes `groupOnlyDmGateShortCircuitsBeforeDispatch`
    (DM-gate short-circuits with `ERROR_INVITE_REQUIRED` BEFORE
    `handleSlash` is consulted).
- `InboundRouterIntakeOrderingTest` gains one new `@Test` method
  `oversizedBodyDropsAfterOverRateCap` that asserts the new
  flood-defense property: when rate-cap rejects AND the body is
  oversize, the over-cap silent-drop fires first and no
  `MESSAGE_TOO_LARGE_REPLY` is emitted.
- `InboundRouterIntakeOrderingTest`'s scenario (e) `FakeInviteCodeConsumer`
  signature updates to accept a `String` parameter (not `UUID`).
- `InboundRouterTest`'s 6 M1-035b/M1-044b @Test methods stay green
  under the new ordering.
- `mvn -B clean verify` exits 0.

## Implementation notes

- **Post-rate-cap size-cap shape.** The new ordering block inside
  `onMessage`:
  ```java
  inboundContext.setAdapterName(adapterName);
  String raw = msg.text();
  String contactId = contactIdOf(msg.scope());

  // Step 1.5 — transport-level rate cap. Per spec §Authorization
  // model step 1.5, fires BEFORE any application-level check so
  // a hostile flood cannot drive outbound cost via friendly-error
  // paths (including the size-cap reply).
  if (!rateCapBucket.tryAcquire(adapterName, contactId)) {
      return;          // silent drop, no outbound at all
  }

  // Body-size cap (M1-038). Bounds NFKC amplification cost; the
  // rate-cap above bounds the per-second outbound rate.
  if (raw != null && raw.getBytes(StandardCharsets.UTF_8).length > maxInboundBodyBytes) {
      sendReply(msg.scope(), MESSAGE_TOO_LARGE_REPLY);
      return;
  }

  // Step 1.7 — Unicode normalize (preserved).
  String normalized = normalize(raw);
  ...
  ```
  Update the class-level Javadoc lines 88-95 to reflect the new
  ordering rationale (rate-cap-first defends the size-cap-reply path
  from flood amplification; size-cap-second still defends NFKC from
  unbounded payloads).

- **Pre-dispatch DM-gate shape.** Move the step-7 block from
  AFTER `handleSlash` to AFTER step 4 (ban check) and BEFORE step 6
  (dispatch). The new block:
  ```java
  // Step 7 (DM-gate carve-out, pre-dispatch). Per spec §Invite-code
  // registration: a user auto-registered via group @mention
  // (registration_state='group_only') is rejected from DM with the
  // same fixed reply as step 2's invalid path. The check fires
  // BEFORE dispatch so the handler's side effects (URL probe, LLM
  // call, DB writes) never land for a blocked DM.
  if (msg.scope() instanceof ScopeRef.Dm
          && snapshot.map(s -> REGISTRATION_STATE_GROUP_ONLY.equals(s.registrationState()))
                  .orElse(false)) {
      sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED));
      return;
  }

  // Step 6 — Parse + dispatch.
  String body;
  try {
      body = normalized.startsWith("/") ? handleSlash(...) : CHAT_MODE_REPLY;
  } catch (RuntimeException e) {
      log.error(...);
      body = INTERNAL_ERROR_REPLY;
  }
  sendReply(msg.scope(), body);
  ```
  Note: the post-dispatch `body = bundleLoader.get(ERROR_INVITE_REQUIRED)`
  assignment is gone; the new shape is sendReply + return BEFORE
  dispatch.

- **`lookupUser` redaction shape.** Mirror M1-044d's pattern:
  ```java
  } catch (SQLException e) {
      throw new IllegalStateException(
              "InboundRouter.lookupUser failed for adapter=" + adapter
                      + " contact_id=" + ContactIds.redact(contactId), e);
  }
  ```
  The `ContactIds` import is already present (M1-038 added it for
  the three error-log sites in `onMessage`); no new import needed.

- **`InviteCodeConsumer.consume` signature + UUID parse.** Move
  `InboundRouter.parseInviteCode` INTO `InviteCodeConsumer` as a
  private static helper. The new `consume`:
  ```java
  public Outcome consume(@NonNull String adapter, @NonNull String contactId, @NonNull String body) {
      UUID candidateCode = parseUuid(body);
      // ... existing race-safe-consume logic, with one new branch:
      // when candidateCode == null, treat as a non-UUID failed attempt
      // — increment the brute-force counter (same INSERT INTO
      // invite_code_attempt row as the UUID-but-invalid case) and
      // return Rejected.
  }

  private static UUID parseUuid(String body) {
      try { return UUID.fromString(body); }
      catch (IllegalArgumentException e) { return null; }
  }
  ```
  The counter-increment for non-UUID probes uses the SAME
  `INSERT INTO invite_code_attempt` shape as the existing
  invalid-UUID path; no new SQL, no new audit row schema, no new
  Outcome variant.

- **Caller-side simplification.** With the UUID-parse moved into
  the consumer, `InboundRouter`'s step-2 block becomes:
  ```java
  if (msg.scope() instanceof ScopeRef.Dm && snapshot.isEmpty()) {
      InviteCodeConsumer.Outcome outcome =
              inviteCodeConsumer.consume(adapterName, contactId, normalized);
      switch (outcome) {
          case InviteCodeConsumer.Accepted a ->
                  sendReply(msg.scope(), bundleLoader.get(BundleKeys.REPLY_WELCOME_DM_FRESH));
          case InviteCodeConsumer.Rejected r ->
                  sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED));
          case InviteCodeConsumer.BruteForceThresholdBreached b ->
                  sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED));
      }
      return;
  }
  ```
  Delete the `parseInviteCode` private static method from
  `InboundRouter.java` — it's dead code after the move.

- **Test-side updates.**
  - `InboundRouterIntakeOrderingTest`'s `FakeInviteCodeConsumer.consume`
    signature changes to accept a `String` body parameter. The fake's
    behavior (return canned Outcome) is unchanged.
  - Scenario (a) `overSizeCapDropsBeforeAnyCollaboratorIsConsulted`
    is RENAMED to `overSizeCapDropsAfterRateCapPassesNoOtherCollaborator`
    AND rewritten: the expected collaborator log NOW starts with
    `rateCapBucket.tryAcquire` (rate-cap-passes-first), then the
    size-cap reply path.
  - Scenario (g) `groupOnlyDmGateReplacesHandlerReplyWithInviteRequired`
    is RENAMED to `groupOnlyDmGateShortCircuitsBeforeDispatch` AND
    rewritten: the expected collaborator log NO LONGER contains
    `handleSlash`-side calls (no HelpCommandHandler dispatch); the
    DM-gate emits `ERROR_INVITE_REQUIRED` right after the ban check.
  - Scenario (e) `unknownContactInvalidInviteRejectsWithFixedReplyAndStopsBeforeBanCheck`
    is updated to pass a non-UUID String body (the test no longer
    constructs a UUID); the FakeInviteCodeConsumer returns Rejected
    regardless. Acceptance semantics unchanged.
  - NEW scenario added: `oversizedBodyDropsAfterOverRateCap` (the
    flood-defense property). The test sets the RateCapBucket fake to
    return false AND passes an oversize body; asserts no outbound
    reply was sent AND no other collaborator was consulted.
  - `InviteCodeConsumerTest`'s 7 M1-044a pre-existing methods get
    their call sites updated from `consume(adapter, contactId, uuid)`
    to `consume(adapter, contactId, uuid.toString())`. NEW @Test
    method `nonUuidBodyIncrementsBruteForceCounter` exercises the
    non-UUID counter increment + threshold trip.

## Big-picture notes

- **AUTH-BYPASS is the load-bearing fix.** The spec promises
  permission BEFORE execute, and the M1-044b implementation deferred
  permission until AFTER execute. Without this fix, every command
  handler that mutates state (`/add-source`, `/promote`, `/demote`,
  `/ban`, `/unban`, `/invite create`) is reachable from a `group_only`
  DM-sender — the side effects land even though the body override
  hides the outcome. The fix is mechanical (move one if-block from
  after dispatch to before dispatch) but the security stakes are
  high.
- **DOS fix changes the spec-conformance posture.** M1-044b's
  size-cap-first ordering was a thoughtful trade-off that the
  red-team identified as in tension with the spec literal. The
  M1-044b commit's class-Javadoc acknowledged the trade-off
  explicitly. The fix here aligns with the spec literal at the cost
  of one (cheap) NFKC amplification surface — and the rate-cap-first
  ordering closes that surface anyway because over-cap inbound never
  reaches normalize.
- **AUDIT-EVASION fix centralizes UUID-parse + counter logic.**
  Splitting "is this a UUID?" between the router (parse) and the
  consumer (counter) was the gap. The fix is a single-method
  ownership move: the consumer owns both. The router becomes
  smaller (one fewer private static method, one fewer branch).
- **INFO-LEAK fix is the smallest of the four.** One line change in
  `lookupUser`'s catch block: `+ contactId` → `+ ContactIds.redact(contactId)`.
  Same shape as M1-044d's six redaction sites.
- **Test pyramid.** The intake-step ordering changes are pinned at
  the unit-test tier (`InboundRouterIntakeOrderingTest` + the new
  `InviteCodeConsumerTest` non-UUID test). The IT-tier tests in
  `verified_stays_green:` are pre-seeded with `registration_state`
  values that bypass the DM-gate short-circuit (`'invited'`,
  `'vouched'`) — they continue to dispatch through the handler. The
  M1-044 umbrella IT (whenever it lands) will exercise the
  group_only → DM rejection at the IT tier.

## Alternatives considered

- **Land the four fixes in four separate tickets.** Rejected as
  process drift. All four sit in the same two files; all four
  exercise the same intake-step surface; all four are mechanical
  fixes to surfaces M1-044b already pinned. Bundling them produces
  one review, one mvn-verify cycle, one squash-merge, one audit
  artifact.
- **Fix AUTH-BYPASS by hardening each command handler with a
  `group_only` check.** Rejected because every handler would need
  the check (defense-in-depth + redundancy), it doesn't address the
  spec's "blocked commands never reach execution" promise (handlers
  still run their early-side-effect code), and M1-045's full step-7
  permission matrix will land the per-command allowlist anyway. The
  router-side single-point gate is the right shape.
- **Fix DOS by decrementing the bucket on oversize-inbound.**
  Rejected because the spec promise is "step 1.5 fires BEFORE
  every application-level check," not "step 1.5 charges a token
  for every check that fires." Charging a token still leaks a
  reply (`MESSAGE_TOO_LARGE_REPLY`) per oversize inbound, which the
  spec forbids under "no friendly error" semantics. Swapping the
  order is spec-correct AND cheaper.
- **Fix AUDIT-EVASION by adding a `recordNonUuidAttempt(adapter,
  contactId)` method on `InviteCodeConsumer` instead of changing
  the `consume` signature.** Rejected because it splits a single
  responsibility across two methods; the router would still have
  to decide which method to call (UUID-parse leaks back into the
  caller). The signature change is the cleaner shape.
- **Defer AUDIT-EVASION to a later ticket.** Rejected because the
  fix is one method-signature change plus one new test; bundling
  it now costs nothing extra and closes a low-severity but
  documented gap.
