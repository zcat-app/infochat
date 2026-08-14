---
id: M1-820
title: Lint that applied Flyway migrations are content-immutable
status: done
created: 2026-08-13
last_updated: 2026-08-15
flow: tick
reproduction: >-
  scripts/lint-migration-immutability.py --self-test. Before start, the
  script did not exist: ls scripts/ showed lint-ticket.py,
  lint-partitioned-test-inserts.py, lint-config-keys.py, tick-lint.py and no
  migration lint; running this command exited 2 with Python's "can't open
  file" error. Observed wrong behavior: commit a60315c3 ("process: remove private planning artifacts")
  edited comments inside the already-applied V50/V55 migration files and no
  gate fired; every database that applied them earlier now fails Flyway
  validation against this checkout (live incident 2026-08-11,
  .scratch/setup-hurdles.md item 1).
analysis_ref: docs/plan/m1/tick-analysis/restore-robustness.md
blocked_by: []
files_scope:
  - scripts/lint-migration-immutability.py
  - docs/spec/deployment.md
  - docs/design/02-schema.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Wiring the lint into the Maven build, a git hook, or CI. There is no CI
    (.github/ absent) and the house lint posture is author-side manual with
    the reviewer re-running (lint-partitioned-test-inserts.py:12-15); this
    ticket follows it. Build wiring is a separate decision.
  - >-
    The restore-time Flyway-history validation gate (M1-819) — detection at
    restore time is the sibling ticket; this ticket is repo-side prevention.
  - >-
    Editing any migration file (including repairing V50's dangling comment
    at V50__banned_admin_actor_checks.sql:190-194) — the rule this lint
    enforces forbids exactly that; the dangling reference stays or is fixed
    by a doc-side note, never by editing the applied file.
  - >-
    Changes to tick-lint.py / lint-ticket.py and to docs/process/** —
    surfacing the lint in the tick workflow's author checklist is a
    process-doc decision for the user, not a rider here.
acceptance:
  - "scripts/lint-migration-immutability.py --self-test (the reproduction, written and run RED at start as the absent script) passes — the embedded fixtures build scratch git repos and the lint FLAGS a content edit to a migration present on the base branch (including a comment-only edit — the a60315c3 shape), FLAGS a deletion and a rename, and PASSES an added V<N+1> file and a tree with no migration changes (P4)."
  - "The lint flags the real incident: the implementor runs git show a60315c3 --stat and records exactly which migration files it touched (the analyst had no shell tool to run it), then the probe run of python3 scripts/lint-migration-immutability.py against that commit — or a synthetic replay if it is unreachable from current history — shows the violation; command + output recorded in the commit message."
  - "History-unavailable posture is a loud SKIP, never a silent pass (P4) — the probe running python3 scripts/lint-migration-immutability.py outside a git worktree (or where the base ref is unresolvable) exits with a distinct SKIP code/wording naming what could not be checked; command + output recorded in the commit message."
  - "Spec record (engineering-rules §12, the M1-779 rides-the-diff shape — wording approved by the user at implementation time; rule-text only): docs/spec/deployment.md §Topology records that once a migration version has shipped on main its file content is immutable — comment edits included — and corrections land as new versions; docs/design/02-schema.md gains the matching note where the migration conventions live. Verify: git diff shows rule-text only — no dates, ticket IDs, or report citations in spec prose."
  - "mvn verify from repo root is green (no Java change; the build must not regress)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      The lint is a python script with an embedded --self-test (the
      lint-partitioned-test-inserts.py:22-24 precedent); no JUnit coverage —
      a Java test cannot pin git-history state it does not control, and the
      self-test's scratch repos are the honest oracle. mvn verify covers the
      no-regression leg.
spec_refs:
  - docs/spec/deployment.md §Topology
decision_refs: []
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
    date: 2026-08-15
    verdict: APPROVE
    checks:
      SPEC-TRUTHNESS-CHECK: PASS
      SECURITY-CHECK: PASS
      TEST-ADEQUACY-CHECK: PASS
      MAINTAINABILITY-CHECK: PASS
      SCOPE-CHECK: PASS
    diff_stats: "5 files, 220 insertions(+), 14 deletions(-) (r1)"
    verdict_file: .scratch/tick-review-M1-820-r1.txt
    test_log: .scratch/tick-test-M1-820-r1.log (BUILD SUCCESS, full suite, 0 failures)
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-820: Lint that applied Flyway migrations are content-immutable

## Context

Commit a60315c3 edited comments inside the already-applied migrations
V50/V55; nothing in the repo prevented it, and the blast radius arrived
weeks later as a Flyway checksum mismatch crash-loop on a freshly restored
host (live session 2026-08-11, `.scratch/setup-hurdles.md` item 1). The
immutability rule exists today only as tribal knowledge inside individual
tickets (M1-207 ticket:22, M1-210 ticket:84, M1-443 ticket:21 all restate
"applied migrations are immutable (Flyway checksums)") — no standing doc
rule, no mechanical guard, no CI. Analysis:
`docs/plan/m1/tick-analysis/restore-robustness.md`.

## Root cause

Proven by listing: `scripts/` contains no migration lint and `.github/`
does not exist, so no automated check of any kind fires on a migration-file
edit; the rule's only enforcement was reviewer memory. The Collector's
validate-at-startup (docs/spec/deployment.md §Topology, deployment.md:38-54)
is the operational backstop — it catches the drift only when a database
that applied the OLD content boots a checkout carrying the NEW content,
i.e. exactly the restore path, which is the worst possible time to learn
it.

## Pitfalls

Numbered per the analysis document; this ticket carries P4.

- P4: The lint must flag CONTENT changes, not the migration set's
  existence. The observed failure was a comment-only edit, so a
  name-presence check is vacuous. The diff base is the merge-base with
  main: ADDED files pass (new migrations are the legitimate path);
  modified/deleted/renamed files that exist on the base ref are violations.
  When git history is unavailable (export tarball, shallow clone without
  the base), the script exits with a loud SKIP naming what it could not
  check — never a silent pass (M1-818's never-a-silent-pass posture).

## Approach

Derived from `spec_refs:` — deployment.md §Topology's
Collector-migrates-with-validate model is the behavior the immutability
rule protects; the §12 amendment records the rule where that model is
specified.

- **Files to touch** (plan, not allowlist): new
  `scripts/lint-migration-immutability.py`; `docs/spec/deployment.md`
  (amendment, user-approved wording); `docs/design/02-schema.md` (matching
  convention note).
- **Steps in order:**
  1. Confirm the incident commit: `git show a60315c3 --stat` — record which
     migration files it touched in the commit message (acceptance item 2).
  2. The lint: `git diff --name-status <merge-base main>...HEAD --
     '**/db/migration/V*.sql'`; any status other than `A` on a matching
     path is a violation (M/D/R flagged with the file and the status).
     `--self-test` builds scratch git repos in a temp dir covering: clean
     tree, added migration, content edit, comment-only edit, deletion,
     rename, history-unavailable (P4).
  3. The §12 spec amendment (exact wording to the user before it lands —
     rule-text only) + the 02-schema.md note — last, they record the rule
     the lint now enforces.
