---
id: M1-324
title: "Amend §Failure handling: group-deleted-upstream via threshold in v1"
status: done
created: 2026-06-13
last_updated: 2026-06-13
blocked_by: []
files_budget: 2
files_scope:
  - docs/spec/messaging.md
  - docs/design/06-messaging.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any code change. This is a documentation-only amendment; no adapter,
    SPI, Provider, or test code is touched. The threshold-path behavior the
    amendment documents already exists (M1-284) and is not modified.
  - Re-opening M1-314. The amendment OBSOLETES M1-314 (its immediate-cleanup
    branch is no longer a v1 requirement); M1-314 stays deferred, it is not
    reopened when this lands.
  - Introducing any v2 carrier design (the failure-class taxonomy / adapter
    group-not-found signal). The amendment only records that the distinction
    is deferred to v2; designing it is future v2 work.
acceptance:
  - "docs/spec/messaging.md §Failure handling 'Group deleted upstream' bullet is amended so v1 treats a permanent group-not-found send failure via the SAME permanent-failure threshold path as bot-removed (no distinct immediate-on-single-signal cleanup branch), and the immediate cleanup on a definitive single group-not-found signal is explicitly recorded as deferred to v2. The amendment preserves the existing v1 behavior (group cleanup still happens via the threshold) and removes only the v1 promise of immediate single-signal cleanup."
  - "docs/design/06-messaging.md 'Group-deleted-upstream' line (currently 'treated identically to bot-removed') is updated to match the amended spec: v1 = threshold path; the adapter-specific group-not-found / group-no-longer-exists signal and the immediate-cleanup branch are v2. No new design contradicts the amended spec section it links upward to."
  - "No code or test file is modified by this ticket (documentation-only)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
decision_refs: []
spec_amend_for: docs/spec/messaging.md §Failure handling
spec_amend_parent: M1-314
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-13
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-324: Amend §Failure handling: group-deleted-upstream via threshold in v1

## Context

Created from the `/m1-tick escalate M1-314 premise-fail` spec-amend
resolution (2026-06-13). M1-314 set out to add an immediate group cleanup
on a definitive "group deleted upstream" signal, distinct from the
threshold-counted bot-removed path. Grounding at `start` found the branch
point does not exist: the adapter→Provider failure signal is the binary
`FailureCategory` (TRANSIENT/PERMANENT) only, with no group-not-found
sub-class. That finer signal was assumed to be owned by M1-294, but
M1-294's acceptance never scoped it (verified across every version of the
M1-294 ticket file), so the carrier was never built by any ticket.

Rather than build the carrier in v1, the decision (user-confirmed) is to
defer the distinction to v2: a deleted group is already cleaned up
correctly today — it rides M1-284's permanent-failure threshold path (the
streak climbs monotonically on a group whose every send fails, so cleanup
fires at the profile threshold). The only thing the immediate path would
add is firing ~2–4 digest cycles sooner, against a real false-positive
risk if the adapter's group-not-found signal can ever flicker (a wrongly
soft-removed live group does not self-heal, since recovery needs a re-add
signal that never comes). Marginal upside, real downside, nothing broken
without it → v2.

## Acceptance

See frontmatter. Two documentation edits that make the spec and design stop
promising v1 immediate-single-signal cleanup and record the threshold path
as the v1 behavior, with the distinction deferred to v2.

## Out-of-scope

See frontmatter. No code, no v2 carrier design, and M1-314 is not reopened
— it is obsoleted by this amendment and remains deferred.

## Notes

- This is a pure-documentation amendment. Per `CLAUDE.md` §"Commit prefixes",
  pure spec/design edits may be landed directly on `main` with a `spec:`
  prefix, bypassing clarity/reviewer/`mvn verify`. This ticket exists as the
  lifecycle record of WHY M1-314 was deferred and WHAT the spec change is; it
  may be executed either via `/m1-tick start M1-324` or as a direct `spec:`
  commit. Whichever path, M1-314 stays deferred afterward.
- The v2 follow-up (when it happens) should not be "bolt one enum value onto
  the binary `FailureCategory`": the `PERMANENT` bucket already lumps ≥5
  distinct causes (design 06-messaging.md table) and the spec already drives
  ≥3 distinct behaviors off "permanent" (abort-reply, per-group cleanup,
  per-user soft-clear). The correct v2 shape is a proper failure-class
  taxonomy (minimally permanent-definitive vs permanent-inferred) plus a
  single group-liveness/cleanup-policy seam, gated on a spike confirming the
  group-not-found signal is reliably terminal per transport. Recorded here as
  rationale, not as v1 commitment.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-324-*.md
```
