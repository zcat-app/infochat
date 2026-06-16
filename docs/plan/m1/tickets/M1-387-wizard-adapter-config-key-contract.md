---
id: M1-387
title: "wizard 6-adapter.sh + design §7.4: emit the adapter config keys the Provider actually reads (binary/data-dir/account), drop the never-read url/session-token/identity-dir"
status: pending
created: 2026-06-16
last_updated: 2026-06-16
blocked_by: []
files_budget: 4
files_scope:
  - prod/scripts/6-adapter.sh
  - docker-compose.yml
  - prod/config/secrets.env.example
  - docs/design/07-deployment.md
complexity: medium
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The Java adapter config contract (SimpleXConfig / SignalConfig / ProductionAdapterBeans) — it is the canonical, shipped, tested contract this ticket aligns the wizard and design TO. No Java change.
  - Shipping the simplex-chat / signal-cli binaries into the Provider image (M1-388) — required for an adapter to actually launch, tracked separately. This ticket only fixes the property names so the launch is even attempted with the right config.
  - The non-empty bootstrap-admin union gate (§7.6.3) and the bootstrap-admin-contact-id capture — unchanged; only the adapter-connection keys are corrected.
acceptance:
  - "For an enabled simplex adapter, 6-adapter.sh writes infochat.adapters.simplex.binary, infochat.adapters.simplex.data-dir, and infochat.adapters.simplex.ws-port (the keys ProductionAdapterBeans reads via SimpleXConfig.BINARY_KEY / DATA_DIR_KEY / WS_PORT_KEY) and no longer writes infochat.adapters.simplex.url or infochat.adapters.simplex.session-token. After a run, `grep -E 'infochat\\.adapters\\.simplex\\.(binary|data-dir|ws-port)=' <runtime application.properties>` matches all three and `grep -E 'infochat\\.adapters\\.simplex\\.(url|session-token)=' <runtime application.properties>` matches nothing."
  - "For an enabled signal adapter, 6-adapter.sh writes infochat.adapters.signal.binary, infochat.adapters.signal.data-dir, and infochat.adapters.signal.account (the keys ProductionAdapterBeans reads) and no longer writes the unread infochat.adapters.signal.identity-dir; the required signal account identifier is captured from the operator. `grep -E 'infochat\\.adapters\\.signal\\.(binary|data-dir|account)=' <runtime application.properties>` matches all three; `grep 'infochat\\.adapters\\.signal\\.identity-dir=' <runtime application.properties>` matches nothing."
  - "The SIMPLEX_SESSION_TOKEN capture is removed from 6-adapter.sh and from the docker-compose.yml infochat-provider environment passthrough; `grep -rn SIMPLEX_SESSION_TOKEN infochat-*/src` returns nothing (confirming no code ever consumed it), and `grep -rn SIMPLEX_SESSION_TOKEN prod docker-compose.yml` returns nothing."
  - "prod/config/secrets.env.example documents the per-adapter bootstrap-admin-contact-id vars 6-adapter.sh actually appends (INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID / INFOCHAT_SIGNAL_ADMIN_CONTACT_ID)."
  - "docs/design/07-deployment.md §7.4 adapter block is rewritten to the implemented key shape (simplex.binary/data-dir/ws-port; signal.binary/data-dir/account/endpoint) and the §7.7.2 step-6 table no longer says 'capture ... SIMPLEX_SESSION_TOKEN'."
  - "prod/scripts/6-adapter.sh passes `bash -n`; `docker compose --profile prod config` exits 0; `mvn -B verify` from the repo root exits 0."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/07-deployment.md §7.4 Canonical
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/design/07-deployment.md §7.6.3 Bootstrap admin
decision_refs:
  - D46
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-387: wizard adapter config-key contract reconciliation

## Context

Re-verified at source 2026-06-16. The wizard's step 6 writes adapter
configuration under property keys that **no code reads**, so the Provider
refuses to start every production adapter.

The Provider's bean factory `ProductionAdapterBeans`
(`infochat-provider/.../messaging/ProductionAdapterBeans.java:90-123`) reads:

- SimpleX: `infochat.adapters.simplex.binary` (required), `.data-dir`
  (required), `.ws-port` (default 5225) — via `SimpleXConfig.BINARY_KEY` /
  `DATA_DIR_KEY` / `WS_PORT_KEY`. `SimpleXConfig.validate()` throws
  *"infochat.adapters.simplex.binary must be set for an activated simplex
  adapter"* when binary is absent.
- Signal: `infochat.adapters.signal.binary` (required), `.data-dir`
  (required), `.account` (required, non-empty), `.endpoint`
  (default 127.0.0.1:7654), `.allow-non-loopback-endpoint`.

`6-adapter.sh` instead writes `infochat.adapters.simplex.url`,
`.session-token`, `.identity-dir`, and `infochat.adapters.signal.identity-dir`
(`6-adapter.sh:200-211`). `grep -rn` confirms **no Java reads** `simplex.url`,
`session-token`, or `identity-dir`. So both adapters get binary/data-dir/account
unset and fail their `validate()` at startup.

**Root cause is a design/code divergence, not just a script slip.** The
deployment design itself specifies the stale shape — `07-deployment.md:187-197`
lists `simplex.url` / `simplex.session-token` / `simplex.identity-dir` /
`signal.identity-dir`, and the §7.7.2 step-6 table tells the wizard to "capture
... `SIMPLEX_SESSION_TOKEN`". The wizard faithfully implemented that design. The
adapter code (M1-105 / M1-107) shipped a different, tested contract
(binary/data-dir/account/ws-port/endpoint). This ticket aligns the **design and
the wizard** to the implemented code — the proportionate fix, since the adapters
are shipped, tested, and NullAway-clean.

The `SIMPLEX_SESSION_TOKEN` secret is a phantom: SimpleX has no session-token
concept (the bot identity lives in its `data-dir`; `simplex-chat` is spawned as
a subprocess). The wizard prompts for it as a *required hidden input*, so the
current flow blocks on a value that is then dropped.

This ticket does NOT make an adapter actually launch — that additionally needs
the `simplex-chat` / `signal-cli` binaries present at the configured `.binary`
path (M1-388). It makes the launch be attempted with config the code can read.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- The value the wizard previously captured as `identity-dir` IS the adapter's
  `data-dir`; write it under `.data-dir`. The default binary paths follow the
  adapters' own documentary config reference: `simplex.binary` defaults to
  `/usr/local/bin/simplex-chat`, `signal.binary` to `/usr/local/bin/signal-cli`.
  Signal `.account` is the signal-cli account identifier (the registered phone
  number) captured in step 6.
- Inside the Provider container `simplex-chat` runs as a same-container
  subprocess, so `ws-port` (loopback) is correct here — unlike the LLM endpoints
  (4-llm.sh), this is NOT a localhost-vs-service-name case.
- Keep the existing idempotent drop-then-append `set_prop` pattern and the
  §7.6.3 admin-union gate exactly as they are; only the per-adapter key names
  and the session-token capture change.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-387-*.md
```
