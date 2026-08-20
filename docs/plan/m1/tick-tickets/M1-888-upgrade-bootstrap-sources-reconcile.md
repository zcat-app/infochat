---
id: M1-888
title: "Reconcile the deployed bootstrap-sources.json in the cutover"
status: done
created: 2026-08-19
last_updated: 2026-08-20
flow: tick
reproduction: >-
  TagTreeCutoverCheckIT.reconcileFileConvertsLegacySourceTags (converted
  at start from the to-be-written marker; run RED against the pre-change
  script on 2026-08-20 — the unknown reconcile-file subcommand refused
  with the usage text — log .scratch/m1-888-red-run.log; green after the
  fix). The wrong behavior it states: nothing in
  the supported upgrade path validates or converts the DEPLOYED runtime
  bootstrap-sources.json's legacy/title-cased source tags — the Collector
  mounts that copy (docker-compose.yml:146), the wizard never clobbers an
  existing runtime file (5-bootstrap.sh:137-141), and the BootstrapLoader
  leaf gate (BootstrapLoader.java:301-337) refuses startup on any
  normalized file tag that is not an existing leaf, so a deployment whose
  file predates the tag tree crash-loops AFTER the database is already
  migrated. Probes against the current tree: grep -n 'reconcile-file'
  prod/scripts/tag-tree-cutover.sh returns nothing — the script's only
  file handling is the nostr/video name check (tag-tree-cutover.sh:106-
  154) and the postflight node check; no verb WRITES the runtime file.
  Live evidence: after the DB cleanup let V84 complete, the test
  Collector still refused startup on 103 legacy/title-cased tags
  (Development, Java, AI-Image, …) and the file was normalized by hand
  (AI-Image → ai, Video dropped)
  (.scratch/LIVE-E2E-REGRESSION-PLAN-2026-08.md §7;
  .scratch/LIVE-E2E-DEFECT-REPORT-2026-08.md D-2, release blocker).
analysis_ref: docs/plan/m1/tick-analysis/upgrade-pre-v84-cutover.md
blocked_by:
  - M1-887
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
    prod/scripts/upgrade.sh and the DB-side machinery in
    tag-tree-cutover.sh (the generalized PREFLIGHT_SQL, the apply verb,
    the upgrade.sh gate) — M1-887 owns those edits; this ticket adds only
    the reconcile-file subcommand, consuming the rulings-file validator
    M1-887 ships, and must not alter the preflight predicates, the apply
    mechanics, or any upgrade.sh line (analysis P13).
  - >-
    prod/config/bootstrap-sources.json — the committed wizard template,
    already rewritten to tree names by M1-866. This ticket reconciles
    each deployment's DEPLOYED runtime copy (the compose mount), never
    the template.
  - >-
    bootstrap-assets.json — it carries per-asset enabled-sub-verb
    allowlists and NO tag names (verified: grep '"tags"'
    prod/config/bootstrap-assets.json returns nothing;
    docs/spec/deployment.md §Bootstrap behavior on startup).
  - >-
    Auto-inventing a target for an unmapped file tag. Normalized leaves
    are kept, V84 mapping keys convert to their leaves, nostr/video drop
    (the M1-866 ruled disposal — the live hand-fix dropped Video); each
    remaining unmapped name (e.g. AI-Image) requires its own ruling line
    in the rulings file (`name: <tree-leaf>` or `name: drop`) — the verb
    executes written rulings, it never decides (M1-866's loud-contract
    ruling; analysis P12).
  - >-
    Full NFC/Unicode normalization in bash — the reconcile lower-cases
    (Locale.ROOT-equivalent for ASCII, the stored form of
    docs/spec/schema.md §Sources and tags) and the loader's own
    normalizer + leaf gate (BootstrapLoader.java:346-348, :301-337)
    remains the boot-time backstop. The non-ASCII NFC edge is an
    ASSUMPTION the implementor states, not a defensive branch
    (engineering-rules §7).
  - >-
    Any DB mutation (the reconcile edits the runtime FILE only; the
    loader's upsert at the next boot propagates it — spec §Bootstrap
    behavior on startup, upsert-never-delete), any Java change, any
    migration edit, new audit rows, or GRANT changes.
