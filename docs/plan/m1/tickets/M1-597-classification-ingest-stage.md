---
id: M1-597
title: "Collector: real per-post classification ingest stage (ClassifierWorker + post.classification, unknown default)"
status: done
created: 2026-07-08
last_updated: 2026-07-08
blocked_by: []
files_budget: 22
files_scope:
  - infochat-core/src/main/resources/db/migration/V57__post_classification.sql
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/ModelTask.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuardKeyDerivationTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/LlmSpisLoadTest.java
  - infochat-llm-adapter/src/main/resources/prompts/classifier.md
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/classifier/ClassifierWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/classifier/ClassificationResult.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/ready/ReadyPromoter.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/classifier/ClassifierWorkerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/classifier/ClassifierWorkerIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/ready/ReadyPromoterIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/ready/ReadyPromoterClockIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvalVerdictNotifyIT.java
  - docs/design/02-schema.md
  - docs/design/05-llm-and-embeddings.md
  - docs/spec/architecture.md
  - docs/spec/llm.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: true
outline_file: target/m1-tick-outline-M1-597.md
out_of_scope:
  - >-
    The provider render + retrieval surface (EligiblePostQuery,
    ClusterBlockRenderer, the reply bundles, docs/design/03-commands.md). That is
    M1-598 (blocked_by this ticket). This ticket produces and stores the
    classification data ONLY; it does NOT change what /summary emits — the
    renderer keeps showing today's reverted tags-copy stub until M1-598 re-points
    it. Do NOT touch any infochat-provider/ Java, render, or retrieval code. The
    SINGLE permitted provider edit is
    infochat-provider/src/main/resources/application.properties, which gains an
    infochat.llm.classifier.* block (base-url + model, mirroring the six existing
    per-task blocks) SOLELY so the shared @Startup LlmRouterStartupGuard — which
    runs on BOTH services and eagerly resolves every ModelTask's per-task config
    at boot — does not abort provider startup once ModelTask.CLASSIFIER exists.
    The provider never issues a classifier call.
  - >-
    Backfilling real classifications onto the pre-migration post backlog. Existing
    posts get the {unknown} column DEFAULT and are marked classifier_done=TRUE (no
    LLM re-run), mirroring V28's deliberate entity backfill. The post table is on a
    ~4-day fetched_at RANGE-partition TTL (Invariant 6), so the {unknown} backlog
    ages out of retention within days — a one-off backfill job is not worth it.
  - >-
    Reviving classification as a SUMMARIZER LLM output (the legacy §5.4.4 framing).
    Rejected: it would make a shown field LLM-authored at query time, breaking the
    D19/D36 byte-identical-replay property /retry depends on, and would require
    fragile structured-parse of the summarizer reply (the entity extractor already
    shows ~85% schema-violation on some backends). Classification is an INGEST
    evaluation, computed once and stored — like tags.
  - >-
    Folding classification into the TaggerWorker's existing LLM call. Rejected:
    the tagger's three-surface fallback (schema-violating→different prompt,
    zero-valid→retry, unreachable→backoff) and prompt-injection delimiter handling
    are security-sensitive and tuned for the controlled-vocabulary tag concern;
    entangling a second independently-failing field muddies that state machine.
    A separate worker mirroring EntityExtractorWorker is cleaner and keeps the
    tagger untouched. Do NOT modify TaggerWorker.java.
  - >-
    A growable `classification` vocabulary table or controlled-vocab seeding.
    Classification is a FIXED closed enum of six values enforced by a DB CHECK
    constraint + a Java-side membership filter (mirroring post_entity.entity_type),
    NOT a Tier-1 `tag`-style table. No new vocabulary table, no bootstrap seeding.
