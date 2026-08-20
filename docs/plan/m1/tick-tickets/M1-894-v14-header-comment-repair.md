---
id: M1-894
title: V14 header comment correction + checksum repair runbook
status: pending
created: 2026-08-20
last_updated: 2026-08-20
flow: tick
reproduction: >-
  Probe (a doc-truth defect; the executable statement is the grep):
  `grep -n 'chat-command equivalent'
  infochat-core/src/main/resources/db/migration/V14__asset_config.sql`
  prints :34 `-- chat-command equivalent in v1).` — the applied V14
  header (:30-34) asserts operator recovery "is the runbook SQL in
  docs/design/10-asset-commands.md §10.8b (no chat-command equivalent in
  v1)", false since /asset-enable shipped (M1-836, done;
  docs/spec/commands.md §Asset commands; §10.8b itself was synced by
  M1-836 and now names the command). Origin: M1-836 r1
  RECOMMENDED-NEW-TICKET (its ticket file :463-475). Sanctioned by the
  owner decision of 2026-08-19 (comment-only edit, flyway repair, never
  re-baseline) and the spec amendment that landed at
  docs/spec/deployment.md:56-64 (owner decision 2026-08-20).
analysis_ref: docs/plan/m1/tick-analysis/small-followup-batch.md
blocked_by: []
files_scope:
  - infochat-core/src/main/resources/db/migration/V14__asset_config.sql
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/V14HeaderCommentRepairIT.java
  - docs/design/07-deployment.md
  - ADMIN_GUIDE.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    Any SQL change — to V14 or any other applied migration. The edit is
    the :30-34 header comment only; `git diff` on the file must show
    comment lines exclusively (the deployment.md:56-64 exception's own
    condition: "the SQL bytes are untouched").
  - >-
    Weakening, editing, or bypassing scripts/lint-migration-immutability.py
    (P8 — its comment-only flag is deliberate, M1-820; the exception is
    recorded in the commit message per the amended rule, never coded
    into the lint).
  - >-
    Re-baselining flyway_schema_history, or any automated repair in
    upgrade.sh or restore.sh (the user decision names a documented
    repair step, not new automation; the M1-819 restore gate's
    print-never-auto-repair posture stands; scripted upgrade preflight
    is its own decision — D-1/D-2 territory).
  - >-
    Any new migration version — the amended rule permits this one
    comment-only correction; "corrections are made in a new migration
    version" remains the default rule for every other correction.
  - >-
    M1-892 (Signal echo) and M1-893 (restore wording + ADMIN_GUIDE
    cold-start note) surfaces — siblings; M1-893's ADMIN_GUIDE edit and
    this ticket's are disjoint sections.
