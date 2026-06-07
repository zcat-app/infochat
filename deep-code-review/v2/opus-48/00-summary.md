# Deep code review — consolidated summary

**Run directory:** /home/ubuntu5/Projects/quarkus-projects/infochat/deep-code-review/v2/opus-48
**Date:** 2026-06-06
**Synthesizer:** review-synthesizer (opus)

## Coverage

- **Reports consumed:** 7
  - architecture: yes
  - module-infochat-core: yes
  - module-infochat-ssrf: yes
  - module-infochat-llm-adapter: yes
  - module-infochat-messaging-adapter: yes
  - module-infochat-collector: yes
  - module-infochat-provider: yes

All seven targets reported successfully; no targets failed or are missing. Prioritization below is complete with respect to the report set.

Finding totals: 36 per-target findings across all reports, consolidated to 35 unique entries (one cross-report duplicate merged — see Synthesizer notes). The `high` band is dense: nine findings landed `high` or above, so several legitimate `high` findings sit in the category tables below rather than in the top-5.

## Top priority

1. [critical] PERFORMANCE — Inbound dispatch blocks the transport read thread; replies deadlock against their own ack.
   - Sources: 05-module-infochat-messaging-adapter.md#F1
   - Why first: the only critical in the run — both production adapters (SimpleX, Signal) cannot reliably answer any inbound message because the reply blocks the same thread that must read its ack, so the user-facing path is non-functional and Signal additionally triggers spurious subprocess restarts.

2. [high] SECURITY — SECURITY DEFINER quarantine procedures are executable by PUBLIC.
   - Sources: 02-module-infochat-core.md#F1
   - Why first: `approve_quarantine`/`reject_quarantine` were never `REVOKE`d from PUBLIC, so the `infochat_collector` role can restore quarantined (possibly injection/malware) content into a post body and flip it READY — collapsing the D34 role-isolation trust boundary the two-service split depends on.

3. [high] SECURITY — Re-eval of a released infra-failure post does not re-hide judge-confirmed-hostile content.
   - Sources: 06-module-infochat-collector.md#F1
   - Why first: when a recovered Stage-2 judge classifies a previously-released post as INJECTION/MALWARE, the code only bumps a counter and leaves it user-visible for the full attempt budget, serving judge-confirmed-malicious content to users contrary to the spec's "stays QUARANTINED" rule; also untested.

4. [high] MAINTAINABILITY-RULES-DRIFT — Default provider throws UnsupportedOperationException for 5 of 6 tasks that production already routes to it.
   - Sources: 04-module-infochat-llm-adapter.md#F1
   - Why first: every default (local-Ollama) deployment permanently degrades tagger, entity, summarizer, chat-agent and translator to their failure paths and silently ignores configured `infochat.llm.tagger.*`/`infochat.llm.entity.*` keys — broad blast radius across both services on the default profile.

5. [high] MAINTAINABILITY-RULES-DRIFT — Group adapters do not strip the bot mention span from delivered text.
   - Sources: 05-module-infochat-messaging-adapter.md#F2
   - Why first: group commands arrive as `@bot /summary tech` and fail to parse, breaking all group interaction; the wrong behavior is additionally locked in by a passing test, so it will not self-correct.

## Cross-cutting themes

### CT1. Hand-written `@NonNull` contradicts the null-marked package default repo-wide

- **Pattern:** every `app.zcat.infochat` package is null-marked under NullAway, so a bare reference type already means non-null and the convention (CLAUDE.md §7a) states `@NonNull` is no longer written by hand — yet hand-written `@NonNull` annotations saturate the public SPI surfaces of multiple modules, adding noise and inviting the inverse misreading (that a bare type elsewhere is intentionally weaker).
- **Where it appears:** 05-module-infochat-messaging-adapter.md#F7, 06-module-infochat-collector.md#F2 (both scored); additionally flagged as report observations in 02-module-infochat-core.md, 03-module-infochat-ssrf.md, and 04-module-infochat-llm-adapter.md (the SPI surfaces in core are noted as the pattern other modules copy).
- **Suggested system-level fix:** a single repo-wide mechanical sweep removing hand-written `@NonNull` (and now-unused imports) on its own dedicated cleanup ticket, keeping only `@Nullable`; the NullAway build continues to enforce the contracts. Resolving it at the core SPI surfaces first stops new copies propagating. Note the two scored reports filed this under different categories (see Synthesizer notes).

### CT2. Programming-error exceptions get misclassified as transient infra/LLM failures by broad catch handlers

