---
id: M1-490
title: "Reconcile §9 split-clock decision sites against the M1-447 backlog"
status: done
created: 2026-06-27
last_updated: 2026-06-29
blocked_by: []
files_budget: 10
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
    NostrRelayConnection cooldown park-duration computes
    Duration.between(Instant.now(), healthTracker.nextAttemptTime()) — wall-clock
    Instant.now() minus an injected-Clock instant (NostrRelayConnection.java:246),
    closed by a new single-clock RelayHealthTracker.untilNextAttempt(); the 05#F2
    NostrStreamSource cursor-floor is ALREADY on clock.instant() (M1-452,
    NostrStreamSource.java:614-622) so it is reconciled, not re-touched; (d)
    ProbationCheck.clearIfPromoted gates on SQL NOW() while inProbation uses the
    injected Clock (ProbationCheck.java:48-50).
  - >-
    Before changing each site, it is reconciled against the M1-447 backlog: any
    of the four already owned by M1-447 is either migrated here (and removed from
    that backlog) or explicitly deferred to it in this ticket's notes — no site
    is left half-migrated (read moved to Clock, write left on now()).
  - >-
    Each migrated gate has a test that pins the decision with a fixed Clock and
    proves the gate flips at the injected boundary (the property the split
    previously made untestable): QuarkusMock.installMockForType(Clock.fixed(...))
    for the three CDI beans (InviteCodeConsumer, InviteCommandHandler,
    ProbationCheck); for the non-CDI, directly-constructed RelayHealthTracker the
    equivalent pin is a constructor-injected fixed Clock (the existing
    RelayHealthTrackerTest MutableClock pattern).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteExpiryClockIT.java"
  modifies:
    # Site (a) attempted_at-write pin reuses the existing brute-force-window
    # clock IT (already pins Clock.fixed via QuarkusMock + has a seedAttempt
    # helper) rather than a duplicate new file; (d) and (c) extend the existing
    # clock tests for their newly-migrated gates.
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteCodeConsumerClockIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/ProbationCheckClockIT.java"
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/RelayHealthTrackerTest.java"
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
      added: 456
      removed: 54
overrides: []
revisions:
  - date: 2026-06-29
    reason: >-
      budget-breach refine (user-authorized, pre-start). Honest diff is 10 files:
      site (c)'s cursor-floor is already on clock.instant() (M1-452), so the clean
      low-ripple fix routes through a new RelayHealthTracker.untilNextAttempt()
      (+RelayHealthTrackerTest) instead of NostrStreamSource, and acceptance item 2
      mandates a now-clock-audit.md backlog edit. Bumped files_budget 9->10,
      corrected the site-(c) acceptance premise, and rewrote test_plan to reuse the
      existing InviteCodeConsumerClockIT / ProbationCheckClockIT / RelayHealthTrackerTest
      (modifies) plus the one new InviteExpiryClockIT, per clarity WARN.
    snapshot:
      files_budget: 9
      test_plan_adds:
        - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InviteBruteForceWindowClockIT.java"
        - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteExpiryClockIT.java"
      acceptance_c: "NostrRelayConnection/NostrStreamSource cooldown + cursor-floor (Instant.now() vs injected clock, 05#F2)"
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-29
    verdict: CLEAN
    base: 9b53aa2dd8014425612178c92ea1809f4446d81a
    head: working-tree (uncommitted, pre-commit lifecycle)
    verdict_file: docs/plan/m1/redteam/M1-490-2026-06-29.md
    out_of_model_count: 1
    note: >-
      CLEAN, 0 findings. §9 split-clock reconciliation, production behaviour
      byte-for-byte preserved under Clock.systemUTC(). One out-of-model item
      (app-authored expires_at vs DB-side consume gate under operator host-clock
      skew) predates the diff and is already documented as accepted in
      now-clock-audit.md; host clock sync is trusted operator config outside the
      threat model — no follow-up ticket.
clarity_check:
  date: 2026-06-29
  verdict: PASS
  warnings: []
  blockers: []
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

### M1-447 backlog reconciliation (acceptance item 2)

The backlog doc is `docs/plan/m1/now-clock-audit.md`. Per-site disposition,
migrated here unless noted; each migrated site has its `now-clock-audit.md`
entry updated ("removed from that backlog"):

- **(a) InviteCodeConsumer** — row 1, marked CONVERTED by M1-447, but the
  `attempted_at` WRITE (`INSERT_ATTEMPT_SQL`, DB `DEFAULT now()`) was an omission:
  the count cutoff moved to the app `Clock`, the value it compares against did
  not. Migrate the write to the sampled `now`; update the audit's InviteCodeConsumer
  section. The `CONSUME_INVITE_SQL` `expires_at > NOW()` read stays on the DB clock
  (M1-447's deliberate intra-statement decision, audit:89-95) — out of this site's
  scope (acceptance cites :115-116,327-393 only).
- **(b) InviteCommandHandler** — the `expires_at` WRITE (`OffsetDateTime.now()`,
  :308/:357) and the two cap-count READs (`expires_at > NOW()`, :88/:96) both move
  to the injected `Clock`, making the open/contact cap gate single-clock. The
  `SELECT_PENDING_LIST_SQL` display filter (:119) and the consumer's consume-time
  `expires_at > NOW()` stay on the DB clock (display + M1-447 intra-statement). This
  is write→Clock with those two reads left on DB `now()` — NOT the forbidden
  "read-moved/write-left" half-migration: the actual gate (cap count) is fully
  single-clock; the residual DB-clock reads are byte-for-byte equivalent under
  production `Clock.systemUTC()`.
- **(c) NostrRelayConnection cooldown** — NOT in the M1-447 (A) table. The audit
  listed `NostrStreamSource` as "already-correct" (audit:57); that holds for the
  cursor-floor (M1-452) but the `NostrRelayConnection:246` cooldown park-duration
  still subtracts a wall-clock `Instant.now()` from the Clock-based
  `nextAttemptTime()`. Fix: add `RelayHealthTracker.untilNextAttempt(relay)` that
  computes the remaining park duration entirely against the tracker's injected
  Clock; `NostrRelayConnection` consumes it and drops `Instant.now()`. Add a
  reconciliation line to the audit's Nostr/already-correct note.
- **(d) ProbationCheck.clearIfPromoted** — explicitly the M1-447 follow-up
  (audit:98-107, "the follow-up ticket that converts ProbationCheck should move
  its read onto the same Clock"). This ticket is that follow-up: `clearIfPromoted`
  moves its `probation_until <= NOW()` gate onto the injected `Clock` (the read
  side `inProbation` already is). Mark the audit's ProbationCheck cross-component
  note resolved.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-490-*.md
```
