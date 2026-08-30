---
id: M1-960
title: "Rename fam-replica-restore.sh to replica-restore.sh"
status: pending
created: 2026-08-30
last_updated: 2026-08-30
flow: tick
reproduction: >-
  Probe (instrument ticket, the M1-954 posture — the §13-compliant artifact
  IS the deliverable; no correctly-named procedure can exist before the
  rename): `ls scripts/replica-restore.sh` returns "No such file or
  directory" (verified 2026-08-30; scripts/ holds the instance-named
  fam-replica-restore.sh, 496 lines, plus eval-scopes-seed.sql,
  tech-drift-restore.sql and the lint/gate/measure scripts — glob verified).
  The user rules "fam" an instance name; the script's CONTENT is
  instance-free and generic (§13-parameterized: required flags with no
  committed defaults, M1-954 round-2 APPROVE), so the committed NAME is the
  engineering-rules §13 residual. Observed ref surface, verified:
  `git grep -n "fam-replica-restore"` over tracked files returns EXACTLY 24
  refs across 6 files — docs/measurement/retrieval-eval-two-leg.md:34 (1),
  docs/plan/m1/tick-tickets/M1-948-fam-replica-extraction.md (6),
  M1-950-eval-harness-two-world-extension.md (1),
  M1-954-fam-replica-restore-reland.md (12),
  infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/ReplicaRestoreWiringTest.java
  (:23 javadoc, :558 repoRoot().resolve), and the script's own header
  (:2) + usage line (:37). The rename's executable proof is the
  default-suite wiring contract: RED at start AFTER `git mv` — the wiring
  legs fail resolving the stale scripts/fam-replica-restore.sh path
  (ReplicaRestoreWiringTest.java:558) — run and logged per workflow §0 at
  .scratch/tick-red-M1-960.log, then green after the refs update.
