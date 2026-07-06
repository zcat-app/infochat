---
id: M1-581
title: "restore.sh: exact identity-path gate, truthful failure remediation, operator-role reminder"
status: done
created: 2026-07-06
last_updated: 2026-07-06
clarity_check:
  date: 2026-07-06
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 3
files_scope:
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    A resumable/re-entrant restore (idempotent steps, checkpoint file). This
    ticket makes the guidance TRUTHFUL about the non-resumable design; making
    restore actually resumable is a different, bigger decision.
  - >-
    Automatic cleanup of partial state from the ERR trap. Restored identity
    material must NEVER be auto-deleted — the trap prints the recipe, the
    operator executes it.
  - >-
    Reconstructing operator LOGIN roles / role memberships automatically.
    Impossible by design: their password hashes are cluster-global and
    deliberately not in the bundle. This ticket only tells the operator.
  - "setup.sh behavior. Only restore.sh stops POINTING at it wrongly."
acceptance:
  - >-
    The identity path-mismatch gate matches an EXACT tar listing line
    (`grep -qxF -- "$rel/"`), not a substring. New RestoreWiringTest case: a
    bundle whose identity members sit under a prefix (e.g.
    `backup/<data-dir>/…`) is REFUSED pre-mutation with the existing mismatch
    message — today the substring `grep -qF "$rel/"` false-passes and the run
    aborts mid-mutation, violating the gate's own "Aborted before any change
    to this host" promise. (pack.sh names the directory member, so GNU tar
    always lists exactly `rel/` — verified against the inner identities.tgz
    listing the gate reads.)
  - >-
    The "$SECRETS_FILE already exists" gate message names the REAL retry
    recipe — remove the runtime files a prior restore placed
    (secrets.env, application.properties, bootstrap files, identity dirs — the
    root-owned ones via a root container) and `docker volume rm` the pgdata
    volume — and stops advising `setup.sh --reset --hard`, which keeps
    secrets.env (verified: do_reset removes containers/network/pgdata/state
    only) and then falls through into the interactive wizard, leaving the
    operator still blocked on the same gate.
  - >-
    The pre-M1-571 custom-GGUF fail-loud message stops advising "re-run
    prod/setup.sh step 4 … then re-run restore.sh": restore.sh's own
    rehydrate comment says 4-llm.sh would rewrite the config restore just laid
    down, and the freshness gates make a re-run impossible. It instead names
    the manual completion path (fetch the GGUF into the
    infochat-llamacpp-models volume, then re-run per the ERR-trap recipe or
    finish bring-up manually).
  - >-
    Once mutation begins (config placement), an ERR trap prints a
    partial-state note: what has been placed so far and the same retry recipe.
    No automatic deletion.
  - >-
    End-of-restore output and §7.10.1 gain one line: operator LOGIN roles and
    their `GRANT infochat_admin TO …` memberships (the V43-documented
    workflow) are cluster-global, absent from the single-DB dump, and must be
    re-created on the clone — the one silent divergence from the "exact clone"
    promise (everything else fails loud).
  - "`mvn verify` is green from the repo root."
test_plan:
  adds:
    - "RestoreWiringTest — prefixed-member bundle refused pre-mutation (exact-match gate)."
  modifies:
    - "prod/scripts/restore.sh — gate anchor, three message rewrites, ERR trap."
  preserves:
    - "All existing gate tests and messages not named above; the M1-568 allowlist and M1-569 mount construction; no behavior change on the happy path."
spec_refs:
  - "docs/design/07-deployment.md §7.10.1"
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-06
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 197
      removed: 18
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-06
    verdict: CLEAN
    base: c426e5d05743c9e5756627b2e3d1e043ab3788a6
    head: "m1/M1-581-restoresh-exact-identity-path (working tree, pre-commit)"
    verdict_file: docs/plan/m1/redteam/M1-581-2026-07-06.md
    out_of_model_count: 3
    note: |
      In-progress audit between review APPROVE (round 1) and commit. CLEAN.
      Three out-of-model advisories: (1) bundle-supplied data-dir paths as a
      root-write vector — operator-trusted supply-chain per security.md, and
      pending M1-584 already tracks path-sanity refusal; (2) rm-based secret
      disposal vs shred — pending M1-583 already tracks it; (3) the printed
      volume-rm recipe's name-substring filter could select a co-located
      project's volume — same filter as the pre-existing fresh-DB gate,
      operator-executed advice only. None auto-filed.
---

# M1-581: exact identity-path gate + truthful remediation

## Context

Three MEDIUM findings of the 2026-07-06 audit of M1-567..576, all in
restore.sh's failure paths, all verified against source and falsified against
setup.sh/V43 before filing:

1. **Substring gate** (restore.sh ~:258): `grep -qF "$rel/"` over the
   identities.tgz listing lets a relocated/hand-repacked bundle pass the
   consistency gate and then abort AFTER mutation began. Two independent audit
   agents converged on this line. One-flag fix.
2. **Wrong remediation advice**: the "already configured" gate points at
   `setup.sh --reset --hard`, which does not remove secrets.env and launches
   the wizard; the pre-M1-571 GGUF failure says "re-run restore.sh", which the
   gates block. A mid-restore failure (image pull, model fetch, the new
   M1-580 gate) strands the host with no documented way forward — the
   acceptance item "each failure names the actionable fix" from M1-567 is
   unmet on exactly the paths where the operator is most stressed
   (disaster recovery).
3. **Silently lost operator roles** (M1-570 residue): `CREATE ROLE
   infochat_admin NOLOGIN` restores object ACLs, but `pg_auth_members` and
   LOGIN roles are never in a single-DB dump; an operator who followed V43's
   documented `ops_alice` workflow loses psql admin access on the clone with
   no warning anywhere.

## Approach

Small, message-heavy diff in one script + one behavioral test + one doc
paragraph. The ERR trap prints; it never deletes.

## Notes

- Coordinate with M1-580/M1-584 (same file) — run serially.
- Audit provenance: findings M1/M2/M5 of the 2026-07-06 audit (memory
  `audit-567-576-open-findings`).
