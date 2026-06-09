---
id: M1-244
title: "InboundRouter: fold is_banned into snapshot + command body cap"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 14
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
  - "T4: USER_SNAPSHOT_SQL selects is_banned alongside id, registration_state, and UserSnapshot carries an isBanned field; the intake step-4 ban check reads the snapshot value instead of issuing a second SELECT is_banned FROM users per inbound. InboundRouterBanSnapshotTest asserts a banned user's inbound is rejected at step 4 with the fixed reply and no LLM/parse/further DB query, using only the single snapshot read. The class javadoc (and UserSnapshot doc) drops the \"separate live is_banned query\" rationale."
  - "T4-tests: because the intake call ordering no longer includes banCheck.isBanned (the ban is served from the snapshot), the existing ordering tests that pin that call are updated to the snapshot-served path: InboundRouterIntakeOrderingTest and InboundRouterProbationOrderingTest drop banCheck.isBanned from their step-4 call-log expectations and seed banned state via UserSnapshot.isBanned instead of FakeBanCheck.banned; InboundRouterConfirmCancelTest, InboundRouterNormalizeTest, RouterNoDoubleSendTest, InboundRouterContactIdRedactionTest take the compile-only UserSnapshot constructor-arg update. No other behavior in those tests changes."
  - "T6: a profile-driven char cap infochat.command.body-cap (per-profile defaults laptop 8192 / vps 4096 / pi 2048 / remote-llm 16384, per docs/design/03-commands.md) is applied to slash-command bodies after normalization and before handleSlash; an over-cap slash body is rejected with a friendly error bundle key and does not reach the parser. InboundRouterCommandCapTest asserts an over-cap slash body is rejected with the error reply and an under-cap slash body is parsed normally. The bundle key is added to BundleKeys plus the en and cs bundles (parity); the per-profile values (and a %test value for a deterministic test cap) are added to application.properties alongside infochat.chat.body-cap."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterBanSnapshotTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterCommandCapTest.java
  modifies:
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
      why: "Pins the intake call ordering with banCheck.isBanned at step 4 (call-log assertions ~314/369/413) and seeds banned state via FakeBanCheck.banned=true (~298/396/464); constructs UserSnapshot at 7 sites. T4 moves the ban read to the snapshot: drop banCheck.isBanned from the step-4 intake call-log expectations, seed banned state through the UserSnapshot(id, registrationState, isBanned) constructor, and update the ordering-doc javadoc to the snapshot-served read. Authorized old-behavior rewrite."
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
      why: "Call-log assertions list banCheck.isBanned at the not-banned step 4 (~90/123/154/246/280); one UserSnapshot construction site (~302) plus FakeBanCheck(log, banned). T4 removes banCheck.isBanned from the intake call log (ban served from UserSnapshot.isBanned=false) and the UserSnapshot site gains the isBanned arg. Authorized old-behavior rewrite."
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
      why: "Constructs UserSnapshot(id, registrationState) at ~144 with NoopBanCheck (no banCheck.isBanned call-log assertion). Compile-only update to add the isBanned constructor arg."
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java
      why: "Constructs UserSnapshot at ~331 with NoopBanCheck. Compile-only isBanned constructor-arg update."
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RouterNoDoubleSendTest.java
      why: "Constructs UserSnapshot at ~80 with NoopBanCheck. Compile-only isBanned constructor-arg update."
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
      why: "Constructs UserSnapshot at ~154 with NoopBanCheck. Compile-only isBanned constructor-arg update."
  preserves:
    - all tests currently green on main EXCEPT the six enumerated under modifies
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
clarity_check: {}
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
authorized (see `test_plan.modifies` for the per-file rationale). This is an
old-behavior rewrite, not a test weakening — the tests still pin the same step-4
rejection semantics, only the *source* of `is_banned` moves:

- **Behavioral** (call-log + construction): `InboundRouterIntakeOrderingTest`,
  `InboundRouterProbationOrderingTest` — drop `banCheck.isBanned` from the
  **intake** step-4 call-log expectations (the ban is now read from the
  snapshot), and seed banned state via `UserSnapshot.isBanned` rather than
  `FakeBanCheck.banned`. The admin confirm-leg `BanCheck` paths are untouched.
- **Compile-only** (constructor arg): `InboundRouterConfirmCancelTest`,
  `InboundRouterNormalizeTest`, `RouterNoDoubleSendTest`,
  `InboundRouterContactIdRedactionTest` — each adds the new `isBanned` argument
  to its single `UserSnapshot(...)` construction; no assertion changes.

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
