---
id: M1-954
title: "Re-land the fam replica restore procedure instance-free"
status: done
created: 2026-08-29
last_updated: 2026-08-29
flow: tick
reproduction: >-
  Probe (instrument ticket, the M1-948/M1-952 posture — the artifact IS the
  deliverable; no committed procedure can exist before it is re-authored):
  `ls scripts/replica-restore.sh` returns "No such file or directory"
  (verified 2026-08-29; scripts/ holds eval-scopes-seed.sql,
  tech-drift-restore.sql and the lint/gate/measure scripts only — glob
  verified) and M1-948's second files_scope artifact — the wiring test its
  committed files_scope names under the provider wiring package — exists
  nowhere in the tree (a filesystem glob for `*FamReplicaRestore*` under
  every src/test tree returns nothing; `git log --all --oneline --
  '*FamReplicaRestore*'` returns empty, brief-verified 2026-08-29 — the
  lost file died uncommitted in the purged worktree). Observed
  consequence: the DONE ticket M1-948 (round-2 APPROVE; its committed
  files_scope names exactly these two artifacts) landed docs-only in a
  squash-merge mishap, so M1-950's COMMITTED fam-leg pins — all 46 records
  of infochat-provider/src/test/resources/retrieval-eval/golden-set-fam.jsonl
  and the validator constant FAM_REPLICA_FINGERPRINT
  (RetrievalGoldenSetTest.java:59) — bind to a replica whose committed
  reproduction procedure does not exist: the broad leg is irreproducible
  from a fresh checkout, and M1-952 (pending) must cite a committed
  procedure. Entry, run RED at start against the absent
  script (workflow §0), .scratch/tick-red-M1-954.log (2026-08-29, 15/15
  legs red — 14 assertion failures + the source-scan NoSuchFile error on
  the missing script):
  ReplicaRestoreWiringTest#restoreOrderingPostgresAloneDockerCpRestoreSchemaSeedPinLast
  — the ordering leg drives the absent script via ProcessBuilder under a
  fake docker (argv log, the RestoreWiringTest pattern), so it compiles
  today and fails RED because the script is missing; the remaining legs of
  the same new class land per test_plan.