- **Pattern:** unexpected or untyped exceptions escape into catch blocks meant for transient failures, where they are silently treated as infra/LLM downtime instead of surfacing as the bugs they are — masking real defects and producing wrong runtime behavior (permanent fallback loops, escaped error classification).
- **Where it appears:** 04-module-infochat-llm-adapter.md#F1 (UnsupportedOperationException caught as generic RuntimeException → retry-then-fallback forever), 04-module-infochat-llm-adapter.md#F5 (`catch (RuntimeException ...)` around in-memory Jackson assembly would mask a bug as LLM downtime), 03-module-infochat-ssrf.md#F1 (unwrapped IllegalArgumentException from a malformed redirect Location escapes the typed `SsrfPolicyException`/`IOException` contract that every caller's classification is built on).
- **Suggested system-level fix:** narrow catch scopes to the exceptions that can actually be thrown, wrap attacker-influenced parse failures into the module's typed exception (as the SSRF class already does for `INVALID_HOST`), and treat programming-error exception types as non-transient at worker boundaries so a misconfiguration or bug fails loudly with a diagnosable signal instead of degrading silently.

### CT3. Advertised contracts (config keys, capability flags) are not actually honored

- **Pattern:** the code advertises a contract callers may rely on — operator-facing config keys, capability flags — that no code path consults or enforces, so the promise is documentation masquerading as enforced behavior.
- **Where it appears:** 04-module-infochat-llm-adapter.md#F1 (`infochat.llm.tagger.*`/`infochat.llm.entity.*` keys are shipped in `application.properties` but the provider never reads them), 05-module-infochat-messaging-adapter.md#F5 (`maxInflightSends`/`maxSendsPerSecond` capabilities advertised via `capabilities()` but enforced nowhere; §6.3.7 bounded inbound queue + throttle reply unimplemented).
- **Suggested system-level fix:** for each advertised contract either implement the enforcement (dynamic per-task config read; outbound governor + bounded inbound queue) or stop advertising it as enforced (explicit "not yet enforced" markers); a startup guard that asserts presence of every advertised key/capability would make the advertised-vs-honored gap a build/boot failure rather than a silent runtime no-op.

### CT4. Tests pin behavior the code's own contract declares wrong, locking in defects

- **Pattern:** tests assert on surfaces the code documents as non-contractual, or assert behavior that contradicts the spec the test should enforce — so the suite stays green while the real contract drifts or a defect is cemented.
- **Where it appears:** 03-module-infochat-ssrf.md#F3 (every policy test asserts the human-facing message text the javadoc declares "free to reword", while `reason()` — the surface callers actually branch on — is never asserted anywhere in the module), 05-module-infochat-messaging-adapter.md#F2 (`SignalGroupEndToEndTest` asserts the un-stripped mention body, locking in behavior that violates design §6.10 step 3).
- **Suggested system-level fix:** as part of fixing each underlying finding, re-point the tests at the contractual surface (assert `reason()` not message text; assert the stripped command body) under explicitly-authorized test-modification tickets, so the suite catches the bug class that matters rather than the cosmetic one.

### CT5. Admin-notification throttling: one canonical notifier, plus an inline re-implementation that drifted

- **Pattern:** `ThrottledAdminNotifier` (infochat-core) is the shared, hardened admin-notification path, but the Provider re-implements the throttle UPSERT inline and drifted from the per-`(channel, error_class)` coalescing contract, while the canonical core copy itself carries a residual log-injection asymmetry — so the one notification surface exists in two subtly-different forms.
- **Where it appears:** 01-architecture.md#F2 and 01-architecture.md#F3 (NEEDS_REVIEW double-fires across two modules; the inline `QuarantineReviewListener` copy collapses PENDING and NEEDS_REVIEW into one throttle key, re-implementing the shared notifier), 02-module-infochat-core.md#F4 (the canonical `ThrottledAdminNotifier.getState` logs the raw unsanitized key on its failure path).
- **Suggested system-level fix:** route every admin notification through the single core `ThrottledAdminNotifier` (deleting the inline UPSERT and encoding the discriminator in the key as other callers do), and fix the core notifier's `getState` to log `safeKey`; this removes the drift surface and assigns each spec-defined transition a single owner.

## Findings by category

### SECURITY (6)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | Re-eval of a released infra-failure post does not re-hide judge-confirmed-hostile content | ReEvaluationJob.java:106-127, 173-185 | 06-module-infochat-collector.md#F1 |
| high | SECURITY DEFINER quarantine procedures are executable by PUBLIC | V21__quarantine_admin.sql:24-119, V25__quarantine_procedure_remediation.sql:12-103, V32__quarantine_review_notify_completeness.sql:33-148 | 02-module-infochat-core.md#F1 |
| medium | Malformed `Location` header escapes the typed exception contract as `IllegalArgumentException` | SsrfGuardedHttpClient.java:380-389 | 03-module-infochat-ssrf.md#F1 |
| medium | Signal DM notification path lacks the reader-survival guard the group path has | SignalJsonRpcClient.java:514-553, SignalMessageCodec.java:153-156 | 05-module-infochat-messaging-adapter.md#F4 |
| low | `getState` logs the raw caller key on failure, bypassing its own sanitizer | ThrottledAdminNotifier.java:285-313 (line 310) | 02-module-infochat-core.md#F4 |
| low | Local-only guard rejects a cloud-only provider as a per-task override but accepts it as the global default | LlmRouterStartupGuard.java:144, 200-215 | 04-module-infochat-llm-adapter.md#F8 |

