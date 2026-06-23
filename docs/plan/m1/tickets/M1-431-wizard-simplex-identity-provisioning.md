---
id: M1-431
title: "Wizard auto-provisions the SimpleX bot identity (profile + address + auto-accept)"
status: done
created: 2026-06-23
last_updated: 2026-06-23
clarity_check:
  date: 2026-06-23
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-431.md
reviews:
  - round: 1
    date: 2026-06-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 534
      removed: 60
redteam_findings: []
redteam_audits:
  - date: 2026-06-23
    verdict: CLEAN
    base: c64cacf53abf2cba1a5d75d06fa0c0bc251ebe41
    head: working-tree (uncommitted branch impl)
    verdict_file: docs/plan/m1/redteam/M1-431-2026-06-23.md
    out_of_model_count: 1
    note: |
      Pre-merge adversarial review of the auto-accept enablement + spec
      carve-out (operator-requested, security_relevant). CLEAN. One
      out-of-model advisory: 6b passes the full --env-file "$SECRETS_FILE" to
      `docker compose run` (same as 7-apps.sh build/up). Within the trusted
      operator boundary (security.md:29) and required so compose interpolates
      ${INFOCHAT_SIMPLEX_DATA_DIR} into the data-dir mount; no action.
blocked_by: []
files_budget: 11
files_scope:
  - docs/spec/deployment.md
  - docs/design/07-deployment.md
  - prod/setup.sh
  - prod/scripts/6-adapter.sh
  - prod/scripts/7-apps.sh
  - prod/scripts/6b-simplex-provision.sh
  - SETUP_GUIDE.md
  - README.md
  - docker-compose.yml
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXProvisioningWiringTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
spec_amend_for: docs/spec/deployment.md §Operator inputs (item 7, Messaging adapter configuration)
out_of_scope:
  - The runtime SimpleXAdapter / SimpleXSubprocess behaviour — UNCHANGED. The adapter at start() still only queries `/show_address` (read) and FAILS if no well-formed address exists (SimpleXAdapter.deriveAndAdoptIdentity / adoptBotQueueAddress). This ticket MUST NOT make the running Provider create, mint, or auto-accept identity. Provisioning is operator-run wizard tooling that executes BEFORE the Provider container starts; the runtime invariant of deployment.md item 7 stays intact.
  - Signal provisioning automation — signal-cli register/verify stays manual (the captcha step is interactive and cannot be one-shot scripted). SETUP_GUIDE's Signal section is unchanged.
  - The bootstrap-admin-contact-id flow — the operator's OWN SimpleX address (the value 6-adapter.sh prompts for as INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID, consumed by AdminBootstrap) is separate from the bot's address and is unchanged here.
  - SimpleXMessageCodec / the adapter's WebSocket command set — not touched; provisioning runs the simplex-chat CLI via `-e`, not the adapter's WS API.
  - Backwards-compat / migration for any prior on-disk SimpleX identity — NONE. M1 is greenfield and (pre-M1-429) the adapter never launched, so there is no prior working identity to preserve. Do not add a shim.
  - Broad data-dir uid/ownership rework — provisioning only needs the configured data-dir to exist and be writable by the container uid (same failure class as the M1 GGUF-volume fix). Anything beyond making provisioning succeed is a separate ticket.
