---
id: M1-900
title: "Fix the span-parser FAIL remedy; drop dead jq advice"
status: done
created: 2026-08-21
last_updated: 2026-08-21
flow: tick
reproduction: >-
  TagTreeCutoverCheckIT.prettyPrintedRuntimeFileFailsWithTheRealRemedy
  The wrong behavior it states (D-14 + UX-1, defects log
  .scratch/V2.0.0-DEFECTS-AND-SMELLS-LOG.md; observed live): postflight on
  the test instance's own deployed bootstrap-sources.json exits 2 with
  "cannot read every \"tags\": [...] span ... (a tags array spans multiple
  lines) — keep the one-array-per-line shape or install jq."
  (/tmp/opencode/c1/postflight-live.out:8-9) — but the script has NO jq code
  path (the only jq mentions are the comment at tag-tree-cutover.sh:75 and
  the advice itself at :89; grep verified), so installing jq changes
  nothing, and the real remedy (restore the wizard's one-array-per-line
  shape) is what the message does NOT say. The live file's content is
  entirely valid — 78 "tags" keys, 0 single-line-parseable spans, every tag
  already a tree node (counts re-derived 2026-08-21); the shape-corrected
  copy passes postflight 8/8 GREEN (/tmp/opencode/c1/postflight-live2.out).
  The fixed behavior the test asserts: the FAIL text names the real remedy
  and contains no "install jq".
analysis_ref: docs/plan/m1/tick-analysis/cutover-apply-mapping-key-destruction.md
blocked_by: []
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
    Honoring jq when present, or any second parse path — weighed and
    REJECTED in the analysis (P10): two host-dependent behavior tiers and a
    doubled parse-contract test matrix against a tool whose premise is
    no-jq-dependency (tag-tree-cutover.sh:75). Probe: grep -n 'jq'
    prod/scripts/tag-tree-cutover.sh shows only the no-dependency comment
    after this ticket (the advice line is gone; no jq invocation is added).
  - >-
    Any parser RELAXATION — multi-line-span tolerance, a lenient mode, a
    silent skip. The fail-loud-never-unseen contract (:75-77, :87-91, and
    the writer-side guard :696-699) is deliberate and stays; this ticket
    changes the MESSAGE and the DOCUMENTATION, never the condition or the
    exit code (analysis P8).
  - >-
    The apply/coverage/lifecycle surfaces — M1-898 and M1-899. The §7.14
    edit here is the deployed-file SHAPE requirement only (sited outside
    the steps 5-6 paragraphs M1-899 rewrites); if M1-899 is in flight,
    land these in sequence or rebase (analysis Decomposition).
  - >-
    The postflight all-rows-vs-leaf-only file predicate (M1-887 r1's
    RECOMMENDED-NEW-TICKET, analysis P12) — pre-existing, recorded open,
    owner's call; NOT fixed here.
  - >-
    Any migration file, upgrade.sh, reconcile-file classification, or Java
    change (migration_touch: false — probe: git diff --name-only shows no
    db/migration path).
