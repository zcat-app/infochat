---
id: M1-970
title: "Brave search client: pinned host, budget guard, typed snippets"
status: pending
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction: >-
  BraveSearchClientTest#pinnedHostQueryReturnsTypedSnippetList
  (to-be-written; child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/websearch-grounding-lane.md; converted at
  /tick start: written first, run RED — the class under test does not
  exist, and grep -rn 'brave\|BraveSearch' infochat-provider/src/main
  returns NO match on this checkout (verified 2026-09-01), so today's
  behavior for the motivating query class is the spec'd KB-miss →
  general-knowledge fallback (security.md:329) with no way to go
  online). The wrong behavior it states: no code path exists that
  queries an external search API for chat grounding — the lane's
  client, budget guard, and typed snippet emission are absent.
analysis_ref: docs/plan/m1/tick-analysis/websearch-grounding-lane.md
blocked_by: [M1-969]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/websearch/BraveSearchClient.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/websearch/WebSearchSnippet.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/websearch/WebSearchBudgetGuard.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/websearch/WebSearchGate.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/websearch/BraveSearchClientTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/websearch/WebSearchBudgetGuardTest.java
complexity: medium
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    REGISTRATION AS A CHAT TOOL (analysis P2, BINDING): no
    ChatToolRegistry/ChatToolCatalog/ChatToolDispatcher/TOOL_INSTRUCTIONS
    change — the lane's first increment is a dispatch-layer service the deterministic
    KB-miss pre-fetch (M1-972) calls directly; the model-elected arm
    (T1) is a separately decided later ticket. The closed allowlist
    stays eight names and the byte-pinned instruction table does not
    move.
  - >-
    Fusion, dual-query, translation, and temporal resolution — M1-971's
    lane. This client exposes ONE typed search(query, searchLang) call;
    it does not compose queries or rank anything.
  - >-
    ANY chat-path wiring — no ChatAgent/prompt/notice change (M1-972);
    the client is dead code until then, exercised by its own tests.
  - >-
    Fetching result URLs, page bodies, or any follow-on request — the
    first increment is snippet-only from ONE pinned endpoint (BINDING; the amendment's
    bound); the client issues exactly ONE GET per call and follows no
    result URL ever (redirect following stays the SSRF gate's own
    capped per-hop re-validation).
  - >-
    A DB-backed budget counter — the guard is in-memory with the
    documented restart residual (the circuit breaker's own posture,
    security.md:1760-1761); the vendor credit limit is the true hard
    cap. A durable counter is a follow-up if the residual bites.
  - >-
    Config documentation drift — every new infochat.* key lands in
    application.properties AND the spec/design text that names it in the
    SAME diff (DocumentedConfigKeyParityTest discipline, M1-708); no
    key is added without its property line.
