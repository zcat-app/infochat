---
id: M1-033
title: Stage 2 LLM judge + first OpenAI-compatible LlmProvider + (ModelTask, scope_language) router
status: done
created: 2026-05-16
last_updated: 2026-05-16
blocked_by:
  - M1-007b
  - M1-032
files_budget: 13
files_scope:
  - infochat-llm-adapter/pom.xml
  - infochat-llm-adapter/src/main/java/io/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/io/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/main/java/io/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/main/resources/prompts/security-judge.md
  - infochat-collector/src/main/java/io/infochat/collector/eval/stage1/Stage1Worker.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/stage2/Stage2Worker.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/stage2/Stage2VerdictHandler.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/stage2/StartupReleaseOnStage2FailureWarn.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-llm-adapter/src/test/java/io/infochat/llm/routing/LlmRouterTest.java
  - infochat-collector/src/test/java/io/infochat/collector/eval/stage2/Stage2WorkerIT.java
  - infochat-collector/src/test/java/io/infochat/collector/eval/stage2/LocalOnlyConflictStartupIT.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to Stage 1 deterministic security logic — the regex set, HTML sanitizer integration, prompt-injection patterns, watchdog implementation, placeholder-id generation, or quarantine-row insertion code (M1-032 territory — Stage 2 fires ONLY on Stage 1 hits per docs/spec/security.md §Ingest pipeline; the Stage1Pipeline + Stage1RegexSet + PlaceholderIds + QuarantineDao classes are consumed unchanged). **The Stage1Worker.java edit in files_scope is BOUNDED to the in-process Stage 2 hand-off** — (a) injecting `Stage2Worker` as a constructor dependency, and (b) consuming the `Stage1Result` returned from `stage1Pipeline.process(...)` and conditionally invoking `stage2Worker.judge(result)` (or equivalent) when `result.flagged() && !result.quarantinedByWatchdog()`. No other Stage1Worker behavior may change — the existing `stage1Done` short-circuit at line 87, the SQL post-load query, and the watchdog/sanitizer code paths are preserved verbatim. The Stage 1 → Tagger hand-off (currently absent — `stage1Pipeline.process` returns and Stage1Worker exits) remains M1-034's concern.
  - any Tagger LLM call, controlled-vocabulary validation, partial-valid handling, bootstrap-tags fallback, tagger_done advance, or tagger_fallback flag — M1-034 territory; Stage 2 advances stage2_done (and optionally stage2_failed) but does NOT touch tagger_done
  - any EmbeddingProvider impl, embedding worker, post_embedding table, dimensionality guard, or model-identity startup guard — M1-034 territory
  - any post.status → READY UPDATE, post.ready_at set, post.status_changed_at advance for READY, or pg_notify('new_post', …) emit — M1-034 territory at Stage 5
  - any EntityExtractor (Stage 3 in docs/design/01-architecture.md §1.3.4), post_entity table, post_reference table, or LinkingJob (§1.3.5) — T2 territory. T1-D's pipeline skips stage 3 (entity extraction) entirely per session-grouping-plan §Tier 1 ("Stage 1 deterministic security, LLM + Stage 2, tagger + embedding" — entity extraction not enumerated). The pipeline goes S1 → (S2 if S1 hit) → Tagger → Embedding → READY
  - any Re-evaluation job (docs/spec/security.md §Re-evaluation job), per-post attempt counter, QUARANTINED → NEEDS_REVIEW transition, per-source UNKNOWN auto-disable, RE_EVAL_RELEASED audit row, or source.status → 'failed' mutation — T2-G territory. Stage 2 here SETS stage2_failed=true and routes UNKNOWN posts to QUARANTINED — those flags FEED the future re-eval queue, but the periodic re-submitter, the attempt counter, and the NEEDS_REVIEW transition are NOT wired in this ticket
  - any throttled admin notifier wiring, AdminNotifier coalescing by (channel, error_class), or per-(channel, error_class) summary-message template — T2-G territory; this ticket logs every Stage 2 infrastructure failure at WARN with the canonical error_class string for the future notifier to pick up without diff churn
  - any LLM output sanitizer (docs/spec/security.md §LLM output sanitizer) — T1-F territory. Stage 2's LLM output is a closed 4-token label set parsed by exact match; it never reaches a user, so the sanitizer is not relevant here
  - any /quarantine list/approve/reject admin command or the approve_quarantine/reject_quarantine stored procedures from docs/design/02-schema.md §2.5.2 — T2-G territory. The PENDING → BENIGN_CLOSED transition handled here on a BENIGN verdict is a state-machine move on the EXISTING quarantine row; APPROVED and REJECTED transitions live in T2-G
  - any Provider-side quarantine_review LISTEN listener or NewQuarantineReviewReconciler — M2 territory per docs/design/01-architecture.md §1.5
  - any new Flyway migration (migration_touch: false; this ticket is impl-only — V10 from M1-032 covers Stage 2's quarantine-table needs; V11 in M1-034 covers post_embedding)
  - any AnthropicProvider native-protocol implementation (docs/design/05-llm-and-embeddings.md §5.3 AnthropicProvider) — T3-D territory per session-grouping-plan §Tier 3. v1's first concrete LlmProvider is OpenAiCompatibleProvider only (covers Ollama, llama.cpp, OpenAI, OpenRouter, NanoGPT per design §5.3)
  - any TranslationProvider concrete impl, LlmTranslationProvider, NoopTranslationProvider, or per-scope translation routing (docs/spec/llm.md §Translation flow) — T1-F territory (the chat-agent reply path is the first translation consumer); Stage 2's outputs are 4-token labels that are never translated
  - any chat-agent recall tool, recallMemory tool dispatcher, or the closed five-tool allowlist (docs/spec/security.md §Prompt-injection defenses, docs/design/04-security.md §4.3) — T2-D chat-agent territory; Stage 2 makes a single LLM call with no tool surface
  - any embedding-provider router (the spec is explicit that EmbeddingProvider has its OWN provider selection, distinct from the (ModelTask, scope_language) router authored here per docs/spec/llm.md §SPI shape "Scope of the enum") — M1-034 ships the embedding-provider resolution path separately
  - any per-task fallback chain (docs/spec/llm.md §Per-task routing rules "No fallback chain in v1") — the router resolves to EXACTLY ONE provider per call; an unreachable provider degrades that task to its task-specific failure path; v2 candidate
  - any Prometheus/Micrometer metric emit for eval_stage2_verdict_total, eval_stage2_failure_total, or eval_stage2_released_with_stage1_only_total (docs/design/04-security.md §4.7 metric table) — observability ticket later; Stage 2 logs verdicts at INFO and failures at WARN with structured fields the metric exporter can later pick up
  - any change to V1..V10 Flyway migrations (V10 from M1-032 is consumed unchanged; V11 lands in M1-034)
  - any change to the M1-007b LlmProvider / EmbeddingProvider / ModelTask SPI surfaces (this ticket WIRES INTO them; the interfaces and the ModelTask enum are frozen from M1-007b)
  - any infochat-provider module change (Stage 2 runs in the Collector; this ticket is collector + llm-adapter only)
  - any rendering of Stage 2 verdicts as user-visible text (Stage 2 is an ingest-side internal-only signal; the verdict never reaches a user)
acceptance:
  - "infochat-llm-adapter/pom.xml declares the HTTP client dependency required by OpenAiCompatibleProvider. The implementation uses java.net.http (built into the JDK) so no new dependency may be needed; if a third-party client is chosen (e.g. quarkus-rest-client-reactive) it MUST be added to infochat-llm-adapter/pom.xml — grep -E '<artifactId>quarkus-rest-client|<artifactId>quarkus-rest-client-reactive' infochat-llm-adapter/pom.xml is permitted but optional; document the choice in Implementation notes"
  - "OpenAiCompatibleProvider.java implements LlmProvider per docs/design/05-llm-and-embeddings.md §5.3 'OpenAiCompatibleProvider' covering Ollama, llama.cpp, OpenAI, OpenRouter, NanoGPT — grep -E 'class\\s+OpenAiCompatibleProvider\\s+implements\\s+LlmProvider' OpenAiCompatibleProvider.java returns at least one match AND grep -E 'public\\s+LlmResponse\\s+generate\\s*\\(' OpenAiCompatibleProvider.java returns at least one match AND grep -E '/chat/completions|/v1/chat' OpenAiCompatibleProvider.java returns at least one match (the OpenAI-compatible chat-completions endpoint path)"
  - "OpenAiCompatibleProvider.java is distinguished at runtime by (base-url, api-key, model) per docs/design/05-llm-and-embeddings.md §5.3 'Distinguished by baseUrl + apiKey. One adapter, four+ effective providers.' The class reads these from per-task @ConfigProperty injections (e.g. infochat.llm.security.base-url, infochat.llm.security.api-key, infochat.llm.security.model) or from a configuration object the LlmRouter passes in. Verify by reading: there is NO hard-coded base-url, NO hard-coded api-key, NO hard-coded model name in the class body"
  - "OpenAiCompatibleProvider.java does NOT implement Anthropic's native messages API (that is AnthropicProvider, design §5.3, which is T3-D territory — out of scope here) — grep -E 'anthropic|claude|/messages' OpenAiCompatibleProvider.java returns zero matches"
  - "LlmRouter.java resolves (ModelTask, scope_language) → LlmProvider per docs/spec/llm.md §Per-task routing rules — grep -E 'class\\s+LlmRouter|@ApplicationScoped' LlmRouter.java returns at least one match AND grep -E 'forTask\\s*\\(|resolve\\s*\\(' LlmRouter.java returns at least one match AND grep -E 'ModelTask' LlmRouter.java returns at least one match"
  - "LlmRouter.java implements the priority-ordered resolution per docs/spec/llm.md §Per-task routing rules: (1) explicit per-task override property (highest priority) — e.g. infochat.llm.security.provider; (2) language-aware capability check (only meaningful for SUMMARIZER/TRANSLATOR — but the code path exists in v1 even though SECURITY_JUDGE is language-agnostic); (3) profile default for the task. Verify by reading: the resolution method consults the per-task override property FIRST, falls back to the profile default LAST, and returns exactly one LlmProvider per call — no fallback chain (docs/spec/llm.md §Per-task routing rules 'No fallback chain in v1')"
  - "LlmRouter.java NEVER returns multiple providers and NEVER silently switches on unreachability per docs/spec/llm.md §Per-task routing rules 'No fallback chain in v1. The router resolves (ModelTask, scope_language) to exactly one LlmProvider; an unreachable provider degrades that task to its task-specific failure path (security.md §Failure handling) and does NOT silently switch to a different configured provider' — verify by reading: the resolution method returns LlmProvider (singular, not List<LlmProvider>); the unreachability check (if any health probe exists) is at the LlmProvider call site, NOT inside the router; the router resolves and returns, the caller handles failure"
  - "LlmRouterStartupGuard.java is a Quarkus @Startup bean that detects the local-only configuration conflict per docs/spec/llm.md §Per-task routing rules 'Local-only is the most-restrictive posture. When the operator sets the explicit local-only property, the router never picks a remote provider — and a per-task override pointing to a remote provider while local-only is set is a configuration conflict that fails Provider startup with a fatal log line identifying the offending task and provider. This is checked once at startup, not per call.' Verify: grep -E '@Startup' LlmRouterStartupGuard.java returns at least one match AND grep -E 'infochat\\.llm\\.local-only|local-only' LlmRouterStartupGuard.java returns at least one match AND grep -E 'fatal|FATAL|throw\\s+new|System\\.exit|Quarkus\\.asyncExit' LlmRouterStartupGuard.java returns at least one match"
  - "LlmRouterStartupGuard.java enumerates every configured per-task override's base-url and refuses to start when local-only=true AND any per-task base-url resolves to a non-loopback host. The implementation MUST use the same IP-blocklist policy as infochat-ssrf (the IpBlocklist class from M1-024 — DNS-resolved IP not in loopback range) OR a simpler equivalent (URI.getHost() ∈ {localhost, 127.0.0.1, ::1}). Verify by reading: the guard's check rejects e.g. base-url=https://api.openai.com/v1 when local-only=true; the rejection log line names the offending ModelTask and the offending base-url"
  - "LlmRouterStartupGuard.java runs on the Collector startup chain (Stage 2 is the Collector-side LLM call site even though docs/design/01-architecture.md §1.4.3 lists the @Priority table under the Provider). The spec wording at docs/spec/llm.md §Per-task routing rules says 'fails Provider startup with a fatal log line' — treat this as a doc-bug routing call and wire the guard on the Collector because Stage 2 runs there. Document this routing choice in Implementation notes. The guard's @Priority is between Flyway (100) and OutboxRehydrator (300) so router misconfiguration is caught before any post is picked up — e.g. @Priority(150) — grep -E '@Priority\\s*\\(\\s*1[0-9][0-9]\\s*\\)|@Priority\\s*\\(\\s*2[0-4][0-9]\\s*\\)' LlmRouterStartupGuard.java returns at least one match"
  - "infochat-llm-adapter/src/main/resources/prompts/security-judge.md exists and follows the template at docs/design/05-llm-and-embeddings.md §5.4.1: instructs the model to classify untrusted text; wraps the content in <<<UNTRUSTED_CONTENT id=\"{{id}}\">>>...<<<END id=\"{{id}}\">>>; lists the four labels (BENIGN, INJECTION, MALWARE, UNKNOWN); demands a single-token reply — grep -E 'BENIGN|INJECTION|MALWARE|UNKNOWN' security-judge.md returns at least four matches (one per label, distinct lines) AND grep -E '<<<UNTRUSTED_CONTENT' security-judge.md returns at least one match AND grep -E '<<<END' security-judge.md returns at least one match AND grep -E '\\{\\{\\s*id\\s*\\}\\}|\\{\\{id\\}\\}' security-judge.md returns at least one match (the per-call random delimiter id)"
  - "Stage2Worker.java is the Collector-side worker that consumes Stage-1-flagged posts (a stage1_flagged=true post arrives from Stage 1's hand-off per M1-032 acceptance items 20 and 21). Stage 2 is NOT invoked when stage1_flagged=false per docs/spec/security.md §Ingest pipeline 'Stage 2 — LLM judge. Only invoked when Stage 1 flagged something' — grep -E 'class\\s+Stage2Worker' Stage2Worker.java returns at least one match AND the worker is wired downstream of Stage 1 (either via @Incoming on a Stage1-to-Stage2 channel, or via direct invocation from Stage1Worker on the stage1_flagged=true branch — document the choice in Implementation notes)"
  - "Stage2Worker.java invokes the router with SECURITY_JUDGE and the scope-default language ('en' — Stage 2 is not user-language-driven; the security model is the same regardless of the SCOPE that will later see the post). The retrieved LlmProvider receives the ORIGINAL (pre-redaction) body per docs/spec/security.md §Ingest pipeline 'Stage 2 — LLM judge. The judge sees the original (pre-redaction) content inside an untrusted-content wrapper' — verify by reading: the call site passes the originalBody field from the Stage1Result (NOT the redacted body which is post.body). The system prompt is loaded from prompts/security-judge.md; the per-call random delimiter UUID is generated fresh via UUID.randomUUID() and substituted into the {{id}} template placeholder"
  - "Stage2Worker.java parses the LLM reply by EXACT MATCH against the four-token closed set ('BENIGN', 'INJECTION', 'MALWARE', 'UNKNOWN'). Anything else (extra whitespace tolerated by .trim(), but never extra tokens or different casing) is treated as unparseable per docs/spec/security.md §Failure handling 'Schema-violating LLM output (wrong JSON shape, unexpected label value, missing required field) is treated identically to an unparseable reply at every stage: retry once, then apply the stage-specific failure path' — grep -E 'BENIGN|INJECTION|MALWARE|UNKNOWN' Stage2Worker.java returns at least four matches AND grep -E 'switch\\s*\\(|case\\s+\"BENIGN\"|case\\s+\"INJECTION\"' Stage2Worker.java returns at least one match (the dispatch shape — switch expression preferred per CLAUDE.md §Prefer switch expressions)"
  - "Stage2Worker.java's retry policy is retry-once-then-fallback per docs/spec/security.md §Failure handling. On (a) unparseable / schema-violating reply, (b) LLM unreachable, or (c) timeout: retry exactly once. After the retry exhausts, this is the infrastructure-failure path (NOT a verdict). Verify by reading: the call site is wrapped in a try-catch / retry harness that fires at most one retry; the retry uses the SAME prompt (no fallback prompt for Stage 2 — that exists only for the Tagger in M1-034) — grep -E 'retry|Retry' Stage2Worker.java returns at least one match"
  - "Stage2VerdictHandler.java handles BENIGN per docs/spec/security.md §Failure handling 'Stage 2 verdict of BENIGN → post released to the tagger and embedding stage; Stage 1 redactions remain in the body (quarantine rows transition PENDING → BENIGN_CLOSED, not deleted — the original text is restorable only via admin /quarantine approve)': UPDATE post.stage2_done=true; LEAVE post.status='RAW' (Tagger and Embedding still need to run before the post reaches READY per Invariant 5 'Posts in RAW with one or more stage-outcome flags already set resume from the next uncompleted stage; the per-stage flags are the durable cursor'); UPDATE every quarantine row for this post with status='PENDING' AND flagged_by='stage1' to status='BENIGN_CLOSED', updated_at=now(). Stage 1 redactions are NOT lifted (post.body retains the [REDACTED:<id>] placeholders). The transition from RAW → READY happens in M1-034's Stage 5 after Tagger and Embedding complete. Verify by reading: the BENIGN branch sets post.stage2_done=true AND DOES NOT touch post.status (leaves the RAW); the UPDATE of quarantine rows transitions PENDING → BENIGN_CLOSED only (never APPROVED — that lives in T2-G); the redaction placeholders are NOT replaced"
  - "Stage2VerdictHandler.java handles INJECTION and MALWARE per docs/spec/security.md §Failure handling 'Stage 2 verdict of INJECTION, MALWARE, or UNKNOWN → post stays QUARANTINED until admin review': UPDATE post.status='QUARANTINED', post.stage2_done=true. Quarantine rows stay status='PENDING' (no state-machine move). Verify: the INJECTION and MALWARE branches both write status='QUARANTINED' (not 'NEEDS_REVIEW' — that transition is T2-G after re-eval cap exhaustion) AND set stage2_done=true"
  - "Stage2VerdictHandler.java handles UNKNOWN per docs/spec/security.md §Failure handling 'The judge model treating UNKNOWN as a soft injection signal is intentional: a degraded judge must never auto-release' — same shape as INJECTION/MALWARE: UPDATE post.status='QUARANTINED', post.stage2_done=true, quarantine rows stay PENDING. The UNKNOWN-specific re-eval-queue feed (the per-post attempt counter + periodic re-submit) is T2-G territory and intentionally absent. Document the boundary in the code: the UNKNOWN branch sets a comment 'UNKNOWN posts feed the future re-eval queue (T2-G); this ticket sets stage2_done and routes to QUARANTINED, the re-eval scheduler reads stage2_done + status to enqueue'"
  - "Stage2VerdictHandler.java handles the infrastructure-failure path per docs/spec/security.md §Failure handling 'Stage 2 infrastructure failure (LLM unreachable, timeout, unparseable or schema-violating reply after retry) → release as READY with the Stage 1 redactions retained, mark the post for re-evaluation, notify admin via the throttled channel. A profile-driven flag lets production profiles invert this default and keep the post quarantined.' The handler reads the @ConfigProperty 'infochat.security.release-on-stage2-failure' (profile-driven defaults per docs/design/04-security.md §4.7: laptop true / pi true / vps false / remote-llm false) — grep -E 'infochat\\.security\\.release-on-stage2-failure|release-on-stage2-failure' Stage2VerdictHandler.java returns at least one match"
  - "Stage2VerdictHandler.java's release-on-stage2-failure=true path: UPDATE post.stage2_done=true, post.stage2_failed=true; LEAVE post.status='RAW' so Tagger and Embedding still run (the post follows the same downstream path as BENIGN — release through the rest of the pipeline; Stage 5 in M1-034 advances RAW → READY when Tagger and Embedding complete). The design 04-security.md §4.7 wording 'post.status=READY' on infra-fail is treated as shorthand for 'enters the release path that ends at READY' — the literal status flip happens in Stage 5 per Invariant 5's RAW-plus-flag-bitmap representation; document this spec-vs-implementation alignment in Implementation notes. Quarantine rows stay PENDING (the verdict is unknown, not BENIGN). Log WARN with canonical error_class='stage2.infra_failure'. Stage 1 redactions retained in post.body. Verify by reading: the true-branch writes stage2_failed=true AND stage2_done=true AND DOES NOT touch post.status; the quarantine rows are NOT transitioned to BENIGN_CLOSED (the infra failure is NOT a BENIGN verdict — only a real BENIGN reply transitions them)"
  - "Stage2VerdictHandler.java's release-on-stage2-failure=false path: UPDATE post.status='QUARANTINED', post.stage2_done=true, post.stage2_failed=true; quarantine rows stay PENDING; log WARN with canonical error_class='stage2.infra_failure'. Verify by reading: the false-branch writes status='QUARANTINED' AND stage2_failed=true AND stage2_done=true"
  - "Stage2VerdictHandler.java NEVER auto-releases the ORIGINAL (pre-Stage-1) content per docs/spec/security.md §Ingest pipeline 'Stage 1 is a coarse filter, not a complete defense. It exists to ... provide a degraded mode (Stage-1-redacted-but-released) when the judge can't run' — the worst-case release path is the Stage-1-redacted body, NEVER the original. Verify by reading: the BENIGN branch does NOT replace [REDACTED:<id>] placeholders in post.body; the release-on-stage2-failure=true branch does NOT replace them either. The only path that lifts redactions is /quarantine approve (T2-G, out of scope here)"
  - "StartupReleaseOnStage2FailureWarn.java is a Quarkus @Startup bean on the Collector. When the active profile has infochat.security.release-on-stage2-failure=true (laptop / pi defaults per docs/design/04-security.md §4.7), the bean: (a) emits the WARN-level startup line from §4.7 verbatim (or close — the exact wording is design-tier; pick a phrasing that includes the key facts 'release-on-stage2-failure=true', 'Stage 1 only', 'English-language coarse filter', 'multilingual or obfuscated injection content can reach LLM call sites'); (b) writes ONE audit_log row with action='STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE' (the spec calls for an audit row per docs/design/04-security.md §4.7 'is also written to the audit_log once per process start with action=STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE so the operating posture is reconstructible from audit history'). The spec design wording says 'the Provider emits the WARN' but Stage 2 runs in the Collector — treat as another doc-bug routing call and wire the WARN + audit on the Collector. Document the routing choice in Implementation notes — grep -E '@Startup' StartupReleaseOnStage2FailureWarn.java returns at least one match AND grep -E 'STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE' StartupReleaseOnStage2FailureWarn.java returns at least one match AND grep -E 'release-on-stage2-failure|infochat\\.security\\.release-on-stage2-failure' StartupReleaseOnStage2FailureWarn.java returns at least one match"
  - "Bounded concurrency per provider per docs/spec/llm.md §Bounded concurrency and observability 'Per-provider concurrency is bounded so a slow provider applies back-pressure to the eval queue rather than exhausting threads' and docs/design/05-llm-and-embeddings.md §5.7 — the security task's max-concurrency value is read from infochat.llm.security.max-concurrency (laptop 4 / vps 2 / pi 1 / remote-llm 8). Implementation: a worker semaphore (Quarkus vert.x worker pool, java.util.concurrent.Semaphore, or @ApplicationScoped @Inject ManagedExecutor — pick one and document) that bounds concurrent Stage 2 invocations. Verify: grep -E 'infochat\\.llm\\.security\\.max-concurrency' Stage2Worker.java returns at least one match OR grep -E 'Semaphore|ManagedExecutor|max-concurrency' Stage2Worker.java returns at least one match"
  - "application.properties under infochat-collector/src/main/resources declares the security-judge default property surface keys (operators override per profile via Quarkus's @ConfigProperty profile mechanism). Required keys: infochat.security.release-on-stage2-failure (a default value here — laptop profile's true OR a no-default-property-with-required-injection pattern); infochat.security.stage1.regex-timeout-ms (read by M1-032's Stage1Pipeline — declared here even though M1-032 owns the source code, because property declarations live with the consumer module not with the SPI); infochat.llm.security.base-url, infochat.llm.security.api-key, infochat.llm.security.model (the OpenAI-compatible provider config; default values acceptable for local Ollama: http://localhost:11434/v1 + ignored + llama3.2:3b); infochat.llm.security.max-concurrency (the per-provider worker semaphore bound). Verify: grep -E 'infochat\\.security\\.release-on-stage2-failure' application.properties returns at least one match AND grep -E 'infochat\\.llm\\.security\\.base-url' application.properties returns at least one match AND grep -E 'infochat\\.llm\\.security\\.model' application.properties returns at least one match AND grep -E 'infochat\\.llm\\.security\\.max-concurrency' application.properties returns at least one match"
  - "LlmRouterTest.java is a plain JUnit5 unit test (NOT a @QuarkusTest — router resolution is in-process Java). It is the harness for the four per-behavior acceptance items 26a-26d below; each item pins one named test method so per-behavior coverage is mechanically checkable without an aggregate @Test count. grep -E '@Test' LlmRouterTest.java MUST return at least four matches across the four named items"
  - "26a (LlmRouterTest profile-default resolution for SECURITY_JUDGE): a @Test method named with substring `profileDefault` AND `securityJudge` (case-insensitive) asserts that LlmRouter.forTask(SECURITY_JUDGE, \"en\") returns the configured profile-default provider when no per-task override property is set. Verify: grep -iE '@Test\\s*\\n[^}]*(profileDefault|profile_default)[^}]*(securityJudge|security_judge)|@Test\\s*\\n[^}]*(securityJudge|security_judge)[^}]*(profileDefault|profile_default)' LlmRouterTest.java returns at least one match (the test method dispatches on profile default; method name carries both substrings)"
  - "26b (LlmRouterTest per-task override priority): a @Test method named with substring `override` asserts that infochat.llm.security.provider=<alternate> takes priority over the profile default — set the override to a distinct provider key, invoke forTask(SECURITY_JUDGE, \"en\"), assert the resolved provider matches the override, NOT the default. Verify: grep -iE '@Test\\s*\\n[^}]*override' LlmRouterTest.java returns at least one match"
  - "26c (LlmRouterTest singular-return-type contract): a @Test method asserts that LlmRouter.forTask's return type is LlmProvider (singular), NOT List<LlmProvider> or Optional<LlmProvider>. The assertion has two parts: (i) a compile-time signature check (the test method invokes `LlmProvider p = router.forTask(...)` and the test compiles only if the return type is assignable to LlmProvider — a List<LlmProvider> return would fail compilation; this is the load-bearing static check); (ii) a runtime non-null assertion. Verify: grep -E 'LlmProvider\\s+\\w+\\s*=\\s*\\w+\\.forTask\\s*\\(' LlmRouterTest.java returns at least one match (the singular-return assignment is the compile-time witness)"
  - "26d (LlmRouterTest language-aware capability check for SUMMARIZER): a @Test method named with substring `Summarizer` OR `language` (case-insensitive) exercises the language-aware branch even though Stage 2 doesn't use it — the code path must exist in v1 per docs/spec/llm.md §Per-task routing rules. Scenario: register two LlmProvider candidates for ModelTask.SUMMARIZER — one declaring SUPPORTS_LANGUAGE_CS via the M1-007b capabilities mechanism, the other not — and assert forTask(SUMMARIZER, \"cs\") returns the capability-declaring provider. Verify: grep -iE '@Test\\s*\\n[^}]*(summarizer|languageCapab|supportsLanguage)' LlmRouterTest.java returns at least one match"
  - "Stage2WorkerIT.java is a @QuarkusTest IT against a real Postgres (DevServices acceptable per M1-027/M1-028 pattern) + a STUB LlmProvider (a hand-written Quarkus @Alternative bean implementing LlmProvider directly — see acceptance Item 29 for the stub's shape). It is the harness for the nine per-scenario acceptance items 28a-28i below; each item pins one named test method so per-scenario coverage is mechanically checkable without an aggregate @Test count. grep -E '@Test' Stage2WorkerIT.java MUST return at least nine matches across the nine named items; grep -E 'BENIGN|INJECTION|MALWARE|UNKNOWN' Stage2WorkerIT.java MUST return at least four matches (one per label, across the BENIGN/INJECTION/MALWARE/UNKNOWN scenario method bodies)"
  - "28a (Stage2WorkerIT BENIGN verdict end-to-end): a @Test method named with substring `benign` (case-insensitive) sets the stub to return 'BENIGN'; persists one Stage-1-flagged post with stage1_flagged=true, status='RAW', and one PENDING quarantine row (flagged_by='stage1'); invokes Stage2Worker; asserts post.stage2_done=true, post.status stays 'RAW' (NOT 'READY' — Tagger/Embedding still need to run, status='READY' is M1-034 Stage 5), quarantine row transitions PENDING → BENIGN_CLOSED, [REDACTED:<id>] placeholders RETAINED in post.body. Verify: grep -iE '@Test\\s*\\n[^}]*benign' Stage2WorkerIT.java returns at least one match AND the matching method body references both 'BENIGN_CLOSED' and 'RAW'"
  - "28b (Stage2WorkerIT INJECTION verdict end-to-end): a @Test method named with substring `injection` (case-insensitive) sets the stub to return 'INJECTION'; asserts post.status='QUARANTINED', post.stage2_done=true, quarantine row stays PENDING (no state-machine move). Verify: grep -iE '@Test\\s*\\n[^}]*injection' Stage2WorkerIT.java returns at least one match"
  - "28c (Stage2WorkerIT MALWARE verdict end-to-end): a @Test method named with substring `malware` (case-insensitive) sets the stub to return 'MALWARE'; same DB-state shape as 28b — post.status='QUARANTINED', post.stage2_done=true, quarantine row stays PENDING. Verify: grep -iE '@Test\\s*\\n[^}]*malware' Stage2WorkerIT.java returns at least one match"
  - "28d (Stage2WorkerIT UNKNOWN verdict end-to-end): a @Test method named with substring `unknown` (case-insensitive) sets the stub to return 'UNKNOWN'; same DB-state shape as 28b/28c — post.status='QUARANTINED', post.stage2_done=true, quarantine row stays PENDING. The re-eval feed implicit in (stage2_done=true AND stage2_failed=false AND status='QUARANTINED') is T2-G's input, not this ticket's assertion. Verify: grep -iE '@Test\\s*\\n[^}]*unknown' Stage2WorkerIT.java returns at least one match"
  - "28e (Stage2WorkerIT schema-violating reply on both calls takes infra-failure path): a @Test method named with substring `schemaViolat` OR `unparseable` OR `invalidLabel` (case-insensitive) sets the stub to return 'BENIGN_PLEASE' on the first call AND 'BENIGN_PLEASE' again on the retry (verifies retry-once policy). Asserts the post follows the infra-failure path per the active test profile's release-on-stage2-failure value (the active profile is the test's own; assertion shape depends on which profile the test runs under — document the chosen profile). Verify: grep -iE '@Test\\s*\\n[^}]*(schemaViolat|unparseable|invalidLabel)' Stage2WorkerIT.java returns at least one match"
  - "28f (Stage2WorkerIT empty reply on both calls takes infra-failure path): a @Test method named with substring `empty` (case-insensitive) sets the stub to return '' on both calls; asserts retry-once-then-fallback to the infra-failure path under the active profile. Verify: grep -iE '@Test\\s*\\n[^}]*empty' Stage2WorkerIT.java returns at least one match"
  - "28g (Stage2WorkerIT unreachable-LLM takes infra-failure path): a @Test method named with substring `unreachable` OR `throws` (case-insensitive) configures the stub to throw on every call (e.g. IOException simulating connection refused); asserts retry-once-then-fallback to the infra-failure path; asserts the stub was invoked exactly twice (once + retry). Verify: grep -iE '@Test\\s*\\n[^}]*(unreachable|throws|exception)' Stage2WorkerIT.java returns at least one match"
  - "28h (Stage2WorkerIT release-on-stage2-failure=true profile infra-failure path): a @Test method named with substring `releaseOnFailureTrue` OR `releaseOnStage2FailureTrue` (case-insensitive) runs under a @TestProfile that sets infochat.security.release-on-stage2-failure=true; triggers an infra-failure (e.g. via the stub throwing); asserts post.stage2_done=true, post.stage2_failed=true, post.status STAYS 'RAW' (Tagger/Embedding still need to run; release-to-READY is M1-034 Stage 5), [REDACTED:<id>] placeholders retained; asserts a WARN log line with error_class='stage2.infra_failure' was emitted. Verify: grep -iE '@Test\\s*\\n[^}]*releaseOn(Failure|Stage2Failure)True' Stage2WorkerIT.java returns at least one match"
  - "28i (Stage2WorkerIT release-on-stage2-failure=false profile infra-failure path): a @Test method named with substring `releaseOnFailureFalse` OR `releaseOnStage2FailureFalse` (case-insensitive) runs under a @TestProfile that sets infochat.security.release-on-stage2-failure=false; triggers an infra-failure; asserts post.status='QUARANTINED', post.stage2_done=true, post.stage2_failed=true; asserts a WARN log line with error_class='stage2.infra_failure'. Verify: grep -iE '@Test\\s*\\n[^}]*releaseOn(Failure|Stage2Failure)False' Stage2WorkerIT.java returns at least one match"
  - "Stage2WorkerIT.java's stub LlmProvider is REAL Java (not a Mockito mock at the SPI level — Mockito is fine for verification but the @Alternative bean is a hand-written class that implements LlmProvider directly so the SPI contract is exercised end-to-end) AND is implemented as a **nested static class inside Stage2WorkerIT.java** (NOT a top-level class in its own .java file — this keeps files_scope at 13 entries; the Implementation notes bullet for the test stub pins this choice). The Stage 2 call site invokes LlmRouter.forTask(SECURITY_JUDGE, …), receives the stub, invokes stub.generate(...) with the assembled prompt, and the stub returns the canned response. Verify by reading: grep -E 'static\\s+(final\\s+)?class\\s+TestStubLlmProvider\\s+implements\\s+LlmProvider' Stage2WorkerIT.java returns at least one match (the nested static class declaration); the stub class is NOT a top-level type at the file's primary class declaration; the stub is selected by Quarkus's @Alternative + @Priority(Integer.MAX_VALUE) or equivalent mechanism for the test profile"
  - "LocalOnlyConflictStartupIT.java is a @QuarkusTest IT that asserts the local-only conflict detection from acceptance items 8/9/10. Test profile: infochat.llm.local-only=true AND infochat.llm.security.base-url=https://api.openai.com/v1 (or any non-loopback host). The Collector startup FAILS with the fatal log line naming the SECURITY_JUDGE task and the offending base-url. The test verifies the FAIL via Quarkus's @QuarkusTestResource pattern OR via expecting an exception during Quarkus boot — the precise mechanism depends on how the guard signals failure (System.exit, throwing from @Startup, or @Observes StartupEvent throwing). Document the mechanism in the file's class JDoc. grep -E '@Test' LocalOnlyConflictStartupIT.java returns at least one match AND grep -E 'local-only|SECURITY_JUDGE' LocalOnlyConflictStartupIT.java returns at least one match"
  - "mvn -B -pl infochat-collector -am verify exits 0; failsafe reports show Stage2WorkerIT and LocalOnlyConflictStartupIT executed — grep -rE 'Tests run: [1-9]' infochat-collector/target/failsafe-reports returns at least two new matches across the two new IT classes"
  - "mvn -B -pl infochat-llm-adapter -am test exits 0; surefire reports show LlmRouterTest executed — grep -rE 'Tests run: [1-9]' infochat-llm-adapter/target/surefire-reports returns at least one new match for LlmRouterTest"
  - "mvn -B clean verify from the repo root exits 0; all prior tests (M1-003, M1-007, M1-007a/b/c, M1-008/008a/b/c, M1-009, M1-017, M1-022..M1-029, and M1-032) continue to pass alongside the new Stage 2 worker, LlmRouter, OpenAiCompatibleProvider, and the startup guards"
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/io/infochat/llm/routing/LlmRouterTest.java (unit test of router priority resolution, no Quarkus boot)
    - infochat-collector/src/test/java/io/infochat/collector/eval/stage2/Stage2WorkerIT.java (@QuarkusTest IT against real Postgres + stub LlmProvider exercising all four verdicts + schema-violating + unreachable + both release-on-stage2-failure profiles)
    - infochat-collector/src/test/java/io/infochat/collector/eval/stage2/LocalOnlyConflictStartupIT.java (@QuarkusTest IT asserting startup-time refusal when local-only=true conflicts with a remote per-task base-url)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b — the SPI surface stays empty-stub at the interface level; the router and impl extend it without changing the interfaces)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-017 — no new migration; V1..V10 unchanged)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006)
    - all M1-008a/b/c schema tests
    - all M1-022/023/024/025/026/029 ingest + SSRF tests
    - M1-027's three provider outbox ITs
    - M1-028's PostPersisterIT + OutboxRehydratorIT + FetchSchedulerIT
    - M1-032's Stage1PipelineIT + Stage1WatchdogIT + Stage1RegexSetTest