acceptance:
  - "TagTreeCutoverCheckIT.prettyPrintedRuntimeFileFailsWithTheRealRemedy (the converted reproduction) passes — a runtime-file fixture in the LIVE file's pretty-printed shape (every \"tags\" array spanning multiple lines; content all valid tree leaves) fed to BOTH `postflight` and `reconcile-file --dry-run`: each exits 2; the message keeps the pinned prefix \"cannot read every \\\"tags\\\"\", states the real remedy — restore the one-array-per-line shape (every \"tags\": [...] span on a single line), the shape the wizard template prod/config/bootstrap-sources.json models, noting the Collector reads either shape and only this cutover tooling requires it — and does NOT contain 'install jq' (failure mode: dead advice in an operator-facing failure message; analysis P8/P10). The fixture's content validity is the discrimination: only the shape defeats the parser."
  - "The fail-loud contract is untouched (analysis P8): TagTreeCutoverCheckIT.reconcileFileRefusesInvalidRulingsAndUnreadableSpans passes UNMODIFIED (its multi-line-span leg pins the \"cannot read every\" prefix at :513 — the message change keeps that prefix), the span guard still fires on exactly the same condition with exit 2, and the writer-side guard (:696-699) still refuses an unreadable span before any rewrite. This ticket modifies NO pre-existing test."
  - "docs/design/07-deployment.md §7.14 names the deployed runtime file's shape requirement: the cutover tooling reads bootstrap-sources.json with a one-array-per-line span parser (every \"tags\": [...] on a single line, the wizard template's shape); a pretty-printed file fails loud with the remedy; restoring the shape is the fix — the Collector itself reads either shape. Probes: grep -n 'one-array-per-line' docs/design/07-deployment.md hits inside §7.14; grep -rn 'install jq' prod/scripts/tag-tree-cutover.sh docs/design/07-deployment.md returns nothing."
  - "The script meets the prod/scripts conventions: shellcheck-clean (author-run), mode 0755; no credential-path touch (probe: grep -n 'PGPASSWORD' prod/scripts/tag-tree-cutover.sh shows only the existing read/export lines)."
  - "mvn verify from repo root is green; the diff touches no file under infochat-core/src/main/resources/db/migration/."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema/TagTreeCutoverCheckIT.java — new case: prettyPrintedRuntimeFileFailsWithTheRealRemedy
  preserves:
    - >-
      all tests currently green on main — in particular, unmodified: every
      existing TagTreeCutoverCheckIT case (including the multi-line-span
      leg of reconcileFileRefusesInvalidRulingsAndUnreadableSpans, whose
      pinned prefix the new message keeps), every UpgradeWiringTest case,
      and M1-898/M1-899's new cases (their surfaces do not intersect this
      ticket's two-line message edit).
spec_refs:
  - docs/spec/deployment.md §Bootstrap behavior on startup
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
    date: 2026-08-21
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "5 files changed, 39 insertions(+), 11 deletions(-)"
    verdict_file: .scratch/tick-review-M1-900-r1.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-900: Fix the span-parser FAIL remedy; drop dead jq advice

## Context

D-14 + UX-1 of the v2.0.0 verification campaign (defects log
`.scratch/V2.0.0-DEFECTS-AND-SMELLS-LOG.md`): the cutover script's span
parser cannot read a pretty-printed runtime bootstrap-sources.json and its
FAIL text prescribes a remedy that does nothing — "keep the
one-array-per-line shape or install jq" (tag-tree-cutover.sh:88-89) — while
the script has no jq code path at all (the only other jq mention is the
"No jq dependency" comment at :75). The live test instance's own deployed
file trips this at postflight (/tmp/opencode/c1/postflight-live.out:8-9):
78 `"tags"` keys, 0 single-line-parseable spans, content entirely valid
tree nodes — only the shape defeats the parser, and the shape-corrected
copy passes 8/8 GREEN (postflight-live2.out). §7.14 names no shape
requirement for the deployed file, so an operator hits this with no
documented way out. Full analysis:
docs/plan/m1/tick-analysis/cutover-apply-mapping-key-destruction.md.

## Root cause

