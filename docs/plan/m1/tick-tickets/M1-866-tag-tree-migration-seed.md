---
id: M1-866
title: "Seed the tag tree and migrate the flat vocabulary"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  to-be-written TagTreeMigrationIT.legacyVocabularyIsMappedOntoTreeLeaves
  (child of a 2+ decomposition — the seed's leaf list exists only after
  M1-864 freezes it and the tree columns only after M1-865; `start`
  converts the marker per workflow §0). Intended wrong behavior it
  states: after a full Flyway migrate to head on a database seeded with
  the CURRENT flat vocabulary (ai, development, claude, security, java,
  video, nostr, glmai, kimiai, crypto, zcash, quarkus, research, news,
  google, openai, anthropic, qwen, spring-io, langchain4j, oracle,
  malware, privacy — the 23-name operator profile), the tag table
  carries no tree rows, no top assignments, and no mapping: post.tags
  arrays, source.bootstrap_tags arrays, and scope_tag rows still hold
  flat names with no branch, so the M1-865 resolver runs in identity
  mode forever and no consumer can go tree-aware (verified ground state:
  grep '^INSERT INTO' over infochat-core/src/main/resources/db/migration
  returns no tag seed; V81 lands columns only, M1-865 acceptance 8).
analysis_ref: docs/plan/m1/tick-analysis/tag-tree-taxonomy-v2.md
blocked_by:
  - M1-864
  - M1-865
  - M1-868
files_scope:
  - infochat-core/src/main/resources/db/migration/V83__tag_tree_seed_and_migration.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapLoaderIT.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source/SourceUpsertServiceIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TagTreeMigrationIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - >-
    ANY TREE-AWARE READ semantics — searchPosts, digest, /summary,
    /follow-tag beyond row acceptance: M1-867. This ticket makes the DATA
    tree-shaped and gates the WRITE paths; consumers come after.
  - >-
    BACKFILLING TIER-2 CANDIDATES for posts that never re-tag: the
    migration writes OLD ENTITY-NAME tags into post.tag_candidates for
    SEARCH CONTINUITY (decision 6) — it does NOT synthesize losing-leaf
    candidates (no LLM, no invention). Historical posts keep their
    MAPPED Tier-1 tags until natural re-evaluation (the M1-736 sweep's
    designed tags='{}'-only response; no full historical re-tag).
  - >-
    FROZEN CONTENT — summary_cache bodies, digest replay section slugs
    (DigestSectionRepository), and saved_post snapshots keep old tag
    names; D19/D36/D65 byte-faithful replay forbids rewriting them and
    they age out with retention (analysis P18).
  - >-
    REMOVING OR RENAMING any v1 name outside the deterministic lookup —
    the mapping table in this ticket is exhaustive and total (every one
    of the 23 current names + the M1-864 record's list maps to exactly
    one node); unmapped-name leftovers must fail the migration loudly,
    not silently persist.
  - >-
    EDITING pre-existing tests beyond the two AUTHORIZED modifications
    (SourceUpsertServiceIT's tag-union fixtures and BootstrapLoaderIT's
    new fail-fast case is an ADD) — engineering-rules §8: unauthorized
    test edits are violations.
