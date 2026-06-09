---
id: M1-244
title: "InboundRouter: fold is_banned into snapshot + command body cap"
status: in-progress
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 16
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterBanSnapshotTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterCommandCapTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RouterNoDoubleSendTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/FakeBanCheck.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopBanCheck.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The admin confirm-leg ban paths that do their own FOR UPDATE reads — they keep using BanCheck; only the intake step-4 read is served from the snapshot.
  - The step-4 execution ordering relative to step 3/3.5 (security.md §Authorization model) — unchanged; only the SOURCE of the is_banned value moves from a second SELECT to the step-1 snapshot.
  - The generic 64 KiB byte cap and the existing chat-mode cap (chatBodyCap) — unchanged; this adds the slash-command cap that is currently missing.
  - BanCommandHandler and RateCapBucket — owned by M1-249.
acceptance:
  - "T4: USER_SNAPSHOT_SQL selects is_banned alongside id, registration_state, and UserSnapshot carries an isBanned field; the intake step-4 ban check reads the snapshot value instead of issuing a second SELECT is_banned FROM users per inbound. Because step 4 was the SOLE InboundRouter use of the injected BanCheck, the now-unused @Inject BanCheck field (and its import) is removed from InboundRouter — BanCheck the class is retained for the M1-249 confirm-leg FOR UPDATE paths. A code comment at the step-4 site records that the snapshot-served ban is spec-legal (the step-1→step-4 TOCTOU is accepted; the ban takes effect on the next inbound). InboundRouterBanSnapshotTest asserts a banned user's inbound is rejected at step 4 with the fixed reply and no LLM/parse/further DB query, using only the single snapshot read. The class javadoc (and UserSnapshot doc) drops the \"separate live is_banned query\" rationale."
  - "T4-tests: because the intake call ordering no longer includes banCheck.isBanned (the ban is served from the snapshot) and the BanCheck field is removed, every `router.banCheck = ...` assignment in the six tests is removed. InboundRouterIntakeOrderingTest and InboundRouterProbationOrderingTest additionally drop banCheck.isBanned from their step-4 call-log expectations and seed banned state via UserSnapshot.isBanned instead of FakeBanCheck.banned; InboundRouterConfirmCancelTest, InboundRouterNormalizeTest, RouterNoDoubleSendTest, InboundRouterContactIdRedactionTest take the UserSnapshot constructor-arg update plus the banCheck-assignment removal. The two test doubles FakeBanCheck.java and NoopBanCheck.java — referenced only by these six tests — become orphaned and are deleted. No other behavior in those tests changes."
  - "T6: a profile-driven char cap infochat.command.body-cap (per-profile defaults laptop 8192 / vps 4096 / pi 2048 / remote-llm 16384, per docs/design/03-commands.md) is applied to slash-command bodies after normalization and before handleSlash; an over-cap slash body is rejected with a friendly error bundle key and does not reach the parser. InboundRouterCommandCapTest asserts an over-cap slash body is rejected with the error reply and an under-cap slash body is parsed normally. The bundle key is added to BundleKeys plus the en and cs bundles (parity); the per-profile values (and a %test value for a deterministic test cap) are added to application.properties alongside infochat.chat.body-cap."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterBanSnapshotTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterCommandCapTest.java
  modifies:
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
      why: "Pins the intake call ordering with banCheck.isBanned at step 4 (call-log assertions ~314/369/413) and seeds banned state via FakeBanCheck.banned=true (~298/396/464); constructs UserSnapshot at 7 sites; sets router.banCheck (~552). T4 moves the ban read to the snapshot: drop banCheck.isBanned from the step-4 intake call-log expectations, seed banned state through the UserSnapshot(id, registrationState, isBanned) constructor, remove the router.banCheck assignment (field gone), and update the ordering-doc javadoc to the snapshot-served read. Authorized old-behavior rewrite."
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
      why: "Call-log assertions list banCheck.isBanned at the not-banned step 4 (~90/123/154/246/280); one UserSnapshot construction site (~302); sets router.banCheck via FakeBanCheck(log, banned) (~311/365). T4 removes banCheck.isBanned from the intake call log (ban served from UserSnapshot.isBanned=false), the UserSnapshot site gains the isBanned arg, and the router.banCheck assignments are removed (field gone). Authorized old-behavior rewrite."
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
      why: "Constructs UserSnapshot(id, registrationState) at ~144 and sets router.banCheck = new NoopBanCheck() (~151); no banCheck.isBanned call-log assertion. Add the isBanned constructor arg and remove the router.banCheck assignment (field gone)."
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
      why: "Constructs UserSnapshot at ~331 and sets router.banCheck = new NoopBanCheck() (~336). Add the isBanned constructor arg and remove the router.banCheck assignment (field gone)."
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RouterNoDoubleSendTest.java
      why: "Constructs UserSnapshot at ~80 and sets router.banCheck = new NoopBanCheck() (~87). Add the isBanned constructor arg and remove the router.banCheck assignment (field gone)."
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
      why: "Constructs UserSnapshot at ~154 and sets router.banCheck = new NoopBanCheck() (~165). Add the isBanned constructor arg and remove the router.banCheck assignment (field gone)."
  deletes:
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/FakeBanCheck.java
      why: "Test double for the InboundRouter BanCheck field, referenced only by InboundRouterIntakeOrderingTest and InboundRouterProbationOrderingTest. T4 removes that field, so the double is orphaned. Deleted to avoid leaving dead test infrastructure (resurrectable from git history if M1-249's confirm-leg tests need it)."
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopBanCheck.java
      why: "Null-object test double for the InboundRouter BanCheck field, referenced only by the four compile-only tests above. Orphaned once the field is removed; deleted."
  preserves:
    - all tests currently green on main EXCEPT the six enumerated under modifies (the two deletes are test doubles, not test classes)
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/commands.md §Surface conventions
decision_refs: []
reviews: {}
escalations:
  - date: 2026-06-09
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED FAIL: T4 (UserSnapshot gains isBanned; intake
      step-4 banCheck.isBanned call removed) breaks pre-existing tests not in
      test_plan.modifies — InboundRouterIntakeOrderingTest,
      InboundRouterProbationOrderingTest, InboundRouterConfirmCancelTest,
      InboundRouterNormalizeTest, RouterNoDoubleSendTest,
      InboundRouterContactIdRedactionTest all construct
      UserSnapshot(id, registrationState) and/or assert banCheck.isBanned in
      step-4 call logs. test_plan.preserves='all tests currently green on main'
      is contradicted as written.
  - date: 2026-06-09
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (developer discovery before any code edit). Line 450 is the SOLE
      InboundRouter use of the @Inject BanCheck banCheck field (line 248).
      T4 serves step-4 from the snapshot, so banCheck.isBanned is no longer
      called and the field becomes dead code that must be removed. That
      removal stops every `router.banCheck = ...` assignment in the 6 in-scope
      tests from compiling and orphans the two test doubles FakeBanCheck.java
      and NoopBanCheck.java (referenced ONLY by those 6 tests). Deleting the
      two orphaned doubles touches 2 paths outside files_scope and pushes
      files-touched to 16 > files_budget 14. No IT references the field
      (ITs exercise ban via the DB is_banned column, which the snapshot read
      preserves).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
