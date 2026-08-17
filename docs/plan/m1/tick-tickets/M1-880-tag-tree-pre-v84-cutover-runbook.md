---
id: M1-880
title: "Tag-tree cutover runbook: cleanup, migrate, verify for every pre-V84 deployment"
status: pending
created: 2026-08-17
last_updated: 2026-08-17
flow: tick
reproduction: >-
  to-be-written TagTreeCutoverCheckIT.leftoverOccurrencesFailThePreflight —
  `start` converts the marker: write prod/scripts/tag-tree-cutover.sh and
  the IT, run the IT RED (absent script — "no such script", the M1-818
  absent-artifact shape), then the live preflight against the target
  deployment's DB (every deployment with a pre-V84 DB needs its own
  pass). The wrong behavior it states: the operator cutover surface does
  not exist — nothing checks a pre-V84 deployment DB for the ruled
  nostr/video leftovers across tag / post.tags / source.bootstrap_tags /
  scope_tag, nothing removes them, and nothing verifies the
  post-migration state, so the next Collector restart onto the V84
  migration set fails loudly (E4002 crash-loop under restart:
  unless-stopped) or trips the BootstrapLoader node gate
  (BootstrapLoader.java:304-339) against the pre-V84 tag table.
analysis_ref: self
blocked_by:
  - M1-866
files_scope:
  - prod/scripts/tag-tree-cutover.sh
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Any edit to V84__tag_tree_seed_and_migration.sql or any other applied
    migration — after a migration ships on main its file content is
    immutable (docs/spec/deployment.md §Topology); this ticket EXECUTES V84
    via the Collector boot, it never touches a migration file
    (migration_touch: false).
  - >-
    Any Java application code — BootstrapLoader, TaggerWorker,
    SourceUpsertService, the resolver, or any consumer. M1-866 shipped
    every gate and migration this runbook operates around; nothing here
    changes runtime behavior, only the operator procedure that reaches it.
  - >-
    Mapping nostr/video into the tree. The user ruling (M1-866
    clarity_check ruling 1) is disposal, not mapping: platform/medium names
    fail loudly and are removed; inventing a category is explicitly
    forbidden. The script's cleanup targets exactly {nostr, video}.
  - >-
    Auto-removal of any OTHER unmapped name (a stray coinage like
    ai-image). Cleanup removes only the ruled two; any other unmapped
    leftover is an operator decision (remove or map) signalled by V84's own
    loud failure at the migrate boot (V84:173-175, 222-224), which rolls
    back safely — see P3.
  - >-
    Frozen content — summary_cache bodies, digest replay section slugs
    (DigestSectionRepository), saved_post snapshots. They keep old tag
    names by design (D19/D36/D65 byte-faithful replay) and age out with
    retention (M1-866 analysis P18); V84 does not check them and neither
    does this runbook.
  - >-
    M1-867 (tree-aware retrieval) and M1-869 (spec amendments) — not
    prerequisites for the cutover and not touched here. The post-V84 DB
    with pre-M1-867 consumers is the family's designed intermediate state
    (M1-866 analysis P6/P18); this ticket adds no spec prose (the runbook
    lives in docs/design/; M1-869 owns the lifecycle rewrite).
  - >-
    prod/config/bootstrap-sources.json — M1-866 already rewrote the
    committed wizard template. This ticket reconciles the operator's
    DEPLOYED runtime copy (the compose mount at
    /app/bootstrap-sources.json), never the template.
  - >-
    Changes to backup.sh / upgrade.sh / pack.sh / restore.sh / 8-verify.sh
    / apps.sh or the setup wizard. The runbook INVOKES them as they are;
    no ops script is edited except the new one.
  - >-
    New audit rows, GRANT changes, or any widening of a service role. The
    cleanup runs as the migration-owner role via psql — the ownership-level
    operator action docs/spec/security.md §DB roles reserves to the owner
    — and writes no audit row (it is a pre-boot operator act, not an app
    transition; the BootstrapLoader writes its own BOOTSTRAP_SOURCE_LOAD
    row at the next boot as it always does).
