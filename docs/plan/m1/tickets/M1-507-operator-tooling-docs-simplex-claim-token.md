---
id: M1-507
title: "Operator tooling + docs for SimpleX claim-token bootstrap"
status: pending
created: 2026-06-28
last_updated: 2026-06-28
blocked_by: []
remediates: M1-506
files_budget: 8
files_scope:
  - prod/scripts/6-adapter.sh
  - prod/config/secrets.env.example
  - SETUP_GUIDE.md
  - docs/design/07-deployment.md
  - docs/design/06-messaging.md
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - "The M1-506 runtime claim code itself (SimpleXAdminClaim, AdapterRegistry gate 7, AdminBootstrap simplex-skip, InboundRouter step 2). That landed and is done; this ticket changes ONLY operator tooling + design/setup docs to match the contract it established."
  - "docs/spec/** — the spec (deployment.md §Operator inputs, security.md §Authorization model / §Per-adapter admin threat profile / trust boundary 1, decisions.md D50) was already aligned by M1-506. Do not re-edit the spec here; this ticket aligns the DESIGN notes and tooling that lag it."
  - "The by-address bot-admin COMMANDS rework (/grant-admin <contact>, /ban <contact>, etc.) — a separate pre-existing defect tracked as its own follow-up."
  - "Permanent SimpleX claim-token single-use (the M1-506 redteam medium PERM-ESCAL deferral, which needs a durable token-spent marker + schema migration) — separate follow-up; this ticket only documents the v1 operator hygiene (unset the token after first claim)."
  - "Signal wizard/admin flow — Signal stays configure-by-ACI; do not change the Signal branch of the wizard or its docs."
  - "Any Flyway migration — migration_touch is false."
acceptance:
  - >-
    The setup wizard (prod/scripts/6-adapter.sh) collects the SimpleX
    bootstrap admin as a SECRET TOKEN and writes
    infochat.adapters.simplex.admin-token=<secret> to the runtime config
    (via secrets.env), NOT infochat.adapters.simplex.admin=<address>. SimpleX
    no longer prompts for an admin contact id/address. Signal's branch is
    unchanged (still collects the ACI and writes infochat.adapters.signal.admin).
  - >-
    The wizard's non-empty bootstrap-admin UNION enforcement counts a
    configured SimpleX admin-token as satisfying SimpleX's side of the union
    (mirroring AdapterRegistry.hasBootstrapAdminPath), so a SimpleX-only
    deployment configured purely through the wizard starts successfully (gate 7
    no longer fails "union empty"). A SimpleX-only wizard run with no token
    supplied is refused with a clear message, the same way a missing sole-adapter
    admin was refused before.
  - >-
    prod/config/secrets.env.example documents the SimpleX admin-token variable
    (replacing the INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID address var for SimpleX),
    with a comment that the value is a secret and SHOULD be unset once the first
    admin is claimed.
  - >-
    SETUP_GUIDE.md step 6 (and the admin-question section) describe the SimpleX
    flow as: configure the token, start, DM the bot the token to become admin,
    then unset the token. The "paste your SimpleX address" instruction for
    SimpleX is removed; Signal's "paste your ACI" stays.
  - >-
    docs/design/07-deployment.md is updated: the config example no longer shows
    infochat.adapters.simplex.admin=...; the §7.6.3 / §7.14-7.15 admin
    bootstrap + lost-admin recovery runbook describes the claim-token model and
    its operator hygiene (unset token after claim). docs/design/06-messaging.md
    line ~580's infochat.adapters.simplex.admin reference is corrected (the
    bot's own queue address is derived from the running simplex-chat per M1-320,
    independent of any admin value).
  - >-
    A MIGRATION runbook is added (07-deployment.md recovery section) for
    deployments that already bootstrapped SimpleX by address before M1-506: it
    explains that the old by-address bootstrap left a phantom
    (simplex, is_admin=true) users row that (a) was never reachable and (b) now
    BLOCKS the token claim (the single-use gate sees an existing SimpleX admin),
    and gives the exact recovery steps — set the token; clear the phantom row;
    DM the token to claim; unset the token — including the
    last-admin-protection caveat (for a SimpleX-only deployment the phantom is
    the only global admin, so the clear requires temporarily disabling the
    last-admin trigger, the documented break-glass DB action).
  - "mvn -B verify is green from the repo root (sanity; this ticket is tooling/docs only, so no Java/config/DB file changes — verify is N/A by the inert-diff rule, but the no-regression baseline is the fork point)."
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main (this ticket touches only shell tooling and markdown docs; no Java/test/migration files)
spec_refs:
  - "docs/spec/deployment.md §Operator inputs"
  - "docs/spec/security.md §Per-adapter admin threat profile"
