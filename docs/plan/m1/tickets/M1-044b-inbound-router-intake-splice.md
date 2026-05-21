---
id: M1-044b
title: InboundRouter intake-step splice (1.5, 2, 4, 7-DM-gate) + bundle keys + rate-cap config
status: pending
created: 2026-05-20
last_updated: 2026-05-21
escalations:
  - date: 2026-05-21
    reason: clarity-warn
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: WARN (0 blockers, 3 warnings).

      Warnings:
        1. ACCEPTANCE-RUNNABLE: Items 1, 3, 4, 6, 8 contain source-order
           reading checks that cannot be run as commands. Behavioral
           coverage is provided by InboundRouterIntakeOrderingTest in
           item 12.
        2. ACCEPTANCE-RUNNABLE + ACCEPTANCE-VS-DOD-CONSISTENT: Item 13
           uses an undefined variable N (`@Test ≥(N+1)`). Implementation
           notes name the specific new method
           `rateCapOverflowDropsSilentlyWithoutOutbound` — a precise
           grep for that method would be tighter than the (N+1) form.
        3. ACCEPTANCE-VS-DOD-CONSISTENT: Item 12 asserts `@Test ≥6`
           over 8 behaviorally distinct scenarios (a)–(h) — HETEROGENEOUS-
           AGGREGATE count. Per-scenario method-name greps would
           localize regressions instead of masking them.

      Per user-standing feedback rule (memory:
      feedback_no_heterogeneous_aggregate_test_counts), the warn #3
      pattern is a hard-no even at WARN level. User elected to refine
      items 12 and 13 before starting.
  - date: 2026-05-21
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL (1 blocker, 1 warning).

      Blocker:
        1. Acceptance item 8 states the wrong step ordering in its
           verification line: "setAdapterName → size-cap → normalize →
           rate cap." This places normalize BEFORE rate cap, contradicting
           (a) the spec (security.md §Authorization model: step 1.5 rate
           cap → step 1.7 normalize), (b) the DoD's Implementation notes
           code sketch (rate-cap before normalize), and (c) acceptance
           item 1 ("step 1.5 rate cap → 1.7 normalize"). A developer
           implementing the spec-correct order cannot satisfy item 8;
           a developer satisfying item 8's stated order contradicts the
           spec and item 1.

           Fix: change the verification line in item 8 to read
           "setAdapterName → size-cap → rate cap → normalize" (matching
           the spec's step 1.5 → 1.7 ordering).

      Warning:
        1. ACCEPTANCE-RUNNABLE items 1, 3, 4, 6: Source-order reading
           checks or behavioral prose assertions without runnable
           commands. Behavioral coverage is provided by
           InboundRouterIntakeOrderingTest (item 12) which covers all
           8 scenarios with mock-verified collaborator sequences. Not a
           blocker because the ordering test provides the runnable
           equivalent.
  - date: 2026-05-21
    reason: premise-fail
    reviewer_verdict_excerpt: |
      Trigger context (developer one-liner — colloquial label
      "tests-cascade"; canonical reason "premise-fail" per the
      five-way escalation menu's reason table):

      Round-1 mvn verify (from .reviews/m1-044b-r1-test.log) shows
      8 failures + 4 errors traced to three test classes; the
      ticket's acceptance contains a structural defect.

      (a) AdapterRegistryTest.singleAdapterHappyPathActivatesInMemoryAndRegistersRouter
          fails: delivers `/xyz` from unknown alice and expects
          UNKNOWN_COMMAND_REPLY, but the new splice routes the
          unknown DM contact through step 2 (invite consume) →
          Rejected → ERROR_INVITE_REQUIRED. AdapterRegistryTest.java
          is NOT in files_scope; this test's purpose is wiring
          (AdapterRegistry → InMemoryAdapter → InboundRouter
          round-trip), not unknown-command logic. The canonical
          unknown-command coverage is
          InboundRouterTest.unknownCommandProducesFriendlyUnknownCommandReply
          which still passes.

      (b) InboundRouterContactIdRedactionTest (M1-038, 3 errors) +
          InboundRouterNormalizeTest.bodyAtExactlyTheCapIsAcceptedAndNormalizeRuns
          (M1-035b, 1 error) all NPE with `Cannot invoke
          "RateCapBucket.tryAcquire(String, String)" because
          "this.rateCapBucket" is null`. Root cause: both files
          use a `newRouter()`-style helper that does
          `new InboundRouter()` and assigns the pre-M1-044b field
          set; the splice introduces 4 new mandatory @Inject fields
          (rateCapBucket, inviteCodeConsumer, banCheck,
          bundleLoader, dataSource) that the helpers do not wire.

      Why item 14 is structurally unsatisfiable:
        Item 14 says "InboundRouterNormalizeTest ... and
        InboundRouterContactIdRedactionTest ... continue to pass
        without ANY modification ... `git diff main -- ...`
        returns ZERO changes". This cannot hold given the splice's
        new mandatory dependencies. The only ways to satisfy item
        14 would be: (i) make the new fields optional /
        null-tolerant inside onMessage → violates CLAUDE.md
        §Engineering rules "No defensive code for impossible
        scenarios"; (ii) skip the splice for inputs from those
        test contacts → architecturally impossible. The prior
        reviewer + clarity passes missed this because the conflict
        only surfaces when the splice actually runs — a runnable-
        grep check doesn't catch it.

      Refine target: loosen item 14 from "ZERO changes" to "no
      @Test method body modified; helper-method changes ONLY to
      wire new collaborator stubs are allowed" + add
      AdapterRegistryTest.java to files_scope with a new
      acceptance item for the @BeforeEach alice pre-seed + bump
      files_budget 10→13 + update test_plan.modifies/preserves +
      add an Out-of-scope expansion carve-out.
blocked_by:
  - M1-044a
files_budget: 13
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRegistryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to the spec — §Authorization model is the source of truth; this ticket implements step 1.5/2/4/7-DM-gate verbatim
  - any change to M1-044a's InviteCodeConsumer, BanCheck, AutoRegisterService, or the V12 migration — those are M1-044a's commit and consumed unchanged via @Inject. RateCapBucket.java is the ONE exception: per the /redteam M1-044a low-severity DOS finding (verdict file docs/plan/m1/redteam/M1-044a-2026-05-21.md), this ticket widens the eviction predicate to evict drained idle buckets — see acceptance item 16 and Implementation notes §"Rate-cap eviction-predicate fix"
  - any new admin command handler — M1-044c territory
  - any /vouch handler — M1-045 territory
  - any /grant-admin / /revoke-admin handler — M1-046 territory
  - the umbrella IT — M1-044 territory
  - any change to ContactIds.redact — M1-038 helper consumed unchanged
  - any change to InboundContext — M1-040 bean consumed unchanged
  - any change to AutoRegisterService method body or signature beyond the call-site swap — M1-044a's narrowed shape stays
  - any change to ScopeRef, InboundMessage, OutboundMessage, MessagingAdapter, AdapterRegistry — messaging-adapter SPI surfaces stay
  - any change to existing CommandHandler implementations (HelpCommandHandler from M1-035c, AddSourceCommandHandler from M1-036, SummaryCommandHandler from M1-037) — those are M1-044c's territory only insofar as the step 7 DM-gate carve-out interposes a check; the handlers themselves are NOT modified
  - any new probation-check service or CommandPermissions class — M1-045 territory (step 5 + step 7 probation check)
  - any test outside the eleven files in files_scope — every M1-035c/M1-036/M1-037/M1-039/M1-040 test stays green unchanged; M1-035b's InboundRouterNormalizeTest, M1-038's InboundRouterContactIdRedactionTest, and M1-008b's AdapterRegistryTest are extended with helper-only modifications (see acceptance items 14 and 15) rather than left untouched
acceptance:
  - "InboundRouter.onMessage executes the intake steps in the spec's exact numerical order: identity (step 1, already resolved by AdapterRegistry into msg.sender()) → 1.5 rate cap → 1.7 normalize (already on disk) → 2 invite gate (DM unknown only) → 3 group auto-register (group `@mention` only) → 4 ban check → 6 parse + dispatch → 7 permission DM-gate carve-out (DM from `registration_state='group_only'` rejected with the invite-required reply). Verify by reading InboundRouter.java: the calls appear in source order rateCapBucket.tryAcquire → normalize → inviteCodeConsumer.consume → autoRegisterService.resolveOrRegisterGroup → banCheck.isBanned → handleSlash"
  - "Step 1.5 — rate-cap silent drop: when `rateCapBucket.tryAcquire(adapter, contactId)` returns false, InboundRouter.onMessage returns IMMEDIATELY with no outbound reply (no fixed ban reply, no fixed invite-required reply, no friendly error) per spec §Authorization model step 1.5 (`Over-cap inbound is dropped silently`). Verify InboundRouter.java: the rate-cap branch contains no `sendReply` call and returns directly. grep -E 'rateCapBucket\\.tryAcquire' InboundRouter.java returns ≥1 match"
  - "Step 2 — DM unknown contact + invite-code consume: when the inbound is a DM scope (`scope instanceof ScopeRef.Dm`) AND the resolved `users.findByContactId(adapter, contactId)` is empty, InboundRouter invokes `inviteCodeConsumer.consume(adapter, contactId, normalizedBody)`. On Accepted outcome, the welcome reply (`reply.welcome.dm_fresh` bundle value) is sent and dispatch STOPS (no slash parse, no chat-mode fallback) per spec step 2 (`Valid: create user row, mark code USED, send welcome, stop. No further processing.`). On Rejected outcome, the fixed `error.invite.required` reply is sent and dispatch STOPS. On BruteForceThresholdBreached outcome, the fixed `error.invite.required` reply is sent (same user-visible reply per spec — the rate-limit state does not change the per-failure reply) and dispatch STOPS"
  - "Step 3 — group `@mention` auto-register: when the inbound is a Group scope AND no `users` row exists for `(adapter, contactId)`, InboundRouter invokes `autoRegisterService.resolveOrRegisterGroup(msg.sender(), adapter)` (the M1-044a narrowed method); the call writes a row with `registration_state='group_only'` and `probation_until = NOW() + slow_start_window`; dispatch CONTINUES to step 4 per spec §Authorization model step 3 (`Continue to step 4`). The first-@mention auto-promote and group_membership row inserts are deferred to T2-F (group support — this ticket does NOT auto-promote nor write group_membership)"
  - "Step 4 — ban check: AFTER step 2/3 resolved the user, InboundRouter invokes `banCheck.isBanned(adapter, contactId)`; when true, the fixed `error.ban.fixed` reply (the `Your access has been revoked.` literal) is sent and dispatch STOPS per spec §User ban (`Banned user receives one fixed reply per inbound message, regardless of input`). grep -E 'banCheck\\.isBanned' InboundRouter.java returns ≥1 match. The reply body equals the `error.ban.fixed` bundle value (NOT a different literal)"
  - "Step 7 — DM-gate carve-out for `group_only` users: after a slash-command dispatch produces an outbound, BUT BEFORE returning, when the inbound is a DM scope AND `users.registration_state = 'group_only'` for the resolved row, the dispatch result is REPLACED with the fixed `error.invite.required` reply (the same fixed reply step 2's invalid path uses, per spec §Invite-code registration `Group-registered users do not get free DM access ... rejected with the same fixed reply as step 2's invalid path`). The DM-gate check fires AFTER the ban check (step 4) AND AFTER the dispatch to the command handler — the spec sequences DM-gate at step 7, NOT step 4. Verify InboundRouter.java: the DM-gate check is sequenced after handleSlash returns AND only fires when the scope is ScopeRef.Dm. Note: this differs from the spec's prose ordering — the spec describes step 7 as part of permission evaluation; here the carve-out is implemented as a post-dispatch result override so the dispatch can still resolve the handler (the permission check runs INSIDE the handler today per M1-035c/M1-036/M1-037; full step-7 permission matrix lands in M1-045). The /stop carve-out from spec §Slow-start tier (`/stop is not blocked`) and analogous carve-outs are NOT in this ticket's scope — the DM-gate fires for EVERY slash command issued by a `group_only` user; M1-045's CommandPermissions can later widen the DM-gate to allow specific commands if the spec changes (the current spec gives no per-command carve-out for the DM-gate)"
  - "InboundRouter.onMessage continues to invoke `inboundContext.setAdapterName(adapterName)` BEFORE any service call, so M1-040's adapter-scoped `users` lookups in downstream handlers continue to see the correct adapter. Verify: `grep -E 'inboundContext\\.setAdapterName' InboundRouter.java` returns ≥1 match AND the call appears before `rateCapBucket.tryAcquire` in source order"
  - "InboundRouter.onMessage continues to apply the body-size cap (M1-038) BEFORE the rate-cap check AND continues to run `normalize()` AFTER the rate-cap check but BEFORE any body-content check. Verify the order is preserved: setAdapterName → size-cap → rate cap → normalize. (The size cap fires before rate-cap so adversarial bodies cannot drive bucket-arithmetic cost on un-bounded payloads; rate-cap fires before normalize so the bucket-arithmetic short-circuits BEFORE the NFKC amplification cost — matching spec §Authorization model step 1.5 (rate cap) preceding step 1.7 (normalize). M1-038's body-size-cap test continues to pass.)"
  - "BundleKeys.java adds four new public constants: `ERROR_INVITE_REQUIRED = \"error.invite.required\"`, `ERROR_BAN_FIXED = \"error.ban.fixed\"`, `REPLY_WELCOME_DM_FRESH = \"reply.welcome.dm_fresh\"`, `REPLY_WELCOME_GROUP_FIRST_MENTION = \"reply.welcome.group_first_mention\"`. Verify: `grep -E 'ERROR_INVITE_REQUIRED\\s*=\\s*\"error\\.invite\\.required\"' BundleKeys.java` returns 1 match AND `grep -E 'ERROR_BAN_FIXED\\s*=\\s*\"error\\.ban\\.fixed\"' BundleKeys.java` returns 1 match AND `grep -E 'REPLY_WELCOME_DM_FRESH\\s*=\\s*\"reply\\.welcome\\.dm_fresh\"' BundleKeys.java` returns 1 match AND `grep -E 'REPLY_WELCOME_GROUP_FIRST_MENTION\\s*=\\s*\"reply\\.welcome\\.group_first_mention\"' BundleKeys.java` returns 1 match. The BundleLoaderTest's existing reflective bundle-completeness assertion (per M1-035c) automatically extends to the new keys — no test edit required for completeness coverage"
  - "bundles/en.properties adds the four bundle entries with text drawn from docs/design/03-commands.md §3.11 Welcome messages (for the two welcome keys) AND from docs/spec/security.md §Invite-code registration / §User ban (for the two fixed-error keys — the spec's quoted literal `Access requires an invitation.` and `Your access has been revoked.` resp.). Verify: `grep -E '^error\\.invite\\.required\\s*=' en.properties` returns 1 match AND the value contains the literal substring `Access requires an invitation` (the trailing period optional) AND `grep -E '^error\\.ban\\.fixed\\s*=' en.properties` returns 1 match AND the value equals or starts with `Your access has been revoked` AND `grep -E '^reply\\.welcome\\.dm_fresh\\s*=' en.properties` returns 1 match AND `grep -E '^reply\\.welcome\\.group_first_mention\\s*=' en.properties` returns 1 match"
  - "application.properties adds the per-profile rate-cap + invite TTL config keys. The base values mirror the spec's profile table (docs/spec/security.md §Rate limiting — `Per-user chat-mode messages (transport rate) 60/min token bucket`; docs/design/04-security.md §4.9 Per-user chat-mode messages and §4.5 brute-force threshold/window; docs/design/03-commands.md §3.10 TTL table). At minimum the file declares: `infochat.rate-cap.inbound-per-minute=60`, `infochat.invite.brute-force-threshold=10`, `infochat.invite.brute-force-window=1h`, `infochat.invite.ttl=7d`, `infochat.invite.open-cap-per-adapter=3`, `infochat.invite.contact-cap-global=50`, `infochat.probation.duration=24h`. Per-profile overrides (under `%vps`, `%pi`, `%remote-llm`) MAY be added but the laptop defaults above are mandatory. Verify: `grep -E '^infochat\\.rate-cap\\.inbound-per-minute=' application.properties` returns ≥1 match AND `grep -E '^infochat\\.invite\\.brute-force-threshold=' application.properties` returns ≥1 match AND `grep -E '^infochat\\.invite\\.ttl=' application.properties` returns ≥1 match AND `grep -E '^infochat\\.probation\\.duration=' application.properties` returns ≥1 match"
  - "InboundRouterIntakeOrderingTest pins the step ordering via a unit test that constructs InboundRouter with mock collaborators (the four services from M1-044a) and a fake CommandHandler, driving onMessage with synthetic InboundMessages and asserting (via mock interactions in order) the eight scenarios below. Each scenario is implemented as its own @Test method whose name contains the per-scenario identifying substring listed below (case-insensitive). Every grep MUST return ≥1 match — a regression in any one scenario fails its own check rather than being masked by an aggregate count. (a) DM body exceeds size cap → only the size-cap branch fires, no other collaborator consulted. `grep -iE 'void\\s+\\w*OverSizeCap\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java` ≥1. (b) DM over rate cap → rateCapBucket consulted, nothing else, no outbound. `grep -iE 'void\\s+\\w*OverRateCap\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java` ≥1. (c) DM under cap with body empty after normalize → no further collaborators, no outbound. `grep -iE 'void\\s+\\w*EmptyBodyAfterNormalize\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java` ≥1. (d) DM from unknown contact_id with valid invite body → rateCapBucket → normalize → inviteCodeConsumer (returns Accepted) → outbound is the welcome, banCheck NOT consulted, handleSlash NOT called. `grep -iE 'void\\s+\\w*UnknownContactValidInvite\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java` ≥1. (e) DM from unknown contact with invalid body → rateCapBucket → normalize → inviteCodeConsumer (returns Rejected) → outbound is error.invite.required, banCheck NOT consulted. `grep -iE 'void\\s+\\w*UnknownContactInvalidInvite\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java` ≥1. (f) DM from known is_banned=true contact → rateCapBucket → normalize → users lookup → banCheck (returns true) → outbound is error.ban.fixed, no handleSlash. `grep -iE 'void\\s+\\w*KnownBannedDmStops\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java` ≥1. (g) DM from known group_only contact with /help body → rateCapBucket → normalize → banCheck (returns false) → handleSlash → DM-gate post-check fires → outbound is error.invite.required (NOT the /help reply). `grep -iE 'void\\s+\\w*GroupOnlyDmGate\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java` ≥1. (h) Group @mention from unknown contact → rateCapBucket → normalize → autoRegisterService.resolveOrRegisterGroup → banCheck → handleSlash → outbound is the dispatch reply. `grep -iE 'void\\s+\\w*GroupMentionAutoRegisters\\w*\\s*\\(' InboundRouterIntakeOrderingTest.java` ≥1"
  - "InboundRouterTest gains exactly ONE new @Test method whose name contains `rateCapOverflowDropsSilentlyWithoutOutbound` (case-insensitive) that asserts when rateCapBucket.tryAcquire returns false, no reply is sent and no downstream service is consulted. M1-035b's eight pre-existing @Test methods continue to pass unchanged — none is deleted; none is renamed in a way that drops the identifying substring listed below. Verify the new method AND each of the eight pre-existing methods by its single-line declaration grep — every one MUST return ≥1 match. New: `grep -iE 'void\\s+\\w*rateCapOverflowDropsSilentlyWithoutOutbound\\w*\\s*\\(' InboundRouterTest.java` ≥1. M1-035b preservation: `grep -iE 'void\\s+\\w*emptyAndWhitespaceAndInvisibleOnlyBodies\\w*\\s*\\(' InboundRouterTest.java` ≥1; `grep -iE 'void\\s+\\w*leadingWhitespaceBeforeSlashCommand\\w*\\s*\\(' InboundRouterTest.java` ≥1; `grep -iE 'void\\s+\\w*chatModeBodyProducesDeterministic\\w*\\s*\\(' InboundRouterTest.java` ≥1; `grep -iE 'void\\s+\\w*unknownCommandProducesFriendly\\w*\\s*\\(' InboundRouterTest.java` ≥1; `grep -iE 'void\\s+\\w*commandHandlerExceptionProducesInternalError\\w*\\s*\\(' InboundRouterTest.java` ≥1; `grep -iE 'void\\s+\\w*firstDmSlashInsertsUsersRow\\w*\\s*\\(' InboundRouterTest.java` ≥1; `grep -iE 'void\\s+\\w*firstDmChatModeInsertsUsersRow\\w*\\s*\\(' InboundRouterTest.java` ≥1; `grep -iE 'void\\s+\\w*repeatedDmsFromSameContactProduceExactlyOne\\w*\\s*\\(' InboundRouterTest.java` ≥1"
  - "InboundRouterNormalizeTest (M1-035b) and InboundRouterContactIdRedactionTest (M1-038) continue to assert the same behaviors they did before this ticket — the body-size cap, the normalize pass, and the ContactIds.redact log-redaction logic are preserved unchanged. The M1-044b splice introduces 4 new mandatory @Inject fields on InboundRouter (rateCapBucket, inviteCodeConsumer, banCheck, bundleLoader, dataSource) that the M1-035b / M1-038 test helpers do not wire; a minimal helper modification is required to inject no-op fakes so the size-cap / normalize / redaction code paths the tests actually exercise can run. The constraint: NO @Test method body is modified — the helper-method changes are ONLY to wire the new collaborator stubs. The two specific helpers authorized to change are (a) `InboundRouterContactIdRedactionTest.newRouter()` (a private factory method already in the file) and (b) the inline `new InboundRouter()` block in `InboundRouterNormalizeTest.bodyAtExactlyTheCapIsAcceptedAndNormalizeRuns` (the one M1-035b @Test method that calls `router.onMessage(...)`; the other 6 normalize-related tests in that class invoke `InboundRouter.normalize(...)` as a static method and need no router instance — they are NOT modified). Verify per file: `git diff main -- infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java | grep -cE '^[+-]\\s*@Test\\b'` returns 0 AND `git diff main -- infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java | grep -cE '^[+-]\\s*@Test\\b'` returns 0 (the @Test annotation set is unchanged: no test method added, removed, or renamed in either file)"
  - "AdapterRegistryTest.singleAdapterHappyPathActivatesInMemoryAndRegistersRouter (M1-008b's wiring round-trip test — verifying AdapterRegistry → InMemoryAdapter → InboundRouter flow) continues to assert that delivering `/xyz` from contact `alice` produces UNKNOWN_COMMAND_REPLY (the M1-035b friendly-unknown-command reply, NOT the new ERROR_INVITE_REQUIRED reply step 2 would emit for an unseeded contact). The M1-044b splice would otherwise route the unknown DM contact through step 2 → Rejected → ERROR_INVITE_REQUIRED; to preserve the wiring-test's purpose, the test's `@BeforeEach` is extended to pre-seed an `alice` users row with `registration_state='invited'` (the same pattern used in InboundRouterTest's @BeforeEach widening per item 13 of this ticket). The canonical unknown-command behavior coverage stays in InboundRouterTest.unknownCommandProducesFriendlyUnknownCommandReply (preserved per item 13); AdapterRegistryTest's job is the round-trip wiring, not the unknown-command branch logic. NO @Test method body in AdapterRegistryTest is modified — only @BeforeEach is extended. Verify: `grep -E 'INSERT INTO users.*alice' infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRegistryTest.java` returns ≥1 match AND `git diff main -- infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRegistryTest.java | grep -cE '^[+-]\\s*@Test\\b'` returns 0 (no @Test annotation set change)"
  - "RateCapBucket.evictIdleBuckets widens the eviction predicate per the /redteam M1-044a DOS finding (verdict file docs/plan/m1/redteam/M1-044a-2026-05-21.md). The M1-044a predicate evicts only buckets where `tokens == inboundPerMinute` AND idle past threshold; a drained-and-abandoned bucket never refills (refill is lazy inside tryAcquire) so it stays pinned forever. The fix removes the tokens-equality requirement: eviction fires whenever the bucket has been idle past the threshold, regardless of token count. A returning contact pays a one-time bucket-cold cost (a new Bucket allocated full). Verify: `grep -nE 'tokens\\s*==\\s*inboundPerMinute' RateCapBucket.java` returns ZERO matches in the evictIdleBuckets method (the structural assertion that the equality gate has been removed)"
  - "RateCapBucketTest has a @Test method whose name contains `evictionDrainedIdle` (case-insensitive) that asserts the widened eviction: seed a bucket via N tryAcquire calls that drain it to ≤0 tokens, advance the test Clock past the eviction threshold without further tryAcquire calls, run the eviction sweep, and assert the bucket entry is removed from the underlying map. Verify: `grep -iE 'void\\s+\\w*evictionDrainedIdle\\w*\\s*\\(' RateCapBucketTest.java` returns ≥1 match"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass: M1-003 @QuarkusTest stubs, M1-007/007a/b/c, M1-008/008a/b/c, M1-022..M1-026, M1-027/028, M1-032/033/034a/034b, M1-035/035a/b/c/d, M1-036, M1-037, M1-038, M1-039, M1-040, M1-043, plus M1-044a's per-service tests (RateCapBucketTest's four M1-044a methods continue to pass under the widened eviction predicate)"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
    - infochat-provider/src/main/resources/bundles/en.properties
    - infochat-provider/src/main/resources/application.properties
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRegistryTest.java (M1-008b — @BeforeEach pre-seed of alice users row; no @Test method body change; acceptance item 15)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java (M1-035b — inline new InboundRouter() block in bodyAtExactlyTheCapIsAcceptedAndNormalizeRuns wired with no-op fakes for the 4 new mandatory @Inject fields; no @Test method body change; acceptance item 14)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java (M1-038 — private newRouter() factory method wired with no-op fakes for the 4 new mandatory @Inject fields; no @Test method body change; acceptance item 14)
  preserves:
    - all tests currently green on main outside files_scope
    - M1-044a's InviteCodeConsumerTest / BanCheckTest / AutoRegisterServiceTest / AdapterRouterIT (RateCapBucketTest is extended, not preserved)
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §User ban
  - docs/spec/security.md §Invite-code registration
  - docs/spec/security.md §Rate limiting
  - docs/spec/commands.md §Onboarding
decision_refs:
  - D11
  - D44
  - D45
  - D46
revisions:
  - date: 2026-05-21
    reason: premise-fail refine snapshot (item 14 structurally unsatisfiable; AdapterRegistryTest collateral)
    summary: |
      Pre-refine snapshot. Round-1 mvn verify (after clarity PASS + Plan
      PASS + status flip to in-progress) failed with 8 failures + 4
      errors traced to three test classes. Two distinct defects in the
      ticket's acceptance:

      Defect 1 — item 14 "ZERO changes" constraint is structurally
      unsatisfiable. Item 14 said InboundRouterNormalizeTest (M1-035b)
      and InboundRouterContactIdRedactionTest (M1-038) "continue to
      pass without ANY modification — `git diff main -- ...` returns
      ZERO changes". The M1-044b splice introduces 4 new mandatory
      @Inject fields on InboundRouter (rateCapBucket,
      inviteCodeConsumer, banCheck, bundleLoader, dataSource); both
      test files use a `newRouter()`-style helper that does
      `new InboundRouter()` and assigns the pre-M1-044b field set, so
      onMessage NPEs on the new fields. The only ways to satisfy
      item 14 — make the new fields optional / null-tolerant, or skip
      the splice for those test contacts — violate the engineering
      rules. The clarity preflight's runnable-grep check could not
      detect this because the conflict only surfaces at mvn-verify
      time when the splice actually runs.

      Defect 2 — AdapterRegistryTest collateral damage, NOT in
      files_scope. AdapterRegistryTest.singleAdapterHappyPathActivatesInMemoryAndRegistersRouter
      delivers `/xyz` from unknown alice and expects
      UNKNOWN_COMMAND_REPLY. Under the new splice, an unknown DM
      contact routes through step 2 → Rejected → ERROR_INVITE_REQUIRED.
      The test's purpose is wiring (AdapterRegistry → InMemoryAdapter
      → InboundRouter round-trip), NOT unknown-command logic; the
      canonical unknown-command coverage in
      InboundRouterTest.unknownCommandProducesFriendlyUnknownCommandReply
      still passes. Fix is a @BeforeEach pre-seed of an alice users
      row with registration_state='invited' (same pattern as the
      InboundRouterTest fix already in the round-1 diff).

      Pre-refine acceptance item 14 (verbatim):
        "InboundRouterNormalizeTest (M1-035b) and
         InboundRouterContactIdRedactionTest (M1-038) continue to pass
         without ANY modification — the size cap, normalize pass, and
         ContactIds.redact log-redaction behavior are preserved
         unchanged. These two files appear in files_scope only so the
         reviewer can verify they were NOT inadvertently modified
         (negative-space check). Verify: `git diff main --
         infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java`
         returns ZERO changes AND the same for
         InboundRouterContactIdRedactionTest.java"

      Refine target:
        (A) Loosen item 14 from "ZERO changes" to "no @Test method
            body modified; helper-method changes ONLY to wire new
            collaborator stubs are allowed", citing the cause and
            naming the specific helper methods. New verification:
            `git diff main -- <path> | grep -c '^[+-].*@Test'`
            returns 0 for each file.
        (B) Add AdapterRegistryTest.java to files_scope + a new
            acceptance item (the @BeforeEach alice pre-seed) +
            Authorized test changes entry.
        (C) Bump files_budget from 10 to 13 (10 existing +
            AdapterRegistryTest + 2 negative-space tests
            now-modifiable).
        (D) Update test_plan.modifies / preserves: move
            InboundRouterNormalizeTest + InboundRouterContactIdRedactionTest
            from preserves to modifies (helper-only constraint); add
            AdapterRegistryTest to modifies. Add a carve-out to the
            Out-of-scope expansion section.

      The round-1 implementation in the working tree (uncommitted)
      remains structurally valid against the refined acceptance — the
      branch carries no commits beyond main; only the three test
      files need helper edits to align with the refined items.

  - date: 2026-05-21
    reason: clarity-fail refine snapshot (item 8 ordering contradiction with item 1 / DoD / Impl-notes)
    summary: |
      Pre-refine snapshot. The second `/m1-tick start M1-044b` clarity
      preflight (after the clarity-warn refine of items 12 + 13)
      returned FAIL with 1 blocker: acceptance item 8 stated
      `setAdapterName → size-cap → normalize → rate cap` while item 1,
      the DoD bullet, and the Implementation notes code sketch all
      asserted the opposite order (`rate cap → normalize`, matching the
      spec's step 1.5 before step 1.7). The bug had been in the ticket
      since the original draft (commit f7af709) — round-1 clarity
      flagged item 8 only as a "reading check" (form-level WARN under
      ACCEPTANCE-RUNNABLE) without reading the content against item 1.

      Pre-refine acceptance item 8 (verbatim):
        "InboundRouter.onMessage continues to apply the body-size cap
         (M1-038) BEFORE normalization AND continues to run `normalize()`
         BEFORE any body-content check. Verify the order is preserved:
         setAdapterName → size-cap → normalize → rate cap. (The size
         cap fires before rate-cap so adversarial Hangul-jamo bodies
         cannot drive NFKC amplification cost AND to bound the inbound
         payload before it hits the bucket arithmetic — M1-038's
         body-size-cap test continues to pass.)"

      Refine target: rewrite the verification sentence to match the
      spec / DoD / Impl-notes order (size-cap → rate cap → normalize)
      and tighten the parenthetical justification so the rationale
      matches the new order.

      Structural process changes landed alongside this refine
      (separate `process:` commit) so the bug class cannot recur:
        - lint-ticket.py ACCEPTANCE-ORDERING-CONSISTENT (BLOCKER) —
          mechanical arrow-bigram contradiction check.
        - clarity-prompt.md check #11 (FAIL) — LLM prose-variant check
          for orderings expressed without arrows.

      Memory entry [[feedback-acceptance-ordering-consistency]] records
      the recurring-pattern rule so future tickets pre-empt the trap.
  - date: 2026-05-21
    reason: clarity-warn refine snapshot (items 12 + 13 acceptance phrasing)
    summary: |
      Pre-refine snapshot. The `/m1-tick start M1-044b` clarity preflight
      returned WARN with 3 warnings; warning #3 (heterogeneous-aggregate
      @Test count on item 12) matches a user-standing feedback rule
      (no heterogeneous-aggregate @Test counts when DoD enumerates ≥3
      distinct test methods). User picked refine to tighten items 12 + 13
      before status flips to in-progress.

      Pre-refine acceptance items (verbatim):
        - Item 12 ended: "`grep -E '@Test' InboundRouterIntakeOrderingTest.java`
          returns ≥6 matches (matching the 6 distinct DM scenarios above;
          the group scenario MAY be a separate test or folded into the
          same class)"
        - Item 13 ended: "`grep -E '@Test' InboundRouterTest.java` returns
          ≥(N+1) matches where N is the count before this ticket"

      Refine target: replace the aggregate-count assertions with
      per-scenario / per-method-name greps that pin each of the 8
      ordering-test scenarios (a)–(h) and the one new
      `rateCapOverflowDropsSilentlyWithoutOutbound` method individually.
      No other frontmatter or DoD content is changed by this refine; the
      Implementation notes already commit to the exact method count.
  - date: 2026-05-21
    reason: redteam-finding fold-in (M1-044a DOS-low + AUTH-BYPASS-low)
    summary: |
      /redteam M1-044a (verdict file docs/plan/m1/redteam/M1-044a-2026-05-21.md)
      returned 4 findings; the 2 medium findings landed in the new M1-044d
      remediation ticket. The 2 low findings are folded here per user direction:

      DOS (low) — RateCapBucket.evictIdleBuckets requires `tokens == cap`
      AND idle past threshold; drained-and-abandoned buckets never refill
      (refill is lazy inside tryAcquire) so they stay pinned. Fix folded
      into this ticket: drop the tokens-equality gate; evict on idle alone.
      Changes:
        - files_scope: added RateCapBucket.java + RateCapBucketTest.java
        - files_budget: 8 → 10
        - acceptance: added 2 items (eviction-predicate widening +
          evictionDrainedIdle test method)
        - test_plan.modifies: added RateCapBucket.java + RateCapBucketTest.java
        - Implementation notes: added §"Rate-cap eviction-predicate fix"
        - Authorized test changes: added RateCapBucketTest.java entry
        - out_of_scope: carved out the RateCapBucket.java exception with
          explicit cross-reference to the verdict file
      The RateCapBucket modification is a contained surgical edit (single
      method body) that does NOT cross-cut with the splice work; the
      reviewer's scope-drift check will see two distinct concern groups
      (intake-step splice; eviction fix) bound to two distinct acceptance
      blocks (items 1-13 + 17 splice; items 15-16 eviction). This is the
      smallest viable shape that avoids creating a third tiny remediation
      ticket for a one-method change.

      AUTH-BYPASS (low) — InviteCodeConsumer.consume defaults open;
      M1-044b's existing acceptance items 3 (Step 2 emptiness
      precondition) and 5 (Step 4 ban check after step 2/3) already
      enforce the caller-side gating that prevents the scenario. NO new
      acceptance items needed; a Big-picture note was added pinning the
      single-snapshot reuse pattern (derive both the emptiness predicate
      and the ban predicate from the SAME UserSnapshot lookup to
      eliminate TOCTOU).
