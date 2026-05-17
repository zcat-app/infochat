---
id: M1-034a
title: Tagger pipeline + V11 (post_embedding + embedding_metadata)
status: done
created: 2026-05-17
last_updated: 2026-05-17
decomposed_from: M1-034
clarity_check:
  date: 2026-05-17
  verdict: PASS
  warnings: []
  blockers: []
escalations:
  - date: 2026-05-17
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      SPEC-REFS-VALID: FAIL
        docs/spec/schema.md §Tag stored form — ANCHOR-NOT-FOUND
          "Tag stored form" is bold prose text inside ### Sources and tags
          subsection of docs/spec/schema.md (~lines 207-215), not a heading.
        docs/design/02-schema.md §2.2.1 tag — ANCHOR-NOT-FOUND
          §2.2.1 is `source`; the tag table lives under §2.2.2.
  - date: 2026-05-17
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (escalation surfaced by developer during implementation, not by a
      reviewer verdict).

      Trigger: TaggerWorkerIT requires a stub LlmProvider so per-test
      scenarios can drive the LLM round-trip (canned valid JSON, partial-
      valid, schema-violating, zero-valid, unreachable). The only existing
      reusable stub is `Stage2WorkerIT.TestStubLlmProvider`, defined as a
      `public static` nested class inside a package-private outer class
      (`class Stage2WorkerIT`, not `public class`). Cross-package import
      is blocked by the outer-class visibility — the nested type is
      effectively package-private.

      Three workarounds were verified with `mvn -B -pl infochat-collector
      -am verify` against the in-progress branch:

        1. Add a second nested @Alternative @Priority(Integer.MAX_VALUE)
           stub in TaggerWorkerIT → Quarkus ArC reports
           AmbiguousResolutionException at deployment (verified verbatim
           below); both ITs fail to boot.
        2. QuarkusMock.installMockForType(myStub, LlmProvider.class) →
           ClassCastException: the resolved bean is Stage2's stub class;
           QuarkusMock requires the mock to be assignable to that
           specific bean class, which my mock is not.
        3. Custom CDI qualifier on a new @ApplicationScoped stub → my
           stub becomes invisible to LlmRouter's `Instance<LlmProvider>`
           injection (which carries the @Default qualifier), so TaggerWorker
           still routes to Stage2's stub at runtime.

      Verbatim Quarkus error from approach 1:

        Ambiguous dependencies for type io.infochat.llm.LlmProvider and
        qualifiers [@Default]
          - injection target: io.infochat.collector.eval.stage2.Stage2WorkerIT#llmProvider
          - available beans:
            - Stage2WorkerIT$TestStubLlmProvider (@Default)
            - TaggerWorkerIT$CannedStubLlmProvider (@Default)
            - OpenAiCompatibleProvider (@Default)

      Resolution requires either (a) modifying Stage2WorkerIT (not
      authorized — body §"Authorized test changes" = "(none)") or
      (b) introducing a shared test-stub utility in a new file (would
      take files_budget from 7 → 8 or 9). Both are frontmatter changes
      that must flow through `/m1-tick escalate refine`.

      User direction (from chat): extract a shared StubLlmProvider into
      a `testing/` sub-package and refactor Stage2WorkerIT to consume it
      — the test-utility abstraction is naturally reusable by future ITs
      (M1-034b EmbeddingWorker, T2 chat-agent), keeping dependency arrows
      pointing from per-test ITs to a shared utility rather than from
      one IT to another.
  - date: 2026-05-17
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A (escalation surfaced by the commit-step `mvn verify` safety re-run
      for high-complexity tickets, not by a reviewer verdict).

      Trigger: round-1 was APPROVE; the commit-step verify caught a 25%
      structural flake in Stage2WorkerIT. Stack frames in
      target/m1-tick-test-M1-034a-rcommit.log (lines 830, 950, 985) show
      TaggerWorker.onTick (the @Scheduled poll, default every 5s) firing
      in the background during Stage2WorkerIT.@Order(7). The shared
      @ApplicationScoped StubLlmProvider that production code resolves to
      ALSO gets called by the background scheduler tick when it picks up
      Tagger-eligible posts from a prior IT's seeds. Result:
      Stage2WorkerIT.unreachableLlmAfterRetryExhaustionTakesInfraFailurePath
      asserts `callCount=2` but observes `4` (its own 2 + scheduler's 2).

      Per-test stub-instance isolation does NOT fix this: the race is
      background-thread-vs-test-thread, not state-leak-between-tests.
      Any per-test stub mechanism (QuarkusMock.installMockForType,
      @Dependent, ThreadLocal-scoped producer) still produces one bean
      instance in scope during the test method, and the background
      scheduler thread calls that same instance through CDI.

      The test's premise — that the @Scheduled tick wouldn't pollute the
      shared stub during a @QuarkusTest — was wrong. Structural fix:
      switch the test profile to `quarkus.scheduler.start-mode=halted` so
      no @Scheduled tick fires automatically; tests that need a tick
      (HeartbeatSchedulerIT) inject `io.quarkus.scheduler.Scheduler` and
      call `scheduler.resume()` (or invoke the @Scheduled method
      directly). One-time fix that pre-empts the same race for every
      future @Scheduled eval-pipeline worker (M1-034b EmbeddingWorker,
      T2 chat-agent re-eval, ...).
revisions:
  - date: 2026-05-17
    reason: budget-breach rework
    note: |
      Resolved the budget-breach escalation by widening files_budget 7 → 9
      to authorize a shared test-stub extraction. Two new files_scope
      entries:
        - infochat-collector/src/test/java/io/infochat/collector/eval/testing/StubLlmProvider.java
          (NEW) — extracted public top-level @Alternative @Priority(MAX)
          @ApplicationScoped stub implementing LlmProvider. Replaces the
          nested Stage2WorkerIT.TestStubLlmProvider (M1-033) and the
          in-progress nested TaggerWorkerIT.CannedStubLlmProvider that
          triggered the budget breach (the Stage2 stub is enclosed in a
          package-private outer class and is therefore not importable
          from io.infochat.collector.eval.tagger; a second @Alternative
          @Priority(MAX) bean in the same module yielded ArC
          AmbiguousResolutionException at deployment per the
          budget-breach escalation reviewer_verdict_excerpt). Public
          top-level placement so any future LLM-evaluation IT (M1-034b
          EmbeddingWorker, T2 chat-agent) can consume the same stub
          without re-declaring its own @Alternative bean.
        - infochat-collector/src/test/java/io/infochat/collector/eval/stage2/Stage2WorkerIT.java
          (MODIFIED — see body §"Authorized test changes") — delete the
          nested `public static class TestStubLlmProvider`; rewrite the
          `(TestStubLlmProvider) llmProvider` cast at the @Inject site to
          `(StubLlmProvider) llmProvider`; add the import
          `io.infochat.collector.eval.testing.StubLlmProvider`. All nine
          @Test methods (28a–28i) keep their behavior and assertions
          byte-equal — only the test-utility class moves. M1-033's
          behavioral contract (nine scenarios + release-on-stage2-failure
          flag toggling) is preserved.
      Snapshot of the pre-refine frontmatter (only the changed fields
      are listed; everything else carries through unchanged):
        files_budget: 7
        files_scope:
          - infochat-core/src/main/resources/db/migration/V11__post_embedding.sql
          - infochat-llm-adapter/src/main/resources/prompts/tagger.md
          - infochat-llm-adapter/src/main/resources/prompts/tagger-fallback.md
          - infochat-collector/src/main/java/io/infochat/collector/eval/tagger/TagVocabulary.java
          - infochat-collector/src/main/java/io/infochat/collector/eval/tagger/TaggerWorker.java
          - infochat-collector/src/main/resources/application.properties
          - infochat-collector/src/test/java/io/infochat/collector/eval/tagger/TaggerWorkerIT.java
      Acceptance items 1–29 are NOT modified — they describe the Tagger
      pipeline contract, and the shared-stub extraction is test
      scaffolding. Item 27 ("≥7 @Test in TaggerWorkerIT") still holds:
      the nested CannedStubLlmProvider class carried zero @Test methods,
      so deleting it leaves the seven scenario @Test methods untouched.
      The change is forward-compatible with M1-034b's EmbeddingWorker IT
      (which will @Inject the same LlmProvider) and avoids the visibility
      hack of widening Stage2WorkerIT to public solely so its nested
      stub can be imported cross-package.
  - date: 2026-05-17
    reason: clarity-fail rework
    note: |
      Resolved both SPEC-REFS-VALID blockers and both clarity warnings:
        - spec_refs: docs/spec/schema.md §Tag stored form
          → docs/spec/schema.md §Sources and tags (the actual enclosing
          heading; the stored-form definition lives as bold prose inside
          that subsection).
        - spec_refs: docs/design/02-schema.md §2.2.1 tag
          → docs/design/02-schema.md §2.2.2 tag (off-by-one correction;
          §2.2.1 is `source`, §2.2.2 is `tag`).
        - acceptance item 22 regex tightened: `tagger\.partial_valid|
          valid.*invalid|tagger_partial` → `tagger_partial_valid|
          tagger\.partial_valid|valid tags.*invalid|tagger.*valid.*invalid`
          (closes the loophole where a comment or unrelated line containing
          "valid" + "invalid" would satisfy the grep without the tagger
          actually emitting partial-valid counts).
        - complexity: medium → high, formally authorizing round_cap: 3
          (workflow requires complexity:high or risk:high for round_cap:3;
          the V11 migration + TagVocabulary + TaggerWorker + 2 prompts +
          application.properties amendment + 7-scenario IT scope justifies
          the high claim and the 3-round headroom that was the rationale
          for the M1-034 split).
        - body Implementation notes and acceptance item 19 reference text
          updated to the corrected spec section paths.
      No DoD / acceptance / files_scope / out_of_scope changes beyond the
      spec_ref + regex + complexity adjustments above.
  - date: 2026-05-17
    reason: premise-fail rework (commit-step scheduler-race)
    note: |
      Resolved the premise-fail escalation surfaced by the commit-step
      `mvn verify` safety re-run. Round-1 was APPROVE on a diff that
      assumed the TaggerWorker's @Scheduled background tick would not
      fire during @QuarkusTest method bodies; the safety re-run
      revealed a 25% structural flake where the background tick fires
      every 5s and pollutes shared @ApplicationScoped beans (concretely:
      Stage2WorkerIT's callCount assertion against the shared
      StubLlmProvider). The premise the round-1 IT relied on was wrong.

      The structural fix is a one-line property in the test profile
      (`%test.quarkus.scheduler.start-mode=halted` in
      infochat-collector/src/main/resources/application.properties),
      which halts the Quarkus scheduler at boot so no @Scheduled tick
      fires automatically. Tests that need a tick
      (HeartbeatSchedulerIT from M1-009) inject
      io.quarkus.scheduler.Scheduler and call scheduler.resume() (or
      invoke the @Scheduled method directly). This is preferred over a
      per-worker poll-interval override because every future @Scheduled
      eval-pipeline worker (M1-034b EmbeddingWorker, T2 chat-agent
      re-eval, ...) would otherwise need the same patch — forgetting it
      would re-introduce the same 5s-cadence flake silently.

      Audit of collector ITs against the start-mode=halted profile:
        - HeartbeatSchedulerIT (M1-009) — Thread.sleep(7_000) waiting on
          background tick; MUST be adjusted to inject Scheduler +
          scheduler.resume() (or invoke tick() directly). The original
          contract (heartbeat row updates after the tick) is preserved;
          only the trigger source changes from implicit-background-tick
          to explicit-scheduler-resume.
        - FetchSchedulerIT (M1-028) — calls fetchScheduler.tickOnce(row)
          directly; not affected by start-mode=halted; no change needed.
        - TaggerWorkerIT, Stage2WorkerIT, OutboxRehydratorIT,
          PostPersisterIT, Stage1PipelineIT, Stage1WatchdogIT,
          LocalOnlyConflictStartupIT, FlywayMigrationIT, DbRoleMatrixIT,
          and the provider-module ITs — all invoke their @Scheduled
          methods directly or do not depend on any tick; no change
          needed.

      Snapshot of the pre-refine frontmatter (only the changed fields
      are listed; everything else carries through unchanged):
        files_budget: 9
        files_scope:
          - infochat-core/src/main/resources/db/migration/V11__post_embedding.sql
          - infochat-llm-adapter/src/main/resources/prompts/tagger.md
          - infochat-llm-adapter/src/main/resources/prompts/tagger-fallback.md
          - infochat-collector/src/main/java/io/infochat/collector/eval/tagger/TagVocabulary.java
          - infochat-collector/src/main/java/io/infochat/collector/eval/tagger/TaggerWorker.java
          - infochat-collector/src/main/resources/application.properties
          - infochat-collector/src/test/java/io/infochat/collector/eval/tagger/TaggerWorkerIT.java
          - infochat-collector/src/test/java/io/infochat/collector/eval/testing/StubLlmProvider.java
          - infochat-collector/src/test/java/io/infochat/collector/eval/stage2/Stage2WorkerIT.java
        acceptance: 29 items (the round-1 APPROVED set). This refine
          adds a 30th acceptance item asserting the
          %test.quarkus.scheduler.start-mode=halted property; the 29
          prior items are NOT modified.
      Diff impact (refine widens by exactly one file):
        files_budget: 9 → 10
        files_scope adds:
          - infochat-collector/src/test/java/io/infochat/collector/startup/HeartbeatSchedulerIT.java
            (MODIFIED — see body §"Authorized test changes" — inject
            io.quarkus.scheduler.Scheduler; call scheduler.resume() in
            @BeforeEach OR invoke tick() directly, whichever is the
            minimum-change variant. The original M1-009-asserted
            contract — heartbeat.last_seen_at advances after a tick —
            is preserved; only the trigger source changes.)
        application.properties (already in files_scope) gains one new
          line: `%test.quarkus.scheduler.start-mode=halted` plus a
          block comment documenting the cross-worker rationale.
      Round-numbering: round-1 was the APPROVE just received; this
      refine is "(round 1 rework)" by the escalate-skill's convention
      (the round number that just escalated, even though here the
      escalation was triggered by commit-step verify rather than a
      review verdict). The next reviewer round is round 2.
reviews:
  - round: 1
    date: 2026-05-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 1637
      removed: 109
  - round: 2
    date: 2026-05-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 1838
      removed: 109
blocked_by:
  - M1-008b
  - M1-008c
  - M1-033
files_budget: 10
files_scope:
  - infochat-core/src/main/resources/db/migration/V11__post_embedding.sql
  - infochat-llm-adapter/src/main/resources/prompts/tagger.md
  - infochat-llm-adapter/src/main/resources/prompts/tagger-fallback.md
  - infochat-collector/src/main/java/io/infochat/collector/eval/tagger/TagVocabulary.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/io/infochat/collector/eval/tagger/TaggerWorkerIT.java
  - infochat-collector/src/test/java/io/infochat/collector/eval/testing/StubLlmProvider.java
  - infochat-collector/src/test/java/io/infochat/collector/eval/stage2/Stage2WorkerIT.java
  - infochat-collector/src/test/java/io/infochat/collector/startup/HeartbeatSchedulerIT.java
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: true
out_of_scope:
  - any Stage 1 HTML sanitizer, Unicode normalization, prompt-injection regex set, watchdog, placeholder-id generation, or quarantine-row insertion code (M1-032 territory — consumed unchanged; the Tagger reads post.body which is already Stage-1-redacted)
  - any Stage 2 LLM judge call, BENIGN/INJECTION/MALWARE/UNKNOWN verdict handling, retry-once-then-fallback, stage2_done advance, stage2_failed flag, OpenAiCompatibleProvider, LlmRouter, or release-on-stage2-failure config-flag wiring (M1-033 territory — the (ModelTask, scope_language) router authored in M1-033 is CONSUMED for the TAGGER task)
  - any concrete EmbeddingProvider impl (OpenAiCompatibleEmbeddingProvider) — M1-034b territory. V11 creates the embedding_metadata seed row here so M1-034b's startup guard has a row to compare against, but the provider itself is M1-034b
  - any EmbeddingMetadataDao, EmbeddingMetadataStartupGuard, EmbeddingWorker, ReadyPromoter, or pg_notify('new_post', ...) emission — M1-034b territory. M1-034a writes post.tagger_done=true; M1-034b picks up from there
  - any post_embedding INSERT — the table is created here in V11 but no code in M1-034a writes a row; the EmbeddingWorker in M1-034b is the sole writer
  - any EntityExtractor, post_entity table, post_reference table, LinkingJob — T2 territory
  - any Re-evaluation job, attempt counter, QUARANTINED → NEEDS_REVIEW transition, per-source UNKNOWN auto-disable — T2-G territory
  - any throttled admin notifier wiring — T2-G; the Tagger fallback path logs at INFO/WARN with canonical error_class strings for the future notifier to pick up
  - any LLM output sanitizer — T1-F territory; tagger output is validated against the controlled vocabulary, invalid tags are silently dropped per the partial-valid rule
  - any change to V1..V10 Flyway migrations (V10 from M1-032 is consumed unchanged; this ticket adds V11)
  - any change to the M1-007b LlmProvider / EmbeddingProvider / ModelTask SPI surfaces (frozen)
  - any infochat-provider module change
  - any partition_pruner job — T2 territory
  - any embedding-model migration script (scripts/reembed.sh)
  - any Prometheus/Micrometer metric emit for tagger_partial_valid_total or per-task latency histograms
acceptance:
  - "infochat-core/src/main/resources/db/migration/V11__post_embedding.sql exists and creates the post_embedding table per docs/design/02-schema.md §2.4.2 — grep -E 'CREATE TABLE\\s+post_embedding\\s*\\(' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding with column post_id UUID NOT NULL — grep -E 'post_id\\s+UUID\\s+NOT NULL' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding with column embedding vector(768) NOT NULL (the laptop/vps default per docs/design/05-llm-and-embeddings.md §5.5; the pi/remote-llm variants are operator-selected via alternative migration file or operator override per §2.8 — document the operator path in the migration's header comment) — grep -E 'embedding\\s+vector\\s*\\(\\s*768\\s*\\)\\s+NOT NULL' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding with column embedding_model TEXT NOT NULL — grep -E 'embedding_model\\s+TEXT\\s+NOT NULL' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding with column fetched_at TIMESTAMPTZ NOT NULL — grep -E 'fetched_at\\s+TIMESTAMPTZ\\s+NOT NULL' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding PRIMARY KEY (post_id, fetched_at) — grep -E 'PRIMARY KEY\\s*\\(\\s*post_id\\s*,\\s*fetched_at\\s*\\)' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding PARTITION BY RANGE (fetched_at) — grep -E 'PARTITION BY RANGE\\s*\\(\\s*fetched_at\\s*\\)' V11__post_embedding.sql returns at least one match"
  - "V11 creates at least one initial partition of post_embedding so the schema is queryable on day one (matching M1-008c's post partitioning pattern) — grep -E 'CREATE TABLE\\s+post_embedding_[0-9]+\\s+PARTITION OF\\s+post_embedding' V11__post_embedding.sql returns at least one match"
  - "V11 creates the HNSW vector index per docs/design/02-schema.md §2.4.2 (laptop/vps/remote-llm: HNSW with m=16, ef_construction=64; the pi profile's IVFFlat variant is a separate operator choice — document in the migration header) — grep -E 'USING\\s+hnsw\\s*\\(\\s*embedding\\s+vector_cosine_ops\\s*\\)' V11__post_embedding.sql returns at least one match"
  - "V11 creates the embedding_metadata singleton table per docs/spec/llm.md §Embedding pipeline 'Model identity guard. The active embedding model's identifier and vector dimensionality are stored in a singleton metadata row on first use.' Shape: embedding_metadata(model_identifier TEXT NOT NULL, dimension INT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()), singleton-enforced via CREATE UNIQUE INDEX ON embedding_metadata ((TRUE)) — grep -E 'CREATE TABLE\\s+embedding_metadata\\s*\\(' V11__post_embedding.sql returns at least one match AND grep -E 'CREATE UNIQUE INDEX.*embedding_metadata.*\\(\\(TRUE\\)\\)|CREATE UNIQUE INDEX.*embedding_metadata.*\\(\\(true\\)\\)' V11__post_embedding.sql returns at least one match"
  - "V11 INSERTs the default embedding_metadata row matching the laptop/vps profile (model_identifier='nomic-embed-text', dimension=768 per docs/design/05-llm-and-embeddings.md §5.5 Model and dimension by profile) — grep -E 'INSERT\\s+INTO\\s+embedding_metadata' V11__post_embedding.sql returns at least one match AND grep -E 'nomic-embed-text' V11__post_embedding.sql returns at least one match AND grep -E '\\b768\\b' V11__post_embedding.sql returns at least one match"
  - "V11 grants Collector write access to post_embedding (it will run the embedding worker in M1-034b) — grep -E 'GRANT\\s+SELECT\\s*,\\s*INSERT\\s+ON\\s+post_embedding\\s+TO\\s+infochat_collector' V11__post_embedding.sql returns at least one match"
  - "V11 grants Provider read access to post_embedding (for later T1-F /summary and T2-D chat-agent semantic-similarity queries) — grep -E 'GRANT\\s+SELECT\\s+ON\\s+post_embedding\\s+TO\\s+infochat_provider' V11__post_embedding.sql returns at least one match"
  - "V11 grants Collector SELECT, INSERT, UPDATE on embedding_metadata (Collector enforces the startup guard in M1-034b and may UPDATE on operator override) — grep -E 'GRANT\\s+SELECT\\s*,\\s*INSERT\\s*,\\s*UPDATE\\s+ON\\s+embedding_metadata\\s+TO\\s+infochat_collector' V11__post_embedding.sql returns at least one match"
  - "V11 grants Provider read access to embedding_metadata (for diagnostic) — grep -E 'GRANT\\s+SELECT\\s+ON\\s+embedding_metadata\\s+TO\\s+infochat_provider' V11__post_embedding.sql returns at least one match"
  - "V11 does NOT create post_entity, post_reference, or any LinkingJob-related tables (T2 territory per the session-grouping-plan T1-D row) — grep -E 'CREATE TABLE\\s+(post_entity|post_reference)' V11__post_embedding.sql returns zero matches"
  - "infochat-llm-adapter/src/main/resources/prompts/tagger.md exists and follows the JSON-primary template at docs/design/05-llm-and-embeddings.md §5.4.2: instructs the model to assign 1..4 tags from a controlled vocabulary; demands JSON output {\"tags\": [\"tag1\",\"tag2\"]}; lists the vocabulary inline via Mustache/Qute iteration; wraps the post body in the per-call random delimiter — grep -E '\\{\"tags\":' tagger.md returns at least one match AND grep -E '<<<UNTRUSTED_CONTENT' tagger.md returns at least one match AND grep -E '\\{#tags\\}|\\{\\{#tags\\}\\}' tagger.md returns at least one match"
  - "infochat-llm-adapter/src/main/resources/prompts/tagger-fallback.md exists and follows the line-oriented fallback template (single 'TAGS: tag1, tag2, tag3' line, no JSON; designed for small models that struggle with JSON mode) — grep -E 'TAGS:' tagger-fallback.md returns at least one match AND grep -E '\\{#tags\\}|\\{\\{#tags\\}\\}' tagger-fallback.md returns at least one match AND grep -E '<<<UNTRUSTED_CONTENT' tagger-fallback.md returns at least one match"
  - "TagVocabulary.java is an @ApplicationScoped CDI bean that loads the controlled vocabulary from the tag table (seeded in M1-008b) into an immutable Set<String> ONCE at startup. The loaded names are normalized to NFC + Locale.ROOT lower-case + character class [a-z0-9][a-z0-9-]{0,47} (the tag stored form per docs/spec/schema.md §Sources and tags / docs/design/02-schema.md §2.2.2) so the membership check is byte-equal against the tagger output's same-rule normalization — grep -E 'class\\s+TagVocabulary' TagVocabulary.java returns at least one match AND grep -E 'SELECT\\s+name\\s+FROM\\s+tag|FROM\\s+tag\\s+ORDER' TagVocabulary.java returns at least one match AND grep -E 'Locale\\.ROOT|toLowerCase\\s*\\(\\s*Locale' TagVocabulary.java returns at least one match"
  - "TaggerWorker.java is a Collector-side @Scheduled polling worker (matching the M1-028 FetchScheduler / M1-033 Stage2Worker pattern). Pickup criteria: status='RAW' AND stage1_done=true AND (stage1_flagged=false OR stage2_done=true) AND tagger_done=false. Quarantined posts are excluded by the status='RAW' filter (Stage 2 INJ/MAL/UNK and Stage 1 watchdog fail-closed both set status='QUARANTINED') — grep -E 'class\\s+TaggerWorker' TaggerWorker.java returns at least one match AND grep -E \"status\\s*=\\s*'RAW'\" TaggerWorker.java returns at least one match AND grep -E 'tagger_done\\s*=\\s*FALSE|tagger_done\\s*=\\s*false' TaggerWorker.java returns at least one match AND grep -E '@Scheduled' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java invokes the M1-033 LlmRouter with ModelTask.TAGGER and scope language 'en' (Tagger output is fixed-vocabulary tag names, not user-visible prose; scope language doesn't drive the tagger) — grep -E 'ModelTask\\.TAGGER|router\\.forTask\\s*\\(\\s*TAGGER' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java's primary tagging path: load prompts/tagger.md, substitute the controlled vocabulary loaded from TagVocabulary + the per-call random {{id}} UUID + the post body + title; invoke provider.generate; parse strict JSON {\"tags\": [...]}; for each parsed tag normalize per the same NFC + Locale.ROOT lower-case + character class [a-z0-9][a-z0-9-]{0,47} rule used in TagVocabulary; check membership; keep valid tags, silently drop invalid per docs/spec/llm.md §Failure handling 'Partial-valid handling. ... the valid tags are kept and the invalid tags are silently dropped'. Records an INFO log entry naming the count of valid + invalid tags so a future operator alert on sustained high invalid rates has the data — grep -E 'tagger_partial_valid|tagger\\.partial_valid|valid tags.*invalid|tagger.*valid.*invalid' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java's three-surface fallback chain per docs/spec/security.md §Failure handling 'Tagger failure → fall back to source.bootstrap_tags, mark the post, throttled admin notify' AND docs/spec/llm.md §Failure handling (recap): (a) schema-violating output (JSON parse throws OR the parsed object lacks a 'tags' array) → retry once with tagger-fallback.md (different prompt because re-issuing the same JSON-mode prompt to the same model produces the same garbage); (b) zero valid tags after partial-valid handling (the JSON parsed but ZERO entries passed vocabulary validation) → retry once with the SAME primary prompt (vocabulary mismatch is a content issue, not a prompt-shape issue); (c) LLM unreachable / timeout → retry once with the SAME primary prompt (transient infrastructure issue). On second failure of any path: post.tags = source.bootstrap_tags AND post.tagger_fallback=true AND log WARN with canonical error_class='tagger.fallback_to_bootstrap'. Document the per-path retry choice in TaggerWorker's class JDoc — grep -E 'tagger_fallback|tagger\\.fallback_to_bootstrap' TaggerWorker.java returns at least one match AND grep -E 'bootstrap_tags|source\\.bootstrap_tags' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java's tagger_done=true UPDATE is the persistence cursor for the Tagger boundary per Invariant 5 (docs/spec/schema.md §Invariants 'the per-stage flags are the durable cursor'). UPDATE post SET tags=:tags, tagger_done=true, tagger_fallback=:fallback WHERE id=:post_id AND fetched_at=:fetched_at — the same statement writes both the tag array and the cursor flags atomically — grep -E 'tagger_done\\s*=\\s*TRUE|tagger_done\\s*=\\s*true' TaggerWorker.java returns at least one match AND grep -E 'tags\\s*=' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java's concurrency is bounded by infochat.llm.tagger.max-concurrency (laptop default 4 per docs/design/05-llm-and-embeddings.md §5.7); the bounded-concurrency shape matches M1-033's Stage 2 semaphore — grep -E 'infochat\\.llm\\.tagger\\.max-concurrency|tagger\\.maxConcurrency' TaggerWorker.java returns at least one match"
  - "application.properties under infochat-collector/src/main/resources is amended to add the tagger property surface (the embedding property surface lands in M1-034b). Required keys: infochat.llm.tagger.base-url, infochat.llm.tagger.api-key, infochat.llm.tagger.model (default llama3.1:8b for laptop per docs/design/05-llm-and-embeddings.md §5.7), infochat.llm.tagger.max-concurrency (default 4 for laptop per §5.7), infochat.llm.tagger.poll-interval (default 5s matching M1-028 FetchScheduler cadence — document choice in property comment) — grep -E 'infochat\\.llm\\.tagger\\.base-url' application.properties returns at least one match AND grep -E 'infochat\\.llm\\.tagger\\.model' application.properties returns at least one match AND grep -E 'infochat\\.llm\\.tagger\\.max-concurrency' application.properties returns at least one match"
  - "TaggerWorkerIT.java is a @QuarkusTest IT against real Postgres + a stub LlmProvider replacing the production provider for the test profile (@Alternative @Priority(MAX_VALUE) — same pattern as M1-033's Stage2WorkerIT). Seven @Test methods covering: (1) happy path — stub returns valid JSON {\"tags\":[\"security\",\"news\"]} where both are vocabulary members → post.tags=[\"security\",\"news\"], tagger_done=true, tagger_fallback=false; (2) partial-valid — stub returns {\"tags\":[\"security\",\"news\",\"NOTAVALIDTAG\"]} → post.tags=[\"security\",\"news\"], tagger_fallback=false, INFO log mentions partial-valid count; (3) zero-valid → bootstrap fallback (post.tags=<source.bootstrap_tags>, tagger_fallback=true); (4) schema-violating ('this is not json') → retry with fallback prompt; if retry returns 'TAGS: security, news', uses those (tagger_done=true, tagger_fallback=false); (5) total-fail (both prompts return garbage) → bootstrap fallback; (6) LLM unreachable (stub throws on every call) → retry once → bootstrap fallback; (7) status='QUARANTINED' post NOT picked up (tagger_done stays false) — grep -E '@Test' TaggerWorkerIT.java returns at least seven matches"
  - "mvn -B -pl infochat-collector -am verify exits 0; failsafe reports include TaggerWorkerIT — grep -rE 'Tests run: [1-9]' infochat-collector/target/failsafe-reports returns at least one new match for TaggerWorkerIT"
  - "mvn -B clean verify from the repo root exits 0; all prior tests (M1-003, M1-006, M1-007/007a/b/c, M1-008/008a/b/c, M1-009, M1-017, M1-022..M1-029, M1-032, M1-033) continue to pass alongside the new V11 migration and the Tagger pipeline. DbRoleMatrixIT (M1-006) continues to pass without modification — it asserts only role-presence + NOLOGIN, not a closed expected-grants list, so the new V11 GRANTs are non-breaking (this addresses the M1-034 clarity_check_at_abort TEST-CHANGES-AUTHORIZED warning). FlywayMigrationIT (M1-017) applies V11 alongside V1..V10 without edit"
  - "infochat-collector/src/main/resources/application.properties declares %test.quarkus.scheduler.start-mode=halted under the test profile so background @Scheduled ticks (TaggerWorker today, and future EmbeddingWorker / re-eval / chat-agent workers) do not pollute @QuarkusTest assertions on shared @ApplicationScoped beans during the test phase. Tests that need the scheduler to fire (HeartbeatSchedulerIT) inject io.quarkus.scheduler.Scheduler and call scheduler.resume() OR invoke the @Scheduled method directly — grep -E '%test\\.quarkus\\.scheduler\\.start-mode\\s*=\\s*halted' infochat-collector/src/main/resources/application.properties returns at least one match AND grep -E 'io\\.quarkus\\.scheduler\\.Scheduler|scheduler\\.resume\\s*\\(\\s*\\)|HeartbeatScheduler\\s*\\.\\s*tick|heartbeatScheduler\\.tick\\s*\\(' infochat-collector/src/test/java/io/infochat/collector/startup/HeartbeatSchedulerIT.java returns at least one match"
test_plan:
  adds:
    - infochat-collector/src/test/java/io/infochat/collector/eval/tagger/TaggerWorkerIT.java (@QuarkusTest IT against real Postgres + stub LlmProvider exercising happy / partial-valid / zero-valid / schema-violating / total-fail / LLM-unreachable / quarantined-exclusion paths)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-017 — V11 must apply cleanly alongside V1..V10)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006 — asserts role-presence + NOLOGIN, not a closed grants list; the new V11 GRANTs are non-breaking)
    - all M1-008a/b/c schema tests
    - all M1-022/023/024/025/026/029 ingest + SSRF tests
    - M1-027's three provider outbox ITs
    - M1-028's PostPersisterIT + OutboxRehydratorIT + FetchSchedulerIT
    - M1-032's Stage1PipelineIT + Stage1WatchdogIT + Stage1RegexSetTest
    - M1-033's LlmRouterTest + Stage2WorkerIT + LocalOnlyConflictStartupIT
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/security.md §DB roles
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/llm.md §Hardware profile contract
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Invariants
  - docs/spec/schema.md §Sources and tags
  - docs/spec/architecture.md §Pipelines
  - docs/design/01-architecture.md §1.3.4 Eval pipeline workers
  - docs/design/02-schema.md §2.2.2 tag
  - docs/design/02-schema.md §2.3.1 post
  - docs/design/02-schema.md §2.4.2 post_embedding
  - docs/design/02-schema.md §2.8 Embedding model migration
  - docs/design/05-llm-and-embeddings.md §5.4.2 Tagger
  - docs/design/05-llm-and-embeddings.md §5.5 Embeddings
  - docs/design/05-llm-and-embeddings.md §5.7 Profile defaults
  - docs/design/05-llm-and-embeddings.md §5.8 Failure handling per task