acceptance:
  - "The reproduction probe flips: post-edit, `grep -n 'chat-command equivalent' infochat-core/src/main/resources/db/migration/V14__asset_config.sql` prints nothing, and the V14 header (:30-34 region) now states current truth briefly (engineering-rules §11) — operator recovery from `failed` is `/asset-enable` (docs/design/10-asset-commands.md §10.8b), with the §10.8b SQL as the host-level fallback; the comment carries the stable design anchor and no chronicle."
  - "SQL bytes untouched (the exception's condition): `git diff -U0 infochat-core/src/main/resources/db/migration/V14__asset_config.sql` shows only lines whose content is a `--` comment (added and removed sides alike) — Verify: the diff probe; any non-comment line change fails review outright."
  - "FAILURE-MODE / upgrade hazard (P7 — LOAD-BEARING): V14HeaderCommentRepairIT.driftedV14ChecksumFailsValidateAndDocumentedRepairRestoresBoot passes (Testcontainers PostgreSQL per engineering-rules §8; the FlywayMigrationIT boot pattern) — a database migrated through the edited migration set, then mutated to carry the PRE-edit V14 checksum in flyway_schema_history (the state every pre-edit production DB is in), fails Flyway validation on the next migrate; applying the documented repair UPDATE (`UPDATE flyway_schema_history SET checksum = <new> WHERE version = '14';`) makes validation pass and the boot proceed. This proves the runbook's mechanism end-to-end against real Flyway, not against the awk model."
  - "The repair runbook lands where an upgrading operator meets it BEFORE the restart (P7 — LOAD-BEARING): docs/design/07-deployment.md §7.11 (upgrade) and ADMIN_GUIDE.md's Advanced section each carry the one-time step — after deploying a tree carrying this comment edit, and before the first Collector start against a database that applied V14 before the edit, run the repair once: the per-row checksum UPDATE with the value computed by restore.sh's `flyway_checksum` (restore.sh:635-669, pinned to flyway-core by RestoreFlywayChecksumIT) or `flyway repair`; restores of pre-edit dumps need nothing new (the M1-819 drift gate at restore.sh:711-729 already prints the equivalent) — Verify: `grep -n \"version = '14'\" docs/design/07-deployment.md ADMIN_GUIDE.md` hits both runbooks, and RestoreFlywayChecksumIT passes unmodified."
  - "Lint exception recorded, lint untouched (P8 — LOAD-BEARING): `python3 scripts/lint-migration-immutability.py` run on the branch prints its expected FAIL naming V14__asset_config.sql (the control FIRING on the deliberate exception); that output and the authorization (owner decision 2026-08-19; exception rule docs/spec/deployment.md:56-64, owner decision 2026-08-20) are recorded in the committing change's message; the lint script itself is unchanged and `python3 scripts/lint-migration-immutability.py --self-test` passes — Verify: the commit message carries both, and `git diff scripts/` is empty."
  - "mvn verify from repo root is green (engineering-rules §5), including every pre-existing migration and Flyway test."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema/V14HeaderCommentRepairIT.java
  preserves:
    - all tests currently green on main
    - >-
      RestoreFlywayChecksumIT (pins the awk flyway_checksum the runbook
      cites to the pinned flyway-core) and FlywayMigrationIT — both run
      unmodified; this ticket edits no pre-existing test.
spec_refs:
  - docs/spec/deployment.md §Topology
  - docs/design/10-asset-commands.md §10.8b
decision_refs:
  - D42
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

# M1-894: V14 header comment correction + checksum repair runbook

## Context