revisions:
  - date: 2026-06-09
    reason: clarity-fail rework
    snapshot:
      status: escalated
      files_budget: 8
      files_scope:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/BanCheck.java
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterBanSnapshotTest.java
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterCommandCapTest.java
      test_plan_at_snapshot:
        adds:
          - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterBanSnapshotTest.java
          - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterCommandCapTest.java
        preserves:
          - all tests currently green on main
      escalation_reason: clarity-fail
      clarity_check:
        date: 2026-06-09
        verdict: FAIL
        warnings:
          - "BanCheck.java is in files_scope but acceptance describes no change to BanCheck itself (retained for admin confirm-leg paths); removing it from files_scope would tighten the boundary. Minor."
        blockers:
          - "TEST-CHANGES-AUTHORIZED FAIL: T4 (UserSnapshot gains isBanned; intake step-4 banCheck.isBanned call removed) breaks pre-existing tests not in test_plan.modifies — InboundRouterIntakeOrderingTest, InboundRouterProbationOrderingTest, InboundRouterConfirmCancelTest, InboundRouterNormalizeTest, RouterNoDoubleSendTest, InboundRouterContactIdRedactionTest all construct UserSnapshot(id, registrationState) and/or assert banCheck.isBanned in step-4 call logs. Add these to test_plan.modifies (or an Authorized test changes section) with the new expected behavior, since preserves='all tests currently green on main' is contradicted as written."
      refine_summary: |
        Authorized the six old-behavior/compile-only test changes T4 forces via
        test_plan.modifies; added the T6 surface files clarity did not flag
        (BundleKeys.java, application.properties, en/cs bundles) to files_scope;
        raised files_budget 8 -> 14; dropped BanCheck.java from files_scope
        (WARN: acceptance describes no change to it).
  - date: 2026-06-09
    reason: budget-breach rework
    snapshot:
      status: escalated
      files_budget: 14
      escalation_reason: budget-breach
      clarity_check:
        date: 2026-06-09
        verdict: PASS
        warnings: []
        blockers: []
      refine_summary: |
        Mid-start discovery (no code written): step 4 is the SOLE InboundRouter
        use of the @Inject BanCheck field, so T4 must remove it, which orphans
        the test doubles FakeBanCheck.java + NoopBanCheck.java (used only by the
        six in-scope tests). Added both doubles to files_scope as deletes;
        raised files_budget 14 -> 16; recorded the BanCheck field removal in
        acceptance T4, the router.banCheck-assignment removals in T4-tests, and
        the orphaned-double deletes under test_plan.deletes. Decision: keep T4
        and T6 folded (one coherent intake-path surface, one /redteam pass);
        delete the orphaned doubles rather than leave dead test infrastructure.
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-244: InboundRouter intake-path — is_banned snapshot + command body cap

