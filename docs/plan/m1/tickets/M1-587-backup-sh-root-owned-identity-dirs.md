---
id: M1-587
title: "backup.sh handles root-owned adapter identity dirs (the M1-569 follow-up)"
status: done
created: 2026-07-07
last_updated: 2026-07-07
blocked_by: []
remediates: M1-569
files_budget: 3
files_scope:
  - prod/scripts/backup.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/BackupWiringTest.java
  - docs/design/07-deployment.md
complexity: low
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The Provider-runs-as-root architecture (07-deployment.md:700). That root
    container is the ROOT CAUSE of the root-owned identity dirs, but changing it
    is a large, separate decision — this ticket ADAPTS backup.sh to the existing
    architecture exactly as M1-569 adapted pack/restore, it does not change the
    architecture.
  - >-
    pack.sh and restore.sh. They already run the identity tar/untar as root
    in-container (M1-569); this ticket only brings backup.sh up to that same
    mechanism. Do NOT re-touch them.
  - >-
    The pg_dump path, the DB artifact, retention/pruning (crontab's job, §7.10),
    and the backup-dir precedence logic. This ticket changes ONLY the adapter
    identity tar step (the `tar -C /` at backup.sh:136).
  - >-
    Any new tar-slip / extraction-allowlist machinery. backup.sh only READS the
    identity dirs and writes the tgz to a host file; it never untars onto the
    host, so the M1-568 write-side allowlist is not relevant here. The only
    pack.sh guard that DOES port is the M1-584 pre-mount data-dir check (a colon
    or system-prefix path mis-parses the new `-v` mount spec).
acceptance:
  - >-
    backup.sh captures every CONFIGURED adapter identity dir INCLUDING root-owned,
    owner-only (mode 0700) subtrees — concretely signal-cli/data and
    signal-cli/attachments, which the Provider's root signal-cli daemon writes
    root:root 0700 — WITHOUT requiring the invoking user to be root and WITHOUT
    interactive sudo. Concretely, `upgrade.sh`'s step-1 backup (which calls
    backup.sh as the non-root deploy user) must SUCCEED. Today backup.sh runs
    `tar -C /` as the deploy user and fails `Permission denied` on those dirs,
    which (under upgrade.sh's `set -euo pipefail`) aborts the entire upgrade
    before any container is touched — the live failure that surfaced this ticket
    on 2026-07-07.
  - >-
    The fix uses the SAME root-privileged in-container tar mechanism M1-569
    established for pack.sh (`docker run --rm -u 0:0 --entrypoint tar
    $IDENTITY_TAR_IMAGE -C / -czpf - <rel-paths>`, each configured data-dir
    bind-mounted READ-ONLY at its absolute host path). It is ADAPTER-AGNOSTIC —
    SimpleX and Signal identity dirs both go through the same privileged path,
    with no signal-cli special-case and no reliance on SimpleX's incidental 0644
    mode. The resulting adapters-YYYYMMDD.tgz is written to the host backup dir as
    the invoking (non-root) user via stdout redirection, byte-compatible with the
    existing archive layout so the §7.10 restore path (`tar -xzpf ... -C /`) is
    unchanged.
  - >-
    Because backup.sh now builds a `-v /$rel:/$rel:ro` mount, it applies the same
    M1-584 pre-mount data-dir guard pack.sh uses (reject a data-dir containing a
    colon or a clearly-system prefix before the mount is built), so a malformed
    value cannot silently mis-parse the `-v` spec and corrupt the backup. Faithful
    port of pack.sh's identity-tar block, including that guard.
  - >-
    mvn verify is green. A new BackupWiringTest (mirroring RestoreWiringTest's
    read-the-script source-assertion style) pins that backup.sh's adapter tar runs
    via the M1-569 privileged mechanism (docker run -u 0:0 --entrypoint tar) and
    NOT a bare host `tar -C /`. The REAL root-owned round trip (run backup.sh as
    the deploy user against a live root-owned signal-cli dir -> the tgz contains
    signal-cli/data) remains HOST validation, not mvn verify, exactly as M1-569's
    round trip is.
  - >-
    docs/design/07-deployment.md §7.10.1 is updated: backup.sh now uses the same
    root-privileged in-container tar as pack/restore, and the earlier note that
    "backup.sh shares the same host-side tar model ... (pointer to the separate
    follow-up)" is resolved — this ticket IS that follow-up.
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/BackupWiringTest.java — reads prod/scripts/backup.sh and asserts the adapter identity tar runs via the M1-569 privileged in-container mechanism (docker run -u 0:0 --entrypoint tar) and not a bare host `tar -C /`; mirrors RestoreWiringTest/ShredBundleWiringTest source-assertion style."
  modifies: []
  preserves:
    - all tests currently green on main
    - "RestoreWiringTest's existing M1-569 privileged-untar assertions (restore.sh is untouched by this ticket)."
  notes:
    - >-
      Faithful root-ownership capture cannot be asserted by mvn verify (needs real
      Docker + root-owned fixtures); that is HOST validation. The wiring test pins
      the invocation shape only, mirroring M1-569's RestoreWiringTest scope.
spec_refs:
  - docs/design/07-deployment.md §7.10.1
  - docs/spec/deployment.md §Backups, rotation, secrets
decision_refs:
  - D34
  - D46
