---
id: M1-572
title: operator-invoked shred-bundle.sh for safe disposal of pack.sh bundles
status: pending
created: 2026-07-05
last_updated: 2026-07-05
blocked_by: []
files_budget: 4
files_scope:
  - prod/scripts/shred-bundle.sh
  - prod/scripts/pack.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/ShredBundleWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
security_relevant: true
migration_touch: false
round_cap: 2
out_of_scope:
  - >-
    Auto-shredding inside restore.sh, or any time-based / cron auto-disposal. The
    bundle is a disaster-recovery FALLBACK — restore.sh's own cutover note says to
    decommission the source only AFTER the clone verifies healthy. Destroying the
    only backup the moment restore finishes fights recovery. Disposal stays a
    deliberate, operator-timed act; this ticket only makes that act safe and
    one-command. Do NOT add any automatic invocation.
  - >-
    backup.sh and its upkeep archives. backup.sh DELIBERATELY excludes config +
    secrets (§7.10), so its archives are not full-secret material and need no
    secure-disposal helper. Do not touch backup.sh.
  - >-
    Encryption-at-rest / in-transit for the bundle (age/gpg). pack.sh already
    delegates TRANSFER and STORAGE encryption to the operator (D34). This ticket
    is about DISPOSAL of a bundle once its purpose is served, not protecting it in
    flight. Do not add encryption.
  - >-
    Shredding of the NON-secret round-trip records (golden-snapshot.txt, the
    restore logs/markers). The helper targets bundles and recovery secret-material
    directories; deciding what non-secret logs to keep stays a manual operator
    call (a log may need a secret-leak scan first). The helper must not sweep
    arbitrary sibling files it was not pointed at.
  - >-
    prod/scripts/restore.sh. It is the consumer of a bundle, not its disposer, and
    was just revalidated by M1-570. It gets no shred logic (see the first item).
