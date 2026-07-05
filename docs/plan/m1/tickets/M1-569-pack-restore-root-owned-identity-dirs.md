---
id: M1-569
title: pack.sh/restore.sh handle root-owned adapter identity dirs
status: pending
created: 2026-07-05
last_updated: 2026-07-05
blocked_by: []
remediates: M1-567
files_budget: 5
files_scope:
  - prod/scripts/pack.sh
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
security_relevant: true
migration_touch: false
round_cap: 2
out_of_scope:
  - >-
    prod/scripts/backup.sh. backup.sh shares the identical host-side `tar -C /`
    identity model and therefore has the SAME latent gap (it only works today
    because the operator's cron runs it as root). Its contract is frozen
    out-of-scope (M1-427 / M1-567). Do NOT change backup.sh here; if the fix
    lands as a shared helper, applying it to backup.sh is a separate follow-up
    ticket. Note the gap; do not fix it inline.
  - >-
    The Provider-runs-as-root architecture (07-deployment.md:700). That root
    container is the ROOT CAUSE of the root-owned identity dirs, but changing it
    is a large, separate decision. This ticket ADAPTS pack/restore to the
    existing architecture, it does not change the architecture.
  - >-
    The DB restore path (pg_restore), model rehydration, precondition gates, and
    app bring-up in restore.sh; and the DB dump + config/secrets/bootstrap copy
    in pack.sh. This ticket touches ONLY the adapter-identity tar (pack) and
    untar (restore) steps.
  - >-
    The M1-568 extraction allowlist SEMANTICS. Restore must still extract ONLY
    the configured data-dir paths (no regression); this ticket changes HOW the
    untar runs (as root, in-container), not WHAT it is allowed to write.
  - >-
    Threat-model amendment / bundle-signature scheme. Unchanged trust boundary.
acceptance:
  - >-
    pack.sh captures every configured adapter identity dir INCLUDING root-owned,
    owner-only (mode 0700) subtrees — concretely signal-cli/data and
    signal-cli/attachments, which the Provider's root signal-cli daemon writes
    root:root 0700 — WITHOUT requiring the invoking user to be root and WITHOUT
    interactive sudo. (Today pack.sh runs `tar -C /` as the deploy user and
    fails `Permission denied` on those dirs; it only succeeds on the SimpleX DBs
    because simplex-chat happens to write them mode 0644.)
  - >-
    The fix is ADAPTER-AGNOSTIC — SimpleX and Signal identity dirs both go
    through the same privileged tar path. No signal-cli special-case, and no
    reliance on SimpleX's incidental 0644 mode (a future simplex-chat writing
    0600 must not break pack).
  - >-
    restore.sh reconstructs each identity dir preserving BOTH ownership and
    modes as pack captured them (root:root 0700 for the signal daemon store;
    the SimpleX DB modes as stored), so each adapter daemon accepts the restored
    identity as its own. A restore run as a non-root deploy user must still
    produce a faithful, daemon-usable identity (the untar runs with the needed
    privilege itself, e.g. in a root container, rather than depending on the
    caller's uid).
  - >-
    The M1-568 extraction allowlist is preserved: restore still extracts ONLY
    the configured adapter data-dir paths (the `${dir#/}` allowlist), and the
    empty-allowlist guard still fires. A tampered bundle's out-of-allowlist
    member is still ignored even though the untar now runs as root.
  - >-
    mvn verify is green. RestoreWiringTest pins the privileged untar invocation
    shape (that restore names the allowlisted paths AND runs the extraction with
    the intended privilege mechanism); the REAL root-owned-ownership round trip
    (pack a live root-owned signal-cli dir -> restore -> daemon accepts it)
    remains HOST validation, not mvn verify, exactly as M1-567's round trip is.
  - >-
    docs/design/07-deployment.md §7.10.1 documents that pack/restore handle
    root-owned identity dirs via a root-privileged (in-container) tar so the
    host-clone cycle needs no interactive sudo, and notes that backup.sh shares
    the same host-side tar model and thus the same root requirement (pointer to
    the separate follow-up).
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  preserves:
    - all tests currently green on main
    - >-
      the M1-568 extractionWritesOnlyAllowlistedIdentityDirs case must still
      pass (the allowlist is unchanged; only the untar privilege mechanism
      changes). If the untar moves into a container, the test's fake `docker`
      must model the extraction (or the test asserts the invocation shape) so
      the allowlist assertion stays meaningful.
  notes:
    - >-
      Faithful root-ownership preservation cannot be asserted by mvn verify
      (needs real Docker + root-owned fixtures); that is HOST validation. The
      wiring test pins the invocation/allowlist shape only, mirroring M1-567's
      gate-only test scope.
spec_refs:
  - docs/design/07-deployment.md §7.10.1
  - docs/spec/deployment.md
decision_refs:
  - D34
  - D46
---

# M1-569: pack.sh/restore.sh handle root-owned adapter identity dirs

## Context

Surfaced by the M1-567 host-clone round-trip validation (the outstanding HOST
work the handoff flagged as the primary next step). On the first real
`pack.sh` run it failed loud:

```
tar: home/infochat/infochat/prod/runtime/signal-cli/attachments: Cannot open: Permission denied
tar: home/infochat/infochat/prod/runtime/signal-cli/data: Cannot open: Permission denied
```

`pack.sh` runs `tar -C / -czpf identities.tgz "${adapter_rel_paths[@]}"` as the
**deploy user** (e.g. `infochat`). The Provider image runs as **root**
(07-deployment.md:700), and the `signal-cli` daemon it spawns writes its account
store `root:root` mode **0700** (`signal-cli/data`, `signal-cli/attachments`) —
so a non-root `tar` cannot read it, and the bundle cannot capture the Signal
identity.

**Rechecked — this is NOT a live-testing artifact and NOT signal-specific:**

| identity file | owner | mode | non-root readable? |
|---|---|---|---|
| `simplex/simplex_v1_*.db` | root:root | 0644 | yes → pack succeeds by luck |
| `signal-cli/data`, `.../attachments` | root:root | 0700 | no → pack fails |

Both adapters' identity DBs are **root-owned** (both written by the root Provider
container — simplex-chat provisioning/runtime and the signal-cli daemon). SimpleX
only survives today because `simplex-chat` writes mode 0644; `signal-cli`
deliberately locks its store to 0700. Any clean wizard deployment ends up the
same way, so the gap is architectural. Relying on SimpleX's incidental 0644 is
fragile, so the fix must be **adapter-agnostic**.