acceptance:
  - "TagTreeCutoverCheckIT.reconcileFileConvertsLegacySourceTags (the converted reproduction) passes — a runtime-file fixture in the wizard's one-array-per-line shape carrying the live D-2 shape ['Development', 'Java', 'AI-Image', 'Video', 'AI']: with a rulings file that has NO line for ai-image, reconcile-file refuses with exit 2 naming the uncovered name and the file is BYTE-IDENTICAL afterwards (failure mode: an unruled unknown never converts); with `ai-image: ai` ruled, reconcile-file --dry-run prints the deterministic classification — Development → software-development, Java → software-development (V84 mapping keys convert to their leaves), Video → dropped (the ruled disposal), AI → ai (normalized leaf, kept), AI-Image → ai (the operator's written ruling) — and the file is BYTE-IDENTICAL afterwards (failure mode: dry-run purity — a would-be destructive run must be reviewable and side-effect-free)."
  - "TagTreeCutoverCheckIT.reconcileFileApplyIsDeterministicIdempotentAndBytePreserving passes — with `ai-image: ai` ruled: the real run prints the conversion table; the rewritten file's tags[] is exactly ['software-development', 'ai'] — mapping dedup (Development + Java → one software-development), order-preserved, normalized forms written; EVERY non-tags line is byte-identical (the parser's one-array-per-line contract and the operator's other fields survive — analysis P11); the script's own postflight file check (the loader gate's predicate) is GREEN on the rewritten file — boundary siting: the assertion is at the consumer's predicate, not the writer's echo (engineering-rules §8 assertion-adequacy); a re-run with the unretired rulings file refuses the consumed `ai-image` line as stale (exit 2 naming the line) and changes nothing, and after the line is retired a further run exits 0 a clean no-op (the staleness guard, analysis P14/P15 — idempotent: no double application is possible)."
  - "TagTreeCutoverCheckIT.reconcileFileRefusesInvalidRulingsAndUnreadableSpans passes (failure mode, analysis P14) — each invalid rulings-file shape fed as a fixture: a duplicate name line; an extra line (a name in no current unknown inventory); a malformed line; a `*: drop` catch-all line; a map target that is no seeded leaf — every leg asserts exit 2 naming the offending line and the runtime file UNTOUCHED; and a fixture whose tags array spans multiple lines keeps the existing loud parse failure (exit 2, the tag-tree-cutover.sh:64-81 contract — an unreadable span fails loud, never unseen)."
  - "docs/design/07-deployment.md §7.14 'Cut over the tag-tree migration (one-time)' names reconcile-file [--dry-run] as the runtime-file step (replacing the hand-edit sentence M1-887 leaves), stating the deterministic classification (leaves kept normalized, mapping keys converted, nostr/video dropped, every other unmapped name taken from its rulings-file line) and that the rewritten file takes effect at the next Collector boot via the loader's upsert. Probes: grep -n 'reconcile-file' docs/design/07-deployment.md hits inside the subsection; grep -o 'tag-tree-cutover.sh [a-z-]*' docs/design/07-deployment.md still matches only subcommands the script implements."
  - "The script meets the prod/scripts conventions (P9 carried): executable, set -euo pipefail, shellcheck-clean (author-run), the credential discipline untouched (no new secrets handling — the file verb reads the same CUTOVER_PSQL/CUTOVER_MAP_FILE seams, no new credential path). Probes: ls -l mode 0755; grep -n 'source.*secrets.env' prod/scripts/tag-tree-cutover.sh returns nothing."
  - "mvn verify from repo root is green; the diff touches no file under infochat-core/src/main/resources/db/migration/ and modifies NO pre-existing test (test_plan.preserves holds — M1-887's pins are the base this ticket adds to, analysis P10/P13)."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java — new cases: reconcileFileConvertsLegacySourceTags, reconcileFileApplyIsDeterministicIdempotentAndBytePreserving, reconcileFileRefusesInvalidRulingsAndUnreadableSpans
  preserves:
    - >-
      all tests currently green on main — in particular, unmodified:
      every M1-887 TagTreeCutoverCheckIT case and every UpgradeWiringTest
      case (the gate's file-fix wording is subcommand-neutral by M1-887's
      design; this ticket changes no wording those tests pin).
