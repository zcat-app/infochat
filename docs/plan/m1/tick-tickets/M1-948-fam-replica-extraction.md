---
id: M1-948
title: "Isolated fam replica: dump, restore, pin fingerprint"
status: done
created: 2026-08-28
last_updated: 2026-08-28
flow: tick
reproduction: >-
  Probe (instrument ticket; no procedure can exist before it is written — the
  M1-844/M1-859/M1-928 posture): `ls scripts/replica-restore.sh` returns
  "No such file or directory" (verified 2026-08-28; scripts/ holds
  eval-scopes-seed.sql and the lint/gate scripts only) and no committed
  procedure anywhere names an isolated fam eval postgres — `grep -rn
  'fam-replica\|fam replica' scripts/ docs/measurement/ infochat-provider/
  src/test/` returns NOTHING. Observed consequence: every future two-world
  number would ride an ad-hoc hand-run restore with none of the fences the
  campaign discipline requires — no live-fam port fence (a mis-pointed
  eval.db.url measures LIVE fam at the live source port), no restore-order
  guarantee (restore.sh:12-17: pg_restore BEFORE any Flyway pass), no
  docker-cp restore path (stdin piping loses the custom-format header), and
  no pinned replica fingerprint for labels to bind to. Intended entry: the
  script's documented operator invocation, producing the pinned fingerprint
  + coverage + census readout recorded under gitignored
  .bench/retrieval-eval/fam-replica/ (operator-local).
