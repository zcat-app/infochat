---
id: M1-861
title: "Seed validated news categories into the tag vocabulary"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  to-be-written DefaultTagVocabularySeedTest.seededCategoriesArePresentAfterMigrate
  (child of a 2+ decomposition — the winning category list exists only
  after M1-860 lands, so the test cannot be run RED at filing time; `start`
  converts the marker per workflow §0). Intended wrong behavior it states:
  after a full Flyway migrate to head, `SELECT name FROM tag WHERE
  source_origin = 'bootstrap'` contains ONLY the bootstrap-file union
  (ai/development/claude/security/java/video/nostr class) — no fashion,
  politics, sport, or any mid-band news category — so a deployment that
  follows such sources ships a tagger whose validated output vocabulary
  cannot classify them (verified ground state: grep '^INSERT INTO' over
  infochat-core/src/main/resources/db/migration/*.sql returns 4 hits, none
  on tag; prod/config/bootstrap-sources.json read in full carries only the
  operator profile).
analysis_ref: docs/plan/m1/tick-analysis/tag-vocabulary-coverage.md
blocked_by:
  - M1-860
files_scope:
  - infochat-core/src/main/resources/db/migration/V81__default_tag_vocabulary.sql
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/DefaultTagVocabularySeedTest.java
  - docs/spec/schema.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - >-
    ANY change to TagVocabulary.java, TaggerWorker.java, the tagger prompts
    (tagger.md / tagger-fallback.md), BootstrapLoader.java,
    DigestPostCollector.java, DigestCategorizer, SearchPostsTool, or any
    provider surface — every consumer is vocabulary-content-agnostic
    (verified: DigestPostCollector's single tag predicate is the scope_tag
    subquery at :201-204; SearchPostsTool.validateTagsKnown reads the live
    tag table at :103-122; no Java main-source hardcodes a tag name). D62's
    threshold-3 + section-cap-8 already bound vocabulary growth (M1-721).
  - >-
    BACKFILL of already-tagged posts. Forward-only is the posture: mis-
    tagged research/ai posts are not sweep-eligible (eligibility is
    tags='{}' only, TaggerWorker.enumerateSweepCandidates :753-766). The
    designed exception — the one-time bounded M1-736 sweep of tags='{}'
    posts after the fingerprint bump — is stated behavior, not work.
  - >-
    REMOVING or renaming any current tag — append-only is the v1 spec
    commitment (schema.md §Sources and tags, Vocabulary lifecycle); the
    2026-08-16 user ruling preserves the current categories.
  - >-
    EDITING pre-existing tests (TagVocabularyRefreshTest,
    TaggerWorkerIT, TaggerWorkerSweepIT, TagTableTest,
    BootstrapLoaderIT) — verified seed-safe as-is: they assert relative
    facts (specific runtime tags, iteration order == table order,
    explicitly seeded fixtures), never absolute vocabulary size or
    contents. test_plan.modifies is empty; any edit is an unauthorized
    engineering-rules §8 change.
acceptance:
  - "DefaultTagVocabularySeedTest.seededCategoriesArePresentAfterMigrate (the converted reproduction) passes: after app boot on the Flyway-migrated schema, every category in M1-860's recorded winning list is present in `tag` with source_origin='bootstrap' and display=name — probe: the test asserts the list verbatim against the record, so a drift between migration content and the record fails here (spec: docs/spec/schema.md §Sources and tags; analysis P7, P8)."
  - "Every seeded name is in stored form: the test asserts TagNormalizer.normalize(name).equals(name) per name (NFC + Locale.ROOT lower-case + ^[a-z0-9][a-z0-9-]{0,47}$) — a non-normalized name in the migration is caught at test time, not at deploy-time CHECK failure (spec: docs/spec/commands.md §Surface conventions; analysis P7)."
  - "The seed is append-only and collision-safe: V81 uses INSERT INTO tag (name, display, source_origin) ... 'bootstrap' ON CONFLICT (name) DO NOTHING, and a test feeds the hostile precondition — a pre-existing 'user'-origin row with a colliding name — then executes the migration's own INSERT statement (read from the classpath db/migration resource, so the test cannot drift from the SQL) and asserts the operator's row survives verbatim (source_origin, display, no duplicate) (spec: docs/spec/schema.md §Sources and tags — append-only lifecycle; analysis P8)."
  - "The boundary consumer sees the seed: the test asserts tagVocabulary.contains(<name>) for each seeded category after boot — TagVocabulary is the load path the tagger prompt and validation share (spec: docs/spec/llm.md §SPI shape :70-72 — Tagger output validated against the supplied set; analysis Ground truth)."
  - "Spec amendment rides the diff, rule text only: docs/spec/schema.md §Sources and tags 'Vocabulary lifecycle (v1)' records that a deploy-time migration seeds the default news-category vocabulary alongside the bootstrap-file union — no dates, ticket IDs, or report citations in the prose (engineering-rules §12); the exact wording goes to the user for approval at implementation — probe: the amended paragraph greps for the deploy-time seed sentence; append-only/no-removal semantics unchanged (analysis P11)."
  - "V81's header comment states the sweep interaction as current-truth behavior: on upgraded deployments the vocabulary change bumps the M1-736 sweep generation and re-tags previously tags='{}' posts within the existing caps (batch-size, max-attempts); a fresh database records its baseline at generation 0 and sweeps nothing — probe: grep -n 'sweep' V81__default_tag_vocabulary.sql (analysis P9)."
  - "No consumer hardcodes the new categories: grep for each seeded name over **/src/main/java returns zero matches — the digest/search/sweep surfaces stay name-agnostic (analysis P10; D62 bounds)."
  - "TagVocabularyRefreshTest, TaggerWorkerIT, TaggerWorkerSweepIT, TagTableTest, BootstrapLoaderIT pass UNMODIFIED (seed-safe by construction — relative assertions only; analysis P13)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/DefaultTagVocabularySeedTest.java
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Sources and tags
  - docs/spec/llm.md §SPI shape
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D5
  - D8
  - D14
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

# M1-861: seed validated news categories into the tag vocabulary

## Context

M1-860's record (`docs/measurement/tag-vocabulary.md`) carries the winning
news-category list — the categories the local tagger model applies to domain
content without drifting the current feed. This ticket makes that list the
shipped default: a Flyway V81 migration seeds the categories into `tag`,
so any deployment — including one whose operator follows only fashion or
politics sources — ships a tagger that can classify those feeds, and two
operators adding fashion sources converge on `fashion` instead of inventing
style/apparel/couture. The current categories are preserved untouched
(append-only; user ruling 2026-08-16). Shared context: `analysis_ref:`
(analysis doc, Pitfalls P6–P15, Solution options O1–O4 with rejections).

## Root cause

The vocabulary has no product-level default. Seeding is per-source operator
intent only: the bootstrap file requires `tags ≥1` per SOURCE entry
(deployment.md §Operator inputs — no vocabulary-without-source slot), and
`/add-source --tags` unions caller-chosen names (SourceUpsertService
:108-111, the glmai/kimiai tail's origin). No migration seeds `tag` (grep
`^INSERT INTO` over migrations: V9/V21 provider_state, V11
embedding_metadata, V27 audit_log — none on tag; V81 is the next free
number and carries the catalogue-data precedent of V9/V11/V21).

## Pitfalls

Numbered per the analysis document:

- P6: seeding via `bootstrap-sources.json` (fake sources → real fetch
  traffic + world-visible rows) — rejected; the migration is the mechanism.
- P7: non-normalized names in SQL (TagNormalizer is bypassed; the CHECK
  catches late, at deploy).
- P8: wrong `source_origin`/`display` — 'bootstrap' (the V6 closed set's
  seeded-vocabulary value; 'user' misattributes operator intent), display =
  name (the /add-source growth precedent writes t, t).
- P9: the M1-736 sweep bump — a one-time, bounded re-tag of `tags='{}'`
  posts on upgraded deployments (≤4/tick, ≤3 attempts/post); fresh
  databases baseline at generation 0. Expected spend, stated in the
  migration header — not "unaffected".
- P10: touching digest/search code "for the new categories" — D62
  threshold-3 + section-cap-8 bound growth (M1-721); every consumer is
  name-agnostic; any change is §1 scope drift.
- P11: seeding without the spec amendment is a SPEC-TRUTHNESS fail —
  schema.md's lifecycle paragraph enumerates entry paths; the amendment is
  rides-the-diff rule text (user approves wording), NOT a SPEC-GAP
  (append-only promise unchanged).
- P13: pre-existing tests are seed-safe and stay unmodified.

## Approach

- **Files to touch:** V81 migration (infochat-core), the seed test
  (infochat-collector, `DefaultTagVocabularySeedTest` — a @QuarkusTest on
  the Flyway-migrated schema, the TagVocabularyRefreshTest convention),
  and the schema.md amendment (one sentence-class edit in the Vocabulary
  lifecycle paragraph).
- **Steps, in order:**
  1. Convert the reproduction marker: write
     `seededCategoriesArePresentAfterMigrate` against M1-860's recorded
     list, run it RED (no seed exists).
  2. Author V81: header comment stating purpose + the sweep interaction
     (P9); `INSERT INTO tag (name, display, source_origin) VALUES ...
     'bootstrap'` for the recorded list, `ON CONFLICT (name) DO NOTHING`;
     names copied verbatim from the record (P7/P8). Run the test GREEN.
  3. Add the stored-form assertion (TagNormalizer round-trip per name) and
     the collision test (pre-insert a 'user' row with a colliding name;
     execute the migration's own INSERT read from the classpath
     db/migration resource; assert the operator row survives verbatim).
  4. Add the boundary assertion (`tagVocabulary.contains` per seeded name).
  5. Spec amendment: schema.md §Sources and tags, Vocabulary lifecycle —
     record the deploy-time default seed in rule text only; show the user
     the exact wording before it lands (engineering-rules §12).
  6. Probes: grep each seeded name over **/src/main/java (expect zero);
     confirm no DELETE FROM tag anywhere.
- **Controls to preserve (engineering-rules §10):** no production code
  path is rerouted — the migration adds rows only. Enumerated for the
  paths the change rides ON: TagVocabulary's load/refresh contract
  (TagVocabularyRefreshTest pins order + runtime-add visibility — stays
  green unmodified); BootstrapLoader's `ON CONFLICT (name) DO NOTHING`
  union (what guarantees seed survival across loader re-runs — untouched);
  TaggerWorker's sweep mechanics (TaggerWorkerSweepIT — untouched; the
  fingerprint change is its subject, not its victim); SearchPostsTool's
  live-table validation (untouched — seeded tags are searchable
  immediately); no sanitize/redaction/authz/audit emission moves.