decision_refs:
  - D9
  - D46
  - D50
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check: {}
---

# M1-507: Operator tooling + docs for SimpleX claim-token bootstrap

## Context

M1-506 changed the SimpleX bootstrap-admin contract from configure-by-address
(`infochat.adapters.simplex.admin=<SimpleX address>`, seeded at startup) to a
single-use claim-token (`infochat.adapters.simplex.admin-token=<secret>`,
claimed by the first DM whose body equals the token). The runtime code and the
SPEC (`docs/spec/`) were updated in M1-506. The DESIGN notes
(`docs/design/`) and the OPERATOR TOOLING (`prod/`) still implement and
document the old by-address model and are now stale — and one is a functional
break, not just a doc lag:

- **`prod/scripts/6-adapter.sh` (the setup wizard, run by `prod/setup.sh`)**
  still prompts for a SimpleX admin *address* and writes
  `infochat.adapters.simplex.admin=...`. Post-M1-506 that key is inert for
  SimpleX (gate 7 counts only `…admin-token`), so a **SimpleX-only deployment
  configured by the current wizard fails to start** with the gate-7
  "bootstrap admin union empty" error.
- `prod/config/secrets.env.example`, `SETUP_GUIDE.md`,
  `docs/design/07-deployment.md` (config example + lost-admin recovery runbook)
  and `docs/design/06-messaging.md` all reference the old `simplex.admin`
  bootstrap value.

There is also a **migration hazard for existing deployments** that already
bootstrapped SimpleX by address: the old path seeded a phantom
`(simplex, <address>, is_admin=true)` users row. That row survives an
`upgrade.sh` (the DB volume is preserved), was never reachable (inbound DMs
resolve to a per-connection `contact_id`, the M1-505/506 root cause), and now
**blocks the new token claim** because the single-use gate is
`WHERE NOT EXISTS (… adapter='simplex' AND is_admin=TRUE)`. Operators upgrading
from an old-bootstrap deployment therefore need an explicit recovery runbook.

## Acceptance

See the YAML `acceptance:` list. In prose:

1. The wizard collects the SimpleX admin as a **token secret** and writes
   `infochat.adapters.simplex.admin-token`; it no longer prompts for / writes a
   SimpleX admin address. Signal's branch is untouched.
2. The wizard's union enforcement counts a configured SimpleX token as
   SimpleX's bootstrap-admin path (so a SimpleX-only wizard run starts), and
   still refuses a SimpleX-only run with no token.
3. `secrets.env.example`, `SETUP_GUIDE.md`, `07-deployment.md`,
   `06-messaging.md` describe the token model (configure → first-DM claim →
   unset the token).
4. A migration runbook covers existing old-bootstrap deployments: the phantom
   `(simplex, is_admin=true)` row blocks the claim; recovery is set-token →
   clear-phantom-row → claim → unset-token, with the last-admin-trigger
   break-glass caveat for SimpleX-only deployments.

## Notes

- **Reuse the runtime contract, don't reinvent.** The wizard's union check
  should mirror `AdapterRegistry.hasBootstrapAdminPath`: SimpleX → the
  `admin-token` key; every other adapter → the `.admin` key. Keep the wizard's
  per-adapter prompt structure; only the SimpleX branch changes.
- **Security hygiene is the v1 mitigation for the redteam deferral.** The docs
  must tell operators to unset `infochat.adapters.simplex.admin-token` once the
  first admin is established (this fully closes the re-arm-after-revoke gap that
  M1-506's redteam flagged and deferred). Permanent single-use without
  unsetting is a separate follow-up (needs a durable token-spent marker +
  schema migration).
- **Migration break-glass.** Document the exact DB steps to clear the phantom
  row, including the last-admin-protection trigger disable/enable for a
  SimpleX-only deployment (the same `ALTER TABLE users DISABLE/ENABLE TRIGGER`
  shape the workflow already uses elsewhere), and warn that it is a break-glass
  DB action, not a routine command (the by-address admin commands cannot reach
  the phantom row).
- **Inert diff.** This ticket touches only `*.sh` and `*.md` — no
  `*.java`/`pom.xml`/`src/**/resources/**` — so `mvn verify` covers nothing it
  changes (the SKILL inert-diff rule); the no-regression baseline is the fork
  point. The wizard change should still be exercised manually / by any existing
  wizard shell test harness if one is added.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-507-*.md
```