acceptance:
  - "TagTreeMigrationIT.legacyVocabularyIsMappedOntoTreeLeaves (the converted reproduction) passes: after migrate on a DB seeded with the flat vocabulary, every M1-864 frozen-list leaf is present in tag with node_kind='leaf', its recorded parent top present with node_kind='top', source_origin='bootstrap', display=name, and every pre-existing flat name resolves through the deterministic lookup (claude/openai/anthropic/qwen/google→tech/ai; crypto/zcash→business/crypto; malware/privacy→tech/cybersecurity; quarkus/java/spring-io/langchain4j/oracle→tech/software-development; research→science/research; news→news/world; video→culture/movies unless the M1-864 record's glossary rules otherwise; glmai/kimiai→others/misc; ai→ai; comfyui→tech/software-development) — the seed cites the record's list verbatim so a drift between migration and record fails here (spec: docs/spec/schema.md §Sources and tags; analysis P9, P20)."
  - "Stored form and collision safety (the M1-861 salvage): every seeded name asserts TagNormalizer.normalize(name).equals(name); the seed uses INSERT INTO tag (name, display, source_origin) ... 'bootstrap' ON CONFLICT (name) DO NOTHING; a test pre-inserts a hostile 'user'-origin row with a colliding leaf name, executes the migration's own INSERT read from the classpath db/migration resource, and asserts the operator's row survives verbatim (spec: docs/spec/commands.md §Surface conventions; docs/spec/schema.md §Sources and tags)."
  - "post.tags arrays are rewritten deterministically and zero-LLM: each element maps via the same lookup (entity names like claude additionally land in that post's tag_candidates array — M1-868's column — preserving search continuity per decision 6); a test seeds posts carrying the flat names, runs the migration, and asserts every post.tags element is a tree leaf and every mapped-away entity name appears in tag_candidates (analysis P10; spec: docs/spec/schema.md §Posts and derivatives as amended by M1-869)."
  - "source.bootstrap_tags arrays are rewritten via the same lookup — pinned by a test that then drives the tagger's three-surface FALLBACK path (TaggerWorker.java:466 writes bootstrap_tags into post.tags unvalidated) and asserts the stored fallback tags are leaves; this is what keeps the fallback and the /unfollow-tag seed (UnfollowTagCommandHandler.java:97-110 joins bootstrap_tags names against tag.name) functional post-migration (analysis P10)."
  - "scope_tag rows remap without orphans: rows pointing at superseded v1 tag rows are re-pointed at the mapped node's row (INSERT new + DELETE old, ON CONFLICT DO NOTHING), a followed 'ai' row keeps resolving, and after the migration no scope_tag.tag_id references a retired row — pinned by a test asserting zero orphaned FKs and a surviving follow; operator-owned rows are never deleted, only re-targeted (analysis P12)."
  - "V83 (next free number at start, after M1-865's and M1-868's migrations) header states the sweep interaction as current-truth behavior: the vocabulary change bumps the M1-736 sweep generation, re-tagging previously tags='{}' posts within the existing caps (batch-size, max-attempts) — bounded, one-time, expected; mapped non-empty historical tags are NOT re-tagged (eligibility is tags='{}' only) — probe: grep -n 'sweep' V83__*.sql (analysis P13; spec: docs/spec/llm.md §Failure handling (recap))."
  - "The /add-source growth gate: SourceUpsertService.upsertTagVocab (SourceUpsertService.java:108-111) unions ONLY names that already exist as tree nodes (top or leaf); an unknown name rejects the command with the existing friendly fuzzy-suggestion shape with NO partial write — SourceUpsertServiceIT.addNodeGateRejectsUnknownTagNameWithNoPartialWrite (failure mode: /add-source with --tags kimiai2, an unconstrained coinage from the measured 0.52-Jaccard failure class, returns the friendly error and asserts no tag row, no source row, and no subscription was written) (analysis P11; spec: docs/spec/commands.md §Source management as amended by M1-869, decision D14 as amended)."
  - "The bootstrap-loader growth gate: BootstrapLoader fails fast at startup on a bootstrap-sources.json tags[] name that is not an existing tree node, with a message naming the offending name (the M1-077 fail-fast shape extended from character-class to node-membership) — pinned by a BootstrapLoaderIT case (analysis P11; spec: docs/spec/deployment.md §Bootstrap behavior on startup)."
  - "No consumer hardcodes vocabulary members: grep for each seeded leaf name over **/src/main/java returns zero matches — the digest/search/sweep surfaces stay name-agnostic (M1-860 analysis P10 posture, re-asserted for the v2 list)."
  - "SourceUpsertServiceIT.tagVocabUpsertIssuesOneStatementForManyTags and branchAFreshInsertWritesSourceTagsSubscriptionInOneTransaction pass with fixtures updated to tree-node names (AUTHORIZED in test_plan.modifies — the gate changes what a fresh insert may union); BootstrapLoaderIT's existing cases pass unmodified."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TagTreeMigrationIT.java
    - >-
      BootstrapLoaderIT — new fail-fast case for a non-node bootstrap tag
      name (fixture inlined per the M1-077 rework precedent).
    - >-
      SourceUpsertServiceIT.addNodeGateRejectsUnknownTagNameWithNoPartialWrite
      — new unknown-name rejection case (failure mode acceptance 7).
  modifies:
    - >-
      SourceUpsertServiceIT (tagVocabUpsertIssuesOneStatementForManyTags,
      branchAFreshInsertWritesSourceTagsSubscriptionInOneTransaction) —
      fixtures move from arbitrary names to tree-node names because the
      node gate makes arbitrary names invalid input; authorized by this
      ticket's acceptance 7 (engineering-rules §8 authorization).
  preserves:
    - all tests currently green on main
    - >-
      TagVocabularyRefreshTest, TaggerWorkerIT, TaggerWorkerSweepIT —
      unmodified (the vocabulary swap is the sweep's designed input, not
      its victim).
