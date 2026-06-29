---
id: M1-520
title: "Remove test-only no-arg AssetCommandFamilyOracle ctor"
status: done
created: 2026-06-29
last_updated: 2026-06-29
blocked_by: []
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AssetCommandFamilyOracle.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetCommandFamilyOracleTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/CommandPermissionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopCommandPermissions.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
decomposed_from: M1-494
out_of_scope:
  - "Any behavioral change to the production isAssetCommand verdict — the CDI (@Inject) path already takes a real AssetRegistry; only the no-arg/null path (which only tests reach) is removed."
  - "infochat-collector/**, infochat-messaging-adapter/**, infochat-llm-adapter/** — this is a provider-only change."
acceptance:
  - >-
    The no-arg AssetCommandFamilyOracle() constructor
    (AssetCommandFamilyOracle.java:25-29) is removed, the assetRegistry field
    becomes non-null (final, set only by the @Inject constructor), and the
    now-dead `assetRegistry != null` guard in isAssetCommand is dropped — the
    constructor and the guard are coupled (the guard is live only because the
    no-arg ctor sets the field null), so both move together.
  - >-
    Every test that constructed the disabled oracle via
    `new AssetCommandFamilyOracle()` is updated to construct with an empty
    registry (`new AssetCommandFamilyOracle(new AssetRegistry(Map.of()))`),
    which yields the identical "false for all inputs" verdict. The known call
    sites: AssetCommandFamilyOracleTest, CommandPermissionsTest,
    NoopCommandPermissions, HelpCommandHandlerTest, and the InboundRouter
    tests (ProbationClock, ProbationOrdering, IntakeOrdering, ContactIdRedaction,
    ConfirmCancel). AssetCommandFamilyOracleTest.noArgConstructorReturnsFalseForAll
    is reframed to assert the empty-registry oracle returns false for all.
  - "mvn -B verify is green from the repo root."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetCommandFamilyOracleTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/CommandPermissionsTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/NoopCommandPermissions.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationClockTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterContactIdRedactionTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-29
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 33
      removed: 37
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-29
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-520: Remove test-only no-arg AssetCommandFamilyOracle ctor

## Context

Split out of the M1-494 dead-code/defensive-check sweep during a budget-breach
refine. Deep-review finding 10#F2 flagged the no-arg `AssetCommandFamilyOracle()`
constructor as production code existing only to serve tests: the `@Inject`
constructor takes a real `AssetRegistry`, CDI never uses the no-arg form
(verified: a sibling `@ApplicationScoped` bean, `ChatAgent`, has only a
parameterized `@Inject` ctor and the app builds, so ArC does not require a
no-arg ctor), and every `new AssetCommandFamilyOracle()` call site lives in
`src/test`. Unlike the other twelve sweep findings, this one is not a contained
micro-fix: there is no Java visibility level that is "test-only but
cross-package", so the resolution is removal — which forces ~9 cross-package
test call sites to switch to an empty-registry construction. That ripple
exceeded M1-494's `files_budget` and broke its "each item is independently
small" framing, so it became its own ticket.

## Acceptance

See frontmatter. Remove the no-arg ctor and its coupled `assetRegistry != null`
guard, make the field non-null, and migrate the test call sites to an
empty-registry construction that preserves the "false for all" verdict. Full
suite green.

## Out-of-scope

See frontmatter. No change to the production verdict for the @Inject path; this
is provider-only.

## Notes

- Source: `/deep-code-review full` (2026-06-27) finding 10#F2; split from M1-494.
- The constructor and the null-guard are a single coupled unit — the guard is
  reachable only because the no-arg ctor sets `assetRegistry = null`. Removing
  one without the other would leave incoherent code (either a dead guard or an
  NPE path), which is why they belong in one ticket rather than the M1-494 sweep.
- `new AssetRegistry(Map.of())` is the empty-registry stand-in;
  `containsEnabledAsset` over an empty map returns false, matching the old
  no-arg behavior exactly.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-520-*.md
```
