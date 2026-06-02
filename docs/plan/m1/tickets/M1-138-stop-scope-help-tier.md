---
id: M1-138
title: "/stop group/DM scope fix + /help per-tier filtering"
status: done
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 11
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/StopCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - InboundRouter.java — resolve the group scope via GroupRepository / a small handler-local helper, not by editing InboundRouter (keeps this off the PROV-ROUTER contention lane)
  - the bundle-completeness CI rule itself (only the per-command help keys it checks)
acceptance:
  - "/stop cancels in-flight chat work in group scope (resolveUserId no longer returns empty for non-DM), using a scopeKind/scopeId resolution mirroring InboundRouter.resolveChatScopeId (DM→userId, group→groupId)"
  - "A test asserts a group /stop cancels the per-(user, scope) chat work for that group"
  - "/help filters commands by caller tier (probation, non-admin, non-group-admin) and scope (DM vs group header, probation footer), driven from a closed (command, bundleKey, tier) catalogue rather than a hardcoded three-line list; per-command help keys land in the en/cs bundles"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Discovery
  - docs/spec/commands.md §Conversation control
  - docs/spec/commands.md §Permission model
decision_refs:
  - D35
reviews:
  - round: 1
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 692
      removed: 98
overrides: []
escalations:
  - date: 2026-06-02
    reason: budget-breach
    reviewer_verdict_excerpt: |
      Surfaced during round-1 implementation (mvn verify, 2 failures). The
      /help scope-aware-header change (acceptance item 3, "scope: DM vs
      group header") has a blast radius into ITs that assert the OLD /help
      body: LangCommandIT (command test dir, in scope) and
      GroupAuthorizationRoundtripIT (group test dir, NOT in files_scope —
      asserts a group /help startsWith(HELP_HEADER_DM_USER); the spec-
      mandated group header now makes it HELP_HEADER_GROUP). Both are pure
      expected-output updates forced by the acceptance, not bugs. The diff
      is also at 10 files vs files_budget 9. Refine widens files_scope to
      add the group test dir and bumps files_budget 9 -> 11.
  - date: 2026-06-02
    reason: budget-breach
    reviewer_verdict_excerpt: |
      Surfaced at /m1-tick start (post-clarity, before code). Acceptance
      item 3 requires "/help ... driven from a closed (command, bundleKey,
      tier) catalogue ... per-command help keys land in the en/cs bundles",
      and out_of_scope reserves "the per-command help keys it checks" as
      in-scope. The bundle-completeness CI rule (BundleLoaderTest) checks
      help keys by REFLECTING over BundleKeys constants, and the codebase
      convention (BundleKeys javadoc) is that every user-visible bundle key
      is a BundleKeys constant. A full per-tier catalogue needs a help-line
      key per command (~40) plus DM/group header + probation footer keys —
      all of which must be BundleKeys constants to be CI-checked. But
      BundleKeys.java lives in the bundle package, NOT in files_scope (which
      lists only command/StopCommandHandler, messaging/HelpCommandHandler,
      resources, and the two test dirs). Implementing item 3 faithfully
      requires touching BundleKeys.java — a path outside files_scope.
  - date: 2026-06-02
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      Surfaced at /m1-tick start step-0 grounding (before the clarity
      subagent ran). files_scope cited
      .../command/HelpCommandHandler.java, which does not exist; the real
      handler is .../messaging/HelpCommandHandler.java (package
      provider.messaging, alongside InboundRouter / CommandHandler). The
      handler's only test, HelpCommandHandlerTest.java, also lives in the
      messaging test package, not the command test package the original
      files_scope listed — so acceptance item 3 (which changes /help
      output) could not touch its test without scope drift. A
      files_scope anchor that does not resolve on disk is exactly the
      clarity-class blocker the pre-flight validates; caught one step
      early at grounding.
revisions:
  - date: 2026-06-02
    reason: |
      Widen files_scope to add the group test dir + bump files_budget
      9 -> 11 (second budget-breach refine). The /help scope-aware-header
      change reaches GroupAuthorizationRoundtripIT (group test dir, out of
      the prior scope) and LangCommandIT, both asserting the old /help body;
      the diff is 10 files (3 bundle/keys + 2 for /stop + 2 for /help + 3
      forced IT expected-output updates). Snapshot below is the pre-refine
      sizing.
    snapshot:
      files_budget: 9
      files_scope:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/StopCommandHandler.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
        - infochat-provider/src/main/resources
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - date: 2026-06-02
    reason: |
      Widen files_scope to include BundleKeys.java + bump files_budget
      (budget-breach refine). Acceptance item 3's per-command help keys +
      header/footer keys must be BundleKeys constants (the convention, and
      what the reflection-based bundle-completeness CI checks), but
      bundle/BundleKeys.java was outside the prior files_scope. Snapshot
      below is the pre-refine sizing.
    snapshot:
      files_budget: 7
      files_scope:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/StopCommandHandler.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
        - infochat-provider/src/main/resources
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - date: 2026-06-02
    reason: |
      Correct two stale files_scope paths (clarity-fail refine).
      HelpCommandHandler.java moved from the command package path to its
      real messaging package path; added the messaging test directory so
      HelpCommandHandlerTest (which acceptance item 3 must update) is in
      scope.
    snapshot:
      files_scope:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/StopCommandHandler.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/command/HelpCommandHandler.java
        - infochat-provider/src/main/resources
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-02
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-138: /stop group/DM scope fix + /help per-tier filtering

## Context

Two provider command-surface gaps:

- **A17** — `StopCommandHandler.resolveUserId` returns empty for any non-DM
  scope, so a group user's in-flight chat work can't be cancelled; the DM path
  hardcodes `scopeKind="dm"`. Spec §Chat mode (D35) commits to per-(user, scope)
  cancellation in groups.
- **A18** — `/help` hardcodes three commands + assets; no per-tier filtering, no
  group header, no probation footer. `commands.md` §Discovery requires per-tier
  filtering and bundle-composition from per-command keys.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. Resolve group scope through `GroupRepository` or a
handler-local helper — **do not edit `InboundRouter.java`**, so this ticket
stays off the serialized PROV-ROUTER lane and runs parallel to M1-125.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A17, §A18; `opus-47-full-handout.md`
  §F-MAINT-19/20; `opus-47-only-handout.md` §S1, M6.
- Loci: `StopCommandHandler.java:62-115`, `HelpCommandHandler.java:46-74`.
