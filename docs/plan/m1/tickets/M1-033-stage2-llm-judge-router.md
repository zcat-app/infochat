---
id: M1-033
title: Stage 2 LLM judge + first OpenAI-compatible LlmProvider + (ModelTask, scope_language) router
status: escalated
created: 2026-05-16
last_updated: 2026-05-16
blocked_by:
  - M1-007b
  - M1-032
files_budget: 12
files_scope:
  - infochat-llm-adapter/pom.xml
  - infochat-llm-adapter/src/main/java/io/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/io/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/main/java/io/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/main/resources/prompts/security-judge.md
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
  - any Stage 1 deterministic security worker, HTML sanitizer integration, prompt-injection regex set, watchdog implementation, placeholder-id generation, or quarantine-row insertion code (M1-032 territory — Stage 2 fires ONLY on Stage 1 hits per docs/spec/security.md §Ingest pipeline; the Stage1Pipeline + Stage1RegexSet + PlaceholderIds + QuarantineDao classes are consumed unchanged)
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
  - "LlmRouterTest.java is a unit test (NOT a @QuarkusTest — router resolution is in-process Java) that asserts: (a) the router returns a provider for ModelTask.SECURITY_JUDGE when the profile default is configured; (b) the per-task override property (infochat.llm.security.provider) takes priority over the profile default — verify by setting the override to a different provider key and asserting the resolved provider matches the override, not the default; (c) the router returns exactly one LlmProvider per call (signature: LlmProvider, not List<LlmProvider> — assert at compile time + at runtime); (d) the language-aware capability check is exercised for SUMMARIZER (even though Stage 2 doesn't need it, the router code path must exist) — a SUMMARIZER call with scope_language='cs' AND a provider declaring SUPPORTS_LANGUAGE_CS capability resolves to that provider; a SUMMARIZER call with scope_language='cs' AND only providers WITHOUT SUPPORTS_LANGUAGE_CS forces the caller to use TranslationProvider downstream (the router still returns a provider but the caller observes the missing capability and dispatches the two-call shape — this is documented behavior, not an error) — grep -E '@Test' LlmRouterTest.java returns at least four matches"
  - "Stage2WorkerIT.java is a @QuarkusTest IT against a real Postgres + a STUB LlmProvider (Quarkus DevServices acceptable per M1-027/M1-028 pattern; the stub is a Quarkus @Alternative or @Mock-annotated bean that replaces OpenAiCompatibleProvider for the test). Scenarios end-to-end: (1) BENIGN verdict: stub returns 'BENIGN'; one Stage-1-flagged post with stage1_flagged=true, status='RAW', and one PENDING quarantine row is processed → post.stage2_done=true, post.status stays 'RAW' (Tagger/Embedding still need to run; status='READY' transition happens in M1-034 Stage 5), quarantine row PENDING → BENIGN_CLOSED, [REDACTED:<id>] placeholders RETAINED in post.body; (2) INJECTION: stub returns 'INJECTION' → post.status='QUARANTINED', post.stage2_done=true, quarantine row stays PENDING; (3) MALWARE: same shape as INJECTION; (4) UNKNOWN: same shape as INJECTION; (5) schema-violating reply: stub returns 'BENIGN_PLEASE' on first call AND 'BENIGN_PLEASE' again on retry — the post follows the infra-failure path under the active profile's release-on-stage2-failure setting; (6) empty reply: stub returns '' on both calls → same as (5); (7) unreachable LLM (stub throws on every call) → retry-once-then-fallback to infra path; (8) release-on-stage2-failure=true profile: infra failure path → post.stage2_done=true, post.stage2_failed=true, post.status STAYS 'RAW' (Tagger/Embedding still need to run; the release-to-READY transition happens in Stage 5), redactions retained, log at WARN with error_class='stage2.infra_failure'; (9) release-on-stage2-failure=false profile: infra failure path → post.status='QUARANTINED', post.stage2_done=true, post.stage2_failed=true. The test exercises both profile-flag values via @ConfigProperty override per scenario (or via @TestProfile splitting if simpler) — grep -E '@Test' Stage2WorkerIT.java returns at least nine matches AND grep -E 'BENIGN|INJECTION|MALWARE|UNKNOWN' Stage2WorkerIT.java returns at least four matches"
  - "Stage2WorkerIT.java's stub LlmProvider is REAL Java (not a Mockito mock at the SPI level — Mockito is fine for verification but the @Alternative bean is a hand-written class that implements LlmProvider directly so the SPI contract is exercised end-to-end). The Stage 2 call site invokes LlmRouter.forTask(SECURITY_JUDGE, …), receives the stub, invokes stub.generate(...) with the assembled prompt, and the stub returns the canned response. Verify by reading: the stub class is in the test source root (NOT the main source root); the stub is selected by Quarkus's @Alternative + @Priority(Integer.MAX_VALUE) or equivalent mechanism for the test profile"
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
clarity_check:
  date: 2026-05-16
  verdict: FAIL
  warnings: []
  blockers:
    - |
      SPEC-REFS-VALID FAIL: Four spec_refs in
      docs/design/05-llm-and-embeddings.md point to plain-text section
      titles that have no markdown `#` heading markers — they cannot be
      resolved by the anchor resolution algorithm. The sections exist
      in the file but as plain prose lines (line 88: "  5.3 Provider
      implementations", line 147: "  5.4.1 Security Stage 2 judge",
      line 434: "  5.7 Profile defaults table (canonical)", line 489:
      "  5.8 Failure handling per task"). Fix: either (a) add `##`
      heading markers to these sections in
      docs/design/05-llm-and-embeddings.md (a `spec:` commit changing
      only the design file), or (b) remove the four unresolvable
      spec_refs from M1-033's frontmatter and replace them with
      references to sections that do have `##` markers (e.g., §5.1 SPI
      overview). The four affected spec_ref entries are:
      docs/design/05-llm-and-embeddings.md §5.3 Provider
      implementations; §5.4.1 Security Stage 2 judge; §5.7 Profile
      defaults table; §5.8 Failure handling per task.
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
  `UNKNOWN`; demands a single-token reply.
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
  `TestStubLlmProvider` in the test source root implementing
  `LlmProvider` directly. The stub takes a canned response
  (settable via a `setNextResponse(...)` method) and an
  optional `failNextCall` boolean. The stub is selected via
  Quarkus's `@Alternative @Priority(Integer.MAX_VALUE)` for
  the test profile. Mockito at the SPI level is acceptable
  but a hand-written stub is simpler for the four scenarios
  in `Stage2WorkerIT` and matches the M1-027/M1-028 style.
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
