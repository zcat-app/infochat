---
id: M1-869
title: "Amend the spec for the v2 tag-tree taxonomy"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  Probe (spec-truthness gap, verified 2026-08-16): after M1-865..M1-868
  land, the shipped behavior contradicts the spec text at five anchors,
  each quotable today: (1) docs/spec/schema.md:327-347 'Vocabulary
  lifecycle (v1)' promises append-only and ENUMERATES the entry paths
  ('tags enter via the bootstrap loader's tags[] union (decision D8)
  and /add-source --tags on a fresh insert (decision D14, decision
  D5)') — the v2 seed arrives via migration, growth is node-gated, and
  superseded names retire. (2) docs/spec/schema.md:309-317 'Tag' entity
  describes a flat row (name, display, source_origin) — the table now
  carries tops, leaves, and parent links. (3) docs/spec/llm.md §SPI
  shape :70-72 says the Tagger 'produces a list of zero-or-more
  controlled-vocabulary tags ... validated against the vocabulary' —
  output is now a single resolved LEAF branch plus a deterministic
  post-validation resolver. (4) docs/spec/commands.md §Per-scope tag
  preferences :1090-1120 and §Periodic group digests :2051-2140
  describe name-level follows and leaf-only section arithmetic —
  follows accept tops as read-time subtree wildcards and sections render
  at the followed level. (5) docs/spec/security.md tool table
  (searchPosts row, :328) says the requested tag filter 'applies as-is'
  — tops now expand to their subtree. Wrong behavior stated: the spec
  promises a system the code no longer implements; SPEC-TRUTHNESS fails
  at review for every family ticket until these anchors are amended.
analysis_ref: docs/plan/m1/tick-analysis/tag-tree-taxonomy-v2.md
blocked_by:
  - M1-865
  - M1-866
  - M1-867
  - M1-868
files_scope:
  - docs/spec/schema.md
  - docs/spec/llm.md
  - docs/spec/commands.md
  - docs/spec/security.md
  - docs/spec/decisions.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY CODE — this ticket edits docs/spec/** only; every behavior landed
    in M1-865..M1-868 and any wording-vs-code divergence found here is an
    escalation (refine the code ticket or this wording), never a silent
    code fix.
  - >-
    DATES, TICKET IDS, OR REPORT CITATIONS in the spec prose — rule text
    only (engineering-rules §12); the history and the measured numbers
    live in the analysis document and M1-864's record, never in
    docs/spec/**.
  - >-
    LEGACY-CITATION CLEANUP beyond the sections this family changes —
    e.g. the pre-existing divergence between commands.md:1118-1120 ('ALL
    mode uses the union of the scope's world-source bootstrap_tags') and
    DigestPostCollector's actual ALL-mode behavior (no tag predicate,
    verified :164-181; M1-726 flagged it) is a separate spec-quality
    change with its own approval, never a rider here. Named so it is not
    silently 'fixed' inside the digest amendment.
  - >-
    docs/design/** edits — the design notes were synced by their own
    tickets (M1-865, M1-867); re-editing them here is scope drift.
acceptance:
  - "docs/spec/schema.md §Sources and tags: the Tag entity records the tree shape (disjoint top categories; depth-2 leaves; leaf names globally unique so the top is derivable from the leaf; tops are vocabulary rows that never enter the tagger render); the Vocabulary lifecycle paragraph is rewritten v1->v2 recording: a deploy-time migration seeds the default tree; tags enter ONLY via vocabulary nodes (/add-source --tags and the bootstrap file's tags[] must name existing nodes — unknown names rejected); growth is leaf addition through vocabulary review (the misc-share signal); superseded names retire via migration; the append-only v1 sentence is replaced, not merely appended to — probe: grep -n 'node\\|leaf\\|top' docs/spec/schema.md shows the amended sections; the text carries no dates/ticket-ids/citations (spec: docs/spec/schema.md §Sources and tags — self-amendment; analysis P17; decisions D8/D14 as amended)."
  - "docs/spec/llm.md §SPI shape: the Tagger bullet records the v2 contract — the model proposes zero-or-more LEAF names from the rendered leaf vocabulary; a deterministic post-validation resolver (fixed top-priority order, News last) stores exactly one resolved branch per post in Tier 1; the losing validated proposals are retained as internal Tier-2 data — probe: the amended Tagger bullet; §Failure handling (recap)'s partial-valid / empty-proposal / fallback wording is checked and updated ONLY where the resolver changes what it literally says (analysis P17; spec: docs/spec/llm.md §SPI shape + §Failure handling (recap))."
  - "docs/spec/commands.md §Per-scope tag preferences: /follow-tag and /unfollow-tag accept a top OR a leaf; a followed top is a read-time subtree wildcard — future leaves under it apply without re-follow; §Periodic group digests: digest sections render at the level the user followed (leaf-follow -> leaf sections; top-follow -> ONE aggregated section per followed top), the qualifying threshold and section cap apply at the rendered level, section count tracks the followed-node count — probe: grep -n 'followed\\|subtree' docs/spec/commands.md (analysis P17; decisions D15/D59/D62/D63 as extended)."
  - "docs/spec/commands.md §Source management: /add-source --tags names must be existing vocabulary nodes; unknown names fail with the friendly fuzzy-suggestion error and no partial write — probe: the amended §Source management sentence (analysis P17; decision D14 as amended)."
  - "docs/spec/security.md §Prompt-injection defenses (LLM call sites), the searchPosts tool row: a requested tag may name a top — it expands to that top's subtree leaves for the filter; validation still runs against the controlled vocabulary and the world predicate is unchanged — probe: the amended tool-table Notes cell; the tool-allowlist begin/end markers and the registry-name parity test's subject are untouched (verification.md §Security's byte-for-byte registry check stays green) (analysis P17; decision D59)."
  - "docs/spec/schema.md §Posts and derivatives: post records the internal tag_candidates array (Tier-2-class: retained losing proposals; never user-facing, never digest-counted, never follow-addressable) — probe: the amended Post bullet (decision D5 pattern)."
  - "docs/spec/decisions.md gains the tree-taxonomy decision row (the next free D-NN at implementation): disjoint tops + depth-2 leaves, flat leaf-only render to the model, deterministic Java resolution with the subject-beats-lens priority order and News last, single resolved Tier-1 branch + Tier-2 candidates array, bounded Others with the misc-share growth trigger, zero-LLM migration — probe: the new row exists and cross-references D5/D15/D22/D59/D62 (analysis P17)."
  - "Every edited section is shown to the user with the exact proposed text and a plain-English account of what commitment each edit adds, removes, or changes, and lands only after an explicit yes (engineering-rules §12) — probe: the ticket's review records the approval; the committed diff contains no prose a user has not seen."
  - "mvn verify from repo root is green (doc-only diff must not disturb the parity gates — DocumentedConfigKeyParityTest, CommandCatalogueParityTest, the tool-registry parity test)."
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Doc-only ticket: no test surface to add. The parity gates named in
      acceptance 9 are the mechanical check that the spec edits stayed
      consistent with code.
spec_refs:
  - docs/spec/schema.md §Sources and tags
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/llm.md §SPI shape
  - docs/spec/commands.md §Per-scope tag preferences
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/commands.md §Source management
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
decision_refs:
  - D5
  - D15
  - D22
  - D59
  - D62
  - D63
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

# M1-869: amend the spec for the v2 tag-tree taxonomy

## Context

M1-865..M1-868 implement the v2 tree taxonomy; the spec still promises
the v1 flat system at five anchors (reproduction probe quotes each).
Landing those tickets without these amendments is a SPEC-TRUTHNESS fail
(M1-860 analysis P11's logic, now on the family's own diffs), and the
schema's own v1 lifecycle text explicitly deferred removal to "a v2
candidate" (schema.md:340-343) — this ticket IS that anticipated
amendment. All edits land as ONE user-approvable diff, rule text only;
the exact wording goes to the user before it lands (engineering-rules
§12). Shared context: `analysis_ref:` (analysis doc, Pitfalls P17, the
SPEC-GAP assessment explaining why this is the amendment shape, not a
gap).

## Root cause

Not a code defect — deliberate promise change. The family changes what
the spec says the system does (vocabulary shape and lifecycle, Tagger
output contract, follow/digest semantics, source-tag entry paths, the
searchPosts filter's expansion); the spec is the source of truth and
must record it.

## Pitfalls

- P17: landing v2 without the amendments is a SPEC-TRUTHNESS fail; the
  amendments are promise CHANGES (append-only -> lifecycle-with-
  retirement), so they are shown to the user, not slipped in.
- §12 discipline: rule text only — no dates, no ticket IDs, no report
  citations; history lives in the analysis doc and M1-864's record.
- Drift-cleanup is not a rider: the pre-existing ALL-mode wording
  divergence (commands.md:1118-1120 vs the actual no-predicate ALL
  query) is named in out_of_scope, NOT fixed here.

## Approach

- **Files to touch:** the five spec files (schema, llm, commands,
  security, decisions).
- **Steps, in order:**
  1. Draft each amendment against the LANDED code (read the diffs of
     M1-865..M1-868; every sentence must match shipped behavior — where
     wording and code disagree, escalate, do not bend either).
  2. Compose the user-facing change account: per section, what
     commitment is added / removed / changed, in plain English.
  3. User approval on the exact text (§12).
  4. Land the single diff; run the parity gates.
- **Controls to preserve:** the spec's closed sets stay closed (the
  tool allowlist registry parity, the command catalogue parity, the
  documented-config-key parity — acceptance 9); the D62/D63/D59 promise
  text is EXTENDED, never weakened (leaf-level digests, chat breadth,
  world predicates all read at least as strong as before).
- **Pitfall→mitigation:** P17→this ticket exists and is blocked by all
  four implementation tickets; §12→steps 2-4; drift-cleanup→
  out_of_scope naming.

## Definition of done

Every acceptance item holds: all seven spec anchors amended (schema Tag
entity + lifecycle v2 + Post candidates note; llm Tagger SPI; commands
tag preferences + digests + source management; security searchPosts
row; decisions row), rule text only; the user approved the exact wording
before it landed; `mvn verify` green including the parity gates.

## Verification

- Each anchor → its acceptance probe (greps into the amended sections;
  the reproduction's five quotes each become the amended sentence).
- P17 → the reproduction probe re-run after the family lands: each of
  the five quoted v1 sentences becomes impossible to re-derive from the
  shipped system (the per-anchor greps above), and acceptance 8's
  approval record proves the promise changes were shown to the user,
  not slipped in alongside code.
- §12 → acceptance 8 (approval recorded; the diff contains no unseen
  prose).
- Parity gates → acceptance 9 (`mvn verify` exit 0 — the tool-registry
  byte-parity and catalogue/config parity tests are the mechanical
  spec<->code consistency check for a doc-only diff).
- Failure-mode coverage: the probe itself is the failure mode — feeding
  the five v1 quotes forward post-family; the amended text must make
  each quote impossible to re-derive from the shipped system.

## Out-of-scope

See `out_of_scope:` — no code, no citations in prose, no legacy-cleanup
riders (the ALL-mode wording divergence is named for a separate future
change), no design-note edits. If drafting surfaces a wording-vs-code
conflict in a LANDED sibling, the resolution is an escalation to that
ticket, never a code edit here.

## Census

Not class-scoped: a fixed five-file amendment set enumerated by the
reproduction probe.