### PERFORMANCE (5)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| critical | Inbound dispatch blocks the transport read thread; replies deadlock against their own ack | SimpleXWebSocketClient.java:260-286, 306-327; SignalJsonRpcClient.java:395-441, 514-553 | 05-module-infochat-messaging-adapter.md#F1 |
| high | Last-admin trigger locks the whole `users` table on every row update | V35__last_admin_errcode.sql:25-58, 60-80; V5__identity_audit.sql:95-115; V24__identity_audit_remediation.sql:60-91 | 02-module-infochat-core.md#F2 |
| high | Signal open-handle registry leaks one entry per non-finalized send | SignalJsonRpcClient.java:119-127, 217-243 | 05-module-infochat-messaging-adapter.md#F3 |
| medium | JVM-wide exclusive pin lock held across connect + headers of every hop (~140 s adversarial hold serializes all outbound dials) | SsrfGuardedHttpClient.java:347-398, 599-614; PinnedDnsResolver.java:111-118 | 03-module-infochat-ssrf.md#F2 |
| medium | `searchPosts` acquires up to four pooled connections per single tool call | SearchPostsTool.java:56-66, 69-78, 80-94, 116-130, 132-178 | 07-module-infochat-provider.md#F3 |

### SIMPLIFICATION (1)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| low | `joinPath` and `preview` triplicated while the shared support class already exists | OpenAiCompatibleProvider.java:257-273, AnthropicProvider.java:215-230, OpenAiCompatibleEmbeddingProvider.java:214-238 (vs LlmHttpSupport) | 04-module-infochat-llm-adapter.md#F9 |

