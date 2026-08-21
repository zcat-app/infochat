---
id: M1-899
title: "Spell out the shared cutover rulings-file lifecycle"
status: done
created: 2026-08-21
last_updated: 2026-08-21
flow: tick
reproduction: >-
  TagTreeCutoverCheckIT.applyConsumedPrintoutKeepsLinesReconcileStillNeeds
  The wrong behavior it states (UX-2, defects log
  .scratch/V2.0.0-DEFECTS-AND-SMELLS-LOG.md; observed live in C1): a rulings
  line whose name is BOTH a DB unknown and a file-side unresolved name
  (`ai-image: ai` with ai-image on DB surfaces AND `AI-Image` in the runtime
  file) is printed by `tag-tree-cutover.sh apply` under "consumed rulings —
  retire these line(s)" (tag-tree-cutover.sh:604-614;
  /tmp/opencode/c1/apply-real.out:6-9), and §7.14 step 5
  (docs/design/07-deployment.md:1717) tells the operator to retire those
  lines — but the very next step's reconcile-file REFUSES without that line
  ("unknown name 'ai-image' has no ruling", tag-tree-cutover.sh:804-811),
  while the standing/key lines apply consumed must conversely be retired
  BEFORE reconcile or it refuses them as stale (:797-802). An operator
  following §7.14 steps 4-6 literally gets refused twice. The fixed behavior
  the test asserts: apply's printout classifies each consumed line — lines
  reconcile-file still needs are named as kept-for-reconcile, never listed
  under the retire-now instruction — and retiring only the marked lines
  leaves a rulings file reconcile-file accepts.
analysis_ref: docs/plan/m1/tick-analysis/cutover-apply-mapping-key-destruction.md
blocked_by:
  - M1-898
files_scope:
  - prod/scripts/tag-tree-cutover.sh
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java
  - docs/design/07-deployment.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The apply's coverage/execution semantics and the mapping-key ruling
    refusal — M1-898 owns those (blocked_by). This ticket assumes the
    post-M1-898 behavior: apply demands rulings for DB unknowns only and a
    key ruling refuses loud; it changes ONLY the consumed-lines printout's
    classification and the runbook lifecycle text.
  - >-
    The span-parser FAIL text and the shape requirement — M1-900. The §7.14
    edits here are steps 5-6 (the lifecycle); if M1-900 is in flight
    concurrently, its shape sentence is sited outside those steps (analysis
    Decomposition).
  - >-
    Any change to reconcile-file's classification, coverage union, or span
    writer (M1-888, pinned); any upgrade.sh change; any migration file
    (migration_touch: false — probe: git diff --name-only shows no
    db/migration path).
  - >-
    Changing the rulings-file format, the skeleton's content rules beyond
    M1-898's key-placeholder removal, or the staleness/extras refusal
    itself (M1-887 P14/P15) — the lifecycle is DOCUMENTED and the printout
    CLASSIFIED; the validator's rules do not move.
  - >-
    The postflight all-rows-vs-leaf-only file predicate (analysis P12) —
    recorded open, owner's call.