analysis_ref: docs/plan/m1/tick-analysis/two-world-retrieval-instrument.md
blocked_by: []
files_scope:
  - scripts/replica-restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/FamReplicaRestoreWiringTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    LIVE fam as any kind of target — the script's only contact with the live
    instance is the one-time read-only pg_dump inside the fam postgres
    container (the backup.sh:135-138 shape); no eval/labeling/measurement
    ever rides the live source port or the fam compose network, and no fam
    service is stopped, paused, or reconfigured (the standing rule:
    measurements never ride live/prod containers; brief constraint 1).
  - >-
    ANY change under /home/infochat/infochat-fam or its compose files — the
    fam checkout is a live deployment surface, not this ticket's.
  - >-
    Labeling the fam golden set (M1-949), harness changes (M1-950), and the
    two-leg record (M1-952) — this ticket delivers the pinned world only.
  - >-
    ANY production / main-source change and ANY docs/spec/** edit — probe:
    git diff --name-only names no src/main and no docs/spec path.
  - >-
    Completing the replica's embedding coverage (a backfill would mutate the
    search space under a post-only fingerprint — analysis P6); coverage is
    MEASURED and PINNED here, completed only by a separately-decided ticket
    that re-pins.
  - >-
    Committing the dump or ANY user-derived data — the dump carries real
    users' rows (chat history, saves; <redacted-language-census>) plus audit/quarantine
    content (deployment.md §Backups, D34): it stays operator-local under
    gitignored .bench/, encrypted-at-rest per the operator posture.
acceptance:
  - "The script exists with three verbs — dump (pg_dump -F c of the fam DB via docker exec in the fam postgres container, PGPASSWORD from the container env, no secret on the host, output to .bench/retrieval-eval/fam-replica/fam-<ts>.pgc — the backup.sh:135-138 shape; the dump's handling follows docs/spec/deployment.md §Backups, rotation, secrets — sensitive operator material, encrypted-at-rest posture, never committed, D34), restore (bring up an ISOLATED postgres: own compose project, own volume, own host port, loopback-published; docker cp the dump in; pg_restore the in-container path), fingerprint (print the world fingerprint + embedding coverage + embedding_metadata identity + scope_preferences language census) — probe: `scripts/replica-restore.sh -h` prints usage for all three verbs; set -euo pipefail, executable 0755, shellcheck-clean (author-run)."
  - "ISOLATION FENCES (failure-mode, analysis P2/P14): the script REFUSES (exit nonzero, naming the offending value) a target port equal to 15432 (test stack) or 25432 (live fam), a target volume that is not empty/fresh, a missing dump file, and a restore attempted while any app container is attached to the target; the isolated postgres runs in its OWN compose project + network with NO join to the source network or infochat-prod_default (the DB-reach boundary of docs/spec/security.md §Threat model — only the two services and the operator reach a DB — holds by construction) — probe: FamReplicaRestoreWiringTest refusal legs (fake-docker argv pattern, the M1-819 RestoreWiringTest precedent) fire on each hostile input; an operator `docker network inspect` of the replica network shows no fam/prod network members."
  - "RESTORE ORDER (analysis P3/P4): the wiring test's fake-docker argv log proves the sequence postgres-up ALONE → docker cp → pg_restore of the in-container path → schema steps → eval-scope seed → fingerprint read — pg_restore precedes every schema/seed step and the dump is NEVER piped over stdin (grep of the argv log: no `pg_restore` with the dump on stdin)."
  - "Schema reconciliation without an app boot (analysis P5): the script verifies the restored flyway_schema_history against the checkout's migration set (the restore.sh M1-819 checksum-gate posture) and, when the fam dump (prod rev ae295434) is BEHIND the checkout head, applies the pending migrations via a NO-APP-BOOT mechanism (a flyway CLI container over the checkout's migration directory — never a collector/provider boot: a collector boot runs ingest/eval workers and mutates the replica); an incompatible history (applied version absent from the checkout, checksum drift) refuses loud with the M1-819-style message — probes: wiring test feeds a fake history and asserts both the apply path argv and the refusal; `grep -c 'collector\|provider' scripts/replica-restore.sh` shows no app-service boot step."
  - "Eval-scope seeding: the five fixed-UUID scopes of scripts/eval-scopes-seed.sql (instance-agnostic, idempotent) are applied to the replica and the script's probe readout returns 5/0/0 (scopes/subscriptions/exclusions) — probe: the fingerprint verb's output contains the 5/0/0 probe lines (operator run record)."
  - "PIN READOUT (analysis P6 + brief ASSUMPTION-class facts re-derived): the fingerprint verb prints, and the operator run record under .bench/retrieval-eval/fam-replica/ restates: the world fingerprint in the runner's exact render (ready=…;max_ready_at=…;uid_sha256=… over the D59 world, the RetrievalEvalRunnerIT.dbFingerprint :397-429 SQL shape — ready count expected ~7300, brief-given, re-derived here), world_embedding_coverage (READY-world posts WITH a post_embedding row / total READY-world posts), the embedding_metadata singleton (model identifier + dimension — the identity discipline of docs/spec/llm.md §Embedding pipeline), and the scope_preferences language census (expected <redacted-language-census> + the five seeded eval scopes, brief-given, re-derived) — probe: two consecutive fingerprint reads are byte-identical (frozen by construction) and every key resolves in the run record."
  - "mvn verify from repo root is green (FamReplicaRestoreWiringTest runs in the default suite, plain JUnit, no DB, no docker); git diff --name-only names exactly the files_scope paths plus board/frontmatter regen; no path under .opencode/worktrees/** is touched (analysis P16) — probe: git status --porcelain."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/FamReplicaRestoreWiringTest.java
      — fake-docker argv-log legs (the RestoreWiringTest pattern): the
      ordering leg (P3/P4), the refusal legs (P2), the schema-reconciliation
      leg (P5), and a no-stdin-restore discriminator.
  preserves:
    - all tests currently green on main (the new test is plain JUnit; no
      existing test is modified).
spec_refs:
  - docs/spec/deployment.md §Backups, rotation, secrets
  - docs/spec/security.md §Threat model
  - docs/spec/llm.md §Embedding pipeline
decision_refs:
  - D19
  - D29
  - D34
  - D54
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-08-28
    verdict: REWORK
    checks: 'SPEC-TRUTHNESS: PASS, SECURITY: PASS, TEST-ADEQUACY: FAIL, MAINTAINABILITY: PASS, SCOPE: PASS'
    diff_stats: '4 files, +1091/-10 (script 461, test 607, board+frontmatter)'
  - round: 2
    date: 2026-08-28
    verdict: APPROVE
    checks: 'SPEC-TRUTHNESS: PASS, SECURITY: PASS, TEST-ADEQUACY: PASS, MAINTAINABILITY: PASS, SCOPE: PASS; r1 item 1 SATISFIED'
    diff_stats: 'fix hunks: test probe +1/-1; bookkeeping +16 (reviews entry, Round 1 rework section, board row)'
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  2026-08-28: >-
    Lint 0 findings. Census re-ran clean (no fam-replica mention in scripts/,
    docs/measurement/, infochat-provider/, src/test/). All file:line citations
    spot-checked true (backup.sh:135-138, restore.sh:12-17, eval-scopes-seed.sql:5-12,
    RetrievalEvalRunnerIT dbFingerprint :397-429 + FINGERPRINT_INSTANT render :91-93).
    Analysis pitfalls P2/P3/P4/P5/P6-half/P9-half/P13-adj/P14/P16 all present in
    ticket. blocked_by empty (no seam tests to trace). replaces empty; the M1-945-eval
    worktree is P16-forbidden as a source, not prior art to read. No in-flight tick
    tickets → no module overlap; --parallel runs in this ticket's own fresh worktree. Live facts verified
    read-only: fam postgres container the source postgres container (the live source port),
    test stack postgres up at 15432 (refusal targets), checkout migration head V86,
    fam at prod rev ae295434 → behind-head migration case is the live one. No
    blocking question: replica port/project/image defaults and flyway-CLI tag
    resolution are execution choices inside the ticket's stated behavior. The
    worktree is this ticket's own fresh worktree — never the P16 probe one.
escalation_reason:
---

# M1-948: Isolated fam replica: dump, restore, pin fingerprint

## Context

The retrieval-eval instrument is being re-anchored as a two-leg instrument
(analysis `analysis_ref:`): the tech world (corner-case leg) plus an isolated
fam SNAPSHOT replica (broad-distribution leg — economy/world/health/sports
mainstream, ai/cyber tail, ~7300 READY posts, real cs usage). Live fam is
NEVER a measurement target (binding constraint); the replica is a pg_dump of
it, restored into an isolated eval postgres and frozen by construction.
Nothing committed today can produce that replica reproducibly or fence it
against the live instance — this ticket is the extraction instrument: a
committed, refusal-fenced operator script plus its CI-runnable wiring test.
It delivers the pinned replica fingerprint, embedding coverage, and census
that M1-949's labels, M1-950's manifest pins, and M1-952's record all cite.

## Root cause

Not a code defect — a missing operator instrument (the M1-928 posture).
Verified: no `scripts/replica-restore.sh` exists (scripts/ holds
`eval-scopes-seed.sql` + lint/gate scripts); no committed procedure names an
isolated fam postgres. The mechanics exist as prior art and are adopted, not
re-invented: in-container `pg_dump -F c` with the password from container env
(`prod/scripts/backup.sh:135-138`); restore-order postgres-alone → pg_restore
BEFORE any Flyway pass (`prod/scripts/restore.sh:12-17`); `docker cp` +restore
the in-container path, never stdin (memory pg-restore-fixture-via-docker-cp);
fam's topology — own postgres `the live source port`, volume
`the source volume`, prod rev `ae295434`, DNS-collision discipline
(memory-local fam-instance-topology). The eval-scope seed SQL is
instance-agnostic (five fixed UUIDs, `scripts/eval-scopes-seed.sql:5-12`) and
seeds the replica unchanged.

## Pitfalls

Numbered per the analysis document; this ticket carries P2, P3, P4, P5,
P6 (measurement half), P9 (handling half), P13-adjacent freeze duties, P14,
P16.

- P2: live-fam leakage — a mis-pointed target measures LIVE fam; fences:
  port/volume/network refusals + the isolated project; the label-fingerprint
  fence (M1-950) is the runtime tripwire.
- P3: stdin pg_restore loses the custom-format header — docker cp + in-container
  path, asserted in the argv log.
- P4: restore order — pg_restore before ANY schema step (restore.sh:12-17);
  ordering asserted in the argv log.
- P5: schema/rev skew — dump at ae295434 vs checkout head; verify history,
  apply pending migrations with NO app boot (a collector boot runs ingest/eval
  workers and mutates the replica); fingerprint computed LAST so the pin
  describes the end state.
- P6 (measurement half): coverage is measured + pinned here, never completed
  (a backfill mutates the search space under a post-only fingerprint).
- P9 (handling half): the dump carries real users' rows + audit/quarantine
  content — operator-local, encrypted-at-rest posture (D34), never committed;
  no user-derived text in any committed artifact.
- P14: replica freeze/isolation — own compose project/volume/network, no fam
  network join (the fam DNS-collision discipline inverted); no app deployment
  step exists in the script at all.
- P16: the NEVER-merge probe worktree (.opencode/worktrees/M1-945-eval) is
  never a source of code or dumps for this ticket.

## Approach

- **Files to touch** — `files_scope`: the operator script and its wiring
  test (plus operator-local run artifacts under gitignored
  `.bench/retrieval-eval/fam-replica/`).
- **Steps in implementation order:**
  1. Write `FamReplicaRestoreWiringTest` RED (workflow §0): the ordering,
     refusal, schema-reconciliation, and no-stdin legs against a stub script
     (or its absence).
  2. Author the script: three verbs (dump | restore | fingerprint), the
     backup.sh dump shape, the isolation fences, the docker-cp restore, the
     history verification + no-app-boot migration step, the eval-scope seed,
     the pin readout (P2/P3/P4/P5/P14).
  3. Operator run on this host: dump fam (one-time read-only), restore into
     the isolated postgres, seed, read + record the pins; two consecutive
     fingerprint reads byte-identical (P6 measurement half, P9 handling
     half).
  4. `mvn verify` green from the repo root; diff fence.
- **Controls to preserve (§10):** nothing in production is rerouted; the
  script's only live-fam contact is the read-only dump; the default suite
  gains one plain-JUnit test; the campaign's frozen test stack is untouched
  (the replica is a separate postgres; ports 15432/25432 are refused as
  targets).
- **Pitfall→mitigation:** P2→fences + isolated project; P3/P4→argv-log legs;
  P5→history gate + flyway-CLI migration + fingerprint-last; P6→coverage in
  the readout; P9→operator-local dump, no user data in diffs; P14→network
  inspect probe + no app-boot step; P16→diff fence.

## Definition of done

The script exists with all three verbs and passes its wiring test (ordering,
refusals, schema reconciliation, no-stdin); an operator run has produced the
isolated replica with the eval scopes seeded (5/0/0 probe) and a run record
restating fingerprint + coverage + embedding identity + language census with
two byte-identical consecutive fingerprint reads; `mvn verify` is green; the
diff touches nothing outside `files_scope`; no user-derived data and no
worktree path appears in the committed diff.

## Verification

- P2 → FamReplicaRestoreWiringTest refusal legs: hostile inputs (port 15432,
  port 25432, non-empty target volume, missing dump) each exit nonzero naming
  the offending value; the network-isolation operator probe.
- P3/P4 → the ordering leg's argv log: postgres-up → docker cp → in-container
  pg_restore → schema steps → seed → fingerprint; no stdin-piped restore.
- P5 → the schema-reconciliation leg: a behind-head fake history applies the
  pending migrations via the no-app-boot mechanism; an absent-checkout version
  or checksum drift refuses with the M1-819-style message; grep shows no
  collector/provider boot step in the script.
- P13-adjacent (freeze duties) → the ordering leg's argv log ends with the
  fingerprint read — AFTER the schema and seed writes — so the pin describes
  the replica's END state, and the acceptance probe's two consecutive
  fingerprint reads are byte-identical; a read that fails to repeat is a
  STOP, never an obstacle to route around (the tech leg's byte-exactness
  discipline, mirrored at bring-up time).
- P6 → the fingerprint verb's output carries `world_embedding_coverage`
  (with/total); the run record restates it.
- P9 → git status/diff fence: only the two files_scope paths (+board); grep of
  the committed diff for user-shaped data returns nothing; the dump lives
  under gitignored .bench/ only.
- P14 → docker network inspect (operator probe, restated in the run record):
  no shared network with the source network / infochat-prod_default.
- P16 → the diff fence names no path under .opencode/worktrees/**.
- acceptance items → the named legs/probes above; the final item via
  `git diff --name-only` and repo-root `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: live fam as a target beyond the one-time read-only
dump; the fam checkout/compose files; labeling (M1-949); harness (M1-950);
the record (M1-952); any production/main-source or spec edit; embedding
-backfill on the replica; committing the dump or any user-derived data. No
pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-948-fam-replica-extraction.md
```

## Round 1 rework

REWORK ITEMS:
1. Finding 1: change the `admin` probe at
   FamReplicaRestoreWiringTest.java:345 to
   `argv.indexOf("-f /tmp/admin-role.sql")` so the psql EXEC line (not the
   docker-cp line) is the pinned position, evaluated via
   FamReplicaRestoreWiringTest.restoreOrderingPostgresAloneDockerCpRestoreSchemaSeedPinLast:
   green on the unchanged script, RED under the mutation "move
   scripts/replica-restore.sh:283 to immediately after :286".
