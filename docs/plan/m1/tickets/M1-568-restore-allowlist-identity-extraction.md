---
id: M1-568
title: restore.sh extracts only allowlisted identity dirs (tar-slip)
status: pending
created: 2026-07-05
last_updated: 2026-07-05
blocked_by: []
files_budget: 3
files_scope:
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/07-deployment.md
complexity: low
risk: low
security_relevant: true
migration_touch: false
round_cap: 2
out_of_scope:
  - >-
    prod/scripts/pack.sh. pack.sh tars a TRUSTED source and is unaffected; this
    ticket only hardens the untrusted-input side (restore's extraction of a
    bundle that may have been tampered with in transit/storage). Do not change
    what pack.sh emits.
  - >-
    The DB restore path (pg_restore), model rehydration, precondition gates, and
    app bring-up in restore.sh. This ticket touches ONLY the identities.tgz
    extraction line; the rest of restore.sh is untouched.
  - >-
    Threat-model amendment. This is defense-in-depth WITHIN the existing model
    (a tampered bundle stays an operator-infra / supply-chain concern that
    docs/spec/security.md places out of scope, and the operator is still told to
    encrypt the bundle for transfer). Do NOT add a bundle-signature requirement
    or otherwise change the trust boundary — that is a separate, larger decision.
  - >-
    infochat-collector / infochat-provider application code. Shell + one test +
    a doc note only; no runtime Java changes.
acceptance:
  - >-
    restore.sh extracts identities.tgz naming ONLY the configured adapter
    data-dir relative paths as tar extraction targets (the same `${dir#/}`
    values the existing path-consistency gate already computes), so a bundle
    member OUTSIDE those subtrees (e.g. `etc/...`, `root/.ssh/...`) is silently
    ignored, never written to the host filesystem.
  - >-
    A legit bundle whose identities.tgz contains only the configured adapter
    data-dirs restores byte-identically to the pre-change behaviour — the
    allowlist is a no-op for well-formed bundles (no regression to the normal
    clone path).
  - >-
    RestoreWiringTest gains a case proving a bundle carrying an extra
    out-of-allowlist member does NOT write that member on extraction. (Test
    approach is a PLAN question: the existing gate tests fail BEFORE the
    `tar -C /` extraction, so this case needs either a get-past-gates path whose
    identity data-dir resolves into the test TempDir — so `tar -C /` stays
    sandboxed — or a focused unit test of the extraction invocation alone.)
  - >-
    mvn verify is green.
  - >-
    docs/design/07-deployment.md §7.10.1 states that restore extracts only the
    allowlisted identity dirs, and the §7.10 manual restore prose (the
    `tar -xzpf ... -C /` step) carries the same allowlist note so the manual
    path is not left as the unguarded twin.
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  preserves:
    - all tests currently green on main
  notes:
    - >-
      RestoreWiringTest (added by M1-567) currently pins the fail-loud
      precondition gates only, all of which fail before the identity extraction.
      This ticket adds ONE extraction-allowlist case. Any real
      pack->transfer->restore round trip remains host validation, not mvn verify.
spec_refs:
  - docs/design/07-deployment.md §7.10.1
  - docs/spec/security.md §Threat model
decision_refs:
  - D34
---

# M1-568: restore.sh extracts only allowlisted identity dirs (tar-slip)

## Context

Follow-up to the M1-567 pre-commit red-team (verdict CLEAN, one OUT-OF-MODEL
observation; durable record `docs/plan/m1/redteam/M1-567-2026-07-05.md`).
`restore.sh` reconstructs each adapter identity dir with
`tar -C / -xzpf identities.tgz` — extracting relative to the target host's root
so the dirs land at their original absolute paths. The path-consistency gate
only checks that each configured `INFOCHAT_<NAME>_DATA_DIR` *appears* as a tar
entry; it does not restrict extraction to those entries. A **tampered** bundle
carrying extra members whose paths name system locations (e.g. `etc/cron.d/evil`,
`root/.ssh/authorized_keys`) would therefore be written onto the host with the
restoring user's privileges — a classic tar-slip primitive.

This is OUT-OF-MODEL (a tampered bundle is an operator-infrastructure /
supply-chain compromise that `docs/spec/security.md` places out of scope, and
the operator is told to encrypt the bundle for transfer), so it is **not a
vulnerability in the declared model** — this ticket is cheap defense-in-depth on
a privileged (`tar -C /`) primitive, not a required fix. It is filed LOW
priority precisely because it is out-of-model; skipping it is defensible.

## Acceptance

See frontmatter. In one line: extract identities.tgz naming ONLY the configured
data-dir paths (already computed by the path-consistency gate) so out-of-allowlist
members are ignored; a test proves an extra member is not written; docs note the
allowlist for both `restore.sh` and the §7.10 manual restore; `mvn verify` green.

## Out-of-scope

See frontmatter. Notably: pack.sh (tars a trusted source — untouched); the rest
of restore.sh (only the extraction line changes); and any threat-model amendment
or bundle-signature scheme (this stays defense-in-depth within the existing
operator-trust boundary).

## Notes

- **The cheap, verified fix.** `tar -x <archive> <member>...` extracts only the
  named members and ignores all others. `restore.sh` already builds the allowlist
  in its path-consistency loop (`rel="${dir#/}"` per configured adapter), so the
  change is roughly: collect those rel-paths and pass them as extraction targets —
  `tar -C / -xzpf "$STAGED_IDENTITIES" "${rel_paths[@]}"`. ~2 lines.
- **What GNU tar already blocks (verified 2026-07-05, this host).** A member
  containing `..` is refused ("Member name contains '..'") and tar exits
  non-zero, so `set -euo pipefail` already aborts a `../`-traversal bundle. A
  leading `/` is stripped with a warning. The residual gap is plain relative
  members that name system paths (no `..` needed), which `-C /` places under `/`.
  The allowlist closes exactly that gap.
- **Shared twin.** The identical `tar -xzpf ... -C /` extraction is the documented
  §7.10 manual restore step and mirrors `backup.sh`'s tar model; hardening only
  `restore.sh` would leave the manual path unguarded, hence the §7.10 doc note in
  acceptance. (`backup.sh`'s own contract stays frozen — M1-427 / M1-567
  out-of-scope; this ticket does not touch it.)
- **Provenance.** Prompted by the M1-567 red-team OUT-OF-MODEL item, not a
  finding (that audit was CLEAN), so this is a new independent ticket rather than
  a `remediates:` remediation. M1-567 is done + merged (e9a3a027); `restore.sh`
  lives on `main`.
