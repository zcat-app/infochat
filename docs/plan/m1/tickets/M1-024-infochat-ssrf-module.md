---
id: M1-024
title: infochat-ssrf module + RssFetcher hardening (M1-023 remediation)
status: done
created: 2026-05-14
last_updated: 2026-05-15
# (status briefly transited escalated→in-progress on 2026-05-15 per
#  refine-after-premise-fail; see escalations[] + revisions[] entries
#  below. Branch m1/M1-024-infochat-ssrf-module retains the implementation.)
blocked_by:
  - M1-023
remediates: M1-023
clarity_check:
  date: 2026-05-15
  verdict: WARN
  warnings:
    - "ACCEPTANCE-VS-DOD-CONSISTENT / item 2: `grep -cE '<artifactId>' infochat-ssrf/pom.xml is exactly 2` is ambiguous — if test-scoped deps (JUnit 5) are declared in the child pom, count exceeds 2 and the grep fails. Mitigations: enumerate exact <dependency> blocks in DoD; or relax to `at least 1 match` + a separate `grep -E '<scope>runtime</scope>' returns zero`; or rely on parent BOM to manage all test deps without per-module declarations."
    - "ACCEPTANCE-RUNNABLE / item 21: the cross-reference matrix mapping the five M1-023 findings to acceptance items is prose, not a runnable check; it does not add a testable commitment beyond items 1–18."
  blockers: []
escalations:
  - date: 2026-05-15
    reason: premise-fail
    reviewer_verdict_excerpt: "N/A — developer-surfaced unsatisfiable acceptance during implementation. Item 2 asserts `grep -cE '<artifactId>' infochat-ssrf/pom.xml is exactly 2`, but the pom needs a third `<artifactId>` for junit-jupiter (test-scope) so acceptance item 19 (`mvn -B -pl infochat-ssrf test exits 0`) can pass. The DoD's stated goal (NO external RUNTIME deps; no quarkus-*, Apache HttpClient, or Netty) IS met — JUnit is test-scope, not runtime — but the count grep doesn't distinguish scopes. Clarity-WARN on 2026-05-15 predicted this exact unsatisfiability."
revisions:
  - date: 2026-05-15
    reason: premise-fail-refine
    refined_field: acceptance[2]
    snapshot_before: |
      "infochat-ssrf/pom.xml exists, declares `<parent>infochat-parent</parent>`, packaging jar, and adds NO external runtime dependencies beyond the JDK (no quarkus-* deps, no Apache HttpClient, no Netty — the SSRF wrapper uses java.net.http only) — grep -E '<artifactId>infochat-ssrf</artifactId>' infochat-ssrf/pom.xml returns at least one match AND grep -cE '<artifactId>' infochat-ssrf/pom.xml is exactly 2 (the module's own artifactId + the parent's)"
reviews:
  - round: 1
    date: 2026-05-15
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 1253
      removed: 52