- **Pitfall→mitigation:** P6→mechanism choice (V81 only); P7/P8→steps 2-3;
  P9→step 2 header; P10→step 6 probe + out_of_scope; P11→step 5;
  P13→test_plan.modifies empty.

## Definition of done

Every acceptance item holds: the converted reproduction test passes; every
seeded name is in stored form; the collision test proves append-only merge
semantics against a hostile pre-existing row; the boundary consumer
assertion passes; the schema.md amendment is present as rule text and was
approved by the user word-for-word; V81's header states the sweep
interaction; the no-hardcoded-names probe returns zero; the five named
pre-existing test classes pass unmodified; `mvn verify` is green.

## Verification

- P6 → the diff contains no bootstrap-sources.json change (out_of_scope).
- P7 → `DefaultTagVocabularySeedTest` stored-form assertions (acceptance 2)
  — feeds names that would fail NFC/regex and asserts the migration's own
  names are immune.
- P8 → acceptance 1 (source_origin + display) and acceptance 3 (collision:
  pre-existing 'user' row survives verbatim — the hostile input is an
  operator who already added 'fashion' their own way).
- P9 → acceptance 6 probe (grep 'sweep' V81) + TaggerWorkerSweepIT green
  unmodified.
- P10 → acceptance 7 probe (grep seeded names over **/src/main/java →
  zero) + the diff's file list.
- P11 → acceptance 5: amended paragraph present, rule text only, SPEC-
  CONFORMANCE reads it at review.
- P13 → acceptance 8: the five classes green with no diff hunks in them.
- Reproduction → acceptance 1 (the converted test, now passing).
- Failure-mode coverage (mandatory): acceptance 3's collision leg (hostile
  precondition: operator-owned colliding row) and acceptance 2's
  stored-form leg (names that would smuggle in uppercase/whitespace/Unicode
  variants are exactly what the CHECK would reject at deploy time).
- acceptance 9 → `mvn verify` exit 0.

## Out-of-scope

See `out_of_scope:` — no consumer-code changes (name-agnostic, verified),
no backfill (forward-only; the tags='{}' sweep subset is M1-736's designed
behavior, not work here), no removals/renames (append-only; user ruling),
no pre-existing-test edits (seed-safe, verified: relative assertions
only). `test_plan.modifies` is empty by design.

## Census

Not class-scoped: one migration seeding one list on one table; the
class-shaped concern (no consumer hardcodes vocabulary members) is covered
by the acceptance-7 grep probe, which this ticket runs and records rather
than enumerates as fix sites.
