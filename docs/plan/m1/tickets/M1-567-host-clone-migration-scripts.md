---
id: M1-567
title: Host-clone migration scripts (prod/scripts/pack.sh + restore.sh)
status: draft
created: 2026-07-05
last_updated: 2026-07-05
blocked_by: []
files_budget: 6
files_scope:
  - prod/scripts/pack.sh
  - prod/scripts/restore.sh
  - docs/design/07-deployment.md
  - SETUP_GUIDE.md
complexity: high
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    prod/scripts/backup.sh contract. backup.sh stays the cron-invoked upkeep
    wrapper that DELIBERATELY excludes config/secrets (§7.10, M1-427). This
    ticket does NOT change what backup.sh emits or how the crontab calls it;
    pack.sh is a separate, migration-purpose superset. pack.sh MAY reuse
    backup.sh's pg_dump/tar internals (plan decides factor-out vs duplicate),
    but backup.sh's two-artifact output and retention story are untouched.
  - >-
    prod/setup.sh wizard step list. restore.sh is an ALTERNATIVE to a fresh
    wizard run, not a wizard step — the orchestrator never registers it
    (exactly as backup.sh/upgrade.sh are standalone, §7.7.1). Do not add a
    STEPS entry or change the wizard flow.
  - >-
    prod/scripts/upgrade.sh. Same-host rebuild-in-place; unrelated to a
    cross-device clone. Untouched.
  - >-
    Auto-deleting / tearing down the SOURCE host. pack.sh is READ-ONLY and
    mutates nothing — deleting the source at pack time is a backup footgun (a
    corrupt bundle or a failed bring-up on the target would leave zero working
    copies). Decommissioning the old host is a SEPARATE, post-verification
    operator step using the EXISTING tools (`apps.sh stop`, then optionally
    `setup.sh --reset --hard`); this ticket adds no teardown/delete script and
    pack.sh never removes source data.
  - >-
    Data-dir PATH REWRITING. v1 assumes the clone reconstructs identity
    data-dirs at the SAME absolute paths (the tar is stored/extracted relative
    to `/`, mirroring backup.sh + the §7.10 restore step). Relocating the
    deployment to a DIFFERENT absolute path (rewriting
    infochat.adapters.*.data-dir + INFOCHAT_*_DATA_DIR and extracting
    elsewhere) is a follow-up, not this ticket. restore.sh must FAIL LOUD on a
    path mismatch, not silently half-restore.
  - >-
    Encryption-at-rest of the bundle as a hard requirement. Per D34/§7.10
    encryption stays the operator's responsibility; pack.sh writes 0600 and
    warns loudly. An OPTIONAL age/gpg pass-through is a plan question, not a
    mandate — do not build a bespoke crypto layer.
  - >-
    LLM model bytes in the bundle. Models are NOT packed (§7.10 "Models — not
    backed up"); restore RE-fetches them from the restored backend config
    (idempotent ollama pull / llama.cpp GGUF fetch; remote = nothing). Do not
    add multi-GB model blobs to the archive.
  - >-
    DB schema migrations. The restore is a pg_restore of DATA+schema from the
    custom-format dump into a FRESH database; no Flyway migration is authored
    or changed (migration_touch:false). The only ordering constraint is
    "restore before the Collector's first Flyway pass" (see acceptance).
  - >-
    infochat-collector / infochat-provider Java. No application code changes;
    this is shell + docs only.
acceptance:
  - >-
    prod/scripts/pack.sh [OUT_DIR] produces a SINGLE transferable archive
    containing everything needed to reconstruct the deployment on another
    host: (a) the `infochat` DB as pg_dump -F c (custom format, pg_restore-able,
    includes the audit log); (b) every CONFIGURED adapter identity data-dir
    (SimpleX queue keypair + signal-cli account tree) with file modes preserved;
    (c) prod/runtime/application.properties; (d) prod/runtime/secrets.env;
    (e) the bootstrap files (bootstrap-sources.json, and bootstrap-assets.json
    if present). Script is executable, starts `set -euo pipefail`, passes
    shellcheck clean, and follows the existing prod/scripts conventions
    (SCRIPT_DIR/PROD_DIR/REPO_ROOT resolution, --env-file for compose, never
    `source` secrets.env — M1-389/M1-397). A configured adapter whose data-dir
    is missing fails the run loudly (mirrors backup.sh), never packs an empty
    identity.
  - >-
    The bundle is treated as SECRET material: created 0600, and pack.sh prints
    a prominent warning that it contains DB passwords, the LLM API key, the
    full audit log, and the UNRECOVERABLE per-adapter identity keys — transfer
    and store it encrypted (D34/§7.10). This is the single highest-value
    artifact the system can emit; the warning names each sensitive class.
  - >-
    prod/scripts/restore.sh <bundle> on a fresh host (Docker + a clean checkout
    at the SAME absolute repo path) reconstructs the exact clone: unpacks the
    archive; places application.properties + secrets.env + bootstrap files into
    prod/runtime/ (0600 on secrets.env); restores each adapter identity data-dir
    with modes preserved; starts Postgres (roles created from the restored
    secrets.env passwords); pg_restores the DB into the FRESH database BEFORE the
    Collector's first Flyway pass (so the dump's schema does not collide with a
    Flyway-migrated empty DB); re-provisions models per the restored backend
    config (idempotent: `ollama pull` / llama.cpp GGUF fetch; remote backend =
    no model step); then starts Collector, waits healthy, starts Provider; then
    runs the §7.10 verification. Net effect: the DB and both messaging identities
    are already populated — only models are (re)downloaded, matching the ticket's
    stated goal.
  - >-
    restore.sh fails LOUD and early at each precondition rather than
    half-restoring: missing/corrupt bundle, an identity data-dir path in the
    restored config that does not match where the tar would land (the v1
    same-absolute-path constraint), a secrets.env whose DB password would not
    match an already-initialized Postgres volume, or a non-empty target DB.
    Each failure names the actionable fix.
  - >-
    §7.10 (Backups) — or a new §7.12 "Migrating to another device" — documents
    the pack/restore pair as the supported host-clone procedure, superseding the
    manual step list currently in the §7.10 restore prose (which stays as the
    under-the-hood description). SETUP_GUIDE.md gains a short Advanced
    subsection "Migrating to another device" pointing at the two scripts and the
    same-absolute-path constraint, cross-linked with the "Bot chat identity"
    subsection (the identity dirs are what make the clone the SAME bot).
  - >-
    The migration docs state the SINGLE-OWNER cutover invariant and its
    ordering: exactly ONE instance may own the messaging identity at a time, so
    the source instance must stop touching Signal/SimpleX before the clone
    connects, and the old host is decommissioned ONLY AFTER the clone is
    verified healthy. The docs make clear this is NOT enforced by the M1-009
    advisory lock (that lock is per-DATABASE; a clone has its own restored DB,
    so the lock does not span the two hosts) — it is a messaging-identity
    constraint the operator must observe (Signal one-primary-device; SimpleX
    single queue owner), and the safe sequence is stop-source → pack → transfer
    → restore+verify → decommission-source.
  - >-
    mvn verify is green. (No Java changes; this confirms no regression. If the
    plan elects a ProcessBuilder/wiring harness for restore.sh — cf. the M1-418
    SwitchLlmWiringTest precedent — its new test lands under files_budget.)
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main (no Java touched)
  notes:
    - >-
      Test approach is a PLAN question. backup.sh (M1-427) shipped without a
      JUnit harness as "a thin pg_dump+tar wrapper". restore.sh is heavier
      (DB-restore ordering + model rehydration + path/precondition gates), so
      the plan decides between (a) shellcheck + a documented manual
      round-trip verification, and (b) a gated wiring test in the M1-418 style.
      Any real end-to-end pack→transfer→restore round trip is host validation
      (like M1-566's post-merge live step), not part of `mvn verify`.
spec_refs:
  - docs/spec/deployment.md §Backups, rotation, secrets
  - docs/spec/deployment.md §Deployment scenarios
decision_refs:
  - D34
  - D46
---

# M1-567: Host-clone migration scripts (prod/scripts/pack.sh + restore.sh)

## Context

Operator request (2026-07-05): migrate a running deployment to another
machine as an exact clone. Today the primitives exist but there is **no
turnkey path**:

- `prod/scripts/backup.sh` (M1-427) emits a DB dump + an adapter-identity
  tar, but **deliberately excludes** `application.properties` and
  `secrets.env` (§7.10 keeps those in the operator's config repo). So its
  output alone cannot reconstruct a host — no DB passwords, no admin/LLM
  config.
- Restore is **documented prose only** (§7.10 steps 1–5): stop, `pg_restore`
  into a fresh DB, untar the identity dirs preserving modes, start
  Collector→Provider, verify. No script mirrors it.
- `prod/scripts/upgrade.sh` is same-host rebuild-in-place — no help for a
  device move.

The manual sequence also has sharp edges an operator hits blind: config +
secrets must be copied separately from the backup; the DB must be restored
**before** the Collector's first Flyway pass or the dump's schema collides
with a Flyway-migrated empty DB; the identity tar is stored at **absolute**
paths (so the clone must use the same repo path/username); and models must be
re-pulled because they are not backed up.

The desired shape (operator's words): **one `pack` script bundles all
necessary state into a single package; transfer it; one `restore` script on
the target unpacks and stands the clone up — the DB and the SimpleX/Signal
identities are already in place, so all that remains is (re)downloading
models.** The identity material is exactly what makes the clone the *same*
bot (same Signal number without re-registration, same SimpleX contact link
so nobody reconnects) — see the SETUP_GUIDE "Bot chat identity" subsection.

## Acceptance

See frontmatter. In one line: `pack.sh` → single 0600 archive of DB dump +
identity dirs + config + secrets + bootstrap files (loudly flagged as secret);
`restore.sh` → reconstruct config/secrets/identities, `pg_restore` into a
fresh DB before Flyway, re-provision models from the restored backend config,
start and verify; docs updated; `mvn verify` green.

## Out-of-scope

See frontmatter. Notably: backup.sh's contract, the wizard step list,
upgrade.sh, path-rewriting to a different absolute location (v1 requires the
same paths and fails loud otherwise), mandatory bundle encryption (operator
responsibility per D34; optional age/gpg is a plan question), and packing
model bytes (re-fetched on restore).

## Notes / open design questions (for the plan sidecar)

- **Reuse vs duplicate backup.sh internals.** pack.sh needs the same pg_dump
  and identity-tar logic backup.sh already has, plus config/secrets/bootstrap
  and a single-archive wrap. Plan decides: factor a shared helper both call,
  or keep pack.sh independent. Constraint: do not regress backup.sh's cron
  contract.
- **Model rehydration is the crux.** `ollama pull` and the llama.cpp
  `fetch_gguf` in 4-llm.sh are already idempotent; the remote backend needs no
  model step. Options: restore.sh invokes a re-provision-from-existing-config
  path (a subset of 4-llm.sh that pulls/fetches without re-writing config,
  since config is already restored), or the plan factors that model side out
  of 4-llm.sh into a shared function. Avoid re-running the full interactive
  4-llm.sh (it would re-prompt and re-write the restored config).
- **DB-restore ordering.** The Collector runs Flyway at first boot
  (`migrate-at-start=true`, §7.7 topology). restore.sh must `pg_restore` into
  the fresh DB before the Collector ever starts, so the restore path brings up
  Postgres alone (like 3-postgres.sh), restores, THEN starts the apps —
  it cannot lean on the normal 7-apps.sh ordering unmodified.
- **Precondition gates (system boundary — validate here).** Corrupt/missing
  bundle; target DB already populated; secrets.env DB password vs an
  already-initialized pgdata volume; identity-dir absolute-path mismatch. Each
  is an operator-facing failure with an actionable message, not a silent
  half-restore.
- **Security posture.** The bundle is the highest-value artifact the system
  emits (all secrets + audit log + irreplaceable keys). 0600, loud warning,
  and a redteam pass on this ticket (security_relevant:true). Do not log the
  bundle path contents; mirror backup.sh's no-secret-on-host-env discipline.
- **Relationship to M1-427 (done):** sibling backup script; this is the
  migration superset + the missing restore half. Not blocked_by (M1-427 is
  merged).
- **Single-owner cutover — the real coexistence constraint (operator asked).**
  Old + new hosts must never run against the messaging identity at once, but
  the M1-009 advisory lock does NOT protect against it: that lock is scoped to
  a single Postgres database, and the clone restores into its OWN database, so
  two hosts hold two independent locks and both would start. The binding
  constraint is the identity itself — Signal treats signal-cli as the account's
  single primary device, and a SimpleX queue has one legitimate owner; two
  live consumers corrupt session / ratchet state. The mitigation is
  operational, not a lock: stop the source before the clone connects, and
  decommission the source only after the clone is verified. pack.sh stays
  read-only precisely so it is safe to pack a still-running source and cut over
  deliberately, rather than being forced to destroy the source to pack it.