acceptance:
  - "TagTreeCutoverCheckIT.applyConsumedPrintoutKeepsLinesReconcileStillNeeds (the converted reproduction) passes — seeds ai-image as BOTH a DB unknown (tag row, post {ai, ai-image}, a scope_tag follow) AND a file-side unresolved name (file fixture carries AI-Image), plus a `video` DB occurrence; rulings `ai-image: ai` + `video: drop`: the real apply's output lists `video: drop` under the retire-now instruction but does NOT list `ai-image: ai` there — it names `ai-image: ai` as still needed by reconcile-file (analysis P11 class (i) vs (ii)); retiring exactly the marked lines (video: drop) and running `reconcile-file` SUCCEEDS, consumes `ai-image: ai`, and the post-reconcile rulings file (now empty of active lines) passes both verbs as clean no-ops (failure mode: an operator following the printed instructions literally must never be refused by the next step). Boundary siting: the assertion is on the operator-facing instruction text, not an internal state (engineering-rules §8 assertion-adequacy)."
  - "The classification reuses the file-side predicate, not a second list: a consumed DB line is marked still-needed IFF its name appears in the CURRENT runtime file as a name reconcile-file would resolve via a ruling (resolve_tag's 'ruling' kind — not a leaf, not a mapping key, not nostr/video); pinned by the reproduction's negative leg — with the file fixture carrying AI-Image the line is kept-marked, and in a second seeding WITHOUT AI-Image in the file the same `ai-image: ai` ruling prints under retire-now (the discriminating assertion: the mark differs by file state, analysis P5/P11)."
  - "docs/design/07-deployment.md §7.14 steps 5-6 are rewritten to the single lifecycle (analysis P11): (i) a consumed line whose name is still a file-side unresolved name SURVIVES apply into reconcile-file and is retired after it; (ii) every other apply-consumed line — including the standing nostr/video drops — is retired after apply, before reconcile-file (reconcile never takes rulings for keys or nostr/video); (iii) file-only non-key lines are consumed by reconcile-file only; (iv) mapping-key names take no line at all (M1-898's loud refusal). Probes: grep -n 'retire' docs/design/07-deployment.md shows the per-class retirement rules inside the subsection; grep -o 'tag-tree-cutover.sh [a-z-]*' docs/design/07-deployment.md still matches only implemented subcommands; no sentence instructs retiring a line class another step requires."
  - "Every pre-existing pin passes UNMODIFIED — in particular applyAppliesTheRulingsFile and reconcileFileApplyIsDeterministicIdempotentAndBytePreserving (the printout keeps every consumed NAME present and the 'consumed rulings' header phrase those cases pin — analysis P5) and the three UpgradeWiringTest legs (probe: mvn verify). This ticket modifies NO pre-existing test."
  - "The script meets the prod/scripts conventions: shellcheck-clean (author-run), mode 0755, no credential-path touch (probe: grep -n 'PGPASSWORD' prod/scripts/tag-tree-cutover.sh shows only the existing read/export lines)."
  - "mvn verify from repo root is green; the diff touches no file under infochat-core/src/main/resources/db/migration/."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java — new case: applyConsumedPrintoutKeepsLinesReconcileStillNeeds
  preserves:
    - >-
      all tests currently green on main — in particular, unmodified: every
      existing TagTreeCutoverCheckIT case (including M1-898's new pair),
      every UpgradeWiringTest case, TagTreeMigrationIT, BootstrapLoaderIT,
      TaggerWorkerSweepIT, RestoreFlywayChecksumIT. The printout split is
      shaped so no existing wording pin moves (analysis P5).
spec_refs:
  - docs/spec/deployment.md §Bootstrap behavior on startup
  - docs/spec/schema.md §Sources and tags
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
    date: 2026-08-21
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "5 files changed, 103 insertions(+), 16 deletions(-)"
    verdict_file: .scratch/tick-review-M1-899-r1.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  result: clear
  checked: 2026-08-21
  note: >-
    M1-898 is done; the cited apply/reconcile seams and the runbook steps are
    present as described. No implementation ambiguity blocks execution.
escalation_reason:
---

# M1-899: Spell out the shared cutover rulings-file lifecycle

## Context

UX-2 of the v2.0.0 verification campaign (defects log
`.scratch/V2.0.0-DEFECTS-AND-SMELLS-LOG.md`): the single shared
`tag-cutover-map.txt` rulings file serves two verbs — `apply` (DB side) and
`reconcile-file` (runtime file) — and its lifecycle across them is
self-contradictory. Apply prints its consumed rulings under "retire these
line(s)" (tag-tree-cutover.sh:604-614) and §7.14 step 5
(07-deployment.md:1717) says to retire them — but a line like
`ai-image: ai` whose name is both a DB unknown and a file-side unresolved
name is exactly what step 6's reconcile-file still needs (refusal:
"unknown name ... has no ruling", :804-811), while the standing
nostr/video drops and (pre-M1-898) the trap-forced key lines must be
retired BEFORE reconcile or it refuses them as stale (:797-802). Both
refusal modes observed live (/tmp/opencode/c1/apply-stale.out,
reconcile-stale.out). An operator following the runbook literally gets
refused twice. Full analysis:
docs/plan/m1/tick-analysis/cutover-apply-mapping-key-destruction.md.

## Root cause

Verified (analysis §Ground truth): two consumers, one file, and the only
operator guidance — apply's consumed printout (:604-614) and §7.14 steps
5-6 (:1717-1718) — states no per-line-class disposition. The printout
filters to DB_UNKNOWN names (:607) but says nothing about whether a
consumed line is still needed by reconcile-file; the runbook's step-6
sentence about key lines (:1718) papers over one class while step 5's
blanket "retire those lines" creates the other. After M1-898 the line
classes are fully determined (analysis P11: (i) DB+file names, (ii)
DB-only names incl. the standing drops, (iii) file-only non-key names,
(iv) keys — never ruled), so the contradiction can be removed at its two
surfaces: the printout classifies, the runbook spells out the lifecycle.

## Pitfalls

Numbered consistently with the analysis document:

- P2: The staleness/extras guard is the mechanism this lifecycle rides —
  do not relax it (a ruling for a name in no current inventory still
  refuses). This ticket classifies CONSUMED lines; it changes no
  validation rule (M1-887 P14/P15).
- P5: Fixture calibration — the split printout must keep every consumed
  NAME present and the "consumed rulings" header phrase:
  applyAppliesTheRulingsFile (:314-315) and
  reconcileFileApplyIsDeterministicIdempotentAndBytePreserving (:457-460)
  pin those, and this ticket modifies NO pre-existing test. The
  discriminating assertion of the new case (the kept-mark differs by file
  state) must discriminate — a printout that always keeps, or always
  retires, fails it.
- P9: Conventions — shellcheck-clean, mode 0755, no credential-path touch
  (the printout code sits next to the transaction output; no new secrets
  handling).
