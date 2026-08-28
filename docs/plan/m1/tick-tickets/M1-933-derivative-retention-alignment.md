---
id: M1-933
title: "Align derivative retention with post (30d base, 14d pi)"
status: done
created: 2026-08-26
last_updated: 2026-08-28
flow: tick
reproduction: >-
  PartitionRetentionAlignmentIT#previousMonthDerivativePartitionsSurviveAlongsidePost
  (RED 2026-08-28 against main: post_embedding_202002 DROPPED by onTick while
  post_202002 survived — .scratch/tick-red-M1-933-it.log) and
  PartitionRetentionPropertiesTest#baseProfileAlignsDerivativeRetentionWithPost
  (RED: post-embedding parsed 4, expected 30) plus
  #piProfileAlignsDerivativeRetentionWithPost (RED: the %pi derivative keys
  are absent — found 0 declarations). The wrong behavior they state: with the
  Clock pinned to 2020-03-10T00:00:00Z and the shipped config,
  PartitionPruner.onTick() DROPS the February-2020 post_embedding /
  post_entity / post_reference partitions (shipped derivative retention 4 d →
  cutoff 2020-03-06; the February partition ends 2020-03-01, which is before
  the cutoff) while post_202002 SURVIVES (retention 30 d → cutoff 2020-02-09)
  — a post visible under post retention with no semantic surface: the
  lexical-only zombie zone. Probe against the current tree:
  infochat-collector/src/main/resources/application.properties:176-179 declares
  post=30 vs derivatives=4; the %pi block overrides post only (:863); the
  pruner predicate is pure per-table config (PartitionDdl.java:128-149) with
  no hardcoded 4 anywhere in Java (grep 'retention-days' over src/main hits
  only PartitionPruner's five @ConfigProperty names).
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/partition/PartitionRetentionAlignmentIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/partition/PartitionRetentionPropertiesTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/UnresolvedRepostEdgeUniqueIT.java
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/02-schema.md
  - docs/design/07-deployment.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The LinkingJob lookback window (infochat.linking.lookback-days=4,
    application.properties:697) and its doc mentions
    (docs/design/01-architecture.md:261-267 "last 4 days",
    docs/design/05-llm-and-embeddings.md:1061) — a DIFFERENT knob gating the
    linking driving set, not retention. lookback 4 ≤ derivative retention
    (30/14) after this change, so embeddings exist across the whole candidate
    window; the change strictly improves the window's embedding coverage.
    Probe: `git diff` shows no LinkingJob.java / linking.* hunk.
  - >-
    Any recency/window/filter change to SemanticSearchTool or the retrieval
    arms — recency ordering in the fused window was REJECTED by the owner
    (M1-917 out_of_scope, 2026-08-23), the honest not-time-filtered pre-fetch
    header landed in M1-927 (done), and temporal parsing is the RAG campaign's
    topic 5. The widened corpus reach is cross-referenced here, not acted on.
    Probe: SemanticSearchTool.java absent from the diff.
  - >-
    price_snapshot retention (7 d) and the pre-existing drift at
    docs/design/10-asset-commands.md:97 ("30-day retention" for price data vs
    the 7 d value in 02-schema.md §2.10/:1682) — price_snapshot was excluded
    from retention work in M1-906 P8 and self-heals via the asset fetcher;
    its doc drift is a separate doc fix if wanted.
  - >-
    Invariant 6 itself: no row deletes, no finer partition granularity, no
    pruner-side grace (a pruner grace was analysis option D in M1-906 and was
    rejected as a global behavior change for a restore-scoped problem). No
    Java production file is touched: PartitionPruner.java, PartitionDdl.java,
    LinkingJob.java, EmbeddingMetadataStartupGuard.java stay byte-identical.
    Probe: `git diff --name-only` shows no src/main path.
  - >-
    Backfill/re-embed of posts that already lost embeddings under the 4 d
    regime — derivative partitions never regenerate (M1-906 Root cause:
    EmbeddingWorker pickup only selects in-window embedding_done=FALSE rows);
    the zombie window self-heals as those posts age out of post retention.
  - >-
    chat_memory TTL (90/30 per 02-schema.md §2.10), the post retention values
    themselves (30/14 stay), and any new wizard key — the decision is
    profile-scoped defaults, not wizard surface.
  - >-
    Frozen ticket history mentioning the 4 d horizon (M1-034, M1-092, M1-180,
    M1-597, M1-906 ticket text) — frozen premise documents, never edited.
acceptance:
  - "PartitionRetentionAlignmentIT.previousMonthDerivativePartitionsSurviveAlongsidePost (the reproduction, converted and run RED at start) passes — @QuarkusTest, Clock pinned to 2020-03-10T00:00:00Z (QuarkusMock Clock.fixed), SeedDataSource owner seam, the PartitionPrunerClockIT pattern: create post_202002, post_embedding_202002, post_entity_202002, post_reference_202002, drive partitionPruner.onTick(), assert ALL FOUR survive — equal retention under the same per-table predicate (PartitionDdl.java:128-149) must drop all four tables' partitions in the same month. Mutation caught: reverting ANY one derivative key to 4 drops exactly that table's February partition (P1)."
  - "Failure-mode / discriminating (P5): PartitionRetentionAlignmentIT.agedMonthStillDropsOnAllTables passes — the same pinned-clock rig with January-2020 partitions (end 2020-02-01 < cutoff 2020-02-09): onTick() DROPS all four tables' January partitions (retention stays live — the alignment moves the drop boundary, it must not disable aging toward unbounded growth), AND current/future-month partitions (e.g. post_embedding_202606) survive (the never-drop-active/next-month floor guard, PartitionDdl.java:140-142, covers the derivative tables too)."
  - "PartitionRetentionPropertiesTest.baseProfileAlignsDerivativeRetentionWithPost passes (plain JUnit, no container — parses the collector's classpath application.properties, the M1-917 SemanticSearchToolDefaultLimitWiringTest wiring-pin precedent): the four base keys infochat.partitions.retention-days.{post,post-embedding,post-entity,post-reference} each parse to 30 — RED today (derivatives parse 4)."
  - "PartitionRetentionPropertiesTest.piProfileAlignsDerivativeRetentionWithPost passes: the four %pi-prefixed keys each parse to 14 — RED today (only %pi post=14 exists at :863; a missing %pi derivative key fails the parse, so silent 30-on-pi drift is impossible to reintroduce (P4))."
  - "restore.sh stale literals reconciled — probe: `grep -nE 'effective=4|\\(4d\\)' prod/scripts/restore.sh` returns nothing; the shipped-default fallback at :832 reads 30, the comment at :789 and the probe-failure WARN at :864-865 name 30d. Behavior is provably unchanged otherwise: for any probe-found past-ended partition (age_days ≥ 1), required = age+30 ≥ 31 exceeds effective under either literal, so the floor-trigger set and the [age+30, age+31] envelope are identical; only the WARN's prior-value wording changes. RestoreWiringTest.restoredOldDerivativePartitionsRaiseTheRetentionFloor passes WITH the ONE authorized assertion edit: 'back to 4' → 'back to 30' (RestoreWiringTest.java:1130) — the staged config (bringUpAppProps, :1597-1601) carries no derivative keys, so the WARN names the fallback literal; every other assertion in that case (envelope, lapse date, argv order, no-secret, banner) is value-derived and passes unmodified (P2, §8 authorization in Out-of-scope)."
  - "Design docs reconciled (P3): `grep -nE '4 days|4-day|\\b4d\\b' docs/design/02-schema.md` returns no derivative-retention truth — §2.4 heading (:944) no longer says 'TTL 4 days', §2.4.4 (:1059) lists the three derivative tables at '30 days (laptop/vps/remote-llm), 14 days (pi)' aligned with post, §2.10 rows (:1901-1903) match, §2.11 (:1930) drops 'within 4-day partition', and §2.8 (:1708, :1710) derives its re-embed window from the post retention horizon instead of a literal 4 days (P6); `grep -n 'retention-days' docs/design/07-deployment.md` shows the derivative values 30/14 in the profile table (new row after :95), the config mirror block (:284-288) and its comment, and §7.10.1 (:1226) reads '(shipped default 30 days)'."
  - "UnresolvedRepostEdgeUniqueIT passes with a comment-only edit (authorized): the :33-38 comment justifying CREATED_AT=now() no longer claims 'post_reference retention is 4 days … drops month partitions almost as soon as they end' — probe: `git diff` on the file is comment-only, the test body and assertions byte-identical (P7)."
  - "mvn verify from repo root is green (engineering-rules §5), including every pre-existing RestoreWiringTest case except the single authorized literal in acceptance item 5, and PartitionPrunerClockIT / PartitionInsertIT / PartitionCreatorTest unmodified (their 2020 synthetic-month arithmetic is post-retention-based and unaffected)."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/partition/PartitionRetentionAlignmentIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/partition/PartitionRetentionPropertiesTest.java
  preserves:
    - all tests currently green on main
    - >-
      every RestoreWiringTest case except the ONE authorized assertion literal
      in restoredOldDerivativePartitionsRaiseTheRetentionFloor (:1130 'back to
      4' → 'back to 30'); the M1-906 floor semantics (raise-only,
      WARN-and-continue, probe-failure degrade, marker-comment append) are
      value-derived and untouched.
    - PartitionPrunerClockIT, PartitionInsertIT, PartitionCreatorTest unmodified
spec_refs:
  - docs/spec/schema.md §Invariants
  - docs/spec/commands.md §Asset commands
decision_refs:
  - D33
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
    date: 2026-08-28
    verdict: APPROVE
    checks: SPEC-TRUTHNESS-CHECK PASS, SECURITY-CHECK PASS, TEST-ADEQUACY-CHECK PASS, MAINTAINABILITY-CHECK PASS, SCOPE-CHECK PASS
    diff_stats: 10 files, +248/-40 (properties +15, restore.sh +6/-6, RestoreWiringTest +2/-2, UnresolvedRepost comment +3/-3, 02-schema +22, 07-deployment +12, 2 new tests +133/+54, ticket +26, board regen)
    notes: >-
      0 rework, 0 critical/high; reviewer falsified-and-dropped 5 candidate
      findings (restore-floor trigger-set identity under either literal;
      retention-disabled mutation caught by agedMonthStillDropsOnAllTables;
      pi-drift caught by the missing-%pi-key failure mode; parity-gate
      coverage via the existing <table> exemption row; the rewritten
      V29-bootstrap comment claim permanently true). RED evidence
      .scratch/tick-red-M1-933-it.log; log of record
      target/tick-test-M1-933-r1.log (BUILD SUCCESS 10:30). One in-band
      fix mid-round: 07-deployment profile-table row key template .* →
      <table> after the first r1 verify run tripped
      DocumentedConfigKeyParityTest (second full run green).
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  question: >-
    RestoreWiringTest.java:1086-1088 carries the comment "months older than
    the shipped 4d retention" inside the authorized floor test case; it goes
    stale-truth under this change but the §8 authorization ends "No other
    pre-existing test is modified" and the Census has no row for :1087.
  resolution: >-
    User-directed falsification check (2026-08-28) confirmed a P7-class
    comment-only edit: §11 current-truth applies to live test source (the
    frozen-history carve-out covers docs/plan ticket documents only), and §8
    authorization may ride the commit message. Rewrite to "the shipped
    derivative retention" (names no value); no assertion/body change;
    explicitly authorized in the commit body alongside the :1130 literal.
escalation_reason:
---

# M1-933: Align derivative retention with post (30d base, 14d pi)

## Context

The derivative tables carry retention-days=4 while `post` carries 30
(application.properties:176-179). The "4 days" is a VPS-era relic: at
whole-month partition granularity the real semantics are "current month + 4
days of grace" (the never-drop-active/next-month floor guard keeps the
current/next month; a previous-month partition drops once month-end + 4d <
now), producing a monthly coverage cliff — e.g. on Aug 10 a July 28 post is
visible (`post`=30 keeps July's partition) but has NO embedding (July's
derivative partitions dropped from Aug 5 onward), a lexical-only zombie zone.
Semantically-unsearchable-but-visible posts are an integrity inconsistency,
not a feature. Target hardware is now the Strix Halo box (128 GB) where the
storage delta is trivial.

