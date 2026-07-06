---
id: M1-579
title: "/pending lists only currently-actionable users (drop the terminal 'invited' roster arm)"
status: in-progress
created: 2026-07-06
last_updated: 2026-07-06
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/PendingUsersDao.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/PendingCommandHandlerIT.java
  - docs/spec/commands.md
  - docs/design/03-commands.md
  - docs/spec/decisions.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Changing /vouch semantics or the registration_state machine (schema.md's
    closed transition set is untouched). This ticket corrects a READ predicate;
    it does not add an invited→vouched transition or any state write.
  - >-
    Widening /pending toward a /list-users directory. The fix NARROWS the
    listed set; the D55 no-roster posture is the point.
  - >-
    Pagination, rendering, audit (PENDING_LIST row), permission gating, or the
    Clock plumbing of /pending — all correct per the M1-575 review and
    unchanged here. Only the WHERE predicate, its tests, and the spec text
    that describes it.
acceptance:
  - >-
    PendingUsersDao's ACTIONABLE_WHERE no longer contains the
    `registration_state = 'invited'` arm. The actionable set is
    `adapter = ? AND is_banned = FALSE AND probation_until IS NOT NULL AND
    probation_until > ?` (the '?' cutoff stays the caller's injected Clock,
    unchanged). A comment records WHY: post-D47 'invited' is a terminal state
    (`/vouch` only nulls probation_until; the only runtime writers of
    'vouched' are AdminBootstrap and SimpleXAdminClaim, both bootstrap-admin
    paths), so the old arm matched every registered user forever.
  - >-
    The IT's "settled user excluded" fixture seeds the REACHABLE post-vouch
    shape (`registration_state='invited'`, `probation_until=NULL`) and asserts
    exclusion. This is the regression test that would have caught the defect —
    the old fixture seeded 'vouched', a state no regular user can reach.
  - >-
    New IT case: a user listed while inside the probation window disappears
    from /pending after the /vouch-shaped update (probation_until set NULL) —
    pins that the admin queue actually shrinks when the admin acts.
  - >-
    New IT case: an otherwise-actionable user seeded under a DIFFERENT adapter
    name is NOT listed — pins the `adapter = ?` scoping (D55's second bound:
    listed ids must resolve for /vouch and /ban in this conversation).
  - >-
    docs/spec/commands.md §/pending and the docs/design/03-commands.md mirror
    describe the corrected actionable set, so the existing sentence "Banned
    and settled (vouched, out-of-probation) users are excluded … never a full
    roster" becomes TRUE of the code. "Awaiting vouch" is stated as a subset
    of "inside the probation window" (a vouch after natural probation expiry
    is a no-op, so nothing actionable is lost by the narrower predicate).
    decisions.md D55 is touched only if its own text pins the wrong predicate.
  - "`mvn verify` is green from the repo root (full suite, not just the new cases)."
test_plan:
  adds:
    - "PendingCommandHandlerIT — vouch-clears-listing case; cross-adapter exclusion case."
  modifies:
    - "PendingCommandHandlerIT — settled-user fixture flipped to the reachable ('invited', NULL) shape."
    - "PendingUsersDao — ACTIONABLE_WHERE predicate."
  preserves:
    - "All existing /pending IT cases (authorization, DM-only, audit-before-effect, Clock pinning, pagination) stay green unmodified except the fixture above."
spec_refs:
  - "docs/spec/commands.md §Permission model"
  - "docs/spec/schema.md §Identity and access"
  - "docs/design/03-commands.md §3.2 Permission matrix"
decision_refs:
  - "D55 (narrow actionable list, no /list-users roster)"
  - "D45 (slow-start probation)"
  - "D47 (registration-state consolidation)"
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check:
  date: 2026-07-06
  verdict: PASS
  warnings: []
---

# M1-579: /pending lists only currently-actionable users

## Context

The 2026-07-06 decision+implementation audit of M1-567..576 found (HIGH) that
`/pending` (M1-575, commit 88a847ac) is a permanent full user roster:
`ACTIONABLE_WHERE` keeps `registration_state = 'invited'` as an OR-arm, but
post-D47 `invited` is terminal — `/vouch` runs only
`UPDATE users SET probation_until = NULL` (VouchCommandHandler:122, its javadoc
says it "no longer advances registration_state"), and schema.md §Identity and
access states the only runtime path to `vouched` is bootstrap. Verified: the
only `'vouched'` writers in the codebase are AdminBootstrap and
SimpleXAdminClaim; V27's single UPDATE sets `'preban'`.

Consequences: (a) privacy — on a mature deployment every registered user is
listed with a dialable contact id across pages, exactly the /list-users the
ticket's own out_of_scope, D55, and the commands.md sentence added in the same
commit all forbid (the code contradicts its own spec text); (b) functional — a
/vouch'd user STAYS listed; the queue only shrinks via /ban.

It shipped green because the IT's "settled" fixture seeds
`registration_state='vouched'` — unreachable for regular users. The clarity
warning on M1-575 planted the predicate ("registration_state='invited' and/or
probation_until > now") and nobody falsified it against schema.md.

## Approach

Drop the arm; in v1 "awaiting vouch" IS "inside the probation window" (vouch's
sole effect is ending probation early; after natural expiry a vouch is a
no-op). Flip the settled fixture to the reachable shape, add the
vouch-clears-listing and cross-adapter cases, reconcile the spec wording.

## Notes

- Elapsed-but-not-yet-lazily-graduated rows (`probation_until` non-null but
  past) are already excluded by `probation_until > ?` — no interaction with
  InboundRouter's lazy graduation.
- Audit provenance: finding H1 of the 2026-07-06 audit (in-chat report; memory
  `audit-567-576-open-findings`).
