---
id: M1-490
title: "Reconcile §9 split-clock decision sites against the M1-447 backlog"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 9
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "The §9 record-write carve-out sites (e.g. SignalGroupHandler Identity.lastSeen, 08#F2) — those only record time and are exempt; do not migrate them."
  - "Pre-existing inline-time sites already enumerated in the M1-447 migration backlog that are NOT in the four locations below — reconcile, don't expand."
acceptance:
  - >-
    Each of the four decision-gate sites reads and writes the gating timestamp on
    the SAME clock (the injected java.time.Clock), eliminating the app-vs-DB
    split: (a) InviteCodeConsumer brute-force window — countAttempts cutoff is
    app-clock while INSERT_ATTEMPT_SQL stamps DB DEFAULT now()
    (InviteCodeConsumer.java:115-116,327-393); (b) InviteCommandHandler
    invite-expiry — expires_at written on OffsetDateTime.now() but compared with
    DB NOW() (InviteCommandHandler.java:83-96,308,357); (c)
    NostrRelayConnection/NostrStreamSource cooldown + cursor-floor (Instant.now()
    vs injected clock, 05#F2); (d) ProbationCheck.clearIfPromoted gates on SQL
    NOW() while inProbation uses the injected Clock (ProbationCheck.java:48-50).
  - >-
    Before changing each site, it is reconciled against the M1-447 backlog: any
    of the four already owned by M1-447 is either migrated here (and removed from
    that backlog) or explicitly deferred to it in this ticket's notes — no site
    is left half-migrated (read moved to Clock, write left on now()).
  - >-
    Each migrated gate has a test that pins the decision with
    QuarkusMock.installMockForType(Clock.fixed(...)) and proves the gate flips at
    the injected boundary (the property the split previously made untestable).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteBruteForceWindowClockIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteExpiryClockIT.java"
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-490: Reconcile §9 split-clock decision sites against the M1-447 backlog

## Context

From `/deep-code-review full` (2026-06-27), cross-cutting theme **CT1** —
reports `05#F2`, `12#F4`, `14#F1`, `14#F2` (verified at source). Four decision
gates (brute-force window, invite-expiry, relay cooldown/cursor-floor, probation
graduation) read "now" from the injected app `Clock` while the value they
compare against is written/read on the DB clock (`now()`/`NOW()`) — the app-vs-DB
split engineering-rules §9 prohibits, leaving each gate unpinnable in tests. The
synthesizer flagged that several of these may already belong to the M1-447
migration backlog, so this ticket **reconciles** rather than blindly rewrites.

## Acceptance

See frontmatter. Put each gate's read and write on the injected `Clock`,
reconcile against M1-447 first, never leave a site half-migrated, and pin each
with a `Clock.fixed` test. `ReEvaluationJob` is the reference shape.

## Out-of-scope

See frontmatter. Record-write carve-out sites (08#F2 etc.) are exempt; M1-447's
other pre-existing sites are not pulled in.

## Notes

- Source: `/deep-code-review full` (2026-06-27), CT1 (05#F2, 12#F4, 14#F1, 14#F2).
- Severities vary: 14#F1 (brute-force gate) is the highest-blast-radius; 14#F2
  self-heals and is low — but all four are the same split shape.
- `security_relevant: true` because the brute-force window is a security gate.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-490-*.md
```