Storage estimate (recorded per the brief; per-vector math verified):
768-d float32 = 3,072 B/vector (infochat.embeddings.dimension=768,
application.properties:657; HNSW index on every profile, V11__post_embedding.sql:83-84);
at the operator-stated ~800 posts/day (ASSUMPTION — brief-supplied figure),
30 extra days ≈ 800 × 30 × 3.0 KiB ≈ 72 MiB of raw vector bytes, plus HNSW
graph overhead ≈ 150-250 MB total; entity/reference rows are negligible;
query-latency effect indistinguishable at this corpus scale (HNSW "scales to
millions", 05-llm-and-embeddings.md:1054; the pi IVFFlat "≤10K live vectors"
row :1056 is the deferred per-profile design, NOT the shipped v1 index —
05-llm-and-embeddings.md:1047).

This rides the RAG campaign with no eval dependency. The change widens the
semantic pre-fetch's reachable corpus to ~2 months of vectors (see P9 for the
cross-reference to the campaign's temporal topic).

## Root cause

Fully proven — the misalignment is pure config; the drop machinery is
correct and value-free:

- The pruner reads per-table retention from exactly five config keys
  (PartitionPruner.java:57-70, switch at :100-108) and applies one pure
  predicate per table: a previous-month partition is prunable when its END is
  before now − retentionDays (PartitionDdl.java:133, :143-144), guarded so
  the active month and later never drop (:140-142). No Java code hardcodes 4
  (grep 'retention-days' over src/main returns only PartitionPruner's
  @ConfigProperty names; PartitionScan/ReEvaluationJob/NostrStreamSource read
  `retention-days.post` only).
