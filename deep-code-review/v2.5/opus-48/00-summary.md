# Deep code review — consolidated summary

**Run directory:** deep-code-review/v2.5/opus-48
**Date:** 2026-06-08 18:45
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

All seven targets completed; no targets failed or are missing. Prioritization below is complete across the full module set.

## Top priority

1. [high] PERFORMANCE — the semantic-link self-join cannot use the pgvector HNSW index and degrades to a full per-driving-post scan of the co-temporal embedding set.
   - Sources: 06-module-infochat-collector.md#F1
   - Why first: the only `high` performance finding; both `<=>` operands are column references so the paid-for HNSW index is never used, and the cost compounds as ingest grows (up to 64 driving posts/tick each scanning the full window).

2. [high] MAINTAINABILITY-RULES-DRIFT — `/get-tags` and `/get-sources` are spec-committed v1 commands advertised in the welcome/probation surfaces but have no registered handler, so invoking them returns "Unknown command."
   - Sources: 07-module-infochat-provider.md#F1
   - Why first: the only `high` drift finding; it is user-facing on the most-trafficked onboarding path and internally inconsistent across three surfaces (probation allow-set + two bundle strings).

3. [medium] MAINTAINABILITY-RULES-DRIFT — `/retry --digest` deletes the cached digest row before re-running, but the worker's in-flight guard can silently skip the re-run while the admin is told SUCCESS.
   - Sources: 07-module-infochat-provider.md#F2
   - Why first: destructive (cache lost with no replacement) combined with an actively misleading success report; violates the spec's "retry replaces the cached digest" contract.

4. [medium] MAINTAINABILITY-RULES-DRIFT — the "fatal" embedding dimensionality-mismatch path throws every scheduler tick forever with no admin notification and no halt, so posts silently never become visible.
   - Sources: 06-module-infochat-collector.md#F2
   - Why first: a spec-declared "fatal, operator-action-required" condition implemented as a silent infinite retry/log loop; user-visible store is starved of every post in the affected window with nobody told.

5. [medium] SIMPLIFICATION — the `new_price_snapshot` NOTIFY channel is emitted on every snapshot write but has zero production consumers, and the spec'd Provider in-process cache it would invalidate does not exist.
   - Sources: 01-architecture.md#F1
   - Why first: a per-write `pg_notify` round-trip that is pure overhead today plus a spec guarantee (cache + reconnect-flush) the code does not provide; resolving it requires a spec/code reconciliation either way.