decision_refs:
  - D5
  - D22
  - D27
---

# M1-034a: Tagger pipeline + V11 (post_embedding + embedding_metadata)

## Context

First of two replacement tickets for the deferred M1-034 umbrella
(see `aborted_attempts:` on M1-034 for the split rationale). This
ticket lands:

1. **V11 Flyway migration** — creates `post_embedding` (partitioned
   by `fetched_at`, HNSW vector index) and the `embedding_metadata`
   singleton (seeded with the laptop/vps default). The schema is
   shipped here so M1-034b's `EmbeddingMetadataStartupGuard` has a
   row to compare against on first boot.
2. **The Tagger pipeline** — `TagVocabulary`, `TaggerWorker`, the
   two prompt files (`tagger.md` JSON-primary +
   `tagger-fallback.md` line-oriented retry), and the
   `infochat.llm.tagger.*` property surface.
3. **One IT** — `TaggerWorkerIT` exercising all three fallback
   surfaces (schema-violating / zero-valid / LLM-unreachable),
   partial-valid handling, the quarantined-exclusion filter, and
   the bootstrap-fallback audit flag.

Pipeline boundary written by this ticket:

```
status='RAW' AND stage1_done=true AND
  (stage1_flagged=false OR stage2_done=true) AND
  tagger_done=false
    → TaggerWorker (this ticket)
       ↓
status='RAW' AND tagger_done=true AND embedding_done=false
    → EmbeddingWorker (M1-034b)
```