The applied V14 migration's header comment (V14__asset_config.sql:30-34)
asserts operator recovery "is the runbook SQL in
docs/design/10-asset-commands.md §10.8b (no chat-command equivalent in
v1)" — false since `/asset-enable` shipped (M1-836). M1-836 correctly did
not touch the applied migration (its P9) and its round-1 review filed the
fix as a RECOMMENDED-NEW-TICKET. The owner decided (2026-08-19): fix the
comment and make the checksum whole via flyway repair — NOT re-baseline;
comment-only edit, never SQL. That decision was spec-blocked
(deployment.md's immutability passage covered comments); the owner
resolved the gap by amending docs/spec/deployment.md:56-64 (owner decision
2026-08-20), which now permits exactly this shape: a comment-only
correction of a fact-turned-false, SQL bytes untouched, shipping with a
documented repair step every pre-edit deployment runs before its next
Collector start, with the exception and its authorization recorded in the
committing change. Shared analysis: `analysis_ref:`.

## Root cause

Proven. The header comment predates `/asset-enable`; every other text site
was already synced (§10.8b by M1-836 itself, 10-asset-commands.md:414-432;
the Census below confirms V14 is the last live site). The hazard that made
this non-trivial is proven too: a comment-only edit changes V14's Flyway
checksum, and every deployment that applied the pre-edit V14 then fails
Collector boot validation on its next migrate-at-start (only the Collector
migrates in production — docs/spec/deployment.md:39-42; the a60315c3
incident, setup-hurdles item 1, demonstrated the crash-loop on restores).
upgrade.sh has no Flyway preflight (grep-verified), so the repair step is
the ONLY thing standing between an upgrading operator and that crash-loop
— which is why P7 and P8 below are load-bearing requirements of this
ticket, not suggestions.

## Pitfalls

Numbered per the analysis document; this ticket carries P7 and P8 (P9 —
the spec-first gap — is RESOLVED by the landed amendment; its residue is
that every acceptance item here answers to the amendment's conditions).

- P7 (LOAD-BEARING): the comment edit crash-loops every pre-edit
  deployment on upgrade unless the repair lands with it and is executed
  before the restart. The runbook must sit where an upgrading operator
  meets it (07-deployment.md §7.11 + ADMIN_GUIDE Advanced), must carry
  the exact one-time statement (`UPDATE flyway_schema_history SET
  checksum = … WHERE version = '14';` — value computed by restore.sh's
  RestoreFlywayChecksumIT-pinned `flyway_checksum`, or `flyway repair`),
  and must be PROVEN, not merely plausible: the new IT walks a
  drifted-checksum database through validate-fails → repair →
  validate-passes against real Flyway on Testcontainers. Restores need
  nothing new — the M1-819 drift gate (restore.sh:711-729) already
  prints the equivalent per-row repair when a pre-edit dump meets the
  edited tree.
- P8 (LOAD-BEARING): the immutability lint firing on this diff is the
  control working (M1-820 built it to flag comment-only edits, pinning
  that in its own self-test, lint-migration-immutability.py:166-170).
  The amended rule handles the exception by RECORDING — the lint's FAIL
  output plus the owner authorization goes in the commit message — never
  by weakening the lint, adding an allowlist, or bypassing it
  (engineering-rules §2). The lint stays absolute so the NEXT,
  unsanctioned comment edit still trips it.

## Approach

Derived from `spec_refs:` — the amended deployment.md passage (§Topology,
:56-64) is the authorizing rule and dictates the ticket's three
deliverables (the comment-only edit, the documented repair step, the
recorded exception); §10.8b supplies the current truth the comment must
state (chat command primary, SQL as host-level fallback).

- **Files to touch** (plan, not allowlist): `files_scope` — the V14
  header comment, the new IT, and the two runbook sites.
- **Steps in order** (each green before the next):
  1. The comment edit (§11): rewrite the :30-34 status paragraph's last
     clause to state current truth — operator recovery from `failed` is
     `/asset-enable` (pointing at docs/design/10-asset-commands.md
     §10.8b as the ONE stable anchor), with the §10.8b SQL as the
     host-level fallback when the Provider is down. Brief, no chronicle,
     no ticket-ID provenance (the amendment record lives in the commit
     message, not the comment). Nothing else in the file changes.
  2. The repair runbook (P7): 07-deployment.md §7.11 gains the one-time
     step with the exact statement and the before-next-Collector-start
     rule; ADMIN_GUIDE's Advanced section carries the operator-facing
     twin (what happened, who needs it — any deployment that applied V14
     before this change — the one command, once).
  3. The proof IT (P7): V14HeaderCommentRepairIT boots Flyway against
     Testcontainers PostgreSQL through the edited migration set, rewrites
     the V14 history checksum to a wrong value (the pre-edit state),
     asserts validate/migrate fails, applies the documented repair, and
     asserts validate passes.
  4. The exception record (P8): run the lint, capture its expected FAIL
     naming V14, and put that output plus the authorization citations
     (owner decision 2026-08-19; deployment.md:56-64, owner decision
     2026-08-20) in the commit message. The lint script is untouched.
- **Controls to preserve (§10):** V14's SQL bytes (the exception's own
  condition); the immutability lint's strictness (P8); the M1-819 restore
  drift gate's print-never-auto-repair posture (this ticket adds no
  automation); RestoreFlywayChecksumIT's awk-to-flyway-core pin (the
  runbook's checksum source stays trustworthy); every pre-existing
  migration/Flyway test.
- **Pitfall→mitigation:** P7→steps 2-3 + acceptance items 3-4; P8→step 4
  + acceptance item 5.