acceptance:
  - "docs/spec/deployment.md item 7 is amended to distinguish the RUNTIME adapter (which still must not synthesize identity — it reads its identity material at startup and fails if absent) from OPERATOR-RUN WIZARD PROVISIONING (which may create the bot profile from an operator-supplied display name, create the contact address, and enable auto-accept, against the operator-owned data-dir). The existing sentence that the runtime Provider does not synthesize identity is preserved verbatim and the new wizard carve-out is additive."
  - "prod/scripts/6-adapter.sh prompts the operator for the SimpleX bot DISPLAY NAME during the SimpleX branch (alongside binary path / data-dir / ws-port), and persists it for the provisioning step. The bootstrap-admin-contact-id prompt is unchanged."
  - "A provisioning step runs BEFORE the Provider container starts and AFTER the Provider image is built, executing the baked image binary against the SAME mounted data-dir and the SAME `<data-dir>/simplex_v1` path prefix that SimpleXSubprocess.commandFor uses (e.g. `docker compose run --rm --entrypoint /usr/local/bin/simplex-chat infochat-provider -d <data-dir>/simplex_v1 -y ...`), issuing in order: profile create via `--create-bot-display-name <operator-name>`, then `-e \"/ad\"`, then `-e \"/auto_accept on\"`."
  - "Provisioning is idempotent: a second run does NOT rotate or replace an existing profile or address. (Relies on the verified spike behaviour: `--create-bot-display-name` is a no-op when a profile exists, and `/ad` is a no-op — `you already have chat address` — when an address exists. See .scratch/simplex-spike-findings.md items 3 and 7.)"
  - "Provisioning success/failure is determined by PARSING STDOUT for `bad chat command` / error markers, NOT by exit code — a malformed simplex-chat command still exits 0 (spike item 5). A provisioning failure aborts the wizard with a clear message and does not start the Provider."
  - "The bot's contact link (from `/ad` / `/show_address`) is surfaced to the operator so the bootstrap admin can connect to the bot. The raw link is NOT written to application.properties, secrets.env, or any operational log (D37); it is displayed transiently to the operator only (e.g. in setup.sh's handoff)."
  - "SETUP_GUIDE.md no longer instructs the operator to manually run `simplex-chat`, `/ad`, and `/auto_accept on` for the happy path; the SimpleX prerequisite shrinks to installing/locating the binary (which the image already bakes) and choosing a display name. Any residual manual instruction that keeps a `-d` example uses the correct `<data-dir>/simplex_v1` prefix the container launches with — the broken `-d /path/to/bot-data` form (which writes identity to a path the Provider cannot see) is removed."
  - "README.md §Quick setup is updated so its 'one script, a few chat messages, no programming' framing is accurate: the SimpleX happy path no longer requires a manual out-of-band identity step before the wizard."
  - "A new SimpleXProvisioningWiringTest (mirroring LlamacppWiringTest) drives the REAL provisioning script with a fake `simplex-chat` (and, if invoked, fake `docker`) on PATH and asserts: (1) the three provisioning commands are issued with the in-dir `<data-dir>/simplex_v1` prefix; (2) a second invocation is a no-op against an already-provisioned fake; (3) a stdout `bad chat command` marker is treated as failure despite exit 0. Test passes."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXProvisioningWiringTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Operator inputs
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/security.md §Trust boundaries
decision_refs:
  - D10
  - D37
  - D44
  - D46
---

# M1-431: Wizard auto-provisions the SimpleX bot identity (profile + address + auto-accept)

## Context

Today the SimpleX bot identity is created entirely by hand: the operator must run
`simplex-chat` out-of-band, pick a display name, type `/ad` to mint a contact
address, type `/auto_accept on`, and copy the link — all before the wizard is
usable. The adapter only ever *reads* that identity at startup
(`SimpleXMessageCodec` exposes exactly three commands — `/_send`, `/_update`,
`/show_address`; there is no create-address, no `/auto_accept`, and no inbound
contact-request handler), so a missing identity makes the adapter fail to start.
That manual wall is the single biggest onboarding friction and is the one place
README.md's "no programming" framing is untrue.

A VPS spike against the exact pinned simplex-chat v6.5.4 binary
(`.scratch/simplex-spike-findings.md`, 2026-06-22) empirically proved the whole
thing is automatable: `--create-bot-display-name`, `-e "/ad"`, and
`-e "/auto_accept on"` are one-shot, persist in the data-dir, and are idempotent.
M1-429 banked the two launch-flag fixes that spike incidentally surfaced
(`--network`→`-p`, in-dir `-d` prefix) but **explicitly deferred the provisioning
automation itself to "a separate future ticket, blocked on the deployment-item-7
policy decision."** That ticket was never filed. This is it.