M1-034b picks up where this ticket leaves off (post.tagger_done=true).

## Definition of Done

- **V11 migration** at
  `infochat-core/src/main/resources/db/migration/V11__post_embedding.sql`:
  - Creates `post_embedding(post_id UUID NOT NULL, embedding
    vector(768) NOT NULL, embedding_model TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (post_id,
    fetched_at)) PARTITION BY RANGE (fetched_at)`. The dimension
    is the laptop/vps default; pi (vector(384)) and remote-llm
    (vector(1536)) are operator-selected via alternative migration
    file or override — document in the migration header.
  - Creates at least one initial partition of `post_embedding`.
  - Creates the HNSW vector index `USING hnsw (embedding
    vector_cosine_ops) WITH (m=16, ef_construction=64)`. The pi
    profile's IVFFlat variant is an operator choice — document.
  - Creates `embedding_metadata(model_identifier TEXT NOT NULL,
    dimension INT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
    DEFAULT now())` with singleton enforcement via
    `CREATE UNIQUE INDEX ... ON embedding_metadata ((TRUE))`
    (the simpler shape from the original M1-034 implementation
    notes — no synthetic id column).
  - INSERTs the default row `(model_identifier='nomic-embed-text',
    dimension=768)` matching the laptop/vps embedding model.
  - Per-table GRANTs per `docs/spec/security.md` §DB roles:
    `GRANT SELECT, INSERT ON post_embedding TO infochat_collector`
    (Collector writes via M1-034b's EmbeddingWorker);
    `GRANT SELECT ON post_embedding TO infochat_provider`
    (Provider reads for future semantic-similarity queries);
    `GRANT SELECT, INSERT, UPDATE ON embedding_metadata TO
    infochat_collector` (Collector enforces the M1-034b startup
    guard; UPDATE on operator override);
    `GRANT SELECT ON embedding_metadata TO infochat_provider`
    (diagnostic).
  - **Does NOT** create `post_entity`, `post_reference`, or any
    LinkingJob tables (T2 territory).
- **`tagger.md`** under
  `infochat-llm-adapter/src/main/resources/prompts/` — JSON-primary
  template per `docs/design/05-llm-and-embeddings.md` §5.4.2:
  instructs the model to assign 1..4 tags from the controlled
  vocabulary; demands `{"tags": [...]}` JSON output; iterates the
  vocabulary inline via Qute (`{#tags}`) or Mustache (`{{#tags}}`);
  wraps the post body in
  `<<<UNTRUSTED_CONTENT id="{{id}}">>>...<<<END id="{{id}}">>>`
  with the per-call random `{{id}}` UUID.
- **`tagger-fallback.md`** — line-oriented retry template (single
  line `TAGS: tag1, tag2, tag3`, no JSON; designed for small
  models that struggle with JSON mode).
- **`TagVocabulary.java`** under
  `infochat-collector/src/main/java/io/infochat/collector/eval/tagger/`
  — `@ApplicationScoped` CDI bean. Loads `SELECT name FROM tag` once
  at startup into an immutable `Set<String>`. Loaded names are
  normalized using the **tag normalization rule** —
  **NFC + Locale.ROOT lower-case + character class
  `[a-z0-9][a-z0-9-]{0,47}`** (the tag stored form per
  `docs/spec/schema.md` §Sources and tags / `docs/design/02-schema.md`
  §2.2.2; this rule is **inlined here** rather than cross-referenced,
  addressing the M1-034 clarity SELF-CONTAINED-CHECK warning). The
  TaggerWorker output is normalized with the same rule so membership
  is byte-equal.
- **`TaggerWorker.java`** — Collector-side `@Scheduled` polling
  worker (matches M1-028 `FetchScheduler` and M1-033 `Stage2Worker`
  pattern). Pickup: `status='RAW' AND stage1_done=true AND
  (stage1_flagged=false OR stage2_done=true) AND tagger_done=false`.
  Quarantined posts excluded by the `status='RAW'` filter (Stage 2
  INJ/MAL/UNK and Stage 1 watchdog fail-closed both set
  `status='QUARANTINED'`). For each post:
  - Invokes `LlmRouter.forTask(TAGGER, "en")` to get the provider.
  - Loads `tagger.md`, substitutes the vocabulary, the per-call
    random UUID `{{id}}`, the post body + title.
  - Invokes `provider.generate(TAGGER, systemPrompt, userPrompt)`.
  - Parses reply as strict JSON `{"tags": [...]}`.
  - For each parsed tag: normalize per the tag normalization rule
    above, check `TagVocabulary` membership.
  - Keeps valid tags; silently drops invalid per the partial-valid
    rule. INFO log records the valid+invalid count.
  - **Three-surface fallback chain:**
    - Schema-violating (JSON parse fails or no `tags` array):
      retry once with `tagger-fallback.md` (different prompt — same
      garbage from same prompt is wasted effort).
    - Zero valid tags after partial-valid: retry once with the
      SAME primary prompt (vocabulary mismatch is a content issue,
      not prompt-shape).
    - LLM unreachable / timeout: retry once with the SAME primary
      prompt (transient infrastructure issue).
    - On second failure of any path:
      `post.tags = source.bootstrap_tags`,
      `post.tagger_fallback = true`, log WARN with canonical
      `error_class='tagger.fallback_to_bootstrap'`.
    - Document the per-path retry choice in the class JDoc.
  - UPDATE `post SET tags=:tags, tagger_done=true,
    tagger_fallback=:fallback WHERE id=:post_id AND
    fetched_at=:fetched_at` — atomic write of tags + cursor flags.
  - Concurrency bounded by `infochat.llm.tagger.max-concurrency`
    (laptop default 4 per `docs/design/05-llm-and-embeddings.md`
    §5.7).
- **`application.properties`** amended with:
  `infochat.llm.tagger.{base-url, api-key, model, max-concurrency,
  poll-interval}` with laptop-profile defaults
  (model=`llama3.1:8b`, max-concurrency=4, poll-interval=5s).
- **`TaggerWorkerIT.java`** — `@QuarkusTest` against real Postgres
  + a stub `LlmProvider` selected via `@Alternative
  @Priority(MAX_VALUE)` for the test profile. Seven `@Test` methods
  covering all six tagger paths plus the quarantined-exclusion
  filter (see acceptance items for the per-test contract).
- `mvn -B clean verify` from the repo root exits 0.

## Implementation notes

- **Migration version is V11.** V10 lands in M1-032. If a later
  authoring session lands an unrelated migration before this
  ticket starts, re-grep the migration directory at
  `/m1-tick start` time and slide this migration to V12 (and
  rename file).
- **Singleton enforcement on `embedding_metadata`.** Use
  `CREATE UNIQUE INDEX ON embedding_metadata ((TRUE))` — the
  predicate evaluates to a constant per row so only one row can
  satisfy uniqueness. Simpler than a synthetic `id INT DEFAULT 1
  + UNIQUE(id) + CHECK(id=1)` column.
- **Profile-specific dimensions.** V11 ships
  `vector(768)`. The pi profile's `vector(384)` and the remote-llm
  profile's `vector(1536)` are operator territory (alternative
  migration file or operator-issued `ALTER TABLE` at deploy time
  per `docs/design/02-schema.md` §2.8). Document the operator
  path in the V11 header comment.