## Definition of done

The reproduction grep prints nothing and the header states current truth
with the §10.8b anchor; the V14 diff is comment lines only; the repair IT
proves drift → validate-fails → documented-repair → validate-passes on
Testcontainers; both runbook sites carry the exact one-time step with the
before-restart rule; the lint's expected FAIL and the owner authorization
are recorded in the commit message with the lint itself unchanged;
RestoreFlywayChecksumIT and all pre-existing tests green; `mvn verify`
from the repo root green.

## Verification

- P7 → V14HeaderCommentRepairIT.driftedV14ChecksumFailsValidateAndDocumentedRepairRestoresBoot
  (FAILURE-MODE: feeds Flyway the pre-edit-deployment state and asserts
  both the failure and the repair's effect — a runbook whose mechanism
  were wrong, e.g. a wrong version key or checksum semantics, fails this)
  + acceptance item 4's grep probes (both runbook sites) +
  RestoreFlywayChecksumIT green (the awk checksum the runbook cites stays
  pinned to flyway-core).
- P8 → acceptance item 5: the lint run's expected FAIL output attached,
  `--self-test` green, `git diff scripts/` empty, the commit message
  carrying the authorization. A missing record is a review blocker; a
  lint edit is out of scope on its face.
- P9 → RESOLVED outside this ticket (the spec-first gap): the amendment
  at docs/spec/deployment.md:56-64 (owner decision 2026-08-20) closed it,
  and acceptance items 1-2 enforce the amendment's conditions
  (comment-only, SQL bytes untouched) — Verify: `grep -n 'comment-only
  correction' docs/spec/deployment.md` shows the authorizing rule; no
  test — the resolution note lives in the analysis's SPEC-GAP section.
- acceptance item 1 → the flipped reproduction grep.
- acceptance item 2 → `git diff -U0` on V14 showing comment lines only.
- acceptance item 6 → `mvn verify` from repo root (engineering-rules §5).
- Non-vacuity: an IT that skipped the drift-injection would pass vacuously
  — it asserts the validate FAILURE before the repair; a repair statement
  keyed to the wrong version reds the IT; a comment edit that touched one
  SQL byte fails item 2's probe.

## Out-of-scope

Prose mirror of the YAML list. No SQL change anywhere — the exception the
amendment grants is comment-only and this ticket uses exactly that. No
lint weakening or bypass (P8). No re-baseline and no scripted repair in
upgrade.sh or restore.sh — the decision is a documented step; automating
flyway-history mutation is a separate decision (the D-1/D-2 upgrade
tickets own upgrade.sh's preflight future). No new migration version.
M1-892's and M1-893's surfaces are untouched; this ticket's ADMIN_GUIDE
edit (the repair note) and M1-893's (the cold-start note) are disjoint
sections of the same file.

## Census

Class: **live text sites asserting the pre-M1-836 world ("no chat-command
equivalent" for asset-pair recovery).** Re-runnable enumeration:
`grep -rn 'chat-command equivalent\|no chat command\|chat-side reset'
infochat-core docs prod`. Rows (verified at draft time):

- `infochat-core/src/main/resources/db/migration/V14__asset_config.sql:34`
  — the stale claim → FIXED by this ticket (acceptance item 1).
- `docs/plan/m1/tick-tickets/M1-836-asset-enable-command-1.md` (:157, :470)
  and `docs/plan/m1/tickets/M1-055b-collector-fetchers-price-snapshot.md:317`
  — frozen historical ticket records → DISPOSED, never edited (tickets are
  history, not maintained truth; §11's anchor discipline).
- `docs/design/10-asset-commands.md` §10.8b (:414-432) — already synced by
  M1-836 (names /asset-enable, SQL as fallback) → DISPOSED, correct as-is;
  it is the anchor the fixed comment points at.
- Any other hit from the enumeration → reviewed at implementation against
  the same rule; none known at draft time.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-894`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-894-v14-header-comment-repair.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