analysis_ref: self
blocked_by: []
files_scope:
  - scripts/replica-restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/ReplicaRestoreWiringTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY verbatim re-land of the operator-local original
    (.agents/memory-local/fam-replica-restore.sh) — it is instance-bound
    (hardcoded replica project/container/port and source container name) and
    is the ADOPTION SOURCE for mechanics only; engineering-rules §13 (landed
    c828b6bf after the mishap) forbids committing its literals — probe: the
    masked-token grep of acceptance item 2.
  - >-
    ANY production / main-source change and ANY docs/spec/** edit — probe:
    git diff --name-only names no src/main and no docs/spec path.
  - >-
    ANY change under the live fam checkout or its compose files, and LIVE fam
    as a target beyond the dump verb's one-time read-only in-container pg_dump
    (unchanged from M1-948): no eval/labeling/measurement rides the live
    source port or its compose network; no fam service is stopped, paused, or
    reconfigured.
  - >-
    ANY run of the restore verb, teardown, volume rebuild, or migration
    against the EXISTING pinned replica — the frozen world stays frozen
    through M1-952 (its fingerprint is committed by M1-949/M1-950 and cited by
    every fam artifact); the operator re-validation is fingerprint-verb-only,
    read-only (acceptance item 9).
  - >-
    Modifying M1-949/M1-950 landed artifacts (golden-set-fam.jsonl,
    RetrievalGoldenSetTest, RetrievalEvalWorlds, the runner) — the committed
    pin is consumed read-only as the re-validation oracle; this ticket changes
    no instrument code.
  - >-
    Editing M1-952's ticket file inside this ticket's diff — the sequencing
    edit (M1-952's blocked_by gains M1-954) is the driver's allocation-time
    action, not a rider on this diff.
  - >-
    Completing the replica's embedding coverage or ANY mutation of its search
    space (a backfill mutates the world under a pinned fingerprint — analysis
    lineage P6 of the two-world campaign); scrubbing the parked §13 material
    from the gitignored two-world analysis doc (the user's separate call).
acceptance:
  - "REPRODUCTION closed: scripts/replica-restore.sh exists (committed, executable 0755, set -euo pipefail, shellcheck-clean author-run) with three verbs — dump (in-container pg_dump -F c of the source DB via docker exec, PGPASSWORD from the container env, no secret on the host, NO docker exec -T (docker 29 dropped the flag), PGDMP magic check on the output — the backup.sh:135-138 shape), restore <dump>, fingerprint — and -h prints usage for all three. PARAMETERIZED per engineering-rules §13: the source container, the target compose project, and the target host port are REQUIRED flags with NO committed default (a missing flag exits nonzero naming it), the target container/volume/network names are DERIVED from the project flag (compose namespacing), image tags default to pinned public tags (the repo's pinned pgvector postgres image and a flyway CLI image major-matched to the repo's pinned flyway-core — RestoreFlywayChecksumIT's pinning posture, restore.sh:73's pinned-image precedent), and the work dir defaults to a generic path under gitignored .bench/ — probes: ReplicaRestoreWiringTest.usageRequiresEveryInstanceShapedFlag (each flag omitted -> nonzero exit naming that flag) and the -h usage leg."
  - "PLACEMENT (engineering-rules §13; failure-mode): the committed script and test name NO instance — the script contains no literal for the source container, the replica's project/container/volume/network, or its host port; the ONLY instance-shaped literals are the two refusal-fence ports 15432 (frozen test stack) and 25432 (live source instance), committed-precedent-OK per M1-948's landed acceptance and M1-950's round-1 adjudication, plus the fixture eval-scope UUIDs (scripts/eval-scopes-seed.sql — fixture values) — probes: (a) the operator substitutes each masked token <source-container>, <replica-project>, <replica-container>, <replica-port> (concrete values recorded in the gitignored .bench/retrieval-eval/README.md fam-restore section) and greps the tracked diff for each — zero matches over tracked files; (b) ReplicaRestoreWiringTest.carriesNoPortShapedLiteralOutsideTheAllowlist — every port-shaped numeric literal in the script text is one of {5432 (in-container postgres), 15432, 25432 (fences)}; (c) the wiring test's class and method names carry no instance vocabulary (generic names, this ticket's constraint)."
  - "ISOLATION FENCES (failure-mode): the restore verb REFUSES (exit nonzero, naming the offending value) a target port of 15432, a target port of 25432, a non-fresh target volume (a docker volume already exists under the derived name), a missing dump file, and a restore attempted while any foreign container is attached to the replica network; the isolated postgres runs in its OWN compose project + network + volume, loopback-published, with NO join to any source or prod network (the DB-reach boundary of docs/spec/security.md §Threat model — only the two services and the operator reach a DB — holds by construction) — probe: ReplicaRestoreWiringTest refusal legs (fake-docker argv pattern, the RestoreWiringTest precedent) feed each hostile input, assert the named refusal + nonzero exit, and assert the argv log shows NO mutation step after the gate."
  - "RESTORE ORDER (the M1-948 round-1-pinned shape): the argv log proves postgres-up ALONE -> infochat_admin NOLOGIN reconstruction (the restore.sh M1-570 shape: the dump's ACLs grant to the Flyway-V2-created principal, which a single-DB dump cannot carry) -> docker cp the dump -> in-container pg_restore of the in-container path under the bounded ignorable-error gate (ONLY the extension-COMMENT ownership set tolerated, everything else fails loud — restore.sh:587-610's shape) -> flyway-history verification (+ pending apply) -> eval-scope seed -> pin readout LAST (the pin describes the END state); pg_restore precedes every other schema/seed step; each psql leg is distinguishable in argv by a step marker variable (the restore.sh inherited_failed_probe pattern); the ordering pins are argv.indexOf over the psql EXEC lines, NEVER over transfer lines (the M1-948 round-1 rework lesson) — probe: ReplicaRestoreWiringTest.restoreOrderingPostgresAloneDockerCpRestoreSchemaSeedPinLast, RED under the mutation shapes 'move the admin-role step to after the dump docker cp' and 'move the pin read before the seed'."
  - "NEVER-STDIN DISCRIMINATOR (failure-mode): the BINARY custom-format dump is NEVER piped over stdin — docker cp appears in the argv log EXACTLY ONCE (the dump leg) and the pg_restore argv restores the in-container path (memory pg-restore-fixture-via-docker-cp: the pipe path loses the custom-format magic header) — probe: ReplicaRestoreWiringTest.restoreLoadsBinaryDumpViaDockerCpNeverStdin (single docker-cp argv + in-container-path pg_restore argv; RED under a mutation piping the dump via docker exec -i)."
  - "TEXT-SQL TRANSPORT = STDIN (analyst decision, stated): the known daemon defect (the fingerprint verb's docker cp of pin-read.sql FAILS under the current daemon — operator-host fact, .agents/memory-local/MEMORY.md fam-replica-restore.sh entry) is folded in: admin-role.sql, eval-scopes-seed.sql, and pin-read.sql travel as stdin pipes to in-container psql (docker exec -i <container> sh -c '…psql…' < file — the restore.sh:535-544 text-SQL-over-stdin precedent; text SQL has no custom-format hazard — the NEVER-stdin fence is the dump's alone); the fingerprint verb contains NO docker cp at all; the dump-leg docker cp is FENCE-LOAD-BEARING and stays (disclosed residual: unproven under the current daemon — this ticket runs no restore, and the first real restore re-proves it or fails loud; recorded with the flag values in the gitignored README) — probes: the ordering leg asserts no docker cp outside the single dump leg; ReplicaRestoreWiringTest.fingerprintVerbExecutesPsqlOnly (drives the fingerprint verb under the fake docker: argv log shows the psql exec and ZERO docker cp)."
  - "SCHEMA RECONCILIATION WITHOUT AN APP BOOT (failure-mode x2): the restored flyway_schema_history is verified against the checkout's migration set by the M1-819 gate (per-script checksums recomputed dependency-free — the restore.sh:628-731 shape, pinned to the repo's flyway-core by RestoreFlywayChecksumIT); when the restored history is BEHIND the checkout head, pending migrations are applied via a flyway-CLI container over the mounted migration directory on the replica network — NEVER a collector/provider boot (a collector boot runs ingest/eval workers and mutates the replica); an applied version absent from the checkout refuses loud with the newer-source-revision diagnosis, and a checksum drift refuses loud naming the drifted versions with both recovery options — probes: ReplicaRestoreWiringTest.restoreAppliesPendingMigrationsViaFlywayCliNeverAnAppBoot (behind-head fake history -> a docker run argv whose image is the flyway CLI and whose command is migrate; no app image anywhere in the log) and .restoreRefusesAbsentAppliedVersionAndChecksumDrift (both named refusals); plus grep -nE 'up -d|docker run' scripts/replica-restore.sh shows only the isolated-postgres bring-up and the flyway-CLI apply."
  - "EVAL-SCOPE SEED: scripts/eval-scopes-seed.sql (instance-agnostic, idempotent, five fixed fixture UUIDs) is applied over stdin and the script's probe readout asserts exactly 5/0/0 (eval_scopes / eval_scope_subscriptions / eval_scope_exclusions) — a nonzero-delta abort fails loud before the pin read — probe: the ordering leg's fake docker returns the seed probe rows and the run completes to the pin read; a corrupted-delta variant (fake seed output 4/0/0) aborts nonzero before any pin read (ReplicaRestoreWiringTest.seedProbeDeltaAbortsBeforePinRead)."
  - "PIN READOUT LAST + OPERATOR RE-VALIDATION (read-only; the committed-oracle leg): the fingerprint verb prints the world fingerprint in the runner's exact render (the WORLD_WHERE predicate with the en eval scope — the committed shape of scripts/tech-drift-restore.sql:28-40 and RetrievalEvalRunnerIT.dbFingerprint), world embedding coverage (with/total), the embedding_metadata identity row, the scope-language census, and the 5/0/0 probe; two consecutive reads are byte-identical. The operator runs ONLY the fingerprint verb against the EXISTING pinned replica (no restore, no teardown, no migration — the frozen world survives through M1-952) using the concrete flag values recorded in the gitignored .bench/retrieval-eval/README.md fam-restore section (placement per §13: instance values live ONLY there, next to the fam invocation); the world_fingerprint line byte-equals the committed oracle RetrievalGoldenSetTest.FAM_REPLICA_FINGERPRINT (read from the tree at verification time; the M1-950-adjudicated committed pin — no value quoted here) and the operator-local run record under .bench/retrieval-eval/fam-replica/ restates the full readout — probes: the operator byte-equality diff against the constant; two consecutive reads identical (both restated in the run record); grep of the gitignored README shows the fam-restore section with every required flag value."
  - "mvn verify from repo root is green (ReplicaRestoreWiringTest runs in the default suite: plain JUnit, Linux-gated, no DB, no docker daemon — the fake docker on a restricted PATH, the RestoreWiringTest pattern; no existing test modified); git diff --name-only names exactly the two files_scope paths plus board/frontmatter regen; no path under .opencode/worktrees/** or .worktree/** is touched — probes: git diff --name-only; git status --porcelain."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/ReplicaRestoreWiringTest.java
      — fake-docker argv-log legs (the RestoreWiringTest pattern, re-authored
      from M1-948's test_plan + acceptance contract — the original file is
      lost): usage/required-flag legs, the §13 port-allowlist source-scan leg,
      the refusal legs, the ordering leg
      (restoreOrderingPostgresAloneDockerCpRestoreSchemaSeedPinLast), the
      never-stdin discriminator, the fingerprint-no-docker-cp leg, the
      schema-reconciliation legs, the seed-delta abort leg.
  preserves:
    - all tests currently green on main (the new test is plain JUnit; no
      existing test is modified).
spec_refs:
  - docs/spec/deployment.md §Backups, rotation, secrets
  - docs/spec/security.md §Threat model
  - docs/spec/llm.md §Embedding pipeline
decision_refs:
  - D34
  - D43
  - D59
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
    date: 2026-08-29
    verdict: REWORK
    checks: "SPEC-TRUTHNESS: WARN; SECURITY: PASS; TEST-ADEQUACY: WARN; MAINTAINABILITY: WARN; SCOPE: PASS"
    diff_stats: "4 files, +1125/-11 (script 495 new, wiring test 598 new, board/frontmatter bookkeeping)"
    notes: >-
      3 REWORK items (all low): (1) the acceptance items 1/2b promised probe
      names usageRequiresEveryInstanceShapedFlag and
      carriesNoPortShapedLiteralOutsideTheAllowlist, but the behaviors
      landed as missingInstanceFlagFailsLoudNamingTheFlag and
      sourceScanHoldsThePortAllowlistAndNoPseudoTty — rename to the
      promised names; (2) nothing ties the script's flyway_checksum copy to
      the pinned prod original (RestoreFlywayChecksumIT extracts
      prod/scripts/restore.sh only) — add a byte-identity leg; (3) the
      script header retells the M1-948 re-land chronicle (§11) — rewrite
      contract-only. Gate binding deviation recorded in the round's
      mechanical report (general-purpose spawn per harness-mapping §2
      fallback; contamination check clean).
  - round: 2
    date: 2026-08-29
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "fix hunks: 3 files, +63/-4 (renames x2, byte-identity leg + helper, header rewrite); cumulative 4 files, +1184/-12"
    notes: >-
      All three round-1 items SATISFIED (reviewer-verified: independent
      python extraction of both flyway_checksum bodies reports
      byte-identical; the mutate-one-byte probe went RED then green on
      restore). r2 log: full suite green, ReplicaRestoreWiringTest 16/16
      in the default suite. No RECOMMENDED-NEW-TICKET entries.
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-08-29
  result: passed
  note: >-
    All Root cause/Approach citations re-verified in the worktree at 549e0917:
    scripts/ holds exactly the named files (glob); the 46-record
    golden-set-fam.jsonl; RetrievalGoldenSetTest FAM_REPLICA_FINGERPRINT
    (~:58-61); tech-drift-restore.sql:28-40 WORLD_WHERE shape matches the
    operator-local pin-read; eval-scopes-seed.sql ends in the 5/0/0 probes;
    V87 is the checkout head, V86 the replica's restore point;
    docker-compose.yml:45 pins pgvector/pgvector:pg16 (restore.sh:73
    pinned-image precedent); flyway-core 12.0.0 (RestoreFlywayChecksumIT
    posture) -> flyway/flyway:12; restore.sh:12-17/535-544/587-610 shapes
    and backup.sh:135-138 read and adopted (backup.sh's literal exec -T is
    the docker-29 breakage P6 names — not adopted). blocked_by is empty
    (nothing to trace). Design resolution for the re-validation: the
    existing pinned replica was created with an EXPLICIT container_name, so
    the derivation is container_name: <project>-postgres in the generated
    compose (volume <project>_pgdata, network <project>_default) — the
    committed script stays instance-free while the fingerprint verb still
    addresses the existing container via the recorded gitignored flag
    value; disclosed in the README fam-restore section and the run record.
escalation_reason:
---

# M1-954: Re-land the fam replica restore procedure instance-free

## Context

M1-948 (done, round-2 APPROVE) authored the fam-replica extraction
instrument — a committed, refusal-fenced dump/restore/fingerprint operator
script plus its CI-runnable wiring test — but its squash-merge landed
docs-only (a merge mishap, not a scrub; the artifacts died uncommitted in
the since-purged worktree `.opencode/worktrees/M1-948/`, an empty husk —
recovery exhausted: filesystem, reflogs, fsck, all negative). Verified this
session at main tip `ff38802a`: `scripts/replica-restore.sh` does not
exist (scripts/ holds `eval-scopes-seed.sql`, `tech-drift-restore.sql` and
the lint/gate/measure scripts only) and no `*FamReplicaRestoreWiringTest*`
file exists anywhere. Meanwhile the family moved AROUND the hole: M1-949
and M1-950 landed fam-leg artifacts whose pins BIND to that replica — all
46 records of `golden-set-fam.jsonl` carry
`labeled_against.db_fingerprint` pinning the replica's end state, and
`RetrievalGoldenSetTest.java:59` (`FAM_REPLICA_FINGERPRINT`) enforces it —
so a fresh checkout carries committed pins pointing at a world whose
committed reproduction procedure does not exist, and M1-952 (pending, the
two-leg record) must cite a committed procedure. The re-land cannot be
verbatim: engineering-rules §13 ("deployment-identifying material never
commits", landed `c828b6bf` AFTER the mishap) now forbids the instance
literals the original carried (its hardcoded replica
project/container/port and source container name). The executable
operator-local original survives ONLY at
`.agents/memory-local/fam-replica-restore.sh` (gitignored, 461 lines,
instance-bound) — the adoption source for mechanics, never committable
verbatim. Single-ticket analysis (`analysis_ref: self`); lineage: the
two-world campaign analysis (gitignored
`docs/plan/m1/tick-analysis/two-world-retrieval-instrument.md`) and the
done M1-948 are prior art this ticket re-lands under §13 — M1-948 stays the
historical record (`replaces:` deliberately empty: it is not superseded,
its reviews and operator run record stand; only its artifacts are re-landed).

## Root cause

A process accident, not a code defect: the M1-948 squash-merge captured the
ticket/board files but not the two code artifacts (git-plumbing details are
brief-verified — `git show --stat` on the merge commit names only
`docs/plan/m1/STATUS-TICK.md` and the M1-948 ticket file; the hash is not
reachable from this session's reflogs, so the file-level consequence is
what is independently verified here: both artifacts absent at `ff38802a`
while M1-948's committed `files_scope` names them). The missing procedure
was survivable only while the replica stayed up and operator-local memory
carried the script; M1-950's committed pins (and M1-952's pending record)
turn the absence into a repo-visible defect: the broad leg's provenance
chain is broken at the "reproducible from a fresh checkout" link. The
re-land is forced through a §13 re-authoring (parameterization) because the
original's literals are now uncommittable — and through a from-scratch
wiring test because the original test file is lost with the worktree
(M1-948's acceptance + test_plan + round-1 rework item are the surviving
contract; the round-1 lesson — pin the psql EXEC line, never the transfer
line — is carried as design input).

## Pitfalls

- P1: Verbatim re-land trap (§13, landed `c828b6bf`): copying the
  operator-local original commits its instance literals (replica
  project/container/port, source container name) — a placement FAIL at
  review (SCOPE-CHECK placement leg: "the ticket itself cannot license a
  placement the rules forbid"). Mechanics adopt; literals never.
- P2: Live-instance leakage (two-world P2; measurements-never-ride-prod-
  containers): a mis-pointed target writes/measures the LIVE source
  instance (25432) or the frozen test stack (15432). Fences: port refusals
  naming the value, fresh-volume refusal, foreign-network-member refusal,
  own project/network/volume (security.md:24 — only the two services and
  the operator reach a DB — holds by construction).
- P3: Binary-dump stdin trap (memory pg-restore-fixture-via-docker-cp;
  two-world P3): piping the custom-format dump over stdin loses the PGDMP
  magic header — docker cp in, pg_restore the in-container path, asserted
  in the argv log.
- P4: Restore-order trap (restore.sh:12-17; M1-570; two-world P4): postgres
  ALONE, `infochat_admin` NOLOGIN reconstruction BEFORE pg_restore (the
  dump's ACL grants to the Flyway-V2 principal would otherwise roll back
  the co-located service-role grants), pg_restore BEFORE any other schema
  step. Pin the psql EXEC argv lines, never transfer lines (M1-948 r1).
  NB: the brief's ordering list loosely places "admin-role/schema steps"
  after pg_restore; the verified mechanics (restore.sh:535 before :558; the
  M1-948 r1 mutation — moving the admin-role step after the dump docker cp
  — must RED the ordering leg) put the role reconstruction BEFORE the
  restore; this ticket pins the verified order.
- P5: Schema/rev skew (two-world P5; M1-819): the restored history vs the
  checkout's migration set must be verified by checksum; pending migrations
  applied via a flyway-CLI container, NEVER an app boot (a collector boot
  runs ingest/eval workers and mutates the replica). The checkout's head is
  now V87 (`V87__post_search_tags.sql`; the replica was restored at V86), so
  the behind-head case is the live one for any future fresh restore. The
  EXISTING replica is never migrated by this ticket.
- P6: Daemon-defect fold-in (operator-host fact, memory-local): the
  fingerprint verb's `docker cp` fails under the current daemon; the
  restore verb's docker-cp calls are unproven since the change; docker 29
  dropped `exec -T` (backup.sh's literal `-T` breaks — never use it).
  Decision (stated): text SQL travels over stdin; the dump docker cp stays
  (fence-load-bearing) as a disclosed unproven residual.
- P7: Placement/data discipline (§13; two-world P9; D34): dumps, run
  records, and user-derived data never commit; instance values live only in
  the gitignored `.bench/retrieval-eval/README.md` + `.bench/` stores; the
  committed script may state the local procedure exists and where its class
  of artifact lives, without naming the instance.
- P8: Wiring-test calibration (the M1-785 lesson: fixtures calibrate to the
  END state): the test is re-authored from the CONTRACT, not from the lost
  file; its legs must match the stdin decision — a leg asserting docker cp
  for SQL files would pin the pre-daemon-defect behavior this ticket
  removes; generic class/method names (instance-free); the argv-log legs
  need the script's psql steps distinguishable (step-marker variables, the
  restore.sh `inherited_failed_probe` pattern).
- P9: Frozen-world protection: the existing pinned replica (committed pin
  cited by M1-949 labels + M1-950 fixtures/validator) must not be torn
  down, migrated, or restored-over before M1-952 (the standing
  memory-local rule). The re-validation is fingerprint-verb-only,
  read-only. A refusal during re-validation is a STOP, never an obstacle
  to route around.
- P10: Worktree hygiene (the two-world campaign's never-merge-worktree
  rule): no path under `.opencode/worktrees/**` or `.worktree/**` is
  touched or copied; the empty M1-948 husk is evidence, not a source.
- P11: Sequencing: M1-952 (pending, currently runnable) must not start
  before this lands — its results commit cites "the replica pin from
  M1-948's readout", whose reproduction procedure this ticket restores.
  The driver adds M1-954 to M1-952's `blocked_by` at allocation time (not
  a rider on this diff).
- P12: Probe finality/masking (the M1-950 round-1/2 lessons): an acceptance
  probe quoting an instance literal publishes it (M1-950 r1 SCOPE FAIL);
  mandated records quote probes with the literal masked (`<replica-port>`
  style, operator substitutes from the gitignored README) — and the
  committed pin VALUE is cited by constant name, never quoted in prose.

## Approach

Re-author, don't restore: the script is rewritten parameterized (mechanics
adopted from the operator-local original and the committed prod-script
shapes), and the wiring test is written fresh from M1-948's contract.
Spec-ground: deployment.md §Backups, rotation, secrets (D34 — the dump is
sensitive operator material, encrypted-at-rest posture, never committed;
"specific backup tooling … are operator concerns", which is exactly what
this operator script is); security.md §Threat model (:24 — the DB-reach
boundary the isolation fences preserve); llm.md §Embedding pipeline (the
identity discipline the readout pins: model identifier + dimension, and
the model-must-not-change rule that makes the coverage pin meaningful).

- **Files to touch** — `files_scope`: the script and its wiring test (plus
  operator-local artifacts: the re-validation run record under
  `.bench/retrieval-eval/fam-replica/` and the fam-restore section of the
  gitignored `.bench/retrieval-eval/README.md`; nothing else commits).
- **Steps in implementation order:**
  1. `ReplicaRestoreWiringTest` RED (workflow §0): all legs against the
     absent script — the legs drive the real script via ProcessBuilder
     under a restricted PATH with a parametrized fake docker (argv log),
     the RestoreWiringTest pattern; they compile today and fail because the
     file is missing (P8).
  2. Author `scripts/replica-restore.sh`: required flags (source
     container, target project, target port) with no defaults; derived
     container/volume/network; pinned public image tags; generic work-dir
     default under `.bench/`; three verbs (dump | restore | fingerprint);
     the fences (P2); admin-role-before-restore, docker-cp dump, bounded
     ignorable-error pg_restore (P3/P4); M1-819 checksum gate + flyway-CLI
     pending apply, no app boot (P5); text SQL over stdin, no `exec -T`,
     step-marker psql legs (P6/P8); eval-scope seed + 5/0/0 probe; pin
     readout LAST (P4); §13-clean text (P1/P7).
  3. `mvn verify` from the repo root green (the fake-docker legs carry the
     fences; no DB, no daemon).
  4. Operator re-validation, read-only: record the concrete flag values in
     the gitignored README's fam-restore section; run ONLY the fingerprint
     verb against the existing replica; two consecutive reads
     byte-identical and byte-equal to the committed
     `RetrievalGoldenSetTest.FAM_REPLICA_FINGERPRINT` oracle; restate the
     full readout in the operator-local run record (P7/P9/P12).
  5. Diff fences; hand off for review (the ff38802a-hardened gate: the
     placeholder scan sees the masked tokens as tier-2 §13 masking; every
     probe is final — each either runs literally or names its
     operator-substituted token's store).
- **Controls to preserve (engineering-rules §10):** no production path is
  rerouted; the script's only live-source contact is the dump verb's
  read-only in-container pg_dump (unchanged from M1-948); the frozen test
  stack is untouched (15432 refused as a target); the pinned replica is
  untouched (read-only re-validation; teardown forbidden through M1-952);
  `scripts/eval-scopes-seed.sql` is consumed read-only; the default suite
  gains exactly one plain-JUnit Linux-gated test and modifies no existing
  test (RestoreWiringTest and every wiring sibling byte-identical).
- **Pitfall→mitigation:** P1→step 2 parameterization + acceptance item 2
  probes; P2→fences + refusal legs; P3→never-stdin discriminator; P4→
  ordering leg with EXEC-line pins + the two named mutation shapes;
  P5→schema legs + no-app-boot grep; P6→stdin decision + no-docker-cp
  fingerprint leg + disclosed residual; P7→gitignored placement + masked
  tokens; P8→RED-first contract-driven test + step markers + generic
  names; P9→read-only re-validation + stop-not-route-around; P10→diff
  fence; P11→driver sequencing note; P12→masked probes + cited-not-quoted
  oracle.
- **Alternatives considered** (rejected; the commit message cites them):
  (a) verbatim re-land of the memory-local original — §13 forbids its
  literals; (b) script-only, no wiring test — the fences/ordering would be
  unenforceable and M1-948's approved contract named both artifacts;
  (c) fold into M1-952 — that ticket is a doc-only measurement record whose
  own out_of_scope forbids test changes, and the re-land must land BEFORE
  it; (d) generic placeholder DEFAULTS for the instance-shaped flags
  instead of required flags — any defaulted port/project risks colliding
  with a real instance; required flags fail loud (chosen: no defaults).

## Definition of done

The script exists, parameterized and §13-clean, with the three verbs, all
fences, the adopted mechanics, and the daemon-defect fold-in; the wiring
test (generic names, fake-docker argv legs) pins ordering, refusals,
schema reconciliation, the never-stdin discriminator, the stdin text-SQL
transport, and the fingerprint verb's no-docker-cp shape, all green under
repo-root `mvn verify`; the operator re-validation has run read-only with
two byte-identical consecutive reads byte-equal to the committed oracle,
the flag values and run record landed in the gitignored stores; the
committed diff names exactly the two files_scope paths plus board regen;
M1-952 remains unstarted, sequenced behind this ticket by the driver.

## Verification

- P1 → acceptance item 2 probes (masked-token greps over the tracked diff;
  port-allowlist source-scan leg).
- P2 → ReplicaRestoreWiringTest refusal legs: hostile inputs (port 15432,
  port 25432, pre-existing derived volume, missing dump, foreign
  network member) each exit nonzero naming the value, argv log shows no
  mutation after the gate.
- P3 → restoreLoadsBinaryDumpViaDockerCpNeverStdin: exactly one docker-cp
  argv (the dump), pg_restore argv names the in-container path; RED under
  an exec-i dump-pipe mutation.
- P4 → restoreOrderingPostgresAloneDockerCpRestoreSchemaSeedPinLast: argv
  order postgres-up → admin-role psql → docker cp → pg_restore → history
  gate/apply → seed → pin read LAST, pinned via psql EXEC-line markers; RED
  under both named mutations (admin-role moved after the dump cp; pin read
  moved before the seed).
- P5 → restoreAppliesPendingMigrationsViaFlywayCliNeverAnAppBoot (behind-head
  fake history → flyway-image docker run … migrate argv; no app image in
  the log) + restoreRefusesAbsentAppliedVersionAndChecksumDrift (both named
  refusals: newer-source-revision diagnosis; drift with both recovery
  options) + the up -d/docker run grep over the script.
- P6 → fingerprintVerbExecutesPsqlOnly (zero docker cp) + the ordering
  leg's no-docker-cp-outside-the-dump assertion + no `exec -T` anywhere in
  the script (grep, part of the source-scan leg); the unproven dump-cp
  residual is DISCLOSED (gitignored README + ticket text), never hidden.
- P7 → the gitignored-store placement probes (README fam-restore section;
  run record under the fam-replica bench dir); the tracked diff carries no
  dump, no run record, no user-derived data (grep over the diff).
- P8 → the whole test is the contract's executable form (RED-first at
  start; legs match the END state incl. the stdin decision); generic names
  verified by reading the file.
- P9 → the re-validation leg is fingerprint-verb-only by acceptance item
  9's wording; any refusal is a recorded STOP (run record), never routed
  around; no teardown/migration command runs (the ticket's out_of_scope
  names them; the argv log of the re-validation shows only the psql exec).
- P10 → git status/diff fence naming no `.opencode/worktrees/**` or
  `.worktree/**` path.
- P11 → the driver's allocation-time edit adds M1-954 to M1-952's
  blocked_by (observed on the board before M1-952 starts; not part of this
  diff).
- P12 → every probe in this ticket either runs literally or names its
  masked token's store; the oracle is cited by constant name
  (RetrievalGoldenSetTest.java:59), its value quoted nowhere in committed
  prose.
- acceptance items → the named legs/probes above; the final item via
  repo-root `mvn verify` and `git diff --name-only`.

## Out-of-scope

Named in `out_of_scope`: the verbatim re-land (§13); any production or
spec edit; the live fam checkout and live fam as a target beyond the
dump verb's read-only pg_dump; ANY restore/teardown/migration against the
existing pinned replica (read-only re-validation only — the frozen world
survives through M1-952); modifying M1-949/M1-950 landed instrument
artifacts (the committed pin is consumed read-only); editing M1-952's file
inside this diff; embedding backfill or any replica search-space mutation;
the parked §13 scrub of the gitignored two-world analysis doc. No
pre-existing test is modified (test_plan.modifies is empty).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-954-fam-replica-restore-reland.md
```

## Round 1 rework

REWORK ITEMS (verbatim from .scratch/tick-review-M1-954-r1.txt):

1. Finding 1: rename ReplicaRestoreWiringTest.java:126 and :157 to the acceptance-promised probe names (usageRequiresEveryInstanceShapedFlag, carriesNoPortShapedLiteralOutsideTheAllowlist), or amend the ticket's acceptance citations to the landed names — one binding, evaluated via the EVALUATED-AS grep returning both @Test definitions plus `mvn -pl infochat-provider test -Dtest=ReplicaRestoreWiringTest` green (15 tests).
2. Finding 2: add a byte-identity leg to ReplicaRestoreWiringTest tying flyway_checksum (scripts/replica-restore.sh:213) to the pinned original (prod/scripts/restore.sh:635), the RestoreFlywayChecksumIT extraction pattern, evaluated via the mutate-one-byte probe described in EVALUATED-AS plus the module test run green (16 tests).
3. Finding 3: rewrite the header comment at scripts/replica-restore.sh:2-3 to contract-only wording (drop the "re-land of the M1-948 instrument" chronicle; keep the §13/D34 pointers), evaluated via the sed probe plus `bash -n` and the shellcheck container run exiting 0.