- **HNSW vs IVFFlat index.** V11 ships HNSW (laptop/vps/
  remote-llm). The pi profile's IVFFlat is also operator
  territory. Document in the migration header.
- **Tagger pickup invocation shape.** `@Scheduled` polling
  matches the M1-028 `FetchScheduler` and M1-033 `Stage2Worker`
  pattern; poll interval 5s on laptop (documented in
  application.properties).
- **Tagger's two retry shapes.** Schema-violating retries with a
  DIFFERENT (line-oriented) prompt because re-issuing the same
  JSON-mode prompt to the same small model tends to produce the
  same garbage. Zero-valid-after-validation AND LLM-unreachable
  retry with the SAME prompt because those failure modes are
  unrelated to prompt shape (vocabulary mismatch is a content
  issue; unreachability is infrastructure). Document the choice
  in `TaggerWorker.java`'s JDoc.
- **Tag normalization rule inlined.** The full rule (`NFC +
  Locale.ROOT lower-case + character class
  [a-z0-9][a-z0-9-]{0,47}`) is in the DoD here, addressing
  M1-034's clarity SELF-CONTAINED-CHECK warning. The same rule
  applies in `TagVocabulary` (loaded vocabulary) and in
  `TaggerWorker` (parsed tagger output) so membership is
  byte-equal. If M1-008b already shipped a normalization helper
  in `infochat-core`, reuse it (one less new file); otherwise
  inline a small static helper in `TaggerWorker.java` and add a
  TODO comment to extract to `infochat-core` later. The reviewer's
  negative-space check should not flag a missing `infochat-core`
  file if the helper exists already.
