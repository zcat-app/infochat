---
id: M1-866
title: "Seed the tag tree and migrate the flat vocabulary"
status: done
created: 2026-08-16
last_updated: 2026-08-17
flow: tick
reproduction: >-
  to-be-written TagTreeMigrationIT.legacyVocabularyIsMappedOntoTreeLeaves
  (child of a 2+ decomposition — the seed's leaf list exists only after
  M1-864 freezes it and the tree columns only after M1-865; `start`
  converts the marker per workflow §0). Intended wrong behavior it
  states: after a full Flyway migrate to head on a database seeded with
  the CURRENT flat vocabulary MINUS the platform/medium names whose
  disposal the user ruled loud at start (nostr, video — see acceptance
  1's lookup and the clarity_check record: ai, development, claude,
  security, java, glmai, kimiai, crypto, zcash, quarkus, research, news,
  google, openai, anthropic, qwen, spring-io, langchain4j, oracle,
  malware, privacy — the mapped remainder of the 23-name operator
  profile), the tag table
  carries no tree rows, no top assignments, and no mapping: post.tags
  arrays, source.bootstrap_tags arrays, and scope_tag rows still hold
  flat names with no branch, so the M1-865 resolver runs in identity
  mode forever and no consumer can go tree-aware (verified ground state:
  grep '^INSERT INTO' over infochat-core/src/main/resources/db/migration
  returns no tag seed; V82 lands columns only, M1-865 acceptance 8).
analysis_ref: docs/plan/m1/tick-analysis/tag-tree-taxonomy-v2.md
blocked_by:
  - M1-864
  - M1-865
  - M1-868