acceptance:
  - >-
    New prod/scripts/shred-bundle.sh <target> securely disposes of a pack.sh
    bundle. For a regular file it runs `shred -uz <file>`; for a directory it runs
    `find <dir> -type f -exec shred -uz {} +` then `rm -rf <dir>`, so every file
    is overwritten-then-removed and no empty tree is left. Runs under the operator
    account with no docker/root — pack.sh writes bundles 0600 operator-owned under
    a 077 umask, and the safety-copy members (db-independent dump, identities
    tarball, raw-config/) are likewise operator-owned regular files.
  - >-
    PATH GUARD (load-bearing — this is a destructive tool): the script REFUSES
    (nonzero exit, nothing removed) when the resolved target is a nonexistent
    path, `/`, the invoking user's $HOME, or the repo root. It acts only when the
    target is a regular `*.tgz` file OR a directory whose immediate contents are
    pack.sh bundle / recovery material (at least one `*.tgz`, or the safety-copy
    members identities*.tgz / *.pgc / raw-config/). A target that matches none of
    these shapes is refused rather than shredded.
  - >-
    CONFIRMATION: destruction requires explicit consent — a `--yes`/`-y` flag for
    unattended use, or an interactive y/N prompt (defaulting to No) when run on a
    TTY without the flag. Before destroying, the script prints the resolved
    absolute target and a one-line inventory (file count / total size) so the
    operator sees exactly what will be irreversibly overwritten.
  - >-
    pack.sh's WARNING block (the `cat >&2 <<WARN … WARN` heredoc, currently ending
    at "encryption for TRANSFER and STORAGE is YOUR responsibility (D34/§7.10)")
    gains one line naming the disposal step: when the bundle has served its
    purpose, dispose of it with `prod/scripts/shred-bundle.sh <bundle>`. No other
    pack.sh behavior changes.
  - >-
    mvn verify is green. A new ShredBundleWiringTest (JUnit, shelling out to the
    script the way RestoreWiringTest shells restore.sh) pins: (a) the guard
    REFUSES a dangerous/ineligible target (the repo root and a nonexistent path)
    with a nonzero exit and leaves a control fixture untouched; (b) a `--yes` run
    on a fixture bundle FILE removes it, and on a fixture recovery DIRECTORY
    (containing a `*.tgz` plus a raw-config/secrets.env) removes the whole tree.
    Uses a JUnit @TempDir fixture — no real bundle, no docker, no network.
  - >-
    docs/design/07-deployment.md §7.10.1 ("Migrating to another device") documents
    shred-bundle.sh as the closing step of the migration lifecycle (pack →
    transfer → restore → verify → DISPOSE), and states the deliberate
    non-automation (disposal is operator-timed, never auto-run by restore.sh),
    mirroring the out_of_scope rationale.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/ShredBundleWiringTest.java
  modifies: []
  preserves:
    - all tests currently green on main
    - >-
      the existing RestoreWiringTest gate cases (M1-568 allowlist, M1-569
      privileged untar, M1-570 role-before-pg_restore, M1-571 custom-GGUF
      recovery) — this ticket adds a sibling script + test and does not touch
      restore.sh.
  notes:
    - >-
      The test exercises real shred/rm on JUnit @TempDir fixtures (fast, local, no
      docker) — unlike the restore round-trip, secure disposal has no multi-GB or
      privileged step, so the behavior is fully unit-testable rather than
      host-only. Assert both the refusal path (guard) and the success path
      (file + directory fixtures gone).
    - >-
      shred(1) on a copy-on-write or journaled FS does not guarantee the old
      blocks are unrecoverable; document that caveat in the script header (same
      class of caveat as pack.sh's "encryption is your responsibility"). It does
      not change the acceptance — overwrite-then-remove is the best-effort disposal
      this helper standardizes; full-disk encryption is the real guarantee.
spec_refs:
  - docs/design/07-deployment.md §7.10.1
decision_refs:
  - D34
reviews: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check: {}
---

# M1-572: operator-invoked shred-bundle.sh for safe disposal of pack.sh bundles

## Context

`pack.sh` emits the single highest-value artifact this system produces: a 0600
bundle carrying the DB passwords, the (optional) remote LLM API key, the full
audit log inside the DB dump, and the UNRECOVERABLE per-adapter identity keys
(its own WARNING block spells this out). After a migration/round-trip, that
bundle — and any independent safety copy — must be destroyed, not left on disk.

Today there is **no tooling for that disposal**. The operator hand-types a
`find <dir> -type f -exec shred -uz {} +` plus `rm -rf`, which is both fiddly and
dangerous to get wrong (a mistyped path shreds the wrong tree). The M1-567→571
round-trips each ended with this manual dance. pack.sh warns the bundle is secret
but says nothing about how to dispose of it.

## The fix

Add a small, explicit, operator-invoked `prod/scripts/shred-bundle.sh <target>`
that codifies the safe disposal (overwrite-then-remove every file, then drop the
directory), guarded so it can only act on a bundle or a recovery
secret-material directory — never `/`, `$HOME`, or the repo root — and only after
explicit confirmation. Wire pack.sh's WARNING to name it, closing the
pack → transfer → restore → verify → **dispose** lifecycle. Document it in
§7.10.1.

Deliberately NOT automated: disposal never rides along inside restore.sh or a
cron. The bundle is a disaster-recovery fallback; you keep it until the clone is
proven healthy, then destroy it on your own schedule. Auto-destruction the moment
restore finishes would leave you with no bundle exactly when a subtly-broken
restore reveals itself.

## Alternatives considered

- **Auto-shred at the tail of restore.sh** — rejected (see out_of_scope item 1):
  fights the disaster-recovery purpose; the bundle must outlive the restore until
  the clone is verified.
- **A TTL cron that shreds bundles older than N days** — rejected: a background
  destructive process could nuke a bundle mid-migration, and adds a scheduled
  actor to a security-sensitive path for marginal convenience.
- **Fold disposal into backup.sh** — rejected: backup.sh's archives exclude
  secrets (§7.10), so they are not the same class of material and need no secure
  shred.

## Provenance

Surfaced at the close of the M1-570/571 migration round-trip (2026-07-05), when
the round-trip's plaintext bundle + safety copy were shredded by hand. Filing this
turns that recurring hand-run into a guarded one-command step. It remediates no
defect — it adds the missing disposal half of the pack.sh lifecycle.