`restore.sh` has the mirror problem: run as a non-root user its `tar -C /`
untar cannot recreate `root:root` ownership, so the restored identity would not
faithfully match the source.

## The fix (design — PLAN question)

Run the identity **tar (pack)** and **untar (restore)** with root privilege
*inside a throwaway container*, the same in-container-privilege pattern
`pack.sh`/`restore.sh`/`backup.sh` already use for `pg_dump`/`pg_restore` (so no
host-side interactive sudo is needed). Sketch for pack:

```
docker run --rm -u 0:0 \
  -v /home/.../signal-cli:/home/.../signal-cli:ro \
  -v /home/.../simplex:/home/.../simplex:ro \
  <image-with-GNU-tar> tar -C / -czpf - <rel-paths> > "$STAGING/identities.tgz"
```

Open PLAN questions for the implementer:
- **Which image** provides a GNU-compatible `tar` (busybox tar under-preserves
  modes; a coreutils/debian or an already-pulled image with GNU tar is safer).
- **Mount shape**: bind-mount each configured data-dir at its absolute host path
  so the `-C /` relative-path convention (which M1-568's allowlist and restore
  depend on) is preserved byte-for-byte.
- **Restore untar**: run as root in-container extracting from stdin, STILL naming
  only the M1-568 allowlist members, writing to the target root — mind that this
  now-root `tar -C /` is a stronger primitive, so the allowlist (M1-568) is
  load-bearing, not just defense-in-depth, here.
- **Ownership on restore**: root untar recreates `root:root` + preserved modes;
  confirm the signal-cli daemon and simplex-chat accept the result.

## Out-of-scope

See frontmatter. Notably: `backup.sh` (same host-side tar model, same latent
root requirement — frozen contract, separate follow-up), the Provider-runs-as-root
architecture (root cause, but out of scope — we adapt to it), and the M1-568
allowlist *semantics* (preserved; only the untar's privilege mechanism changes).

## Notes

- **Provenance.** Found by the M1-567 host round-trip (unmerged validation), not
  a red-team finding. Filed as a `remediates: M1-567` remediation because M1-567
  is done + merged (e9a3a027) and its commit is immutable.
- **backup.sh shares the gap.** `backup.sh`'s `adapters-*.tgz` uses the identical
  host-side `tar -C /`; it works in production only because cron runs it as root.
  A run as the deploy user would fail identically. Out-of-scope here; note it.
- **Why the round-trip earned its keep.** `pack.sh` failed BEFORE any wipe, so
  the live Signal identity (a rented number) was never at risk — exactly the
  "verify the bundle before destroying the source" discipline the host round-trip
  is meant to enforce.