## Context

Two intake-path findings grouped because both modify `InboundRouter` on the same
inbound hot path (folding them avoids a merge conflict between two separate
tickets on one file). Source: `deep-code-review/v3/` UNIFIED-REPORT.md T4 (mimo
`07#F1`+`07#F4`) and T6 (opus `07#F1`).

- **T4 [high, perf].** `USER_SNAPSHOT_SQL` selects only `id,
  registration_state`; step 4 calls `banCheck.isBanned(...)`, a second `SELECT
  is_banned FROM users WHERE adapter=? AND contact_id=?` on **every** inbound.
  The javadoc honestly documents this as a deliberate "freshest is_banned"
  choice, but `security.md §Authorization model` (the step-4 ordering rule) only
  requires the ban check reads `is_banned=true` at step-4 ordering — it does
  **not** mandate a separate live query. Folding `is_banned` into the snapshot is
  spec-legal: the TOCTOU between a step-1 read and a step-4 read is milliseconds
  and the ban takes effect on the next message regardless.
- **T6 [medium, rules-drift].** `commands.md` commits to **two** caps;
  `design/03-commands.md` assigns per-profile values. Only the chat-mode cap is
  implemented (`chatBodyCap`, non-slash bodies only). There is no
  `infochat.command.body-cap` property and the slash path has no length gate
  before parsing; the only backstop is the generic 64 KiB byte cap.

## Acceptance

See frontmatter. In prose: add `is_banned` to the snapshot and serve the intake
ban check from it (keeping `BanCheck` for the admin confirm-leg `FOR UPDATE`
paths); add the profile-driven slash-command char cap mirroring the existing
chat-cap shape and the design per-profile values. Named tests pin both; `mvn
verify` is 0.

## Out-of-scope

See frontmatter. Step-4 ordering, the 64 KiB byte cap, the chat cap,
`BanCommandHandler`, and `RateCapBucket` are untouched.

## Authorized test changes

Adding `isBanned` to the `UserSnapshot` record and serving the intake ban check
from the snapshot (T4) necessarily breaks pre-existing tests; these changes are
authorized (see `test_plan.modifies`/`deletes` for the per-file rationale). This
is an old-behavior rewrite, not a test weakening — the tests still pin the same
step-4 rejection semantics, only the *source* of `is_banned` moves.

Because step 4 was the **sole** `InboundRouter` use of the injected `BanCheck`,
the fold removes the `@Inject BanCheck banCheck` field entirely. That makes every
`router.banCheck = ...` assignment in the six tests stop compiling, and it
orphans the two test doubles (`FakeBanCheck`, `NoopBanCheck`) that exist only to
feed that field. The doubles are deleted; `BanCheck` the class survives for the
M1-249 confirm-leg `FOR UPDATE` paths.

- **Behavioral** (call-log + construction + assignment): `InboundRouterIntakeOrderingTest`,
  `InboundRouterProbationOrderingTest` — drop `banCheck.isBanned` from the
  **intake** step-4 call-log expectations (the ban is now read from the
  snapshot), seed banned state via `UserSnapshot.isBanned` rather than
  `FakeBanCheck.banned`, and remove the `router.banCheck` assignment.
- **Construction + assignment**: `InboundRouterConfirmCancelTest`,
  `InboundRouterNormalizeTest`, `RouterNoDoubleSendTest`,
  `InboundRouterContactIdRedactionTest` — each adds the new `isBanned` argument
  to its single `UserSnapshot(...)` construction and removes its
  `router.banCheck = new NoopBanCheck()` line; no assertion changes.
- **Deleted doubles**: `FakeBanCheck.java`, `NoopBanCheck.java` — orphaned by the
  field removal; resurrectable from git history if M1-249 needs a `BanCheck`
  double.

## Notes

- T4 was flagged by mimo as a "javadoc lies" contradiction; the report corrected
  that — the javadoc is internally honest. Draft/implement T4 as a pure
  performance optimization authorized by the spec check above, not as a doc fix.
- `security_relevant: true` because the ban check is a security gate (the change
  moves its data source, not its semantics); a `/redteam` pass is appropriate.
- The error-bundle key for the command cap should mirror the chat-cap's
  friendly-rejection shape (look at how `chatBodyCap` rejects via
  `BundleKeys.ERROR_CHAT_BODY_TOO_LARGE` / `error.chat.body_too_large`): a new
  `BundleKeys` constant plus matching `en.properties` and `cs.properties`
  entries (bundle parity is enforced).
- The per-profile cap values live in `application.properties` next to the
  `infochat.chat.body-cap` block (lines ~329-334), which also carries a `%test`
  override — mirror that with a `%test.infochat.command.body-cap` so
  `InboundRouterCommandCapTest` has a deterministic small cap to exercise.
</content>
</invoke>
