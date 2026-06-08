---
id: M1-239
title: "infochat-provider: NOTIFY discriminator, stale ban comment, locale fold"
status: done
created: 2026-06-08
last_updated: 2026-06-09
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/QuarantineReviewListener.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GroupTimezoneCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/QuarantineReviewListenerDiscriminatorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GroupTimezoneLocaleFoldTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The QuarantineReviewListener LISTEN loop, cursor advance, and row-truth handling — unchanged; only payload discriminator validation is added.
  - The InboundRouter step-4 ban gate — it is authoritative and unchanged; this ticket reconciles AddSourceCommandHandler's stale comment about it.
  - The authoritative timezone validation (ZoneId.of) — unchanged; only the fuzzy-suggestion case folding is corrected.
  - Other command handlers' ban/probation re-checks (e.g. GrantAdminCommandHandler's in-tx defense-in-depth) — those carry their own valid rationale; unchanged.
acceptance:
  - "A-F2: QuarantineReviewListener.parsePayload rejects a target_kind that is not in {\"quarantine\",\"post\"} at the NOTIFY wire boundary (throws, so dispatch logs the raw payload and drops it), instead of silently routing any non-\"quarantine\" value to the post base-table lookup; a named test asserts an out-of-set discriminator is dropped-with-log, not mis-routed."
  - "P-F3: the /add-source in-handler ban check is reconciled with reality — either remove it (InboundRouter step 4 BanCheck.isBanned is authoritative and blocks banned senders before dispatch, so the in-handler check is dead §7 code) OR keep it with a corrected comment stating the real defense-in-depth reason; the stale 'the upstream T2-A ban gate is not yet wired' comment is gone."
  - "P-F4: GroupTimezoneCommandHandler.fuzzySuggestions folds case with Locale.ROOT (all toLowerCase() calls), per commands.md §Surface conventions, so suggestions do not break on a Turkish-locale JVM; a named test asserts a fuzzy suggestion resolves locale-independently."
  - "Existing QuarantineReviewListener / AddSourceCommandHandler / GroupTimezoneCommandHandler tests stay green; mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/QuarantineReviewListenerDiscriminatorTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GroupTimezoneLocaleFoldTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/commands.md §Surface conventions
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 151
      removed: 20
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-239: infochat-provider — NOTIFY discriminator, stale ban comment, locale fold

## Context

Three low-severity findings in `infochat-provider`, grouped (same module,
all small):

- `deep-code-review/v2.5/opus-48/01-architecture.md#F2` (DRIFT):
  `QuarantineReviewListener.parsePayload` never validates `target_kind ∈
  {quarantine, post}`, and `lookupRowState` routes any non-`"quarantine"`
  value to the `post` table — a silent mis-route at a boundary the spec
  names as requiring defensive parsing. Not currently exploitable (closed
  producer) → low.
- `deep-code-review/v2.5/opus-48/07-module-infochat-provider.md#F3` (DRIFT):
  `AddSourceCommandHandler`'s in-handler ban check is justified by a comment
  claiming "the upstream T2-A ban gate is not yet wired" — false;
  `InboundRouter` step 4 (`BanCheck.isBanned`) blocks banned senders before
  any handler runs.
- `#F4` (SECURITY, low): `GroupTimezoneCommandHandler.fuzzySuggestions` uses
  bare `toLowerCase()` (default locale), diverging from `commands.md`
  §Surface conventions' `Locale.ROOT` folding rule that the rest of the
  module honors; UX degradation on a Turkish-locale JVM, not an authz break.

## Acceptance

See frontmatter. In prose: validate the NOTIFY discriminator at the wire
boundary (drop-with-log on an out-of-set value); reconcile the stale
`/add-source` ban comment (remove the dead check or correct its rationale);
switch the timezone fuzzy-suggestion folding to `Locale.ROOT`; named tests
pin the discriminator drop and the locale-independent suggestion; `mvn
verify` is 0.

## Out-of-scope

See frontmatter. The LISTEN loop, the authoritative ban gate and timezone
validation, and other handlers' re-checks are untouched.

## Notes

- A-F2's one-comparison fix at `parsePayload` is permitted under §7 because
  NOTIFY deserialization is an enumerated system boundary; `dispatch`
  already logs+drops on a `parsePayload` throw.
- P-F3: prefer the §7-aligned removal unless defense-in-depth here is
  deliberate; if kept, the corrected comment must state the real reason (a
  ban landing between intake and this handler in the same dispatch).
- Exact recommended edits are in the source findings.