acceptance:
  - "REPRODUCTION closed: BraveSearchClientTest.pinnedHostQueryReturnsTypedSnippetList passes — against a local com.sun.net.httpserver harness (the collector fetcher-test idiom) the client's search(query, searchLang) issues exactly ONE GET whose request line and Host header carry the code-pinned vendor host and path (asserted verbatim against the single HOST/PATH constant pair — the owner-live-verified web-search path), whose query string carries the query text and the search-language parameter URL-encoded (asserted; no other user-derived bytes), with an over-bound query (longer than the verified 400 chars / 50 words) clamped BEFORE the request, and whose headers carry the operator key from the infochat.websearch.api-key property (env-backed) in the verified x-subscription-token header plus Accept: application/json; the typed result list's fields (title, url, snippet, extra_snippets, the per-result age/date field when the fixture carries one) come from the harness's RANKED fixture JSON with order preserved (the rank input M1-971 fuses over). Mutations failing it: a host/path taken from any caller input, a second request, an unclamped over-bound query, or a rank-order-scrambling parse."
  - "TYPED PARSE + CAPS (analysis P6, the M1-940 discipline): BraveSearchClientTest.overCapFieldsAreTruncatedCodeAtFixedCaps — a fixture whose snippet/title exceed the per-entry byte caps is cut on a code-point boundary with the repo's truncation marker; a fixture whose entry count exceeds the per-call entry cap drops the tail WHOLE (order preserved); absent/null fields surface as null, never invented; an unparseable or non-2xx response is a typed failure, never a partial list. Non-vacuity: a no-truncation mutation fails the first arm; a mid-entry cut fails the whole-entry arm."
  - "FAILURE-MODE (degrade posture): BraveSearchClientTest.everyFailureDegradesToTypedEmptyNeverThrows — an SSRF policy rejection (SsrfGuardedHttpClient.SsrfPolicyException), a connect timeout, a 4xx/5xx status (including the verified typed 422 ErrorResponse an unauthenticated or bad-token probe returns), and a malformed body each surface as the typed empty/failure result the caller (M1-972) converts to no-web-grounding or the honest-refusal rung; NO exception escapes the search() boundary and NO retry is attempted (one call, one outcome)."
  - "BUDGET GUARD (analysis P5, engineering-rules §9): WebSearchBudgetGuardTest.monthWindowUsesInjectedClockAndTripsAtCap — with QuarkusMock/SimpleRegistry-injected Clock pinned mid-month, calls up to the configured monthly cap consume, the (cap+1)-th call inside the same month window is refused; crossing the month boundary on the pinned Clock resets the window; the guard's month arithmetic reads ONLY the injected Clock (probe: grep -n 'Instant.now()\\|LocalDate.now()' over the websearch package returns nothing — the §9 rule)."
  - "KILL-SWITCH + OPT-OUT (analysis P4): BraveSearchClientTest.disabledOrOptedOutScopeIssuesZeroRequests — with infochat.websearch.enabled=false the gate refuses before any HTTP attempt (the harness records zero requests); with the scope resolving to opted-out (the gate seam's scope input) likewise; the refusal is a typed no-op result, never an error."
  - "TTL RESULT CACHE (identical-call economy): BraveSearchClientTest.identicalQueryAndLanguageServedFromCache — two search() calls with identical (query, searchLang) issue ONE harness request (the second served from the in-memory TTL cache, bounded capacity, the QueryTranslationCache idiom); a different searchLang misses. A no-cache mutation fails on request count."
  - "KEY HANDLING (§Secrets handling): the API key is read from configuration (env-backed property), never logged, never in any result or exception text — probe: grep -rn 'api-key\\|apiKey' over the websearch package shows the single ConfigProperty read; the harness asserts the key travels ONLY in the request header."
  - "ALLOWLIST FENCE (analysis P2/P3): git diff names NO ChatToolRegistry/ChatToolCatalog/ChatToolDispatcher/ChatAgent production file — probe: mvn -pl infochat-provider -am test -Dtest='ChatToolRegistryTest,ChatToolAllowlistSpecParityTest,ChatAgentTest' is green UNMODIFIED (still eight tools; the byte-pinned instruction table untouched)."
  - "CONFIG PARITY: the new keys (infochat.websearch.enabled, .api-key, .monthly-budget, .result-cache-ttl, and the opt-out key named in the Approach) land in application.properties with profile defaults where the repo convention requires — probe: grep -c 'infochat.websearch' infochat-provider/src/main/resources/application.properties returns every key the code reads."
  - "mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/websearch/BraveSearchClientTest.java
      — pinnedHostQueryReturnsTypedSnippetList (the reproduction),
      overCapFieldsAreTruncatedCodeAtFixedCaps,
      everyFailureDegradesToTypedEmptyNeverThrows,
      disabledOrOptedOutScopeIssuesZeroRequests,
      identicalQueryAndLanguageServedFromCache, plus the key-handling
      assertion arm.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/websearch/WebSearchBudgetGuardTest.java
      — monthWindowUsesInjectedClockAndTripsAtCap (pinned-Clock,
      engineering-rules §9).
  preserves:
    - >-
      all tests currently green on main — explicitly
      ChatToolRegistryTest.registryContainsExactlySpecTools,
      ChatToolAllowlistSpecParityTest, ChatAgentTest
      .renderedInstructionTableIsByteIdentical and
      everyRegisteredToolIsAdvertised (all UNMODIFIED: no tool is
      added), and every SsrfGuardedHttpClient suite (the library is a
      dependency, not a diff surface).
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/security.md §SSRF and outbound connections
  - docs/spec/security.md §Rate limiting
  - docs/spec/security.md §Secrets handling
  - docs/spec/security.md §Failure handling
decision_refs:
  - D20
  - D19
  - D28
---

# M1-970: Brave search client — pinned host, budget guard, typed snippets

## Context

