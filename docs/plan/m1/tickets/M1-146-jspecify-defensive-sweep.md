---
id: M1-146
title: "Defensive-code sweep: remove dead internal null-guards (CT4)"
status: done
created: 2026-06-02
last_updated: 2026-06-05
blocked_by:
  - M1-133
files_budget: 12
files_scope:
  - infochat-llm-adapter
  - infochat-ssrf
  - infochat-collector
  - infochat-provider
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - boundary validation (system-boundary null-checks stay per §No-defensive-code — only internal-trust-boundary guards are removed)
  - "JSpecify annotation pass, lint-contracts CI, and per-method @NonNull/@Nullable work — superseded by the NullAway/Error Prone adoption (D48); this ticket retains ONLY the §7 dead-guard removal half of the original CT4 bundle"
  - InboundContext / MessagingException annotation work (subsumed by the non-null-default model under D48)
  - any behavioral change beyond dead-guard removal and the AssetSnapshotFetcher catch relocation
acceptance:
  - "Dead defensive null-checks and catch arms between internal classes are removed: LlmRouter ctor/record, SsrfGuardedHttpClient resolver-seam, OpenAiCompatibleProvider apiKey coalesce, AssetSnapshotFetcher catch moved to the outer runHostTick loop with a distinct error class (so it does not feed the D42 ladder as an upstream-fetch failure), the dead UserSnapshot.isBanned record component in InboundRouter (and its ProbationCheck call sites), and the BootstrapAssetsLoader unreachable guard"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  modifies:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java — drop the isBanned argument from new UserSnapshot(...) constructions (and the UserSnapshotSeed helper's banned component if it becomes dead); assertions unchanged"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterNormalizeTest.java — drop the isBanned argument from new UserSnapshot(...); assertions unchanged"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java — drop the isBanned argument from new UserSnapshot(...); assertions unchanged"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java — drop the isBanned argument from new UserSnapshot(...); assertions unchanged"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java — drop the isBanned argument from new InboundRouter.UserSnapshot(...) (qualified form, missed by the unqualified-pattern survey); assertions unchanged"
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Architectural principles
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
      files: 13
      added: 87
      removed: 65
escalations:
  - date: 2026-06-05
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (budget-breach, pre-implementation). Removing the dead
      UserSnapshot.isBanned record component forces edits to 4 test files that
      construct the 3-arg record (InboundRouterProbationOrderingTest,
      InboundRouterNormalizeTest, InboundRouterConfirmCancelTest,
      InboundRouterContactIdRedactionTest) on top of the 6 named production
      files (LlmRouter, OpenAiCompatibleProvider, SsrfGuardedHttpClient,
      AssetSnapshotFetcher, BootstrapAssetsLoader, InboundRouter) — minimum 10
      files vs files_budget: 9, and test_plan has no modifies list authorizing
      the test edits (flagged by the 2026-06-05 clarity WARN).
  - date: 2026-06-02
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A (premise-fail). Running scripts/lint-contracts.py over src/main/java
      across all modules yielded 164 findings in 46 files (record components,
      genuinely-unannotated public methods, and regex false positives on
      multi-line `new X(...)`), not the ~8 named classes the ticket and clarity
      pre-flight assumed. The original acceptance item 1 ("lint runs clean
      across all modules ... wired into CI") could not be satisfied with item 4
      (mvn verify exits 0) inside files_budget: 16. There is also no CI system
      in the repo. Resolution: the annotation/lint/CI half is superseded by the
      NullAway/Error Prone adoption (D48); this ticket is refined down to the
      §7 dead-guard sweep, which is orthogonal to the enforcement mechanism.
revisions:
  - date: 2026-06-05
    reason: refine after budget-breach
    summary: |
      Pre-implementation file accounting showed minimum 10 files: the 6 named
      production files plus 4 test files that construct the 3-arg
      InboundRouter.UserSnapshot record and break compilation when the dead
      isBanned component is removed. files_budget 9 -> 12 (10 minimum +
      headroom for ProbationCheck.java and one incidental). Added a
      test_plan.modifies list authorizing constructor-argument-only edits to
      the 4 test files (assertions unchanged), resolving the 2026-06-05
      clarity TEST-CHANGES-AUTHORIZED warning. Acceptance unchanged.
      Same-day addendum: round-1 mvn verify surfaced a 5th forced test file
      (InboundRouterIntakeOrderingTest) using the qualified
      `new InboundRouter.UserSnapshot(...)` form the unqualified grep survey
      missed; added to modifies with the same constructor-argument-only
      nature. 11 files total, still within files_budget: 12.
  - date: 2026-06-03
    reason: refine after premise-fail
    summary: |
      Scope cut from the full §7+§7a CT4 bundle to the §7 dead-guard sweep
      only. Removed acceptance items 1 (lint runs clean / wired into CI) and 3
      (InboundContext/MessagingException annotations) — both absorbed by the
      NullAway adoption under D48 (non-null-by-default makes a hand-annotation
      pass unnecessary, and NullAway in the build replaces the regex lint as
      the §7a gate). files_budget 16 -> 9; files_scope narrowed to the four
      modules holding the named guard sites; risk low -> medium (the
      AssetSnapshotFetcher catch relocation is behavioral). title updated.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-05
  verdict: WARN
  warnings:
    - "TEST-CHANGES-AUTHORIZED: The ticket removes a record component (UserSnapshot.isBanned) and references \"ProbationCheck call sites\" without a modifies list or explicit statement that no test files reference these sites. If test fixtures construct UserSnapshot or call ProbationCheck with isBanned, those files need authorized modification. Implementer should verify before starting and add a modifies entry if any test files are affected."
  blockers: []
---

# M1-146: Defensive-code sweep — remove dead internal null-guards (CT4)

## Context

Several modules carry defensive null-checks / `catch (RuntimeException)` arms
guarding scenarios that cannot happen given the internal trust boundary, in
violation of the §"No defensive code for impossible scenarios" engineering
rule. The reviewer applies §7 narrowly — boundary validation stays; internal
guards go.

This ticket originally bundled the §7 dead-guard sweep with a §7a JSpecify
annotation pass + lint-contracts CI wiring. That half was found to be
mis-premised (a full-codebase lint surfaced 164 findings across 46 files, not
the ~8 named classes, and the repo has no CI to wire into) and is superseded by
the NullAway/Error Prone adoption recorded in decision D48 — under a
non-null-by-default model the hand-annotation pass is unnecessary and NullAway
in the build replaces the regex lint as the §7a gate. This ticket retains only
the §7 dead-guard removal, which is independent of the enforcement-mechanism
choice.

## Acceptance

See frontmatter. Remove the named dead internal guards; relocate the
`AssetSnapshotFetcher` catch to the outer loop with a distinct error class; keep
the full suite green.

## Out-of-scope

See frontmatter. System-boundary null-checks are NOT removed (adapter inbound,
HTTP endpoints, config parsing, SQL deserialization, LLM tool-call args, file
I/O). The §7a annotation / lint / CI work is out — it lives in the NullAway
adoption (D48), not here.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-DEFENSIVE-CODE;
  `opus-47-full-handout.md` §F-MAINT-44/45/62/72/76/77/80, CT4;
  `opus-47-only-handout.md` §M12/14/16, CT2.
- `AssetSnapshotFetcher` catch moves to the outer `runHostTick` loop with a
  distinct error class so it doesn't feed the D42 ladder as an upstream-fetch
  failure.
- `UserSnapshot` is a nested record in
  `infochat-provider/.../messaging/InboundRouter.java`; removing the dead
  `isBanned` component also touches its `new UserSnapshot(...)` call sites
  (incl. `ProbationCheck`).
- This ticket's `files_scope` (infochat-llm-adapter, infochat-ssrf,
  infochat-collector, infochat-provider) overlaps the per-module NullAway
  onboarding subtickets, so it must NOT be started in parallel with those.
  Sequencing (before or after a given module's onboarding) is the operator's
  call; NullAway onboarding of those modules will later re-validate that the
  removed guards were genuinely dead.
