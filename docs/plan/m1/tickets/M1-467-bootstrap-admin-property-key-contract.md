---
id: M1-467
title: "Fix bootstrap-admin property key: wizard/docs vs runtime"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 3
files_scope:
  - prod/scripts/6-adapter.sh
  - docs/design/07-deployment.md
  - prod/config/secrets.env.example
complexity: low
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  # The Java adapter config contract is canonical and stays untouched — the
  # runtime reads infochat.adapters.<name>.admin (AdapterRegistry gate 7/7b,
  # AdminBootstrap). This ticket aligns the wizard + design TO that key, the
  # same direction M1-387 took for the connection keys. No Java change.
  - infochat-provider/**
  - infochat-messaging-adapter/**
  # The non-empty admin-union gate semantics (§7.6.3) and the env-var names
  # INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID / INFOCHAT_SIGNAL_ADMIN_CONTACT_ID are
  # correct and unchanged — only the application.properties property KEY the
  # wizard writes (and the design documents) is wrong.
  # docs/design/06-messaging.md is already consistent (M1-465 §6.4.4 note uses
  # infochat.adapters.simplex.admin) — not touched.
acceptance:
  - >-
    prod/scripts/6-adapter.sh writes the bootstrap admin under
    infochat.adapters.simplex.admin and infochat.adapters.signal.admin (the
    keys AdapterRegistry/AdminBootstrap read), not
    infochat.adapters.<name>.bootstrap-admin-contact-id. After the change,
    grep -nE 'infochat\.adapters\.(simplex|signal)\.admin=' prod/scripts/6-adapter.sh
    matches both writer lines and
    grep -n 'bootstrap-admin-contact-id' prod/scripts/6-adapter.sh matches nothing.
  - >-
    docs/design/07-deployment.md uses the runtime key
    infochat.adapters.<name>.admin at every CONFIGURATION locus — the §7.4
    canonical application.properties block (the two simplex/signal admin
    lines), the §7.6.3 property reference, the §7.7.2 step-6 table, the
    env-var/refuses-to-start prose, the lost-bootstrap-admin recovery table,
    the §7.16 go-live checklist — so that
    grep -n 'infochat\.adapters\.[^ ]*\.bootstrap-admin-contact-id' docs/design/07-deployment.md
    matches nothing (the bare phrase "bootstrap admin" in narrative prose may
    remain; only the wrong property-KEY form is removed).
  - >-
    prod/config/secrets.env.example's comment describing the written property
    line names infochat.adapters.<name>.admin, not bootstrap-admin-contact-id;
    the INFOCHAT_*_ADMIN_CONTACT_ID variable names are unchanged.
  - prod/scripts/6-adapter.sh passes `bash -n`
  - mvn -B verify is green (no Java change; the full suite must still pass)
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Operator inputs
  - docs/design/07-deployment.md §7.6.3 Bootstrap admin
decision_refs:
  - D9
  - D46
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-467: Fix bootstrap-admin property key contract (wizard/docs vs runtime)

## Context

The first-run wizard (`prod/scripts/6-adapter.sh:307,315`) writes the
bootstrap admin into `application.properties` under
`infochat.adapters.<name>.bootstrap-admin-contact-id`, and the deployment
design (`docs/design/07-deployment.md` — the §7.4 canonical block lines
199/209 plus ~7 further loci) documents the same key. But the Provider
runtime reads `infochat.adapters.<name>.admin`:
`AdapterRegistry` gate 7 (union) and gate 7b (parse) at
`AdapterRegistry.java:267,306`, and `AdminBootstrap.seed` at
`AdminBootstrap.java:141`. `grep -rn 'bootstrap-admin-contact-id'` over
`infochat-*/src/main` returns nothing — **no code ever reads that key**, and
no SmallRye relocate/alias bridges the two names.

So a production deployment configured by the documented wizard path sets a
bootstrap admin under a key the Provider never reads. The union of
`infochat.adapters.<name>.admin` is therefore empty and **gate 7 refuses to
start** ("the union of infochat.adapters.<name>.admin ... is empty"), or — if
the operator only ever runs the wizard — there is simply no admin row. Either
way the documented happy path produces an unusable deployment.

This is the exact failure class M1-387 fixed for the *connection* keys
(`simplex.url`/`session-token`/`identity-dir` → `binary`/`data-dir`/`account`):
"the wizard's step 6 writes adapter configuration under property keys that no
code reads, so the Provider refuses to start." M1-387 **explicitly left the
bootstrap-admin key out of scope** ("the bootstrap-admin-contact-id capture —
unchanged; only the adapter-connection keys are corrected"). This ticket
closes that one remaining key, the same way: align the wizard + design to the
shipped, tested Java contract (`.admin`).

## Acceptance

See frontmatter. In prose: the wizard and `07-deployment.md` everywhere write
and document `infochat.adapters.<name>.admin` (the key the runtime reads);
the unread `…bootstrap-admin-contact-id` property key disappears; the
`INFOCHAT_*_ADMIN_CONTACT_ID` env-var names and the §7.6.3 union semantics are
unchanged; `bash -n` and `mvn -B verify` pass.

## Out-of-scope

See frontmatter. No Java change — the runtime key is canonical (M1-387
precedent). `docs/design/06-messaging.md` already uses the correct `.admin`
form (added by M1-465 §6.4.4) and is not touched. The env-var names and the
admin-union gate behavior are correct as-is.

## Notes

- **Direction decision.** Two ways to reconcile: (a) align wizard + docs to
  the runtime `.admin` key (chosen — proportionate, mirrors M1-387, touches no
  tested Java); (b) rename the runtime to read `bootstrap-admin-contact-id`
  (more descriptive but touches `AdapterRegistry`, `AdminBootstrap`, and every
  bootstrap-admin test that hard-codes `.admin`, and re-opens M1-208's reviewed
  surface). (a) is the smaller, lower-risk change consistent with the existing
  precedent. If the reviewer prefers (b), that is an `escalate → refine` to a
  Java-touching scope, not a silent expansion.
- Reference fix to mirror: M1-387 (`docs/plan/m1/tickets/M1-387-wizard-adapter-config-key-contract.md`)
  — same idempotent drop-then-append pattern, same grep-presence/absence
  acceptance shape for a config-key contract.
- Loci of the wrong key in `07-deployment.md`: lines ~199, 209 (§7.4 block),
  353 (refuses-to-start prose), 478 (§7.6.3), 672 (§7.7.2 step-6 table), 856
  (manual start prose), 1134–1135 (recovery table), 1164 (go-live checklist).
  Re-verify line numbers at implementation time — they drift.
- Why `risk: high` / `security_relevant: true`: this governs whether the
  deployment has a working bot admin at all. A wrong key silently yields a
  zero-admin (or fail-to-start) deployment — the last-admin/bootstrap invariant
  the whole admin model rests on.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-467-*.md
```