The web-grounding lane authorized by M1-969 needs its egress vehicle: a
client that queries the pinned vendor endpoint — the owner-live-verified
ranked web-search surface, whose per-result ranking is the fusion's rank
input (the context-shaped grounding endpoint exposes none and is
reserved for the deferred T1 single-query shape) — behind the
deployment's existing SSRF gate, budget-guarded and kill-switchable,
emitting a typed, capped snippet record list and degrading to
typed-empty on every failure. This is the lane's only outbound surface;
fusion and chat wiring are the siblings'. Shared analysis:
`analysis_ref:` (this ticket carries P2, P4, P5, P6, P13, P14).

## Root cause

Verified absence: no `brave`/`BraveSearch` symbol anywhere in provider
main (grep, 2026-09-01); the Provider's only `SsrfGuardedHttpClient`
consumers are the `/add-source` probe path (spec §SSRF enumerates
exactly that, `security.md:198-209`, until M1-969 widens it); the
client therefore builds on `SsrfGuardedHttpClient.get(URI,
Map<String,String>)` (`SsrfGuardedHttpClient.java:397`), whose
caller-headers are origin-scoped on cross-origin redirects
(`:400-407`) — the key header cannot leak to a redirect target. The
house idiom is hand-rolled JDK HttpClient behind the gate; no bespoke
HttpClient is created (the `AssetDataSource` contract's rule,
`AssetDataSource.java:16-19`). The endpoint surface is
owner-live-verified: `GET /res/v1/web/search` returns RANKED web
results with params `q` (≤400 chars/50 words), `search_lang`,
`count` (≤20), `freshness`, `extra_snippets`, `safesearch`; auth
header `x-subscription-token`; an unauthenticated probe returns a
typed 422 `ErrorResponse`.

## Pitfalls

Carried from the analysis: P2 (NOT a tool — the allowlist/byte-pin
surfaces must not move), P4 (egress enumerated: query text + declared
language only; key from env; kill-switch/opt-out as the valve), P5
(injected-Clock month window; in-memory restart residual documented;
one call per invocation, no retries; the fallback sub-cap shares the
monthly budget so a hard-down primary cannot drain the credit), P6
(typed, capped, whole-entry emission — the vendor response is
endpoint-chosen input per trust boundary item 9's logic), P13 (no
instance data in tests — the harness fixtures are synthetic), P14 (the
endpoint and parameter surface are owner-live-verified and land as ONE
constant pair; pricing/qps/SOC-2 claims stay implementer-verified at
start; the M1-968 spike exercises the real shape operator-side).

## Approach