spec_refs:
  - docs/spec/deployment.md §Bootstrap behavior on startup
  - docs/spec/schema.md §Sources and tags
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D38
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
    date: 2026-08-20
    verdict: REWORK
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY FAIL, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "5 files changed, 376 insertions(+), 15 deletions(-)"
    rework_items: 1
    verdict_file: .scratch/tick-review-M1-888-r1.txt
  - round: 2
    date: 2026-08-20
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "fix hunks: 3 files changed, 30 insertions(+), 7 deletions(-) (test leg + round-1 bookkeeping); full diff since merge-base: 5 files, 400 insertions(+), 16 deletions(-)"
    rework_disposition: "round-1 item 1 SATISFIED (drop-ruling leg: fixture ML-Ops ruled ml-ops: drop; asserts ML-Ops -> drop table line, consumed-line printout, exact rewritten span minus the element; TagTreeCutoverCheckIT 11 tests 0 failures + BUILD SUCCESS in .scratch/tick-test-M1-888-r2.log)"
    verdict_file: .scratch/tick-review-M1-888-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-08-20
  result: pass
  note: >-
    Start pre-flight: lint clean (0 findings — the initial
    ANALYSIS-REF-RESOLVABLE BLOCKER was a worktree artifact: tick-analysis/
    is gitignored, so the analysis doc was copied into the worktree's
    private path, not a ticket defect); blocked_by M1-887 done. Citations
    spot-checked (docker-compose.yml:146 mount, 5-bootstrap.sh:137-141
    skip-if-present, the BootstrapLoader leaf gate + normalizer at
    infochat-collector/.../BootstrapLoader.java:301-348 — the ticket names
    the wrong module for that file, line substance matches). Census grep
    re-ran clean (bootstrap-sources.json only; assets has zero "tags").
    No replaces:. Analysis cross-read — P1/P7/P9/P11-P15 all landed in the
    ticket. M1-887's pins traced: every TagTreeCutoverCheckIT case and the
    UpgradeWiringTest gate wording is preserved unmodified (this ticket
    only ADDS cases). No blocking ambiguities.
escalation_reason:
---

# M1-888: Reconcile the deployed bootstrap-sources.json in the cutover

## Context

Release blocker D-2 of the 2026-08 live E2E campaign: after the DB
cleanup let V84 complete, the Collector still refused startup because the
deployed runtime `bootstrap-sources.json` carried 103 legacy/title-cased
source tags (`Development`, `Java`, `AI-Image`, …) — a second cutover
gate the upgrade path does not handle. The wizard never refreshes an
existing runtime file (5-bootstrap.sh:137-141), so the legacy file
survives every upgrade, and the loader's leaf gate
(BootstrapLoader.java:301-337) crash-loops the Collector AFTER the DB is
already migrated. The file had to be normalized by hand (`AI-Image` →
`ai`, `Video` dropped). The directed shape (2026-08-19, refined
2026-08-20): VALIDATE + APPLY — validate the deployed copy against the
same gate and apply a deterministic conversion with an explicit listing,
taking any unmapped name's conversion from the operator's written ruling
in the rulings file (`$RUNTIME_DIR/tag-cutover-map.txt`, the M1-887
surface). Full analysis:
docs/plan/m1/tick-analysis/upgrade-pre-v84-cutover.md.

## Root cause