Verified (analysis §Ground truth): the parser contract is deliberate and
sound (file_tag_names :78-95; comment :75-77 — "a span this parser cannot
read fails loud, never unseen"). The defect is purely the message and the
missing documentation: the FAIL text at :88-89 names a remedy ("install
jq") that cannot work (no jq path exists) and omits the remedy that does
(restore the wizard's one-array-per-line shape), and §7.14
(07-deployment.md:1700-1727) never states the deployed file's shape
requirement. The campaign's D-2 hand-normalization produced content-correct
output in an editor's pretty-printed shape — a predictable operator
trajectory the tooling's message must meet.

## Pitfalls

Numbered consistently with the analysis document:

- P5: Fixture calibration — the new message must keep the
  "cannot read every \"tags\"" prefix pinned by the existing
  reconcileFileRefusesInvalidRulingsAndUnreadableSpans (:513); this ticket
  modifies NO pre-existing test. The new case's discriminating assertion
  is the remedy CONTENT (one-array-per-line restore named; 'install jq'
  absent) — a message that swaps one dead remedy for vaguer advice fails
  it.
- P8: The fail-loud-never-unseen contract is a control, not the defect —
  text-only change; the refusal condition (:87-91) and exit code are
  byte-identical in behavior; no multi-line tolerance, no lenient mode
  (a hand-rolled multi-line JSON parse in bash is the fragility the
  contract exists to refuse — M1-888 P11).
- P9: Conventions — shellcheck-clean, mode 0755, no credential-path touch.
- P10: The jq temptation — "honor jq when present" is REJECTED (analysis,
  Solution options D): host-dependent behavior tiers and a doubled
  parse-contract test matrix against the tool's no-jq premise (§2/§4
  simplicity; §7 no defensive second path).

## Approach

Derived from `spec_refs:`: §Bootstrap behavior on startup commits the
loader to reading the deployed file (with a real JSON parser — either
shape boots), which is exactly why the remedy is "restore the shape for
the tooling", not "the file is invalid"; §Surface conventions commits the
stored tag form the file carries.

**Files to touch** (guidance, not an allowlist):

1. `prod/scripts/tag-tree-cutover.sh` — the file_tag_names FAIL text
   (:88-89) only: keep the "cannot read every \"tags\": [...] span"
   prefix and the multi-line explanation; replace the remedy clause with
   the real one — restore the one-array-per-line shape (every
   `"tags": [...]` span on a single line), the shape the wizard template
   prod/config/bootstrap-sources.json models; the Collector reads either
   shape, only this tooling requires it. Delete "or install jq". No code
   path changes; the comment at :75-77 stays true (no jq dependency).
2. `infochat-core/.../TagTreeCutoverCheckIT.java` — the one new case
   (acceptance 1). No existing case touched.
3. `docs/design/07-deployment.md` — §7.14 gains the shape requirement
   (acceptance 3), sited in the subsection's runtime-file framing OUTSIDE
   the steps 5-6 paragraphs M1-899 rewrites.

**Steps, in order:** write the reproduction case RED against the current
message (it contains 'install jq' and no real remedy) → the message edit →
green → the §7.14 sentence → shellcheck + probes + mvn verify.

**Controls to preserve (engineering-rules §10):** the fail-loud-never-unseen
contract on BOTH the read path (:87-91) and the writer-side span guard
(:696-699) — same condition, same exit code, same zero-mutation refusal;
the pinned message prefix (P5); the postflight's eight predicates
unchanged; no credential or transport surface touched.

**Pitfall→mitigation mapping:** P5→the prefix constraint + acceptance 2's
unmodified-pin probe + test_plan.preserves; P8→the text-only diff boundary
+ acceptance 2; P9→acceptance 4's probes; P10→out_of_scope + the jq grep
probe.

## Definition of done

Every `acceptance:` item, verified by its named test/probe: the converted
reproduction green (both verbs fail loud on the pretty-printed fixture
with the real remedy and no 'install jq'); the fail-loud contract provably
untouched (the existing multi-line-span pin passes unmodified, same
condition and exit code); §7.14 names the shape requirement with its
probes green; conventions probes green; `mvn verify` green; no
db/migration diff hunk.

## Verification

- P8/P10 → TagTreeCutoverCheckIT.prettyPrintedRuntimeFileFailsWithTheRealRemedy
  (failure mode, the reproduction) — feeds the live file's shape with
  all-valid content to postflight and reconcile-file --dry-run; asserts
  exit 2, the kept prefix, the real remedy named, and the absence of
  'install jq'. A relaxed parser (exit 0), a changed refusal condition,
  or a message still carrying dead advice fails it.
- P5 → acceptance 2: reconcileFileRefusesInvalidRulingsAndUnreadableSpans
  green UNMODIFIED (its :513 prefix pin is the tripwire for a message
  rewrite that drifts the pinned prefix).
- P9 → acceptance 4's probes (shellcheck author-run, mode 0755, the
  PGPASSWORD grep).
- acceptance item 3 → the named greps (§7.14 shape sentence; 'install jq'
  absent from script and runbook).
- acceptance item 5 → `mvn verify` exit 0 + the git-diff probe.

## Out-of-scope

Per the YAML block: no jq path and no second parser (analysis P10,
rejected option D); no parser relaxation — the contract stays fail-loud
(P8); no apply/coverage/lifecycle surface (M1-898/M1-899 — this ticket's
§7.14 edit is the shape requirement only, sited outside M1-899's steps
5-6 rewrite); the postflight leaf-predicate gap stays open (analysis P12);
no migration, upgrade.sh, reconcile-classification, or Java change.
**No pre-existing test is modified** — the new message keeps the pinned
"cannot read every \"tags\"" prefix (P5).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-900-cutover-span-parser-jq-wording.md
```
