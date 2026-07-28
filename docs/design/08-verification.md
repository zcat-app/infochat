> **Status: design notes, not spec.**
> Implementation details below (DDL, class names, package layout, property keys,
> retry counts, regex strings, etc.) are working notes that may change without a
> spec amendment. The authoritative *what & why* lives in `docs/spec/`.

  ---
                                                                                                                                                                                                                                                        
  # 08 — Verification and testing
                                                                                                                                                                                                                                                        
  > **Status: original test plan, superseded by the shipped suite.**
  > This file is the plan that was written before the suite existed and it
  > was never reconciled with what got built (audit 2026-07-27). Roughly 30
  > class names below — `AdapterContractTest`, `LlmProviderContractTest`,
  > `EmbeddingProviderContractTest`, `TranslationProviderContractTest`,
  > `FakeLlmProvider`, `FakeEmbeddingProvider`, `MigrationsIT`,
  > `IsolationIT`, `QuarantineIT`, `EvalPipelineIT`, `GroupDigestIT`,
  > `ChatMemoryIT`, `ChatConcurrencyIT`, `ConnectionPoolIT`, `SavedPostIT`,
  > `SourceLifecycleIT`, `TranslationIT`, `AdminLifecycleIT`,
  > `LinkingJobUnitTest`, `PermissionEvaluatorTest`, `PromptBuilderTest`,
  > `CommandParserTest`, `Stage1SanitizerTest`, and the production types
  > `ConfirmationStore`, `SimplexAdapter`, `TimeWindowParser`,
  > `SourceJsonValidator`, `RedactingLogger`, `ChunkingTextSplitter` —
  > **do not exist under those names**, and §8.1's layer counts and CI
  > wall-clock targets do not match the built suite (659 test source files
  > under `infochat-*/src/test`, distributed provider 339 / collector 150 /
  > messaging-adapter 77 / core 52 / llm-adapter 30 / ssrf 11).
  >
  > **Read it as intent, never as an index of the suite.** The suite itself
  > is the ground truth: `find infochat-*/src/test -name '*.java'`. The
  > spec-level obligations this plan exists to serve are in
  > `docs/spec/verification.md`, which is current. Renaming the entries to
  > shipped classes would be guesswork about which planned suite maps to
  > which built one, so the names are left standing as history rather than
  > silently rewritten.

  This file specifies the test strategy: what we test, at which level, with what fixtures, and what signals we look at to declare a release ready. The goal is reproducible verification — running the suite against a fresh checkout produces the same 
  verdict every time.
                                                                                                                                                                                                                                                        
  We split tests by where they sit in the system: pure unit tests, integration tests against real Postgres + fake LLM, contract tests for SPIs, and a small end-to-end smoke that exercises the full message-in / message-out path with                 
  `InMemoryAdapter`.
                                                                                                                                                                                                                                                        
  ---                                                                              

  ## 8.1 Test layers

  | Layer | Scope | Tooling | Runs in CI? | Approx. count |                                                                                                                                                                                             
  |---|---|---|---|---|
  | Unit | Pure logic, no I/O | JUnit 5, AssertJ | yes (every PR) | ~200 |                                                                                                                                                                              
  | Integration | Postgres + Flyway, fake LLM, in-memory adapter | JUnit 5 + Quarkus DevServices (Testcontainers Postgres + pgvector) | yes | ~80 |                                                                                                     
  | SPI contract | Adapter / LlmProvider / EmbeddingProvider implementations | parameterized JUnit 5 | yes | ~30 (× number of impls) |                                                                                                                  
  | End-to-end smoke | Message → response across the whole stack | Quarkus integration test, InMemoryAdapter | yes | ~10 |                                                                                                                              
  | Manual regression | Real SimpleX, real Ollama | docs/runbook | release-time only | n/a |                                                                                                                                                            
                                                                                                                                                                                                                                                        
  Targets:                                                                                                                                                                                                                                              
  - Unit: < 30 s wall clock total.                                                                                                                                                                                                                      
  - Integration: < 3 min total (parallelizable per Testcontainer pool).                                                                                                                                                                                 
  - E2E smoke: < 1 min.                                                                                                                                                                                                                                 
  - Full CI: < 5 min wall clock for a green PR.                                                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  ---                                                                              
                                                                                                                                                                                                                                                        
  ## 8.2 Conventions                                                                                                                                                                                                                                    
   
  - Test classes live next to production classes in `src/test/java/...` mirroring package layout.                                                                                                                                                       
  - Names: `*Test` for unit, `*IT` for integration, `*ContractTest` for SPI suites, `*E2E` for end-to-end.
  - Fixtures: under `src/test/resources/fixtures/`. JSON files for RSS samples, prompt-injection corpora, embedding pre-computed vectors.                                                                                                               
  - Test logs: ERROR-only by default; per-class override with `@TestProperty`.                                                                                                                                                                          
  - Determinism: zero `Thread.sleep` in tests. Use `Awaitility` with explicit conditions (`until(() -> ...)`) where async is unavoidable.                                                                                                               
  - DB cleanup: each `*IT` class gets a fresh schema via Flyway `clean+migrate`; per-test cleanup uses `TRUNCATE ... RESTART IDENTITY CASCADE`.                                                                                                         
  - LLM in tests: use `FakeLlmProvider` (returns canned outputs by prompt-hash). Real Ollama is **never** called in CI.                                                                                                                                 
  - Test data: tagged with `@Tag("slow")` for anything > 5 s; `@Tag("manual")` for SimpleX-touching tests skipped by default.                                                                                                                           
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  ## 8.3 Unit tests                                                                

  ### 8.3.1 Stage 1 sanitizer (security)                                                                                                                                                                                                                
   
  `Stage1SanitizerTest`:                                                                                                                                                                                                                                
                                                                                   
  - Positive: each prompt-injection regex flags its target span. Fixture corpus at `fixtures/security/prompt-injection-positive.txt`, one entry per line.                                                                                               
  - Negative: benign content with edge phrases ("In a previous email I asked...", "ignore the noise", code samples) does NOT flag. Corpus at `fixtures/security/prompt-injection-negative.txt`.
  - HTML sanitizer: malicious HTML becomes safe text; allowlist preserved (links, code, basic structure). Cases include `<script>`, `<iframe>`, `javascript:`, `data:`, `<a onclick>`, malformed HTML.                                                  
  - Unicode: NFKC normalization changes detection where expected; bidi controls stripped; zero-widths inside fenced code preserved.                                                                                                                     
  - Span offsets: replaced placeholders are byte-accurate. Positive cases verify `(span_start, span_end)` round-trip.
  - **ReDoS resilience (F27):** an adversarial input crafted to trigger catastrophic backtracking (e.g., a long alternation tail like `"a" * 5000 + "!"` against a naive `(a|aa)+!` style pattern, plus the curated cases in `fixtures/security/redos-attacks.txt`) MUST either complete within 100 ms or fail fast via the configured RE2/J / `Pattern` timeout. The assertion is wall-clock-bounded with a generous margin (test fails if any single regex pass exceeds 250 ms, well below the 100 ms target plus CI jitter). This validates the protection required by [04-security.md §4.2](04-security.md).                                                                                                                                   
                                                                                                                                                                                                                                                        
  ### 8.3.2 Command parser                                                                                                                                                                                                                              
                                                                                                                                                                                                                                                        
  `CommandParserTest`:                                                                                                                                                                                                                                  
   
  - Slash detection.                                                                                                                                                                                                                                    
  - `@mention` strip in group inputs.                                              
  - Time-window parser: every form (`1h`, `12h`, `1d`, `7d`, `1w`, `4w`, `1m`) maps to expected Duration; out-of-range fails.                                                                                                                           
  - Unknown command suggestion: Levenshtein-2 returns expected three.                                                                                                                                                                                   
  - Unknown tag suggestion: same.                                                                                                                                                                                                                       
  - Confirmation token: `clear confirm` accepts; `Clear confirm` accepts (case-insensitive); `clear  confirm` (double-space) rejects.                                                                                                                   
  - Confirmation expiry: 31s after issue rejects, 29s accepts (uses `Clock` injection, not real time).                                                                                                                                                  
  - Argument validation: `/add-source` without `--tags` rejects with the canonical error.                                                                                                                                                               
                                                                                                                                                                                                                                                        
  ### 8.3.3 Permission matrix                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  `PermissionEvaluatorTest`:                                                                                                                                                                                                                            
   
  - Table-driven: every command × every actor type (DM banned/normal/admin, Group member/group-admin/banned/bot-admin) with expected allow/deny.                                                                                                        
  - Asserts source of truth match between this test and §3.2 of `03-commands.md` (table is loaded from a markdown extract or duplicated as a Java enum; CI fails if drift).
                                                                                                                                                                                                                                                        
  ### 8.3.4 LLM call shaping                                                                                                                                                                                                                            
                                                                                                                                                                                                                                                        
  `PromptBuilderTest`:                                                                                                                                                                                                                                  
                                                                                   
  - Each prompt template renders deterministically given a fixture input.                                                                                                                                                                               
  - The same input twice produces byte-identical output (templating purity).
  - Untrusted-content blocks have UUID-randomized id; assertion checks the id is unique per call but format is exact.                                                                                                                                   
  - `target_language=cs` switches the language directive; `en` doesn't.                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  ### 8.3.5 Tier-2 linking math                                                                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  `LinkingJobUnitTest`:                                                                                                                                                                                                                                 
                                         
  - Entity-match scoring: shared count → score.                                                                                                                                                                                                         
  - Cosine threshold: < 0.18 distance produces semantic link.
  - Cap N=10: only top-N retained, ordered by score desc.                                                                                                                                                                                               
  - Bidirectional row creation: both `(A→B)` and `(B→A)` written.                                                                                                                                                                                       
                                                                                                                                                                                                                                                        
  ### 8.3.6 Other small targets                                                                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  - `TimeWindowParser`, `TagFuzzyMatcher`, `ConfirmationStore`, `PerUserRateLimiter`, `RedactingLogger`, `SourceJsonValidator`, `ScopeRefMapper`, `ChunkingTextSplitter`, `Stage1SpanReplacer`.                                                         
   
  ---                                                                                                                                                                                                                                                   
                                                                                   
  ## 8.4 Integration tests

  Run with Quarkus DevServices: Testcontainers Postgres + pgvector image. Flyway migrates on bring-up. `FakeLlmProvider` and `InMemoryAdapter` are CDI-active in test profile.                                                                          
   
  ### 8.4.1 Schema and migrations                                                                                                                                                                                                                       
                                                                                   
  `MigrationsIT`:                                                                                                                                                                                                                                       
                                                                                   
  - Fresh DB → all migrations apply, schema matches the spec (indices, partitions, triggers).                                                                                                                                                           
  - Repeat migrate is a no-op.
  - `pgvector` extension present; column dimensions match the active profile (test runs each profile in its own class to verify dimension switch).                                                                                                      
  - Last-admin-protection trigger blocks revoking the only admin.                                                                                                                                                                                       
  - `saved_post.post_id ON DELETE RESTRICT` prevents pruning while saved.                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  ### 8.4.2 Bootstrap loader                                                                                                                                                                                                                            
                                                                                                                                                                                                                                                        
  `BootstrapLoaderIT`:                                                                                                                                                                                                                                  
  
  - Valid JSON loads; row counts match.                                                                                                                                                                                                                 
  - Malformed JSON aborts startup with a clear message (parsed by test).           
  - Missing required field aborts.                                                                                                                                                                                                                      
  - Duplicate `(fetcher,url)` becomes one row + one subscription per scope.                                                                                                                                                                             
  - Re-running the loader is idempotent; updates only mutable fields.                                                                                                                                                                                   
  - Tags union seeds `tag` table; new tag gets `source_origin='bootstrap'`.                                                                                                                                                                             
                                                                                                                                                                                                                                                        
  ### 8.4.3 Eval pipeline (per-stage failure)                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  `EvalPipelineIT` (parameterized over each failure mode):                                                                                                                                                                                              
  
  - Happy path: post inserted RAW → all stages pass → READY → `new_post` notify fires.                                                                                                                                                                  
  - Stage 2 fails twice: post stays redacted, `READY`, no original restoration. Quarantine row remains PENDING. Admin-notify channel emits one event.
  - Tagger fails: `tagger_fallback=true`; tags equal `source.bootstrap_tags`. Admin notified.                                                                                                                                                           
  - Entity extractor fails: post is READY, no entity rows. Admin notified.                                                                                                                                                                              
  - Embedder fails: post is READY, no embedding row. Linking job skips it for semantic links.                                                                                                                                                           
  - Throttling: 47 tagger failures within 15 min → exactly one `eval_failure` admin notification, payload contains the count.                                                                                                                           
                                                                                                                                                                                                                                                        
  ### 8.4.4 Quarantine                                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  `QuarantineIT`:                                                                  

  - Stage 1 hit on injected RSS sample creates a quarantine row PENDING.                                                                                                                                                                                
  - `/quarantine list` returns it via `quarantine_review` view (no `original_html`).
  - `/quarantine approve <id>` restores the original span in `post.body`; status APPROVED; if post was QUARANTINED, becomes READY and fires `new_post`.                                                                                                 
  - `/quarantine reject <id>` keeps placeholder; status REJECTED.                                                                                                                                                                                       
  - `infochat_provider` role cannot SELECT `original_html` directly (raw table) — explicitly tested.                                                                                                                                                    
                                                                                                                                                                                                                                                        
  ### 8.4.5 Source model                                                                                                                                                                                                                                
                                                                                                                                                                                                                                                        
  `SourceLifecycleIT`:                                                                                                                                                                                                                                  
                                                                                   
  - DM `/add-source` creates source + subscription scoped to the user.                                                                                                                                                                                  
  - Group `/add-source` (by group admin) creates source + subscription scoped to the group.
  - Group `/add-source` by non-admin → permission denied.                                                                                                                                                                                               
  - Same URL added by user A and user B → one `source` row, two subscriptions.                                                                                                                                                                          
  - `/unfollow-source` removes only the calling scope's subscription; source remains.                                                                                                                                                                   
  - `/remove-source` (bot admin) cascades subscriptions; posts retained per FK behavior; audit row written.                                                                                                                                             
                                                                                                                                                                                                                                                        
  ### 8.4.6 Save / saved / unsave                                                                                                                                                                                                                       
                                                                                                                                                                                                                                                        
  `SavedPostIT`:                                                                                                                                                                                                                                        
                                                                                   
  - `/save <uid>` creates a row; subsequent prune attempt of the post is REJECTED by FK.                                                                                                                                                                
  - 1000 saves → 1001st rejected by trigger, friendly error.
  - `/saved` returns only calling user's rows even from a group.                                                                                                                                                                                        
  - `/saved tag` filters by personal tag.                                                                                                                                                                                                               
  - `/unsave` removes; pruner can then delete the post.                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  ### 8.4.7 Memory and compress                                                                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  `ChatMemoryIT`:                                                                                                                                                                                                                                       
                                                                                   
  - `/compress` writes a `chat_memory` row scoped to (user, scope).                                                                                                                                                                                     
  - Memory pre-fetch retrieves by GIN keyword match before the chat agent prompt is built.
  - Group memory: user A's memory not visible to user B in the same group.                                                                                                                                                                              
  - `/clear` truncates `chat_session.messages` but preserves `chat_memory`.                                                                                                                                                                             
                                                                                                                                                                                                                                                        
  ### 8.4.8 Periodic group digest                                                                                                                                                                                                                       
                                                                                                                                                                                                                                                        
  `GroupDigestIT` (uses fake clock):                                                                                                                                                                                                                    
  
  - A group with `digest_enabled=true` and tz=UTC at 08:00 UTC: digest enqueued.                                                                                                                                                                        
  - Stagger offset: 3 groups → fires at +0s, +30s, +60s relative to slot start.    
  - Cache: an immediate `/summary` after digest returns the cached value.                                                                                                                                                                               
  - `pi` profile + busy worker: defer to next slot, then degraded fallback (headlines only); test asserts both paths.                                                                                                                                   
                                                                                                                                                                                                                                                        
  ### 8.4.9 Translation                                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  `TranslationIT`:                                                                 

  - Default `en` scope: no translation called; FakeTranslator records zero calls.                                                                                                                                                                       
  - `/lang cs`: outbound text passes through `LlmTranslationProvider`; cache hits on repeat input.
  - Direct-generation provider with `SUPPORTS_LANGUAGE_CS`: summarizer called once with `target_language=cs`; FakeTranslator records zero calls.                                                                                                        
  - Translator failure: original English emitted with the `(translation unavailable)` suffix; admin notified once per 15 min.                                                                                                                           
                                                                                                                                                                                                                                                        
  ### 8.4.10 Cross-scope isolation (privacy)                                                                                                                                                                                                            
                                                                                                                                                                                                                                                        
  `IsolationIT`:                                                                                                                                                                                                                                        
  
  - Fuzz: 50 users × 5 groups × random commands → assert that all queries return only rows where `(scope_kind, scope_id)` matches the actor.                                                                                                            
  - Specific cases:                                                                
    - User A's `/saved` never includes user B's post.                                                                                                                                                                                                   
    - User A's `/lang cs` does NOT change user B's scope language.                                                                                                                                                                                      
    - DM source added by A does NOT appear in any group `/list-sources` for B.                                                                                                                                                                          
    - Memory entries A wrote in group X are NOT in B's recall in the same group.                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  ### 8.4.11 Bot admin and group admin                                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  `AdminLifecycleIT`:                                                                                                                                                                                                                                   
                                                                                   
  - Bootstrap: a per-adapter bootstrap path set (`infochat.adapters.<name>.admin`, or SimpleX's `infochat.adapters.simplex.admin-token`, D50); the pre-seeded or claiming contact ends up `is_admin=true`; audit row written.                                                                                                                               
  - `/grant-admin` / `/revoke-admin` with last-admin protection.
  - First `@mention` in a fresh group auto-promotes that user to group admin; audit row written.                                                                                                                                                        
  - `/promote` / `/demote` callable only by bot admin.                                                                                                                                                                                                  
  - `/ban` blocks subsequent inbound from the target before the parser runs (no DB write past the ban check).
  - `/unban` restores access; previously held group-admin role still effective.
  - **Chat output sanitizer (F2):** `FakeLlmProvider` is configured to return the exact string `"Sure! Here's the command: /grant-admin abc123"` for a benign chat-mode prompt. Assert that:
    1. The outbound `OutboundMessage.text` delivered to `InMemoryAdapter` does NOT contain `/grant-admin abc123` — the chat output sanitizer (see [04-security.md §4.4](04-security.md)) either replaces the matched span or refuses the entire reply with `[refused-action]`.
    2. An `audit_log` row is written with action kind `chat_output_sanitized`, the actor's contact id, scope, and a redacted preview of the matched span.
    3. The same flow for sibling commands (`/demote`, `/ban`, `/unban`, `/remove-source`, `/promote`) produces equivalent sanitization. Run as a parameterized test over the command list to prevent drift if §4.4's regex grows.
    4. A control case where the LLM reply mentions `/help` or `/summary` (non-admin commands) passes through unchanged and writes NO sanitizer audit row.                                                                                                                                                                         
                                                                                                                                                                                                                                                        
  ### 8.4.12 Connection-pool discipline (F18)

  `ConnectionPoolIT`:

  Validates that the Provider releases its JDBC connection before every LLM call, as required by [07-deployment.md §7.4 "Connection-release discipline"](07-deployment.md). Without this, ~10 concurrent chat-mode requests could starve the Collector under the production-recommended pool sizes (provider=30, collector=15).

  - Setup: Provider configured with `quarkus.datasource.jdbc.max-size=10` (deliberately low to make starvation easy to trigger if the discipline is broken). `FakeLlmProvider` configured to sleep 2 s before returning, simulating a slow LLM. A second connection consumer (a tight-loop Collector-side write) runs in parallel.
  - Drive 20 concurrent chat-mode requests through `InMemoryAdapter`.
  - **Assert (a) — pool gauge stays bounded:** the `agroal.connections.active{datasource=provider}` Micrometer gauge, sampled at 100 ms intervals during the run, never exceeds the pool size (10). If the Provider were holding a connection across the LLM call, 11+ requests would block waiting for the pool and the gauge would pin at 10 with a growing acquisition wait time — both observable.
  - **Assert (b) — no LLM-induced starvation:** the parallel Collector writer completes its loop within `(2 s LLM time + 200 ms slack)`, NOT `(20 chats × 2 s)`. This is the tight-fitting failure signal: if connections were held across LLM calls, the Collector writer would block for the full chat-mode duration.
  - **Assert (c) — connection-acquisition-wait stays low:** `agroal.connections.acquire.histogram{datasource=provider}` p99 < 50 ms across the run.
  - **Assert (d) — no held-across-LLM connections:** an instrumentation aspect on `LlmProvider.respond()` / `LlmProvider.classify()` records the calling thread's `Connection`-handle count via a test-only `ThreadLocal<ConnectionTracker>`; assertion is that the count is 0 at every LLM entry. This catches the regression at the source rather than only its symptom.

  ### 8.4.13 Same-(user, scope) chat concurrency (F26)

  `ChatConcurrencyIT`:

  Now that chat history lives in the `chat_message` child table (per [02-schema.md §2.6](02-schema.md), F3) keyed by `(session_id, seq)` — not as a JSONB array on `chat_session` — concurrent appends from the same `(user, scope)` are no longer at risk of lost-update via blob rewrite. This test enforces that property.

  - Setup: one user, one DM scope, one `chat_session` row.
  - Drive **two simultaneous** chat-mode messages from the same `(user, scope)` through `InMemoryAdapter`, started within 5 ms of each other (use a `CountDownLatch` to release both threads at once).
  - **Assert (a):** exactly two `chat_message` rows exist for the session, with distinct `seq` values (no collision, no lost write).
  - **Assert (b):** both rows reference the same `session_id`; no second `chat_session` row was created.
  - **Assert (c):** `chat_session.token_count` (denormalized counter, maintained by trigger or app code per F3) equals the SUM of `chat_message.tokens` for the two rows. This validates that the counter update path is also concurrency-safe.
  - **Assert (d):** the assistant replies sent to the adapter both reference the correct session — no cross-talk between the two chat turns. Each reply's `correlationId` matches its inbound message id.
  - **Assert (e) — fuzz variant:** repeat with 10 simultaneous messages. All 10 `chat_message` rows land with strictly increasing `seq`; no duplicates, no gaps that would indicate a rolled-back insert.

  ---

  ## 8.5 SPI contract suites                                                       

  Each SPI is verified with a parameterized suite that runs against every implementation registered. Adding a new impl auto-runs the same assertions.                                                                                                   
   
  ### 8.5.1 `AdapterContractTest`                                                                                                                                                                                                                       
                                                                                   
  Parameterized over `InMemoryAdapter` and `SimplexAdapter` (the latter using a recorded WS fixture, no live SimpleX needed for CI). For each adapter:                                                                                                  
   
  - Identity is stable for repeated inbound from the same source.                                                                                                                                                                                       
  - DM scope and Group scope with the same contact id produce different `ScopeRef`.
  - Group message without `@mention` is not delivered.                                                                                                                                                                                                  
  - `@mention` is stripped from `text` before delivery.                                                                                                                                                                                                                                                                                                                                                     
  - Reconnect simulation: forced WS close → adapter reconnects within backoff window; queued outbounds eventually delivered.                                                                                                                            
  - `trustLevel == LOW` + `allow-low-trust=false` → boot fails with a diagnostic message.                                                                                                                                                               
  - Idempotent stop: calling `stop()` twice is safe.                                                                                                                                                                                                    
                                                                                                                                                                                                                                                        
  ### 8.5.2 `LlmProviderContractTest`                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Parameterized over `FakeLlmProvider` and `OpenAiCompatibleProvider` configured to point at a `WireMock` server returning canned responses.                                                                                                            
                                                                                   
  - `chat()` returns a string for a simple input.                                                                                                                                                                                                       
  - `classify()` returns valid JSON matching the requested schema.                 
  - Capabilities are non-null and self-consistent (`SUPPORTS_LANGUAGE_CS` → at least one Czech generation passes a smoke prompt).                                                                                                                       
  - Bounded concurrency: `max-concurrency=2` blocks the third concurrent call until one finishes (verified with `CountDownLatch`).                                                                                                                      
  - Failure path: provider 5xx → exception with `outcome=fail` metric increment.                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  ### 8.5.3 `EmbeddingProviderContractTest`                                                                                                                                                                                                             
                                                                                                                                                                                                                                                        
  - Output dimension matches `dimension()`.                                                                                                                                                                                                             
  - Same input → same vector (deterministic).
  - Batch input returns same number of vectors.                                                                                                                                                                                                         
  - Failure path increments fallback metric.                                                                                                                                                                                                            
                                                                                                                                                                                                                                                        
  ### 8.5.4 `TranslationProviderContractTest`                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  - Round-trip preserves backticks, code fences, URLs, UIDs.                                                                                                                                                                                            
  - Cache hit on identical input.
  - `canTranslate(from,to)` matches actually-supported pairs (test fails if a provider claims `cs` but the smoke fails).                                                                                                                                
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  ## 8.6 End-to-end smoke (`E2E`)                                                  

  One Quarkus integration test per smoke flow, all using `InMemoryAdapter` + `FakeLlmProvider`. These are guard-rails that prove the wiring is intact.                                                                                                  
   
  `SmokeE2E.dmHelp`:                                                                                                                                                                                                                                    
  1. New user sends `/help`.                                                       
  2. Provider auto-registers, replies with help text in DM.                                                                                                                                                                                             
  3. Welcome line appears (first-time only).                                                                                                                                                                                                            
  4. Audit log shows `USER_REGISTERED`.                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  `SmokeE2E.dmAddSourceAndSummarize`:                                                                                                                                                                                                                   
  1. Auto-register.                                                                                                                                                                                                                                     
  2. `/add-source --type rss --url ... --tags ai,research`.                                                                                                                                                                                             
  3. Inject one fake post via Collector test seam (`@Inject TestSeam fixtures`).                                                                                                                                                                        
  4. Wait for `READY`.                                                                                                                                                                                                                                  
  5. `/summary -w 1h` returns a summary mentioning the post UID.                                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  `SmokeE2E.dmSaveAndRetrieve`:                                                    
  1. After `dmAddSourceAndSummarize`, `/save <uid>`.                                                                                                                                                                                                    
  2. `/saved` returns it.                                                                                                                                                                                                                               
  3. `/unsave <uid>`; `/saved` returns empty.
                                                                                                                                                                                                                                                        
  `SmokeE2E.groupBootstrapAndDigest`:                                                                                                                                                                                                                   
  1. New user @mentions bot in a new group → user becomes group admin.                                                                                                                                                                                  
  2. Group admin `/add-source` (with `--tags`).                                                                                                                                                                                                         
  3. Trigger digest scheduler (fake clock at 08:00 group-tz).                                                                                                                                                                                           
  4. Adapter receives a group-scoped outbound message.                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  `SmokeE2E.groupMemberCannotConfigure`:                                                                                                                                                                                                                
  1. Group has admin A.                                                                                                                                                                                                                                 
  2. User B (group member) tries `/add-source` → permission-denied reply.                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  1. Bot admin issues `/ban <contact> --reason "..."` (with confirm).                                                                                                                                                                                   
  2. Banned user sends `/help` → fixed reply, no audit row beyond the ban.                                                                                                                                                                              
  3. `/unban`; user sends `/help` → normal help reply.                                                                                                                                                                                                  
                                                                                                                                                                                                                                                        
  `SmokeE2E.quarantineFlow`:                                                                                                                                                                                                                            
  1. Inject a post containing a known prompt-injection pattern.                                                                                                                                                                                         
  2. Stage 1 flags it; Stage 2 (fake LLM) returns INJECTION; post moves to QUARANTINED.                                                                                                                                                                 
  3. Bot admin `/quarantine list` shows it; `/quarantine approve <id>` restores.                                                                                                                                                                        
                                                                                                                                                                                                                                                        
  `SmokeE2E.langSwitch`:                                                                                                                                                                                                                                
  1. `/lang cs` in DM.                                                                                                                                                                                                                                  
  2. `/summary -w 1h` returns Czech (FakeTranslator records the call).                                                                                                                                                                                  
  3. Subsequent `/saved` is also Czech.                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  `SmokeE2E.compressAndRecall`:                                                                                                                                                                                                                         
  1. Send 80 chat-mode messages over a (fake-clock-driven) session.                                                                                                                                                                                     
  2. Auto-compress fires at 75% threshold; `chat_memory` row exists.                                                                                                                                                                                    
  3. After `/clear`, ask the chat agent about something covered earlier; it pre-fetches memory and answers (via FakeLlmProvider canned response keyed by memory keywords).                                                                              
                                                                                                                                                                                                                                                        
  `SmokeE2E.evalDegraded`:                                                                                                                                                                                                                              
  1. Configure FakeLlmProvider to fail Stage 2 every time.                                                                                                                                                                                              
  2. Inject a post with Stage 1 hits.                                                                                                                                                                                                                   
  3. Post becomes READY with redactions; user `/summary` includes it in degraded form.                                                                                                                                                                  
  4. Admin notification fires once.                                                   
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
                                                                                                                                                                                                                                                        
  ## 8.7 Fixtures                                                                                                                                                                                                                                       
                                                                                   
  ### 8.7.1 RSS / social samples
                                                                                                                                                                                                                                                        
  `src/test/resources/fixtures/feeds/`:
                                                                                                                                                                                                                                                        
  - `rss-clean.xml` — benign news feed, ~10 items.                                 
  - `rss-mixed.xml` — same feed with 2 items containing prompt-injection patterns.                                                                                                                                                                      
  - `rss-malformed.xml` — broken XML, parser failure path.                        
  - `bluesky-actorfeed.json` — sample Bluesky author feed.                                                                                                                                                                                              
  - `youtube-channel.xml` — sample YouTube channel feed.                           
  - `nitter.xml` — sample xcancel feed.                                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Each fixture is small (under 16 KB) and committed; no live network in tests.                                                                                                                                                                          
                                                                                                                                                                                                                                                        
  ### 8.7.2 Prompt-injection corpus                                                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  `fixtures/security/`:                                                            
                       
  - `prompt-injection-positive.txt` — one phrase per line, each must be flagged by Stage 1.
  - `prompt-injection-negative.txt` — phrases that look suspicious but must NOT be flagged.                                                                                                                                                             
  - `unicode-attacks.txt` — bidi-control, zero-width, homoglyph cases.                     
                                                                                                                                                                                                                                                        
  Adding a new attack pattern is a doc + corpus + (optionally) regex change; the test suite enforces no regression.                                                                                                                                     
                                                                                                                                                                                                                                                        
  ### 8.7.3 Pre-computed embeddings                                                                                                                                                                                                                     
                                                                                                                                                                                                                                                        
  For deterministic linking-job tests, fixed `float[]` vectors stored as JSON. `FakeEmbeddingProvider` returns them by input-hash lookup.
                                                                                                                                                                                                                                                        
  `fixtures/embeddings/embeddings.json`:
  ```json                                                                                                                                                                                                                                               
  {                                                                                
    "post-text-1-sha256...": [0.012, -0.045, ...],                                                                                                                                                                                                      
    "post-text-2-sha256...": [0.011, -0.043, ...]                                  
  }                                                                                                                                                                                                                                                     
  ``` 

  ### 8.7.4 SimpleX WS recordings                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  fixtures/simplex/:
                                                                                                                                                                                                                                                        
  - recording-direct-message.json — array of WS frames for a single DM.                                                                                                                                                                                 
  - recording-group-mention.json — group message with @mention.
  - recording-group-no-mention.json — group message that should be filtered out.                                                                                                                                                                        
  - recording-reconnect.json — close + reopen sequence.                                                                                                                                                                                                 
                                                                                                                                                                                                                                                        
  Used by SimplexAdapterIT and AdapterContractTest. Capturing new recordings: there's a documented procedure in docs/test-fixtures.md (out of scope for v1 spec — committed alongside the test code).                                                   
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  ## 8.8 Performance and load                                                         
                          
  Not part of CI; run pre-release on representative hardware.
                                                                                                                                                                                                                                                        
  ### 8.8.1 Fetch-and-eval throughput                                                                                                                                                                                                                       
                                                                                                                                                                                                                                                        
  Synthetic load: 1 source emitting 10 posts/min for 1 hour. Measure:                                                                                                                                                                                   
                                                                                   
  - Eval queue depth (must stay < 50% of infochat.eval.queue-size).                                                                                                                                                                                     
  - Stage 2 LLM call rate (only fires on Stage-1-flagged posts; expect << 10% on benign feeds).
  - DB write throughput.                                                                                                                                                                                                                                
                                                                                                                                                                                                                                                        
  Profile-specific targets:                                                                                                                                                                                                                             
  - laptop: 10 posts/min sustained, < 5 s end-to-end (fetch → READY).                                                                                                                                                                                   
  - vps: 5 posts/min sustained.                                                                                                                                                                                                                         
  - pi: 2 posts/min sustained. 
                                                                                                                                                                                                                                                        
  ### 8.8.2 Summary latency                                                            
                                                                                                                                                                                                                                                        
  /summary -w 24h against ~200 candidate posts:                                                                                                                                                                                                         
  - laptop: < 8 s (cluster + LLM + render).                                                                                                                                                                                                             
  - vps: < 20 s.                                                                                                                                                                                                                                        
  - pi: < 60 s; if longer, scope narrowing or cluster cap reductions per infochat.summary.cluster-cap.
                                                                                                                                                                                                                                                        
  ### 8.8.3 Vector search latency                                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  `SELECT post_id FROM post_embedding ORDER BY embedding <=> $1 LIMIT 10` with a warm HNSW index over 50K vectors:                                                                                                                                        
  - laptop: < 50 ms median.                                                                                                                                                                                                                             
  - vps: < 100 ms.                                                                                                                                                                                                                                      
  - pi: < 150 ms over 10K vectors. (The IVFFlat index this target was
    written against is deferred beyond v1 — v1 measures HNSW on every
    profile; see `01-architecture.md` §1.7.)
                                                                                                                                                                                                                                                        
  ### 8.8.4 Memory ceiling
                                                                                                                                                                                                                                                        
  - Provider JVM RSS: < 1 GB on vps/pi; < 2 GB on laptop.                                                                                                                                                                                               
  - Collector JVM RSS: similar.
  - Ollama model memory dominates; profile-driven model choice keeps it under hardware capacity.                                                                                                                                                        
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  ## 8.9 Continuous integration                                                                                                                                                                                                                            
                                                                                                                                                                                                                                                        
  GitHub Actions or equivalent. Pipeline:
                                                                                                                                                                                                                                                        
  1. mvn -B verify — runs unit + integration + contract + smoke.                                                                                                                                                                                        
  2. Flyway dry-run against a reference schema dump.
  3. Markdown link checker against docs/.                                                                                                                                                                                                               
  4. JSON schema validation for bootstrap-sources.json.                            
  5. Spotless / formatter check.                                                                                                                                                                                                                        
  6. ErrorProne / NullAway static analysis.                                                                                                                                                                                                             
  7. Coverage report (JaCoCo); soft target 75% line, 65% branch on infochat-core. No hard gate in v1.                                                                                                                                                   
                                                                                                                                                                                                                                                        
  Slow tests (@Tag("slow")) run on nightly cron, not per-PR.                                                                                                                                                                                            
                                                                                                                                                                                                                                                        
  Manual @Tag("manual") tests (live SimpleX, live Ollama) excluded from CI; release manager runs them locally per the runbook.                                                                                                                          
                                                                                   
  ---                                                                                                                                                                                                                                                   
  ## 8.10 Release verification (manual checklist)                                     
                                                                                                                                                                                                                                                        
  Before tagging a release, the release manager runs:
                                                                                                                                                                                                                                                        
  - Full CI green.                                                                                                                                                                                                                                      
  - Manual smoke against a live SimpleX bot account on a staging host:
    - /help, /add-source (RSS, Bluesky, YouTube, Nitter), /summary, /save, /unsave, /saved.                                                                                                                                                             
    - Group: invite bot, first @mention auto-promotes; second user is read-only; /add-source from second user denied.                                                                                                                                   
    - /ban and /unban with a throwaway test contact.                                                                                                                                                                                                    
    - /lang cs end-to-end on at least one summary.                                                                                                                                                                                                      
  - Load run on a staging host matching one production profile (typically vps).                                                                                                                                                                         
  - Backup taken pre-release; restore tested into a sandbox DB.                                                                                                                                                                                         
  - Migration dry-run on a copy of prod-shaped data.                                                                                                                                                                                                    
  - Release notes mention any schema changes and migration time estimate.                                                                                                                                                                               
                                                                                                                                                                                                                                                        
  ---                                                                                                                                                                                                                                                   
  ## 8.11 What's intentionally NOT in v1 testing                                                                                                                                                                                                           
                                                                                                                                                                                                                                                        
  - Property-based fuzz testing — JQwik etc. nice-to-have; targeted fuzzing in IsolationIT is enough for v1.
  - Chaos / network-partition tests — out of scope; the system is colocated.                                                                                                                                                                            
  - Live LLM benchmarks — not in CI; ad-hoc only.                                                                                                                                                                                                       
  - UI tests — there's no UI.                                                                                                                                                                                                                           
  - Penetration testing — operator's responsibility before public exposure; this spec lists the threat model and defenses (04-security.md) but doesn't claim security audit.                                                                            
  - Cross-version DB upgrade tests — only forward migrations within one minor version are tested. Major-version upgrades use the documented restore-from-backup path.                                                                                   

  **Mutation testing was on this list and no longer is** — it ships as an opt-in advisory profile (M1-713). A 2026-07-27 spike ran PIT to settle the question with numbers rather than argument, and it surfaced three real defects (M1-710, M1-711, M1-712) that the green suite, code review and `/redteam` had all missed. The reactor `pom.xml` carries a `mutation` profile; the documented sweep is:

  ```
  mvn -Pmutation \
      -pl infochat-core,infochat-ssrf,infochat-llm-adapter,infochat-messaging-adapter \
      -am test-compile org.pitest:pitest-maven:mutationCoverage
  ```

  HTML and XML reports land in each module's `target/pit-reports/`. Three limits are deliberate, and each is enforced by the POM rather than by convention:

  - **Not a gate.** No `mutationThreshold`, no `verify` binding, no `/m1-tick` hook. A score gate rewards assertions written to kill mutants over assertions that state intent, and equivalent mutants make the number noisy by construction. Mutation score belongs in the advisory tier with `/deep-code-review` and `/redteam`: occasional, deliberate, human-read.
  - **Not run over the Quarkus-bootstrapped tier.** All but two of the repo's `@QuarkusTest` classes live in `infochat-collector` and `infochat-provider`, and PIT re-runs the covering tests once per mutant — every mutant there would pay a Quarkus boot. The plugin is declared reactor-wide so those modules *can* be measured deliberately; the documented invocation omits them.
  - **Container-free.** PIT runs every non-excluded target test in its coverage pass, so the exclusion list is what keeps a sweep safe beside a live stack. It must cover three distinct shapes, not one: `*IT` (the failsafe tier); the two genuine `@QuarkusTest` classes (`InstanceLockLivenessTest`, `ThrottledAdminNotifierTest`); and `app.zcat.infochat.core.schema.*` — plain surefire-tier `*Test` classes that inherit `PostgresSchemaTestBase`, whose static initializer starts a raw pgvector container and runs Flyway. That third shape carries no annotation and no `IT` suffix, so an `@QuarkusTest`-only audit misses it; it is the spec's layer-2 persistence tier (`docs/spec/verification.md` §Test layers). Verify any change to the list with `docker events` **during** a sweep — `docker ps` before and after cannot see it, because the base never stops its container and Ryuk reaps it at minion-JVM exit.

  `mvn verify` is unaffected: the profile carries no `<activation>` and the plugin declares no `<executions>`, so the default build cannot reach it. When reading a report, **test strength** is the meaningful figure rather than mutation score — the two differ by mutants with no covering test at all, which the `*IT` exclusion inflates.
                                                                                                                                                                                                                                                        
  --- 