---

# M1-044b: InboundRouter intake-step splice (1.5, 2, 4, 7-DM-gate) + bundle keys + rate-cap config

## Context

T2-A.1 subticket 2 of 3 (preceded by M1-044a). M1-044a landed
the four intake-step services (`RateCapBucket`,
`InviteCodeConsumer`, `BanCheck`, narrowed `AutoRegisterService`)
plus the V12 brute-force-counter migration. This ticket wires
them into the production dispatcher at
`InboundRouter.onMessage` in the spec's exact authorization
order, ships the four new fixed-reply bundle keys the splice
emits, and lands the per-profile rate-cap / invite-TTL config
in `application.properties`. The umbrella M1-044 then ships the
cross-cutting roundtrip IT; M1-044c lands the admin command
handlers (`/ban`, `/unban`, `/invite create/list/revoke`).

The splice is the single load-bearing security commitment of
T2-A.1: every spec promise in §Authorization model from step
1.5 through step 7-DM-gate-carve-out becomes runnable here.

`complexity: high` and `risk: high` because the step ordering
is a security-critical invariant (deviation is a CVE-class
defect — a banned user reaching the LLM via misordered steps,
an unknown contact bypassing the invite gate, a `group_only`
user driving DM-only commands). The `round_cap: 3` accommodates
likely-to-fail-first acceptance items (the seven-step ordering
unit test in particular).