(The two remaining `medium` findings — core#F1 audit denormalization and llm-adapter#F1 `forTask` null-check — are in the category tables below; they rank just under the top five because their blast radius is narrower than the five above.)

## Cross-cutting themes

### CT1. Defensive null-checks between internal classes contradict the null-marked contract

- **Pattern:** Methods inside the trust boundary throw on a bare (non-`@Nullable`) reference parameter that NullAway already proves non-null at compile time, so the branch is unreachable dead code that violates engineering-rules §7 / §7a (no defensive code for impossible scenarios).
- **Where it appears:** 03-module-infochat-ssrf.md#F3, 04-module-infochat-llm-adapter.md#F1
- **Suggested system-level fix:** Treat the null-marked package default as the enforcement mechanism it is intended to be: delete in-boundary null guards on bare reference parameters wherever they appear, keeping null-checks only at enumerated system boundaries (config parsing, wire deserialization). A one-time grep for `== null) throw` / `Objects.requireNonNull` on non-`@Nullable` parameters across all modules would surface any other instances before they accrete.

### CT2. Comments assert facts about the system that are no longer (or were never) true

- **Pattern:** WHY-comments carry a factual claim the code contradicts — a duplicated-literal location that two of three providers do not use, a documented offset unit that the code does not store, and a "not yet wired" rationale for a gate that is now wired. Each misleads a future reader into trusting a false invariant.
- **Where it appears:** 04-module-infochat-llm-adapter.md#F3, 06-module-infochat-collector.md#F3, 07-module-infochat-provider.md#F3
- **Suggested system-level fix:** Each is individually a doc/comment correction (no behavior change). As a pattern, these are comments that pin a claim about *other* code or *historical* state, which is exactly the rot CLAUDE.md §Coding style warns against; prefer comments that explain local intent over comments that mirror facts maintained elsewhere, and audit comments that name a sibling location or a wiring state when that sibling/state changes.

## Findings by category

### SECURITY (2)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| low | Locale-dependent case folding in timezone fuzzy suggestions | GroupTimezoneCommandHandler.java:198,200,207-210 | 07-module-infochat-provider.md#F4 |
| low | Signal mention comparison is not constant-time, unlike the SimpleX sibling that treats the same comparison as a timing-sensitive trust anchor | SignalMentionParser.java:53-63 | 05-module-infochat-messaging-adapter.md#F1 |

### PERFORMANCE (2)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | Semantic-link query cannot use the pgvector HNSW index | LinkingJob.java:257-296 | 06-module-infochat-collector.md#F1 |
| low | Virtual-thread-per-read in the body reader | SsrfGuardedHttpClient.java:530-593 (loop at 552-553) | 03-module-infochat-ssrf.md#F1 |

### SIMPLIFICATION (3)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| medium | `new_price_snapshot` channel has no consumer and the spec'd Provider cache does not exist | PriceSnapshotStore.java:46-52,123-133, AssetSnapshotReader.java:88-112 | 01-architecture.md#F1 |
| low | AssetSnapshotFetcher injects three unused config fields as speculative scaffolding | AssetSnapshotFetcher.java:119-129 | 06-module-infochat-collector.md#F4 |
| low | The two HTTP `LlmProvider` impls duplicate the full call pipeline | AnthropicProvider.java:113-184, OpenAiCompatibleProvider.java:154-224 | 04-module-infochat-llm-adapter.md#F2 |

### MAINTAINABILITY-RULES-DRIFT (12)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | `/get-tags` and `/get-sources` are advertised but unimplemented | HelpCommandHandler.java:98-101, CommandPermissions.java:45-56, bundles/en.properties:100,167 | 07-module-infochat-provider.md#F1 |
| medium | `/retry --digest` can destroy the cached digest and still report success | DigestRetryService.java:69-88, DigestWorker.java:76-91 | 07-module-infochat-provider.md#F2 |
| medium | "Fatal" embedding dimensionality mismatch throws every tick with no operator signal | EmbeddingWorker.java:241-256 | 06-module-infochat-collector.md#F2 |
| medium | `delete_preban_user` audit row drops the spec-mandated denormalized actor columns | V24__identity_audit_remediation.sql:40-53 | 02-module-infochat-core.md#F1 |
| medium | `forTask` null-checks a parameter that the null-marked contract forbids being null | LlmRouter.java:138-141 | 04-module-infochat-llm-adapter.md#F1 |
| low | `DEFAULT_BODY_CAP_BYTES` Javadoc describes a mirroring that two of three providers do not do | LlmHttpSupport.java:37-45 | 04-module-infochat-llm-adapter.md#F3 |
| low | `effectivePort` returns the wrong default for `wss` | SsrfGuardedHttpClient.java:449-455 | 03-module-infochat-ssrf.md#F2 |
| low | Quarantine span offsets are documented as bytes but are char offsets | QuarantineDao.java:46-52,119-124, Stage1Pipeline.java:341-342,400-402 | 06-module-infochat-collector.md#F3 |
| low | `quarantine_review` consumer treats an unknown `target_kind` discriminator as a `post` event | QuarantineReviewListener.java:194-206,276-289 | 01-architecture.md#F2 |
| low | Redundant defensive null-check on `blocklist` | SsrfGuardedHttpClient.java:197-199 | 03-module-infochat-ssrf.md#F3 |
| low | Signal group-message timestamp extraction lacks the presence/type guard the codec applies to the DM path on the same trust boundary | SignalGroupHandler.java:157-159 | 05-module-infochat-messaging-adapter.md#F2 |
| low | Stale rationale on the in-handler ban check in `/add-source` | AddSourceCommandHandler.java:50-55,124-128 | 07-module-infochat-provider.md#F3 |

## Synthesizer notes

- Several per-target reports (ssrf, messaging-adapter, llm-adapter, collector, provider) appended their own "Synthesizer-relevant observations" listing cross-module contract checks they explicitly deferred to the architecture lens — e.g. whether Provider LISTEN parsers consume the collector's NOTIFY byte shapes correctly, the reconnect-window send-classification divergence between the SimpleX and Signal adapters, the JVM-global `InetAddressResolverProvider` uniqueness, and the sanitizer/chat-registry closed-list-vs-spec equality. The architecture report (01) raised two NOTIFY findings (F1, F2) but did not record dispositions for those specific deferred cross-module checks. This is an observation about coverage of the reports, not a new finding; a reader tracing the deferred items will not find all of them resolved in 01.
- No SECURITY findings above `low` were surfaced by any reviewer. The ssrf, llm-adapter, and collector reports each affirmatively recorded that the core security postures they reviewed (SSRF routing/pinning/XXE closure, API-key non-logging, prompt-injection wrapping, outbox/NOTIFY transactionality) matched spec commitments with no finding.
