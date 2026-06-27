---
id: M1-468
title: Document last-admin protection blind spot for unreachable admins
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 2
files_scope:
  - docs/spec/security.md
  - docs/design/07-deployment.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  # No code change in this ticket. The last-admin protection trigger
  # (V5 / V40, SQLSTATE IC001) and the seeding path stay as-is. A
  # code-level reachability guard (a "confirmed-reachable" admin concept)
  # is a larger schema + intake + trigger change requiring a spec amendment;
  # captured in §Notes as a future direction, NOT done here.
  - infochat-core/src/main/resources/db/migration/**
  - infochat-provider/**
  - infochat-messaging-adapter/**
acceptance:
  - >-
    docs/spec/security.md §Authorization model documents the last-admin
    protection blind spot: the invariant counts users rows by
    is_admin = TRUE AND is_banned = FALSE only, with no reachability
    dimension, so a bootstrap-seeded admin whose contact_id never
    byte-matches any inbound message ("phantom admin", e.g. from a mistyped
    bare id) counts as a live admin. The doc states the consequence — the
    deployment can be reduced (by a co-admin) to only an unreachable admin
    while the trigger still believes one admin remains — and that this is an
    operator-misconfiguration risk, not an adversary-reachable path.
  - >-
    docs/design/07-deployment.md adds an operator recovery note (in the
    bootstrap-admin / lost-admin runbook area, §7.14 / the recovery table)
    covering how to detect and recover from a phantom admin row (verify the
    seeded contact_id against an inbound message; re-seed via bootstrap-admin
    drift; the direct-DB last-resort recovery already documented for a lost
    admin).
  - mvn -B verify is green (doc-only change; the full suite must still pass)
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/deployment.md §Bootstrap behavior on startup
decision_refs:
  - D9
  - D10
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-468: Document the last-admin protection blind spot for unreachable admins

## Context

The last-admin protection trigger (`V5__identity_audit.sql`, lock-scope
revised in `V40__last_admin_lock_scope.sql`, SQLSTATE `IC001`) guards against
leaving the deployment with zero bot admins by counting:

```sql
SELECT count(*) FROM users WHERE is_admin = TRUE AND is_banned = FALSE
```

There is **no reachability dimension** in that count. A bootstrap-seeded
admin row whose `contact_id` never byte-matches what its adapter reports for
inbound messages — a "phantom admin" — is still `is_admin = TRUE,
is_banned = FALSE`, so it counts as a live admin. Such a row arises from
operator misconfiguration: a mistyped bare contact id, or (pre-M1-465) a
hand-extracted SimpleX queue id that differs from what `simplex-chat` later
reports inbound (`SimpleXMessageCodec.decodeNewChatItem` reads
`chatInfo.contact.contactId`).

Consequence: with a phantom admin plus real admins, a co-admin can
`/revoke-admin` (or ban) the real admins down to only the phantom; the
trigger sees `count >= 1` and allows it, leaving a deployment that is locked
out of admin while the protection invariant believes one admin remains. This
was surfaced as an out-of-model observation by the M1-465 red-team
(`docs/plan/m1/redteam/M1-465-2026-06-27.md`).

This is **not adversary-reachable** — the seeded contact id is trusted
operator config (`docs/spec/security.md` trust boundaries), and the risk
predates M1-465 (which in fact *reduces* the SimpleX-link source by
canonicalizing the operator's link to the same bare id the bot-identity
derivation produces). Because the practical exposure is low and a robust code
fix carries real design tension (see §Notes), this ticket's deliverable is to
**document the limitation and the operator recovery**, and to record the
code-hardening option for a future ticket — not to change the invariant.

## Acceptance

See frontmatter. In prose: `security.md` §Authorization model states that
last-admin protection is flag-based (`is_admin AND NOT is_banned`) with no
reachability check, names the phantom-admin lockout consequence, and frames it
as an operator-misconfiguration (not adversary) risk; `07-deployment.md` adds
an operator detection + recovery note; the full suite stays green.

## Out-of-scope

See frontmatter. No code, no migration, no trigger change. The
reachability-aware "confirmed admin" hardening is deliberately deferred — it
needs a spec amendment and a schema/intake change disproportionate to a
no-adversary-path operator-misconfig issue.

## Notes

- **Why doc-only, and the code-fix option for later.** A faithful code fix
  would have to distinguish a *reachable* admin from a phantom, which the
  schema has no concept of today. Candidate future direction (own ticket,
  spec-amend first): add a "confirmed-reachable" marker set the first time an
  inbound message from a bootstrap admin's `(adapter, contact_id)` is seen,
  and count only confirmed admins in the last-admin protection trigger. That
  touches the schema (migration), the intake path, and the V5/V40 trigger —
  and it changes a security invariant, so it must go through
  `escalate → spec-amend`. Reachability cannot be asserted at `@Startup`
  bootstrap time (the adapter transports are not necessarily up, and a live
  round-trip would break the offline, per-adapter-resilient bootstrap design),
  which is why a startup-time guard is not the answer.
- **Relationship to M1-467.** M1-467 fixes a *different* divergence (the
  wizard writing a property key the runtime never reads, which yields a
  zero-admin / fail-to-start deployment, not a phantom). They are independent;
  neither blocks the other.
- Relevant code: trigger bodies in
  `infochat-core/src/main/resources/db/migration/V5__identity_audit.sql` and
  `V40__last_admin_lock_scope.sql`; the consuming handlers
  `RevokeAdminCommandHandler` / `BanCommandHandler` (catch `IC001`).
- Source observation: `docs/plan/m1/redteam/M1-465-2026-06-27.md` (OUT-OF-MODEL).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-468-*.md
```