acceptance:
  - "TagTreeCutoverCheckIT.leftoverOccurrencesFailThePreflight (the converted reproduction) passes — it seeds the hostile four-surface state on the real Testcontainers DB (a tag row 'nostr', a post carrying {ai, video}, a source carrying {cybersecurity, nostr}, and a scope_tag row referencing the nostr tag row), drives prod/scripts/tag-tree-cutover.sh preflight through the CUTOVER_PSQL seam, and asserts exit 1 with every occurrence named per surface (tag / post.tags / source.bootstrap_tags / scope_tag) — the runbook's pre-migrate inventory (implements docs/spec/deployment.md §Topology's operator side; the V84 header's own 'the operator removes them and re-runs', V84:13-15)."
  - "TagTreeCutoverCheckIT.cleanupRemovesExactlyNostrAndVideo passes (failure mode) — with the four-surface seeding plus benign bystander data (a v1-shaped 'ai' tag row, a post {ai, video}, a source {ai, nostr}, a scope_tag on the nostr row AND one on the ai row), cleanup: removes nostr/video from all four surfaces without an FK violation (scope_tag references deleted BEFORE the tag rows), leaves every bystander row/element byte-identical, and a second run changes 0 rows; cleanup --dry-run prints the targets and changes nothing (hostile precondition: a would-be destructive run must be reviewable and side-effect-free)."
  - "TagTreeCutoverCheckIT.cutoverRehearsalPassesPostflight passes — the full operator rehearsal on the Testcontainers DB: preflight RED on seeded leftovers → cleanup → preflight GREEN (zero findings) → re-execute V84's own statements read from the classpath resource minus the boot-applied ALTER (the TagTreeMigrationIT runMigration mechanics) → postflight exits 0 with GREEN lines for: flyway_schema_history carries version '84' with success, 9 tops and 53 leaves present, exactly 8 fallback=true rows (world + the seven per-top residuals), zero nostr/video anywhere, every post.tags and source.bootstrap_tags element names an existing tag node, zero scope_tag orphans, and the runtime bootstrap-file fixture's tags[] all name existing nodes."
  - "TagTreeCutoverCheckIT.runtimeBootstrapFileNamesOnlyTreeNodes passes (failure mode) — a runtime file fixture whose tags[] carries a retired name REDs the check that owns it (preflight names the file for a nostr/video entry; postflight REDs on any non-node name against the seeded tree, the BootstrapLoader gate's own predicate), and the tree-named fixture is GREEN (P7, P1; docs/spec/deployment.md §Bootstrap behavior on startup)."
  - "The runbook lands in docs/design/07-deployment.md §7.14 (Operator runbook) as a new subsection 'Cut over the tag-tree migration (one-time)', with the exact sequence — apps.sh stop → backup.sh (Postgres stays up for pg_dump, the M1-582 stop-first order) → preflight (first run RED = the inventory) → cleanup --dry-run → cleanup → preflight GREEN → reconcile the deployment's runtime bootstrap-sources.json tags[] to tree names with Video/Nostr removed (5-bootstrap.sh never clobbers an existing runtime file, 5-bootstrap.sh:137-138) → apps.sh start (the Collector applies the migration set incl. V84 — only the Collector migrates in production, docs/spec/deployment.md §Topology) → postflight → 8-verify.sh smoke — plus the two failure-mode recoveries (E4002 at boot = a leftover survived; the message names it, the failed migration rolled back leaves the DB at V83, fix + re-run preflight + restart — never a pg_restore; a loader-gate failure AFTER a successful V84 = the runtime file still carries non-node names, fix the file + restart, the DB is already migrated) and the sweep note (V84:17-23: tags='{}' posts re-tag within caps, mapped non-empty tags are NOT re-tagged). The runbook states the deployment scope explicitly: the SAME sequence runs once per pre-V84 deployment — prod, the live test instance, any restored clone or host-migration target — each with its own stop-first pass, none skipped. Probes: grep -n 'Cut over the tag-tree migration' docs/design/07-deployment.md hits; the runbook names only script subcommands the script implements (grep -o 'tag-tree-cutover.sh [a-z-]*' docs/design/07-deployment.md matches the script's case statement); the §7.14 scenario table gains the E4002-leftover row pointing at the subsection."
  - "The script meets the prod/scripts conventions and handles the credential safely: executable, `set -euo pipefail`, shellcheck-clean (author-run, the M1-427 convention), reads INFOCHAT_DB_PASSWORD via a non-sourcing read_prop-shaped helper (backup.sh:76-86 precedent) and passes it as PGPASSWORD environment only — never on the command line, never echoed. Probes: grep -n 'source.*secrets.env' prod/scripts/tag-tree-cutover.sh returns nothing; grep -n 'INFOCHAT_DB_PASSWORD' shows only the read_prop call and the PGPASSWORD assignment; ls -l shows mode 0755."
  - "mvn verify from repo root is green; the diff touches no file under infochat-core/src/main/resources/db/migration/ (probe: git diff --name-only <fork>..HEAD | grep db/migration returns nothing) and no pre-existing test is modified (test_plan.preserves holds)."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java
  preserves:
    - >-
      all tests currently green on main — in particular, unmodified:
      TagTreeMigrationIT (V84's own seed/loud-failure pins),
      BootstrapLoaderIT (the node gate), TaggerWorkerSweepIT (sweep
      mechanics), RestoreFlywayChecksumIT (Flyway-history discipline).
spec_refs:
  - docs/spec/deployment.md §Topology
  - docs/spec/deployment.md §Bootstrap behavior on startup
  - docs/spec/schema.md §Sources and tags
  - docs/spec/security.md §DB roles
decision_refs:
  - D8
  - D22
  - D34
decomposed_from: M1-866
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

# M1-880: Tag-tree cutover runbook — cleanup, migrate, verify for every pre-V84 deployment

## Context

M1-866 (done, merged on main as 16e2d028) shipped the tag-tree seed and
migration (V84) but deferred the prod-DB cutover to a separate
operator-runbook ticket it promised in three places — its clarity_check
(M1-866:236-238: "The prod-DB cutover (cleanup of loud leftovers +
migration + verification) is deferred to a separate operator-runbook
ticket blocked_by this one."), its Out-of-scope (M1-866:372-374), and
V84's own header (V84:30-31: "The prod-DB cleanup of any stored
nostr/video rows is a separate operator-runbook ticket blocked by this
one."). That promise is orphaned: grep -i 'prod.?db|cutover|runbook'
over docs/plan/m1/tick-tickets/*.md and docs/plan/m1/tickets/*.md hits
only those M1-866 lines, the board (STATUS-TICK.md) lists no such item,
and no branch or worktree carries it. Any pre-V84 deployment DB still
sits pre-V84: its `tag` table holds the flat v1 vocabulary (grown by the
pre-gate bootstrap union, D8, and `/add-source --tags`), including the
ruled nostr/video rows, and post.tags / source.bootstrap_tags arrays
carry the same names. Meanwhile main already carries everything that
makes the pre-V84 state a startup failure: the BootstrapLoader node gate
(BootstrapLoader.java:304-339) fails fast on file tags that are not
existing tree nodes, and V84 raises loudly on any unmapped leftover
(V84:131-176, 181-226). The cutover therefore cannot be "restart the
Collector" — it is a sequenced operator procedure this ticket must
exist to define and verify: cleanup, migrate, verify.

**User re-scope (2026-08-17, binding):** the cutover is NOT
prod-specific. It is a per-deployment procedure for ANY deployment with
a pre-V84 database — the prod instance, the live test instance, any
restored clone or host-migration target. The same stop-first pass
(cleanup, migrate, verify) runs once per deployment, pointed at that
deployment's DB and runtime file; the title, script contract, and
acceptance wording are deployment-generic accordingly.

## Root cause

Verified, with citations:

- **The promise exists and the ticket does not.** M1-866:236-238 and
  :372-374 are the only corpus mentions (grep above); V84:30-31 makes
  the same promise from inside the shipped migration. No ticket file,
  no board row (STATUS-TICK.md, 2026-08-17), no branch/worktree (driver
  falsified — git history is not re-verifiable from this read-only
  session; the file/board absence is re-verified above).
- **A pre-V84 DB cannot reach V84 by a plain restart.** V84's two
  validation blocks enumerate every occurrence of an unmapped name and
  RAISE with the list: tag rows (V84:131-176) and array elements in
  post.tags / source.bootstrap_tags (V84:181-226). nostr/video are
  deliberately outside both the seeded-name list and the mapping VALUES
  table (V84:138-152, 153-171), so any stored occurrence fails loudly.
  scope_tag rows referencing the nostr/video tag rows are covered via
  the tag-table check plus the FK (scope_tag.tag_id REFERENCES tag(id),
  V7:64-69).
- **Even a leftover-free DB is not enough: the file gate fires too.**
  The Collector mounts the deployment's runtime bootstrap-sources.json
  at /app/bootstrap-sources.json (docker-compose.yml:146) and
  BootstrapLoader fails fast at startup on any tags[] name that is not
  an existing tree node (BootstrapLoader.java:304-339). The DEPLOYED
  runtime file predates M1-866 (copied at setup; 5-bootstrap.sh:137-138
  never clobbers an existing runtime file), so it still carries v1 names
  and the removed Video/Nostr platform/medium tags. **Brief-vs-code
  discrepancy:** the first draft's ground truth says
  prod/config/bootstrap-sources.json "already carries tree names
  (M1-866 diff) and Video/Nostr removed" — true for the committed
  WIZARD TEMPLATE (verified: prod/config/bootstrap-sources.json tags are
  ai/software-development/cybersecurity, no Video/Nostr), but the gate's
  actual boot input is the operator's deployed runtime copy, which the
  first draft never named. The runbook must reconcile the runtime file,
  not the template (P7).
- **The fallback path makes bootstrap_tags a real pollution vector.**
  The tagger's three-surface fallback returns the source's
  bootstrap_tags as the stored tag set (TaggerWorker.java:463-478) and
  persistCursor writes them into post.tags unvalidated
  (TaggerWorker.java:710-731); /unfollow-tag joins bootstrap_tags names
  against tag.name (UnfollowTagCommandHandler.java:97-110, per the
  M1-866 analysis). Cleanup must therefore cover bootstrap_tags — the
  brief's explicit ground truth, and M1-866 analysis P10 re-stated on
  the operator surface.
- **The migrate mechanism is the Collector boot, and only that.** Only
  the Collector runs Flyway in production (docs/spec/deployment.md
  §Topology; 7-apps.sh:3-7; docker-compose.yml:236-242 gates the
  Provider on the Collector's health). Hand-running V84's statements
  via psql would land the schema change without recording
  flyway_schema_history — the next boot would re-apply V84 over
  existing objects (P5). The runbook's migrate step IS `apps.sh start`,
  which also brings the Provider up only after the Collector is healthy.
- **The current migration head is V84** (db/migration listing: V84 is
  the highest version; no V85+), so the cutover boot applies exactly
  the set ending in V84. The migration is deterministic and zero-LLM
  (V84 header; M1-866 acceptance 1-5).

## Pitfalls

- P1: **The half-migrated restart window.** Between "runtime file
  reconciled" and "DB migrated" (or the reverse) any Collector start
  fails: Flyway raises E4002 on leftovers, or the node gate fails on
  non-node file tags (BootstrapLoader.java:304-339) against a tag table
  without the seed. With `restart: unless-stopped`
  (docker-compose.yml:100) a failure crash-loops. The runbook stops the
  apps FIRST (`apps.sh stop` = explicit stop; unless-stopped does not
  resurrect it) and keeps them stopped until BOTH halves are updated.
  Spec: docs/spec/deployment.md §Bootstrap behavior on startup (fail
  fast on invalid input), §Topology.
- P2: **FK-order violation on tag deletion.** scope_tag.tag_id
  REFERENCES tag(id) with no cascade (V7:64-69); deleting the
  nostr/video tag rows before their scope_tag references raises a
  constraint violation. Cleanup deletes scope_tag rows first, then tag
  rows, then rewrites arrays — in one psql transaction.
- P3: **Cleanup overreach beyond the ruled disposal.** The user ruling
  disposes ONLY nostr/video (M1-866 clarity_check ruling 1, Out-of-scope:
  "fails the migration loudly on either name wherever it occurs instead
  of inventing a category"). A cleanup that removed "anything not in the
  valid set" would silently destroy stray coinages the operator might
  have ruled on — the exact opposite of the loud contract. Cleanup
  targets exactly {nostr, video}; anything else is the operator's
  decision, signalled by V84's own failure at boot (safe: rollback, see
  P11). Pinned by the IT's bystander-data assertions.
- P4: **The fallback path re-imports leftover names.** If
  source.bootstrap_tags survives the cutover with a retired name, the
  fallback writes it into post.tags unvalidated (TaggerWorker.java:
  463-478 → 710-731) and the /unfollow-tag seed joins a name that no
  longer has a row (UnfollowTagCommandHandler.java:97-110) — M1-866
  analysis P10 on the operator surface; D22 makes bootstrap_tags the
  deterministic fallback. Postflight re-asserts every bootstrap_tags
  element names a node.
- P5: **The "manual migration" temptation.** Only the Collector runs
  Flyway in production (docs/spec/deployment.md §Topology). psql-applied
  V84 statements do not record flyway_schema_history; the next boot
  re-applies them over existing objects and fails. Also: shipped
  migrations are immutable (docs/spec/deployment.md §Topology) — the
  diff must not touch db/migration. The runbook's migrate step is the
  Collector start, never hand-run SQL.
- P6: **upgrade.sh as an accidental cutover trigger.** upgrade.sh
  restarts the apps automatically in step 5 (upgrade.sh:291-309) and
  auto-rolls back the code on health failure — running the routine
  upgrade against the pre-cutover DB lands in the P1 crash-loop, and
  the rollback rebuilds the old image, masking the leftover state. The
  runbook sequences around upgrade.sh: build with the step-5 restart
  confirm declined (upgrade.sh:301), then stop/cleanup/migrate/start.
- P7: **The operator's runtime file is not the committed template.** The
  Collector mounts the deployment's runtime bootstrap-sources.json
  (docker-compose.yml:146); 5-bootstrap.sh never clobbers an existing
  runtime file (5-bootstrap.sh:137-138), so the wizard will NOT bring
  the deployed file to tree names. The runbook reconciles the RUNTIME
  copy (hand-edit, or delete + re-run 5-bootstrap.sh). This is the
  first draft's ground-truth gap corrected (see Root cause).
- P8: **The sweep bump is expected, not a defect.** Post-V84 the
  vocabulary change bumps the sweep fingerprint; tags='{}' posts re-tag
  within the existing caps (batch 4/tick, max 3 attempts — V84:17-23;
  M1-736; TaggerWorkerSweepIT pins the mechanics). The operator will
  see tagger activity on old posts after cutover: bounded, one-time,
  expected; mapped non-empty tags are NOT re-tagged.
- P9: **psql credential handling.** The script reads the migration-owner
  password from secrets.env; it must pass it as PGPASSWORD environment
  only (never argv, never echoed) and never source secrets.env
  (backup.sh:76-86 / restore.sh:216 read_prop shape; M1-389/M1-397).
  security_relevant: true carries the M1-427 clarity WARN's lesson — a
  script handling DB credentials and prod mutation is a redteam surface.
- P10: **The target deployment's actual leftovers are unknown from this
  checkout.** This box has no deployment DB access (memory-local: zero
  containers, no runtime file), so no claim about a given deployment's
  row counts can be verified here — the runbook treats preflight as the
  inventory step: it runs RED first and its output defines the cleanup.
  The implementor records the live preflight output against the first
  real target deployment at start (the live RED leg of the
  reproduction).
- P11: **V84 boot failure is loud but safe.** A surviving leftover makes
  the Collector boot fail with E4002 (docs/design/09-reference.md:111)
  naming the leftover (V84:173-175, 222-224); Flyway runs each migration
  in a transaction (the Postgres default), so the failed attempt rolls
  back — the DB stays at V83 and the recovery is fix-then-restart, never
  a pg_restore. The runbook states this so the operator does not reach
  for the backup.
- P12: **Provider writes during the window.** With the apps up, the
  Provider can write tag (INSERT per V31 — docs/spec/security.md
  §DB roles, security.md:2107-2115) and scope_tag, and the tagger
  fallback can write post.tags — a concurrent writer between cleanup and
  migrate re-introduces a leftover and V84 fails again. Stop-first is
  the race closure (the M1-582 single-owner shape), not just hygiene.

## Approach

Derived from `spec_refs:` — the spec already commits the operation to
the mechanisms this runbook sequences: only the Collector migrates, the
operator starts Collector-before-Provider (§Topology), bootstrap input
is fail-fast (§Bootstrap behavior on startup), tag rows/arrays/scope_tag
are the name-bearing surfaces (§Sources and tags), and ownership-level
DB actions are owner-role psql actions (§DB roles). The disposal of
nostr/video is the user's start ruling, recorded in M1-866 and restated
by V84's own header (V84:13-15) — the runbook executes a designed
operation, it changes no promise. Rejected options: (a) a
migration-applying script (hand-runs V84 — P5, violates §Topology);
(b) booting the Collector to migrate first and cleaning after (V84
raises before the seed lands — impossible by construction); (c)
documenting the runbook with no executable verification script (the
M1-427 "restorability is the success bar" lesson: a doc-only runbook
drifts the day it is written — the M1-582 stale-line clarity WARN is
the measured cost). Chosen: one shell script carrying the SQL as the
runbook's executable core + one Testcontainers IT that rehearses the
cutover against a real DB + the runbook subsection.

**Files to touch** (guidance, not an allowlist):

1. `prod/scripts/tag-tree-cutover.sh` — new. Subcommands `preflight`,
   `cleanup [--dry-run]`, `postflight`. Deployment-agnostic transport:
   host psql with the target host/db taken from env seams
   (CUTOVER_PGHOST/CUTOVER_PGDB/CUTOVER_PGUSER, or wholesale via the
   CUTOVER_PSQL wrapper) — defaults never hardcode a single deployment
   (the script is pointed at whatever deployment needs the cutover);
   `PGPASSWORD=` from a non-sourcing read_prop of secrets.env
   (backup.sh:129-137 shape). Test seam: `CUTOVER_PSQL` (an alternate
   psql command the IT supplies as a docker-exec wrapper) and
   `CUTOVER_BOOTSTRAP_FILE` (default `$RUNTIME_DIR/bootstrap-sources.json`).
   Contract:
   - `preflight` — inventory, exit 0/1: (a) nostr/video entries in the
     runtime file's tags[]; (b) tag rows named nostr/video; (c)
     post.tags elements nostr/video; (d) source.bootstrap_tags elements
     nostr/video; (e) scope_tag rows referencing the nostr/video tag
     rows. RED lists every finding per surface — the pre-migrate
     inventory (P10). Deliberately scoped to the ruled two names (P3):
     any other unmapped name surfaces at the migrate boot via E4002.
   - `cleanup` — exactly {nostr, video} (P3): delete the scope_tag
     references, then the tag rows, then `array_remove` both names from
     both arrays (array_remove removes every occurrence per call);
     prints per-surface row counts; idempotent (second run 0 rows);
     `--dry-run` prints the targets and changes nothing.
   - `postflight` — end-state verification, exit 0/1, GREEN/RED lines:
     flyway_schema_history carries version '84' with success; 9 tops +
     53 leaves; exactly 8 fallback=true rows; zero nostr/video anywhere
     (the preflight probes); every post.tags / source.bootstrap_tags
     element names an existing tag node (DB-driven, no static list);
     zero scope_tag orphans (LEFT JOIN tag); the runtime file's tags[]
     all name existing nodes (the BootstrapLoader gate's predicate).
   - Conventions: `set -euo pipefail`, SCRIPT_DIR/PROD_DIR/REPO_ROOT
     resolution, umask 077 for nothing written (the script writes no
     files), exit 0 pass / 1 findings / 2 usage.
2. `infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java`
   — new, extends `PostgresSchemaTestBase` (the singleton migrated
   Testcontainers DB; per-test truncateAll gives the clean slate; the
   IT seeds what each case needs). Drives the REAL script via
   ProcessBuilder with `CUTOVER_PSQL` = a TempDir wrapper that execs
   `docker exec <containerId> psql "$@"` (the psql-inside-the-pgvector-
   container seam — no host psql dependency) and
   `CUTOVER_BOOTSTRAP_FILE` = a TempDir fixture. The rehearsal case
   re-executes V84's statements read from the classpath resource minus
   the boot-applied ALTER (the TagTreeMigrationIT runMigration
   mechanics) as the migrate step.
3. `docs/design/07-deployment.md` — §7.14 gains the subsection named in
   acceptance 5; the §7.14 scenario table gains the E4002-leftover row
   pointing at it. No docs/spec/** edit (not §12-gated work; the design
   doc is the runbook's home per the M1-507/M1-427 precedent).

**Steps, in order** (script → IT → docs; the docs cite only subcommands
the script actually implements, avoiding the M1-582 stale-reference
class of clarity WARN):

1. Write `tag-tree-cutover.sh` (the IT and the runbook both consume it).
2. Convert the reproduction marker: write `TagTreeCutoverCheckIT`, run
   it RED (the script's absence is the RED — the M1-818 shape), then
   drive the four acceptance cases to green with the script.
3. Write the runbook subsection + scenario row, citing the script's
   implemented subcommands.
4. shellcheck the script; the acceptance-6 probes; `mvn verify`.

**Controls to preserve (engineering-rules §10):** this diff adds a path
rather than rerouting one, but it operates on surfaces other artifacts
pin, and those obligations travel into the script's contract: the V84
seed/collision discipline — cleanup never touches a seeded row (nostr/
video are never seeds by the ruling) and the rehearsal re-executes the
real resource, never a copy (P3); the Flyway-history integrity — no
hand-run migration statements, the migrate step is the Collector boot
(P5); the weak-role matrix (docs/spec/security.md §DB roles) — the
script runs as the owner role, the ownership-level psql action §DB
roles reserves for exactly this (security.md:2147-2154), no GRANT
changes, no service-role widening; the loader's audit emission is
untouched (the loader writes its own BOOTSTRAP_SOURCE_LOAD row at the
next boot); the existing pins stay green unmodified — TagTreeMigrationIT
(the migration's seed/loud-failure pins), BootstrapLoaderIT (the node
gate), TaggerWorkerSweepIT (sweep mechanics), RestoreFlywayChecksumIT.
No §9 clock surface: the script's SQL reads stored names only, no
decision-time.

**Pitfall→mitigation mapping:** P1→stop-first sequencing + the IT's
file/DB checks; P2→cleanup's FK-first order + the four-surface case;
P3→cleanup's exact {nostr, video} target + the bystander assertions;
P4→postflight's bootstrap_tags⊆nodes assertion; P5→migrate = Collector
start + the no-db/migration-diff probe; P6→the runbook's upgrade.sh
decline-restart step; P7→the runtime-path reconcile step + the
CUTOVER_BOOTSTRAP_FILE fixture cases; P8→the runbook's sweep note;
P9→read_prop + PGPASSWORD + shellcheck + security_relevant: true;
P10→preflight-as-inventory + the implementor's recorded live RED;
P11→the runbook's E4002 recovery (fix + restart, never pg_restore);
P12→stop-first before cleanup, start only after postflight-clean boot.

## Definition of done

Every `acceptance:` item, verified by its named test/command/probe: the
converted reproduction green (four-surface leftovers named by
preflight); cleanup exact, FK-safe, dry-run-able, idempotent, with
bystander data intact; the full rehearsal green (preflight RED →
cleanup → preflight GREEN → V84 re-executed → postflight GREEN incl.
history/seed/fallback/orphan/file checks); the file-check failure mode
green; the runbook subsection + scenario row landed with both
failure-mode recoveries, the sweep note, and the per-deployment scope
statement, naming only implemented subcommands; the script
shellcheck-clean with the credential discipline probes green; `mvn
verify` green; no db/migration diff hunk.

## Verification

- P1 → TagTreeCutoverCheckIT.runtimeBootstrapFileNamesOnlyTreeNodes +
  the rehearsal's preflight — hostile inputs: a file with retired
  names, a DB with leftovers; asserts the RED legs name them.
- P2 → TagTreeCutoverCheckIT.cleanupRemovesExactlyNostrAndVideo — the
  scope_tag-on-nostr seeding proves the FK-first order (a wrong order
  raises on the tag DELETE).
- P3 → the same cleanup case's bystander assertions (ai row/array/
  scope_tag byte-identical) + cleanup --dry-run changes nothing.
- P4 → TagTreeCutoverCheckIT.cutoverRehearsalPassesPostflight's
  bootstrap_tags⊆nodes assertion.
- P5 → acceptance 7's git-diff probe + the runbook's migrate-step
  wording (Collector start, never psql-run V84).
- P6 → acceptance 5's sequence wording (upgrade.sh restart declined;
  apps.sh stop/start owns the window).
- P7 → TagTreeCutoverCheckIT.runtimeBootstrapFileNamesOnlyTreeNodes
  (fixture path via CUTOVER_BOOTSTRAP_FILE; default is the runtime
  path).
- P8 → acceptance 5's sweep-note probe text (V84:17-23) + TaggerWorkerSweepIT
  green unmodified.
- P9 → acceptance 6's greps + shellcheck + security_relevant: true.
- P10 → the reproduction's live preflight leg recorded by the
  implementor at start.
- P11 → acceptance 5's E4002 recovery prose + TagTreeMigrationIT's
  loud-failure cases green unmodified (the rollback behavior they pin
  is the recovery's premise).
- P12 → acceptance 5's stop-first sequencing.
- acceptance item 1 → TagTreeCutoverCheckIT.leftoverOccurrencesFailThePreflight.
- acceptance items 2-4 → the named IT cases.
- acceptance item 5 → the named probes (grep the subsection anchor, the
  subcommand-set match, the scenario row).
- acceptance item 6 → the named probes + shellcheck (author-run).
- acceptance item 7 → `mvn verify` exit 0 + the git-diff probe.

## Out-of-scope

Per the YAML block: no migration edit (V84 is shipped and immutable —
the ticket executes it); no Java change (the gates shipped in M1-866);
no mapping of nostr/video (the ruling is disposal); no auto-removal of
any other unmapped name (operator decision at the E4002 signal); no
frozen-content rewrite (D19/D36/D65 replay; M1-866 analysis P18); no
dependency on M1-867/M1-869 and no spec prose (the runbook lives in
docs/design/); no prod/config/bootstrap-sources.json edit (M1-866 owns
the template; this ticket reconciles each operator's deployed runtime
copy); no edits to the existing ops scripts or the wizard; no audit
rows, no grant changes. No pre-existing test is modified.

## Census

Class: **DB surfaces that can carry a tag name the cutover must
inspect.** Re-runnable enumeration: `grep -nE "unnest\(|scope_tag|node_kind"
infochat-core/src/main/resources/db/migration/V84__tag_tree_seed_and_migration.sql`
returns the tag-row validation block (V84:131-176), the array
validation block covering post.tags and source.bootstrap_tags
(V84:181-226), and the scope_tag remap (V84:316-344); the column
definitions are V7:159 (post.tags TEXT[]), V6:42
(source.bootstrap_tags TEXT[]), V7:64-69 (scope_tag.tag_id FK), V6:74-84
(tag.name). Disposition: tag.name → cleanup DELETE (after scope_tag —
P2); post.tags → array_remove; source.bootstrap_tags → array_remove;
scope_tag.tag_id → DELETE referencing rows first. Surfaces examined and
excluded: post.tag_candidates (V83) — empty on a pre-cutover DB
(pre-M1-868 code never writes it) and not validated by V84; frozen
content (summary_cache / digest replay slugs / saved_post) — never
rewritten, out of scope (M1-866 analysis P18); the runtime
bootstrap-sources.json file — the file check, not a DB surface.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-880-tag-tree-pre-v84-cutover-runbook.md
```
