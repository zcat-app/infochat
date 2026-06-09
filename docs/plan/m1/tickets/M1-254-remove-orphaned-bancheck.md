---
id: M1-254
title: "Remove orphaned BanCheck (intake step-4 ban folded into snapshot by M1-244)"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: [M1-244]
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/BanCheck.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/BanCheckTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "BanCommandHandler and AddSourceCommandHandler's own is_banned reads — unchanged; they never referenced BanCheck and keep their specialized queries (BanCommandHandler's in-transaction SELECT ... FOR UPDATE lock; AddSourceCommandHandler's id/is_admin/is_banned projection). Only AddSourceCommandHandler's javadoc prose is touched, not its query or logic."
  - "InboundRouter's step-4 snapshot ban check (M1-244) — unchanged; this ticket removes only the now-orphaned standalone BanCheck class, not the intake gate that replaced its sole caller."
  - "Incidental ban-check terminology in test method names and prose comments (InviteIntakeRoundtripIT, AssetCommandsRoundtripIT, AddSourceBanCheckOrderingTest, InboundRouterIntakeOrderingTest, ProbationCheckTest, InboundRouterBanSnapshotTest) — these name the conceptual step-4 ban check, not the BanCheck type; renaming them is churn and out of scope."
acceptance:
  - "T1: BanCheck.java is deleted. After M1-244 folded the intake step-4 ban read into the InboundRouter step-1 UserSnapshot, the class has no remaining production consumer (it was introduced in M1-044a solely for InboundRouter step 4; BanCommandHandler and AddSourceCommandHandler each issue their own is_banned reads and never referenced it). `grep -rn '\\bBanCheck\\b' infochat-provider/src/main --include=*.java` returns no match (the AddSourceCommandHandler javadoc fixed under T3 is the only prior main reference)."
  - "T2: BanCheckTest.java — the @QuarkusTest IT that injects and exercises BanCheck against the DevServices Postgres container — is deleted alongside the class it covers. This is dead-code removal (the unit under test no longer exists), not a test weakened to mask a failure. `grep -rn '\\bBanCheck\\b' infochat-provider/src/test --include=*.java` returns only incidental method-name / prose-comment matches that name the conceptual step, never a BanCheck type reference (import / field / constructor / @Inject), so the test tree still compiles."
  - "T3: AddSourceCommandHandler's defense-in-depth ban-check javadoc no longer names the removed class — the `{@code BanCheck.isBanned}` mention is reworded to refer to the InboundRouter step-4 snapshot ban check. Javadoc prose only; the handler's SELECT, its actor lookup, and its own re-check logic are untouched."
  - "mvn -B clean verify from the repo root exits 0 — a green full suite proves nothing referenced the removed class."
test_plan:
  deletes:
    - file: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/BanCheckTest.java
      why: "Dedicated @QuarkusTest IT for BanCheck (three invariants: banned row → true, unbanned row → false, unknown contact → false). BanCheck is removed as dead code once M1-244 lands, so its IT is removed with it — there is no longer a unit to test. Not a weakening: no assertion is relaxed to dodge a failure; the covered class ceases to exist. Resurrectable from git history if a future ticket reintroduces a standalone ban-read service."
  preserves:
    - all tests currently green on main EXCEPT BanCheckTest (which covers the deleted class)
spec_refs: []
decision_refs: []
reviews: {}
escalations: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
revisions: []
---

# M1-254: Remove orphaned BanCheck

## Context

`BanCheck` (`infochat-provider/.../messaging/BanCheck.java`) is a standalone,
non-locking, `is_banned`-only read introduced in M1-044a for exactly one caller:
`InboundRouter` step 4 (the intake-path ban gate). M1-244 folds `is_banned` into
the consolidated step-1 `UserSnapshot` SELECT and removes the `@Inject BanCheck`
field from `InboundRouter`. That was the class's sole production consumer, so
after M1-244 lands `BanCheck` is dead production code.

The two other `is_banned` reads in the provider were audited and **cannot** adopt
`BanCheck` (so this is obsolescence, not under-use):

- `BanCommandHandler` issues its authoritative ban mutation inside a transaction
  with `SELECT ... FOR UPDATE` — it needs row-level **locking**, which
  `BanCheck`'s non-locking read does not give. It never referenced `BanCheck`.
- `AddSourceCommandHandler` does a defense-in-depth re-check with
  `SELECT id, is_admin, is_banned` — it needs `is_admin` from the same row, which
  `BanCheck` (returning only `is_banned`) cannot supply. It mentions `BanCheck`
  only in a `{@code}` javadoc, never as a type.

This ticket was split out of M1-244 to keep that security-relevant intake-path
change surgical (and its `/redteam` surface focused). M1-244's frontmatter
originally claimed `BanCheck` was "retained for the M1-249 confirm-leg FOR UPDATE
paths"; that was incorrect on two counts — the FOR UPDATE paths live in
`BanCommandHandler` and never used `BanCheck`, and M1-249's own scope excludes
`BanCheck` — so M1-244's prose was corrected to point here instead.

`blocked_by: [M1-244]` because the class is only deletable once M1-244 removes
its last caller; running this before M1-244 lands would delete a class still in
use at `InboundRouter` step 4.

## Acceptance

See frontmatter. In prose: delete the now-orphaned `BanCheck` class and its
dedicated IT, and reword the one `AddSourceCommandHandler` javadoc line that names
the removed class so it points at the `InboundRouter` step-4 snapshot ban check
instead. `mvn verify` is 0.

## Out-of-scope

See frontmatter. The two surviving `is_banned` readers
(`BanCommandHandler`'s FOR UPDATE lock, `AddSourceCommandHandler`'s
`id/is_admin/is_banned` projection), the M1-244 snapshot gate, and incidental
ban-check terminology in test names/comments are all untouched.

## Authorized test changes

`BanCheckTest.java` (`deletes`): the `@QuarkusTest` IT exists solely to exercise
`BanCheck`. Removing the class removes its reason to exist, so the IT is deleted
with it. This is dead-code removal, not a test-integrity violation — no surviving
assertion is relaxed, disabled, or rewritten to dodge a failure; the class under
test simply no longer exists. The intake-path ban behavior remains pinned by
M1-244's `InboundRouterBanSnapshotTest` (snapshot-served step-4 rejection) and by
the ban-ordering ITs, none of which reference the `BanCheck` type. The double can
be resurrected from git history if a future ticket reintroduces a standalone
ban-read service.

## Notes

- `security_relevant: true` is for audit traceability only — `BanCheck` is a
  ban-gate class by name, so a `/redteam` pass should confirm no *live* ban path
  is weakened. There is no behavior change: the class has zero production callers
  once M1-244 lands, and the authoritative intake gate (`InboundRouter` step 4)
  is unaffected.
- The `AddSourceCommandHandler` reference is `{@code BanCheck.isBanned}` (plain
  code font, not a resolvable `{@link}`), so deleting the class does not break the
  javadoc build — but the prose would name a class that no longer exists, hence
  the T3 reword.
- Scope the T1/T2 verification greps as written: `src/main` must have zero
  `BanCheck` matches after T3; `src/test` keeps only incidental method-name and
  prose-comment matches (e.g. `...StopsBeforeBanCheck`, `bannedUserHitsBanCheck...`,
  `AddSourceBanCheckOrderingTest`) that are not `BanCheck` *type* references.
</content>
</invoke>
