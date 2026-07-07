---
id: M1-583
title: "Close the secret-disposal gaps: shred pack.sh staging; shred-bundle accepts pack remnants and bare .pgc; hardlink/SSD caveat"
status: done
created: 2026-07-06
last_updated: 2026-07-07
clarity_check:
  date: 2026-07-06
  verdict: WARN
  warnings:
    - >-
      ACCEPTANCE-RUNNABLE item 1 (pack.sh staging-trap shredding) has no named
      automated test in test_plan — the shred-unavailable fallback and
      failure-path trap firing are verified by diff-read only.
    - >-
      test_plan.preserves lists "symlink guard" as an existing
      ShredBundleWiringTest behavior, but the current test file has no symlink
      fixture — closest is the realpath-based dangerous-path guard. Slightly
      inaccurate characterization; nothing pins the phantom case.
  blockers: []
blocked_by: []
files_budget: 4
files_scope:
  - prod/scripts/pack.sh
  - prod/scripts/shred-bundle.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/ShredBundleWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Auto-shred of bundles, TTL cron disposal, or folding shred into backup.sh —
    all rejected in M1-572 with recorded rationale; the operator-invoked
    posture is unchanged.
  - >-
    Encrypting the staging dir or bundle at rest. D34/§7.10 posture stands:
    transfer/storage encryption is the operator's responsibility; this ticket
    closes DISPOSAL gaps only.
  - "backup.sh. Same latent concerns, frozen contract, separate follow-up as before."
acceptance:
  - >-
    pack.sh's staging EXIT trap best-effort-shreds staging files before
    removing the tree (`find "$STAGING" -type f -exec shred -uz {} +` then
    `rm -rf`), on success AND failure paths. Today the trap plain-`rm`s a tree
    holding the complete secret set (DB dump, identities.tgz, secrets.env
    copy) on every run — leaving in freed blocks exactly the material
    shred-bundle.sh's own header says plain `rm` leaves, so the "pack → … →
    dispose" lifecycle the M1-572 WARN advertises was partly illusory on the
    source host. If shred is unavailable, fall back to today's rm (cleanup
    must never be lost to a missing tool).
  - >-
    shred-bundle.sh directory eligibility recognizes an interrupted-pack
    staging remnant: a directory containing `db/*.pgc` one level down, or
    named `.infochat-pack.*`, is eligible. Today it is REFUSED (the glob
    checks only the immediate level — reproduced empirically), sending the
    operator back to the hand-typed find|shred dance the helper exists to
    eliminate. Remnants arise from SIGKILL/OOM/power-loss during pg_dump (the
    EXIT trap covers TERM/HUP).
  - >-
    shred-bundle.sh's file branch accepts `*.pgc` in addition to `*.tgz` — the
    independent safety-copy dump the recovery convention produces is currently
    refused as a direct target even though `.pgc` is eligible as a directory
    member and the header names it as handled material.
  - >-
    The best-effort caveat (script header AND the §7.10.1 mirror) additionally
    names hardlinks (shredding one name zeroes the shared inode — other
    directory entries survive pointing at zeroed content, and conversely a
    hardlinked "safety copy" IS destroyed by shredding any name) and SSD
    wear-leveling/FTL (overwrites may never reach the original NAND cells).
  - >-
    ShredBundleWiringTest gains behavioral cases (same ProcessBuilder harness,
    real shred on @TempDir fixtures): staging-remnant directory shredded;
    bare .pgc file shredded; a non-tgz/pgc file target still REFUSED with
    nothing removed (pins the guard branch that had no test).
  - "`mvn verify` is green from the repo root."
test_plan:
  adds:
    - "ShredBundleWiringTest — staging-remnant dir accepted; .pgc file accepted; non-eligible file still refused."
  modifies:
    - "pack.sh — trap shreds staging; shred-bundle.sh — eligibility + caveat; 07-deployment.md — caveat mirror."
  preserves:
    - "All existing ShredBundleWiringTest cases (consent gate, symlink guard, / and repo-root refusal, partial-failure retention); shred-bundle's not-a-general-purpose-shredder posture."
spec_refs:
  - "docs/design/07-deployment.md §7.10.1"
decision_refs:
  - "D34 (operator-owned transfer/storage encryption)"
reviews:
  - round: 1
    date: 2026-07-06
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 149
      removed: 31
  - round: 2
    date: 2026-07-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 176
      removed: 31
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-07
    verdict: CLEAN
    base: main (3c59a356)
    head: m1/M1-583-secret-disposal-gaps (working tree, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-583-2026-07-07.md
    out_of_model_count: 3
    note: >-
      Pre-commit audit (security_relevant: true) of the branch working tree.
      CLEAN. Three out-of-model advisories: silent no-shred rm fallback in
      pack.sh cleanup (deliberate, documented best-effort posture); widened
      destruction eligibility as an operator footgun (consent gate is the
      control; local-attacker shapes outside model); inventory-vs-destroy
      TOCTOU (requires host write access, outside model). None warrants a
      follow-up ticket — each requires an attacker position the threat model
      explicitly excludes, and the D34 operator-owned-encryption posture
      already covers the residual risk.
---

# M1-583: close the secret-disposal gaps

## Context

MEDIUM finding of the 2026-07-06 audit of M1-567..576 (plus one earmarked
nit). The M1-572 audit agent empirically reproduced both refusals: a fixture
staging remnant (`stag/db/infochat.pgc`) and a bare `.pgc` file both get
`FAIL … nothing removed`, exit 1. The pack.sh staging plain-`rm` is
pre-existing since M1-567, but M1-572 is the ticket that made disposal
coherence the point — a diligently shredded bundle gives false confidence
while the same bytes sit plain-unlinked in freed blocks next to it.

The `.pgc` file refusal matches M1-572's acceptance verbatim, so it is a
ticket-design gap, not an implementation deviation — this ticket amends the
shape rule deliberately.

The hardlink caveat line was already earmarked ("fold into the next ticket
touching that file") — this is that ticket.

## Notes

- shred on a multi-GB staging dump costs time on every pack; acceptable for an
  operator-invoked, infrequent tool. If it ever matters, `shred -n1 -z` is the
  knob — implementer's choice, note it in the script comment.
- The staging-remnant eligibility keeps the no-sibling-sweep posture: a
  `.infochat-pack.*` dir sits inside OUT_DIR, and the operator names IT, not
  OUT_DIR.
- Audit provenance: finding M4 + hardlink nit of the 2026-07-06 audit (memory
  `audit-567-576-open-findings`, `shred-bundle-hardlink-caveat-nit`).

## Round 1 rework

1. Restore the raw-config directory eligibility in
   `prod/scripts/shred-bundle.sh`: re-add the deleted block
   `if [[ -d "$TARGET_ABS/raw-config" ]]; then ELIGIBLE=yes; fi` alongside
   (not instead of) the new `.infochat-pack.*` name check in the directory
   branch, so a recovery directory whose only qualifying member is a
   raw-config/ subdir remains eligible as it was pre-diff (M1-572 behavior).
   The diff's own eligibility comment, refusal message, and amended §7.10.1
   text already state this shape — only the check is missing.