- P11: One lifecycle, one rule per line class — the code's printout and
  §7.14 must state the SAME dispositions; a runbook that contradicts the
  tool's own output is the defect restated (the current state:
  apply-real.out:6-9 tells the operator to retire the line :1718 needs).

## Approach

Derived from `spec_refs:`: §Bootstrap behavior on startup is why
reconcile-file exists at all (the loader's fail-fast gate + upsert
propagation — the file must be valid at the next boot, so its rulings must
survive until consumed); §Sources and tags commits the stored normalized
form the line classes key by.

**Files to touch** (guidance, not an allowlist):

1. `prod/scripts/tag-tree-cutover.sh` — apply's consumed-rulings printout
   (:604-614) only: for each DB-consumed ruling, classify with the
   file-side predicate (the name appears in the current file inventory AND
   resolve_tag would resolve it via a ruling — not leaf/key/nostr/video).
   Still-needed lines print under a keep-for-reconcile-file grouping;
   the rest under the existing retire-now instruction, keeping the
   "consumed rulings" header phrase and every consumed NAME present (P5).
   No validation, transaction, or dry-run code moves.
2. `infochat-core/.../TagTreeCutoverCheckIT.java` — the one new case
   (acceptance 1-2). No existing case touched.
3. `docs/design/07-deployment.md` — §7.14 steps 5-6 rewritten to the
   single lifecycle (acceptance 3), naming only implemented subcommands.
   Do not touch the shape-requirement sentence if M1-900 has landed it
   (it sites outside steps 5-6).

**Steps, in order:** the printout classification first (the IT consumes
it) → write the reproduction case RED against the post-M1-898 script
(today's printout lists ai-image: ai under retire) → green → the runbook
rewrite → shellcheck + probes + mvn verify.

**Controls to preserve (engineering-rules §10):** the validation contract
untouched (P2 — zero-mutation refusals, exact coverage); the apply's audit
surface (per-surface counts unchanged; the printout split keeps names +
header — P5); reconcile-file's own consumed printout (:831-841) unchanged
in behavior; the skeleton rules as M1-898 leaves them; the runbook's other
steps (stop-first order, backup, migrate = Collector start) untouched.

**Pitfall→mitigation mapping:** P2→out_of_scope + preserved refusal cases;
P5→the printout shape constraints + acceptance 4's unmodified-pins probe +
the discriminating negative leg; P9→acceptance 5's probes; P11→the
acceptance-3 greps requiring per-class rules that agree with the printout.

## Definition of done

Every `acceptance:` item, verified by its named test/probe: the converted
reproduction green (the kept-mark never instructs retiring a line
reconcile-file needs; the literal retire-then-reconcile sequence succeeds
end to end); the discriminating negative leg green (no AI-Image in the
file → the same ruling prints retire-now); §7.14 steps 5-6 state the
per-class lifecycle with the probes green; every pre-existing pin passes
unmodified; conventions probes green; `mvn verify` green; no db/migration
diff hunk.

## Verification

- P11/P5 → TagTreeCutoverCheckIT.applyConsumedPrintoutKeepsLinesReconcileStillNeeds
  (failure mode, the reproduction) — feeds the DB+file double-presence
  seeding; asserts the instruction text never retires the still-needed
  line and that the literal sequence (retire marked → reconcile-file) runs
  clean. A printout that keeps everything, retires everything, or drops a
  name fails it; the negative leg fails a classification that ignores file
  state.
- P2 → the preserved refusal cases (applyRefusesAnInvalidRulingsFile,
  reconcileFileRefusesInvalidRulingsAndUnreadableSpans) green unmodified —
  any relaxation of the staleness guard fails them.
- P9 → acceptance 5's probes (shellcheck author-run, mode 0755, the
  PGPASSWORD grep).
- acceptance item 3 → the named greps on §7.14.
- acceptance item 6 → `mvn verify` exit 0 + the git-diff probe.

## Out-of-scope

Per the YAML block: no apply coverage/execution semantics (M1-898's, via
blocked_by); no span-parser or §7.14 shape-requirement work (M1-900); no
reconcile-file classification/coverage/writer change; no upgrade.sh or
migration touch; no rulings-format or validator-rule change — the
lifecycle is documented and the printout classified, the rules do not
move; the postflight leaf-predicate gap stays open (analysis P12).
**No pre-existing test is modified** — the printout split is deliberately
shaped (names + "consumed rulings" header preserved) so every existing
wording pin holds (P5).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-899-cutover-map-file-lifecycle.md
```

## Review observations

From the round-1 review (APPROVE), a RECOMMENDED-NEW-TICKET recorded, not
relayed (`TOUCHED-BY-THIS-DIFF: no`, no `DECIDE-BEFORE:`) — filing is the
user's call:

- `load_actions` in prod/scripts/tag-tree-cutover.sh still carries the dead
  `lineno=0` local already flagged in M1-887's round-2 review and re-noted in
  this family's analysis; a one-line deletion no acceptance item here
  authorizes. WHAT: a dead variable assignment sits in a live validation
  path. WRONG: reading load_actions today shows a `lineno` local that is set
  and never used. EXPECTED: the function carries no unused local.