analysis_ref: docs/plan/m1/tick-analysis/retrieval-campaign-followups.md
blocked_by: []
files_scope:
  - scripts/replica-restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/ReplicaRestoreWiringTest.java
  - docs/plan/m1/tick-tickets/M1-948-fam-replica-extraction.md
  - docs/plan/m1/tick-tickets/M1-950-eval-harness-two-world-extension.md
  - docs/plan/m1/tick-tickets/M1-954-fam-replica-restore-reland.md
  - docs/measurement/retrieval-eval-two-leg.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The script's CONTENT — mechanics, flags, fences, image pins, the
    flyway_checksum body are M1-954's round-2-approved contract,
    byte-identical under the new name except the TWO self-referential path
    strings (:2, :37); probe: git diff --stat shows a rename with no
    content hunks beyond those two lines.
  - >-
    ANY restore/teardown/migration/fingerprint run against ANY instance —
    a pure rename has no operator leg; the frozen replica and the frozen
    test stack are untouched (probe: no docker command in the diff's
    verification beyond the wiring test's fake docker).
  - >-
    The operator-local stores — .agents/memory-local/fam-replica-restore.sh
    (a DIFFERENT, instance-bound file, the M1-954 adoption source) and the
    memory index entries keep their names; they are gitignored and outside
    the repo (analysis P21; probe: git status --porcelain names no
    .agents/memory-local or .bench path).
  - >-
    ANY spec/design edit, and any behavior change to the eval lane — the
    runner, worlds seam, and golden sets never resolve the script path.
  - >-
    History rewrite of any kind — plain `git mv` + ref edits; no rebase, no
    filter-branch, no force push (analysis P20).
acceptance:
  - "REPRODUCTION closed: scripts/replica-restore.sh exists at the new name via `git mv` (content byte-identical except the two self-referential path strings) and `git grep -n \"fam-replica-restore\"` over tracked files returns ZERO matches — probes: git grep returns nothing; `git log --follow --oneline -- scripts/replica-restore.sh` shows the rename chain (no history rewrite); `bash -n scripts/replica-restore.sh` exits 0."
  - "RED-at-start leg (workflow §0, the ordering the reproduction names): immediately after `git mv` and BEFORE any ref edit, `mvn -pl infochat-provider -am test -Dtest=ReplicaRestoreWiringTest` is RED on the stale path resolution (ReplicaRestoreWiringTest.java:558 resolves scripts/fam-replica-restore.sh), logged at .scratch/tick-red-M1-960.log (the M1-945 convention); after the ref edit the class is GREEN with ALL its legs' assertions unmodified (the flyway-checksum byte-identity leg pins prod/scripts/restore.sh — a DIFFERENT path, untouched by this rename; the §13 port-allowlist source-scan leg re-scans the renamed file and still passes)."
  - "The 24 refs carried: the three ticket files' occurrences update by STRING-ONLY substitution (no landed acceptance/review/prose semantics edited — reviewer spot check on each of the three); ReplicaRestoreWiringTest.java's two refs (:23, :558) update; the script's own two self-refs update — probe: per-file `git grep -c \"replica-restore.sh\"` shows the updated counts and the acceptance-1 grep returns zero for the old name."
  - "APPEND-ONLY record correction (analysis P19; engineering-rules §12 spirit): docs/measurement/retrieval-eval-two-leg.md gains EXACTLY ONE appended dated correction line stating the procedure's committed name is now scripts/replica-restore.sh (the historical :34 citation stays byte-identical) — probes: git diff over the record shows exactly one added line; grep -n 'fam-replica-restore' over the record still returns :34 (history visible, corrected append-only)."
  - "mvn verify from the repo root is green (ReplicaRestoreWiringTest runs in the default suite); git diff --name-only names exactly the files_scope paths plus board/frontmatter regen."
test_plan:
  adds: []
  modifies:
    - >-
      ReplicaRestoreWiringTest (AUTHORIZED: the two path strings :23/:558
      follow the renamed file; no assertion, leg, or fake-docker shape
      changes — what the legs assert is untouched, only WHERE the contract
      file lives).
  preserves:
    - >-
      every ReplicaRestoreWiringTest leg's assertions byte-identical
      (ordering, refusals, never-stdin, §13 allowlist, checksum
      byte-identity, seed-delta abort).
    - all tests currently green on main.
spec_refs:
  - docs/spec/deployment.md §Backups, rotation, secrets
decision_refs:
  - D34
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-960: Rename fam-replica-restore.sh to replica-restore.sh

## Context

M1-954 re-landed the replica restore instrument instance-free in CONTENT
(required flags, no committed instance defaults, refusal fences) but under
a name that carries the instance word "fam". The user has ruled "fam" an
instance name (§13 spirit; their drafted-but-unfiled rename proposal from
the M1-954 session records rides this brief — session-carried, not
independently verifiable here), so the committed name is the placement
residual and the rename is compliance, not cosmetics. 24 committed refs
across 6 files carry it (verified counts in the reproduction). Shared
analysis: `analysis_ref:` (Ground truth, Pitfalls P19-P22).

## Root cause

A §13 placement residual, not a behavior defect: the script's mechanics
were parameterized in M1-954 precisely so nothing instance-bound commits,
yet its own filename names the instance family it was first built for.
Verified: the target name does not exist; the ref surface is exactly 24
occurrences in 6 tracked files (grep-verified, per-file counts in the
reproduction); the wiring test resolves the path at
ReplicaRestoreWiringTest.java:558, so the rename is mechanically proven by
the default suite (RED on the stale path post-mv, GREEN after the refs).

## Pitfalls

Carried from the analysis, numbered identically; this ticket carries
P19-P22 (P20's no-rewrite and P21's scope boundary realized below).

- P19: append-only vs mechanical — the two-leg record gets ONE appended
  correction line (history stays visible); the ticket files get
  string-only substitution (no semantic edits to landed records; §1).
- P20: the rename's executable proof and order — RED-at-start is the
  post-mv pre-ref wiring run; plain `git mv` + refs; no history rewrite.
- P21: §13 scope boundary — only COMMITTED artifacts rename; the
  gitignored operator-local stores keep their names.
- P22: placement — the §13 grep returns zero after the rename; no
  instance name survives in any committed artifact of this diff.

## Approach

Derived from `spec_refs:` — deployment.md §Backups, rotation, secrets is
the section owning the procedure's class (operator tooling, dumps never
committed — D34); the rename changes the artifact's NAME only, so the
spec's commitments are preserved by construction.