spec_refs:
  - docs/spec/schema.md §Sources and tags
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/commands.md §Surface conventions
  - docs/spec/deployment.md §Bootstrap behavior on startup
decision_refs:
  - D5
  - D8
  - D14
  - D22
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

# M1-866: seed the tag tree and migrate the flat vocabulary

## Context

M1-865 landed the mechanism (tree columns, leaf-only render, resolver)
and M1-864 froze the leaf list; nothing has put v2 DATA in the database.
This ticket seeds the frozen tree as the product default and migrates
every flat-vocabulary surface onto it with a deterministic, zero-LLM
lookup — the exact mechanics M1-861 designed for the flat seed
(append-only ON CONFLICT seed, TagNormalizer stored-form test, collision
test, schema.md lifecycle amendment wording, sweep-generation statement)
salvaged onto the v2 shape (both tickets' abandonment notes say so).
It also gates the two growth paths that would regrow the vendor tail
into Tier 1 the day after migration: `/add-source --tags`'s
unconditional union (SourceUpsertService.java:108-111) and the
bootstrap-loader `tags[]` union. Shared context: `analysis_ref:`
(analysis doc, Pitfalls P9–P13, P18–P20).

## Root cause

Verified: no migration seeds `tag` (grep over db/migration returns no
tag INSERT); post.tags and source.bootstrap_tags are free TEXT[] arrays
with no FK (V7:159, V6:42), so nothing links their contents to tree
nodes; scope_tag alone FKs tag(id) (V7:64-69). Until the arrays are
rewritten and the rows re-pointed, the M1-865 resolver runs in identity
mode and no consumer can go tree-aware. The mapping is total and
deterministic (the 23 current names each map to exactly one node);
entity names additionally land in M1-868's tag_candidates column — why
this ticket is blocked_by M1-868.

## Pitfalls

- P9: duplicate leaf names across branches break top-derivation —
  UNIQUE(name) enforces it; the seed test pins it.
- P10: unmapped bootstrap/fallback names — the fallback writes
  bootstrap_tags into post.tags UNVALIDATED (TaggerWorker.java:466);
  bootstrap_tags MUST be rewritten by the same lookup as post.tags.
- P11: the vendor tail regrows — both growth paths gate on existing
  nodes; the measured failure class (kimiai 0.52 Jaccard) is the
  rejection test's input.
- P12: scope_tag FK orphans — re-point before retiring superseded rows;
  operator-owned rows survive verbatim.
- P13: the sweep bump is real spend — stated in the migration header;
  TaggerWorkerSweepIT stays green unmodified.
- P18: frozen content is never rewritten (out_of_scope).
- P19/P20: the seed cites M1-864's record verbatim; names are stored-form
  and English; pins in the migration header.

## Approach

- **Files to touch:** V83 migration (seed + array rewrites + scope_tag
  remap + retirement), `BootstrapLoader` (node-membership fail-fast),
  `SourceUpsertService` (node-gated union + friendly rejection), the
  three test files.
- **Steps, in order:**
  1. Convert the reproduction marker: write
     `legacyVocabularyIsMappedOntoTreeLeaves`, run RED (no seed exists).
  2. Author V83: header (purpose + the sweep statement, P13); seed tops
     + leaves from M1-864's frozen list ('bootstrap', display=name, ON
     CONFLICT DO NOTHING — P20); the exhaustive name→node lookup (as
     JOINs over a VALUES table, not procedural code); post.tags :=
     mapped leaves + tag_candidates := mapped-away entity names
     (single UPDATE per array, set-based, zero-LLM — P10);
     source.bootstrap_tags := mapped; scope_tag re-point (INSERT ...
     SELECT ... ON CONFLICT DO NOTHING, then DELETE superseded rows no
     longer referenced — P12).
  3. Stored-form + collision tests (the M1-861 salvage, verbatim shape).
  4. Growth gates: SourceUpsertService node-gated union + rejection
     error; BootstrapLoader fail-fast (P11).
  5. Probes: grep sweep in V83; grep seeded names over **/src/main/java
     (expect zero).