acceptance:
  - >-
    Migration V57__post_classification.sql adds to the partitioned `post` table:
    (a) `classification TEXT[] NOT NULL DEFAULT ARRAY['unknown']::TEXT[]` with a
    CHECK that every element is in the closed set — `classification <@
    ARRAY['factual','opinion','technical','urgent','ongoing','unknown']::TEXT[]` —
    and a non-empty CHECK (`array_length(classification,1) >= 1`); and (b)
    `classifier_done BOOLEAN NOT NULL DEFAULT FALSE` as the per-stage durable
    cursor (Invariant 5). It backfills existing posts with `UPDATE post SET
    classifier_done = TRUE WHERE tagger_done = TRUE` (classification stays the
    {unknown} default — no LLM re-run), exactly as V28 did for entity_done. The
    migration is a single atomic transaction. No GRANT change (post is already
    collector-write / provider-read).
  - >-
    ModelTask gains `CLASSIFIER("classifier")` and LlmRouter routes it from
    `infochat.llm.classifier.*` config (base-url / api-key / model /
    max-concurrency / poll-interval) added to the collector application.properties,
    mirroring the existing `infochat.llm.entity.*` shape. `LlmRouter.isLanguageAwareTask`
    gains `CLASSIFIER` in its non-language-aware (`-> false`) switch arm so the
    exhaustive switch still compiles (classification, like tagging, has no
    target-language variation). Because the `@Startup LlmRouterStartupGuard` runs
    on BOTH services and eagerly resolves every ModelTask at boot, the provider
    application.properties ALSO gains an `infochat.llm.classifier.*` block
    (base-url + model at minimum) so provider startup does not abort — the
    provider never runs a classifier call (see out_of_scope for the single-file
    carve-out).
  - >-
    ClassifierWorker (new, modeled on EntityExtractorWorker) is a @Scheduled poller
    whose pickup is `status='RAW' AND tagger_done=TRUE AND classifier_done=FALSE
    AND fetched_at >= scanWindowFloor(clock.instant())` (the fetched_at floor read
    from an injected java.time.Clock per engineering-rules §9, mirroring the
    tagger). Per post it makes ONE LLM call via prompts/classifier.md, parses the
    returned label set, normalizes + filters to the closed enum (out-of-enum
    labels dropped), caps the accepted substantive set at 3 (the 1-3 cardinality
    carried over from the retired §5.4.4 prompt), and writes the atomic cursor
    `UPDATE post SET classification=?, classifier_done=TRUE WHERE id=? AND
    fetched_at=?`. `unknown` is a first-class label the model may return when no
    substantive label fits and is never combined with a substantive label (a
    non-empty filtered substantive set yields 1-3 of those labels; an empty one
    yields exactly `{unknown}`); and on schema-violation / empty-after-filter / LLM
    unreachable the worker writes `classification={unknown}, classifier_done=TRUE`
    (graceful — mirrors the entity extractor's "entity_done=TRUE with no rows").
    Untrusted post content is wrapped with the per-call rotating {{id}} delimiter,
    same as the tagger/entity prompts (docs/design/04-security.md §4.3).
  - >-
    ReadyPromoter's readiness gate gains `AND classifier_done = TRUE` (both the
    javadoc-documented predicate and the enumeratePending SQL). This is
    load-bearing: every eval worker's pickup filters on `status='RAW'`, so if a
    post promoted RAW→READY before classification, the classifier's RAW filter
    would exclude it permanently and it would stay {unknown} forever. After the
    change a post promotes only once stage1/(stage2)/tagger/entity/embedding AND
    classifier are all done.
  - >-
    NAMED TESTS. ClassifierWorkerTest (unit, no DB): parse of the classifier reply,
    closed-enum membership filter (out-of-enum labels dropped), the cap (a reply
    with >3 substantive labels is truncated to 3; `unknown` returned alongside
    substantive labels is dropped so the two never co-occur), and the three
    fallback-to-{unknown} surfaces (schema-violating, empty-after-filter,
    unreachable). ClassifierWorkerIT (Testcontainers, mirrors EntityExtractorWorkerIT):
    a RAW tagger-done post is classified and its classification + classifier_done
    are written; a schema-violating reply yields classification={unknown} and
    classifier_done=TRUE. ReadyPromoterIT is extended to prove a post with
    classifier_done=FALSE is NOT promoted to READY and one with it TRUE (others
    done) IS. Red-before / green-after.
  - >-
    Design/spec updated to match the code: docs/design/02-schema.md §2.3.1 `post`
    gains the classification + classifier_done columns and the §2.12 Invariant-5
    coverage row lists classifier_done; docs/design/05-llm-and-embeddings.md gains
    a Classifier prompt-template subsection modeled on §5.4.2 Tagger, the closed
    enum is documented WITH `unknown`, and §5.4.4 Summarizer is reframed so
    classification is described as an ingest-time per-post evaluation (NOT a
    summarizer output); docs/spec/architecture.md §Pipelines adds `classifier` to
    the parallel-after-tagger stage set ({classifier, entity extraction, embedding});
    and docs/spec/llm.md §SPI shape adds CLASSIFIER("classifier") to the closed
    ModelTask enum listing (line ~43) — its design mirror docs/design/05
    §5.1 SPI overview does the same — and the §Per-task routing rules prose that
    enumerates the Collector ingest-pipeline tasks (security judge, tagging,
    entity extraction, embedding) adds classification.
  - >-
    The two ModelTask-enumeration guards are updated for the seventh value:
    LlmRouterStartupGuardKeyDerivationTest's EXPECTED_SEGMENTS map gains
    `ModelTask.CLASSIFIER, "classifier"` (its assertion pins
    `EXPECTED_SEGMENTS.size() == ModelTask.values().length` and the loop derives
    each per-task key), and LlmSpisLoadTest's spec-mandated count assertion
    `assertEquals(6, ModelTask enum-constant count)` becomes 7 with its comment
    updated to list classification. Both are authorized in test_plan.modifies and
    are red-before / green-after the enum widening.
  - >-
    Prompt-injection hardening (D21 redteam remediation): the classifier prompt
    wraps the untrusted post TITLE inside the per-call {{id}} delimiter (not just
    the body), so all user-derived text sits inside the delimited block per
    §Prompt-injection defenses. ClassifierWorkerTest.renderPrompt_wrapsTitleInsideDelimiter
    asserts the rendered title lands between the delimiter markers (red-before /
    green-after). Pre-existing tagger/entity title placement is a separate
    follow-up, out of scope here.
  - mvn verify is green from the repo root.