- **Files to touch** — `files_scope`: the renamed script, the wiring
  test's two path strings, the three ticket files' strings, one appended
  line in the record.
- **Steps in order:**
  1. `git mv scripts/fam-replica-restore.sh scripts/replica-restore.sh`;
     update the script's own two self-refs (:2, :37).
  2. Run the wiring test RED on the stale path; log
     `.scratch/tick-red-M1-960.log`.
  3. Update the wiring test's two refs (:23, :558); run GREEN.
  4. Update the three ticket files' path strings (string-only).
  5. Append the one-line dated correction to the two-leg record.
  6. Full `mvn verify`; diff fences (the §13 grep returns zero).
- **Controls to preserve (§10):** the wiring test's every leg assertion
  byte-identical (including the flyway-checksum byte-identity leg, which
  pins `prod/scripts/restore.sh` — a different path, and the §13
  port-allowlist source-scan, which re-scans the renamed file); the
  record's landed sections byte-identical; the frozen instances untouched
  (no operator leg at all).
- **Pitfall→mitigation:** P19→steps 4-5's shapes + the probes; P20→step
  2's RED log + `git log --follow`; P21→the git status fence; P22→the
  zero-hit grep.
- **Alternatives considered (rejected; the commit message cites them):**
  keep the name and document an exception (B — §13 placement is not
  licensable by documentation; the user has ruled); a back-compat wrapper
  or symlink at the old path (C — a forbidden compatibility shim, §7).

## Definition of done

The script lives at `scripts/replica-restore.sh` via a tracked rename with
no content change beyond its two self-refs; the wiring contract is RED on
the stale path then GREEN with every assertion unmodified; all 24 refs
carry the new name (string-only in the tickets; one appended correction
line in the record); the old name returns zero hits over tracked files;
repo-root `mvn verify` is green; the diff names nothing outside
`files_scope` plus board regen.

## Verification

- P19 → acceptance item 4's probes (exactly one added line in the record;
  :34 still greppable) + the reviewer's string-only spot check on the
  three tickets (acceptance item 3).
- P20 → acceptance item 2's RED log + acceptance item 1's
  `git log --follow` probe.
- P21 → the git status fence (no `.agents/memory-local`/`.bench` path in
  the diff).
- P22 → acceptance item 1's zero-hit grep (the §13 discriminator).
- FAILURE-MODE coverage → the RED-at-start leg itself (the stale path
  observed wrong), plus the source-scan leg re-scanning the renamed file
  (a mutation planting a port literal in it still reds the allowlist leg).
- acceptance items 1-5 → the named probes/commands.

## Out-of-scope

Named in `out_of_scope`: the script's content; any run against any
instance; the operator-local stores; spec/design edits; eval-lane
behavior; any history rewrite. The one pre-existing test modified is
authorized in `test_plan.modifies`: what changes is WHERE the contract
file lives (two path strings), never what any leg asserts
(engineering-rules §8).

## Census

The class the rename guards: **committed references to the renamed
artifact.** Re-runnable: `git grep -n "fam-replica-restore"`. Rows
(verified at draft time — 24 occurrences):

| File | Count | Disposition |
|---|---|---|
| scripts/fam-replica-restore.sh (:2, :37) | 2 | **FIX** (git mv + self-refs) |
| ReplicaRestoreWiringTest.java (:23, :558) | 2 | **FIX** (path strings; assertions untouched) |
| M1-948 ticket | 6 | **FIX** (string-only) |
| M1-950 ticket | 1 | **FIX** (string-only) |
| M1-954 ticket | 12 | **FIX** (string-only) |
| docs/measurement/retrieval-eval-two-leg.md (:34) | 1 | **FIX** (ONE appended correction line; :34 never edited) |
| .agents/memory-local/* (3 files, gitignored) | — | **DISPOSED** (out of repo scope, P21) |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-960-replica-restore-script-rename.md
```