- **Stub provider in the IT.** A single shared
  `StubLlmProvider` at
  `infochat-collector/src/test/java/io/infochat/collector/eval/testing/`
  is selected via `@Alternative @Priority(Integer.MAX_VALUE)
  @ApplicationScoped` for every IT in the module that @Inject-s
  `LlmProvider`. Per-test scenario configures the stub's response
  (valid JSON, partial-valid JSON, zero-valid JSON, garbage, throws).
  This file lands here in M1-034a — the first ticket with a second
  consumer — and replaces M1-033's nested `Stage2WorkerIT.TestStubLlmProvider`
  (which is enclosed in a package-private outer class and therefore
  not importable from `io.infochat.collector.eval.tagger`; a second
  @Alternative @Priority(MAX) bean in the same module triggered ArC
  AmbiguousResolutionException, see the budget-breach escalation's
  `reviewer_verdict_excerpt`). Public top-level placement means
  future IT classes (M1-034b EmbeddingWorker, T2 chat-agent) can
  consume it without re-declaring a stub. The choice to extract here
  rather than widen `Stage2WorkerIT` to `public` keeps test classes
  package-private (the Quarkus + JUnit 5 default) while making the
  test-utility class explicitly part of the test classpath surface.
- **Mustache vs Qute templating.** Qute is built-in to Quarkus
  and supports the `{#tags}...{/tags}` iteration syntax the
  prompts need. Use Qute, avoid the Mustache dependency.
  Document the choice in the prompt header.