reviews:
  - round: 1
    date: 2026-07-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 167
      removed: 13
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-07
    verdict: CLEAN
    base: 70a8985a081f026598c0b432b1672dd68b80b27d
    head: working-tree-pre-commit
    verdict_file: docs/plan/m1/redteam/M1-587-2026-07-07.md
    out_of_model_count: 2
    note: |
      Pre-commit --in-progress audit (between review APPROVE and commit). CLEAN,
      no findings. Faithful port of M1-569's root-in-container tar (itself
      redteam-CLEAN) into backup.sh; :ro mounts + M1-584 colon/system-prefix
      guard. 2 out-of-model observations (supply-chain / operator-infra, out of
      scope per security.md). No follow-up ticket recommended.
clarity_check:
  date: 2026-07-07
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-587: backup.sh handles root-owned adapter identity dirs (the M1-569 follow-up)

## Context

Surfaced live on 2026-07-07: an operator ran `prod/scripts/upgrade.sh -y` and it
aborted at **step 1 (backup)** with:

```
+ tar -czpf .../adapters-20260707.tgz (-C /) .../simplex .../signal-cli
tar: home/infochat/infochat/prod/runtime/signal-cli/data: Cannot open: Permission denied
tar: Exiting with failure status due to previous errors
```

`upgrade.sh` calls `backup.sh` under `set -euo pipefail`, so the tar failure
aborts the whole upgrade **before any container is rebuilt or restarted** (the
running collector/provider were left untouched — the pg_dump had already written
`infochat-20260707.pgc`, but `adapters-20260707.tgz` was truncated).

Root cause — the EXACT gap M1-569 documented and deliberately deferred.
`backup.sh:136` tars the adapter identity dirs with a host-side
`tar -C / -czpf "$ADAPTERS_ARTIFACT" "${adapter_rel_paths[@]}"` running as the
**deploy user** (`infochat`). The Provider image runs as **root**
(07-deployment.md:700), and the `signal-cli` daemon it spawns locks its account
store to `root:root` mode **0700** (`signal-cli/data`, `signal-cli/attachments`)
— so a non-root `tar` cannot even traverse it. SimpleX survives the same tar only
because `simplex-chat` happens to write mode 0644.

| identity file | owner | mode | non-root readable? |
|---|---|---|---|
| `simplex/simplex_v1_*.db` | root:root | 0644 | yes → tar succeeds by luck |
| `signal-cli/data`, `.../attachments` | root:root | 0700 | no → tar fails |

M1-569 fixed this for `pack.sh`/`restore.sh` (root-privileged tar inside a
throwaway container) but froze `backup.sh` out of scope, naming the fix a
"separate follow-up ticket" and predicting this incident verbatim:

> backup.sh's `adapters-*.tgz` uses the identical host-side `tar -C /`; it works
> in production only because cron runs it as root. **A run as the deploy user
> would fail identically.**

That follow-up ticket was never filed. `upgrade.sh` runs `backup.sh` as the
non-root deploy user, so it failed identically. This ticket IS that follow-up.

## The fix

Port M1-569's already-merged pattern from `pack.sh` (lines ~250-256) into
`backup.sh`'s adapter identity tar step: run the tar as root inside a throwaway
container that read-only-mounts each configured data-dir at its absolute host
path, so no host-side interactive sudo is needed and the archive layout is
byte-identical to the old host tar. Mechanism, verbatim from pack.sh:

```
IDENTITY_TAR_IMAGE="pgvector/pgvector:pg16"   # already pulled by the postgres service; ships GNU tar
tar_mounts=()
for rel in "${adapter_rel_paths[@]}"; do
  tar_mounts+=(-v "/$rel:/$rel:ro")
done
docker run --rm -u 0:0 "${tar_mounts[@]}" --entrypoint tar "$IDENTITY_TAR_IMAGE" \
  -C / -czpf - "${adapter_rel_paths[@]}" > "$ADAPTERS_ARTIFACT"
```

The `> "$ADAPTERS_ARTIFACT"` redirect runs on the host as the invoking user, so
the resulting tgz is owned by the deploy user (not root) — same as pack.sh's
`> "$STAGING/identities.tgz"`. backup.sh only READS the identity dirs (mounts are
`:ro`) and never untars onto the host, so the M1-568 write-side extraction
allowlist is not relevant here; the only pack.sh guard that ports is the M1-584
pre-mount data-dir check (a colon/system-prefix path would mis-parse the `-v`
mount spec).

## Out-of-scope

See frontmatter. Notably the Provider-runs-as-root architecture (root cause, out
of scope — we adapt to it, as M1-569 did), pack.sh/restore.sh (already fixed),
and the pg_dump / retention / backup-dir-precedence logic (untouched — only the
adapter tar step changes).

## Notes

- **Provenance.** Live upgrade failure 2026-07-07, and the deliberately-deferred
  M1-569 follow-up. Filed `remediates: M1-569` because M1-569 is done + merged
  (its commit is immutable) and correctly scoped backup.sh out at the time.
- **Why HOST validation.** Faithful capture of root-owned 0700 subtrees needs
  real Docker + root-owned fixtures, so the round trip (backup.sh as deploy user
  -> tgz contains signal-cli/data) is HOST work, exactly as M1-569's was. mvn
  verify pins only the invocation shape via BackupWiringTest.