`security_relevant: true`.

## Definition of Done

- `InboundRouter.onMessage` invokes the four M1-044a services
  in the spec's exact order: step 1.5 (rate cap) → 1.7
  (normalize, already on disk) → 2 (invite consume, DM
  unknown only) → 3 (group auto-register, group `@mention`
  only) → 4 (ban check) → 6 (parse + dispatch, already on
  disk) → 7 (DM-gate carve-out for `group_only` users).
- Each step's failure / short-circuit shape matches the spec:
  - 1.5 over-cap → silent drop, no outbound.
  - 2 Accepted → welcome reply, dispatch stops.
  - 2 Rejected / BruteForceThresholdBreached → fixed
    `error.invite.required` reply, dispatch stops.
  - 4 banned → fixed `error.ban.fixed` reply, dispatch stops.
  - 7 DM-gate → command handler's reply is REPLACED with the
    `error.invite.required` literal.
- `BundleKeys.java` adds four new public constants;
  `bundles/en.properties` adds the four matching entries.
- `application.properties` adds the per-profile rate-cap +
  invite TTL + brute-force threshold/window + probation
  duration + PENDING cap config keys.
- `InboundRouterIntakeOrderingTest` (new) pins the step
  ordering across the six DM scenarios + the group scenario.
- `InboundRouterTest` (M1-035b's, modified) adds one new test
  for the rate-cap silent-drop case; all prior assertions
  remain.
- `InboundRouterNormalizeTest` (M1-035b's) and
  `InboundRouterContactIdRedactionTest` (M1-038's) are
  helper-only modified — the `newRouter()` factory / inline
  `new InboundRouter()` block in their onMessage-path tests
  is wired with no-op fakes for the 4 new mandatory @Inject
  fields on InboundRouter; NO `@Test` method body is changed
  in either file. The body-size cap, normalize pass, and
  ContactIds.redact log-redaction assertions remain verbatim.
- `AdapterRegistryTest` (M1-008b's) is helper-only modified —
  its `@BeforeEach` pre-seeds an `alice` users row with
  `registration_state='invited'` so the splice's step 2
  invite-gate does not short-circuit the wiring round-trip.
  No `@Test` method body is changed; the
  `singleAdapterHappyPathActivatesInMemoryAndRegistersRouter`
  assertion on UNKNOWN_COMMAND_REPLY remains the test's exit
  condition.
- `mvn -B clean verify` exits 0.

## Implementation notes

- **The splice shape inside `onMessage`.** Read the current
  M1-040 body (in `InboundRouter.java` lines 194-249) for
  reference. The new body, in order:
  ```java
  @ActivateRequestContext
  public void onMessage(InboundMessage msg, String adapterName) {
      inboundContext.setAdapterName(adapterName);
      String raw = msg.text();
      // M1-038 body-size cap (preserved).
      if (raw != null && raw.getBytes(StandardCharsets.UTF_8).length > maxInboundBodyBytes) {
          sendReply(msg.scope(), MESSAGE_TOO_LARGE_REPLY);
          return;
      }
      // Step 1.5 — transport-level rate cap.
      String contactId = contactIdOf(msg.scope());
      if (!rateCapBucket.tryAcquire(adapterName, contactId)) {
          return;          // silent drop per spec §Authorization model step 1.5
      }
      // Step 1.7 — Unicode normalize (preserved).
      String normalized = normalize(raw);
      if (normalized.isEmpty()) {
          return;
      }
      // Step 2 — DM unknown contact + invite consume.
      // Step 3 — Group unknown contact + auto-register.
      // Step 4 — Ban check.
      // Step 6 — Parse + dispatch.
      // Step 7 — DM-gate carve-out.
      // (full body in the implementation; the comment block
      // here is a sketch.)
  }
  ```
- **Step 2 dispatch.** When `scope instanceof ScopeRef.Dm dm`
  AND `usersLookup.findByContactId(adapter, dm.contactId())`
  returns empty: call `inviteCodeConsumer.consume(adapter,
  contactId, normalized)`. Switch on the typed outcome:
  Accepted → send welcome via `bundleLoader.get(REPLY_WELCOME_DM_FRESH)`;
  Rejected / BruteForceThresholdBreached → send
  `bundleLoader.get(ERROR_INVITE_REQUIRED)`. Return after
  either reply.
  - The `usersLookup` is a small new helper (a tiny method on
    InboundRouter or a private `@Inject DataSource`-using
    block — implementer's choice). The SELECT shape is
    `SELECT id, is_banned, registration_state FROM users
    WHERE adapter = ? AND contact_id = ?` (a single row fetch
    used by step 2 emptiness check + step 4 ban check + step 7
    DM-gate). To avoid three SELECTs per inbound, cache the
    result of the first lookup in a local variable and reuse
    for step 4 and step 7. This is a single-query optimization,
    not an architectural change.
- **Step 3 dispatch.** When `scope instanceof ScopeRef.Group`
  AND the users lookup returned empty: call
  `autoRegisterService.resolveOrRegisterGroup(msg.sender(),
  adapterName)` and continue to step 4 (the spec says
  "Continue to step 4"). The auto-promote slot is NOT consumed
  here (the spec says probation users are ineligible; the
  registered user is in probation by construction). Group
  membership row inserts are deferred to T2-F.
- **Step 4 dispatch.** Re-read the users lookup result; if
  `is_banned`, send `bundleLoader.get(ERROR_BAN_FIXED)` and
  return. For groups, the users row may have just been written
  in step 3 — re-read after the auto-register write OR pass
  the locally-known `is_banned=false` forward (a freshly
  auto-registered user is never banned — the AutoRegisterService
  INSERT writes `is_banned=FALSE` per the V5 default).
- **Step 6.** `handleSlash` is the M1-035b method already on
  disk; preserved unchanged. The chat-mode fallback is the
  M1-035b `CHAT_MODE_REPLY` literal preserved unchanged (T2-D
  replaces it).
- **Step 7 DM-gate.** After `handleSlash` returns its body
  string, check `scope instanceof ScopeRef.Dm` AND
  `usersLookup.registrationState == "group_only"`. If both,
  REPLACE the body with `bundleLoader.get(ERROR_INVITE_REQUIRED)`.
  Send the chosen body to the adapter.
  - **Why not block before dispatch?** The spec sequences the
    DM-gate at step 7 (permission check), which the design
    notes describe as "permission check against the matrix."
    The full permission matrix is M1-045 territory (probation
    + actor-tier + bot-admin gating). The DM-gate is the only
    permission-step rule that lands in M1-044b — handlers can
    still resolve in M1-044b's commit, and the DM-gate
    override happens in `onMessage` rather than in each
    handler. This means in particular: a `group_only` user
    issuing `/help` will execute the HelpCommandHandler (small
    DB read), get the rendered help text, and then have the
    response replaced with the DM-gate reply before the
    outbound goes out. The HelpCommandHandler's SELECT is
    cheap (a `users` row read) so the wasted work is bounded;
    the implementation MAY pre-check the DM-gate before
    dispatch as an optimization, but the spec correctness is
    what counts.
- **The `users` SELECT shape.** `SELECT id, is_banned,
  registration_state FROM users WHERE adapter = ? AND
  contact_id = ?`. The (adapter, contact_id) UNIQUE constraint
  guarantees zero-or-one result. Use a small private record
  inside InboundRouter: `record UserSnapshot(UUID id, boolean
  isBanned, String registrationState) {}`.
- **`@ConfigProperty` for the rate-cap value at
  InboundRouter** — none; RateCapBucket owns the cap (M1-044a).
  M1-044b adds the property key to `application.properties`;
  RateCapBucket reads it. No `@ConfigProperty` on InboundRouter
  for the rate-cap value.
- **Bundle entry text** for the welcome keys is drawn from
  `docs/design/03-commands.md` §3.11 Welcome messages (Mode
  1: DM fresh; Mode 3: Group first @mention). The English
  literal in the design's bracketed `[bot]` lines is the bundle
  value. Multi-line bundle values use literal `\n` per
  Properties file convention. The exact wording is
  implementer-of-this-ticket's transcription from the design
  note; do NOT amend the design note.
- **`application.properties` shape.** Add the seven base
  properties listed in acceptance item 11 under the existing
  property block. Per-profile overrides go under `%vps`,
  `%pi`, `%remote-llm` blocks if any value differs from the
  base. For the M1-044b commit, base values are mandatory;
  per-profile overrides are encouraged but not required (a
  follow-up ticket can fold the per-profile values from
  docs/design/04-security.md and docs/design/03-commands.md as needed).
- **InboundRouterIntakeOrderingTest design.** Plain JUnit
  + Mockito or similar (M1-035b's InboundRouterTest pattern).
  Construct InboundRouter via `new InboundRouter()` and
  assign package-private fields directly (mocks for
  `rateCapBucket`, `inviteCodeConsumer`, `banCheck`,
  `autoRegisterService`, `inboundContext`, `replyTarget`).
  Verify mock invocations in expected order via Mockito's
  `InOrder` verifier or sequential `verify(...)` calls.
  - The test does NOT need a real DataSource or
    Testcontainers — every database-touching collaborator is
    mocked. This is what makes the test "fast unit"
    rather than "IT."
- **InboundRouterTest updates.** M1-035b's existing tests
  (chat-mode-not-in-MVP, unknown-command, dispatch
  happy-path, normalize empty body, replyTarget-missing
  logging) MUST stay green. Add one new test method:
  `rateCapOverflowDropsSilentlyWithoutOutbound`. That test
  constructs the router with a `RateCapBucket` mock that
  returns `false`, asserts no reply was sent, and asserts no
  downstream service was consulted.
- **Rate-cap eviction-predicate fix (folded /redteam M1-044a DOS
  finding).** `RateCapBucket.evictIdleBuckets` (M1-044a) requires
  `bucket.tokens == inboundPerMinute` AND idle past threshold;
  refill is lazy (only `tryAcquire` advances
  `lastRefillEpochMillis`), so a bucket drained-and-abandoned by
  its contact never refills back to cap and is pinned forever.
  Fix: drop the tokens-equality gate from the eviction predicate.
  Evict on idle alone — `lastRefillEpochMillis < NOW() -
  evictionThreshold`. The eviction-sweep cost stays the same; the
  memory-bound becomes "buckets per (adapter, contactId) active in
  the last evictionThreshold window," not "buckets per
  (adapter, contactId) ever seen." A returning contact whose
  bucket has been evicted pays a one-time bucket-cold cost
  (`computeIfAbsent` allocates a fresh bucket at full token count).
  This is the same behavior a process restart would produce for a
  long-idle contact, so the bound is conservative.
  - RateCapBucketTest gains one new method (`evictionDrainedIdle`
    per acceptance item 17). The M1-044a `RateCapBucketTest`'s four
    existing tests (underCap, overCap, independent, refill) MUST
    continue to pass — they each issue a tryAcquire that resets
    `lastRefillEpochMillis`, so no eviction would have fired
    under either the M1-044a or M1-044b predicate within their
    timeframes.

## Big-picture notes

- **The DM-gate post-dispatch override is the load-bearing
  defense for D44.** Without it, a `group_only` user could
  send `/help` (or any other slash command the handler
  accepts) and receive the response — silently bypassing the
  invite gate. M1-045 lands the full step-7 permission matrix
  including the per-command probation set; this ticket lands
  the single DM-gate rule per the spec's "rejected with the
  same fixed reply as step 2's invalid path."
- **The seven-step ordering is the security promise.** A
  reordering — e.g. ban check before invite consume — would
  fail open in subtle ways (an unknown contact would be
  treated as not-banned and routed through the invite gate,
  which is correct; but if the invite path were authoritative
  and the ban check ran second, a pre-ban row's `is_banned`
  would be missed). The current spec order (1 → 1.5 → 1.7 →
  2 → 3 → 4 → 6 → 7) IS the secure order; this ticket
  implements it verbatim.
- **The umbrella's IT in M1-044 will catch a reordering
  defect at commit time** — the seven-step roundtrip exercises
  the order against the real services. The unit test here
  pins it with mocks for fast feedback during implementation.
- **M1-045's step 5 (probation check) splices BETWEEN step 4
  (ban check) and step 6 (parse).** M1-045 will modify
  InboundRouter.onMessage again to insert that one step;
  this ticket's commit leaves a deliberate comment at the
  step 4 / step 6 transition naming the M1-045 seam.
- **/redteam M1-044a AUTH-BYPASS finding (low) — caller-side
  gating is the load-bearing defense.** Verdict file
  docs/plan/m1/redteam/M1-044a-2026-05-21.md flagged that
  `InviteCodeConsumer.consume` does not itself verify no `users`
  row exists for `(adapter, contactId)` — it defaults open and
  relies on the caller to enforce the precondition. This ticket
  IS that caller, and acceptance items 3 (Step 2 — only when
  `users.findByContactId(adapter, contactId)` is empty) and 5
  (Step 4 ban check runs AFTER step 2/3 resolved the user) pin
  the gating order that prevents the scenario. The implementer
  MUST treat acceptance item 3's emptiness precondition as
  non-negotiable: if the users-row lookup returns a row (any
  registration_state, any is_banned value), `consume` MUST NOT
  be invoked. The local `UserSnapshot` record (see the users-
  SELECT-shape note above) is the single source of truth for
  the lookup — derive both the step-2 emptiness predicate and
  the step-4 ban predicate from the SAME snapshot to eliminate
  TOCTOU between the two checks.

## Out-of-scope expansion

- **M1-044a services.** Consumed unchanged.
- **Admin command handlers.** M1-044c.
- **/vouch handler + probation step 5 + step 7 matrix.**
  M1-045.
- **/grant-admin + /revoke-admin.** M1-046.
- **The umbrella's roundtrip IT.** M1-044.
- **Per-profile property values beyond the laptop defaults.**
  Encouraged but not required; a follow-up ticket may add the
  full per-profile table.
- **CommandPermissions class + asset-family oracle stub.**
  M1-045.
- **The `audit_log_view` redaction-stub bodies.** V5 ships
  pass-through stubs; the real redaction lands in M1-041.
- **Modifying any pre-existing test outside the eleven files
  in files_scope.** M1-035c/M1-036/M1-037/M1-039/M1-040 tests
  stay green unchanged — InboundRouter's intake splice changes
  the dispatch path's behavior for unknown DM contacts, but
  those existing tests seed registered users (or stub the
  relevant collaborators) so the new gates pass through them.
  - **Three pre-existing tests in files_scope ARE extended
    with helper-only modifications** (carve-out from the
    "stays green unchanged" rule above; details in acceptance
    items 14 and 15 plus the Authorized test changes section):
    - `AdapterRegistryTest.java` (M1-008b): @BeforeEach widens
      to pre-seed an `alice` users row with
      `registration_state='invited'` so the wiring round-trip's
      `/xyz` delivery still produces UNKNOWN_COMMAND_REPLY.
    - `InboundRouterNormalizeTest.java` (M1-035b) and
      `InboundRouterContactIdRedactionTest.java` (M1-038):
      the `newRouter()` / inline `new InboundRouter()` helpers
      gain no-op fakes for the 4 new mandatory @Inject fields
      (`rateCapBucket`, `inviteCodeConsumer`, `banCheck`,
      `bundleLoader`, `dataSource`) so the existing assertions
      can still run.
  - In all three cases, NO `@Test` method body is modified —
    every prior behavioral assertion stays as the originating
    ticket wrote it.

## Authorized test changes

- `InboundRouterTest.java` — M1-035b's class is **extended**
  (one new test method) but no prior method is modified.
- `AdapterRegistryTest.java` — M1-008b's class is **extended**
  (the `@BeforeEach` resetAdapterState is widened to pre-seed an
  `alice` users row with `registration_state='invited'` so the
  M1-044b splice's step 2 invite-gate does not short-circuit the
  wiring round-trip's `/xyz` delivery). No `@Test` method body is
  modified; the existing single happy-path assertion on
  `UNKNOWN_COMMAND_REPLY` remains the test's exit condition. See
  acceptance item 15.
- `InboundRouterNormalizeTest.java` (M1-035b) — extended with a
  helper-only modification per acceptance item 14: the inline
  `new InboundRouter()` block in
  `bodyAtExactlyTheCapIsAcceptedAndNormalizeRuns` (the one @Test
  method that calls `router.onMessage(...)`) is wired with no-op
  fakes for the 4 new mandatory @Inject fields (`rateCapBucket`,
  `inviteCodeConsumer`, `banCheck`, `bundleLoader`, `dataSource`).
  The other 6 normalize-related @Test methods invoke
  `InboundRouter.normalize(...)` as a static method and need no
  router instance — they are NOT modified.
- `InboundRouterContactIdRedactionTest.java` (M1-038) — extended
  with a helper-only modification per acceptance item 14: the
  private `newRouter()` factory method is updated to assign no-op
  fakes for the 4 new mandatory @Inject fields. No `@Test` method
  body is modified — every redaction assertion (the contact-id
  redaction in log lines on size-cap reject, on normalize-empty
  return, on dispatch happy-path, etc.) stays as M1-038 wrote it.
- `RateCapBucketTest.java` — M1-044a's class is **extended**
  (one new `evictionDrainedIdle` test method per acceptance
  item 17) but no prior method is modified. The four M1-044a
  methods (underCap, overCap, independent, refill) MUST stay
  green under the widened eviction predicate; the new method
  exercises only the previously-untested drained-and-idle case.
- (no other pre-existing test is modified by this ticket.)

## Alternatives considered

- **Land the DM-gate check at step 4 (before dispatch).**
  Rejected because the spec sequences it at step 7
  ("permission check") and combining the gate with the ban
  check would (a) bury two distinct rules under one branch and
  (b) require a refactor when M1-045 lands the full step-7
  matrix. The post-dispatch override is the smallest viable
  shape that respects the spec's step-ordering and leaves
  room for M1-045's expansion.
- **Pre-empt M1-045 by landing step 5 (probation check) in
  this ticket.** Rejected — the probation check requires the
  `CommandPermissions` allowlist (M1-045) and the asset-family
  oracle seam (M1-045). Landing it here would expand scope
  significantly and couple this ticket to M1-045's design.
- **Have InboundRouter cache the users-row lookup across
  inbound dispatches.** Rejected for v1 — the inbound
  dispatch is fast enough that one SELECT per inbound is
  acceptable, and a cache introduces a staleness vector for
  ban-row mutations (which must take effect on the NEXT
  inbound from the banned user). The local-variable reuse
  across step 2 / 4 / 7 within a SINGLE dispatch is the only
  caching the implementation does.
- **Replace the M1-040 `setAdapterName` call with a passed
  parameter through the new services.** Rejected because the
  M1-040 `InboundContext` bean is the production-blessed
  per-request seam; replacing it would defeat M1-040's
  design and force a SPI rewrite.
- **Pre-check the DM-gate before dispatch (skip the handler
  entirely for `group_only` users).** Considered as a future
  optimization; not in this ticket. The wasted dispatch cost
  is bounded (a single `users` SELECT for /help) and the
  spec-correct shape is the post-dispatch override. If M1-045
  finds the dispatch waste material, it can move the check
  before dispatch as part of the full step-7 matrix.