- **DbRoleMatrixIT non-breakage.** The IT (M1-006) asserts only
  role-presence + NOLOGIN attribute; it does NOT assert a closed
  expected-grants list. The new V11 GRANTs are non-breaking
  (this addresses the M1-034 clarity TEST-CHANGES-AUTHORIZED
  warning; verified by reading
  `infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java`
  at authoring time of this replacement ticket).
- **Halt the Quarkus scheduler in the test profile.** Round-1 of this
  ticket shipped a diff that the reviewer APPROVED, but the commit-step
  `mvn verify` safety re-run (high-complexity tickets re-execute verify
  before commit) caught a 25% structural flake: TaggerWorker's
  `@Scheduled` poll fires every 5s in the background during
  @QuarkusTest method bodies and calls the shared @ApplicationScoped
  `StubLlmProvider` that production code resolves to. The race surfaces
  in Stage2WorkerIT's `callCount` assertion (asserts 2; observes 4 when
  the scheduler tick adds two of its own calls). The fix is a one-line
  property in this module's `application.properties`:
  `%test.quarkus.scheduler.start-mode=halted` — halts the Quarkus
  scheduler at boot so no `@Scheduled` tick fires automatically. Tests
  that need a tick (HeartbeatSchedulerIT from M1-009) inject
  `io.quarkus.scheduler.Scheduler` and call `scheduler.resume()` (or
  invoke the @Scheduled method directly). Preferred over a per-worker
  poll-interval override because every future @Scheduled
  eval-pipeline worker (M1-034b EmbeddingWorker, T2 chat-agent re-eval,
  ...) would otherwise need the same patch and forgetting one
  re-introduces the same 5s-cadence flake silently. The new property
  carries a block comment explaining the cross-worker rationale so a
  future contributor reading `application.properties` sees the
  invariant without having to dig through ticket history.

## Big-picture notes

- **M1-034b picks up at `tagger_done=true`.** This ticket's
  TaggerWorker writes the `tagger_done=true` cursor flag; M1-034b's
  EmbeddingWorker uses that as its pickup criterion. The two
  tickets share no class-level coupling — the contract is the
  state-machine flag on `post`.
- **The `embedding_metadata` seed row is the load-bearing handoff
  to M1-034b.** M1-034b's `EmbeddingMetadataStartupGuard` reads
  this row on first Collector boot, compares to the configured
  `infochat.embeddings.model` and `infochat.embeddings.dimension`,
  refuses startup on mismatch unless
  `infochat.embeddings.allow-model-change=true`. If V11 ships
  without the seed row OR with a model/dimension different from
  M1-034b's default property values, M1-034b's first boot will
  fatal-fail. The shipped seed (`nomic-embed-text`, 768) must
  match M1-034b's default `infochat.embeddings.model` and
  `infochat.embeddings.dimension`.
- **No post_embedding rows written here.** The table is created
  but unpopulated. M1-034b's EmbeddingWorker is the sole writer.
  A SELECT against `post_embedding` between M1-034a-merge and
  M1-034b-merge returns zero rows; this is expected.
- **Tagger fallback chain audit trail.** `tagger_fallback=true`
  is the audit flag — admins query "show me posts whose tags
  came from bootstrap fallback over the last 24h" to spot
  sustained LLM outages or vocabulary-mismatch issues. The
  WARN log with `error_class='tagger.fallback_to_bootstrap'`
  feeds the future T2-G throttled admin notifier.
- **Partial-valid handling preserves useful tags.** Per
  `docs/spec/llm.md` §Failure handling: losing the whole tag
  list because one tag is out-of-vocab would degrade tagging
  quality across deployments where smaller models occasionally
  emit one bad tag in an otherwise-clean list. The per-post
  valid+invalid counter (INFO log) feeds future T2 observability.

## Out-of-scope expansion

- **Concrete EmbeddingProvider impl.** M1-034b ships
  `OpenAiCompatibleEmbeddingProvider`. V11 here seeds
  `embedding_metadata` so the M1-034b startup guard has a row
  to compare; the provider class itself is M1-034b's diff.
- **EmbeddingMetadataDao, EmbeddingMetadataStartupGuard,
  EmbeddingWorker, ReadyPromoter, pg_notify('new_post', ...).**
  All M1-034b.
- **Any post_embedding INSERT.** V11 creates the table; no code
  in this ticket writes a row.
- **Stage 1 / Stage 2 / EntityExtractor / Re-eval / quarantine
  commands / TranslationProvider / chat-agent.** Per the
  `out_of_scope:` list.
- **V1..V10 migration changes.** Frozen. V11 is purely additive.
- **infochat-provider module changes.** This ticket is
  collector + core-migration + llm-adapter only.
- **partition_pruner job.** T2 territory. V11 creates the
  initial partition; nightly DROP-PARTITION schedule lives in T2.
- **Prometheus metric emit for `tagger_partial_valid_total`.**
  T2 observability ticket. This ticket logs at INFO/WARN with
  canonical `error_class` strings.

## Authorized test changes

- **`infochat-collector/src/test/java/io/infochat/collector/eval/stage2/Stage2WorkerIT.java`**
  (M1-033) — authorized scope (added during the budget-breach refine,
  see `revisions:` 2026-05-17 entry):
  - Delete the nested `public static class TestStubLlmProvider` block
    (the @Alternative @Priority(MAX) @ApplicationScoped stub) and
    rewrite the `(TestStubLlmProvider) llmProvider` cast sites at the
    @Inject `LlmProvider` field consumers to
    `(StubLlmProvider) llmProvider`.
  - Add the import
    `io.infochat.collector.eval.testing.StubLlmProvider`.
  - All nine @Test methods (28a-28i) keep their behavior, ordering,
    and assertions byte-equal — only the test-utility class moves.
    M1-033's behavioral contract (nine scenarios +
    release-on-stage2-failure flag toggling at items 28h/28i) is
    preserved. No assertions, no @Order values, no helper methods,
    no field declarations other than the cast-site changes above
    are modified.
  - DbRoleMatrixIT (M1-006) and FlywayMigrationIT (M1-017) continue
    to pass against the new V11 GRANTs without edit per the
    Implementation notes (asserts role-presence + NOLOGIN, not a
    closed grants list).