spec_refs:
  - docs/spec/security.md §Ingest pipeline (security side)
  - docs/spec/security.md §Failure handling
  - docs/spec/security.md §DB roles
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Prompt-injection-aware prompt shape
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/llm.md §Bounded concurrency and observability
  - docs/spec/llm.md §Hardware profile contract
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Invariants
  - docs/spec/architecture.md §Pipelines
  - docs/spec/architecture.md §Architectural principles
  - docs/design/01-architecture.md §1.3.4 Eval pipeline workers
  - docs/design/01-architecture.md §1.4.3 Startup-bean ordering
  - docs/design/01-architecture.md §1.6 Concurrency and rate limiting
  - docs/design/04-security.md §4.2 Layered ingest security
  - docs/design/04-security.md §4.3 Prompt-injection defenses
  - docs/design/04-security.md §4.7 Eval pipeline failure handling
  - docs/design/05-llm-and-embeddings.md §5.1 SPI overview
  - docs/design/05-llm-and-embeddings.md §5.3 Provider implementations
  - docs/design/05-llm-and-embeddings.md §5.4.1 Security Stage 2 judge
  - docs/design/05-llm-and-embeddings.md §5.7 Profile defaults table
  - docs/design/05-llm-and-embeddings.md §5.8 Failure handling per task
