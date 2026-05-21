---
id: M1-044b
title: InboundRouter intake-step splice (1.5, 2, 4, 7-DM-gate) + bundle keys + rate-cap config
status: pending
created: 2026-05-20
last_updated: 2026-05-21
blocked_by:
  - M1-044a
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/application.properties
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
  - any change to M1-044a's InviteCodeConsumer, BanCheck, AutoRegisterService, or the V12 migration — those are M1-044a's commit and consumed unchanged via @Inject. RateCapBucket.java is the ONE exception: per the /redteam M1-044a low-severity DOS finding (verdict file docs/plan/m1/redteam/M1-044a-2026-05-21.md), this ticket widens the eviction predicate to evict drained idle buckets — see acceptance item 15 and Implementation notes §"Rate-cap eviction-predicate fix"
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
  - any test outside the eight files in files_scope — every M1-035c/M1-036/M1-037/M1-038/M1-039/M1-040 test stays green unchanged
acceptance:
  - "InboundRouter.onMessage executes the intake steps in the spec's exact numerical order: identity (step 1, already resolved by AdapterRegistry into msg.sender()) → 1.5 rate cap → 1.7 normalize (already on disk) → 2 invite gate (DM unknown only) → 3 group auto-register (group `@mention` only) → 4 ban check → 6 parse + dispatch → 7 permission DM-gate carve-out (DM from `registration_state='group_only'` rejected with the invite-required reply). Verify by reading InboundRouter.java: the calls appear in source order rateCapBucket.tryAcquire → normalize → inviteCodeConsumer.consume → autoRegisterService.resolveOrRegisterGroup → banCheck.isBanned → handleSlash"
  - "Step 1.5 — rate-cap silent drop: when `rateCapBucket.tryAcquire(adapter, contactId)` returns false, InboundRouter.onMessage returns IMMEDIATELY with no outbound reply (no fixed ban reply, no fixed invite-required reply, no friendly error) per spec §Authorization model step 1.5 (`Over-cap inbound is dropped silently`). Verify InboundRouter.java: the rate-cap branch contains no `sendReply` call and returns directly. grep -E 'rateCapBucket\\.tryAcquire' InboundRouter.java returns ≥1 match"
  - "Step 2 — DM unknown contact + invite-code consume: when the inbound is a DM scope (`scope instanceof ScopeRef.Dm`) AND the resolved `users.findByContactId(adapter, contactId)` is empty, InboundRouter invokes `inviteCodeConsumer.consume(adapter, contactId, normalizedBody)`. On Accepted outcome, the welcome reply (`reply.welcome.dm_fresh` bundle value) is sent and dispatch STOPS (no slash parse, no chat-mode fallback) per spec step 2 (`Valid: create user row, mark code USED, send welcome, stop. No further processing.`). On Rejected outcome, the fixed `error.invite.required` reply is sent and dispatch STOPS. On BruteForceThresholdBreached outcome, the fixed `error.invite.required` reply is sent (same user-visible reply per spec — the rate-limit state does not change the per-failure reply) and dispatch STOPS"
  - "Step 3 — group `@mention` auto-register: when the inbound is a Group scope AND no `users` row exists for `(adapter, contactId)`, InboundRouter invokes `autoRegisterService.resolveOrRegisterGroup(msg.sender(), adapter)` (the M1-044a narrowed method); the call writes a row with `registration_state='group_only'` and `probation_until = NOW() + slow_start_window`; dispatch CONTINUES to step 4 per spec §Authorization model step 3 (`Continue to step 4`). The first-@mention auto-promote and group_membership row inserts are deferred to T2-F (group support — this ticket does NOT auto-promote nor write group_membership)"
  - "Step 4 — ban check: AFTER step 2/3 resolved the user, InboundRouter invokes `banCheck.isBanned(adapter, contactId)`; when true, the fixed `error.ban.fixed` reply (the `Your access has been revoked.` literal) is sent and dispatch STOPS per spec §User ban (`Banned user receives one fixed reply per inbound message, regardless of input`). grep -E 'banCheck\\.isBanned' InboundRouter.java returns ≥1 match. The reply body equals the `error.ban.fixed` bundle value (NOT a different literal)"
  - "Step 7 — DM-gate carve-out for `group_only` users: after a slash-command dispatch produces an outbound, BUT BEFORE returning, when the inbound is a DM scope AND `users.registration_state = 'group_only'` for the resolved row, the dispatch result is REPLACED with the fixed `error.invite.required` reply (the same fixed reply step 2's invalid path uses, per spec §Invite-code registration `Group-registered users do not get free DM access ... rejected with the same fixed reply as step 2's invalid path`). The DM-gate check fires AFTER the ban check (step 4) AND AFTER the dispatch to the command handler — the spec sequences DM-gate at step 7, NOT step 4. Verify InboundRouter.java: the DM-gate check is sequenced after handleSlash returns AND only fires when the scope is ScopeRef.Dm. Note: this differs from the spec's prose ordering — the spec describes step 7 as part of permission evaluation; here the carve-out is implemented as a post-dispatch result override so the dispatch can still resolve the handler (the permission check runs INSIDE the handler today per M1-035c/M1-036/M1-037; full step-7 permission matrix lands in M1-045). The /stop carve-out from spec §Slow-start tier (`/stop is not blocked`) and analogous carve-outs are NOT in this ticket's scope — the DM-gate fires for EVERY slash command issued by a `group_only` user; M1-045's CommandPermissions can later widen the DM-gate to allow specific commands if the spec changes (the current spec gives no per-command carve-out for the DM-gate)"
  - "InboundRouter.onMessage continues to invoke `inboundContext.setAdapterName(adapterName)` BEFORE any service call, so M1-040's adapter-scoped `users` lookups in downstream handlers continue to see the correct adapter. Verify: `grep -E 'inboundContext\\.setAdapterName' InboundRouter.java` returns ≥1 match AND the call appears before `rateCapBucket.tryAcquire` in source order"
  - "InboundRouter.onMessage continues to apply the body-size cap (M1-038) BEFORE normalization AND continues to run `normalize()` BEFORE any body-content check. Verify the order is preserved: setAdapterName → size-cap → normalize → rate cap. (The size cap fires before rate-cap so adversarial Hangul-jamo bodies cannot drive NFKC amplification cost AND to bound the inbound payload before it hits the bucket arithmetic — M1-038's body-size-cap test continues to pass.)"
  - "BundleKeys.java adds four new public constants: `ERROR_INVITE_REQUIRED = \"error.invite.required\"`, `ERROR_BAN_FIXED = \"error.ban.fixed\"`, `REPLY_WELCOME_DM_FRESH = \"reply.welcome.dm_fresh\"`, `REPLY_WELCOME_GROUP_FIRST_MENTION = \"reply.welcome.group_first_mention\"`. Verify: `grep -E 'ERROR_INVITE_REQUIRED\\s*=\\s*\"error\\.invite\\.required\"' BundleKeys.java` returns 1 match AND `grep -E 'ERROR_BAN_FIXED\\s*=\\s*\"error\\.ban\\.fixed\"' BundleKeys.java` returns 1 match AND `grep -E 'REPLY_WELCOME_DM_FRESH\\s*=\\s*\"reply\\.welcome\\.dm_fresh\"' BundleKeys.java` returns 1 match AND `grep -E 'REPLY_WELCOME_GROUP_FIRST_MENTION\\s*=\\s*\"reply\\.welcome\\.group_first_mention\"' BundleKeys.java` returns 1 match. The BundleLoaderTest's existing reflective bundle-completeness assertion (per M1-035c) automatically extends to the new keys — no test edit required for completeness coverage"
  - "bundles/en.properties adds the four bundle entries with text drawn from docs/design/03-commands.md §3.11 Welcome messages (for the two welcome keys) AND from docs/spec/security.md §Invite-code registration / §User ban (for the two fixed-error keys — the spec's quoted literal `Access requires an invitation.` and `Your access has been revoked.` resp.). Verify: `grep -E '^error\\.invite\\.required\\s*=' en.properties` returns 1 match AND the value contains the literal substring `Access requires an invitation` (the trailing period optional) AND `grep -E '^error\\.ban\\.fixed\\s*=' en.properties` returns 1 match AND the value equals or starts with `Your access has been revoked` AND `grep -E '^reply\\.welcome\\.dm_fresh\\s*=' en.properties` returns 1 match AND `grep -E '^reply\\.welcome\\.group_first_mention\\s*=' en.properties` returns 1 match"
  - "application.properties adds the per-profile rate-cap + invite TTL config keys. The base values mirror the spec's profile table (docs/spec/security.md §Rate limiting — `Per-user chat-mode messages (transport rate) 60/min token bucket`; design/04-security.md §4.9 Per-user chat-mode messages and §4.5 brute-force threshold/window; design/03-commands.md §3.10 TTL table). At minimum the file declares: `infochat.rate-cap.inbound-per-minute=60`, `infochat.invite.brute-force-threshold=10`, `infochat.invite.brute-force-window=1h`, `infochat.invite.ttl=7d`, `infochat.invite.open-cap-per-adapter=3`, `infochat.invite.contact-cap-global=50`, `infochat.probation.duration=24h`. Per-profile overrides (under `%vps`, `%pi`, `%remote-llm`) MAY be added but the laptop defaults above are mandatory. Verify: `grep -E '^infochat\\.rate-cap\\.inbound-per-minute=' application.properties` returns ≥1 match AND `grep -E '^infochat\\.invite\\.brute-force-threshold=' application.properties` returns ≥1 match AND `grep -E '^infochat\\.invite\\.ttl=' application.properties` returns ≥1 match AND `grep -E '^infochat\\.probation\\.duration=' application.properties` returns ≥1 match"
  - "InboundRouterIntakeOrderingTest pins the step ordering via a unit test that constructs InboundRouter with mock collaborators (the four services from M1-044a) and a fake CommandHandler, driving onMessage with synthetic InboundMessages, and asserting (via mock interactions in order): (a) for a DM with a body that exceeds the size cap → only the size-cap branch fires, no other collaborator; (b) for a DM that is over rate cap → rateCapBucket consulted, nothing else, no outbound; (c) for a DM that is under rate cap with an empty body after normalize → no further collaborators; (d) for a DM from an unknown contact_id with a valid invite body → rateCapBucket → normalize → inviteCodeConsumer (returns Accepted) → outbound is the welcome, banCheck NOT consulted, handleSlash NOT called; (e) for a DM from an unknown contact with an invalid body → rateCapBucket → normalize → inviteCodeConsumer (returns Rejected) → outbound is error.invite.required, banCheck NOT consulted; (f) for a DM from a known is_banned=true contact → rateCapBucket → normalize → users lookup → banCheck (returns true) → outbound is error.ban.fixed, no handleSlash; (g) for a DM from a known `group_only` contact with `/help` body → rateCapBucket → normalize → banCheck (returns false) → handleSlash → DM-gate post-check fires → outbound is error.invite.required (NOT the /help reply); (h) for a Group `@mention` from unknown contact → rateCapBucket → normalize → autoRegisterService.resolveOrRegisterGroup → banCheck → handleSlash → outbound is the dispatch reply. `grep -E '@Test' InboundRouterIntakeOrderingTest.java` returns ≥6 matches (matching the 6 distinct DM scenarios above; the group scenario MAY be a separate test or folded into the same class)"
  - "InboundRouterTest is updated to extend (NOT replace) M1-035b's existing dispatch + chat-mode + unknown-command tests with one new test that asserts the rate-cap branch silently drops with no outbound (the cap-overflow case). M1-035b's existing test methods continue to pass unchanged. `grep -E '@Test' InboundRouterTest.java` returns ≥(N+1) matches where N is the count before this ticket"
  - "InboundRouterNormalizeTest (M1-035b) and InboundRouterContactIdRedactionTest (M1-038) continue to pass without ANY modification — the size cap, normalize pass, and ContactIds.redact log-redaction behavior are preserved unchanged. These two files appear in files_scope only so the reviewer can verify they were NOT inadvertently modified (negative-space check). Verify: `git diff main -- infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java` returns ZERO changes AND the same for InboundRouterContactIdRedactionTest.java"
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
  preserves:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java (M1-035b — verbatim)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java (M1-038 — verbatim)
    - all tests currently green on main
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
  `InboundRouterContactIdRedactionTest` (M1-038's) are NOT
  modified — they appear in `files_scope` only for the
  reviewer's negative-space check.
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
  design/04-security.md and design/03-commands.md as needed).
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
    per acceptance item 16). The M1-044a `RateCapBucketTest`'s four
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
- **Modifying any pre-existing test outside the four files in
  files_scope.** M1-035c/M1-036/M1-037/M1-038/M1-039/M1-040
  tests stay green unchanged — InboundRouter's intake splice
  changes the dispatch path's behavior for unknown DM
  contacts, but those existing tests seed registered users
  (or stub the relevant collaborators) so the new gates
  pass through them.

## Authorized test changes

- `InboundRouterTest.java` — M1-035b's class is **extended**
  (one new test method) but no prior method is modified.
- `RateCapBucketTest.java` — M1-044a's class is **extended**
  (one new `evictionDrainedIdle` test method per acceptance
  item 16) but no prior method is modified. The four M1-044a
  methods (underCap, overCap, independent, refill) MUST stay
  green under the widened eviction predicate; the new method
  exercises only the previously-untested drained-and-idle case.
- (no other pre-existing test is modified by this ticket.)
- The `InboundRouterNormalizeTest.java` (M1-035b) and
  `InboundRouterContactIdRedactionTest.java` (M1-038) appear
  in `files_scope` only so the reviewer can verify they are
  NOT modified. The reviewer's NEGATIVE-SPACE-CHECK reports
  the un-touched files as expected (deliberate skip).

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