### MAINTAINABILITY-RULES-DRIFT (23)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | Default provider throws `UnsupportedOperationException` for 5 of 6 tasks that production already routes to it | OpenAiCompatibleProvider.java:157-166 | 04-module-infochat-llm-adapter.md#F1 |
| high | Group adapters do not strip the bot mention span from delivered text | SignalGroupHandler.java:159-167, SimpleXGroupHandler.java:76-84, SignalGroupEndToEndTest.java:83 | 05-module-infochat-messaging-adapter.md#F2 |
| high | `/stop` worst-case bound (statement_timeout + pg_cancel_backend) not applied to chat-tool or `/summary` queries | InFlightTracker.java:36, CancellationService.java:46-81, SearchPostsTool.java, EligiblePostQuery.java | 07-module-infochat-provider.md#F1 |
| high | The module-DAG guard "collector ↛ messaging-adapter" is claimed to be build-enforced but isn't | pom.xml (all six module poms), docs/design/09-reference.md:41-43 | 01-architecture.md#F1 |
| medium | Declared concurrency/rate caps are never enforced; inbound back-pressure is unimplemented | SimpleXAdapter.java:64-78, SignalAdapter.java:72-86, SimpleXWebSocketClient.java:168-221, SignalJsonRpcClient.java:217-228 | 05-module-infochat-messaging-adapter.md#F5 |
| medium | Design note claims `infochat-core` is "Pure Java; no Quarkus, no I/O" — the code is neither | docs/design/09-reference.md:30, 39 | 01-architecture.md#F5 |
| medium | `Entry.supportedLanguages` carries a false `@Nullable` contract that generates dead code and a contradictory javadoc | LlmRouter.java:332-341 (dead check 161-171) | 04-module-infochat-llm-adapter.md#F4 |
| medium | Module tests pin the non-contractual message text; `reason()` — the contract — is never asserted | SsrfGuardedHttpClientTest.java:64-68 (+ 78-82, 88-92, 102-106, 134-137, 172-176, 194-198, 354-360, 425-431) | 03-module-infochat-ssrf.md#F3 |
| medium | `NEEDS_REVIEW` transitions fire two independent admin notifications across two modules | ReEvaluationJob.java:152-171, QuarantineReviewListener.java:143-152 | 01-architecture.md#F2 |
| medium | Newly-approved groups emit spurious missed-slot audit rows and admin notifications | DigestScheduler.java:103-139 (esp. 124-128) | 07-module-infochat-provider.md#F2 |
| medium | `QuarantineReviewListener` coalesces all actionable statuses into one throttle key | QuarantineReviewListener.java:67, 87-106, 143-188 | 01-architecture.md#F3 |
| medium | Retry-After machinery is dead code; javadoc asserts consumer behavior that does not exist; parse is unclamped if ever wired | LlmHttpSupport.java:100-135; OpenAiCompatibleProvider.java:304-313; OpenAiCompatibleEmbeddingProvider.java:264-273 | 04-module-infochat-llm-adapter.md#F3 |
| medium | SPI javadoc contradicts the record it produces on the `sourceId` contract | Fetcher.java:25-28, StreamSource.java:27-28, NormalizedPost.java:17-21 | 02-module-infochat-core.md#F3 |
| medium | The active hardware profile has two parallel sources of truth | collector/provider InfochatProfile.java; StartupReleaseOnStage2FailureWarn.java:82, StatusCommandHandler.java:61, EligiblePostQuery.java:67 | 01-architecture.md#F4 |
| medium | Unknown configured default provider falls back to CDI-discovery order instead of failing startup | LlmRouter.java:180-215 | 04-module-infochat-llm-adapter.md#F2 |
| low | CHAT_AGENT config-key segment is `chat` in code, `chat-agent` in the operator-facing design doc | ModelTask.java:28 | 04-module-infochat-llm-adapter.md#F7 |
| low | Export truncation flag has an off-by-one false positive | ExportDataCollector.java:188-199 | 07-module-infochat-provider.md#F4 |
| low | Hand-written `@NonNull` contradicts the package-default convention | module-wide: messaging MessagingAdapter.java:81-150 et al.; collector 156 occurrences across 37 files | 05-module-infochat-messaging-adapter.md#F7, 06-module-infochat-collector.md#F2 |
| low | Quarantine span offsets documented as "byte offsets" are actually char indices | QuarantineDao.java:46-52, 119-124; Stage1Pipeline.java:333-334 | 06-module-infochat-collector.md#F3 |
| low | Stale `rejectsWebsocketSchemeForNow` test narrative contradicts the class it tests | SsrfGuardedHttpClientTest.java:71-83 | 03-module-infochat-ssrf.md#F4 |
| low | Stale `resolve(...)` identity stubs throw for already-shipped tickets and are unreachable | SimpleXIdentity.java:28-31, SignalIdentity.java:28-31 | 05-module-infochat-messaging-adapter.md#F6 |
| low | Ticket IDs, reviewer-workflow references, and stale claims in production comments | LlmRouterStartupGuard.java:96-110, LlmRouter.java:94, 181-269, OpenAiCompatibleProvider.java:66, 164 | 04-module-infochat-llm-adapter.md#F6 |
| low | §7 defensive code inside the trust boundary: dead null-checks and catches around operations that cannot throw | LlmRouter.java:139-142; OpenAiCompatibleProvider.java:185, 265-268; AnthropicProvider.java:134, 222-225; OpenAiCompatibleEmbeddingProvider.java:130, 230-233 | 04-module-infochat-llm-adapter.md#F5 |

## Synthesizer notes

- The `@NonNull` convention drift was scored by two reviewers under different categories: 05-module-infochat-messaging-adapter.md#F7 filed it as SIMPLIFICATION, 06-module-infochat-collector.md#F2 filed it as MAINTAINABILITY-RULES-DRIFT. They share one root cause and one fix, so they are consolidated into a single MAINTAINABILITY-RULES-DRIFT row above (the rule-drift framing, since the §7a convention is the thing being violated). Both severities were `low`; the consolidated entry keeps `low`. The same drift was additionally raised as a non-scored observation in three further reports (core, ssrf, llm-adapter), which is why CT1 treats it as cross-cutting.
- The `high` band is unusually dense (nine findings at `high` or above). Four `high` findings did not make the top-5 and live in the tables: 02-module-infochat-core.md#F2 (global `users` table lock, PERFORMANCE), 05-module-infochat-messaging-adapter.md#F3 (Signal handle leak, PERFORMANCE), 01-architecture.md#F1 (unenforced module-DAG guard), and 07-module-infochat-provider.md#F1 (`/stop` bound not wired). The top-5 weighted the sole critical first, then the two `high` SECURITY findings and the two `high` findings with the broadest user-facing/default-deployment blast radius; the developer may reasonably re-order among the `high` band.
- Several per-target reports raised cross-module questions in their "Synthesizer-relevant observations" sections that are flagged by their authors for architecture-level confirmation but were not scored as findings — e.g. the `READY → QUARANTINED` demotion has no dedicated NOTIFY shape (06 observation, tied to 06#F1), `TranslationProvider`/`LlmProvider` placement vs spec (04 observation), the bot-mention-anchor source divergence (05 observation), and two independent transcriptions of the privileged-command closed list (07 observation). These are not represented as findings above because no per-target reviewer scored them; they are recorded here only to note they exist and would warrant a follow-up reading rather than synthesis.