- Cliff arithmetic verified: on 2026-08-05+ (now − 4d > Aug 1) the July
  derivative partitions are prunable while July posts stay visible until
  ~Aug 31 under post=30 — a ~26-day window every month in which visible
  posts have no semantic surface.
- The premise "config + docs only" is FALSIFIED in the narrow sense — three
  non-config sites hardcode or pin the 4 (all enumerated in Census):
  1. prod/scripts/restore.sh:832 — the M1-906 restore-time floor's
     shipped-default fallback `[[ -n "$effective" ]] || effective=4`
     (plus the ":789" comment "(4d)" and the ":864-865" probe-failure WARN
     "(4d shipped)"). After this change the fallback understates the running
     image's shipped default and the WARN's "lower $key back to N" operator
     action names a value that would re-create the D-17-class immediate drop.
  2. RestoreWiringTest.java:1130 — asserts the WARN contains "back to 4"
     (the staged config, :1597-1601, carries no derivative keys, so the case
     exercises the fallback literal). One authorized literal edit required
     (§8 test-modification authorization in Out-of-scope).
  3. UnresolvedRepostEdgeUniqueIT.java:35 — a comment justifying
     CREATED_AT=now() by "post_reference retention is 4 days" (§11
     stale-truth once the value moves; the test's approach stays valid).
- Verified UNAFFECTED (the brief's named suspects, retired with citations):
  - LinkingJob reads only `infochat.linking.{lookback-days,
    semantic-window-hours, semantic-threshold, max-links-per-post}`
    (LinkingJob.java:135-145); its windows are lookback knobs, not retention,
    and its semantic probe filters `pe.fetched_at >= semanticCutoff`
    (:347) so a wider retention does not widen its scan. lookback 4 ≤ 30/14
    after the change, so embedding availability now covers the whole
    candidate window (strictly better).
  - EmbeddingMetadataStartupGuard reads model/dimension/allow-model-change
    only (EmbeddingMetadataStartupGuard.java:86-93) — no retention coupling.
  - M1-906 floor semantics need NO re-derivation beyond the literals: the
    floor is required = age + 30 (restore.sh:829) and fires for any
    past-ended partition (age_days ≥ 1 → required ≥ 31 > effective under
    either 4 or 30), so the trigger set and the [age+30, age+31] envelope are
    identical before and after; a floor is compatible with raising the
    ceiling. An OLD bundle that ships explicit `=4` lines still gets floored
    correctly (read_prop finds 4; the floor raises it) — only the no-key
    fallback literal and the two texts go stale.
  - Collector tests inherit shipped defaults (src/test/resources/application.properties
    sets no retention-days key — verified by grep), and every existing
    pruner test drives synthetic 2020 months with post-retention arithmetic
    (PartitionPrunerClockIT.java:44-46, PartitionInsertIT.java:117-143) or
    pure parameterized selection (PartitionCreatorTest.java:104) — none pins
    the derivative value. Raising retention only keeps MORE partitions
    alive; no test asserts a derivative partition must be gone.
  - SemanticSearchTool's semantic arm has no time filter
    (SemanticSearchTool.java:246-254 — READY + world predicate + distance),
    so partition lifetime is the only corpus bound today; that is the P9
    cross-reference, not a dependency.

Discrepancy note (brief vs tree): the brief's prior-art block said the four
keys live at application.properties:176-179 — post is :176 and the three
derivatives are :177-179 (price-snapshot=7 is :180, untouched). The %pi
post=14 override is at :863 as stated. The brief's §2.4.4 citation
(:1042-1063) and :944/:1708 citations all verified verbatim; the analysis
additionally found derivative-value mentions the brief omitted — 02-schema.md
§2.10 rows :1901-1903 and §2.11 row :1930, 07-deployment.md :281-288 config
mirror and :1226 §7.10.1, restore.sh :789/:832/:865, RestoreWiringTest :1130,
UnresolvedRepostEdgeUniqueIT :35 — all enumerated in Census.

## Pitfalls

- P1: The alignment property has no pin — "no visible post is ever
  semantically unsearchable" is a relation BETWEEN four config values, not a
  value; nothing today fails when one table drifts (the M1-896 lesson: a
  premise about production code must be pinned, not assumed). Equal retention
  under the same per-table predicate and month granularity yields identical
  drop timing per month — that equivalence is the property to test, at the
  pruner behavior level (onTick under a pinned clock), not only as parsed
  values.
- P2: restore.sh stale literals (M1-906 reconciliation) — the fallback
  `effective=4` (:832), the "(4d)" comment (:789) and "(4d shipped)" WARN
  (:864-865) go stale-truth the moment the properties change; the WARN's
  named operator action ("lower back to 4") would advise re-creating the
  immediate-drop defect. Editing RestoreWiringTest's "back to 4" pin without
  §8 authorization is a test-integrity violation — the authorization lives
  in this ticket's Out-of-scope and acceptance item 5.
- P3: Partial doc updates lie atomically — the 4-day truth lives in seven
  design-doc spots (02-schema.md :944, :1059, :1708/:1710, :1901-1903, :1930;
  07-deployment.md :95-region, :284-288, :1226) and 02-schema.md §2.4.4 is
  the canonical table other docs defer to (M1-424 precedent). The
  SPEC-TRUTHNESS review leg reads design docs as spec-tier for config
  values (07-deployment §7.10.1 is M1-906's spec_ref). All must move in ONE
  commit with the properties.
- P4: %pi completeness — base=30 with a %pi override for post only (today's
  shape, :863) would silently leave pi derivatives at 30, misaligned within
  the profile (post 14 vs derivatives 30) and 2× the intended pi corpus. All
  three %pi keys must land together and stay pinnable (the properties test
  fails on a missing key, not only a wrong value).
- P5: Retention must stay provably alive (failure-mode) — a botched
  "alignment" that effectively disables derivative aging (typo'd huge value)
  violates the bounded-growth purpose behind Invariant 6 / D33. The IT's
  aged-month case (January 2020 drops on ALL FOUR tables) plus the
  floor-guard survival assert the change moves the drop BOUNDARY, not the
  dropping.
- P6: §2.8 migration-sketch semantics — the deferred re-embed procedure's
  `fetched_at > now() - interval '4 days'` window and "After 4 days, drop
  the old column" (:1708, :1710) were coherent when embeddings lived 4 days;
  under a 30-day horizon a 4-day re-embed window strands every post 4-30 days
  old with NO embedding once the old column drops — the exact zombie zone
  this ticket kills, recreated by a future migration. Reconcile the sketch to
  derive its window from the post retention horizon (§2.4.4 reference, no
  magic literal).
- P7: Stale test comment (UnresolvedRepostEdgeUniqueIT:33-38) — §11: a stale
  comment keeps asserting a premise the code stopped satisfying. Comment-only
  edit, explicitly authorized; no behavior change (CREATED_AT=now() always
  lands in the floor-guarded active month, valid under any retention).
- P8: LinkingJob-lookback scope creep — 01-architecture.md:261-267 and
  05-llm-and-embeddings.md:1061 say "last 4 days", which LOOKS like the same
  4 but is `infochat.linking.lookback-days` (application.properties:697), a
  different knob. §1 scope drift trap for a well-meaning implementor
  "aligning" it; named out-of-scope with a probe.
- P9: Retrieval-surface interplay (cross-reference only, no action) — the
  semantic arm's corpus grows from ~current-month to ~2 months of vectors;
  recency ordering in the fused window was explicitly REJECTED (M1-917
  out_of_scope, owner-accepted 2026-08-23), the honest not-time-filtered
  pre-fetch header with ready_at landed (M1-927, done), and temporal parsing
  is the RAG campaign's topic 5 — whose salience this change raises (more old
  posts can now surface by similarity). An implementor adding a recency
  filter or window here would reopen a decided question.
- P10: Config-parity mechanics — no key NAME changes and none added (the %pi
  lines are profile-scoped instances of existing names), so
  DocumentedConfigKeyParityTest and the documented-key exemptions row
  (infochat-provider/src/test/resources/documented-config-key-exemptions.txt:58,
  a name-template row) are unaffected; price_snapshot=7 (:180) and the
  §2.10/:1682 7-day truth must NOT be touched (M1-906 P8 precedent). The
  10-asset-commands.md:97 "30-day retention" drift about price data is
  pre-existing and out of scope.

## Approach

Derived from `spec_refs:` — Invariant 6 (schema.md §Invariants, :784-793)
partitions `post`, `post_reference`, `post_embedding`, `price_snapshot` for
TTL-by-drop and commits post to a fixed profile-driven horizon (D33);
commands.md §Asset commands → Retention (:780-785) records that retention
horizons are profile-driven and LIVE IN DESIGN NOTES, and the mechanism is
not row-level DELETE. The spec deliberately pins no derivative value — the
4 is a design-note/config choice, so aligning it is a design-notes + config
change with NO spec amendment (nothing in docs/spec promises derivative <
post; verified by grep — docs/spec carries no derivative 4-day text). The
user's binding decisions (2026-08-26): default/laptop/vps/remote-llm = 30
for all four tables; %pi = 14 for all four; no fight with Invariant 6.

Options considered (the rejected ones are the commit's
"Alternatives considered"):

- **A (chosen): align values** — derivatives = post per profile. Changes
  only the fan-out of one value; spec-supported (above); storage ~150-250 MB
  on the Strix Halo target.
- **B (rejected): keep 4, annotate the cliff** — documents the integrity
  hole instead of closing it; the zombie zone is user-visible behavior
  (lexical-only degradation) every month.
- **C (rejected): pruner-side grace / drop derivatives with post** — a
  global behavior change for what is a config choice; M1-906 already
  rejected the pruner-grace shape (its option D) and this ticket's
  out-of-scope forbids touching Java production files.
- **D (rejected): row deletes / finer granularity** — violates Invariant 6 /
  D33 (user decision: do not fight it).
- **E (rejected): wizard keys** — the user bound profile-scoped defaults;
  a wizard surface would add config surface for no operator choice.

Files to touch (plan; every edit enumerated in Root cause / Census):

1. infochat-collector/src/main/resources/application.properties —
   :177-179 → 30; three %pi lines (=14) after :863 (alphabetical:
   post < post-embedding < post-entity < post-reference); rewrite the
   :170-175 comment block ("post and the three derivative tables are
   profile-driven — 30d laptop/vps/remote-llm, 14d pi; price_snapshot shares
   one value across profiles").
2. prod/scripts/restore.sh — :789 comment, :832 fallback → 30, :864-865
   WARN text (P2).
3. RestoreWiringTest.java — the ONE authorized literal at :1130
   ("back to 4" → "back to 30") (P2, §8).
4. UnresolvedRepostEdgeUniqueIT.java — the :33-38 comment sentence only (P7).
5. docs/design/02-schema.md — §2.4 heading :944 (drop "TTL 4 days");
   §2.4.4 :1059 (30/14 aligned with post); §2.8 :1708/:1710 (window derived
   from the post retention horizon, P6); §2.10 :1901-1903 (30/14 rows);
   §2.11 :1930 (drop "within 4-day partition") (P3).
6. docs/design/07-deployment.md — new derivative row after :95 in the
   profile table (30/30/14/30); :281-288 config mirror + comment; §7.10.1
   :1226 "(shipped default 30 days)" (P3).
7. New tests (below).

Steps in order:

1. Write both test classes and run them RED against the current tree
   (workflow §0): the IT drops the February derivative partitions while
   post_202002 survives; the properties test parses 4 (base) and fails on
   the missing %pi derivative keys.
2. The properties change (config is the root cause; tests now have a
   mutation to catch) — base values, %pi keys, comment block (P1, P4).
3. restore.sh + RestoreWiringTest literal (P2) — same commit; the restore
   WARN test is the only pre-existing pin on the 4.
4. The doc reconciliation (P3, P6) — same commit; §2.4.4 is canonical.
5. The comment-only edit (P7).
6. `mvn verify` from repo root.

Controls to preserve (engineering-rules §10 — this change RE-PARAMETERIZES
the retention path; its incidental obligations must survive):

- The M1-906 floor semantics: raise-only comparison, WARN-and-continue,
  probe-failure degrade, marker-comment append under §7.10.1, config write
  before the Collector start — all value-derived and pinned by the four
  RestoreWiringTest cases; only the prior-value literal changes.
- The pruner's never-drop-active/next-month floor guard
  (PartitionDdl.java:140-142) and its pure-selection pin
  (PartitionCreatorTest:104) — untouched, and newly asserted for derivative
  tables by the IT's survival assertions.
- Invariant 6 mechanics: owner-datasource DROP (PartitionPruner.java:45-47),
  no row deletes introduced, partition granularity unchanged.
- DocumentedConfigKeyParityTest's key-name set (no names added/removed) and
  the exemptions row (:58) (P10).
- price_snapshot=7 everywhere it appears (P10).

Pitfall→mitigation: P1→step 1 IT + step 2; P2→step 3 + acceptance item 5;
P3→step 4 (one commit); P4→step 2 %pi keys + properties test; P5→IT
aged-month + floor-guard assertions; P6→step 4 §2.8 edit; P7→step 5;
P8/P9→out_of_scope probes (no LinkingJob/SemanticSearchTool hunks); P10→
probes (price_snapshot untouched, key names unchanged).

## Definition of done

The reproduction IT passes (all four tables' previous-month partitions
survive together under the pinned clock); the aged-month IT case drops all
four together and the floor guard holds for derivative tables; both
properties-test cases pass (base 30 / pi 14, missing %pi key fails);
restore.sh carries no 4-literal and its floor test passes with the single
authorized assertion edit; the design docs carry no derivative 4-day truth
(greps of acceptance item 6); UnresolvedRepostEdgeUniqueIT's edit is
comment-only; `mvn verify` green from repo root.

## Verification

- P1 → PartitionRetentionAlignmentIT.previousMonthDerivativePartitionsSurviveAlongsidePost
  — pinned clock 2020-03-10, February-2020 partitions on all four tables;
  asserts all four survive onTick. Mutations caught: any derivative key
  reverted to 4 (exactly that table's partition drops); unequal derivative
  values.
- P2 → RestoreWiringTest.restoredOldDerivativePartitionsRaiseTheRetentionFloor
  (authorized literal) + probe `grep -nE 'effective=4|\(4d\)' prod/scripts/restore.sh`
  → empty. A stale fallback fails the "back to 30" assertion; any other
  assertion touched beyond the authorized literal is a §8 violation the
  reviewer fails.
- P3 → probes `grep -nE '4 days|4-day|\b4d\b' docs/design/02-schema.md`
  (no derivative-retention hits) and `grep -n 'retention-days' docs/design/07-deployment.md`
  (30/14 rows + config block + §7.10.1 at 30 days).
- P4 → PartitionRetentionPropertiesTest.piProfileAlignsDerivativeRetentionWithPost
  — parses all four %pi keys, fails on missing OR wrong value.
- P5 → PartitionRetentionAlignmentIT.agedMonthStillDropsOnAllTables — feeds
  January-2020 partitions (end before cutoff under BOTH 4 and 30); asserts
  all four DROP (retention alive) and current/future-month partitions
  survive (floor guard extends to derivative tables).
- P6 → the §2.8 grep leg of acceptance item 6 (:1708/:1710 carry no literal
  4-day window).
- P7 → `git diff` on UnresolvedRepostEdgeUniqueIT.java is comment-only; the
  test passes unmodified.
- P8 → probe: `git diff` shows no LinkingJob.java hunk and
  `grep -n 'lookback-days' infochat-collector/src/main/resources/application.properties`
  still returns 4.
- P9 → probe: SemanticSearchTool.java absent from `git diff --name-only`.
- P10 → probes: `grep -n 'price-snapshot' infochat-collector/src/main/resources/application.properties`
  still parses 7; no new config-key NAME anywhere (`git diff` adds only
  value changes and %pi-scoped instances of existing names).
- acceptance item 8 → `mvn verify` from repo root (engineering-rules §5).

## Out-of-scope

Prose mirror of the YAML list. The LinkingJob lookback window
(infochat.linking.lookback-days=4) and its doc mentions are a different knob
and stay; after this change lookback 4 ≤ derivative retention on every
profile, so the candidate window's embedding coverage only improves. No
retrieval-arm, recency, or window change — recency was rejected in M1-917,
the honest pre-fetch header landed in M1-927, and temporal parsing is the
RAG campaign's topic 5 (this ticket's widening of the reachable corpus is
recorded here as that topic's motivation, nothing more). price_snapshot
retention and its pre-existing 10-asset-commands.md:97 doc drift stay out.
Invariant 6 is untouched — no row deletes, no finer granularity, no
pruner-side grace, and NO Java production file changes (PartitionPruner,
PartitionDdl, LinkingJob, EmbeddingMetadataStartupGuard byte-identical). No
backfill of already-lost embeddings (they never regenerate; the zombie
window self-heals as posts age out). chat_memory TTL and the post values
themselves stay. Frozen ticket history is not edited.

**Authorized pre-existing-test modifications (engineering-rules §8):**
(i) RestoreWiringTest.restoredOldDerivativePartitionsRaiseTheRetentionFloor,
line ~1130: the assertion `r.output.contains("back to 4")` becomes
`"back to 30"` — this ticket changes the shipped default the M1-906 WARN
names as the prior value (the staged config carries no derivative keys, so
the WARN text follows the restore.sh fallback literal), and the test must
pin the new truthful value; every other assertion in the case is
value-derived and must stay untouched. (ii) UnresolvedRepostEdgeUniqueIT
:33-38: one comment sentence rewritten to not name the retention value —
no test-body change. No other pre-existing test is modified.

## Census

Class: **every live (non-frozen) site that names the derivative retention
value 4 (or its semantics).** Re-runnable enumeration:
`grep -rnE 'retention-days\.(post-embedding|post-entity|post-reference)|4 days|4-day|\b4d\b'`
over application.properties, prod/scripts, src (main+test), docs/design,
docs/spec — then dispose:

- infochat-collector/src/main/resources/application.properties:177-179 — FIX
  (values → 30); :170-175 comment — FIX; :863 %pi block — FIX (add three
  14-keys). (:697 lookback-days and :608 post+slack comment: different
  knob/post-based — no action.)
- prod/scripts/restore.sh:789, :832, :864-865 — FIX (P2). No other prod
  script reads the value (grep 'retention-days' prod/scripts → restore.sh
  only; M1-906 verified no wizard script writes these keys).
- RestoreWiringTest.java:1130 — FIX (authorized literal, P2); :1249-1251
  (the 400-override case) — no action (value-agnostic).
- UnresolvedRepostEdgeUniqueIT.java:35 — FIX (comment-only, P7).
- docs/design/02-schema.md:944, :1059, :1708, :1710, :1901-1903, :1930 —
  FIX (P3, P6).
- docs/design/07-deployment.md:95-region, :284-288, :1226 — FIX (P3).
- docs/design/01-architecture.md:261-267, 05-llm-and-embeddings.md:1061 —
  OUT (LinkingJob lookback, P8); 05-llm:1056 — OUT (deferred IVFFlat design
  note, superseded by :1047's shipped-HNSW statement); 10-asset-commands.md:97
  — OUT (price_snapshot drift, pre-existing); 03-commands.md:52 — OUT
  ("equals the post TTL" stays true at 30).
- docs/spec/** — no hits (verified): the spec delegates values to design
  notes; no spec amendment.
- docs/plan/m1/tickets + tick-tickets (M1-034, M1-092, M1-180, M1-597,
  M1-906 texts) — OUT (frozen history).
- docs/measurement/** hits are hashes/version strings, not retention — OUT.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-933`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-933-derivative-retention-alignment.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