The policy decision (operator: 2026-06-23): full automation including profile
creation is desired. deployment.md item 7's "Provider does not synthesize bot
identity" is preserved as a RUNTIME invariant; this ticket adds a carve-out that
operator-run wizard provisioning (operator-supplied display name, keypair written
to the operator-owned data-dir) is not the running Provider synthesizing identity.
Auto-accept is safe: it only opens the SMP transport connection; the application
invite/registration gate (D44, InviteCodeConsumer at intake) still rejects any
un-invited contact, independent of transport accept.

## Acceptance

See the YAML `acceptance:` list. In prose, "done" means: the spec carve-out lands
(runtime invariant preserved); the wizard prompts for a display name and, before
the Provider starts, provisions profile + address + auto-accept idempotently
against the same `<data-dir>/simplex_v1` prefix the Provider launches with,
parsing stdout (not exit code) for failure; the bot's contact link is surfaced to
the operator but never persisted/logged; SETUP_GUIDE and README are reconciled to
the now-automated reality (including removing the broken `-d /path/to/bot-data`
instruction); a wiring test pins the provisioning commands and idempotency; and
`mvn -B clean verify` is green.

## Out-of-scope

See the YAML `out_of_scope:` list. The load-bearing exclusion: **the runtime
adapter must not change.** SimpleXAdapter.start() still derives the identity
read-only via `/show_address` and throws if it is absent — provisioning is a
distinct, operator-run, pre-startup step. Signal provisioning stays manual (interactive
captcha). The operator's own bootstrap-admin contact id is unchanged. Do not add a
backwards-compat shim for any prior on-disk identity (greenfield).

## Notes

- **Where provisioning runs.** The simplex-chat binary is baked only into the
  Provider image (`infochat-provider/.../Dockerfile.jvm`), not assumed on the host.
  So provisioning must execute that binary against the bind-mounted data-dir, e.g.
  `docker compose run --rm --entrypoint /usr/local/bin/simplex-chat infochat-provider …`,
  after `7-apps.sh` builds the image but before the Provider service comes up. The
  implementer may add a dedicated `prod/scripts/6b-simplex-provision.sh` and call it
  from `setup.sh`/`7-apps.sh`, or inline it; either is in budget. `complexity: high`
  triggers the plan-writer to settle the placement before code.
- **The prefix trap.** Provisioning MUST use `<data-dir>/simplex_v1` (matching
  `SimpleXSubprocess.commandFor`, M1-429). A bare `-d <data-dir>` writes the identity
  DBs as siblings OUTSIDE the directory (spike item 2) where the Provider cannot see
  them — this is exactly the latent bug in SETUP_GUIDE today.
- **Stdout, not exit code.** A bad simplex-chat command exits 0 (spike item 5); the
  script must grep stdout for `bad chat command`/error. `-y` is required so first-run
  migrations don't block.
- **Test pattern.** Mirror the existing `LlamacppWiringTest` (the llm-adapter
  wizard-wiring test): drive the real script with a fake binary on PATH and assert the
  generated commands. Linux-gated (`ProcessBuilder` + GNU sed/bash), per the existing
  wizard-wiring precedent.
- **Security review.** `security_relevant: true` — the spec carve-out and the
  auto-accept enablement should go through `/redteam` before merge (the operator asked
  to redteam the design first). Auto-accept-does-not-bypass-invite is code-verified
  (DevTerminalHarness.java:34 documents the intake chain; InviteCodeConsumer enforces
  it); D37 (never log the raw queue address) constrains how the link is surfaced.
- **Alternative considered.** Splitting the deployment.md amendment into its own
  `spec_amend_for` ticket ahead of the code. Rejected for now: the carve-out is small
  and tightly coupled to the wizard behaviour it authorizes, and CLAUDE.md permits
  "spec coordinated with code" in one ticket. If clarity/redteam prefers the split, the
  amendment can be lifted out and this ticket made its `spec_amend_parent`.
- Relevant design note: `docs/design/07-deployment.md` (wizard step sequencing). The
  empirical spike evidence (verbatim simplex-chat v6.5.4 command outputs) lives in the
  2026-06-22 SimpleX provisioning spike findings under the repo's scratch area.