Verified (analysis §Ground truth): the boot input is the DEPLOYED file
(compose mount at /app/bootstrap-sources.json), which predates the tag
tree; nothing in the upgrade path validates or converts it. The
deterministic failure shape: a title-cased name like `Development`
normalizes to `development` — a V84 MAPPING KEY but NOT a tree node — so
even a DB-side cleanup cannot help the file; the loader gate fires on
normalized non-leaf names regardless (BootstrapLoader.java:301-337, with
TagNormalizer at :346-348).

## Pitfalls

Numbered consistently with the analysis document; this ticket owns the
file-side pitfalls (the DB-side apply/gate pitfalls are M1-887's).

- P1: Mirror drift — the conversion table mirrors V84's frozen mapping
  (key → leaf) and seeded leaf list (the same script-side mirror M1-887's
  preflight and rulings validation use); a drift mis-converts a file tag.
  Caught by driving the real postflight file predicate after the rewrite.
- P7: No false positives — a file already at tree names is a silent-clean
  no-op (the idempotence arm), so every later upgrade's preflight stays
  quiet.
- P9: Conventions — set -euo pipefail, shellcheck, mode 0755; no new
  credential path (the file verb reuses the existing seams).
- P11: the jq-free rewrite preserves every non-tags byte and the
  one-array-per-line shape (the parser contract,
  tag-tree-cutover.sh:64-81 — unreadable spans fail loud); dedup after
  mapping; normalized forms written; idempotent; --dry-run
  side-effect-free. A rewrite that reorders/reformats the file destroys
  the operator's content and the parser's own precondition.
- P12: Normalization semantics; never invent — leaves kept (writing the
  normalized form), mapping keys converted, nostr/video dropped by the
  ruling, every other unmapped name taken from its rulings-file line.
  Bash lower-casing is the ASCII stored form; the loader gate stays the
  backstop for the NFC edge (assumption, §7).
- P13: Sibling boundary — this ticket adds the file verb only: no
  preflight re-generalization, no apply-mechanics edit, no upgrade.sh
  touch, no modification of M1-887's pins (its gate wording is
  deliberately subcommand-neutral).
