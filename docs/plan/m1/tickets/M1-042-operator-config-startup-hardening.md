---
id: M1-042
title: Operator-config + startup-guard hardening (LlmRouter, startup guard, outbox, fetcher log)
status: deferred
created: 2026-05-19
last_updated: 2026-05-19
deferred_reason: post-mvp-hardening
deferred_on: []
blocked_by: []
files_budget: 9
files_scope:
  - infochat-llm-adapter/src/main/java/io/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/main/java/io/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-collector/src/main/java/io/infochat/collector/outbox/OutboxRehydrator.java
  - infochat-collector/src/main/java/io/infochat/collector/fetch/FetchScheduler.java
  - infochat-llm-adapter/src/test/java/io/infochat/llm/routing/LlmRouterUnknownDefaultTest.java
  - infochat-llm-adapter/src/test/java/io/infochat/llm/routing/LlmRouterStartupGuardAllUrlsTest.java
  - infochat-collector/src/test/java/io/infochat/collector/outbox/OutboxRehydratorPaginationIT.java
  - infochat-collector/src/test/java/io/infochat/collector/fetch/FetchSchedulerLogRedactionTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any change to the spec or the threat model — every item bundled here is OUT-OF-MODEL per the M1-028 / M1-032..M1-034b id-range audits; this ticket is pure defense-in-depth inside operator-config territory
  - any change to SSRF allowlist, fetcher body-cap, or M1-023 / M1-028 / M1-024..M1-026 surfaces beyond the log-redaction touch on FetchScheduler
  - any change to OutboxRehydrator's rehydration semantics — the change is only how rows are loaded (paginated vs. one unbounded List)
  - any change to LlmRouter's actual routing logic — only the fallback-on-unknown-default-provider branch is touched
  - any change to LlmRouterStartupGuard's overall posture — only the validateLocalOnlyConfiguration method widens to inspect all three per-task base-URLs
  - any new Flyway migration
  - any change to the InMemoryAdapter test double, the TestRssFetcher SSRF bypass (test-only), or any test that the M1-028 / M1-032..M1-034b reports flagged as OUT-OF-MODEL but specifically scoped to test code
acceptance:
  - "LlmRouter.forTask refuses to silently fall back to entries.get(0) on an unknown infochat.llm.default.provider. EITHER (a) fail-startup mode: an unknown name causes LlmRouterStartupGuard to refuse startup with a descriptive error referencing the configured value AND the registered provider names; OR (b) fail-on-call mode with audit-loud WARN: LlmRouter.forTask logs a one-shot WARN on first invocation naming the misconfiguration AND the fallback used. Choose ONE in Implementation notes; document the rationale. grep -E 'unknown default provider|defaultProvider.*notFound|defaultProvider.*unknown|fallback' LlmRouter.java OR LlmRouterStartupGuard.java returns at least one match"
  - "LlmRouterStartupGuard.validateLocalOnlyConfiguration inspects infochat.llm.security.base-url AND infochat.llm.tagger.base-url AND infochat.embeddings.base-url when infochat.llm.local-only=true is set. A non-loopback value in ANY of the three refuses startup with a descriptive error naming the offending property. grep -E 'security\\.base-url|tagger\\.base-url|embeddings\\.base-url' LlmRouterStartupGuard.java returns at least three matches AND the inspection covers each independently (per-property, not aggregated)"
  - "OutboxRehydrator paginates rehydration. The current `List<PostPersister.PersistedPostKey>` collected over the full RAW set is replaced by a paginated/streamed shape (a fetchSize cursor, or chunked SELECT with offset/cursor; pick one in Implementation notes). The startup rehydrate path can handle ≥ 1M RAW rows without an unbounded allocation. grep -E 'fetchSize|setFetchSize|streamRaw|LIMIT\\s+\\?|cursor' OutboxRehydrator.java returns at least one match"
  - "OutboxRehydratorPaginationIT seeds N RAW rows where N > the chosen page size (e.g. N=2000 with page=500) and asserts rehydrate processes all N rows without loading them all into a single in-memory List at once (verify via a test seam: a counter that records the maximum in-flight list size during rehydrate)"
  - "FetchScheduler.tickOnce wraps the exception-chain message via the existing UrlRedactor before logging. A wrapped RssFetchException whose root cause's message contains a URL with userinfo (e.g. `https://user:secret@example.com/feed`) emerges with the userinfo redacted in the SLF4J error log. grep -E 'UrlRedactor|urlRedactor|redactUrl' FetchScheduler.java returns at least one match"
  - "FetchSchedulerLogRedactionTest forces a fetcher failure whose root-cause IOException message contains `https://user:secret@example.com/feed`, captures the SLF4J log, and asserts the literal `secret` does NOT appear in any emitted line"
  - "mvn -B clean verify from the repo root exits 0; M1-028 / M1-027 / M1-032..M1-034b tests continue to pass"
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/io/infochat/llm/routing/LlmRouterUnknownDefaultTest.java
    - infochat-llm-adapter/src/test/java/io/infochat/llm/routing/LlmRouterStartupGuardAllUrlsTest.java
    - infochat-collector/src/test/java/io/infochat/collector/outbox/OutboxRehydratorPaginationIT.java
    - infochat-collector/src/test/java/io/infochat/collector/fetch/FetchSchedulerLogRedactionTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
  - docs/spec/llm.md §Local-only posture
  - docs/spec/security.md §Secrets handling