- **`infochat-collector/src/test/java/io/infochat/collector/eval/testing/StubLlmProvider.java`**
  (NEW shared test-utility class) — added during the budget-breach
  refine (see `revisions:` 2026-05-17 entry). Public top-level
  `@Alternative @Priority(Integer.MAX_VALUE) @ApplicationScoped`
  bean implementing `LlmProvider`. Same shape as the prior nested
  Stage2 stub (reset / setNextResponse / setNextResponses / failAll /
  callCount + an `LlmResponse generate(ModelTask, String, String)`
  override). Replaces both M1-033's nested `TestStubLlmProvider` and
  M1-034a's in-progress nested `CannedStubLlmProvider`. Forward-
  compatible with M1-034b's EmbeddingWorker IT and any future
  eval-pipeline or chat-agent IT @Inject-ing `LlmProvider`.

- **`infochat-collector/src/test/java/io/infochat/collector/startup/HeartbeatSchedulerIT.java`**
  (M1-009) — authorized scope (added during the premise-fail refine,
  see `revisions:` 2026-05-17 entry):
  - Inject `io.quarkus.scheduler.Scheduler` and call
    `scheduler.resume()` from `@BeforeEach` so the @Scheduled tick
    fires for this IT (which the `%test.quarkus.scheduler.start-mode=halted`
    property otherwise suppresses), OR invoke `HeartbeatScheduler.tick()`
    directly — whichever is the minimum-change variant against the
    in-tree shape.
  - No assertions, no @Test method names, no helper methods are
    modified. The original M1-009-asserted contract — `heartbeat.last_seen_at`
    advances after a tick — is preserved; only the trigger source
    changes from implicit-background-tick to explicit
    `scheduler.resume()` / direct tick invocation.
  - The collector module's `FetchSchedulerIT` (M1-028) already calls
    `fetchScheduler.tickOnce(row)` directly and is not affected by
    `start-mode=halted`; it is intentionally left untouched and not
    added to `files_scope`.
  - The provider module's `HeartbeatSchedulerIT` is governed by the
    provider's own `application.properties` and is not affected by
    this collector-only change; it is intentionally left untouched
    and not added to `files_scope`.

## Alternatives considered

- **Bundle the EmbeddingProvider impl + EmbeddingMetadata startup
  guard here too.** Rejected. Splitting the original M1-034 at
  the Tagger | Embedding+ReadyPromoter boundary (the rationale
  for the M1-034 abort) requires the EmbeddingProvider to live
  with its first consumer (the EmbeddingWorker in M1-034b). The
  M1-033 pattern landed `OpenAiCompatibleProvider` WITH
  `Stage2Worker`, not as a separate one-class ticket. The
  EmbeddingProvider in M1-034b matches that shape.
- **Ship V11 in M1-034b instead.** Rejected. The schema needs
  to land before the startup guard runs (the guard reads
  `embedding_metadata`); if V11 lives in M1-034b, its first
  boot would race the Flyway migration. Cleaner: V11 lands
  here in 034a (with the table + seed row); 034b reads/updates
  the existing row.
- **Use Mustache instead of Qute for prompt templating.**
  Acceptable but worse: Qute is built-in to Quarkus, no
  dependency add needed. Use Qute.
- **Inline the TagVocabulary in TaggerWorker.java instead of
  a separate class.** Rejected on cohesion grounds —
  `TagVocabulary` is a @ApplicationScoped singleton with its
  own startup loading lifecycle; conflating it with the worker
  obscures both. Two classes, single responsibility each.

## Implementation outline (M1-034a, generated by Plan subagent on 2026-05-17)

### Files to touch (7 of 7)
- create: `infochat-core/src/main/resources/db/migration/V11__post_embedding.sql` — Flyway V11: `post_embedding` partitioned table + HNSW index + `embedding_metadata` singleton seeded with `(nomic-embed-text, 768)` + per-role GRANTs. Header comment documents the pi/remote-llm operator overrides (vector dimension + IVFFlat index alternative).
- create: `infochat-llm-adapter/src/main/resources/prompts/tagger.md` — Qute JSON-primary prompt template per design §5.4.2; iterates vocabulary with `{#tags}…{/tags}`; wraps body in `<<<UNTRUSTED_CONTENT id="{id}">>>…<<<END id="{id}">>>`; demands `{"tags": [...]}` output.
- create: `infochat-llm-adapter/src/main/resources/prompts/tagger-fallback.md` — Qute line-oriented retry template; demands single `TAGS: tag1, tag2, tag3` line; same vocabulary iteration + delimiter wrapper.
- create: `infochat-collector/src/main/java/io/infochat/collector/eval/tagger/TagVocabulary.java` — `@ApplicationScoped` CDI bean; `@PostConstruct` loads `SELECT name FROM tag` once into an immutable `Set<String>`; exposes `boolean contains(String normalized)` + `Set<String> names()` (for prompt rendering); applies the inlined tag normalization rule (NFC + `Locale.ROOT.toLowerCase` + regex `^[a-z0-9][a-z0-9-]{0,47}$`) on loaded names for byte-equal membership.
- create: `infochat-collector/src/main/java/io/infochat/collector/eval/tagger/TaggerWorker.java` — `@ApplicationScoped` + `@Scheduled(every = "${infochat.llm.tagger.poll-interval}")` poller; pickup SQL `status='RAW' AND stage1_done=true AND (stage1_flagged=false OR stage2_done=true) AND tagger_done=false`; invokes `LlmRouter.forTask(TAGGER, "en")`; three-surface fallback chain documented in class JDoc; bounded by `Semaphore(infochat.llm.tagger.max-concurrency)`; atomic UPDATE writes `tags`, `tagger_done=true`, `tagger_fallback`; inlines `normalizeTag` helper with TODO referencing the shared T1-D extraction tracked in `BootstrapLoader`.
- modify: `infochat-collector/src/main/resources/application.properties` — append tagger property surface keys (`infochat.llm.tagger.base-url`, `.api-key`, `.model`, `.max-concurrency`, `.poll-interval`) at base + per-profile (`%laptop`, `%vps`, `%pi`, `%remote-llm`) per design §5.7 (laptop: `llama3.1:8b`, concurrency 4, poll 5s); comments explain the 5s cadence (matches `FetchScheduler`/`Stage2Worker`).
- create: `infochat-collector/src/test/java/io/infochat/collector/eval/tagger/TaggerWorkerIT.java` — `@QuarkusTest` IT against real Postgres + `TestStubLlmProvider` (`@Alternative @Priority(Integer.MAX_VALUE)` scoped to test); seven `@Test` methods (see Tests).

### Tests
- add: `infochat-collector/src/test/java/io/infochat/collector/eval/tagger/TaggerWorkerIT.java` — covers acceptance items 27 (seven scenarios) + 28 (failsafe report includes IT):
  - `tagsHappyPathPersistsValidVocabularyTags()` — stub returns `{"tags":["security","news"]}`; assert `post.tags=["security","news"]`, `tagger_done=true`, `tagger_fallback=false`.
  - `tagsPartialValidDropsInvalidAndKeepsValid()` — stub returns `{"tags":["security","news","NOTAVALIDTAG"]}`; assert kept `["security","news"]`, `tagger_fallback=false`, INFO log mentions partial-valid count.
  - `zeroValidTagsFallsBackToBootstrap()` — stub returns `{"tags":["NOTAVALIDTAG"]}` twice (primary + same-prompt retry); assert `post.tags = source.bootstrap_tags`, `tagger_fallback=true`, WARN log with `error_class='tagger.fallback_to_bootstrap'`.
  - `schemaViolatingRetriesWithFallbackPromptThenUsesIt()` — primary returns `"this is not json"`, retry with `tagger-fallback.md` returns `"TAGS: security, news"`; assert tags persisted, `tagger_fallback=false`.
  - `totalFailureOnBothPromptsFallsBackToBootstrap()` — primary garbage, fallback garbage; assert bootstrap fallback + WARN log.
  - `llmUnreachableRetriesOnceThenBootstrapFallback()` — stub throws on every call; assert one retry attempt, then bootstrap fallback.
  - `quarantinedPostIsNotPickedUp()` — seed a `status='QUARANTINED'` post that otherwise matches; assert `tagger_done` stays `false`, stub never invoked.