- P14: Rulings-file validation is total and refuses BEFORE any rewrite —
  the shared validator (M1-887's) checks the file against the current
  union inventory: a duplicate, an extra/stale line, an uncovered
  unknown, a malformed line (any `*` catch-all included), or a non-leaf
  map target refuses with exit 2 naming the line or the name, and the
  runtime file stays untouched (the M1-819 print-never-auto-repair
  posture).
- P15: Rulings-file lifecycle — the consumed ruling lines go stale after
  a successful run: a re-run with the unretired file refuses them
  (extras, exit 2 naming the line) and a post-retirement run is a clean
  no-op; keys match the normalized stored form (a title-cased file entry
  like `AI-Image` is ruled by its normalized `ai-image` key, which the
  M1-887 skeleton's comments display).

## Approach

Derived from `spec_refs:`: §Bootstrap behavior on startup commits the
loader to fail-fast on invalid input and to upsert-never-delete — the
reconcile feeds that gate a valid file and lets the next boot propagate
it (no DB write here); §Sources and tags + §Surface conventions commit
the stored normalization form (NFC, Locale.ROOT lower-case,
`[a-z0-9][a-z0-9-]{0,47}`) and the leaf-only bootstrap_tags rule the
conversion targets. The nostr/video drop is the M1-866 user ruling;
every other unmapped name executes the operator's written ruling from
the rulings file — the verb executes rulings, it never decides.

**Files to touch** (guidance, not an allowlist):

1. `prod/scripts/tag-tree-cutover.sh` — new subcommand `reconcile-file
   [--dry-run]` (no argv rulings):
   - Parse the runtime file with the existing file_tag_names parser
     (CUTOVER_BOOTSTRAP_FILE seam, default
     $RUNTIME_DIR/bootstrap-sources.json; the loud exit-2 contract for
     unreadable spans is preserved).
   - Classify every tag deterministically: lower-cased form is a seeded
     leaf → keep (write the normalized form); is a V84 mapping key → its
     leaf (the same script-side mirror M1-887's preflight and rulings
     validation use — ONE mirror in the script, all consumers); is
     nostr/video → drop (ruled); otherwise → unresolved unless the
     rulings file (CUTOVER_MAP_FILE, default
     $RUNTIME_DIR/tag-cutover-map.txt) carries a line for its normalized
     name.
   - Validate the rulings file with M1-887's shared validator against
     the current union inventory (P14): coverage exactly-once of every
     unknown name, no duplicates, no extras/stale lines, no malformed or
     `*` lines, every map target a seeded leaf — any violation refuses
     exit 2 naming the line or the name BEFORE any rewrite.
   - --dry-run: print the full conversion table, change nothing, exit 0
     on a valid file. Apply: rewrite ONLY the `"tags": [...]` spans —
     dedup post-mapping, order-preserved, normalized forms — every other
     byte identical; print the table and the consumed ruling lines to
     retire; a post-retirement re-run is a clean no-op (exit 0).
   - Usage text gains the verb; exit codes unchanged (0 pass / 1
     findings / 2 usage-or-environment — a rulings validation refusal is
     2).
2. `infochat-core/.../TagTreeCutoverCheckIT.java` — the three new cases
   (acceptance 1-3); no existing case touched.
3. `docs/design/07-deployment.md` — §7.14's runtime-file step names
   reconcile-file (acceptance 4).

**Steps, in order:** the script verb first (the IT consumes it) →
convert the reproduction RED → the apply/idempotence/failure-mode legs
green → the runbook wording (naming only implemented subcommands) →
shellcheck + probes + mvn verify.

**Controls to preserve (engineering-rules §10):** the parser's
fail-loud-never-unseen contract (:64-81) extends to the writer; the
loader's fail-fast gate and its BOOTSTRAP_SOURCE_LOAD audit row at boot
are untouched (the reconcile never boots anything); the loader's
upsert-never-delete semantics are the propagation path, so no DB row is
written here; the M1-887 pins (generalized preflight listing, the
rulings-file apply mechanics, the three UpgradeWiringTest legs) pass
unmodified.

**Pitfall→mitigation mapping:** P1→the shared mirror + acceptance 2's
postflight-predicate assertion; P7→the clean no-op arm; P9→acceptance
5's probes; P11→acceptance 2's byte-identity/dedup arms + acceptance 1's
dry-run purity; P12→the classification rules + acceptance 1's
unruled-refusal leg; P13→out_of_scope + test_plan.preserves; P14→
acceptance 3's refusal legs; P15→acceptance 2's stale-line and
post-retirement arms.

## Definition of done

Every `acceptance:` item, verified by its named test/probe: the converted
reproduction green (the D-2 fixture refuses unruled with exit 2, then
classifies exactly as specified under the written ruling, and the
dry-run changes nothing); the apply leg green (exact resulting tags[],
non-tags bytes identical, conversion table printed, GREEN at the
postflight file predicate, stale-line refusal, clean no-op after
retirement); every invalid rulings-file shape and the unreadable span
refused with exit 2 and the file untouched; the runbook step landed
naming only implemented subcommands; conventions probes green; `mvn
verify` green; no db/migration diff hunk; no pre-existing test modified.

## Verification

- P1 → TagTreeCutoverCheckIT.reconcileFileApplyIsDeterministicIdempotentAndBytePreserving
  — after the rewrite, the script's own postflight file check (the
  loader gate's DB-driven predicate) must be GREEN: a mis-mirrored
  conversion fails at the consumer's predicate.
- P7 → the same case's post-retirement arm: a tree-named file is a
  clean no-op (exit 0, no changes printed) — a reconcile that always
  rewrites fails there.
- P9 → acceptance item 5's probes — ls -l mode 0755, the
  secrets-source grep returning nothing, shellcheck (author-run): a
  conventions regression fails there.
- P11 → acceptance 2's arms: exact tags[] content
  (['software-development', 'ai'] — the dedup discriminates: a
  non-deduping mutation leaves two software-development entries),
  per-line byte-identity outside tags[], and acceptance 1's dry-run
  byte-identity.
- P12 → TagTreeCutoverCheckIT.reconcileFileConvertsLegacySourceTags
  (failure mode) — the unruled 'AI-Image' leg: the verb refuses with
  exit 2 naming the uncovered name and never converts it; the classified
  table pins leaves-kept / keys-converted / ruled-dropped exactly.
- P13 → test_plan.preserves: every M1-887 case unmodified (mvn verify).
- P14 → TagTreeCutoverCheckIT.reconcileFileRefusesInvalidRulingsAndUnreadableSpans
  (failure mode) — feeds duplicate / extra / malformed / `*: drop` /
  non-leaf-target fixtures and the multi-line span; asserts exit 2
  naming the line and the runtime file untouched — the refusal never
  partially rewrites.
- P15 → acceptance 2's staleness arms — the consumed `ai-image` line is
  refused on the unretired re-run (exit 2 naming the line), and after
  retirement the run exits 0 a clean no-op.
- acceptance item 4 → the named greps.
- acceptance item 5 → ls -l + the secrets-source grep + shellcheck.
- acceptance item 6 → `mvn verify` exit 0 + the git-diff probe.

## Out-of-scope

Per the YAML block: no edit to upgrade.sh or to M1-887's DB-side
machinery (this ticket adds only the reconcile-file subcommand and
consumes M1-887's shared validator); no template edit (M1-866 owns
prod/config/bootstrap-sources.json); no bootstrap-assets.json (no tag
names); no invented mappings — each unmapped file tag takes its own
written ruling line, nostr/video drop per the standing ruling; no full
Unicode normalization in bash (loader gate is the backstop; stated
assumption); no DB mutation, no Java, no migration edit, no audit rows,
no GRANT changes. **No pre-existing test is modified** — this ticket
only ADDS cases to TagTreeCutoverCheckIT; M1-887's pins (including the
subcommand-neutral gate wording in UpgradeWiringTest) are deliberately
compatible with the verb this ticket adds.

## Census

Class: **runtime files the Collector consumes that can carry tag names.**
Re-runnable enumeration: `grep -l '"tags"' prod/config/*.json` returns
bootstrap-sources.json only; `grep '"tags"'
prod/config/bootstrap-assets.json` returns nothing (verified — the assets
file carries per-asset enabled-sub-verb allowlists,
docs/spec/deployment.md §Bootstrap behavior on startup). Dispositions:
the deployed runtime bootstrap-sources.json (CUTOVER_BOOTSTRAP_FILE,
default $RUNTIME_DIR/bootstrap-sources.json) → THIS ticket's
reconcile-file; prod/config/bootstrap-sources.json (the wizard template)
→ out of scope (M1-866 rewrote it to tree names); bootstrap-assets.json
→ out of scope (no tags). The DB-side name surfaces (tag / post.tags /
source.bootstrap_tags / scope_tag) are M1-887's census, not this
ticket's.

## Round 1 rework

1. Finding 1: add a drop-ruling leg to
   TagTreeCutoverCheckIT.reconcileFileApplyIsDeterministicIdempotentAndBytePreserving
   (fixture gains "ML-Ops", rulings gain "ml-ops: drop"; assert the
   "ML-Ops -> drop (ruling)" table line, the consumed-line printout, and the
   exact rewritten span with the element removed), evaluated via that test's
   extended exact-content assertEquals plus
   assertTrue(real.out().contains("ML-Ops -> drop")) — the mutation of keying
   the rewrite skip on RESOLVED_KIND at prod/scripts/tag-tree-cutover.sh:706
   must fail it.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-888-upgrade-bootstrap-sources-reconcile.md
```