decision_refs: []
---

# M1-042: Operator-config + startup-guard hardening

## Context

Four OUT-OF-MODEL advisories from the Tier 1 red-team reports
that do NOT trace to spec violations and do NOT bite in steady
state but represent defense-in-depth gaps inside operator-config
territory. None block T2 critical-path work; none are
exploitable by the in-model adversary; all four are
back-of-queue follow-ups.

1. **LlmRouter silent fallback on unknown default-provider**
   (id-range M1-032..M1-034b OOM #3). When the configured
   `infochat.llm.default.provider` names no registered
   `LlmProvider`, the router falls back to `entries.get(0)`
   (CDI discovery order) with no WARN. An operator typo could
   route SECURITY_JUDGE calls to an unintended provider that
   happens to be first in discovery order.

2. **`local-only` posture only inspects security base-URL**
   (id-range M1-032..M1-034b OOM #1).
   `LlmRouterStartupGuard.validateLocalOnlyConfiguration` only
   inspects `infochat.llm.security.base-url`. A
   `local-only=true` posture combined with a non-loopback
   Tagger or Embedding base-URL would not be rejected at
   startup. Violates the operator's `local-only` expectation,
   not the threat model itself (operator-config trust
   boundary).

3. **Outbox depth has no cap** (M1-028 OOM #1).
   `OutboxRehydrator.rehydrate()` collects every `status='RAW'`
   post id into an unbounded in-memory `List` before emitting.
   If the eval pipeline stalls and millions of RAW posts
   accumulate, restart triggers a single unbounded allocation.
   The threat model commits to per-source UNKNOWN auto-disable
   and NEEDS_REVIEW absolute-depth alert but does not commit
   to a bound on the pre-Stage-1 outbox.

4. **FetchScheduler exception-chain logs unredacted URL**
   (M1-028 OOM #3). `LOG.warnf(e, ...)` propagates the
   exception's message into logs. The production `RssFetcher`
   redacts URLs via `UrlRedactor.redact` before throwing
   `RssFetchException`, but the underlying `IOException.getMessage()`
   from the JDK HttpClient may include the full URL (potentially
   with userinfo). FetchScheduler is the call site that logs
   the chain.

## Why this is deferred

All four are OUT-OF-MODEL — outside the documented threat model.
The operator-config trust boundary covers items 1 and 2; items
3 and 4 are defense-in-depth inside an internal trust zone.
None are exploitable by the in-model adversary; none affect a
v1 deployment's correctness or security posture in steady state.

This bundle sits in `deferred_reason: post-mvp-hardening`
alongside M1-019 / M1-020 / M1-031 — known follow-ups, surfaced
in STATUS.md, picked up if M1 has slack before v1 tag, otherwise
carried into M2 with the same deferred-reason.

## Definition of Done

(See `acceptance` block. Each of the four items lands as one
narrow change; their tests are independent so the four can be
landed atomically OR decomposed at start time via `/m1-tick
escalate M1-042 decompose` if slack is tight.)

## Implementation notes

- **LlmRouter unknown-default behavior.** Choose between
  fail-startup and fail-loud-fallback. Fail-startup is cleaner
  (the misconfiguration is the operator's bug, fail-fast lets
  them see it immediately) but riskier (a typo blocks startup
  rather than producing a degraded run). Fail-loud-fallback
  preserves availability but trusts the operator to read logs.
  Pick per the project's posture on operator-blocking startup
  failures; the M1-008 / M1-021 schema posture is fail-startup,
  so consistency argues for fail-startup here too. Document
  the choice.

- **LocalOnly all-URLs inspection.** Three property keys to
  check: `infochat.llm.security.base-url`,
  `infochat.llm.tagger.base-url`, `infochat.embeddings.base-url`.
  Each must be loopback (host in `{localhost, 127.0.0.1, ::1}`,
  port any) when `local-only=true`. The check shape mirrors the
  existing security-only inspection — extract into a per-URL
  helper to avoid copy-paste.

- **OutboxRehydrator pagination.** Two acceptable shapes:
  (a) JDBC fetchSize + streaming cursor (single SELECT,
  bounded memory via fetch buffer); (b) chunked SELECT with
  `ORDER BY id LIMIT page_size OFFSET ?` (or keyset
  pagination). Option (a) is preferable — keyset pagination
  on UUIDs needs a stable order, which `post.id` provides but
  requires care if the chunk processing can update the same
  rows. Implementer's call; document the choice. Page size
  should be configurable (e.g. `infochat.collector.outbox.rehydrate-page-size`,
  default 500 matching M1-030's catch-up page size).

- **FetchScheduler log redaction.** Reuse the existing
  `UrlRedactor` from `infochat-collector/...fetch/UrlRedactor.java`
  (or wherever it lives — locate at start time). Wrap
  exception messages through `UrlRedactor.redactMessage(String)`
  or equivalent before passing to `LOG.warnf`. If no
  `redactMessage` entry point exists, add one or apply the
  redaction at the call site. The existing `UrlRedactor.redact(URL)`
  shape is URL-input; an exception message contains URL
  substrings — the helper either scans the message or the
  scheduler extracts URL substrings from `e.getMessage()`
  and redacts each.

## Big-picture notes

- **Bundle stays low-priority.** If T2-A authoring pressure
  is tight, leave this deferred; if v1 tag is approaching
  and slack exists, run `/m1-tick reopen M1-042` and pick it
  up. The four fixes are independent enough that decomposition
  via `/m1-tick escalate M1-042 decompose` produces four
  small tickets if even M1-042 as a bundle is too large for
  a session.

- **No spec amendment required.** Every fix lands inside
  existing spec promises (operator-config trust, defense-in-
  depth, secrets handling). If a v2 amendment ever extends
  the threat model to formally cover these surfaces, the
  fixes above are pre-emptively in place.

## Out-of-scope expansion

See `out_of_scope` block. Notably: SSRF allowlist semantics
(M1-023 / M1-024 / M1-025 / M1-026 territory) are unchanged;
outbox semantics (M1-027 / M1-028 / M1-030 / M1-031
territory) are unchanged; the LlmRouter routing logic
itself is unchanged.

## Authorized test changes

- (none — adds four new test files; modifies no pre-existing
  test.)

## Alternatives considered

- **Decompose into four separate tickets at authoring time.**
  Considered — would surface each item independently in
  STATUS.md. Rejected at authoring because all four share
  the same `post-mvp-hardening` deferral and the same
  "operator-config defense-in-depth" framing. Bundle stays
  together unless slack demands decomposition; the
  `/m1-tick escalate M1-042 decompose` path remains
  available.
- **Spec-amend the threat model to cover these surfaces.**
  Considered for the local-only-all-URLs item especially
  (a real operator-promise gap). Rejected at authoring
  because the threat model deliberately keeps operator
  config inside the operator's trust boundary; this fix
  closes the gap WITHIN that posture rather than re-shaping
  the boundary.
- **Accept all four as residual risk and skip the ticket.**
  Considered — none are exploitable in v1. Rejected because
  three of the four are near-zero implementation cost (a
  log redaction call, a startup-guard widening, a WARN log)
  and the OutboxRehydrator pagination is the right shape
  for a system that can accumulate millions of RAW rows
  under sustained eval-pipeline stalls.