decision_refs:
  - D20
  - D22
  - D27
  - D32
escalations:
  - date: 2026-05-16
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      SPEC-REFS-VALID: FAIL
        - docs/design/05-llm-and-embeddings.md §5.3 Provider
          implementations: ANCHOR-NOT-FOUND — line 88 reads
          "  5.3 Provider implementations" with NO `#` marker.
        - docs/design/05-llm-and-embeddings.md §5.4.1 Security
          Stage 2 judge: ANCHOR-NOT-FOUND — line 147 reads
          "  5.4.1 Security Stage 2 judge" with NO `#` marker.
        - docs/design/05-llm-and-embeddings.md §5.7 Profile
          defaults table: ANCHOR-NOT-FOUND — line 434 reads
          "  5.7 Profile defaults table (canonical)" with NO `#`
          marker.
        - docs/design/05-llm-and-embeddings.md §5.8 Failure
          handling per task: ANCHOR-NOT-FOUND — line 489 reads
          "  5.8 Failure handling per task" with NO `#` marker.
        Four ANCHOR-NOT-FOUND entries for
        docs/design/05-llm-and-embeddings.md → FAIL.
  - date: 2026-05-16
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation escalation. The clarity pre-flight
      returned WARN (not FAIL) with 4 warnings; the Plan subagent's
      outline then surfaced one scope ambiguity that requires a
      files_scope edit before implementation can proceed:

        "Stage1Worker.java wiring is required but not in files_scope.
         The ticket's Implementation notes say 'M1-032's Stage1Worker
         calls Stage1Pipeline → if stage1_flagged=true, calls
         Stage2Worker directly with the Stage1Result.' But
         Stage1Worker.java is NOT in the M1-033 files_scope list.
         The in-process hand-off requires injecting Stage2Worker
         into Stage1Worker and adding the conditional dispatch — a
         non-trivial diff to a file not authorized for change."

      User invoked: /m1-tick escalate M1-033 refine and address all
      warnings. Resolution: refine. Scope of the refine:
        1. Add Stage1Worker.java to files_scope; bump files_budget
           12 → 13 (resolves the outline-surfaced scope ambiguity).
        2. Pin "each label on its own dedicated line" in the
           security-judge.md DoD bullet (resolves clarity Warning 1).
        3. Split acceptance Item 26 (LlmRouterTest @Test >= 4) into
           four per-behavior acceptance items 26a-26d (resolves
           clarity Warning 2 HETEROGENEOUS-AGGREGATE smell).
        4. Split acceptance Item 28 (Stage2WorkerIT @Test >= 9) into
           nine per-scenario acceptance items 28a-28i (resolves
           clarity Warning 3 HETEROGENEOUS-AGGREGATE smell).
        5. Pin "TestStubLlmProvider as a nested static class inside
           Stage2WorkerIT.java" in the Implementation notes test-stub
           bullet (resolves clarity Warning 4 off-by-one; no
           files_scope addition).