test_plan:
  adds:
    - >-
      infochat-collector/.../eval/classifier/ClassifierWorkerTest.java — pure unit
      (no DB, no @QuarkusTest — the IT-naming guard requires a new DataSource-
      injecting test be named *IT): parseClassification parse / closed-enum filter
      / cap-at-3 / unknown-mutual-exclusion / schema-violating→null /
      empty-after-filter→{unknown} / fenced-JSON recovery / renderPrompt wraps
      the title inside the {{id}} delimiter (D21 redteam remediation).
    - >-
      infochat-collector/.../eval/classifier/ClassifierWorkerIT.java — end-to-end
      (Testcontainers): RAW→classified via the real pickup SQL + scan-window
      floor, plus the write-level fallback surfaces schema-violation→{unknown}
      and LLM-unreachable→{unknown}+notify (the two surfaces that need
      processOne + a DB; the parse-level schema/empty surfaces live in the unit
      test).
  modifies:
    - >-
      infochat-collector/.../eval/ready/ReadyPromoterIT.java — classifier_done is
      now part of the RAW→READY gate.
    - >-
      infochat-llm-adapter/.../routing/LlmRouterStartupGuardKeyDerivationTest.java
      — EXPECTED_SEGMENTS gains ModelTask.CLASSIFIER -> "classifier" (6->7) so the
      key-derivation pin covers the new task (forced by the enum widening).
    - >-
      infochat-llm-adapter/.../LlmSpisLoadTest.java — the spec-mandated ModelTask
      count assertion goes 6 -> 7 and its comment lists classification (forced by
      the enum widening).
    - >-
      infochat-collector/.../eval/ready/ReadyPromoterClockIT.java — its
      seedPromotionReadyPost fixture gains classifier_done=TRUE (forced by the
      ReadyPromoter classifier_done gate; else the in-window post is gate-excluded).
    - >-
      infochat-collector/.../eval/reeval/ReEvalVerdictNotifyIT.java — the
      re-eval→READY drive-sequence gains classifierWorker.onTick() so the requeued
      post clears the new classifier_done gate (forced by the gate; else it stays RAW).
  preserves:
    - all tests currently green on main
    - >-
      TaggerWorker / EntityExtractorWorker behavior — untouched; their tests stay
      green (the new stage is additive and parallel).
spec_refs:
  - docs/spec/architecture.md §Pipelines
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/llm.md §Bounded concurrency and observability
  - docs/design/02-schema.md §2.3.1 `post` — partitioned by `fetched_at`
  - docs/design/05-llm-and-embeddings.md §5.4.2 Tagger
  - docs/design/05-llm-and-embeddings.md §5.4.3 Entity extractor
  - docs/design/05-llm-and-embeddings.md §5.4.5 Summarizer (cluster mode) [was §5.4.4 pre-M1-597; renumbered by the new §5.4.4 Classifier]
