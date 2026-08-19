---
id: M1-886
title: "Spec: reply-mode decisive switch amendments (D79)"
status: pending
created: 2026-08-19
last_updated: 2026-08-19
flow: tick
reproduction: >-
  Probe: grep -n 'registry' docs/spec/llm.md hits :322 and :333, grep -n
  'registry\|uncleared\|inactive' docs/spec/commands.md hits :1179-1189, and
  the D79 row (docs/spec/decisions.md:98) promises "Native resolves only for
  a (model, language) pair the bar-clearing registry clears" — while the
  user-directed, live-verified behavior is the operator-owned decisive
  switch (configured native resolves native for any model and language;
  .scratch/LIVE-E2E-DEFECT-REPORT-2026-08.md:32 records the divergence as
  "Pending divergence" and directs this amendment). The registry gate the
  spec promises was live-proven silently inert on 100% of turns
  (.scratch/LIVE-E2E-REGRESSION-HANDOFF-2026-08-18.md §Source change made),
  so the spec contradicts both the directed behavior and the working-tree
  fix waiting behind this ticket; the amendment lands first (spec-first,
  M1-845 shape).
analysis_ref: docs/plan/m1/tick-analysis/reply-mode-decisive-switch.md
blocked_by: []
files_scope:
  - docs/spec/decisions.md
  - docs/spec/llm.md
  - docs/spec/commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ANY code, bundle, test, or script change — M1-885 owns the working-tree
    fix, the five-bundle help-text reword, and the comment cleanups. This
    ticket is a spec amendment only (the M1-663/M1-845 shape); the user
    approves the exact wording before it lands (engineering-rules §12).
  - >-
    docs/spec/security.md — its §Secrets handling D79 bullet
    (security.md:2258-2273) is mode-conditional but carries NO registry-gate
    wording and survives true as-is; do not touch it (verified by grep:
    every chat-reply-mode 'registry' hit in docs/spec/ is one of this
    ticket's four sites).
  - >-
    WEAKENING any surviving commitment: the two modes and the translate
    deployment default, the per-scope override + inheritance, the
    window-raw/checkpoint-English canonicity sentences, the D58
    query-anchoring-both-modes sentence, the declared-never-inferred rule
    (D29), the translation sanity checks, and every other display leg's
    unconditionality (the family P19) all survive intact.
  - >-
    The measurement record docs/measurement/direct-chat-e2e.md — a frozen
    historical artifact; its "M1-848 registry seed" citation is history,
    not a rule. The D79 row is where the posture change lives.
  - >-
    Dates, ticket IDs, or report citations in spec rule text (§12) — the
    defect's history belongs in the register's motivation column and the
    analysis document.
acceptance:
  - "The D79 row (docs/spec/decisions.md:98) is rewritten as the operator-owned decisive switch (rule-text only; user approves the wording, §12): the two modes and `infochat.chat.reply-mode` (default `translate`) unchanged; the native-resolution clause becomes — the configured mode is decisive, the per-scope override wins over the deployment default, native resolves whenever configured for any model and any language, there is NO clearance condition, NO resolution-time fallback, and NO stored-but-inactive state; the D78-aligned ownership is stated — the operator owns the value, the committed in-language measurement record is ADVICE for that choice, never a gate, and an operator choosing native against the record's advice risks reply quality only, never a security posture; the window-raw/checkpoint-English canonicity and the D58 both-modes sentences survive byte-identical. Verify: grep -n 'registry' docs/spec/decisions.md shows no hit inside the D79 row, and the row still names `infochat.chat.reply-mode` with its `translate` default."
  - "docs/spec/llm.md §Translation flow (:319-324) loses the registry-gate wording: the mode-resolution sentence states the override-wins/decisive rule and the never-flips-mid-turn property with no (model, language) clearance condition, no code-constant registry, and no uncleared-pair fallback. Verify: grep -n 'registry\\|clears\\|uncleared' docs/spec/llm.md §Translation flow region returns nothing."
  - "The accepted-residual sentence (docs/spec/llm.md:329-334) is re-owned, not dropped (analysis P6): native mode still carries no mechanical language net and a whole-turn collapse still delivers the wrong language with no note — the stated controls become the operator's configured choice informed by the measurement record as advice (the D78 override shape), not the registry gate. Verify: grep -n -A2 'no mechanical language net' docs/spec/llm.md shows the residual paragraph present with the operator-owned controls sentence."
  - "docs/spec/commands.md §Conversation control's /reply-mode entry (:1173-1190) is reworded to the decisive behavior: `native` takes effect when set (no pair-clearing condition); the stored-either-way, uncleared-pair-confirmation, and stored-but-inactive-status clauses are removed; every other command property survives — the index entry (:236) and CATALOGUE membership, DM own scope, group admin or bot admin, probation-allowed, zero audit rows, the unsupported-value error listing `translate` and `native`, the bare-invocation status read, D43 bundle strings. Verify: grep -n 'registry\\|uncleared\\|inactive' docs/spec/commands.md §Conversation control region returns nothing, and grep -n '^/reply-mode' docs/spec/commands.md shows the index entry intact."
  - "The wizard-claim fork is decided WITH the wording (analysis P7): arm (a) RECOMMENDED — the D79 row names the measurement record as operator-facing advice and makes NO setup-wizard-recommendation claim (no prod script reads or writes `infochat.chat.reply-mode` today; grep -r 'reply-mode' prod/ returns empty), with a wizard ask left to a possible follow-up ticket; arm (b) — the user explicitly approves a wizard-recommendation sentence AND a follow-up ticket against prod/scripts/4-llm.sh is filed alongside. Verify: the §12 approval record names the chosen arm, and the landed row is truthful under it."
  - "Rule-text only throughout: git diff docs/spec/ carries no dates, ticket IDs, or report citations in spec prose (§12); the D79 row's motivation column carries the history descriptively (the silent-inertness failure mode of a model-name string match; the D78 operator-owned precedent; the record-as-advice resolution) in the register's established style. Verify: git diff docs/spec/ shows the register history confined to the motivation column."
  - "mvn verify from repo root is green with the doc gates passing (engineering-rules §5): CommandCatalogueParityTest (index + CATALOGUE + tier untouched), DocumentedConfigKeyParityTest (`infochat.chat.reply-mode` remains documented AND built — no exemption entry), LlmOutputSanitizerTest closed-list parity (a commands.md body reword, no command-set change). Verify: the verify log."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Spec-only ticket (M1-663/M1-845 precedent): no JUnit surface; the doc
      gates are the test surface. The commands.md edit is a body reword of an
      existing entry — the command index, CATALOGUE, and tier model do not
      move, so the parity gates' pre-change state equals their post-change
      state by construction.
spec_refs:
  - docs/spec/decisions.md §Decisions log
  - docs/spec/llm.md §Translation flow
  - docs/spec/commands.md §Conversation control
decision_refs:
  - D29
  - D43
  - D58
  - D73
  - D78
  - D79
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for: docs/spec/llm.md §Translation flow
spec_amend_parent: M1-885
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-886: Spec: reply-mode decisive switch amendments (D79)

## Context

D79's registry gate was live-proven silently inert on 100% of turns: it
compared the deployment's short chat-model ID against the configured GGUF
filename, never matched, and silently resolved TRANSLATE while `/reply-mode`
reported the configured native posture (evidence and citations in the
analysis document, `analysis_ref:`). The user has directed and pre-approved
the fix's shape — the configured mode is decisive, D78-style operator
ownership — and the working-tree code waits behind this amendment
(M1-885, blocked). The landed spec still promises the gate in exactly four
places; this ticket rewrites those four clauses as rule-text, with the exact
wording approved by the user before it lands (§12).

## Root cause

Not a code defect in this ticket's scope: the spec text was written for the
bar-clearing-registry design (M1-845, D79) that operational evidence has
since falsified — a model-name string match interposed between an explicit
operator choice and behavior fails silent, and D78 settled the same question
class for /image as operator-owned. The spec must record the operator-owned
decisive switch before the code that implements it may land (spec-first).

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P6, P7, P8.

- P1: spec-first inversion — this ticket IS the mitigation; it merges before
  M1-885 and its wording is approved before any code lands. Drafting it as a
  record of the code ("the code does X") instead of as rules the system
  commits to would invert the relationship again.
- P6: the language-collapse residual must be re-owned honestly
  (llm.md:329-334): no mechanical language net exists in native mode, and the
  controls are now the operator's configured choice informed by the
  measurement record as advice — neither "the registry gate" (smuggled) nor
  a deleted residual paragraph (dropped).
- P7: wizard-claim truthfulness — no prod script writes
  `infochat.chat.reply-mode` today (grep-verified); the D79 row must not
  claim a wizard recommendation that does not exist. Arm (a) recommended:
  record-as-advice, no wizard sentence; arm (b) requires explicit user
  approval plus a follow-up wizard ticket. Decided at the §12 wording review.
- P8: doc-gate traps — reword the /reply-mode entry BODY only (index :236,
  CATALOGUE, tier posture survive; §8's command-entry rule; closed-list
  parity parses commands.md); keep `infochat.chat.reply-mode` named and
  documented in the D79 row (DocumentedConfigKeyParityTest); rule-text only
  (§12), register history confined to the motivation column.

## Approach

- **Files to touch:** `files_scope` — three spec files only.
- **Steps, in order:**
  1. Read the analysis document's Ground truth and P6/P7; read D78
     (decisions.md:97) as the ownership-shape precedent.
  2. Draft the D79 row rewrite (acceptance item 1): decisive-switch clause,
     D78-aligned ownership with record-as-advice, the surviving sentences
     byte-identical, motivation carrying the history descriptively.
  3. Draft the two llm.md edits (:319-324 gate clause → decisive rule;
     :329-334 residual re-owned per P6).
  4. Draft the commands.md /reply-mode entry reword (item 4's survival list
     as the drafting checklist).
  5. Resolve the P7 fork into the draft (recommended arm (a)); show the user
     the exact proposed text with a plain-English account of each commitment
     added/removed/changed (§12); only an explicit yes lands it.
- **Controls to preserve (§10):** every commitment named in `out_of_scope`
  item 3 survives byte-identical or stronger — the amendment moves exactly
  the four gate clauses and nothing else (the M1-663 "no commitment
  weakened" discipline; the reviewer's SPEC-TRUTHNESS reads the diff against
  this).
- **Pitfall→mitigation:** P1→the ticket's own ordering + step 5's approval
  gate; P6→step 3's re-owned residual sentence; P7→step 5's named arm;
  P8→step 4's checklist + acceptance item 7's gates.

## Definition of done

The four amendments land as rule-text with user approval recorded: the D79
row states the operator-owned decisive switch (record-as-advice, D78-aligned,
canonicity and query-anchoring sentences intact); llm.md's gate clause and
residual-controls sentence are re-founded; the /reply-mode entry describes
the decisive behavior with every other command property surviving; the P7
fork is resolved truthfully; the diff is confined to the three spec files;
mvn verify is green.

## Verification

- P1 → the merge order (this ticket before M1-885) + the §12 approval record.
- P6 → acceptance item 3's grep — the residual paragraph present, controls
  re-owned.
- P7 → acceptance item 5 — the approval record names the arm; the landed row
  is truthful under it (grep prod/ is the arm-(a) evidence).
- P8 → acceptance items 4 (index intact) and 7 (doc gates green; rule-text
  only).
- acceptance items 1-4 → their named greps.
- failure mode → item 5 IS the failure-mode coverage for the spec surface:
  a row claiming an unbuilt wizard surface, or silently dropping the P6
  residual, fails the §12 wording review this item gates. Item 3's grep
  additionally fails any draft that deletes the residual paragraph.
- acceptance item 7 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: all code/bundles/scripts (M1-885), security.md
(verified gate-free at :2258-2273), any weakening of the enumerated surviving
commitments, the frozen measurement record, and any date/ticket-ID/report
citation in rule text. The diff touches exactly docs/spec/decisions.md,
docs/spec/llm.md, docs/spec/commands.md (`git diff --stat` shows exactly
those — the M1-663 item-4 shape). No test is added, modified, or deleted by
this ticket.

## Census

This ticket removes one multi-site class: **every spec text stating the
registry-gate native resolution** (the bar-clearing registry, the
pair-clearing condition, the uncleared-pair fallback, the stored-but-inactive
status). Re-runnable enumeration:
`grep -rn -i 'bar-clearing registry\|registry clears\|registry gate\|uncleared pair\|pair clears\|stored but inactive' docs/spec/`.
Rows (verified at draft time):

- `docs/spec/decisions.md:98` — the D79 row's native-resolution clause and
  its gate rationale → FIX (acceptance item 1).
- `docs/spec/llm.md:319-324` — the (model, language) clearance condition →
  FIX (acceptance item 2).
- `docs/spec/llm.md:329-334` — "the registry gate" named as the residual's
  controls → FIX (acceptance item 3).
- `docs/spec/commands.md:1173-1190` — the /reply-mode entry's gate clauses
  (clearance condition, stored-either-way, uncleared-pair confirmation,
  stored-but-inactive status) → FIX (acceptance item 4); the index entry
  (:236) carries no gate wording → DISPOSED, survives.
- `docs/spec/security.md:2258-2273` — the D79 canonicity/translator-leg
  bullet is mode-conditional with no gate wording → DISPOSED, no edit
  (`out_of_scope` item 2).
- Bundle help-text and code-comment sites of the same wording → defer:
  M1-885 (its own Census enumerates them; this ticket's grep is spec-only
  by design).
- `docs/measurement/direct-chat-e2e.md` and the M1-845/846/848 ticket files —
  frozen historical records → DISPOSED, no edit.
- Any further grep hit at implementation → disposed by the same rule
  (reword, or cite why it is history).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-886-reply-mode-spec-amendments.md
```