files_budget: 12
files_scope:
  - pom.xml
  - infochat-ssrf/pom.xml
  - infochat-ssrf/src/main/java/io/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/main/java/io/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/main/java/io/infochat/ssrf/UrlRedactor.java
  - infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java
  - infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java
  - infochat-ssrf/src/test/java/io/infochat/ssrf/UrlRedactorTest.java
  - infochat-collector/pom.xml
  - infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFetcher.java
  - infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFeedParser.java
  - infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFetcherTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any Provider-side /add-source URL-validation HEAD/GET probe (commands.md §Source management — call sites land with the Provider command implementation in a later ticket; this ticket lands the shared module + the Collector-side RssFetcher caller only)
  - any WebSocket (ws/wss) wrapping (`SsrfGuardedWebSocket` / `StreamSource` integration is the NostrStreamSource ticket's territory — that ticket consumes the same `IpBlocklist` policy class authored here, but its WebSocket transport wrapper is its own concern)
  - any FetchScheduler / @Scheduled wiring / per-tick cadence selection (T1-C territory at @Priority(400) — the FetchScheduler INSTANTIATES the hardened Fetcher and calls fetch(); this ticket only hardens the Fetcher class M1-023 authored)
  - any outbox sink, RAW-row INSERT, post-table write, OutboxRehydrator, LISTEN/NOTIFY new_post, provider_state high-water-mark, or NewPostReconciler wiring (T1-C)
  - any Stage 1 HTML sanitization, NFKC normalization, regex redaction, or canonical-body UID hashing (T1-D — this Fetcher continues to pass the raw HTML body through; the Stage 1 sanitizer downstream of the outbox strips HTML and applies the redaction catalogue)
  - any Bluesky / Nitter / Reddit / YouTube / Odysee fetcher implementation (each binds to the Fetcher SPI but is its own Tier-3 T3-B ticket and will route through `SsrfGuardedHttpClient` as a separate per-fetcher PR)
  - any source-row UPDATE for last_fetch_at / last_success_at / consecutive_failures (D42's per-source failure-counter model is the FetchScheduler's responsibility; this ticket's hardened Fetcher remains stateless between ticks per docs/spec/architecture.md §Ingest SPIs)
  - any pagination cap counter, admin-notification on saturation, or single-tick page-walk (RSS has no pagination; this ticket's item-count cap is a per-call safety bound against malformed feeds, NOT a pagination signal)
  - any retry / backoff / Retry-After honoring / per-source politeness window (microprofile-faulttolerance integration lives with the FetchScheduler at the per-tick boundary, not in the Fetcher or the SSRF wrapper)
  - any change to the io.infochat.core.ingest.Fetcher SPI or NormalizedPost record shape (the SPI signature `List<NormalizedPost> fetch(long sourceId, String identifier)` is unchanged — only RssFetcher's INTERNALS change to route through the SSRF wrapper)
  - any change to V1..V8 Flyway migrations (migration_touch: false; this ticket is impl-only)
  - any LLM tool surface, tool registry, prompt-injection sanitizer, or LLM-output redactor (orthogonal — security.md §Prompt-injection defenses lives in a separate Provider-side ticket)
  - any audit_log write, audit_log_view, or AuditLogger code path (security.md §Secrets handling — the SSRF wrapper's policy denials are logged via the standard JUL logger with the URL already pre-redacted by UrlRedactor, not via audit_log which is reserved for user/admin intent records)
acceptance:
  - "The repo root pom.xml lists `infochat-ssrf` in its `<modules>` block — grep -E '<module>infochat-ssrf</module>' pom.xml returns at least one match (Finding 1 — module exists)"
  - "infochat-ssrf/pom.xml exists, declares `<parent>infochat-parent</parent>`, packaging jar, and adds NO external RUNTIME dependencies beyond the JDK (test-scope JUnit is permitted; the SSRF wrapper uses java.net.http only) — grep -E '<artifactId>infochat-ssrf</artifactId>' infochat-ssrf/pom.xml returns at least one match AND grep -cE '<artifactId>' infochat-ssrf/pom.xml returns exactly 3 (parent + self + junit-jupiter) AND grep -nE '<groupId>(io\\.quarkus|org\\.apache\\.httpcomponents|io\\.netty)' infochat-ssrf/pom.xml returns zero matches (no quarkus, Apache HttpClient, or Netty runtime deps)"
  - "infochat-ssrf/src/main/java/io/infochat/ssrf/SsrfGuardedHttpClient.java exists, declares `public final class SsrfGuardedHttpClient`, exposes a public `HttpResponse<byte[]> get(URI uri)` method (or equivalent named method matching the per-call shape), and uses `java.net.http.HttpClient` internally — grep -E 'public final class SsrfGuardedHttpClient' returns at least one match AND grep -E 'java\\.net\\.http\\.HttpClient' returns at least one match"
  - "SsrfGuardedHttpClient rejects any URI with a non-allowlisted scheme; the allowlist is `http` and `https` only (ws/wss are carved out for the future StreamSource ticket per out_of_scope). Rejected schemes raise an `SsrfPolicyException` (nested class) whose message starts with the literal substring `scheme not allowed` — grep -E 'scheme not allowed' SsrfGuardedHttpClient.java returns at least one match"
  - "SsrfGuardedHttpClient rejects any URI whose authority component carries a userinfo segment (matches `://[^/@]+@`). Rejected URIs raise `SsrfPolicyException` with message starting with the literal substring `userinfo segment not allowed` (Finding 4 — credentials-in-URL leak) — grep -E 'userinfo segment not allowed' SsrfGuardedHttpClient.java returns at least one match"
  - "IpBlocklist.java exists in `io.infochat.ssrf` package and exposes a public method `boolean isBlocked(InetAddress addr)` that returns true for: IPv4 loopback (127.0.0.0/8), IPv4 link-local (169.254.0.0/16, including the `169.254.169.254` cloud-metadata address by virtue of being in this range), IPv4 multicast (224.0.0.0/4), IPv4 RFC1918 private (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16), IPv4 CGNAT (100.64.0.0/10), IPv6 loopback (::1), IPv6 link-local (fe80::/10), IPv6 unique-local (fc00::/7), IPv6 multicast (ff00::/8), and the IPv6 IPv4-mapped form of every blocked IPv4 range — grep -cE '169\\.254\\.|127\\.|10\\.|172\\.|192\\.168\\.|100\\.64\\.|::1|fe80|fc00|ff00' IpBlocklist.java returns at least 10 distinct range tokens"
  - "SsrfGuardedHttpClient performs a pre-call DNS resolution and rejects the request when ANY resolved address satisfies `IpBlocklist.isBlocked`. Rejected requests raise `SsrfPolicyException` with message starting with the literal substring `blocked IP` — grep -E 'blocked IP' SsrfGuardedHttpClient.java returns at least one match"
  - "SsrfGuardedHttpClient enforces a redirect cap (configurable; default 3); when a redirect target is encountered, the wrapper re-resolves DNS for the new URI and re-applies the full allowlist + IpBlocklist check (TOCTOU defense per security.md §SSRF and outbound connections — `DNS is re-resolved after every redirect`). Exceeding the redirect cap raises `SsrfPolicyException` with message starting with the literal substring `redirect cap exceeded` — grep -E 'redirect cap exceeded' SsrfGuardedHttpClient.java returns at least one match AND grep -cE 'isBlocked|InetAddress\\.getAllByName' SsrfGuardedHttpClient.java returns at least 2 matches (one for the initial resolution, one for the per-redirect re-resolution)"
  - "SsrfGuardedHttpClient enforces a body-size cap (configurable; default 10 MiB) by reading the response body through a length-bounded stream — bytes exceeding the cap cause an immediate cancel + raise an `SsrfPolicyException` with message starting with the literal substring `response body exceeded` (Finding 2 — unbounded byte[]) — grep -E 'response body exceeded' SsrfGuardedHttpClient.java returns at least one match AND grep -nE 'BodyHandlers\\.ofByteArray\\s*\\(\\s*\\)' SsrfGuardedHttpClient.java returns zero matches (the unbounded byte[] handler MUST be replaced with a bounded reader)"
  - "SsrfGuardedHttpClient enforces non-zero connect-timeout AND read-timeout; constructor or builder rejects a zero/null timeout with `IllegalArgumentException` carrying the literal substring `timeout must be configured` (security.md §SSRF: `an unset timeout is a configuration error`) — grep -E 'timeout must be configured' SsrfGuardedHttpClient.java returns at least one match"
  - "UrlRedactor.java exists in `io.infochat.ssrf` package and exposes `public static String redact(String url)` that strips userinfo segments AND query strings from a URL, returning `<scheme>://<host>[:<port>]<path>?[REDACTED]` for URLs with a query and `<scheme>://<host>[:<port>]<path>` for those without — UrlRedactorTest asserts: (a) `https://user:secret@host/path` → `https://host/path`; (b) `https://host/p?token=abc` → `https://host/p?[REDACTED]`; (c) `https://user:secret@host/p?token=abc` → `https://host/p?[REDACTED]`; (d) malformed URL passes through as the literal string `<malformed-url>` (never raise from a logging helper)"
  - "RssFetcher.java is modified to construct an `SsrfGuardedHttpClient` instead of a raw `java.net.http.HttpClient` AND to call `client.get(uri)` instead of `httpClient.send(...)`; the SSRF GATE TODO javadoc block is removed (the gate is now wired) — grep -E 'SsrfGuardedHttpClient' RssFetcher.java returns at least one match AND grep -E 'SSRF GATE TODO' RssFetcher.java returns zero matches AND grep -E 'java\\.net\\.http\\.HttpClient(?![A-Za-z])' RssFetcher.java returns zero matches (the raw HttpClient import is removed; only SsrfGuardedHttpClient is imported)"
  - "RssFetcher.java exception messages are scrubbed: every `RssFetchException` message that previously interpolated the raw `identifier` now interpolates `UrlRedactor.redact(identifier)` (Finding 4 — credentials in exception messages) — grep -E 'UrlRedactor\\.redact|UrlRedactor\\.' RssFetcher.java returns at least one match in each of: the InterruptedException branch, the IOException branch, the non-2xx-status branch (≥3 matches total — IDENTICAL-AGGREGATE: three branches each redacting the same identifier variable)"
  - "RssFeedParser.java caps the number of returned `NormalizedPost` rows at a configurable maximum (default 1000) per parse() invocation; parses exceeding the cap raise `RssFeedParseException` with message starting with the literal substring `feed item count exceeded` (Finding 5 — unbounded item count) — grep -E 'feed item count exceeded' RssFeedParser.java returns at least one match"
  - "IpBlocklistTest.java exists, is plain JUnit 5 (no @QuarkusTest), and asserts `isBlocked` returns true for: 127.0.0.1, 169.254.169.254 (AWS cloud-metadata), 10.0.0.1, 172.16.0.1, 192.168.1.1, 100.64.0.1 (CGNAT), and ::1, AND returns false for: 8.8.8.8, 1.1.1.1, 2606:4700:4700::1111. Each assertion is a distinct test method — grep -cE '@Test' IpBlocklistTest.java returns at least 10 matches (IDENTICAL-AGGREGATE: one @Test per per-IP assertion of the same shape)"
  - "SsrfGuardedHttpClientTest.java exists, is plain JUnit 5, and asserts (each as a distinct @Test method): (a) `http://169.254.169.254/...` is rejected with `blocked IP`; (b) `ftp://example.com/` is rejected with `scheme not allowed`; (c) `https://user:pw@example.com/` is rejected with `userinfo segment not allowed`; (d) a 200 response from a localhost in-process HttpServer fixture (`com.sun.net.httpserver.HttpServer` bound to 127.0.0.1:ephemeral) is **also** blocked because 127.0.0.1 is in the blocklist (the spec's loopback rule applies to all callers; the test fixture must use `InetSocketAddress(\"0.0.0.0\", 0)` AND override the IpBlocklist with a test-only allowlist that permits 127.0.0.1 — making the test-mode carve-out an EXPLICIT API surface, not an implicit hole); (e) a response body exceeding the cap raises `response body exceeded`. — grep -cE '@Test' SsrfGuardedHttpClientTest.java returns at least 5 matches"
  - "UrlRedactorTest.java exists, is plain JUnit 5, with at least 4 @Test methods covering each scenario enumerated in the UrlRedactor acceptance item above — grep -cE '@Test' UrlRedactorTest.java returns at least 4 matches"
  - "RssFetcherTest.java is updated: the existing in-process HttpServer fixture binds to a non-loopback test-mode policy (per the SsrfGuardedHttpClientTest carve-out), the existing 3 test methods continue to pass via the test-mode IpBlocklist override, AND two new @Test methods are added: (a) a fetch against an identifier with an embedded credential like `https://user:secret@127.0.0.1:<port>/feed.xml` raises an exception whose message contains the literal substring `userinfo segment not allowed` AND does NOT contain the substring `secret` (Finding 4 verification); (b) a fetch against a server that returns a body exceeding the cap raises `response body exceeded` — grep -cE '@Test' RssFetcherTest.java returns at least 5 matches"
  - "mvn -B -pl infochat-ssrf test exits 0; new test classes execute and pass"
  - "mvn -B clean verify from the repo root exits 0; the M1-001, M1-003, M1-007, M1-007a/b/c, M1-008, M1-008a/b/c, M1-009, M1-017, M1-022, and M1-023 tests continue to pass alongside the new infochat-ssrf module"
  - "The five M1-023 redteam findings are addressed: Finding 1 (no SSRF gate → INJECTION/critical) by acceptance items 1–10 + 12; Finding 2 (unbounded response body → DOS/high) by acceptance item 9; Finding 3 (no scheme allowlist + userinfo → INJECTION/high) by acceptance items 4 + 5; Finding 4 (URL credentials leak in exception → INFO-LEAK/medium) by acceptance items 5 + 11 + 13 + 17 (the userinfo gate at the wrapper makes the exception-message leak unreachable, AND the per-branch redactor closes the residual logging surface); Finding 5 (unbounded item count → DOS/medium) by acceptance item 14"
test_plan:
  adds:
    - infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java (per-policy assertions over the wrapper end-to-end; uses `com.sun.net.httpserver.HttpServer` for the happy-path fixture under a test-mode IpBlocklist override)
    - infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java (per-IP-range assertions; one @Test per blocked / allowed address)
    - infochat-ssrf/src/test/java/io/infochat/ssrf/UrlRedactorTest.java (per-shape assertions over the redaction helper)
  modifies:
    - infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFetcherTest.java — the in-process HttpServer fixture migrates to the test-mode IpBlocklist override and two new @Test methods are added for the M1-023 findings (Finding 4 userinfo rejection + Finding 2 body-size cap). The existing three test methods continue to pass after the migration; their assertions are unchanged in substance.
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a — SPI consumed unchanged)
    - infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFeedParserTest.java (M1-023 — parser fixtures unchanged; the item-count cap default of 1000 leaves all 3-item and 2-item fixture tests passing)
    - infochat-collector/src/test/java/io/infochat/collector/bootstrap/BootstrapLoaderIT.java (M1-022)
    - all M1-008a / M1-008b / M1-008c / M1-009 / M1-017 *Test.java and *IT.java classes
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
  - docs/spec/security.md §Secrets handling
  - docs/spec/architecture.md §Ingest SPIs
  - docs/design/01-architecture.md §1.2 Module layout (Maven)
  - docs/design/01-architecture.md §1.6 Concurrency and rate limiting
decision_refs:
  - D20
  - D38
  - D42
---

# M1-024: infochat-ssrf module + RssFetcher hardening (M1-023 remediation)

## Context

M1-023 landed `RssFetcher` with a documented SSRF carve-out: the
Fetcher class exists and is unit-tested against an in-process HTTP
server, but its outbound HTTP path is **NOT** routed through the
shared `infochat-ssrf` module that `docs/spec/security.md` §SSRF
and outbound connections mandates. The class-level javadoc on
`RssFetcher.java` flags the gap with a `SSRF GATE TODO` comment and
the ticket carries `security_relevant: true` so the milestone-end
`/redteam` sweep finds it.

The `/redteam M1-023` audit (2026-05-14) confirmed the gap and
surfaced four additional findings on the same code path that were
**not** part of the original carve-out:

1. **INJECTION / critical** — no SSRF gate (the documented carve-out).
2. **DOS / high** — `BodyHandlers.ofByteArray()` with no size limit; a
   hostile feed serving a 10 GB response exhausts Collector heap.
3. **INJECTION / high** — no scheme allowlist, no userinfo rejection
   on the identifier URI; `URI.create(identifier)` accepts any URI
   the JDK accepts.
4. **INFO-LEAK / medium** — `RssFetchException` interpolates the
   full raw identifier URL into its message; embedded credentials
   (`https://user:token@host/`) reach exception traces.
5. **DOS / medium** — `RssFeedParser` walks `<item>` / `<entry>` with
   no cap on the returned post count; a feed with a billion items
   exhausts heap before Stage 1 ever runs.

This ticket is the **single remediation ticket** for all five
findings, rolled together per the operator's Option A decision: the
SSRF wrapper that addresses Finding 1 is the natural carrier for
the body-size cap (Finding 2), the scheme allowlist + userinfo
gate (Finding 3), and a URL-redaction helper that closes the
exception-message leak (Finding 4). Finding 5 is a parser-side
fix that lives alongside the Fetcher change since both files are
already in scope.

M1-023's commit (`844cb65`) is **immutable** per `CLAUDE.md` §M1
workflow — "Never amend a passed commit." This ticket lands the
remediation as a fresh `M1-024:` commit and frontmatter
`remediates: M1-023` so the lineage is mechanically traceable.

## Definition of Done

### New module: `infochat-ssrf`

- A new Maven module under `infochat-ssrf/` with:
  - `infochat-ssrf/pom.xml` declaring parent `infochat-parent`,
    packaging `jar`. **No new external runtime dependencies** —
    the SSRF wrapper uses only the JDK (`java.net.http.HttpClient`,
    `java.net.InetAddress`, `javax.xml.stream` is NOT pulled in
    here; this module is HTTP-only).
  - The root `pom.xml` lists `<module>infochat-ssrf</module>` in
    its `<modules>` block, ordered before `infochat-collector` and
    `infochat-provider` so reactor builds resolve it as a
    dependency.

### `io.infochat.ssrf.IpBlocklist`

A pure-data range matcher with one public method:

```java
public boolean isBlocked(InetAddress addr)
```

The blocklist covers the spec-mandated ranges (security.md
§SSRF: private, loopback, link-local, multicast, CGNAT, and
cloud-metadata) plus their IPv6 counterparts:

- IPv4: `127.0.0.0/8`, `169.254.0.0/16`, `224.0.0.0/4`,
  `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
  `100.64.0.0/10`.
- IPv6: `::1` (loopback), `fe80::/10` (link-local), `fc00::/7`
  (unique-local), `ff00::/8` (multicast).
- IPv6-mapped IPv4 (`::ffff:0:0/96`): defer to the underlying IPv4
  check so a mapped form of any blocked v4 range is also blocked.

The class is `final`, stateless, and thread-safe. Range data is
compile-time constant.

### `io.infochat.ssrf.UrlRedactor`

A pure-string redaction helper with one public static method:

```java
public static String redact(String url)
```

Returns a URL with userinfo and query string stripped, suitable
for inclusion in exception messages and operational logs. Malformed
URLs return the literal string `<malformed-url>` rather than
raising — this is a logging helper and must be infallible.

### `io.infochat.ssrf.SsrfGuardedHttpClient`

The main wrapper. One public method on the per-call surface:

```java
public HttpResponse<byte[]> get(URI uri)
```

Internal sequence:

1. **Scheme check.** Reject any URI whose scheme is not `http` or
   `https`. Raise `SsrfPolicyException("scheme not allowed: <scheme>")`.
2. **Userinfo check.** Reject any URI whose authority carries a
   userinfo segment (matches the `://[^/@]+@` shape). Raise
   `SsrfPolicyException("userinfo segment not allowed")` — the
   redactor exists for logging, not for credential laundering at
   intake.
3. **DNS resolve.** Resolve `uri.getHost()` to every available
   `InetAddress`. If ANY resolves to a blocked address, raise
   `SsrfPolicyException("blocked IP: " + addr.getHostAddress())`.
4. **Configure HttpClient.** Build a per-call `HttpClient` with
   `followRedirects = NEVER` (the wrapper handles redirects itself
   so each hop re-runs steps 1–3 — JDK's `Redirect.NORMAL` would
   follow without re-checking).
5. **Send.** Issue the GET with non-zero connect + request timeouts
   (configurable; defaults profile-driven, low-but-reasonable). Use a
   `BodyHandler` that reads through a `BufferedInputStream` with a
   length-bounded `read` loop — at the configurable body-size cap
   (default 10 MiB), cancel the response and raise
   `SsrfPolicyException("response body exceeded <N> bytes")`.
6. **Redirect handling.** On 3xx with a `Location` header, parse the
   target URI and recurse to step 1. Increment a redirect counter;
   on `counter >= redirectCap` (default 3), raise
   `SsrfPolicyException("redirect cap exceeded")`.
7. **Return** the `HttpResponse<byte[]>` on the first 2xx.

Constructor or builder REJECTS a zero or null timeout, body-size cap,
or redirect cap with `IllegalArgumentException("timeout must be
configured")` (or the equivalent for the other knobs). Spec:
"an unset timeout is a configuration error."

The class has one nested exception:

```java
public static final class SsrfPolicyException extends RuntimeException
```

Raised on any policy violation. The Fetcher SPI does not declare
checked exceptions; `SsrfPolicyException extends RuntimeException`
to fit the SPI signature.

### `RssFetcher.java` modifications

- Replace the raw `java.net.http.HttpClient` with
  `SsrfGuardedHttpClient` (one wrapper instance per `RssFetcher`,
  same shared-lifetime model — the wrapper is thread-safe).
- Replace `httpClient.send(...)` with `client.get(URI.create(identifier))`.
- Remove the `SSRF GATE TODO` javadoc block (the gate is now wired).
- Replace every `RssFetchException` constructor that interpolates
  `identifier` with `UrlRedactor.redact(identifier)`. Three branches:
  `InterruptedException`, `IOException`, non-2xx status.
- `SsrfPolicyException` is propagated as-is (RuntimeException; the
  FetchScheduler in T1-C catches both `SsrfPolicyException` and
  `RssFetchException` via D42's per-source failure-counter handler).
- The class continues to capture `fetchedAt = Instant.now()` BEFORE
  the HTTP call; the partition-key invariant from M1-023 is
  preserved.

### `RssFeedParser.java` modifications

Add a per-parse item-count cap (configurable; default 1000). When
the parser would emit the `(cap + 1)`th `NormalizedPost`, raise
`RssFeedParseException("feed item count exceeded <cap>")`. The cap
applies to both RSS `<item>` and Atom `<entry>` walks. The cap is
checked AFTER each successful per-item parse — a feed with exactly
`cap` items succeeds; a feed with `cap + 1` raises on the cap+1th
item.

### Tests

- `IpBlocklistTest` — plain JUnit 5; one `@Test` per blocked / allowed
  address per acceptance item 15.
- `UrlRedactorTest` — plain JUnit 5; per-shape assertions per
  acceptance item 11.
- `SsrfGuardedHttpClientTest` — plain JUnit 5; uses
  `com.sun.net.httpserver.HttpServer` for the happy-path fixture.
  Because the test server binds to localhost and the IP blocklist
  blocks 127.0.0.0/8, the test must supply a **test-mode
  IpBlocklist override** at construction time — an explicit
  package-private constructor parameter (or static factory method)
  that accepts a custom `IpBlocklist` instance permitting 127.0.0.1
  for tests only. The override is a deliberate API surface, NOT a
  silent global flag — production callers always use the default
  constructor which uses the strict blocklist.
- `RssFetcherTest` updates per acceptance item 17.

### Module build

`mvn -B clean verify` from the repo root exits 0. All M1-023 tests
continue to pass under the new wrapper (with the test-mode override).

## Implementation notes

- **Library choice: JDK-native `java.net.http.HttpClient` + manual
  redirect handling.** The spec mandates DNS re-resolution per
  redirect hop, which `Redirect.NORMAL` does not provide visibility
  into. Manual redirect handling (Redirect.NEVER + wrapper loop) is
  the only way to enforce TOCTOU defense without adding a third-party
  client. Apache HttpClient / OkHttp would require pom changes and
  still need a per-redirect callback to re-resolve DNS — at that
  point JDK-native is simpler.
- **Body-size cap implementation.** `HttpResponse.BodyHandlers.ofInputStream()`
  combined with a manual length-bounded read loop is the cleanest
  way to cap bytes without buffering the whole response. The handler
  yields an `InputStream`; the wrapper reads up to `cap + 1` bytes
  into a `ByteArrayOutputStream` and raises on overflow. The HTTP
  connection is closed on overflow via `inputStream.close()` which
  closes the underlying socket.
- **DNS re-resolution.** Use `InetAddress.getAllByName(host)` on each
  hop. JDK's resolver respects `/etc/hosts` and the system resolver
  cache; the cache TTL is short enough (seconds, configurable via
  `networkaddress.cache.ttl`) that DNS-rebind across a multi-second
  redirect chain is plausibly detected. A true defense would short-circuit
  the JDK cache, but for v1 the redirect cap (default 3) bounds the
  attacker's window.
- **Why no Provider-side wiring in this ticket.** The Provider's
  `/add-source` command does a HEAD/GET probe per
  `commands.md` §Source management. That call site is wired by the
  Provider command implementation ticket, NOT here. The shared
  module exists, and the Provider's call site is a one-line
  `new SsrfGuardedHttpClient(...).get(uri)` in that later ticket.
- **IPv6-mapped IPv4.** A v4 address can be expressed as
  `::ffff:127.0.0.1`. The blocklist's IPv6 check first normalizes
  via `addr.getAddress().length` — 4-byte arrays are pure IPv4;
  16-byte arrays under the `::ffff:0:0/96` prefix are mapped IPv4
  (delegate to the IPv4 check). This is the path attackers use to
  bypass naive v6 allowlists.
- **Item-count cap default of 1000.** Roughly an order of magnitude
  above what a normal feed produces (most RSS feeds publish 10–50
  items at a time; some news aggregators reach 500). 1000 leaves
  ample headroom for legitimate use while still bounding the
  worst case to a manageable allocation.
- **The IpBlocklist override is an API surface, not a flag.** The
  test-mode constructor takes an `IpBlocklist` parameter; production
  always uses the no-arg constructor which uses the strict singleton.
  This makes the carve-out impossible to accidentally enable in
  production — you must construct an explicit non-default
  `IpBlocklist` and pass it. Mirrors the
  `IS_SUPPORTING_EXTERNAL_ENTITIES=false` discipline from M1-023's
  `RssFeedParser`: defense is a property of the construction
  surface, not a flag check.

## Big-picture notes

- **Three more findings live alongside the SSRF gate that the
  original M1-023 ticket did not authorize.** The operator chose
  Option A — single remediation ticket — over Option B (split into
  two tickets) so all five findings are remediated in one shot
  before T1-C wires the Fetcher to a real scheduler.
- **`remediates: M1-023` is load-bearing.** The clarity reviewer's
  FORWARD-REFERENCE-CHECK validates that `remediates:` resolves to
  an existing ticket. M1-023 is done at the time M1-024 starts; the
  link makes the M1-024 commit cite the M1-023 commit's SHA in its
  body so a future operator can trace the security-fix chain.
- **The Provider-side `/add-source` SSRF probe is a separate
  ticket.** The shared module authored here is consumed by the
  Provider command in the later ticket. Pulling the Provider
  changes into this ticket would breach the impl-only scope and
  inflate the budget — the Provider command path also needs the
  HEAD-shaped wrapper that this ticket does NOT add.
- **WebSocket SsrfGuardedWebSocket is the NostrStreamSource
  ticket's territory.** The IpBlocklist + UrlRedactor classes
  authored here are consumed by that ticket; the WebSocket
  transport wrapper is its own implementation problem. This ticket
  is HTTP-only.
- **Round cap 3 is justified.** complexity: high (new module,
  manual redirect loop, IPv6-mapped IPv4 corner case, item-count
  cap retrofit) AND risk: high (security-critical SSRF defense
  whose failure mode is a cloud-metadata-credentials leak). Per
  the M1 workflow, `complexity: high` OR `risk: high` may opt
  into round_cap 3; both apply here.

## Out-of-scope expansion

- **Provider-side `/add-source` URL-validation probe.** Separate
  later ticket; consumes the shared module authored here.
- **WebSocket / ws / wss wrapping.** NostrStreamSource ticket.
- **FetchScheduler / per-tick wiring / @Scheduled.** T1-C.
- **Outbox sink / RAW-row INSERT / post-table write.** T1-C.
- **OutboxRehydrator / LISTEN-NOTIFY new_post / provider_state.** T1-C.
- **Stage 1 sanitization / NFKC / regex redaction.** T1-D.
- **Bluesky / Nitter / Reddit / YouTube / Odysee fetchers.** Each
  Tier-3 T3-B ticket consumes `SsrfGuardedHttpClient` as a
  one-line construction; the per-fetcher impls land separately.
- **source-row UPDATE for failure counters.** FetchScheduler's
  responsibility per D42; this Fetcher remains stateless.
- **Pagination cap counter / admin-notification on saturation.**
  RSS has no pagination per docs/design/01-architecture.md §1.6.
  The item-count cap added here is a per-call safety bound, not
  a pagination signal.
- **Retry / backoff / Retry-After.** FetchScheduler at the per-tick
  boundary.
- **Fetcher SPI / NormalizedPost shape change.** The SPI signature
  is unchanged; M1-024 only changes RssFetcher's internals.
- **V1..V8 Flyway migrations.** `migration_touch: false`.
- **LLM tool surface, prompt-injection sanitizer, LLM-output
  redactor.** Orthogonal Provider-side surface.
- **audit_log writes.** SSRF policy denials are logged via JUL
  with URLs pre-redacted by UrlRedactor; the audit_log table is
  reserved for user/admin intent records, not transport-layer
  policy denials.

## Authorized test changes

- `infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFetcherTest.java`
  — the existing 3 `@Test` methods are preserved in substance, but
  the in-process `HttpServer` fixture now constructs the
  `RssFetcher` with a test-mode `IpBlocklist` that permits 127.0.0.1
  (the wrapper's default blocks it, which would break the localhost
  fixture). The test-mode override is a deliberate API surface
  authored by this ticket; the migration is a one-line change to the
  fixture-setup helper. Two new `@Test` methods are added:
  `fetchRejectsIdentifierWithEmbeddedCredentials` (Finding 4) and
  `fetchRaisesOnOversizeResponseBody` (Finding 2). All three
  pre-existing assertions remain green.

## Alternatives considered

- **Split into two tickets (Option B).** Considered. Rejected per
  the operator's Option A decision — the SSRF wrapper that fixes
  Finding 1 is the natural carrier for Findings 2 + 3 (body-size
  cap + scheme allowlist), and Finding 4 (URL-redactor) is a
  helper class the wrapper already needs for its own policy-denial
  messages. Finding 5 (item-count cap) is parser-side and could in
  principle live separately, but its file is already in scope via
  the Fetcher-Parser pair and shipping it in the same commit
  reduces the testing matrix.
- **Use Apache HttpClient (`org.apache.httpcomponents.client5`) or
  OkHttp.** Rejected: would require adding the dep to the BOM AND
  to `infochat-ssrf/pom.xml`, then still need per-redirect callbacks
  to re-resolve DNS. The JDK's `HttpClient` with
  `Redirect.NEVER` + manual loop is simpler.
- **Use `Redirect.NORMAL` and rely on the JDK to re-resolve DNS.**
  Rejected: the JDK does NOT re-resolve DNS on follow; it caches
  the initial resolution. A DNS-rebind attacker can pivot from a
  public IP at the first GET to a private IP on the redirect target
  silently. The spec explicitly mandates "DNS is re-resolved after
  every redirect" — we must control redirect handling to enforce
  this.
- **Use a global static flag like `SsrfGuard.disableForTests = true`
  in test setup.** Rejected: silent global flags are exactly the
  pattern that smuggles defense gaps into production. A
  constructor-parameter override is impossible to enable accidentally
  — you have to construct a non-default `IpBlocklist` and pass it.
- **Defer the parser item-count cap to a v2 ticket.** Considered.
  Rejected: the parser file is already in scope via the Fetcher
  pair (M1-024 modifies RssFetcher.java's HTTP-client construction,
  and the body-size cap on the wrapper already bounds the worst
  attack — but a billion small `<item>` elements in a 10 MiB feed
  still exhausts heap during NormalizedPost allocation). The fix
  is one method + one config knob; shipping it with the other four
  findings keeps the remediation atomic.
- **Skip the `UrlRedactor` and let exception messages carry the raw
  URL minus the userinfo (since the wrapper rejects userinfo
  pre-fetch).** Considered. Rejected: the userinfo gate at the
  wrapper closes the worst case, but query-string parameters
  (`?token=abc`) are still a credential-carrying surface, and the
  Fetcher's non-2xx exception still interpolates the full URL. The
  redactor closes both surfaces uniformly. It is also a logging
  helper the Provider's later `/add-source` audit logging will
  consume.

## Implementation outline (M1-024, generated by Plan subagent on 2026-05-15)

### Files to touch (12 of 12)
- modify: `pom.xml` — insert `<module>infochat-ssrf</module>` into `<modules>` BEFORE `infochat-collector` and `infochat-provider` (reactor ordering so downstream depends on built artifact). Also add an `infochat-ssrf` entry under `<dependencyManagement>` to preserve the M1-001 "version-less downstream POMs" invariant (matches the `infochat-core` precedent).
- create: `infochat-ssrf/pom.xml` — new `<parent>infochat-parent</parent>`, `<artifactId>infochat-ssrf</artifactId>`, `<packaging>jar</packaging>`. NO Quarkus extensions (mirror the `infochat-core` plain-library precedent). Test deps: junit-jupiter (BOM-managed). Maven-failsafe-plugin only if any `*IT.java` is needed — this ticket adds only `*Test.java`, so failsafe is omitted.
- create: `infochat-ssrf/src/main/java/io/infochat/ssrf/IpBlocklist.java` — `final` class, no state; method `boolean isBlocked(InetAddress addr)`. Internal helpers private for IPv4 CIDR matches; v6-mapped-v4 (`addr.getAddress().length == 16` with leading bytes per the `::ffff:0:0/96` prefix) delegates to v4 check. Thread-safe by virtue of statelessness.
- create: `infochat-ssrf/src/main/java/io/infochat/ssrf/UrlRedactor.java` — `final` class, private constructor, single `public static String redact(String url)`. Strips userinfo + query; on `URISyntaxException`/`NullPointerException` returns the literal `<malformed-url>` (infallible per DoD).
- create: `infochat-ssrf/src/main/java/io/infochat/ssrf/SsrfGuardedHttpClient.java` — production-default no-arg constructor wires the strict `IpBlocklist` singleton + default timeouts (configurable values declared as final fields). Package-private constructor accepting `(IpBlocklist, Duration connect, Duration request, long bodyCap, int redirectCap)` for test override. Constructor rejects any zero/null value with `IllegalArgumentException("timeout must be configured")`. `HttpResponse<byte[]> get(URI uri)` implements the 7-step internal sequence. Nested `public static final class SsrfPolicyException extends RuntimeException` with `(String)` and `(String, Throwable)` constructors. Body read via `BodyHandlers.ofInputStream()` + manual bounded read into `ByteArrayOutputStream`; on overflow close stream + raise. Manual redirect loop: counter int local, increment on 3xx, re-enter step 1 with parsed `Location` URI.
- modify: `infochat-collector/pom.xml` — add `<dependency>infochat-ssrf</dependency>` (version-less; coordinates managed by parent).
- modify: `infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFetcher.java` — replace the `HttpClient httpClient` field with `SsrfGuardedHttpClient client`; remove `HttpRequest`/`HttpResponse` imports no longer used; replace `httpClient.send(...)` with `client.get(URI.create(identifier))`; remove the `SSRF GATE TODO` javadoc block; in each of the three exception sites (`InterruptedException`, `IOException`, non-2xx) replace `identifier` interpolation with `UrlRedactor.redact(identifier)`. Preserve `fetchedAt = Instant.now()` captured BEFORE the call.
- modify: `infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFeedParser.java` — add a `MAX_ITEMS = 1000` private constant; counter local to `parseRss` / `parseAtom`; on each successful `parseRssItem` / `parseAtomEntry` increment-then-check; if `count > MAX_ITEMS` raise `RssFeedParseException("feed item count exceeded " + MAX_ITEMS)`. Apply identically to both walks.

### Tests
- add: `infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java` — one `@Test` per CIDR class: blocks `127.0.0.1`, `127.255.255.254` (loopback boundary), `169.254.169.254` (link-local + metadata), `10.0.0.1`, `172.16.0.1`, `172.31.255.254`, `192.168.1.1`, `100.64.0.1` (CGNAT), `224.0.0.1` (multicast), `::1`, `fe80::1`, `fc00::1`, `ff02::1`, `::ffff:127.0.0.1` (mapped-v4 delegation); allows `8.8.8.8`, `1.1.1.1`, `2001:4860:4860::8888`. Covers acceptance item 15.
- add: `infochat-ssrf/src/test/java/io/infochat/ssrf/UrlRedactorTest.java` — strips `user:pass@` userinfo; strips `?query=secret`; preserves scheme + host + path; malformed input (`"not a url"`, `null`, embedded control chars) returns `<malformed-url>`. Covers acceptance item 17.
- add: `infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java` — uses `com.sun.net.httpserver.HttpServer` on 127.0.0.1 ephemeral port. Must use a test-mode `IpBlocklist` that permits 127.0.0.1; constructed via the package-private constructor. Test cases: happy-path 2xx returns body; non-http(s) scheme raises `SsrfPolicyException` with `"scheme not allowed"`; userinfo URI raises with `"userinfo segment not allowed"`; strict (production) blocklist on 127.0.0.1 raises with `"blocked IP"`; oversize body raises with `"response body exceeded"`; redirect loop past cap raises with `"redirect cap exceeded"`; redirect to a strict-blocklist IP re-resolves and is rejected hop 2; constructor rejects null `Duration` with `IllegalArgumentException("timeout must be configured")`. Covers acceptance item 16.
- modify: `infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFetcherTest.java` — authorized in ticket body §"Authorized test changes". Three existing methods preserved in substance; their setup migrates to construct `RssFetcher` with a test-mode `SsrfGuardedHttpClient` via constructor-injection seam on `RssFetcher` (preserves production no-arg construction). Add `fetchRejectsIdentifierWithEmbeddedCredentials` (Finding 4) and `fetchRaisesOnOversizeResponseBody` (Finding 2). Covers acceptance item 18.

### Cross-cutting concerns
- **Partition-key invariant**: `fetchedAt` must be captured BEFORE the wrapper call. Replacing `httpClient.send(...)` with `client.get(...)` is one statement; the `Instant.now()` capture above it must remain literally first.
- **Fetcher SPI signature is frozen** (out_of_scope): `fetch(long, String)` returning `List<NormalizedPost>` does not change. The wrapper's exception type (`SsrfPolicyException`) MUST be a `RuntimeException` so it propagates through the SPI without forcing a checked-exception change.
- **Spec scheme allowlist is `http,https,ws,wss`** (security.md §SSRF); this ticket implements only `http`/`https`. The wrapper's scheme check must reject `ws`/`wss` for now AND the exception message must not lie about why — say "scheme not allowed: ws" so the future NostrStreamSource ticket can widen the allowlist without contradicting committed test text.
- **DNS re-resolution per hop** is the TOCTOU defense (security.md §SSRF). `HttpClient.Redirect.NEVER` + manual loop + fresh `InetAddress.getAllByName` per hop is the only correct shape — `Redirect.NORMAL` (currently in RssFetcher) does NOT re-resolve.
- **"Any peer-IP change on stream sockets is a hard close"** is a `StreamSource` commitment, NOT relevant to this ticket's `Fetcher` path. Don't bake socket-migration logic into `SsrfGuardedHttpClient`.
- **No defensive code rule** (CLAUDE.md §Engineering rules): constructor validation IS a system boundary (config parsing), so the IllegalArgumentException on zero/null is rule-compliant. Internal helpers (e.g., the IPv4 CIDR matcher called from `IpBlocklist.isBlocked`) MUST NOT add internal-caller null checks.
- **BOM-managed versions invariant** (M1-001): every new `pom.xml` entry must be version-less; the version pin happens once in parent `dependencyManagement`.
- **D42 failure-counter contract**: `SsrfPolicyException` propagating from `fetch()` lands at the FetchScheduler (out of scope) as an uncategorized failure — same treatment as `RssFetchException`. Do not catch-and-translate inside `RssFetcher`.
- **Logging redaction** (security.md §Secrets handling): if/when `SsrfGuardedHttpClient` emits a JUL log line for a policy denial, the URL must already be redacted at the call site. Do not log raw URLs.
- **No `@QuarkusTest`** on the new tests — matches `RssFetcherTest`, `RssFeedParserTest`, and the `infochat-core` library precedent. The ssrf module ships zero CDI surfaces in this ticket.

### Implementation order
1. **Module skeleton first**: parent `pom.xml` `<modules>` + `dependencyManagement` entry, then `infochat-ssrf/pom.xml`. Verify with `mvn -B -pl infochat-ssrf clean compile` that the empty module builds.
2. **`UrlRedactor`** next — pure function, no deps, used by the next two steps' tests and by RssFetcher; landing it first lets later tests assert against its exact output.
3. **`IpBlocklist`** + `IpBlocklistTest` — pure function, no network. Lock the address-classification surface before the HTTP client depends on it.
4. **`SsrfGuardedHttpClient`** + `SsrfGuardedHttpClientTest` — uses both prior classes. Use `HttpServer` localhost fixture (test-mode blocklist override).
5. **`RssFeedParser.java`** item-count cap — narrowest possible diff (one constant + two counter sites). Land before the RssFetcher rewire because the existing `RssFetcherTest` parses fixture XML.
6. **`RssFetcher.java`** wrapper swap + `UrlRedactor` calls + javadoc edit. Then update `RssFetcherTest` (fixture migrates to test-mode override, two new tests added).
7. **Full `mvn -B clean verify` from repo root** — DoD-mandated.

### Risks
- **`HttpClient.send` for HEAD vs GET asymmetry** — spec says HTTP-shaped fetchers support GET and HEAD only. DoD names only `get(URI)`. Provider's `/add-source` HEAD probe is out_of_scope, so v1 surface is GET-only. If reviewer flags missing HEAD, escalation: **defer** (separate Provider ticket per out_of_scope item 1) — do NOT scope-creep.
- **Item-count cap interplay with redirect cap** — the DoD doesn't constrain interaction; default 1000 items × 3 redirect hops is independent. No risk if the two counters are scoped to their own contexts.
- **Test seam vs feature flag boundary** — the ticket's implementation note "IpBlocklist override is an API surface, not a flag" forbids `infochat.ssrf.test.permit-loopback`-style config. Implementation MUST use the package-private constructor. If a hidden config path appears in PR, reviewer fails it as defensive/feature-flag drift.
- **`Redirect.NEVER` + manual loop may interact with JDK HttpClient internals** (e.g., HTTP/2 server push, `Expect: 100-continue`). RSS feeds in v1 are HTTP/1.1 GETs; risk is bounded by the redirect cap and body-cap.
- **`files_budget: 12` is exactly hit** — adding any extra file (e.g., a logging helper, an inner enum extracted) trips the cap. If `SsrfGuardedHttpClient` grows a `ResponseBodyReader` companion file, inline it as a nested class. Escalation if budget pressure surfaces: **refine** or refactor to use nested classes (prefer the latter).
- **`com.sun.net.httpserver.HttpServer` is JDK-internal** — used by `RssFetcherTest` already; no new precedent. No risk.

### Out-of-scope (echoed from ticket)
- Provider-side `/add-source` URL-validation HEAD/GET probe (separate later ticket)
- WebSocket (ws/wss) wrapping (NostrStreamSource ticket consumes IpBlocklist policy class but its transport wrapper is separate)
- FetchScheduler / @Scheduled wiring / per-tick cadence (T1-C @Priority(400))
- outbox sink, RAW-row INSERT, post-table write, OutboxRehydrator, LISTEN/NOTIFY new_post, provider_state, NewPostReconciler (T1-C)
- Stage 1 HTML sanitization, NFKC normalization, regex redaction, canonical-body UID hashing (T1-D)
- Bluesky / Nitter / Reddit / YouTube / Odysee fetcher implementations (each Tier-3 T3-B ticket)
- source-row UPDATE for last_fetch_at / last_success_at / consecutive_failures (FetchScheduler's responsibility per D42)
- pagination cap counter, admin-notification on saturation, single-tick page-walk
- retry / backoff / Retry-After / per-source politeness (FetchScheduler boundary)
- change to `io.infochat.core.ingest.Fetcher` SPI or `NormalizedPost` record
- V1..V8 Flyway migrations (migration_touch: false)
- LLM tool surface, tool registry, prompt-injection sanitizer, LLM-output redactor
- audit_log writes (SSRF policy denials logged via JUL with URL pre-redacted by UrlRedactor)