- **Controls to preserve (§10):** none rerouted — new script plus doc text;
  the existing lints' posture (manual, reviewer re-runs) is followed, not
  changed.
- **Pitfall→mitigation:** P4 → the diff-base semantics and the self-test
  fixture matrix in step 2; the history-unavailable SKIP fixture kills the
  silent-pass shape.

## Definition of done

The self-test passes over the full fixture matrix (comment-only edit
flagged, add passed, delete/rename flagged, no-git SKIP loud); the lint
demonstrably flags the a60315c3 change (or its synthetic replay) with the
command+output recorded in the commit message; the deployment.md §Topology
rule-text record and the 02-schema.md note land with user-approved wording;
`mvn verify` green.

## Verification

- P4 → `python3 scripts/lint-migration-immutability.py --self-test` — the
  failure-mode fixture matrix: content edit, comment-only edit, deletion,
  and rename fixtures must each be flagged, and the comment-only fixture is
  the non-vacuity case — a name-presence-only implementation never flags
  it.
- acceptance item 2 → the recorded `git show a60315c3 --stat` + the lint
  run against the real history (commit-message evidence).
- acceptance item 3 → the SKIP probe outside a git worktree; the output
  must name what could not be checked (absence of the word SKIP or a zero
  exit fails this).
- acceptance item 4 → `git diff` on docs/spec/deployment.md shows rule-text
  only (no dates, ticket IDs, or report citations — engineering-rules §12).
- acceptance item 5 → `mvn verify` from repo root (engineering-rules §5).

## Out-of-scope

Prose mirror of the YAML list. No Maven/git-hook/CI wiring (there is no CI;
the author-side posture is the house rule and changing it is a separate
decision). The restore-time detection gate is M1-819. No migration file is
edited — notably V50's dangling "Recorded in" comment
(V50__banned_admin_actor_checks.sql:190-194) stays as-is: the rule this
ticket enforces forbids the cosmetic repair too. No process-doc rider.
This ticket modifies NO pre-existing test.

## Census

Mechanical enumeration of the migration tree the lint guards —
`git ls-files '*/db/migration/V*.sql'` (infochat-core is the sole migration
home since M1-017; the lint globs all modules so a future second home is
covered). Every returned path is disposed by the same guard: present on
main → immutable; the lint adds no per-file exceptions.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-820`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-820-restore-robustness-2.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