- modify (preservation only, no edits): `DbRoleMatrixIT` (M1-006) and `FlywayMigrationIT` (M1-017) continue to pass against V11 unchanged — confirmed by ticket body §"Authorized test changes" item: "(none — this ticket adds one new test file…No pre-existing tests are modified.)" Authorization present.

### Cross-cutting concerns
- **Tag normalization rule must be byte-identical at every site.** `BootstrapLoader.normalizeTag` (already in tree) does `NFC + Locale.ROOT.toLowerCase`. `TagVocabulary` and `TaggerWorker` must apply the SAME rule (plus the `^[a-z0-9][a-z0-9-]{0,47}$` character-class validation that the `tag` table's CHECK constraint enforces) so set membership is byte-equal. Diverging on this rule silently breaks partial-valid handling.
- **Per-stage flags are the durable cursor (Invariant 5).** The atomic UPDATE writing `tags + tagger_done + tagger_fallback` in one statement is non-negotiable; splitting into two UPDATEs creates a crash window where `tagger_done=true` but `tags` is empty.
- **Pickup predicate must exclude QUARANTINED.** `status='RAW'` is load-bearing: Stage 1 watchdog fail-closed and Stage 2 INJ/MAL/UNK both set `status='QUARANTINED'`. Filtering on `tagger_done=false` alone would re-process quarantined posts.
- **No `post_embedding` INSERT here.** Per out-of-scope: V11 creates the table but nothing in this ticket writes a row. The EmbeddingWorker in M1-034b is the sole writer; conflating responsibilities would void the M1-034 split rationale.
- **`embedding_metadata` seed values are the M1-034b handoff.** The shipped row `(nomic-embed-text, 768)` MUST match M1-034b's default `infochat.embeddings.model` and `infochat.embeddings.dimension`; mismatch would fatal-fail M1-034b's startup guard on first boot.
- **Determinism boundary.** Tagger is on the LLM-evaluation side of the determinism boundary (`docs/spec/llm.md`); vocabulary lookup + partial-valid filtering are deterministic Java, but the tag set the LLM emits is non-deterministic — the per-call random UUID `{id}` in the delimiter is the prompt-injection mitigation, not a determinism mechanism.
- **Plain-text formatting / no scope leakage.** Tagger never produces user-visible prose; scope language is hardcoded to `"en"` for the LLM router call. No per-(user, scope) state is touched.
- **No new SPI surfaces.** The M1-007b `LlmProvider` / `EmbeddingProvider` / `ModelTask` SPI is frozen; `TaggerWorker` consumes `ModelTask.TAGGER` (already in the enum) via `LlmRouter` (already shipped in M1-033).
- **GRANTs follow the §DB roles least-privilege model.** Collector: SELECT/INSERT on `post_embedding`, SELECT/INSERT/UPDATE on `embedding_metadata`. Provider: SELECT only on both. No grants for `infochat_admin` (operator uses superuser for raw SQL per §DB roles).

### Implementation order
1. **V11 migration first.** The Flyway baseline must apply before any Quarkus boot, otherwise `FlywayMigrationIT` and any IT touching the DB fails at startup. Confirm no migration was added between M1-033 and now (re-grep `migration/` directory at start time; if V11 was taken, slide this to V12 and rename — per Implementation note "Migration version is V11" caveat).
2. **`TagVocabulary` second.** Pure read-side bean with no LLM dependency — landing it before `TaggerWorker` lets `TagVocabulary` compile and exposes the `contains()` / `names()` API surface the worker depends on.
3. **`tagger.md` + `tagger-fallback.md` third.** Resource files with no Java compile cost; landing them now lets `TaggerWorker` reference the classpath paths as constants.
4. **`TaggerWorker` fourth.** The bulk of the logic; depends on (1) for the pickup SQL columns (`tagger_done`, `tagger_fallback` already exist from M1-008b; verify), (2) for vocabulary membership, (3) for prompt resource paths, and on the existing `LlmRouter` + `LlmProvider` SPI from M1-033.
5. **`application.properties` fifth.** Adding the `infochat.llm.tagger.*` keys after `TaggerWorker` is written ensures the `@ConfigProperty` injection sites + `@Scheduled` interval references are known; landing properties earlier risks orphan-key warnings until the worker references them.
6. **`TaggerWorkerIT` sixth.** Real Postgres + stub provider; depends on all of (1)-(5) for the seven scenarios to compile and execute. The stub provider's `@Alternative @Priority(Integer.MAX_VALUE)` is scoped to the test source set so it doesn't shadow production `OpenAiCompatibleProvider`.
7. **Run `mvn -B clean verify` from repo root last.** Per acceptance item 29 — confirms V11 applies cleanly to `FlywayMigrationIT` alongside V1..V10, `DbRoleMatrixIT` still passes against the new GRANTs (no edit needed — it asserts role-presence + NOLOGIN, not a closed grants list), and no M1-003..M1-033 regression.

Wrong-order risks: (a) writing `TaggerWorker` before V11 → its INSERT/UPDATE references to `post.tagger_done` would compile but `FlywayMigrationIT` would fail to apply if a migration mistake breaks earlier ITs; (b) writing the IT before the worker → cannot run; (c) deferring `application.properties` to last → `@Scheduled(every = "${infochat.llm.tagger.poll-interval}")` resolves to missing-property failure at IT boot.

### Risks
- **`files_budget: 7` is exactly tight.** No room for a separate `TagNormalizer` helper class in `infochat-core` even though the existing `BootstrapLoader.normalizeTag` is marked "Temporary inline placement; T1-D's…shared helper." Per Implementation notes the ticket explicitly authorizes a second inlined copy in `TaggerWorker` with a TODO. If the developer feels strongly about extracting, that is scope drift — escalation: `refine` (request +1 file for extraction).
- **`@Scheduled` + `Semaphore`-bounded concurrency interaction.** `@Scheduled` invocations are serialized per method by default in Quarkus; the spec wants bounded concurrency across in-flight tagger calls. The Stage2Worker pattern uses a `@Scheduled` poll that enqueues to an executor + acquires permits in worker threads. Matching that pattern matters; deviating risks single-threaded effective concurrency. If the existing `FetchScheduler` / `Stage2Worker` shape doesn't carry over cleanly, escalation: `refine`.
- **`vector(768)` requires the pgvector extension to be loaded.** V1 already does `CREATE EXTENSION vector`; V11 depends on that. If a future test profile or alternative deployment strips the extension, V11 fails. No action — verify V1 still declares the extension at start time.
- **HNSW build cost on first deploy.** `WITH (m=16, ef_construction=64)` is the design-mandated tuning. Empty partition at migration time means index build is O(1); no risk for V11 itself, but documenting in the header that the operator should expect rebuild cost on profile-switch migrations is the spec contract.
- **No risk requires `decompose`/`defer`/`spec-amend` at this stage.** The split from M1-034 already happened; this ticket is sized correctly for `complexity: high` + `round_cap: 3`.

### Out-of-scope (echoed from ticket)
- any Stage 1 HTML sanitizer / Unicode normalization / prompt-injection regex set / watchdog / placeholder-id generation / quarantine-row insertion (M1-032 territory — consumed unchanged)
- any Stage 2 LLM judge call / verdict handling / `stage2_done` advance / `OpenAiCompatibleProvider` / `LlmRouter` (M1-033 territory — `LlmRouter` CONSUMED for TAGGER task)
- any concrete `EmbeddingProvider` impl (`OpenAiCompatibleEmbeddingProvider`) — M1-034b
- any `EmbeddingMetadataDao` / `EmbeddingMetadataStartupGuard` / `EmbeddingWorker` / `ReadyPromoter` / `pg_notify('new_post', ...)` — M1-034b
- any `post_embedding` INSERT — table created here in V11, EmbeddingWorker in M1-034b is sole writer
- any `EntityExtractor` / `post_entity` / `post_reference` / `LinkingJob` — T2
- any re-evaluation job / attempt counter / `QUARANTINED → NEEDS_REVIEW` transition — T2-G
- any throttled admin notifier wiring — T2-G; fallback path logs canonical `error_class` strings
- any LLM output sanitizer — T1-F (tagger output validated against vocabulary, invalid dropped)
- any change to V1..V10 Flyway migrations (V11 is purely additive)
- any change to M1-007b `LlmProvider` / `EmbeddingProvider` / `ModelTask` SPI surfaces (frozen)
- any `infochat-provider` module change
- any `partition_pruner` job — T2
- any embedding-model migration script (`scripts/reembed.sh`)
- any Prometheus/Micrometer metric emit