files_scope:
  - infochat-core/src/main/resources/db/migration/V84__tag_tree_seed_and_migration.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TagVocabulary.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapLoaderIT.java
  - infochat-collector/src/test/resources/bootstrap/bootstrap-sources-fixture.json
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/*.properties
   - infochat-provider/src/test/java/app/zcat/infochat/provider/source/SourceUpsertServiceIT.java
   - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
   - infochat-provider/src/test/java/app/zcat/infochat/provider/command/StubUserDataSource.java
   - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceIT.java
   - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceAdapterScopeIT.java
   - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceNostrProbeIT.java
   - infochat-provider/src/test/resources/inbound-reflection-error-baseline.txt
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TagTreeMigrationIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerSweepIT.java
   - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TagTreeResolutionTest.java
   - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TagVocabularyRefreshTest.java
   - prod/config/bootstrap-sources.json
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
    the mapping table in this ticket is total over the operator
    profile's flat names: 21 of the 23 current names + comfyui map to
    exactly one node; nostr and video are NAMED exceptions (platform/
    medium names, user ruling at start — the twitter/reddit shape) that
    fail the migration loudly wherever they occur instead of mapping;
    any OTHER unmapped-name leftover must fail the migration loudly,
    not silently persist.
  - >-
    EDITING pre-existing tests beyond the AUTHORIZED modifications
    named in test_plan.modifies (eleven surfaces — the seed and the
    growth gates are the designed input change, and the calibration is
    the family's P19 discipline; engineering-rules §8 authorization
    per surface, stated in plain language there) — any other test edit
    is a violation.
acceptance:
  - "TagTreeMigrationIT.legacyVocabularyIsMappedOntoTreeLeaves (the converted reproduction) passes: after migrate on a DB seeded with the flat vocabulary, every M1-864 frozen-list leaf is present in tag with node_kind='leaf', its recorded parent top present with node_kind='top', source_origin='bootstrap', display=name, PLUS the seven per-top residual leaves added by product ruling at start (other-sports under sport, other-health under health, other-fashion under fashion, other-culture under culture, other-science under science, other-tech under tech, other-business under business — each fallback-marked so a specific leaf outranks its top's residual at equal depth: content that fits a top but has no specific leaf is never excluded from the vocabulary), and every pre-existing flat name resolves through the deterministic lookup (claude/openai/anthropic/qwen/google→tech/ai; crypto/zcash→business/crypto; malware/privacy/security→tech/cybersecurity; quarkus/java/spring-io/langchain4j/oracle/development→tech/software-development; research→science/research; news→news/world; glmai/kimiai→others/misc; ai→ai; comfyui→tech/software-development) — nostr and video are deliberately UNMAPPED (platform/medium names — the twitter/reddit shape: those bootstrap entries carry no platform tag; user ruling at start, recorded in clarity_check): any occurrence in tag/post.tags/source.bootstrap_tags/scope_tag fails the migration loudly, pinned by a failure-mode case — the seed cites the record's list verbatim so a drift between migration and record fails here (spec: docs/spec/schema.md §Sources and tags; analysis P9, P20)."
  - "Stored form and collision safety (the M1-861 salvage): every seeded name asserts TagNormalizer.normalize(name).equals(name); the seed uses INSERT INTO tag (name, display, source_origin) ... 'bootstrap' ON CONFLICT (name) DO NOTHING; a test pre-inserts a hostile 'user'-origin row with a colliding leaf name, executes the migration's own INSERT read from the classpath db/migration resource, and asserts the operator's row survives verbatim (spec: docs/spec/commands.md §Surface conventions; docs/spec/schema.md §Sources and tags)."
  - "post.tags arrays are rewritten deterministically and zero-LLM: each element maps via the same lookup (entity names like claude additionally land in that post's tag_candidates array — M1-868's column — preserving search continuity per decision 6); a test seeds posts carrying the flat names, runs the migration, and asserts every post.tags element is a tree leaf and every mapped-away entity name appears in tag_candidates (analysis P10; spec: docs/spec/schema.md §Posts and derivatives as amended by M1-869)."
  - "source.bootstrap_tags arrays are rewritten via the same lookup — pinned by a test that then drives the tagger's three-surface FALLBACK path (TaggerWorker.java:466 writes bootstrap_tags into post.tags unvalidated) and asserts the stored fallback tags are leaves; this is what keeps the fallback and the /unfollow-tag seed (UnfollowTagCommandHandler.java:97-110 joins bootstrap_tags names against tag.name) functional post-migration (analysis P10)."
  - "scope_tag rows remap without orphans: rows pointing at superseded v1 tag rows are re-pointed at the mapped node's row (INSERT new + DELETE old, ON CONFLICT DO NOTHING), a followed 'ai' row keeps resolving, and after the migration no scope_tag.tag_id references a retired row — pinned by a test asserting zero orphaned FKs and a surviving follow; operator-owned rows are never deleted, only re-targeted (analysis P12)."
  - "V84 (next free number at start; M1-848 landed V81, M1-865 landed V82, M1-868 landed V83) header states the sweep interaction as current-truth behavior: the vocabulary change bumps the M1-736 sweep generation, re-tagging previously tags='{}' posts within the existing caps (batch-size, max-attempts) — bounded, one-time, expected; mapped non-empty historical tags are NOT re-tagged (eligibility is tags='{}' only) — probe: grep -n 'sweep' V84__*.sql (analysis P13; spec: docs/spec/llm.md §Failure handling (recap)). V84 also carries the fallback designation data the M1-876 resolver reads: ALTER TABLE tag ADD COLUMN fallback BOOLEAN NOT NULL DEFAULT false (no GRANT change — the column rides the tag table's existing per-role grants); the seed marks world fallback = true AND the seven per-top residual leaves (other-*) fallback = true — the product ruling at start generalizes the M1-876 tiebreak from within-News to within-any-top: a specific leaf outranks its top's residual at equal depth, the residual stores only when it is the only proposed leaf of that top (this OVERRIDES the M1-878 amendment's 'world and NO other leaf' wording; M1-869's Tagger-SPI sub-order wording must record the generalization); NO other leaf is fallback-marked; the TagVocabulary SELECT reads the column into the TagNode snapshot — probe: grep -n 'fallback' over this ticket shows the column, the world+residual marking (the only fallback-marked leaves), and the SELECT sentence (spec: docs/spec/schema.md §Sources and tags; analysis news-world-below-regions.md P2, P4, P11)."
  - "The /add-source growth gate: SourceUpsertService.upsertTagVocab (SourceUpsertService.java:108-111) unions ONLY names that already exist as tree nodes (top or leaf); an unknown name rejects the command with the existing friendly fuzzy-suggestion shape with NO partial write — SourceUpsertServiceIT.addNodeGateRejectsUnknownTagNameWithNoPartialWrite (failure mode: /add-source with --tags kimiai2, an unconstrained coinage from the measured 0.52-Jaccard failure class, throws UnknownTagsException carrying the name with no tag row, no source row, and no subscription written) and AddSourceCommandHandlerTest pins the friendly reply (bundle error + fuzzy-suggestion footer, never echoing the supplied name — M1-656) (analysis P11; spec: docs/spec/commands.md §Source management as amended by M1-869, decision D14 as amended)."
  - "The bootstrap-loader growth gate: BootstrapLoader fails fast at startup on a bootstrap-sources.json tags[] name that is not an existing tree node, with a message naming the offending name (the M1-077 fail-fast shape extended from character-class to node-membership) — pinned by a BootstrapLoaderIT case (analysis P11; spec: docs/spec/deployment.md §Bootstrap behavior on startup)."
  - "No consumer hardcodes vocabulary members: grep for each seeded leaf name over **/src/main/java returns zero matches — the digest/search/sweep surfaces stay name-agnostic (M1-860 analysis P10 posture, re-asserted for the v2 list). Scoping, stated explicitly (news-world-below-regions.md P2, the Ground-truth discrepancy): the name-agnostic contract binds the CONSUMER surfaces (digest/search/sweep) only — the tagger MECHANISM surface (TagTreeResolver/TagVocabulary/MiscShareMonitor — TOP_PRIORITY, MISC_LEAF, the fallback component) is tree-aware by design and outside the probe; the probe as originally worded ALREADY matches M1-865's shipped MiscShareMonitor.MISC_LEAF ('misc' is a seeded leaf) — this amendment names that reconciliation rather than silently widening the probe."
  - "TagVocabularyRefreshTest passes with its mirror query gaining the leaf filter (WHERE node_kind = 'leaf') — the seed makes the top rows visible for the first time and names() is leaf-only (its runtime tag name is unique and seed-safe); the other pre-existing suites pass WITH their authorized recalibrations: TaggerWorkerTest/TaggerWorkerIT/TaggerWorkerSweepIT move their v1 fixture names (news/security/finance) to unique parentless names so every assertion keeps its pre-seed identity-mode meaning; TagTreeResolutionTest.parentlessVocabularyResolvesToItself feeds inserted parentless rows instead of the whole seeded vocabulary (the identity-passthrough control stays pinned); BootstrapLoaderIT's boot fixture + @Order(1) tag-union assertions move to tree names (the gate makes v1 names fail-fast); SourceUpsertServiceIT's two named cases move their fixtures to tree-node names (the gate changes what a fresh upsert may union); AddSourceIT/AddSourceAdapterScopeIT/AddSourceNostrProbeIT move their /add-source --tags fixtures to seeded tree leaves (the gate makes arbitrary names invalid input) — all named in test_plan.modifies with the §8 plain-language authorization (analysis P13, P19). The core schema tests (PostgresSchemaTestBase subclasses) are seed-safe by construction: their per-test truncateAll wipes the seeded rows before every case."
  - "prod/config/bootstrap-sources.json (deployment input, not read by mvn verify) is rewritten to tree names with the Video and Nostr platform/medium tags REMOVED per the user ruling — the node gate would fail the next Collector startup on the file's retired v1 names otherwise (acceptance 8)."
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
    - >-
      AddSourceCommandHandlerTest — new gate-reply case: the stubbed
      service throws UnknownTagsException(List.of("kimiai2")); the
      handler's reply carries the friendly unknown-tag error with the
      fuzzy-suggestion footer and NEVER echoes the supplied name
      (M1-656) (failure mode acceptance 7).
  modifies:
    - >-
      SourceUpsertServiceIT (tagVocabUpsertIssuesOneStatementForManyTags,
      branchAFreshInsertWritesSourceTagsSubscriptionInOneTransaction) —
      fixtures move from arbitrary names to tree-node names because the
      node gate makes arbitrary names invalid input; authorized by this
      ticket's acceptance 7 (engineering-rules §8 authorization).
    - >-
      TaggerWorkerTest / TaggerWorkerIT / TaggerWorkerSweepIT —
      §8-authorized fixture recalibration: the v1 fixture names seeded
      via seedVocabularyTag (news, security, finance) move to unique
      parentless names (e.g. tagger-fixture-news), because the seed
      owns 'news' as a TOP (not a leaf, so proposals stop validating)
      and retires 'security'; the stubbed proposals and the stored-set
      assertions are renamed identically so every test keeps its
      pre-seed identity-mode meaning (the seed's blast radius on these
      suites, verified at start; the sweep suite's fingerprint
      mechanics are untouched).
    - >-
      TagTreeResolutionTest.parentlessVocabularyResolvesToItself —
      §8-authorized rework: the test fed the whole loaded vocabulary
      expecting parentless identity passthrough; post-seed the
      vocabulary is the 53 parented leaves and resolves to one winner.
      It now inserts a handful of unique parentless rows and feeds
      THOSE, keeping the identity-passthrough control pinned
      (engineering-rules §10 — the control travels).
    - >-
      AddSourceIT / AddSourceAdapterScopeIT / AddSourceNostrProbeIT —
      §8-authorized fixture recalibration (surfaced by the full verify
      — the gate's blast radius on the /add-source IT family was
      missed at start, appended to the authorization): their
      /add-source --tags values move from free-form prefixed coinages
      to seeded tree leaves (ai / software-development) because the
      node gate makes arbitrary names invalid input; AddSourceIT's
      tag-union assertion rewords to the gate contract (supplied names
      must be existing tree nodes) and its per-test tag-row cleanup is
      removed (the names are seeded rows, never to be deleted).
    - >-
      TagVocabularyRefreshTest.namesIteratesInQueryOrderAfterInitial
      LoadAndAfterRefresh — §8-authorized: the test's own mirror
      SELECT gains WHERE node_kind = 'leaf' because the seed makes the
      top rows visible for the first time and names() is leaf-only
      (pre-seed no tops existed in the test DBs, so the mismatch never
      showed).
    - >-
      BootstrapLoaderIT + bootstrap-sources-fixture.json — §8-authorized
      (user ruling at start): the boot fixture's v1 tags
      (AI/Development/Java/Nostr/Security) move to tree names
      (ai/software-development/cybersecurity) and the @Order(1)
      tag-union assertions follow; the gate makes the old fixture
      fail-fast at startup.
  preserves:
    - all tests currently green on main
    - >-
      TagCandidatesCaptureTest, MiscShareMonitorTest — unmodified
      (unique names / same-shaped tree fixtures with snapshot-restore).
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
reviews:
  - round: 1
    date: 2026-08-17
    verdict: REWORK
    checks: "SPEC-TRUTHNESS-CHECK: FAIL; SECURITY-CHECK: PASS; TEST-ADEQUACY-CHECK: FAIL; MAINTAINABILITY-CHECK: WARN; SCOPE-CHECK: PASS"
    diff_stats: "29 files, +1381/-157 (V84 migration, TagTreeMigrationIT, gates in SourceUpsertService/BootstrapLoader, authorized test recalibrations, bundles, prod bootstrap-sources.json); 3 findings (1 medium, 2 low), 0 critical/high — medium: flat-vocabulary identity rows ai/crypto/research survive the seed's ON CONFLICT DO NOTHING as parentless leaves (V84:66-120), losing cross-top ranking; migration IT cannot see it (boot seed masks the collision)"
  - round: 2
    date: 2026-08-17
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS-CHECK: PASS; SECURITY-CHECK: PASS; TEST-ADEQUACY-CHECK: PASS; MAINTAINABILITY-CHECK: PASS; SCOPE-CHECK: PASS"
    diff_stats: "fix hunks 6 files, +93/-11 (V84 re-parenting UPDATE +5, identityLeafCollisionIsReparentedToItsTop +69, two javadoc rewords, ticket/STATUS bookkeeping); 0 findings, 0 critical/high; all three round-1 items SATISFIED (item 2 via scoped fallback probe per driver note); log of record tick-test-M1-866-r5.log (BUILD SUCCESS, TagTreeMigrationIT 7/7)"
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  result: "no blocking ambiguity after four user rulings at start
    (2026-08-17). (1) Mapping table completion: acceptance 1's lookup
    omits development/security/nostr and the record's glossary never
    rules on video — user ruled development→tech/software-development,
    security→tech/cybersecurity, and nostr/video UNMAPPED (platform/
    medium names, the twitter/reddit shape: those entries carry no
    platform tag) — any occurrence fails V84 loudly; prod/config/
    bootstrap-sources.json rewritten to tree names with Video/Nostr
    removed. (2) BootstrapLoaderIT boot fixture carries v1 names the
    node gate rejects — user authorized the fixture+@Order(1)
    recalibration. (3) The seed's full blast radius on pre-existing
    tests (TaggerWorkerTest/IT/SweepIT v1 fixture names, TagTree
    ResolutionTest.parentlessVocabularyResolvesToItself,
    TagTableTest's literal 'news') — user authorized the §8
    recalibration named in test_plan.modifies. (4) Per-top residual
    leaves — the frozen list has no dump leaf per top (volleyball fits
    sport but no leaf existed), so user ruled: add other-sports and
    the same residual under every top lacking one
    (other-health/other-fashion/other-culture/other-science/other-tech/
    other-business; news already has world, others has misc), each
    fallback-marked (specific leaf outranks the residual; the residual
    stores only when it is the top's only proposal) — the M1-876
    tiebreak generalized from within-News to within-any-top; the
    M1-869 sub-order wording and M1-877's re-measure must reflect it.
    The prod-DB cutover (cleanup of loud leftovers + migration +
    verification) is deferred to a separate operator-runbook ticket
    blocked_by this one."
  date: 2026-08-17
  verdict: PASS
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
mode and no consumer can go tree-aware. The mapping is total over the
operator profile's flat names (21 of the 23 current names + comfyui
each map to exactly one node; nostr and video are named unmapped-loud
exceptions per the start ruling); entity names additionally land in
M1-868's tag_candidates column — why this ticket is blocked_by
M1-868.

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

- **Files to touch:** V84 migration (seed + array rewrites + scope_tag
  remap + retirement), `BootstrapLoader` (node-membership fail-fast),
  `SourceUpsertService` (node-gated union + friendly rejection), the
  three test files.
- **Steps, in order:**
  1. Convert the reproduction marker: write
     `legacyVocabularyIsMappedOntoTreeLeaves`, run RED (no seed exists).
  2. Author V84: header (purpose + the sweep statement, P13); seed tops
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
  5. Probes: grep sweep in V84; grep seeded names over **/src/main/java
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
modifications. The `video` mapping is UNMAPPED by user ruling at
start: video is a MEDIUM (a YouTube source is not a movie), like nostr
is a platform — the twitter/reddit entries carry no platform tag, and
the migration fails loudly on either name wherever it occurs instead
of inventing a category; the operator removes both from
bootstrap-sources.json (done in this ticket's diff — the file would
otherwise fail the loader's node gate). The prod-DB cleanup of any
stored nostr/video rows and array elements is a separate
operator-runbook ticket blocked_by this one.

## Census

Not class-scoped: one migration, two growth gates. (The read-site census
is M1-867's; the no-hardcoded-names probe here re-asserts the property
for the v2 list.)

## Round 1 rework

REWORK ITEMS:

1. Finding 1: add the identity-row re-parenting UPDATE to V84 after the leaves seed and add TagTreeMigrationIT.identityLeafCollisionIsReparentedToItsTop (delete seeded 'ai', insert flat row, runMigration, assert parent 'tech'), evaluated via that test's assertion plus the reproduction's existing assertTagRow("ai","leaf","tech",…) holding against a migration that follows the flat seed.
2. Finding 2: reword the TagNode javadoc at TagVocabulary.java:91 to name world and the seven per-top residuals as the fallback-marked leaves, evaluated via `grep -rn "only fallback-marked" infochat-collector/src/main/java/` returning nothing and `grep -c ', TRUE)' V84__tag_tree_seed_and_migration.sql` returning 8.
3. Finding 3: rewrite the stale unconditional-union paragraph in SourceUpsertService.java:78-85 as current truth (gate-validated no-op union, V84 seed as the only vocabulary entry path), evaluated via `grep -rn "SELECT-then-conditional-INSERT race" infochat-provider/src/main/java/` returning nothing.