- **Controls to preserve (engineering-rules §10):** the migration rides
  the existing per-role grants (no GRANT change; provider already
  SELECTs tag); SourceUpsertService's single-statement batch upsert
  property (M1-365) survives the gate (the WHERE narrows input, the
  statement count does not grow); BootstrapLoader's fail-fast ordering
  and audit emission are untouched; nothing in the digest/search/sweep
  code changes.
- **Pitfall→mitigation:** P9→seed test; P10→array rewrites + the
  fallback-path test; P11→step 4 gates + failure-mode tests; P12→step 2
  re-point + orphan assertion; P13→header + SweepIT green; P18→
  out_of_scope; P19/P20→record-verbatim seed + stored-form test.

## Definition of done

Every acceptance item holds: the converted reproduction passes (flat
vocabulary fully mapped onto tree leaves); stored-form + collision
safety; post.tags / bootstrap_tags / scope_tag all migrated and
orphan-free; sweep statement present; both growth gates live with
failure-mode tests; no hardcoded names; the two authorized test
modifications land; `mvn verify` green.

## Verification

- P9 → acceptance 1 (the frozen-list seed with parent tops).
- P10 → acceptances 3 and 4 (array rewrites; the driven fallback path
  stores leaves).
- P11 → SourceUpsertServiceIT.addNodeGateRejectsUnknownTagNameWithNoPartialWrite
  (kimiai2 rejection: friendly error, zero writes; acceptance 7) and the
  BootstrapLoaderIT fail-fast case (acceptance 8) — both failure-mode
  items.
- P12 → acceptance 5 (zero orphans; surviving follow; operator rows
  verbatim) — the collision leg is also a failure mode (hostile
  precondition: pre-existing operator row).
- P13 → acceptance 6 probe + TaggerWorkerSweepIT green unmodified.
- P18 → out_of_scope + diff file list (no summary_cache/replay files).
- P19 → acceptance 1's seed-is-verbatim-against-the-record assertion and
  acceptance 10's authorized fixture move: the seed and the rewritten
  SourceUpsertServiceIT fixtures cite M1-864's frozen leaf names (the
  END-state vocabulary) — no fixture pins a v1 name this ticket itself
  retires.
- P20 → acceptance 2 (stored form) + the record-verbatim seed citation.
- Reproduction → acceptance 1.
- acceptance 11 → `mvn verify` exit 0.

## Out-of-scope

See `out_of_scope:` — no consumer semantics (M1-867), no candidate
synthesis for historical posts, no frozen-content rewrites, no mapping
beyond the exhaustive table, and only the two authorized test
modifications. The open `video` mapping (brief: "Culture/?") is CLOSED
by M1-864's glossary before this ticket starts — if the record leaves
it open, escalate rather than invent.

## Census

Not class-scoped: one migration, two growth gates. (The read-site census
is M1-867's; the no-hardcoded-names probe here re-asserts the property
for the v2 list.)