decision_refs:
  - D19
  - D36
redteam_findings:
  - date: 2026-07-08
    category: INJECTION
    severity: low
    promise: |
      docs/spec/security.md §Prompt-injection defenses (D21): every prompt
      including user-derived text is wrapped in a per-call random delimiter
      block so attackers cannot forge a closing tag.
    gap: |
      The classifier prompt renders `Post title: {{title}}` OUTSIDE the per-call
      {{id}} delimiter (classifier.md: title line above the wrapper opener; only
      {{body}} is inside). ClassifierWorker.renderPrompt substitutes the
      upstream-untrusted row.title() verbatim into that un-delimited position.
      Mirrors the pre-existing tagger (§5.4.2) / entity (§5.4.3) prompts.
    repro: |
      A feed-controlling adversary publishes a post whose TITLE carries
      injection text; the collector weaves it into the prompt outside the
      delimiter so the model reads it as instructions. Impact bounded to nil by
      the closed-enum Java filter + V57 DB CHECK (worst case: mislabel the KIND
      of the attacker's own post) — hence low / defense-in-depth.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-07-08
    verdict: FINDINGS
    base: 87fedd1873c596ac2dc503a5f1343874ff071ca3
    head: working-tree (m1/M1-597-classification-ingest-stage)
    verdict_file: docs/plan/m1/redteam/M1-597-2026-07-08.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      One LOW defense-in-depth injection finding (title outside the {{id}}
      delimiter, mirrors tagger/entity; bounded to nil by the closed-enum
      filter). One SAFE out-of-model note (switch-llm.sh unaware of the
      classifier task; classifier stays local, no remote leak). Disposition:
      user chose refine → remediated in-branch (see the CLEAN re-run below).
  - date: 2026-07-08
    verdict: CLEAN
    base: 87fedd1873c596ac2dc503a5f1343874ff071ca3
    head: working-tree (m1/M1-597-classification-ingest-stage, post-remediation)
    verdict_file: docs/plan/m1/redteam/M1-597-2026-07-08.md
    out_of_model_count: 1
    note: |
      Post-remediation re-run after the D21 fix (title moved inside the {{id}}
      delimiter). CLEAN — the LOW injection finding is closed; no new finding.
      The out-of-model item (switch-llm.sh classifier-awareness) is unchanged
      and carried to a follow-up, out of M1-597's scope.
reviews:
  - round: 1
    date: 2026-07-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 22
      added: 1197
      removed: 55
  - round: 2
    date: 2026-07-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 22
      added: 1312
      removed: 58
clarity_check:
  date: 2026-07-08
  verdict: PASS
  warnings:
    - >-
      risk: medium is defensible but borderline given migration_touch: true plus
      the ReadyPromoter gate change item 4 calls "load-bearing"; informational
      only (the hard rule only flags risk: low against this surface).
  blockers: []
escalations:
  - date: 2026-07-08
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL (2 blockers, 2 warnings). Blockers: (1) cap number
      for the classification label set is unspecified in acceptance items 3 & 5;
      (2) docs/spec/llm.md §SPI shape (closed ModelTask enum) is missing from
      files_scope and the item-6 doc checklist, though item 2 widens that enum.
  - date: 2026-07-08
    reason: outline-fail
    reviewer_verdict_excerpt: |
      OUTLINE FAILED (plan-writer, round 1). Root cause: acceptance item 2's
      ModelTask.CLASSIFIER enum widening forces two edits the ticket never
      scoped. (1) Breaks pre-existing unit test LlmRouterStartupGuardKey
      DerivationTest (6-entry EXPECTED_SEGMENTS, asserts size==values().length,
      loops get(task)) — an unauthorized test modification (not in files_scope
      nor test_plan.modifies). (2) LlmRouterStartupGuard (@Startup, runs on
      BOTH services via provider index-dependency) calls assertAllTasksResolve()
      which requires base-url+model for EVERY ModelTask; provider has no
      classifier block, so provider boot throws TaskConfigUnresolvableException
      → provider ITs fail → "mvn verify green" unreachable. Fix (classifier
      config in infochat-provider/.../application.properties) is forbidden by
      out_of_scope + absent from files_scope. Both confirmed against ground
      truth. SUGGESTED ESCALATION: refine.
  - date: 2026-07-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      files_scope path-list breach (not a count breach: 18 -> 20 = budget 20).
      The ReadyPromoter classifier_done gate (acceptance item 4) breaks TWO
      pre-existing full-pipeline promotion fixtures that drive a post to READY
      and whose seeds omit classifier_done — neither in files_scope nor
      test_plan.modifies (the refine's Risk-3 named ReadyPromoterIT +
      IngestNotifySmokeIT but under-enumerated these two):
        - ReadyPromoterClockIT.enumeratePending_gatesOnInjectedClock — its
          seedPromotionReadyPost INSERT sets stage1/stage2/tagger/entity/
          embedding_done but not classifier_done -> the in-window post is now
          gate-excluded -> "must be enumerated" assertion fails.
        - ReEvalVerdictNotifyIT.unknownBenignReEvalCompletesPipelineAndEmitsNewPost
          — drives tagger/entity/embedding onTick then readyPromoter.onTick
          (the gated enumeratePending path); no classifier stage runs ->
          classifier_done=FALSE -> post stays RAW, "READY" assertion fails.
      Full collector suite ran (199 tests, exactly these 2 failures); other
      promotion ITs use promoteOne (gate-independent) or were already fixed
      (ReadyPromoterIT). Provider module [7/7] was NOT reached (build stopped
      at collector). SUGGESTED ESCALATION: refine (add the two ITs to
      files_scope + test_plan.modifies; fixes are mechanical — add
      classifier_done=TRUE to the ClockIT fixture, add classifierWorker.onTick()
      to the ReEval drive-sequence).
  - date: 2026-07-08
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RED-TEAM: FINDINGS (low=1, out-of-model=1). LOW / INJECTION: the classifier
      prompt renders `Post title: {{title}}` OUTSIDE the per-call {{id}}
      delimiter (only {{body}} is wrapped) — a D21 defense-in-depth gap. The
      title is upstream-untrusted (feed-supplied). Impact bounded to nil by the
      closed-enum Java filter + V57 DB CHECK (worst case: mislabel the KIND of
      the attacker's own post). Mirrors the shipping tagger (§5.4.2) / entity
      (§5.4.3) prompts. Full verdict: docs/plan/m1/redteam/M1-597-2026-07-08.md.
overrides: []
revisions:
  - date: 2026-07-08
    reason: >-
      clarity-fail refine (user-directed via /m1-tick run escalation menu):
      acceptance items 3 & 5 left the classification cap number unstated, and
      files_scope / item-6 doc checklist omitted docs/spec/llm.md §SPI shape
      (the closed ModelTask enum item 2 widens).
    snapshot: |
      files_scope (pre-refine): 13 paths; docs/spec/llm.md absent (spec_refs only).
      acceptance item 3 (verbatim, pre-refine): "... normalizes + filters to the
        closed enum (out-of-enum labels dropped), caps the accepted set, and
        writes the atomic cursor ..."
      acceptance item 5 (verbatim, pre-refine): "... closed-enum membership filter
        (out-of-enum labels dropped), the cap, and the three fallback-to-{unknown}
        surfaces ..."
      acceptance item 6 (pre-refine): ended at docs/spec/architecture.md §Pipelines;
        docs/spec/llm.md §SPI shape not listed.
      clarity blockers (2026-07-08): (1) cap number unspecified; (2) docs/spec/llm.md
        §SPI shape closed enum missing from files_scope + item-6 checklist though
        item 2 adds a 7th ModelTask value.
      resolution: state cap = 3 substantive labels (1-3 carried from retired §5.4.4;
        unknown mutually exclusive); add docs/spec/llm.md to files_scope (14 paths,
        still <= files_budget 16); extend item 6 to require CLASSIFIER in the
        §SPI shape enum listing + design §5.1 mirror + §Per-task routing prose.
        files_budget / complexity / risk / round_cap unchanged.
  - date: 2026-07-08
    reason: >-
      outline-fail refine (user-directed via /m1-tick run escalation menu, applied
      with an exhaustive ripple audit): the ModelTask.CLASSIFIER enum widening
      forced three edits the ticket had not scoped, plus an entity-mirror companion.
    snapshot: |
      files_scope (pre-refine): 14 paths; files_budget 16.
      out_of_scope (pre-refine): "Do NOT touch anything under infochat-provider/."
      test_plan.modifies (pre-refine): ReadyPromoterIT only.
      plan-writer OUTLINE FAILED (round 1) findings + orchestrator audit:
        - FORCED (in-module test): LlmRouterStartupGuardKeyDerivationTest asserts
          EXPECTED_SEGMENTS.size()==ModelTask.values().length (6) -> breaks at 7.
        - FORCED (in-module test, found by orchestrator audit, NOT in the plan
          -writer findings): LlmSpisLoadTest asserts ModelTask enum count == 6.
        - FORCED (provider config): @Startup LlmRouterStartupGuard runs on BOTH
          services and resolves every ModelTask; provider needs a classifier
          base-url+model block or boot aborts. Was under the provider ban.
        - COMPILE (already in scope): LlmRouter.isLanguageAwareTask exhaustive
          switch needs a CLASSIFIER arm.
      audited NOT forced (stay out of scope): TaggerWorker/Stage2Worker (switch
        over non-ModelTask, has default); LlmRouterStartupGuard (data-driven, "no
        guard edit" by design); PostPersister (classifier_done via DB DEFAULT,
        mirrors entity_done which its INSERT also omits); ReEvaluationJob (stage2
        -only); all provider Java + provider tests (constant-use / non-exhaustive
        if); OpenAiCompatibleProviderTest "AllSix" (data-driven, passes; name
        cosmetic).
      resolution: files_scope 14 -> 18 (+ KeyDerivationTest, + LlmSpisLoadTest,
        + provider application.properties, + ClassificationResult.java entity
        mirror); files_budget 16 -> 20; out_of_scope narrowed to a single-file
        provider carve-out; acceptance item 2 extended (isLanguageAwareTask arm +
        provider config); new acceptance item for the two enum-guard tests;
        test_plan.modifies += the two forced tests. complexity/risk/round_cap
        unchanged.
  - date: 2026-07-08
    reason: >-
      budget-breach refine (user-directed via /m1-tick run escalation menu): the
      pre-review mvn verify surfaced two more gate-forced fixture edits outside
      files_scope that the outline-fail refine's Risk-3 under-enumerated.
    snapshot: |
      files_scope (pre-refine): 18 paths; files_budget 20.
      test_plan.modifies (pre-refine): ReadyPromoterIT + the two enum-guard tests.
      pre-review mvn verify (r1): core+messaging+collector ran; 199 collector
      tests, 2 failures — both the ReadyPromoter classifier_done gate breaking a
      full-pipeline promotion fixture:
        - ReadyPromoterClockIT.enumeratePending_gatesOnInjectedClock (seed omits
          classifier_done -> in-window post gate-excluded).
        - ReEvalVerdictNotifyIT.unknownBenignReEvalCompletesPipelineAndEmitsNewPost
          (drives readyPromoter.onTick with no classifier stage -> stays RAW).
      provider module [7/7] not reached (build stopped at collector).
      resolution: files_scope 18 -> 20 (+ ReadyPromoterClockIT,
      + ReEvalVerdictNotifyIT); files_budget 20 -> 22 (headroom before the
      provider module runs); test_plan.modifies += the two. Mechanical fixes:
      classifier_done=TRUE in the ClockIT fixture; classifierWorker.onTick() in
      the ReEval drive-sequence. complexity/risk/round_cap unchanged.
  - date: 2026-07-08
    reason: >-
      redteam-finding refine (user-directed via /m1-tick escalate M1-597
      redteam-finding): remediate the LOW INJECTION finding by wrapping the
      untrusted post title inside the per-call {{id}} delimiter (D21).
    snapshot: |
      Pre-refine: classifier.md rendered `Post title: {{title}}` OUTSIDE the
      <<<UNTRUSTED_CONTENT id="{{id}}">>> block; only {{body}} was wrapped
      (mirrored the tagger/entity prompts). D21 requires every user-derived text
      be delimiter-wrapped. Impact was bounded to nil by the closed-enum filter.
      resolution: move the title inside the delimiter in prompts/classifier.md +
      sync docs/design/05-llm §5.4.4 Classifier; add ClassifierWorkerTest
      .renderPrompt_wrapsTitleInsideDelimiter (red-before/green-after). No code
      change to ClassifierWorker (renderPrompt substitutes tokens wherever the
      template places them). files_scope/budget UNCHANGED — the fix touches only
      already-scoped files (classifier.md, ClassifierWorkerTest, 05-llm). This is
      a user-accepted in-branch redteam remediation → the round-2 growth is a
      citable must-shrink exemption. Pre-existing tagger/entity title-wrapping +
      switch-llm.sh classifier-awareness are a separate follow-up (out of scope).
aborted_attempts: []
reopens: []
---

# M1-597: real per-post classification ingest stage

## Context

`docs/design/03-commands.md` §`/summary` and `05-llm §5.4.4` describe a
per-post **classification** — an enum `{factual, opinion, technical, urgent,
ongoing}` (this ticket adds `unknown`) — as a concept DISTINCT from topic tags.
It was designed from day one but **never implemented with a data source**: there
is no `post.classification` column, and the cluster renderer only ever made
`summary:` LLM-authored, so the classification slot was stubbed as a copy of the
tag union (`joinedTags`). M1-591 then deleted the always-duplicate line; that
deletion was **reverted** (commits `77e96fee`, `57da696a`) because the intended
fix is to give classification a real data source, not to remove the feature.
M1-591 is abandoned/superseded by this ticket + M1-598.

## The fix (this ticket = the collector + schema half)

Compute classification at **ingest**, per post, in the collector's eval
pipeline — the same place and pattern as tagging and entity extraction — and
store it on the post. A separate `ClassifierWorker` (modeled on the proven
`EntityExtractorWorker`, NOT folded into the security-sensitive `TaggerWorker`)
runs one LLM call per post against a fixed closed enum and writes
`post.classification` + the `classifier_done` cursor. `unknown` is both a
first-class label the model returns when nothing substantive fits and the safe
default the worker writes on any failure — so the column is `NOT NULL` and never
empty. `ReadyPromoter` waits for `classifier_done` before promoting RAW→READY.

The provider render side (project `post.classification` into `/summary`, union
it, drop `unknown` unless it is the only label) is **M1-598**, which is
`blocked_by` this ticket.

## Why ingest-time per-post, not summarizer-authored (D19/D36)

Classification is an evaluation, not prose. Computing it at ingest and storing
it keeps `/summary` (and `/retry`) deterministic — the shown field is read from
the DB, so byte-identical replay holds. Making it a summarizer LLM output would
break that and require fragile structured-parse of the summarizer reply. See
frontmatter out-of-scope for the full rationale (and for why a separate worker
beats folding into the tagger).

## `unknown` semantics

`unknown` means "none of the five substantive labels fit" (e.g. a bare `wow` /
`just found this` post) OR "the classifier could not decide / errored." Both
resolve to `{unknown}`. The prompt instructs the model to prefer a substantive
label and fall to `unknown` only when nothing genuinely applies. At the DB it is
a permitted CHECK value; the column DEFAULT is `{unknown}` so existing rows and
any un-run post are non-null.

## Size note

This ticket spans three modules (core migration, llm-adapter, collector) plus a
one-line provider boot-config edit, a migration, and the ReadyPromoter gate —
~18 files (files_budget 20). The size grew from the initial ~13 estimate because
the ModelTask.CLASSIFIER enum widening forces two in-module enum-guard test
updates (LlmRouterStartupGuardKeyDerivationTest, LlmSpisLoadTest) plus the
provider boot-config block (see revisions for the ripple audit). It is kept whole
per the approved collector+schema / provider split. If review finds it unwieldy,
the natural sub-split is [V57 migration + 02-schema] / [ClassifierWorker + router
+ prompt + ReadyPromoter + 05-llm/architecture]; raise it via escalate→decompose
rather than silently splitting.

## Notes

- **Provenance.** Follow-up to the reverted M1-591 (live-test finding
  2026-07-08). The design intent was sound; only the implementation was stubbed.
- **Backfill / TTL.** Existing posts get `{unknown}` + `classifier_done=TRUE`
  (no re-run), and the 4-day `fetched_at` partition TTL ages the backlog out —
  same posture as V28's entity backfill.
- **Security.** The classifier processes untrusted post content through an LLM,
  the same prompt-injection surface as the tagger/entity; the rotating `{{id}}`
  delimiter wrapper is replicated. `security_relevant: true` → redteam gate.