revisions:
  - date: 2026-05-16
    reason: clarity-fail rework
    note: |
      Underlying defect (4 ANCHOR-NOT-FOUND in
      docs/design/05-llm-and-embeddings.md) resolved in spec commits
      7de7e51, 0ce4049, 155d808 (heading markers + fence repairs)
      and clarity-algorithm hardening in fa1832e (fence-state +
      whitespace canon). spec_refs unchanged — the four cited
      anchors now resolve under the hardened algorithm:
        §5.3 Provider implementations    → line 88  (## marker)
        §5.4.1 Security Stage 2 judge    → line 149 (### marker)
        §5.7 Profile defaults table      → line 444 (## marker)
        §5.8 Failure handling per task   → line 499 (## marker)
      No frontmatter or body edits required; this revision is a
      clarity-check reset only.
  - date: 2026-05-16
    reason: budget-breach rework
    note: |
      Pre-implementation refine triggered by the Plan subagent's
      outline-surfaced scope ambiguity (Stage1Worker.java required
      but not in files_scope) plus the four clarity-WARN findings
      from the start-time pre-flight. No implementation rounds had
      run; refine applied on the per-ticket branch
      m1/M1-033-stage2-llm-judge-router before any code lands.
      Pre-refine frontmatter snapshot (the diff this revision
      replaces):
        files_budget: 12
        files_scope: 12 entries (no Stage1Worker.java)
        acceptance: 32 items (Items 26 + 28 were single aggregate
          @Test count assertions)
        DoD security-judge.md bullet: did not pin "each label on
          its own dedicated line"
        Implementation notes TestStubLlmProvider bullet: said
          "in the test source root" without pinning nested vs
          top-level
      Post-refine frontmatter:
        files_budget: 13
        files_scope: 13 entries (Stage1Worker.java added)
        acceptance: items 26 + 28 expanded to per-behavior /
          per-scenario sets (26a-26d, 28a-28i); total item count
          rises from 32 to 43
        DoD: security-judge.md bullet now pins per-label-per-line
        Implementation notes: TestStubLlmProvider pinned as nested
          static class inside Stage2WorkerIT.java
      The expanded acceptance set is per-element-asserting (the
      pattern recommended in docs/process/ticket-template.md
      §acceptance) so each behavior / scenario is mechanically
      checkable in isolation; the HETEROGENEOUS-AGGREGATE smell is
      resolved by construction. clarity_check is preserved as the
      historical record of the warnings this refine addresses;
      per the M1 workflow refine arm for budget-breach, clarity
      pre-flight does NOT re-run.
clarity_check:
  date: 2026-05-16
  verdict: WARN
  warnings:
    - |
      ACCEPTANCE-VS-DOD-CONSISTENT (Item 11 — security-judge.md
      label-line format): the acceptance asserts "at least four
      matches" for grep -E 'BENIGN|INJECTION|MALWARE|UNKNOWN' against
      security-judge.md with the parenthetical "(one per label,
      distinct lines)." Design §5.4.1 shows all four labels on one
      line, which grep would match as 1 line, not 4. The DoD does
      not commit to the separate-lines format. Recommend adding to
      the DoD: "each label BENIGN, INJECTION, MALWARE, and UNKNOWN
      appears on its own dedicated line in security-judge.md."
    - |
      ACCEPTANCE-VS-DOD-CONSISTENT (Item 26 — LlmRouterTest @Test
      aggregate count): "grep -E '@Test' returns at least four
      matches" is a HETEROGENEOUS-AGGREGATE over four structurally
      distinct routing behaviors enumerated in the DoD. Omitting
      any one behavior is undetectable if the total @Test count
      still reaches 4. Recommend splitting into four per-behavior
      acceptance items with method-name-pattern greps so structural
      gaps are mechanically visible.
    - |
      ACCEPTANCE-VS-DOD-CONSISTENT (Item 28 — Stage2WorkerIT @Test
      aggregate count): "grep -E '@Test' returns at least nine
      matches" is a HETEROGENEOUS-AGGREGATE over nine scenarios
      with structurally different DB-state shapes (BENIGN quarantine
      transition vs. INJECTION/MALWARE/UNKNOWN no transition vs.
      infra-failure stage2_failed flag). Recommend splitting into
      nine per-scenario acceptance items each asserting the specific
      post.status and quarantine-row outcome.
    - |
      FILES-BUDGET-PLAUSIBLE (potential off-by-one on
      TestStubLlmProvider): if implemented as a top-level class in
      its own file (as the implementation notes wording implies),
      it is file #13 not listed in files_scope. Clarify before
      starting: if top-level, add the stub file to files_scope and
      set files_budget: 13. If nested inside Stage2WorkerIT.java,
      no change needed.
  blockers: []
reviews:
  - round: 1
    date: 2026-05-16
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 2746
      removed: 29
  - round: 2
    date: 2026-05-16
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 2759
      removed: 29
redteam_findings: []
redteam_audits:
  - date: 2026-05-16
    verdict: CLEAN
    base: main
    head: m1/M1-033-stage2-llm-judge-router (pre-commit branch tip)
    verdict_file: docs/plan/m1/redteam/M1-033-2026-05-16.md
    out_of_model_count: 4
    note: |
      CLEAN — no findings. 4 OUT-OF-MODEL observations (informational,
      not threat-model gaps): SSRF-allowlist scope vs LLM endpoints
      (in-spec; operator-trusted config); LLM response-body preview
      logging vs M1-019 stdout redaction (deferred ticket);
      audit_log raw-JDBC inserts vs spec's redaction-hook layer
      (project-wide pattern, no user-content fields in this row);
      @Incoming method invoking blocking Stage2Worker without
      @Blocking annotation (eval-queue not user-facing event-loop;
      worth defensive annotation in follow-up).
---

# M1-033: Stage 2 LLM judge + first OpenAI-compatible LlmProvider + (ModelTask, scope_language) router

## Context

Second ticket of T1-D (eval pipeline). Stage 2 is the LLM-judge
security boundary that fires ONLY on Stage 1 hits per
`docs/spec/security.md` §Ingest pipeline. It is also the first
LLM call site anywhere in the codebase, which means this ticket
also lands:

1. The first concrete `LlmProvider` impl — `OpenAiCompatibleProvider`
   per `docs/design/05-llm-and-embeddings.md` §5.3, covering
   Ollama, llama.cpp, OpenAI, OpenRouter, and NanoGPT. Distinguished
   at runtime by `(base-url, api-key, model)`.
2. The `LlmRouter` that resolves `(ModelTask, scope_language)` to
   a concrete `LlmProvider` per `docs/spec/llm.md`
   §Per-task routing rules. T1-D's only consumer is
   `SECURITY_JUDGE`; M1-034 (Tagger) and T1-F (`/summary`) and
   T2-D (chat-agent) wire additional `ModelTask`s as they land.
3. The local-only conflict-detection startup guard from
   `docs/spec/llm.md` §Per-task routing rules — a per-task
   override pointing at a remote provider while
   `infochat.llm.local-only=true` fails Collector startup with a
   fatal log line. Checked once at startup, not per call.
4. The verdict-vs-infrastructure split from
   `docs/spec/security.md` §Failure handling and
   `docs/design/04-security.md` §4.7 — the heart of the policy.
5. The `release-on-stage2-failure` config flag with profile-driven
   defaults (laptop / pi true; vps / remote-llm false) per
   `docs/design/04-security.md` §4.7.
6. The `STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE` audit row + WARN
   log emitted on Collector startup whenever the flag is `true`
   in effect.

This is the heart of the security model. A wrong verdict
classification, a parse-error path that doesn't retry, a
schema-violating reply silently bucketed as BENIGN, or a
`release-on-stage2-failure` wired wrong on `vps`/`remote-llm`
defeats the security boundary. Round-cap is 3 to allow one
extra REWORK round for tightening verdict-handler semantics or
the router's priority resolution if round 1 surfaces gaps.

The Stage-2 hand-off shape from M1-032 is a `Stage1Result` (or
equivalent) carrying both `originalBody` (pre-redaction) and
`redactedBody` (post-redaction). Stage 2's prompt assembly uses
`originalBody` per `docs/spec/security.md` §Ingest pipeline;
the post body in DB carries the redacted form throughout.

## Definition of Done

- **`OpenAiCompatibleProvider.java`** under
  `infochat-llm-adapter/src/main/java/io/infochat/llm/impl/`
  implements `LlmProvider` per `docs/design/05-llm-and-embeddings.md`
  §5.3 (OpenAI-compatible chat-completions endpoint). The class:
  - Reads `(base-url, api-key, model)` from `@ConfigProperty`
    injections (per-task keys e.g.
    `infochat.llm.security.base-url`,
    `infochat.llm.security.api-key`,
    `infochat.llm.security.model`).
  - Issues `POST <base-url>/chat/completions` with a JSON body
    `{"model": "...", "messages": [{"role":"system","content":...},
    {"role":"user","content":...}]}`.
  - Uses `java.net.http.HttpClient` (built into JDK 25) for the
    transport. Adding a third-party HTTP client (e.g.
    `quarkus-rest-client-reactive`) is acceptable but requires
    a `pom.xml` add and is heavier than necessary for one
    Stage-2 call site; the recommended path is `java.net.http`.
  - Returns an `LlmResponse` carrying the model-produced text
    (the `choices[0].message.content` field of the
    OpenAI-compatible response shape).
  - NEVER implements Anthropic's native messages API
    (`AnthropicProvider` lives in T3-D).
- **`LlmRouter.java`** under
  `infochat-llm-adapter/src/main/java/io/infochat/llm/routing/`
  is an `@ApplicationScoped` CDI bean resolving
  `(ModelTask, scope_language) → LlmProvider` per
  `docs/spec/llm.md` §Per-task routing rules. The resolution
  priority:
  1. Explicit per-task override property (e.g.
     `infochat.llm.security.provider=openai-compatible`).
  2. Language-aware capability check: if the configured
     summarizer / translator provider declares the requested
     target language via `SUPPORTS_LANGUAGE_CS`-style capability,
     it is preferred (one call instead of two). Stage 2 doesn't
     exercise this — `SECURITY_JUDGE` is language-agnostic — but
     the code path exists in v1.
  3. Profile default for the task (e.g. on `laptop`,
     `infochat.llm.security.model=llama3.2:3b`).
  
  The router resolves to EXACTLY ONE provider per call. No
  fallback chain (`docs/spec/llm.md` §Per-task routing rules
  "No fallback chain in v1"). An unreachable provider degrades
  that task to its task-specific failure path; the router does
  NOT silently switch.
- **`LlmRouterStartupGuard.java`** is a Collector-side `@Startup`
  bean at `@Priority(150)` (between Flyway @ 100 and
  OutboxRehydrator @ 300 so router misconfiguration is caught
  before any post is picked up). When
  `infochat.llm.local-only=true`, the guard enumerates every
  configured per-task override's `base-url` and refuses to
  start if any base-url resolves to a non-loopback host. The
  rejection log line names the offending `ModelTask` and the
  offending `base-url`. Per `docs/spec/llm.md` §Per-task
  routing rules, the spec wording says "fails Provider
  startup" — treated as a doc-bug routing call (Stage 2 runs
  in the Collector, so the guard belongs there).
- **`security-judge.md`** under
  `infochat-llm-adapter/src/main/resources/prompts/` follows
  the template at `docs/design/05-llm-and-embeddings.md`
  §5.4.1: instructs the model to classify untrusted text;
  wraps content in `<<<UNTRUSTED_CONTENT id="{{id}}">>>...
  <<<END id="{{id}}">>>` with a per-call random UUID;
  enumerates the four labels `BENIGN`, `INJECTION`, `MALWARE`,
  `UNKNOWN` **each on its own dedicated line in the prompt
  template body** (one label per line, not a comma-separated
  inline list — this is the structural format the acceptance
  Item 11 grep counts against, distinct from design §5.4.1's
  one-line illustrative phrasing which is a doc-shape choice,
  not a prompt-shape requirement); demands a single-token
  reply.
- **`Stage2Worker.java`** under
  `infochat-collector/src/main/java/io/infochat/collector/eval/stage2/`
  is the Collector-side worker. It is invoked downstream of
  `Stage1Pipeline.process(...)` on the `stage1_flagged=true`
  branch (M1-032's Stage1Worker hands the `Stage1Result` to
  this worker directly when `stage1_flagged=true`, or emits to
  a stage2-queue channel — either shape is acceptable; document
  the choice). The worker:
  - Invokes `LlmRouter.forTask(SECURITY_JUDGE, "en")` to get
    the provider.
  - Loads the `security-judge.md` prompt template, substitutes
    a fresh `UUID.randomUUID()` into `{{id}}`, substitutes
    `originalBody` (from M1-032's `Stage1Result`) into
    `{{content}}`.
  - Invokes `provider.generate(SECURITY_JUDGE, systemPrompt,
    userPrompt)`.
  - Parses the reply by EXACT MATCH (after `.trim()`) against
    the four-token set. Anything else is treated as
    unparseable.
  - Retries exactly ONCE on (a) unparseable reply, (b)
    exception, (c) timeout. After retry exhausts, falls
    through to the infrastructure-failure path.
  - Dispatches to `Stage2VerdictHandler` for the verdict or
    infra-failure branch.
- **`Stage2VerdictHandler.java`** holds the verdict-vs-infra
  dispatch. Per Invariant 5 ("Posts in RAW with one or more
  stage-outcome flags already set resume from the next
  uncompleted stage; the per-stage flags are the durable
  cursor"), `status='RAW'` is the in-flight representation
  and the flag bitmap is the cursor. The transition to
  `status='READY'` happens ONLY in M1-034's Stage 5 after
  Tagger and Embedding complete:
  - **BENIGN**: `UPDATE post SET stage2_done=true` (post.status
    stays `'RAW'` — Tagger/Embedding still need to run);
    `UPDATE quarantine SET status='BENIGN_CLOSED',
    updated_at=now() WHERE post_id=:post_id AND
    flagged_by='stage1' AND status='PENDING'`. Redactions
    retained in `post.body`.
  - **INJECTION**: `UPDATE post SET status='QUARANTINED',
    stage2_done=true`. Quarantine rows stay PENDING.
    Tagger/Embedding skip QUARANTINED posts.
  - **MALWARE**: same as INJECTION.
  - **UNKNOWN**: same as INJECTION. The re-eval feed
    (per-post attempt counter, periodic re-submit) is T2-G.
  - **Infrastructure failure** under
    `release-on-stage2-failure=true`: `UPDATE post SET
    stage2_done=true, stage2_failed=true` (post.status stays
    `'RAW'` so Tagger/Embedding run; Stage 5 in M1-034
    advances to `'READY'` once they complete). Quarantine
    rows stay PENDING. Log WARN with
    `error_class='stage2.infra_failure'`. The design-tier
    `docs/design/04-security.md` §4.7 row "post.status='READY'"
    is treated as shorthand for "enters the release path
    that ends at READY" — the literal status flip is
    deferred to Stage 5 per Invariant 5.
  - **Infrastructure failure** under
    `release-on-stage2-failure=false`: `UPDATE post SET
    status='QUARANTINED', stage2_done=true,
    stage2_failed=true`. Quarantine rows stay PENDING. Log
    WARN with `error_class='stage2.infra_failure'`.
- **`StartupReleaseOnStage2FailureWarn.java`** is a
  Collector-side `@Startup` bean. When
  `infochat.security.release-on-stage2-failure=true` is in
  effect (profile-driven: laptop / pi true; vps / remote-llm
  false), it emits the WARN-level startup line from
  `docs/design/04-security.md` §4.7 and writes ONE
  `audit_log` row with `action='STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE'`.
- **`application.properties`** under
  `infochat-collector/src/main/resources/` declares the
  per-task property surface keys + default values for laptop
  profile: `infochat.security.release-on-stage2-failure`,
  `infochat.security.stage1.regex-timeout-ms` (consumed by
  M1-032's Stage1Pipeline but the property declaration lives
  on the consumer-collector side, NOT in the SPI module),
  `infochat.llm.security.base-url`,
  `infochat.llm.security.api-key`,
  `infochat.llm.security.model`,
  `infochat.llm.security.max-concurrency`,
  `infochat.llm.local-only`.
- **Bounded concurrency**: Stage2Worker uses a semaphore /
  ManagedExecutor bound by `infochat.llm.security.max-concurrency`
  (laptop 4 / vps 2 / pi 1 / remote-llm 8 per
  `docs/design/05-llm-and-embeddings.md` §5.7).
- **Three new tests**:
  - `LlmRouterTest.java` (unit) — priority resolution +
    no-fallback-chain.
  - `Stage2WorkerIT.java` (`@QuarkusTest`) — nine end-to-end
    scenarios.
  - `LocalOnlyConflictStartupIT.java` (`@QuarkusTest`) —
    startup refusal on local-only + remote-base-url conflict.
- `mvn -B clean verify` from repo root exits 0.

## Implementation notes

- **Option B (3 tickets) is the T1-D shape**, locked in
  M1-032's Implementation notes. Stage 2 is the middle ticket;
  it consumes M1-032's Stage1Result and leaves the post in
  one of two states for M1-034 to pick up:
  - `status='RAW'` with `stage2_done=true` (BENIGN or
    infra-fail-release path) — TaggerWorker picks it up via
    `status='RAW' AND stage1_done=true AND (stage1_flagged=false
    OR stage2_done=true) AND tagger_done=false`.
  - `status='QUARANTINED'` with `stage2_done=true` (INJ /
    MAL / UNK verdict, or infra-fail with
    `release-on-stage2-failure=false`) — excluded from
    downstream pickup by the `status='RAW'` filter.
  The status flip to `'READY'` is M1-034's Stage 5 concern
  per Invariant 5.
- **Stage-2 hand-off from M1-032.** The simplest shape is
  in-process invocation: M1-032's Stage1Worker calls
  Stage1Pipeline → if `stage1_flagged=true`, calls
  Stage2Worker directly with the `Stage1Result`. The
  alternative is a separate channel
  (`@Channel("stage2-queue")`) — adds machinery for no
  benefit at v1 scale. Pick the in-process shape; document
  the rejected channel alternative in the commit message.
- **Stage-1-clean posts skip Stage 2.** When
  `stage1_flagged=false`, the post advances to Tagger
  directly (M1-034 reads `stage1_done=true AND
  (stage1_flagged=false OR stage2_done=true)`). Stage 2 is
  never invoked on clean Stage 1 output per
  `docs/spec/security.md` §Ingest pipeline "Stage 2 — LLM
  judge. Only invoked when Stage 1 flagged something."
- **Prompt template substitution.** Use a small in-process
  template substitution (e.g. `String.replace("{{id}}",
  uuid.toString()).replace("{{content}}", originalBody)`).
  Mustache (per design §5.4) is overkill for two
  substitutions and adds a dep. Pick the simple
  `String.replace` shape; document the choice.
- **The per-call delimiter UUID.** Generate a fresh
  `UUID.randomUUID()` PER CALL (not per process, not per
  post) per `docs/spec/security.md`
  §Prompt-injection-aware prompt shape and
  `docs/design/04-security.md` §4.3 "The `{uuid}` is a fresh
  `UUID.randomUUID()` per call (not per process, not per
  post — per individual prompt assembly)."
- **The `OpenAiCompatibleProvider` HTTP client.** v1 uses
  `java.net.http.HttpClient` with a per-call timeout
  matching `infochat.llm.security.timeout-ms` (a new
  property; default per profile is a design-tier decision —
  recommend `30s` baseline). The client is a per-bean
  singleton (HttpClient is thread-safe). Connection pooling
  is automatic. No third-party HTTP dep needed; document
  the rejected `quarkus-rest-client-reactive` alternative.
- **The `LlmRouter`'s priority logic.** A simple chain:
  ```
  resolve(task, lang):
    overrideKey = "infochat.llm." + task.name().toLowerCase() + ".provider"
    if config.has(overrideKey):
      return lookupProviderByKey(config.get(overrideKey))
    // language-aware capability check (only meaningful for SUMMARIZER/TRANSLATOR)
    if task in {SUMMARIZER, TRANSLATOR} and lang != "en":
      capableProvider = findProviderWithLangCap(lang)
      if capableProvider != null: return capableProvider
    return profileDefaultProvider(task)
  ```
  For Stage 2 only the override + profile-default branches
  matter; the language-aware branch is exercised by the
  Tagger and future summarizer call sites.
- **The local-only conflict guard's IP check.** Either
  reuse `IpBlocklist` from M1-024 (the
  infochat-ssrf module — but that introduces a dependency
  from `infochat-llm-adapter` onto `infochat-ssrf` which is
  a wider scope than needed) OR implement a simpler check
  inline: `URI.getHost() ∈ {"localhost", "127.0.0.1",
  "::1"}` after DNS-resolving the host. The simpler check
  is recommended; document the trade-off (the loophole is a
  DNS-rebind attack where the host resolves to loopback at
  startup but to a remote IP at call time — but the spec
  says "checked once at startup, not per call" so that
  loophole is acceptable here; the per-call SSRF defense is
  separate and lives in `infochat-ssrf`'s
  `SsrfGuardedHttpClient`, not in the LLM-call path).
- **Quarkus startup-failure mechanism.** Three options:
  1. `@Startup` bean throws on construction → Quarkus refuses
     to start (the default).
  2. `@Observes StartupEvent` throws.
  3. `Quarkus.asyncExit(1)` with a fatal log line.
  
  (1) is the cleanest shape — Quarkus's default behavior is
  to refuse to start when a `@Startup` bean throws, and the
  fatal log line is the natural by-product of the thrown
  exception's stack trace. Pick (1); document (3) as an
  alternative.
- **Test stub for `LlmProvider`.** Hand-written
  `TestStubLlmProvider` **implemented as a nested static
  class inside `Stage2WorkerIT.java`** (NOT a top-level
  class in its own .java file — the nested placement keeps
  `files_scope` at 13 entries and binds the stub's lifecycle
  to the test class that exercises it). The stub implements
  `LlmProvider` directly and exposes:
  - `setNextResponse(String)` — canned reply for the next
    `generate(...)` call; FIFO queue if multiple are set.
  - `setNextResponses(String...)` — convenience for the
    schema-violating / empty-reply scenarios where the stub
    must return distinct values on call 1 and call 2.
  - `failNext()` / `failAll()` — make the next call (or
    every call) throw an `IOException` simulating
    LLM-unreachable.
  - `callCount()` — exposes the per-test invocation count
    for the retry-policy assertions (scenarios 28e-28g).
  The stub is selected via Quarkus's `@Alternative
  @Priority(Integer.MAX_VALUE)` for the test profile, so
  CDI resolves it in place of `OpenAiCompatibleProvider`.
  Mockito at the SPI level is acceptable but a hand-written
  stub is simpler for the nine `Stage2WorkerIT` scenarios
  and matches the M1-027/M1-028 style. Nesting also keeps
  the stub out of any production source root and out of
  Quarkus's main-context CDI scan.
- **The `StartupReleaseOnStage2FailureWarn` placement on the
  Collector.** Per `docs/design/04-security.md` §4.7 the
  warning is described as "the Provider emits a prominent
  WARN-level startup line." Stage 2 runs in the Collector,
  so the WARN belongs on the Collector startup chain.
  Treat the design-tier "Provider" wording as a doc bug
  (the design table for `@Priority` ordering lists
  Provider beans only because the Provider is the user-facing
  service, but Stage 2 is a Collector concern). Document
  this routing choice in the file's JDoc.
- **`audit_log` write on the Collector.** The Collector has
  `INSERT`-only on `audit_log` per
  `docs/spec/security.md` §DB roles ("Collector role —
  INSERT-only on audit_log"). The
  `STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE` row carries
  `actor_user_id=NULL`, `actor_contact_id=NULL`,
  `actor_adapter=NULL`, `action='STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE'`,
  `target_kind='collector'`, `target_id=<host_id or pid>`,
  `details_json={"profile": "<active-profile-name>"}`. The
  exact column shape depends on M1-008a's audit_log schema —
  read the V5 migration to confirm the column set.

## Big-picture notes

- **The verdict-vs-infrastructure split is the heart of the
  security model.** Per `docs/spec/security.md` §Failure
  handling: a Stage 2 verdict (BENIGN, INJECTION, MALWARE,
  UNKNOWN) is what the judge said; a Stage 2 infrastructure
  failure (LLM unreachable, timeout, unparseable reply
  after retry) is whether the judge ran at all. They have
  different fallbacks because they have different threat
  profiles — a verdict of INJECTION is evidence of attack;
  a timeout is evidence the network is flaky. Conflating
  them would mean either auto-releasing on attack (a
  schema-violating attacker-influenced reply parsed as
  BENIGN) or quarantining on every network blip (an outage
  drowns the admin queue). The split is non-negotiable.
- **`release-on-stage2-failure=true` is a profile-specific
  trade-off.** On laptop / pi, the LLM is local Ollama,
  prone to crashes under memory pressure; release-on-failure
  keeps the bot useful when the judge crashes. On vps /
  remote-llm, the LLM is production-quality, an outage is a
  real outage, and the operator pays for monitoring. The
  default per profile inverts the trade-off. The startup
  WARN + audit row makes the operator's posture auditable.
- **The `release-on-stage2-failure=true` path releases with
  Stage 1 redactions only.** Per `docs/spec/security.md`
  §Ingest pipeline "Stage 1 is a coarse filter, not a
  complete defense. The regex set is English-language and
  pattern-based; multilingual, paraphrased, base64-encoded,
  and otherwise obfuscated injection bypasses Stage 1 by
  design." On the profiles where the flag is `true`, this
  is the user-facing posture: when Stage 2 is down,
  multilingual / paraphrased / obfuscated injection content
  can reach the summarizer or chat agent (T1-F surfaces)
  with only Stage 1's coarse English-only redactions. The
  startup WARN makes operators aware that they are
  honouring this trade-off.
- **UNKNOWN is treated as a soft injection signal.** Per
  `docs/spec/security.md` §Failure handling "The judge
  model treating UNKNOWN as a soft injection signal is
  intentional: a degraded judge must never auto-release."
  An attacker who crafts content that initially looks
  UNKNOWN to the judge (degraded model, warm-up state, edge
  case in the prompt template) could otherwise bypass the
  security boundary. The re-eval queue (T2-G) gives a
  healthy judge a chance to produce a definitive verdict
  later; in M1, UNKNOWN means QUARANTINED with
  `stage2_done=true` and the re-eval feed flag implicit in
  `(post.status='QUARANTINED' AND stage2_done=true AND
  stage2_failed=false)`.
- **The router never falls back.** Per
  `docs/spec/llm.md` §Per-task routing rules "No fallback
  chain in v1. The router resolves (ModelTask,
  scope_language) to exactly one LlmProvider; an
  unreachable provider degrades that task to its
  task-specific failure path." This is a deliberate v1
  simplification — fallback chains add cross-provider
  state (which provider failed which call) and
  observability complexity (which provider produced this
  reply). v2 may add fallback chains; v1 is single
  resolution per call. Operators who require HA on Stage 2
  over-provision the configured provider.
- **The local-only conflict guard runs at startup.** Per
  `docs/spec/llm.md` §Per-task routing rules "Local-only
  is the most-restrictive posture ... checked once at
  startup, not per call, so an operator cannot accidentally
  route one task remote while believing the deployment is
  local-only." A per-call check would be redundant (the
  override property is read at startup and doesn't change
  at runtime) and would add overhead to every Stage 2
  call. The single-shot startup check is the right shape.
- **The router is a Collector-and-Provider-shared SPI.**
  `LlmRouter` lives in `infochat-llm-adapter` (a Maven
  module both the Collector and the Provider depend on per
  `docs/design/01-architecture.md` §1.4.3 + §1.5).
  Collector uses SECURITY_JUDGE + TAGGER; Provider uses
  SUMMARIZER + CHAT_AGENT + TRANSLATOR. The router code
  path is the same for all five tasks; the per-task
  configuration is the only thing that varies.
- **Subticket isolation against M1-032 / M1-034.** M1-032
  lives under `infochat-collector/.../eval/stage1/` and
  adds V10. M1-034 lives under
  `infochat-collector/.../eval/tagger/` +
  `.../eval/embedding/` and adds V11. This ticket lives
  under `infochat-collector/.../eval/stage2/` and
  `infochat-llm-adapter/.../impl/` + `.../routing/`. The
  three `files_scope` lists are disjoint at the file path
  level except for `application.properties`, which both
  M1-033 and M1-034 amend (sequential, not parallel —
  M1-034 blocks on M1-033). M1-033 blocks on M1-032
  (Stage 2 fires only on Stage-1 hits); M1-034 blocks on
  M1-033 (Tagger picks up `status='RAW' AND stage1_done=true
  AND (stage1_flagged=false OR stage2_done=true) AND
  tagger_done=false`, the cursor Stage 2 advances).
- **EntityExtractor is intentionally absent.** Per
  M1-032's "EntityExtractor is not in T1-D" big-picture
  note. T1-D's pipeline goes S1 → (S2 if S1 hit) → Tagger
  → Embedding → READY. Stage 3 (entity extraction) is T2
  territory.
- **The OpenAI-compatible HTTP shape is the v1 wire
  contract.** `OpenAiCompatibleProvider` issues
  `POST /chat/completions` with the OpenAI message-array
  body shape. Ollama, llama.cpp, OpenAI, OpenRouter, and
  NanoGPT all speak this shape per
  `docs/design/05-llm-and-embeddings.md` §5.3.
  AnthropicProvider (T3-D) speaks the native Anthropic
  messages API which is a different wire shape; it lands
  later and shares the `LlmProvider` interface but not the
  wire code.

## Out-of-scope expansion

- **Stage 1 deterministic security, HTML sanitizer,
  prompt-injection regex set, watchdog, placeholder ids,
  quarantine-row insertion.** M1-032 territory.
- **Tagger, controlled-vocabulary validation,
  bootstrap-tags fallback.** M1-034.
- **EmbeddingProvider impl, post_embedding table,
  dimensionality guard, model-identity guard.** M1-034.
- **`post.status → READY` UPDATE, `post.ready_at` set,
  `pg_notify('new_post', …)` emit.** M1-034 at Stage 5.
  This ticket leaves `post.status='RAW'` on every BENIGN
  and on every `release-on-stage2-failure=true` infra
  failure — the status flip to `'READY'` is deferred to
  M1-034's `ReadyPromoter` after Tagger and Embedding
  complete, per Invariant 5 ("`RAW` plus the flag bitmap
  is the complete representation of in-flight evaluation
  state") and `docs/design/01-architecture.md` §1.3.4
  step 5. The design wording at `docs/design/04-security.md`
  §4.2 ("On BENIGN: post released `post.status='READY'`")
  and §4.7 ("release as `READY`") is read as shorthand for
  "enters the release path that ends at READY" — the
  literal status flip lives at Stage 5. See acceptance
  items 17 and 19 and the Alternatives Considered note
  rejecting an early status flip.
- **EntityExtractor.** T2.
- **Re-evaluation job, per-post attempt counter,
  QUARANTINED → NEEDS_REVIEW, per-source UNKNOWN
  auto-disable, RE_EVAL_RELEASED audit, source.status →
  'failed'.** T2-G. Stage 2 SETS the cursor flags that
  feed the re-eval job; the job itself is T2-G.
- **Throttled admin notifier wiring.** T2-G. Stage 2
  logs at WARN with `error_class='stage2.infra_failure'`.
- **LLM output sanitizer.** T1-F. Stage 2 output is a
  closed 4-token label set; sanitizer is not relevant.
- **`/quarantine list/approve/reject` admin commands and
  the stored procedures.** T2-G. The PENDING →
  BENIGN_CLOSED transition here is a state-machine move
  on the existing quarantine row, NOT an admin command.
- **Provider-side `quarantine_review` LISTEN listener.** M2.
- **New Flyway migration.** `migration_touch: false`. V10
  from M1-032 covers Stage 2's needs.
- **AnthropicProvider.** T3-D.
- **TranslationProvider concrete impl.** T1-F.
- **Chat-agent recall tool, five-tool allowlist.** T2-D.
- **Embedding-provider router.** M1-034 (separate
  resolution path from the (ModelTask, scope_language)
  router authored here per `docs/spec/llm.md` §SPI shape).
- **Per-task fallback chain.** v2 candidate.
- **Prometheus metric emit** for `eval_stage2_verdict_total`
  etc. Observability ticket later. Stage 2 logs at INFO/WARN
  with structured fields the exporter can pick up.
- **V1..V10 migration changes.** Frozen.
- **M1-007b SPI surface changes.** Interfaces frozen.
- **`infochat-provider` module changes.** Collector +
  llm-adapter only.
- **User-visible rendering of verdicts.** Internal-only
  signal; never reaches a user.

## Authorized test changes

- (none — this ticket adds three new test files
  (`LlmRouterTest.java` under
  `infochat-llm-adapter/src/test/java/io/infochat/llm/routing/`,
  `Stage2WorkerIT.java` and `LocalOnlyConflictStartupIT.java`
  under
  `infochat-collector/src/test/java/io/infochat/collector/eval/stage2/`).
  No pre-existing tests are modified. M1-007b's
  `LlmSpisLoadTest.java` continues to pass — the SPI surface
  stays stable; this ticket adds the FIRST concrete impl and
  the router but leaves the interfaces unchanged.)

## Alternatives considered

- **Option A — combine Stage 1 + Stage 2 into one ticket.**
  Rejected at the top of this authoring session. See
  M1-032's "Alternatives considered" for the full rationale.
- **Option C — split Tagger off from Embedding.** Rejected.
  See M1-032's "Alternatives considered."
- **Use `quarkus-rest-client-reactive` for the
  OpenAI-compatible HTTP client.** Acceptable but heavier
  than needed for one Stage-2 call site. `java.net.http`
  is built into the JDK; no new dep; the JSON
  serialization for the request/response can be done with
  `quarkus-jackson` (already on the BOM via Quarkus
  Vert.x). Recommended path: `java.net.http`. Document
  the rejected `rest-client-reactive` alternative in the
  commit message.
- **Use Mockito to stub `LlmProvider` in the IT.**
  Acceptable but the hand-written `TestStubLlmProvider`
  selected via `@Alternative` matches M1-027 / M1-028
  style and the SPI contract is exercised end-to-end
  (the `@Inject LlmRouter` resolves to a real router that
  picks the alternative provider — the full router path
  is exercised, not just the worker's call to the stub).
- **Implement a per-call provider-unreachability check
  inside `LlmRouter`.** Rejected on spec grounds. Per
  `docs/spec/llm.md` §Per-task routing rules "No fallback
  chain in v1. The router resolves ... to exactly one
  LlmProvider; an unreachable provider degrades that
  task to its task-specific failure path ... and does
  NOT silently switch." The unreachability check is at
  the call site (Stage 2 worker), not in the router.
- **Run the local-only conflict check per call.**
  Rejected on spec grounds. Per `docs/spec/llm.md`
  §Per-task routing rules "This is checked once at
  startup, not per call." The startup-time check is the
  spec-pinned shape.
- **Use Mustache for the prompt template substitution.**
  Acceptable but overkill. `String.replace("{{id}}", ...)`
  + `String.replace("{{content}}", ...)` is two lines and
  has no dep. Mustache is appropriate for the more complex
  templates (Tagger's vocabulary loop in M1-034) but not
  for Stage 2's two-substitution case.
- **Land the AnthropicProvider here so the SPI is
  exercised against two providers from day one.** Rejected
  per session-grouping-plan §Tier 3. T3-D is dedicated to
  AnthropicProvider (it speaks the native Anthropic
  messages API which is a different wire shape and needs
  its own focused diff). Landing both providers here
  doubles the wire-protocol surface and obscures the
  OpenAI-compatible correctness argument that the
  reviewer is checking.
- **Skip the `StartupReleaseOnStage2FailureWarn` here and
  land it with the throttled admin notifier in T2-G.**
  Rejected. The WARN and audit row are
  `docs/design/04-security.md` §4.7 commitments
  ("written to the audit_log once per process start with
  action='STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE' so the
  operating posture is reconstructible from audit
  history"). The audit row is a per-startup commitment,
  not a notifier event — landing it with the notifier
  would defer a security commitment behind unrelated
  T2-G scope. The WARN + audit row land here.
- **Wire the `LlmRouterStartupGuard` on the Provider per
  the literal spec wording.** Rejected. Stage 2 runs in
  the Collector; the security-judge provider's base-url
  conflict is a Collector-side concern. The spec wording
  "fails Provider startup" is a doc bug to be flagged in
  a separate `spec:` commit; in this ticket the guard
  runs on the Collector. Document the routing call in
  Implementation notes.
- **Make `STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE` a
  per-Stage-2-call audit row rather than a per-startup
  row.** Rejected on spec grounds. The design explicitly
  says "once per process start" — per-call would drown
  the audit_log under sustained Stage 2 outages.

## Implementation outline (M1-033, generated by Plan subagent on 2026-05-16)

### Files to touch (12 of 12)

1. **modify**: `infochat-llm-adapter/pom.xml` — keep current minimal deps; verify `java.net.http` (JDK 25 built-in) suffices, add nothing. Documents the "no third-party HTTP client" choice as a no-op modification to confirm acceptance Item 1.
2. **create**: `infochat-llm-adapter/src/main/java/io/infochat/llm/impl/OpenAiCompatibleProvider.java` — first concrete `LlmProvider`; `POST <base-url>/chat/completions` via `java.net.http.HttpClient`; reads `(base-url, api-key, model, timeout-ms)` per-task via `@ConfigProperty`; not CDI-eager (resolved through router lookup).
3. **create**: `infochat-llm-adapter/src/main/java/io/infochat/llm/routing/LlmRouter.java` — `@ApplicationScoped` CDI bean exposing `forTask(ModelTask, String scopeLanguage) → LlmProvider`; priority chain: per-task override → language-aware capability → profile default; returns exactly one provider, no fallback chain.
4. **create**: `infochat-llm-adapter/src/main/java/io/infochat/llm/routing/LlmRouterStartupGuard.java` — `@Startup @Priority(150) @ApplicationScoped`; when `infochat.llm.local-only=true`, enumerates every per-task `base-url`, rejects non-loopback hosts; throws from `@PostConstruct` so Quarkus refuses to start; log line names offending `ModelTask` + `base-url`.
5. **create**: `infochat-llm-adapter/src/main/resources/prompts/security-judge.md` — system+user prompt template; `<<<UNTRUSTED_CONTENT id="{{id}}">>>...{{content}}...<<<END id="{{id}}">>>` wrapper; each of the four labels `BENIGN`, `INJECTION`, `MALWARE`, `UNKNOWN` on its OWN distinct line (clarity Warning 1 resolution); demands a single-token reply.
6. **create**: `infochat-collector/src/main/java/io/infochat/collector/eval/stage2/Stage2Worker.java` — `@ApplicationScoped`; invoked **in-process** from `Stage1Worker` when `Stage1Result.flagged()=true` AND `!quarantinedByWatchdog` (Stage 1 watchdog/sanitizer paths already wrote `QUARANTINED` directly; Stage 2 only runs on regex hits that left `RAW`). Calls `router.forTask(SECURITY_JUDGE, "en")`; loads template via `String.replace` substitution; uses `originalBody`; bounded by `Semaphore` sized from `infochat.llm.security.max-concurrency`; retry-once on (unparseable / exception / timeout); dispatches to `Stage2VerdictHandler`.
7. **create**: `infochat-collector/src/main/java/io/infochat/collector/eval/stage2/Stage2VerdictHandler.java` — `@ApplicationScoped`; switch expression on parsed label; per-verdict SQL UPDATE on `post` + state-machine UPDATE on `quarantine`. Single-transaction writes via the same `inTransaction(...)` shape as `Stage1Pipeline`. Reads `@ConfigProperty infochat.security.release-on-stage2-failure`. Canonical error_class string `stage2.infra_failure` (public static final).
8. **create**: `infochat-collector/src/main/java/io/infochat/collector/eval/stage2/StartupReleaseOnStage2FailureWarn.java` — Collector-side `@Startup @Priority(150)` bean (sits alongside the router guard — neither needs to run before the other). When the flag is `true`, logs the WARN line verbatim from design §4.7 and writes ONE `audit_log` row with `action='STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE'`, `target_kind='system'` (the closed CHECK enumerates `system`), `target_id=<host_id-pid>`, `details_json={"profile":"<active-profile-name>"}`.
9. **modify**: `infochat-collector/src/main/resources/application.properties` — append the new keys: `infochat.security.release-on-stage2-failure` (true at base for test-profile fallback parallel to existing pattern); `infochat.llm.security.base-url` / `api-key` / `model` / `max-concurrency` / `timeout-ms`; `infochat.llm.local-only` (default false). Add `%laptop` / `%vps` / `%pi` / `%remote-llm` namespaced overrides per §5.7 profile table (laptop 4 / vps 2 / pi 1 / remote-llm 8 for max-concurrency; true/true/false/false for release-on-stage2-failure). Keys stay lexicographically sorted within each profile block per the existing convention.
10. **create**: `infochat-llm-adapter/src/test/java/io/infochat/llm/routing/LlmRouterTest.java` — plain JUnit5 unit test (no `@QuarkusTest`); covers the four named behaviors with distinct `@Test` methods.
11. **create**: `infochat-collector/src/test/java/io/infochat/collector/eval/stage2/Stage2WorkerIT.java` — `@QuarkusTest` IT against DevServices Postgres; **nests** `TestStubLlmProvider` as a `@Alternative @Priority(Integer.MAX_VALUE)` static inner class (resolves clarity Warning 4 without adding file #13); nine per-scenario `@Test` methods with descriptive names.
12. **create**: `infochat-collector/src/test/java/io/infochat/collector/eval/stage2/LocalOnlyConflictStartupIT.java` — `@QuarkusTest` + `@TestProfile` setting `infochat.llm.local-only=true` and `infochat.llm.security.base-url=https://api.openai.com/v1`; uses `QuarkusTestProfile` to assert that Quarkus boot fails (e.g. `@TestProfile` + an `assertThrows` on the test method body that re-bootstraps an isolated `Quarkus.run`, OR — simpler — write the test to assert that the guard's check method, exposed as package-private for test, throws when invoked with the conflict configuration; document the chosen mechanism in the class JDoc per ticket Item 30).

**Budget check**: 12 of 12 files used; `TestStubLlmProvider` nested inside `Stage2WorkerIT.java` per clarity Warning 4 (recommended decision).

### Tests
- **add**: `infochat-llm-adapter/src/test/java/io/infochat/llm/routing/LlmRouterTest.java` — four per-behavior `@Test` methods covering acceptance Item 26's four behaviors with method names that mechanically reveal coverage (clarity Warning 2 resolution):
  - `forTaskReturnsConfiguredProviderForSecurityJudgeWithProfileDefault` — base profile default resolution.
  - `perTaskOverridePropertyTakesPriorityOverProfileDefault` — set `infochat.llm.security.provider=<other>` and assert override wins.
  - `forTaskReturnsExactlyOneProviderNotAList` — compile-time signature assertion (`LlmProvider`, not `List<LlmProvider>`) + runtime assert non-null singular return.
  - `summarizerWithCzechScopeLanguagePrefersProviderWithSupportsLanguageCsCapability` — exercise the language-aware branch even though Stage 2 doesn't use it; assert a provider declaring `SUPPORTS_LANGUAGE_CS` is preferred over one without when `lang="cs"` and task is `SUMMARIZER`.
- **add**: `infochat-collector/src/test/java/io/infochat/collector/eval/stage2/Stage2WorkerIT.java` — nine per-scenario `@Test` methods (clarity Warning 3 resolution) covering acceptance Item 28:
  - `benignVerdictAdvancesStage2DoneAndTransitionsQuarantineToBenignClosedAndKeepsPostRaw`
  - `injectionVerdictMovesPostToQuarantinedAndLeavesQuarantineRowsPending`
  - `malwareVerdictMovesPostToQuarantinedAndLeavesQuarantineRowsPending`
  - `unknownVerdictMovesPostToQuarantinedAndLeavesQuarantineRowsPending`
  - `schemaViolatingReplyOnBothCallsTakesInfraFailurePathUnderActiveProfile`
  - `emptyReplyOnBothCallsTakesInfraFailurePathUnderActiveProfile`
  - `unreachableLlmAfterRetryExhaustionTakesInfraFailurePath`
  - `releaseOnStage2FailureTrueProfileAdvancesPostWithStage2FailedTrueKeepsRawRetainsRedactions`
  - `releaseOnStage2FailureFalseProfileQuarantinesPostWithStage2FailedTrue`
  - Plus nested `TestStubLlmProvider implements LlmProvider` + helper static methods for `setNextResponse` / `failNextCall` / counters for "called twice on retry" assertions.
- **add**: `infochat-collector/src/test/java/io/infochat/collector/eval/stage2/LocalOnlyConflictStartupIT.java` — single `@Test` `localOnlyTrueWithRemoteSecurityJudgeBaseUrlRefusesStartup` asserting startup-failure mechanism (acceptance Item 30).
- **modify**: none. **No pre-existing test modifications.** Confirmed against ticket §"Authorized test changes" — "(none)". Authorization clause is well-formed.

### Cross-cutting concerns
- **Determinism boundary** (`docs/spec/llm.md` §Determinism boundary): the verdict label set is closed; the parser MUST be exact-match-against-4-strings, never a fuzzy match or "contains BENIGN" check. Anything outside the closed set is unparseable per spec.
- **Plain-text formatting** (CLAUDE.md key conventions): Stage 2 outputs are never user-visible, so no rendering concern, but the WARN startup line goes to logs not users — keep it plain text without markdown.
- **Per-(user, scope) isolation**: not applicable here (Stage 2 is ingest-side, no user context).
- **Audit-log INSERT-only on Collector role** (`docs/spec/security.md` §DB roles): the `STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE` row is the first new `audit_log` write outside of `BootstrapLoader` — confirm the collector role has INSERT on `audit_log` (V5 confirms it does); the bean uses raw JDBC, not an ORM.
- **Invariant 5** (`docs/spec/schema.md` §Invariants): `post.status='RAW'` is the in-flight cursor; the per-stage `*_done` flags are the durable cursor. **BENIGN and infra-fail-release MUST NOT touch `post.status`** — leave it `RAW`; only the verdicts that yield QUARANTINED touch status. The literal flip to `READY` is M1-034's Stage 5 concern.
- **Stage 2 fires ONLY when `stage1_flagged=true`** AND `!quarantinedByWatchdog` — the watchdog/sanitizer paths in M1-032 already wrote `status='QUARANTINED'` and set `stage1_done=true`; re-invoking Stage 2 against those rows would be incorrect. The hand-off branch in `Stage1Worker` must check `Stage1Result.flagged() && !Stage1Result.quarantinedByWatchdog()`.
- **Per-call random UUID** (`docs/design/04-security.md` §4.3): fresh `UUID.randomUUID()` per individual prompt assembly. Cannot be cached on the worker bean or per-post.
- **Original (pre-redaction) body** (`docs/spec/security.md` §Ingest pipeline): the LLM judge sees `Stage1Result.originalBody()`, not `redactedBody()`. The DB `post.body` retains the redacted form throughout — Stage 2 never writes back the original body even on BENIGN.
- **Retain redactions on every release path** (acceptance Item 22): neither BENIGN nor `release-on-stage2-failure=true` replaces `[REDACTED:<id>]` placeholders in `post.body`. Only `/quarantine approve` (T2-G) lifts redactions.
- **Idempotency on re-enqueue** (`OutboxRehydrator` Invariant 5 commentary): Stage 2 must short-circuit when `post.stage2_done=true` — parallel to `Stage1Worker`'s `stage1_done` short-circuit. If re-invocation happens after Stage 1 advances but before the Stage 2 transaction commits, the retry of Stage 2 is idempotent because the verdict handler's SQL is `UPDATE` on `post` keyed by `(id, fetched_at)` — repeating the UPDATE is harmless; the quarantine state-machine `PENDING → BENIGN_CLOSED` is also idempotent (the `WHERE status='PENDING'` predicate blocks double-transitions).
- **No defensive code for impossible scenarios** (CLAUDE.md §No defensive code): no null-checks on `Stage1Result` fields when the caller is `Stage1Worker` (both internal); but DO validate at the LLM-reply-string boundary (system boundary — `trim().equals()` is the system-boundary check, the four-label switch is the closed match).
- **Bounded concurrency back-pressure** (`docs/spec/llm.md` §Bounded concurrency): the semaphore caps in-flight Stage 2 calls; when saturated, the `@Incoming` chain back-pressures rather than dropping work. Failed `tryAcquire` after a timeout is itself an infra failure (treat as the retry-once-then-fallback path).
- **Doc-bug routing of the startup guard + WARN bean to the Collector**: the spec wording in `docs/spec/llm.md` §Per-task routing rules ("fails Provider startup") and `docs/design/04-security.md` §4.7 ("the Provider emits...") names the Provider, but Stage 2 runs in the Collector. Document this routing call in each file's JDoc per Implementation notes; do not propose a spec edit in this ticket.

### Implementation order
1. **`security-judge.md` prompt template** — pure resource file, no Java dependencies; landing this first lets every Stage 2 unit/IT test load the real template instead of an inline stub. Each label on its own distinct line per clarity Warning 1.
2. **`OpenAiCompatibleProvider.java`** — first concrete `LlmProvider`. Build with `java.net.http.HttpClient`, per-task `@ConfigProperty` injection. Compiles against M1-007b's frozen SPI. Standalone module: build + unit-test before wiring routing.
3. **`LlmRouter.java`** — depends on `OpenAiCompatibleProvider` being discoverable (CDI lookup or constructor injection). Priority resolution method first; the language-aware branch is the second layer; the profile-default lookup last. Returns singular `LlmProvider`.
4. **`LlmRouterStartupGuard.java`** — depends on `LlmRouter` only conceptually (it inspects properties, not the router instance). Wire `@Startup @Priority(150)` BEFORE the worker — otherwise a test boot under a misconfiguration would reach Stage 2's call site before refusing startup.
5. **`LlmRouterTest.java`** — unit test for the router; can be written in parallel with step 3 but commit after the router so the test isn't speculative. Confirms the four behaviors in isolation.
6. **`application.properties`** — declare new keys + profile defaults BEFORE `Stage2Worker` reads them, otherwise Quarkus boot fails on a missing required property. Properties land here in source order: `infochat.llm.local-only`, then the `infochat.llm.security.*` block, then the new `infochat.security.release-on-stage2-failure`. Keep lexicographic order inside each `%profile.` block per existing convention.
7. **`Stage2VerdictHandler.java`** — the SQL writer. Build the BENIGN / INJECTION / MALWARE / UNKNOWN / infra-true / infra-false branches as a switch expression on an internal enum (`Verdict { BENIGN, INJECTION, MALWARE, UNKNOWN, INFRA_FAILURE }`). The handler is independent of `Stage2Worker` and can be unit-tested in isolation if useful. Transactional shape matches `Stage1Pipeline.inTransaction(...)` — same JDBC pattern, ApplicationScoped service uses `DataSource` injection.
8. **`Stage2Worker.java`** — the orchestrator. Reads `Stage1Result.originalBody()`, builds prompt, calls `LlmRouter.forTask(SECURITY_JUDGE, "en")`, parses verdict via switch, dispatches to `Stage2VerdictHandler`. Semaphore allocation in `@PostConstruct` from the max-concurrency property.
9. **Wire `Stage1Worker → Stage2Worker`** — modify is **not** authorized; the ticket's `files_scope` does not include `Stage1Worker.java`. **THIS IS A RISK** — see Risks section.
10. **`StartupReleaseOnStage2FailureWarn.java`** — `@Startup @Priority(150)` bean. Standalone, no compile dependency on Stage 2 code; can land anytime after `application.properties` declares the flag. Writes one `audit_log` row.
11. **`Stage2WorkerIT.java`** — full end-to-end IT covering all nine scenarios. Stub provider as nested `@Alternative` class. Requires DevServices Postgres + the alternative wins resolution against the real `OpenAiCompatibleProvider`.
12. **`LocalOnlyConflictStartupIT.java`** — startup-refusal test. Either via `@QuarkusTestProfile` that triggers the conflict and asserts boot failure via `@QuarkusTestResource`, or by exposing a package-private `validateLocalOnlyConfiguration(...)` on the guard for direct invocation. Document the chosen mechanism in the test class JDoc.
13. **Full `mvn -B clean verify`** — confirms no regression on M1-022..M1-029 + M1-032.

**Why this order**: provider before router (router resolves providers); router + guard before worker (worker depends on the router); properties before worker (Quarkus boot reads them); verdict handler before worker (worker dispatches to it); WARN bean is fully independent so it lands wherever; IT tests last because they require the full chain.

**Wrong order pitfalls**:
- Declaring `application.properties` keys AFTER writing `Stage2Worker` will fail Quarkus boot in any IT.
- Adding the local-only guard AFTER the worker means a misconfigured base-url could exercise the worker's call path before the guard's refusal — broken intermediate state.
- Writing the WARN bean BEFORE the property declaration would fail boot under base profile.

### Risks
- **`Stage1Worker.java` wiring is required but not in `files_scope`**. The ticket's Implementation notes say "M1-032's Stage1Worker calls Stage1Pipeline → if `stage1_flagged=true`, calls Stage2Worker directly with the `Stage1Result`." But `Stage1Worker.java` is NOT in the M1-033 `files_scope` list. This is a **scope ambiguity**: the in-process hand-off requires injecting `Stage2Worker` into `Stage1Worker` and adding the conditional dispatch — a non-trivial diff to a file not authorized for change. **Escalation: refine**. Either (a) add `Stage1Worker.java` to `files_scope` and bump `files_budget` to 13 (this raises off-by-one to clarity Warning 4 territory), or (b) accept the alternative channel-based shape (`@Channel("stage2-queue")`) which the Implementation notes explicitly reject — that shape would let Stage 2 subscribe via `@Incoming` without modifying `Stage1Worker`, but it contradicts the documented "pick the in-process shape" guidance. Recommend refine to add `Stage1Worker.java` to the scope before implementation begins.
- **`Stage1Worker`'s test (`Stage1WatchdogIT`, `Stage1PipelineIT`) may break when `Stage2Worker` is injected**. If `Stage2Worker` autowires a `LlmRouter` that demands `infochat.llm.security.base-url` at boot, the Stage 1 tests will fail to start unless they either (a) provide the property in the test fixture's `application.properties`, or (b) the Stage1 path short-circuits before invoking Stage2 in those tests' scenarios. Item (a) is the safer path: the test-fixture `application.properties` at `infochat-collector/src/test/resources/application.properties` declares Ollama defaults for the new `infochat.llm.security.*` keys. **Escalation: refine** if this requires modifying the test-fixture properties file — which IS in the `files_scope` indirectly through the main `application.properties` modification. Reviewer interpretation may vary.
- **`@TestProfile` mechanism for `LocalOnlyConflictStartupIT`**: Quarkus `@TestProfile` triggers per-test JVM context; if the guard throws from `@PostConstruct`, the entire test class fails to instantiate — there's no clean way to assert "boot refused" from within a `@Test` method in the same class. Options: (a) split the conflict-config test into a `QuarkusTestProfile` that runs in its own JVM via Maven Failsafe configuration, (b) expose a package-private `validateLocalOnlyConfiguration(Map<String, String> configValues)` method on the guard and call it directly. The Implementation notes acknowledge "the precise mechanism depends on how the guard signals failure ... document the mechanism in the file's class JDoc" — so this is a documented design point, not an escalation. Recommend option (b) for simplicity; the `@QuarkusTest` annotation still exercises the CDI wiring.
- **`audit_log.target_kind` closed CHECK**: V5 enumerates `('user','group','source','post','invite','quarantine','asset','memory','system')`. The startup audit row uses `target_kind='system'` — this is allowed. Confirmed against the schema; no escalation needed.
- **Quarkus `@Startup` ordering at priority 150**: this slot is currently unused (50, 100, 200, 300, 400, 450 are taken on the Collector). Confirm no migration ticket has reserved 150. Reading the architecture table at design 01 §1.4.3 — slot 150 is free. No risk.
- **The `infochat.llm.security.timeout-ms` property** is mentioned in Implementation notes but is NOT in the acceptance grep checks. Add it to `application.properties` for completeness, but the reviewer may flag it as unverified surface. Recommend documenting in the commit message under `Property surface added:` trailer.

### Out-of-scope (echoed from ticket)
- Any Stage 1 worker, HTML sanitizer integration, regex set, watchdog, placeholder ids, quarantine-row insertion (M1-032 territory; consumed unchanged).
- Any Tagger LLM call, controlled-vocabulary validation, partial-valid handling, bootstrap-tags fallback, `tagger_done` advance, `tagger_fallback` flag (M1-034).
- Any `EmbeddingProvider` impl, embedding worker, `post_embedding` table, dimensionality guard, model-identity guard (M1-034).
- Any `post.status → READY` UPDATE, `post.ready_at` set, `pg_notify('new_post', …)` emit (M1-034 Stage 5).
- Any `EntityExtractor`, `post_entity` table, `post_reference` table, `LinkingJob` (T2).
- Any re-evaluation job, per-post attempt counter, `QUARANTINED → NEEDS_REVIEW` transition, per-source UNKNOWN auto-disable, `RE_EVAL_RELEASED` audit row, `source.status='failed'` mutation (T2-G).
- Any throttled admin notifier wiring (T2-G); only emit canonical error_class log lines.
- Any LLM output sanitizer (T1-F).
- Any `/quarantine list/approve/reject` admin command or `approve_quarantine` / `reject_quarantine` stored procedures (T2-G).
- Any Provider-side `quarantine_review` LISTEN listener (M2).
- Any new Flyway migration (`migration_touch: false`; V10 covers Stage 2 needs; V11 is M1-034).
- Any `AnthropicProvider` native messages API impl (T3-D).
- Any `TranslationProvider` concrete impl (T1-F).
- Any chat-agent recall tool, five-tool allowlist (T2-D).
- Any embedding-provider router (M1-034 separate resolution path).
- Any per-task fallback chain (v2 candidate).
- Any Prometheus / Micrometer metric emit (observability ticket later).
- Any V1..V10 migration edits (frozen).
- Any M1-007b SPI surface change (`LlmProvider`, `EmbeddingProvider`, `ModelTask` frozen — this ticket WIRES INTO them, doesn't widen).
- Any `infochat-provider` module change (Stage 2 lives in Collector + llm-adapter only).
- Any user-visible rendering of Stage 2 verdicts (internal-only signal).

### Post-refine status (2026-05-16, after `/m1-tick escalate M1-033 refine`)

The outline above was generated by the Plan subagent BEFORE the refine and is preserved verbatim as the at-start snapshot. After the refine, the following items in the outline are resolved or superseded:

- **"Files to touch (12 of 12)"** is now **13 of 13** — `Stage1Worker.java` was added to `files_scope` and `files_budget` was bumped 12 → 13. The implementation order step 9 ("Wire `Stage1Worker → Stage2Worker` — modify is **not** authorized") is now **authorized** — proceed with the in-process hand-off per the outline's recommended path.
- **Risk #1 (Stage1Worker.java wiring not in files_scope)** is **resolved** — refine added the file to scope; the bounded edit is documented in the refined `out_of_scope` entry 1 (constructor injection of `Stage2Worker` + conditional dispatch on `result.flagged() && !result.quarantinedByWatchdog()` only).
- **Risk #2 (Stage1WatchdogIT / Stage1PipelineIT might break)** is **still open** — refine did not address it because the test-fixture `application.properties` is the same `infochat-collector/src/main/resources/application.properties` already in `files_scope` (Quarkus reads it for both main and test profiles unless a separate test fixture overrides). Add `%test.infochat.llm.security.base-url=http://stub` (or equivalent) to the properties file during implementation if the pre-existing Stage 1 ITs fail to boot.
- **Risk #3 (`@TestProfile` mechanism for `LocalOnlyConflictStartupIT`)** is **still open** — refine did not narrow this design choice (the Implementation notes already document both options). Pick the package-private validator method (option b) per the outline's recommendation when implementing.
- **Risk #6 (timeout-ms property not in acceptance grep)** is **still open** — declare the key in `application.properties` for completeness; document under `Property surface added:` trailer in the commit message.
- **Tests section** — acceptance Item 26 (now 26a-26d) and Item 28 (now 28a-28i) pin the test method names + per-behavior/per-scenario greps. The outline's test method names match the refined acceptance items' substring greps; the developer follows the outline's naming verbatim.
- **Cross-cutting concerns** — unchanged; all invariants remain load-bearing.

The clarity warnings the refine addresses are recorded in `clarity_check:` (preserved as historical record) and in the most recent `revisions:` entry. The refined acceptance set (43 items including 26a-26d + 28a-28i) is the implementation contract; the original aggregate Items 26 and 28 were replaced.

## Round 1 rework

Round 1 verdict: REWORK (1 item). All five frontmatter checks PASS; SPEC-CONFORMANCE PASS. The single rework item is a §7 violation ("No defensive code for impossible scenarios") in `Stage2Worker.judge(...)`.

1. **Remove the defensive precondition checks at the top of `Stage2Worker.judge(...)`** in `infochat-collector/src/main/java/io/infochat/collector/eval/stage2/Stage2Worker.java` (the `if (stage1Result == null) throw ...` block AND the `if (!stage1Result.flagged() || stage1Result.quarantinedByWatchdog()) throw ...` block at the top of the method body, ~lines 129-144). These violate `engineering-rules-verbatim.md` §7 "No defensive code for impossible scenarios" and contradict the ticket's own Cross-cutting concerns bullet: "no null-checks on Stage1Result fields when the caller is Stage1Worker (both internal)." `Stage1Worker` is the sole caller and already gates the call on `result.flagged() && !result.quarantinedByWatchdog()` before invoking `judge(...)`. The internal-to-internal defensive throws are exactly the pattern §7 forbids; delete the entire precondition block (and the `IllegalArgumentException` import if it becomes unused after the deletion). A single short comment above the deletion site noting "precondition enforced by the sole caller Stage1Worker" is acceptable per the project's "comment hidden invariants" rule, but not required.

Address only the named item; re-run `mvn -B verify`; then `/m1-tick review M1-033`.