Derived from `spec_refs:` — the M1-969 amendment is this client's
authorization (§Prompt-injection defenses' enumerated class; §SSRF's
pinned-host lane; §Rate limiting's budget entry; §Secrets handling's
egress inventory; §Failure handling's degrade posture).

- **Files to touch:** `files_scope` (four new main classes in a new
  `chat/websearch` package, one properties file, two test classes).
- **Pre-decided shapes (implementation is execution):**
  1. `WebSearchSnippet` — the typed record (title, url, snippet,
     extra_snippets, the per-result age/date field when the vendor
     supplies one; nulls allowed, nothing invented).
  2. `BraveSearchClient` — `@ApplicationScoped`; ONE public
     `search(String query, String searchLang)` returning a typed
     result (list or typed-failure); ONE code constant pair for
     host+path (P14: the owner-verified web-search path); clamps the
     query to the verified vendor bound (≤400 chars/50 words) BEFORE
     the request; builds the query string from the query text +
     searchLang plus the caller-settable `count`/`freshness`
     parameters only (URL-encoded; never any other user-derived
     bytes; `safesearch` pinned at its safe default), calls
     `ssrfGuardedHttpClient.get(uri, Map.of("Accept",
     "application/json", "x-subscription-token", apiKey))`, parses
     the RANKED results under the fixed caps, checks the TTL cache
     keyed (query, searchLang) before the call. No other public
     surface — M1-971 composes and assembles the block, M1-972 gates.
  3. `WebSearchBudgetGuard` — in-memory monthly counter on the
     injected `Clock` (`@Produces @ApplicationScoped Clock`, the §9
     pattern; tests pin it); `tryAcquire()` boolean; window = calendar
     month of clock.instant() in UTC; a documented fallback sub-cap
     (a `infochat.websearch.*` property recorded in the design note
     the M1-969 amendment names) bounds how much of the monthly budget
     fallback-triggered calls may consume, so a hard-down primary
     source cannot drain the credit (P20).
  4. `WebSearchGate` — resolves enabled/kill-switch (config) +
     per-scope opt-out (config-listed scope keys; the scope coordinates
     arrive as method inputs, never stored) + budget, exposing one
     `boolean allowed(scopeKind, scopeId)` the client's caller uses
     before ANY HTTP attempt.
  5. Properties: `infochat.websearch.enabled` (default false — the
     lane ships dark until the operator turns it on),
     `.api-key` (env-backed), `.monthly-budget` (default from M1-968's
     formula record in design notes), `.result-cache-ttl`,
     `.opted-out-scopes` (empty default).
- **Steps, in implementation order:** (1) verify the endpoint constant
  pair against the live API (P14 step; record in the commit); (2) the
  record + client against the local HTTP harness, RED first; (3) budget
  guard + gate + cache; (4) properties; (5) full verify.
- **Controls to preserve (§10):** the SSRF library is a DEPENDENCY —
  its suites pass untouched; no new HttpClient construction outside it;
  D37 (no user text in logs/metrics — the client logs class + outcome
  only, SafeLog on failures); the tool surface (eight names) and the
  byte-pinned table untouched.
- **Pitfall→mitigation:** P2→acceptance item 8's fence probe; P4→item
  5/7; P5→item 4; P6→item 2; P13→synthetic fixtures; P14→step 1's
  verification + the single constant pair.

## Definition of done

The reproduction and all failure-mode drives pass against the harness;
the budget guard trips on the pinned Clock and resets at the month
boundary; kill-switch/opt-out issue zero requests; the TTL cache
serves identical calls once; the key travels only in the header; the
tool surface and every pre-existing suite pass unmodified; config
parity holds; `mvn verify` green from the repo root.

## Verification

- P2 → acceptance item 8 (the registry/catalog/dispatcher/parity fence
  probe — a registration attempt fails it).
- P4 → item 5 (zero-request refusal) + item 7 (key travels only in the
  header).
- P5 → item 4 (pinned-Clock month arithmetic; the Instant.now() grep
  probe).
- P6 → item 2 (truncation, whole-entry drop, nulls, typed failure).
- P13 → reviewer check: harness fixtures are synthetic; no instance
  identifier anywhere in the diff.
- P14 → step 1's recorded verification of the constant pair.
- FAILURE-MODE coverage → items 2-5 each feed hostile/edge input (SSRF
  rejection, timeout, non-2xx, malformed body, over-cap fields,
  exhausted budget, disabled gate) to this diff's own production code
  and assert the protected behavior.

## Out-of-scope

Named in `out_of_scope`: tool registration (T1 is a separate decision);
fusion/translation/temporal (M1-971); chat wiring (M1-972); result-URL
fetching (never — not in the first increment nor in any later lane); a
DB-backed budget; undocumented config keys.
No pre-existing test is modified.

## Census

Class-scoped in one discipline: the deployment's outbound-HTTP
construction sites — §SSRF's "no bespoke HttpClient" rule
(`AssetDataSource.java:16-19` states the contract; the spec section
backs it) is a class guard, and this ticket adds one site to the
class. Re-runnable enumeration: `grep -rn 'SsrfGuardedHttpClient'`
over `src/main` in both services, plus `grep -rn 'newHttpClient\|HttpURLConnection'`
over the same (the bespoke-construction negative space). Dispositions
(sites as returned at draft time, 2026-09-01):

- NEW `chat/websearch/BraveSearchClient` → **FIX**: rides
  `SsrfGuardedHttpClient.get(URI, Map)`; acceptance item 1 pins the
  single gated GET, item 8 the fence.
- Provider consumers (the `/add-source` probe path / `UrlProbe` —
  the spec's own §SSRF enumeration of Provider outbound) → DISPOSED,
  untouched.
- Collector consumers (`CollectorSsrfClientProducer`,
  `AssetDataSource` and its impls, `BlueskyFetcher`,
  `NostrRelayConnection`, `FetchScheduler`) → DISPOSED, untouched.
- `infochat-ssrf` itself → DISPOSED, dependency only — its suites
  pass unmodified (`test_plan.preserves`).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-970-websearch-brave-client.md
```
