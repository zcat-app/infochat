---
id: M1-883
title: "Exclude a top subtree when unfollowing from ALL"
status: done
created: 2026-08-18
last_updated: 2026-08-18
flow: tick
reproduction: >-
  FollowTopDigestIT.unfollowTopFromAllExcludesDescendantLeaves (written,
  verified RED pre-fix: seed kept 2 scope_tag rows instead of 1) — today ALL
  mode with leaf bootstrap tags `ai` and `football`, followed by
  `/unfollow-tag tech`, seeds `ai` and still renders its digest content.
analysis_ref: docs/plan/m1/tick-analysis/tag-top-source-and-unfollow-subtree.md
blocked_by:
  - M1-882
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnfollowTagCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/FollowTopDigestIT.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Changing M1-867 read-time subtree expansion for follows, digests, search, or summaries.
  - Changing /follow-tag, EXPLICIT-mode unfollow, or the --all confirmation path.
  - Altering source bootstrap representation; fixtures use leaves after M1-882.
  - Adding audit logging for tag-preference changes.
acceptance:
  - "FollowTopDigestIT.unfollowTopFromAllExcludesDescendantLeaves (the converted reproduction) passes: ALL mode seeded from leaf bootstrap tags `ai` under `tech` and `football` under another top, then `/unfollow-tag tech`, flips to EXPLICIT, leaves the unrelated leaf followed, removes all `tech` descendants from scope_tag, and renders no AI content — verification: named Testcontainers adapter-to-digest IT (analysis P5/P6; spec: docs/spec/commands.md §Per-scope tag preferences)."
  - "UnfollowTagCommandHandlerTest.unfollowTopRetainsNormalizationAndUnknownError passes: a normalized mixed-case top follows the subtree exclusion path; an unknown/malformed token still uses the existing normalized, non-reflecting error path and makes no preference mutation — verification: named handler test (analysis P7; spec: docs/spec/commands.md §Surface conventions)."
  - "Failure mode: the reproduction fixture contains both an excluded descendant and an unrelated leaf, so mutating the SQL back to `t.id <> ?` leaves AI content visible and fails the end-of-path assertion — verification: `FollowTopDigestIT.unfollowTopFromAllExcludesDescendantLeaves` (analysis P5)."
  - "Existing leaf-unfollow, DM/group authorization, no-audit, and --all confirmation tests pass unchanged — verification: their existing test classes plus mvn verify."
  - "mvn verify from the repository root is green — verification: command exit 0."
test_plan:
  adds:
    - FollowTopDigestIT.unfollowTopFromAllExcludesDescendantLeaves
    - UnfollowTagCommandHandlerTest normalization/error top case
  modifies: []
  preserves:
    - FollowTopDigestIT.unfollowSeedNodesFlowThroughExplicitDigest
    - UnfollowTagCommandHandler authorization, no-audit, and confirm tests
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Per-scope tag preferences
  - docs/spec/commands.md §Surface conventions
  - docs/spec/schema.md §Sources and tags
  - docs/spec/security.md §Authorization model
decision_refs:
  - D59
decomposed_from: M1-869
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-08-18
    verdict: APPROVE-WITH-FIXES
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY FAIL (1 low stale-comment fix), SCOPE PASS"
    diff_stats: "5 files changed, 152 insertions(+), 18 deletions(-)"
    fix_items: 1
    fix_probes: >-
      1. obsolete-comment grep exited 1; every post-gate changed line was a
      comment/javadoc line; ./mvnw -B -pl infochat-provider -am test-compile
      BUILD SUCCESS; fixed tree .scratch/tick-fixes-M1-883.tree =
      54bb42c2ccb5fe5fda4606aead6ae3f5f55af0ce.
    verdict_file: .scratch/tick-review-M1-883-r1.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-08-18
  result: >-
    Pass. tick-lint 0 findings. blocked_by M1-882 done; its diff added no
    tests on this seam (source/bootstrap gate files only). Acceptance items
    implementable: V84 seeds leaf ai under top tech and football under sport;
    FollowTopDigestIT already holds an ALL-mode UNFOLLOW_GROUP subscribed to
    SOURCE2 with bootstrap_tags {ai,football}. Root-cause citation
    UnfollowTagCommandHandler.java:97-110 verified (UNNEST seed, t.id <> ? at
    109). Pitfalls P5-P7 all landed (P1-P4 owned by M1-882). Preserves
    traced: unfollowSeedNodesFlowThroughExplicitDigest (leaf unfollow) and
    handler flat-leaf minus-one tests remain correct under a node-or-
    descendant exclusion predicate; no ambiguity blocking start.
escalation_reason:
---

# M1-883: Exclude a top subtree when unfollowing from ALL

## Context

The existing ALL-to-EXPLICIT transition gives a user who unfollows `tech` the opposite of the requested result: its leaf `ai` remains followed and can render. This ticket makes top unfollow a subtree exclusion while preserving top follows as read-time wildcards. Shared analysis: P5–P7.

## Root cause

The seed unnests leaf-valued `bootstrap_tags` and applies only `t.id <> ?` (`UnfollowTagCommandHandler.java:80-108`). No seed row has the requested top id, so no descendant is removed. M1-867's existing top expansion happens later at read time and cannot repair a wrong explicit seed.

## Pitfalls

- P5: one-id subtraction leaves descendants followed; changing read expansion instead would break top-follow wildcards.
- P6: a top stored in a bootstrap fixture would hide the actual leaf-source defect and conflict with M1-882.
- P7: this inbound command path must retain normalization, authorization, D59 world filtering, version increments, and no audit row.

## Approach

- **Files to touch:** the ALL seed SQL and two named provider tests.
- Change the SQL predicate to omit a candidate bootstrap leaf when it is the selected node or descends from it. Keep source bootstrap leaves, the D59 source-world join, transaction shape, and all non-ALL branches unchanged.
- Add an adapter-to-digest test first; its leaf fixture discriminates the old predicate from subtree exclusion. Add the normalization/unknown-path guard second.
- P5→ancestor-aware predicate and end-to-end test; P6→leaf fixture; P7→existing control tests plus normalization guard.

## Definition of done

The converted reproduction proves top descendants disappear and unrelated leaves remain; normalization/error and existing permission/no-audit behavior hold; the full build is green.

## Verification

- P5/P6 → failure-mode `FollowTopDigestIT.unfollowTopFromAllExcludesDescendantLeaves`.
- P7 → `UnfollowTagCommandHandlerTest.unfollowTopRetainsNormalizationAndUnknownError` and existing authorization/no-audit tests.
- Failure-mode output must not contain content tagged with a descendant of the unfollowed top.
- Acceptance 5 → `mvn verify`.

## Out-of-scope

No read-time tree expansion, source write path, migration, follow behavior, or audit-policy change. The prior leaf-unfollow regression remains unchanged.
