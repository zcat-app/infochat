---
id: M1-509
title: "Guide accuracy-v2 + de-verbosify pass across the four root guides"
status: pending
created: 2026-06-28
last_updated: 2026-06-30
blocked_by: []
files_budget: 5
files_scope:
  - README.md
  - SETUP_GUIDE.md
  - ADMIN_GUIDE.md
  - USER_GUIDE.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Any code/test/migration/config change — docs only. The bundled signal-cli, backup default dir, claim-token bootstrap, and confirm-keyword behaviors described here already exist in the scripts/code; the guides must be corrected to match what IS, never the reverse."
  - "docs/spec/** and docs/design/** — the spec/design are the authority and are already correct on every point this ticket fixes (verified by the 2026-06-30 four-file audit). This ticket aligns the root GUIDES to them; it does not touch them."
  - "Creating new guide files (no simple/comic + advanced split). The audit found the default happy path already works in one `./prod/setup.sh` invocation; the fix is a quickstart-at-top reorg inside SETUP_GUIDE plus moving reference material, not a new file. A file split would add drift surface, not reduce it."
  - "Documenting M1-508 (SimpleX inbound codec) as an open issue — it is DONE. Do not add a 'bot does not receive on SimpleX' runbook entry."
  - "The pre-M1-506 phantom-admin-row migration gotcha — it applies only to deployments bootstrapped by-address before M1-506; irrelevant to a greenfield install and out of scope for these guides."
acceptance:
  - >-
    SETUP_GUIDE.md — Signal binary: the guide stops telling operators to
    "install signal-cli" / supply a host binary path. signal-cli is BUNDLED in
    the provider image (Dockerfile.jvm downloads v0.14.5, symlinks
    /usr/local/bin/signal-cli) exactly like simplex-chat; the wizard's
    "signal-cli binary path" prompt is the IN-CONTAINER default the operator
    accepts with Enter. The Signal section is symmetric with the SimpleX
    "the image bakes it" framing.
  - >-
    SETUP_GUIDE.md — Signal registration: documents that the one-time
    register/verify can run via the bundled binary against the mounted data-dir
    (e.g. `docker compose run ... signal-cli -a +<num> register --captcha
    <token>`), and that a fresh number requires `--captcha <token>` (plain
    `register` errors demanding the captcha first). The admin value is the ACI
    (UUID), not the phone number, and registration happens BEFORE the wizard's
    step-6 admin prompt.
  - >-
    SETUP_GUIDE.md — backup default dir corrected: the documented default is
    `prod/runtime/backups` (backup.sh: `DEFAULT_BACKUP_DIR="$RUNTIME_DIR/backups"`),
    NOT `/backups`. The cron rotation example targets the SAME directory backups
    are written to (pass the dir explicitly or use the real default) so rotation
    actually matches files.
  - >-
    SETUP_GUIDE.md — de-verbosify: the "set a claim-token, DM it, then unset it"
    instruction appears once (not three times); the ~67-line switch-llm
    worked-examples block is trimmed to the essentials or moved to ADMIN_GUIDE;
    and a ~15-line DEFAULT-path quickstart (SimpleX-only + local Ollama: clone →
    `./prod/setup.sh` → step-6 token → step-8 healthy → DM token → unset) sits at
    the TOP, with the reference/advanced material below it.
  - >-
    ADMIN_GUIDE.md — corrected to match code/spec: (a) the SimpleX claim-token
    bootstrap section is aligned with the current single-use-token model (as
    SETUP_GUIDE already is); (b) the confirm mechanism is the two-step
    `<command> … confirm` keyword resend, NOT a yes/no prompt; (c) `/recover-pool`
    is documented (bare = list pool; `/recover-pool <adapter> <upstream-group-id>`
    = free a slot; bot-admin, DM-only); (d) the two missing `-w` window flags are
    added.
  - >-
    README.md — the fabricated `/news` example command is removed/replaced with a
    real command (no `/news` handler exists).
  - >-
    USER_GUIDE.md — the three optional power-user flags are added as brief notes:
    `/save <uid> [-t personal-tags]`, the `/zcash`|`/monero` exchange sub-verb +
    `--vs <currency>` (usd/eur/czk/btc), and `--page N` on `/saved` / `/list-sources`.
    (Lowest priority; the guide is otherwise accurate and may stay as-is if the
    files_budget is tight.)
  - >-
    Every changed factual claim is verified against ground truth (scripts under
    prod/, docker-compose.yml, Dockerfile.jvm, the *CommandHandler classes,
    application.properties) — not against another doc. No new inaccuracies
    introduced.
  - "No code/config files changed (docs-only); mvn verify N/A by the inert-diff rule — baseline is the fork point."
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main (docs-only ticket; no Java/test/migration files)
spec_refs:
  - "docs/spec/deployment.md §Operator inputs"
  - "docs/spec/commands.md §Admin"
decision_refs:
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

# M1-509: Guide accuracy-v2 + de-verbosify pass across the four root guides

## Context

This ticket replaces the original M1-509 scope ("simple comic + advanced admin
guide split"), whose problem statement went stale. A four-file documentation
audit on 2026-06-30 (README, SETUP_GUIDE, ADMIN_GUIDE, USER_GUIDE — each claim
falsified against the scripts, compose, Dockerfile, command handlers, and spec)
found the guides are ~90% accurate, the default SimpleX-only + local-LLM install
works end-to-end as written, and the original M1-509 premise was largely
out of date:

- SETUP_GUIDE already absorbed the SimpleX claim-token model (M1-506/507) — it
  does NOT still describe the old by-address bootstrap.
- M1-508 (SimpleX inbound codec) is DONE — "bot does not receive on SimpleX" is
  no longer an open issue and must not be documented as one.
- The phantom-admin-row block is a pre-M1-506 migration gotcha, irrelevant to a
  greenfield install.

So the right instrument is NOT a rewrite or a new comic/advanced file pair — it
is a tightly-scoped accuracy pass over the existing four guides, fixing the
enumerable real defects the audit found and trimming the localized verbosity in
SETUP_GUIDE. This is the same class of work as M1-420/421/459/469 (prior guide
fixes); the recurring need for it is the motivation for a future doc-accuracy
build guard (separate ticket, not this one).

## Acceptance

See the YAML `acceptance:` list. In prose: fix the two SETUP_GUIDE WRONGs
(bundled signal-cli framing; `/backups` → `prod/runtime/backups`), add the
Signal docker-register + captcha note, de-verbosify SETUP_GUIDE (single
token-unset instruction, trim/move switch-llm, quickstart at top); align
ADMIN_GUIDE (claim-token section, confirm-keyword mechanism, `/recover-pool`,
two `-w` flags); drop README's fabricated `/news`; add USER_GUIDE's three
optional flags. Every change verified against code/scripts, not against another
doc.

## Notes

- **Audit-sourced.** Every fix traces to a specific finding in the 2026-06-30
  audit with a ground-truth reference (file:line in the scripts/code). Do not
  add fixes the audit did not surface; do not "improve" adjacent prose.
- **Docs must describe what IS.** All four corrected behaviors (bundled
  signal-cli, runtime/backups default, claim-token bootstrap, `… confirm`
  keyword) already exist in the shipped scripts/code — the guides are wrong, the
  code is right. Never change code to match a guide.
- **Priority order if the budget is tight:** SETUP_GUIDE Signal + backup
  (install-breaking) > ADMIN_GUIDE confirm + claim-token (operator-misleading) >
  README `/news` > de-verbosify > USER_GUIDE optional flags.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-509-*.md
```
