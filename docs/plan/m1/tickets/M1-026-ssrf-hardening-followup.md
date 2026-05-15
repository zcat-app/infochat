---
id: M1-026
title: infochat-ssrf hardening followup (M1-025 remediation)
status: done
created: 2026-05-15
last_updated: 2026-05-15
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
      files: 7
      added: 581
      removed: 190
implementation_log:
  - "2026-05-15: implementation complete. mvn -B -pl infochat-ssrf test: 44 tests pass (24 IpBlocklist, 14 SsrfGuardedHttpClient, 6 UrlRedactor); 3 new @Test methods added (hostInterfaceAddedAfterStartupIsBlocked, dripBodyReadHitsTotalDeadline, pinSurvivesMixedCaseAndTrailingDot). mvn -B clean verify from repo root: all 7 modules SUCCESS, RssFetcherTest preserved unchanged. Files touched: 5 (IpBlocklist.java, SsrfGuardedHttpClient.java, PinnedDnsResolver.java, IpBlocklistTest.java, SsrfGuardedHttpClientTest.java) — at files_budget."
clarity_check:
  date: 2026-05-15
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE: Item 10 (isBlocked per-call invocation + TTL cache bound) has no runnable mechanical check; the per-call behavior is tested indirectly by item 11's integration test. The TTL upper-bound (<= 5 s) is not directly asserted by any runnable command. ACCEPTED — the per-call behavior is integration-tested in item 11; the TTL is optional and integration-tested via the AtomicReference mutation pattern."
    - "FORWARD-REFERENCE-CHECK: M1-027 is referenced in the Alternatives considered prose (split off F1 into M1-027) but no such ticket file exists. This is a prose mention of a conditional decomposition outcome; it does not block the ticket but the escalation path it names is untraceable. ACCEPTED — conditional decomposition outcome; if escalated, the actual decomposition ticket will be filed at that point and a substitute placeholder is not warranted."
  warnings_resolved_by_direct_edit:
    - "ACCEPTANCE-RUNNABLE: Item 6 — addressed by adding `grep -cE 'canonicalizeHost' SsrfGuardedHttpClient.java returns at least 2 matches` to item 6 (covers both initial-hop and redirect-hop call sites)."
    - "ACCEPTANCE-RUNNABLE: Item 14 — removed (prose-summary acceptance item was redundant with items 1-11)."
    - "ACCEPTANCE-VS-DOD-CONSISTENT: Item 12 count clause — removed; item 12 now names the three test methods explicitly without an aggregate count. Items 4/8/11 already pin each test individually."
    - "FILES-BUDGET-PLAUSIBLE: Sibling utility class option — removed from both acceptance item 5 and the DoD. canonicalizeHost is now mandated as a package-private static on SsrfGuardedHttpClient (PinnedDnsResolver calls it directly since both classes are in io.infochat.ssrf). Stays within files_budget: 5."
  blockers: []
blocked_by:
  - M1-025
remediates: M1-025
files_budget: 5
files_scope:
  - infochat-ssrf/src/main/java/io/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/main/java/io/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/main/java/io/infochat/ssrf/PinnedDnsResolver.java
  - infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java
  - infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to the `io.infochat.core.ingest.Fetcher` SPI or `NormalizedPost` record (unchanged from M1-023/M1-024/M1-025)
  - any change to `RssFetcher.java` or `RssFeedParser.java` — the wrapper's PUBLIC API surface (`new SsrfGuardedHttpClient()` no-arg constructor + `HttpResponse<byte[]> get(URI)`) is preserved; only the wrapper's internals change. RssFetcher continues to compile against the unchanged surface
  - any change to `RssFetcherTest.java` — the existing test-mode IpBlocklist override seam continues to work; no new test surface is consumed there
  - any change to `HostInterfaceSet.java` — M1-025 landed the enumerator at its current shape; M1-026 only changes how/when IpBlocklist invokes it, not what it returns
  - any change to `META-INF/services/java.net.spi.InetAddressResolverProvider` — the ServiceLoader registration M1-025 added is unchanged
  - any change to `UrlRedactor.java` or `SsrfPolicyException.java`
  - any WebSocket / ws / wss wrapping (NostrStreamSource ticket — that ticket consumes the same `IpBlocklist` and the same DNS-pinning seam as M1-025/M1-026 produce)
  - any Provider-side `/add-source` HEAD/GET probe wiring (separate later ticket; the hardened wrapper is consumed by that ticket as `new SsrfGuardedHttpClient(...).get(uri)`)
  - any FetchScheduler / `@Scheduled` / per-tick cadence selection (T1-C territory)
  - any outbox sink, RAW-row INSERT, post-table write, OutboxRehydrator, LISTEN/NOTIFY new_post, provider_state high-water-mark, or NewPostReconciler wiring (T1-C)
  - any Stage 1 HTML sanitization, NFKC normalization, regex redaction, or canonical-body UID hashing (T1-D)
  - any source-row UPDATE for last_fetch_at / last_success_at / consecutive_failures
  - any retry / backoff / Retry-After / per-source politeness window
  - any change to V1..V8 Flyway migrations (migration_touch: false; this ticket is impl-only)
  - any LLM tool surface, prompt-injection sanitizer, or LLM-output redactor
  - any audit_log write or AuditLogger code path
  - any port-based filtering (OUT-OF-MODEL per M1-024 redteam — a separate spec-amend ticket would be required)
  - any operator-facing knob to disable host-interface enumeration, DNS pinning, or the new body-read deadline (security.md §SSRF: "The allowlist is not user-configurable"; the test seams remain constructor-parameter only, never flags)
acceptance:
  # ----------------------------------------------------------------------
  # Finding 1 (M1-025 redteam, DOS / high) — drip body-read defeats the
  # per-read watchdog, and the JVM-wide pinning lock serializes all
  # outbound HTTP across both services for the drip's duration.
  # Spec verbatim (security.md §SSRF and outbound connections):
  #   "Redirect, body-size, connect-timeout, and read-timeout caps are
  #    enforced; an unset timeout is a configuration error."
  # M1-025 implemented "read-timeout" as a PER-READ watchdog. A drip
  # attacker delivering 1 byte every (readTimeout - epsilon) keeps each
  # individual in.read() under the per-read window, so the watchdog
  # never fires; the body cap (10 MiB) bounds bytes, not time, so the
  # drip can hold a single get(uri) call (and the JVM-wide pinning
  # ReentrantLock) for ~9.6 years. The remediation adds a TOTAL
  # body-read wall-clock deadline and removes the body-read phase from
  # the lock-held scope so concurrent fetches can interleave.
  # ----------------------------------------------------------------------
  - "SsrfGuardedHttpClient's constructor accepts a `Duration bodyReadDeadline` parameter (separate from `readTimeout` and `requestTimeout`); the constructor REJECTS null, zero, or negative `bodyReadDeadline` with `IllegalArgumentException` whose message starts with the literal substring `body read deadline must be configured` (mirroring M1-024's `timeout must be configured` and M1-025's `read timeout must be configured` discipline). The no-arg public constructor supplies a sensible default (e.g., `Duration.ofMinutes(2)`) so RssFetcher's existing call site is unaffected. grep -E 'body read deadline must be configured' SsrfGuardedHttpClient.java returns at least one match"
  - "SsrfGuardedHttpClient.readBounded() enforces a TOTAL wall-clock deadline on the body-read phase: the elapsed wall-clock time from the start of body reading must not exceed `bodyReadDeadline`; if it does, raises `SsrfPolicyException` with message starting with the literal substring `body read deadline exceeded`. This is SEPARATE from M1-025's per-read `readTimeout` watchdog (which still fires on zero-bytes-for->readTimeout); per-read covers stalled reads, total-deadline covers drip attacks that pass per-read but accumulate. grep -E 'body read deadline exceeded' SsrfGuardedHttpClient.java returns at least one match"
  - "SsrfGuardedHttpClient.get(uri) releases `PinnedDnsResolver.Provider.lock()` BEFORE readBounded begins streaming the body. The lock-hold scope is: pin install + `httpClient.send(BodyHandlers.ofInputStream())` (which returns once headers are received) + any redirect-hop pin replacements. After the terminal hop's headers are received, the wrapper clears the pin and unlocks BEFORE invoking readBounded on the response body stream. The JDK's body-read on the already-established connection does NOT re-resolve DNS, so releasing the lock before body-read is safe. Two concurrent `get(uri)` calls to different hosts can interleave their body reads without serializing on the JVM-wide pinning lock. Verifiable by file ordering: the LAST `lock.unlock` (or `lock.unlock()`-equivalent) call site in SsrfGuardedHttpClient.java must appear at a SMALLER line number than the `readBounded(` call site that processes the terminal response — i.e. `awk -F: 'NR==1{u=$2} END{print u\" \"r}' <(grep -nE 'lock\\.unlock\\(\\)' SsrfGuardedHttpClient.java | tail -1) <(grep -nE 'readBounded\\(' SsrfGuardedHttpClient.java | head -1)` returns unlock-line before readBounded-line"
  - "SsrfGuardedHttpClientTest adds a @Test method `dripBodyReadHitsTotalDeadline`: an in-process HttpServer fixture (`com.sun.net.httpserver.HttpServer`) writes `Content-Length: 5000000`, the 200 OK headers, and then 1 byte every (readTimeout / 4) wall-clock interval — so the per-read watchdog NEVER fires (each individual read returns well under readTimeout). The wrapper is constructed with `readTimeout=500ms` and `bodyReadDeadline=2s`. Assert: `assertThrows(SsrfPolicyException.class, () -> client.get(uri))` AND `assertTrue(thrown.getMessage().startsWith(\"body read deadline exceeded\"))` AND `assertTrue(elapsed >= bodyReadDeadline.toMillis() && elapsed < bodyReadDeadline.toMillis() + 1000)` (the deadline fires within bodyReadDeadline + 1s tolerance, NOT after the full requestTimeout). The test proves the drip attack is now bounded by total elapsed body-read time, not per-read time"

  # ----------------------------------------------------------------------
  # Finding 2 (M1-025 redteam, INFO-LEAK / medium) — pin map key
  # case-sensitivity / IDN / trailing-dot mismatch with JDK normalization.
  # Spec verbatim (security.md §SSRF and outbound connections):
  #   "Both services use the same shared library module (infochat-ssrf)
  #    which carries the IP blocklist, DNS-rebind defense, redirect cap,
  #    and timeout caps"
  # AND:
  #   "DNS is re-resolved after every redirect (TOCTOU defense); the IP
  #    blocklist re-applies each hop."
  # M1-025's pinning closes the within-hop TOCTOU window IF the pin
  # actually fires. Map.get(host) is an equals-based exact match; if
  # the JDK normalizes the hostname (case-fold, trailing-dot,
  # IDN ↔ punycode) before invoking the resolver SPI with a different
  # form than URI.getHost() returned, the pin misses and the resolver
  # falls through to BUILTIN — defeating the rebind defense. The
  # remediation canonicalizes the host on BOTH the install side and
  # the lookup side so the pin matches regardless of JDK normalization
  # choices.
  # ----------------------------------------------------------------------
  - "A package-private static helper `io.infochat.ssrf.SsrfGuardedHttpClient.canonicalizeHost(String host)` is defined as a method on `SsrfGuardedHttpClient` itself — NOT a sibling utility class, since `PinnedDnsResolver` lives in the same `io.infochat.ssrf` package and can call the package-private static directly, and a sibling class would push the implementation to 6 files and breach `files_budget: 5`. The helper applies the following transformations in order: (a) reject null/blank with `IllegalArgumentException`; (b) `java.net.IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)` to convert Unicode/punycode to canonical ASCII; (c) `.toLowerCase(java.util.Locale.ROOT)` to case-fold without locale-specific surprises (the Turkish-dotless-i hazard); (d) strip a single trailing `.` if present (the FQDN trailing-dot variant). Returns the canonical form. grep -E 'IDN\\.toASCII' SsrfGuardedHttpClient.java returns at least one match AND grep -E 'Locale\\.ROOT' SsrfGuardedHttpClient.java returns at least one match"
  - "SsrfGuardedHttpClient.get(uri) invokes `canonicalizeHost(uri.getHost())` BEFORE installing the DNS pin, so the pin map entry is always keyed by the canonical form (e.g., `Map.of(canonicalizeHost(uri.getHost()), validatedIps)`). The per-redirect-hop pin replacement applies the same canonicalization to the new target's host. Verifiable: grep -cE 'canonicalizeHost' SsrfGuardedHttpClient.java returns at least 2 matches (the initial-hop call site AND the redirect-hop call site, exclusive of the helper definition itself, so >=2 covers at least the two call sites; the helper definition pushes the actual count to >=3)"
  - "PinnedDnsResolver.lookupByName(host, lookupPolicy) invokes the SAME `canonicalizeHost(host)` helper on its `host` argument BEFORE the `pins.get(...)` lookup — defense-in-depth on the lookup side, so the pin matches even if the JDK passes a form different from what URI.getHost() returned at install time. grep -E 'canonicalizeHost' PinnedDnsResolver.java returns at least one match"
  - "SsrfGuardedHttpClientTest adds a @Test method `pinSurvivesMixedCaseAndTrailingDot`: the resolver-seam is invoked through the wrapper's resolver-seam constructor parameter and records every host it is called with. The test calls `client.get(URI.create(\"http://EVIL.Example.test./\"))` — a mixed-case host with a trailing dot — against an in-process HttpServer on 127.0.0.1 with the test-mode IpBlocklist that permits 127.0.0.1. The seam is configured to return `[127.0.0.1]` for any host input. Assert: `assertEquals(200, response.statusCode())` AND the seam was invoked exactly once with the CANONICAL form `\"evil.example.test\"` (lowercased + trailing-dot-stripped, no IDN-roundtripping needed for ASCII input). If canonicalization were missing on either side, the pin would mismatch and the resolver would fall through to BUILTIN — the in-process server would not be reached and the test would fail on connection refused or unexpected resolution"

  # ----------------------------------------------------------------------
  # Finding 3 (M1-025 redteam, INFO-LEAK / low) — host-interface set is
  # snapshotted at JVM startup; post-start interfaces (VPN, hot-plugged
  # NIC, freshly-attached cloud EIP) are not in the blocklist.
  # Spec verbatim (security.md §SSRF and outbound connections):
  #   "DNS-resolved IPs are checked against a blocklist of private,
  #    loopback, link-local, multicast, CGNAT, and cloud-metadata ranges
  #    (notably 169.254.169.254 and IPv6 equivalents) plus the host's
  #    own non-loopback interfaces."
  # Spec is present-tense ("are checked"), no startup-snapshot qualifier.
  # The remediation widens IpBlocklist's host-interface field from a
  # frozen Set<InetAddress> to a Supplier<Set<InetAddress>> consulted
  # per call, with HostInterfaceSet::enumerate as the default supplier.
  # ----------------------------------------------------------------------
  - "IpBlocklist exposes a new package-private constructor (or static factory) accepting `Supplier<Set<InetAddress>> hostInterfacesProvider`. The existing M1-025 package-private `IpBlocklist(Set<InetAddress> hostInterfaces)` constructor is PRESERVED as an overload (so M1-025's hostInterfaceIpIsBlocked + nonHostPublicIpStillAllowed tests continue to pass UNCHANGED); internally it delegates to the Supplier form as `new IpBlocklist(() -> Set.copyOf(hostInterfaces))`. The no-arg constructor defaults `hostInterfacesProvider` to `HostInterfaceSet::enumerate` directly (per-call enumeration), NOT a one-shot snapshot. grep -E '(Supplier<Set<InetAddress>>|Supplier<? extends Set<InetAddress>>)' IpBlocklist.java returns at least one match"
  - "IpBlocklist.isBlocked(addr) invokes `hostInterfacesProvider.get()` per call (not the snapshot of M1-025); a host interface brought up AFTER IpBlocklist was constructed is correctly seen on the next isBlocked call. Implementation may add a short TTL cache (≤ 5 seconds) over the supplier to amortize the JNI NetworkInterface.getNetworkInterfaces() call on hot paths — that is acceptable as long as the cache lifetime is shorter than 'a few seconds' so post-start interface changes are reflected promptly (the spec's 'plus the host's own non-loopback interfaces' is present-tense)"
  - "IpBlocklistTest adds a @Test method `hostInterfaceAddedAfterStartupIsBlocked`: construct IpBlocklist via the new Supplier-form constructor with a `java.util.concurrent.atomic.AtomicReference<Set<InetAddress>>` whose initial value is `Set.of()` (no host interfaces yet). Assert `isBlocked(InetAddress.getByName(\"203.0.113.5\"))` returns false (the IP is not in any blocked range and the host-interface set is empty). Then call `ref.set(Set.of(InetAddress.getByName(\"203.0.113.5\")))` to simulate a new interface coming up. Assert `isBlocked(InetAddress.getByName(\"203.0.113.5\"))` returns true on the very next call (or within the configured TTL window, e.g., after a `Thread.sleep(6000)` if the impl uses a 5s TTL cache — the test's tolerance for cache-lifetime is named in the assertion message). This proves the snapshot-at-construction semantics from M1-025 are gone"

  # ----------------------------------------------------------------------
  # Build + cross-cut
  # ----------------------------------------------------------------------
  - "mvn -B -pl infochat-ssrf test exits 0; the three new test methods (`dripBodyReadHitsTotalDeadline`, `pinSurvivesMixedCaseAndTrailingDot`, `hostInterfaceAddedAfterStartupIsBlocked`) execute and pass alongside M1-024's and M1-025's existing IpBlocklistTest / SsrfGuardedHttpClientTest / UrlRedactorTest classes"
  - "mvn -B clean verify from the repo root exits 0; M1-024's RssFetcherTest continues to pass UNCHANGED (the wrapper's public API surface — `new SsrfGuardedHttpClient()` no-arg + `HttpResponse<byte[]> get(URI)` — is preserved; this ticket only widens internal constructors + adds the constructor `Duration bodyReadDeadline` parameter, which the no-arg constructor supplies a default for so RssFetcher's call site is unaffected). M1-025's existing IpBlocklistTest and SsrfGuardedHttpClientTest @Test methods continue to pass UNCHANGED (the M1-025 package-private constructors are preserved as overloads of the new Supplier-form and bodyReadDeadline-bearing constructors respectively)"
test_plan:
  adds:
    - infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java — adds at least 1 new @Test method (`hostInterfaceAddedAfterStartupIsBlocked`). Existing M1-024/M1-025 @Test methods preserved unchanged.
    - infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java — adds at least 2 new @Test methods (`dripBodyReadHitsTotalDeadline`, `pinSurvivesMixedCaseAndTrailingDot`). Existing @Test methods preserved (the new `bodyReadDeadline` constructor parameter is supplied with a default in the no-arg constructor, so no-arg-constructor tests are unaffected; tests that construct via the package-private parameterized constructor get the new parameter explicitly, a mechanical one-line edit per test).
  modifies:
    - infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java — file appears in both `adds` and `modifies` because new @Test methods land alongside preserved ones; the file is modified, not created.
    - infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java — same reason as above.
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFetcherTest.java (M1-023/M1-024 — unchanged; the wrapper's public API surface is preserved)
    - infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFeedParserTest.java (M1-023 — unchanged)
    - infochat-ssrf/src/test/java/io/infochat/ssrf/UrlRedactorTest.java (M1-024 — unchanged)
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-collector/src/test/java/io/infochat/collector/bootstrap/BootstrapLoaderIT.java (M1-022)
    - all M1-008a / M1-008b / M1-008c / M1-009 / M1-017 *Test.java and *IT.java classes
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D20
---

# M1-026: infochat-ssrf hardening followup (M1-025 remediation)

## Context

M1-025 landed the second pass of the `infochat-ssrf` shared module,
remediating the four findings the `/redteam M1-024` audit surfaced.
The `/redteam M1-025` audit (2026-05-15, recorded in
`redteam_findings:` on the M1-025 ticket and absorbed into commit
`0e74a0d`) found that the M1-025 implementation closed the four
original gaps but introduced — or did not fully close — three new
ones:

1. **DOS / high — drip body-read defeats the per-read watchdog +
   JVM-wide lock starvation.** M1-025's `readBounded` enforces a
   per-individual-read wall-clock cap (`readTimeout`, 30 s default).
   An attacker delivering 1 byte every `(readTimeout − epsilon)`
   keeps each `in.read(buf)` returning `n=1` well under the per-read
   window, so the watchdog never fires. The body cap (10 MiB) bounds
   bytes, not time — so a drip attacker can hold a single `get(uri)`
   call for ~9.6 years at default settings. Worse, the JVM-wide
   `PinnedDnsResolver.Provider` `ReentrantLock` is held for the
   entire `get(uri)` call (including body read), so a single hostile
   feed serializes ALL outbound HTTP across the Collector + Provider
   for the drip's duration. This regresses the slow-loris vector
   M1-024 Finding 4 was meant to close.

2. **INFO-LEAK / medium — pin map case-sensitivity / IDN /
   trailing-dot mismatch with JDK normalization.** M1-025's pin map
   is keyed by `URI.getHost()` raw-case; the JVM-wide forwarding
   resolver does `Map<String, List<InetAddress>>.get(host)`, an
   `equals`-based exact match. If the JDK's `HttpClient.send`
   internally normalizes the host (case-fold, trailing-dot strip,
   IDN ↔ punycode) before invoking the resolver SPI with a form
   different from what `URI.getHost()` returned, `pins.get(host)`
   returns `null` and `ForwardingResolver` falls through to
   `BUILTIN.lookupByName(host, …)` — the real DNS, with no
   IpBlocklist re-check. A DNS-rebind adversary controlling the
   authoritative nameserver re-opens the within-hop TOCTOU window
   the pinning was added to close.

3. **INFO-LEAK / low — host-interface set is snapshotted at JVM
   startup.** M1-025's no-arg `IpBlocklist()` constructor calls
   `HostInterfaceSet.enumerate()` once at construction and stores
   the result in a final field. Interfaces brought up post-start
   (VPN tunnels, hot-plugged NICs, container bridges added by
   docker-daemon restart, Kubernetes sidecar IPs assigned
   post-pod-init, freshly-attached cloud EIPs) are never added to
   the set. The spec text is present-tense ("are checked"), no
   startup-snapshot qualifier.

This ticket is the **single remediation ticket** for all three
findings, following the M1-024→M1-025 Option-A precedent. The
M1-025 ticket's pre-impl `revisions:` block taught the lesson that
JDK 25's `InetAddressResolverProvider` SPI is JVM-global only —
that design constraint stays in force here; the F1 fix is to
remove the body-read phase from the lock-held scope, not to lift
the lock entirely.

M1-025's squash-merge commit (`0e74a0d`) is **immutable** per
`CLAUDE.md` §M1 workflow — "Never amend a passed commit." This
ticket lands the remediation as a fresh `M1-026:` commit with
frontmatter `remediates: M1-025` so the lineage is mechanically
traceable.

## Definition of Done

### `io.infochat.ssrf.IpBlocklist` (MODIFIED)

- New package-private constructor:
  ```java
  IpBlocklist(Supplier<Set<InetAddress>> hostInterfacesProvider)
  ```
  Widens M1-025's `IpBlocklist(Set<InetAddress>)` to a Supplier so
  the host-interface set is consulted on every `isBlocked` call,
  not snapshotted at construction.
- The M1-025 `IpBlocklist(Set<InetAddress>)` constructor is
  **preserved** as an overload; internally it delegates to the
  Supplier form as `new IpBlocklist(() -> Set.copyOf(hostInterfaces))`.
  This keeps M1-025's `hostInterfaceIpIsBlocked` /
  `nonHostPublicIpStillAllowed` tests passing UNCHANGED.
- The no-arg public constructor defaults the supplier to
  `HostInterfaceSet::enumerate` (per-call enumeration via the JNI
  `NetworkInterface.getNetworkInterfaces()`), NOT a one-shot
  snapshot.
- `isBlocked(InetAddress addr)` invokes `hostInterfacesProvider.get()`
  per call before the equals-check. Implementation **may** wrap
  the supplier with a short TTL cache (≤ 5 s) to amortize the JNI
  call on hot paths; the cache lifetime must be shorter than "a few
  seconds" so post-start interface changes are reflected promptly.

### `io.infochat.ssrf.SsrfGuardedHttpClient` (MODIFIED)

- Constructor adds a `Duration bodyReadDeadline` parameter. The
  no-arg public constructor supplies a sensible default (e.g.,
  `Duration.ofMinutes(2)`). Constructor rejects null/zero/negative
  `bodyReadDeadline` with `IllegalArgumentException("body read
  deadline must be configured")`.
- New package-private static helper `canonicalizeHost(String host)`
  defined on `SsrfGuardedHttpClient` itself (NOT a sibling utility
  class — `PinnedDnsResolver` is in the same `io.infochat.ssrf`
  package and can call the package-private static directly; a
  sibling class would push the implementation to 6 files and breach
  `files_budget: 5`). Applies, in order: reject null/blank;
  `IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)`;
  `.toLowerCase(Locale.ROOT)`; strip a single trailing `.` if
  present. Returns the canonical form.
- `get(URI uri)` invokes `canonicalizeHost(uri.getHost())` BEFORE
  installing the pin, so the pin map entry is keyed by the canonical
  form. Each redirect hop applies the same canonicalization to the
  new target's host before re-installing the pin.
- `get(URI uri)` releases `PinnedDnsResolver.Provider.lock()` BEFORE
  invoking `readBounded(response.body(), bodyCap)`. The lock-hold
  scope is: pin install + `httpClient.send(BodyHandlers.ofInputStream())`
  (which returns once headers are received) + any redirect-hop pin
  replacements. After the terminal hop's headers are received, the
  wrapper clears the pin and releases the lock in a `finally`-equivalent
  shape; only then does `readBounded` start streaming the body. The
  JDK does NOT re-resolve DNS during body read on an already-established
  connection, so the lock release before body-read is safe.
- `readBounded(InputStream in, int bodyCap)` enforces a TOTAL
  wall-clock deadline (`bodyReadDeadline`) on the body-read phase
  in addition to the per-read `readTimeout` watchdog M1-025 added.
  If `(now − bodyReadStartTime) > bodyReadDeadline`, raise
  `SsrfPolicyException("body read deadline exceeded after Nms")`.

### `io.infochat.ssrf.PinnedDnsResolver` (MODIFIED)

- `lookupByName(String host, LookupPolicy lookupPolicy)` invokes
  the SAME `canonicalizeHost(host)` helper (the package-private
  static on `SsrfGuardedHttpClient`, called directly since both
  classes are in `io.infochat.ssrf`) on its `host` argument BEFORE
  the `pins.get(canonicalHost)` lookup. Defense-in-depth: even if
  the JDK passes a non-canonical form, the resolver still matches
  the canonical pin.
- The nested `Provider` class, its static pin slot, and the JVM-wide
  `ReentrantLock` are unchanged in shape — only `lookupByName`'s
  pre-lookup transformation widens.

### Tests

- `IpBlocklistTest` (modified) — 1 new `@Test` method:
  - `hostInterfaceAddedAfterStartupIsBlocked` (Finding 3)
- `SsrfGuardedHttpClientTest` (modified) — 2 new `@Test` methods:
  - `dripBodyReadHitsTotalDeadline` (Finding 1)
  - `pinSurvivesMixedCaseAndTrailingDot` (Finding 2)

Plus mechanical one-line edits to M1-025's existing parameterized-
constructor tests to supply the new `bodyReadDeadline` argument;
substance preserved.

### Module build

`mvn -B clean verify` exits 0. M1-023's `RssFeedParserTest`,
M1-024's `RssFetcherTest` / `UrlRedactorTest`, and M1-025's
preserved `IpBlocklistTest` / `SsrfGuardedHttpClientTest`
`@Test` methods continue to pass unchanged.

## Implementation notes

- **Lock release before body-read is the load-bearing F1 fix.**
  The total `bodyReadDeadline` is necessary (it bounds the drip
  vector by elapsed time) but not sufficient on its own — without
  releasing the lock, a single hostile feed still serializes all
  outbound HTTP for `bodyReadDeadline` (2 minutes default). With
  the lock released, concurrent fetches can run; the worst the
  drip can do is consume one Fetcher's slot for at most
  `bodyReadDeadline` before the deadline fires.
- **JDK does not re-resolve during body read on an established
  connection.** Once `httpClient.send` returns and the connection
  is established, the JDK has the IP bound to the socket; body
  reads pull from that socket without triggering further DNS
  lookups. This is what makes the lock-release-before-body-read
  safe. (If the JDK changed this in a future version, the body-
  read could re-enter the resolver — but the test for "JDK does
  not re-resolve during body read" is the implicit one: the
  drip-body-read test passes with the lock released.)
- **`canonicalizeHost` must be shared, not duplicated.** Two
  classes call it (`SsrfGuardedHttpClient` on pin install,
  `PinnedDnsResolver` on pin lookup); duplicating the
  canonicalization logic creates the very inconsistency F2 was
  about. Pick one home for the helper and import. A
  package-private static method on `SsrfGuardedHttpClient` is
  acceptable since `PinnedDnsResolver` is in the same package.
- **IDN.toASCII applied first, then lowercase.** IDN.toASCII for
  ASCII input is idempotent and very cheap. The order matters
  because lowercase-then-IDN could mis-handle case-sensitive
  punycode constructs in pathological inputs; IDN-then-lowercase
  is the safe ordering.
- **Trailing-dot strip is single, not greedy.** `"foo.example."`
  → `"foo.example"`; `"foo.example.."` would be rejected by
  `IDN.toASCII` as invalid anyway, but be defensive and strip
  only a single trailing `.`.
- **`Supplier<Set<InetAddress>>` with TTL cache implementation.**
  If the developer adds the TTL cache, the recommended pattern
  is a private helper class holding `AtomicReference<CachedSet>`
  where `CachedSet` is `(Set<InetAddress>, long expiryNanos)`.
  `get()` checks expiry; if expired, calls the underlying
  enumerator once, races to update the AtomicReference (last writer
  wins is acceptable). Avoid `synchronized` here to keep `isBlocked`
  non-blocking.
- **The new `bodyReadDeadline` parameter is REQUIRED on the
  package-private parameterized constructor.** The no-arg
  constructor supplies the default. RssFetcher (which uses the
  no-arg constructor) needs no change. Tests that construct via
  the parameterized form get the new parameter explicitly via the
  authorized one-line edits.

## Big-picture notes

- **Three findings, one ticket.** Following the M1-024→M1-025
  precedent. All three findings live in the same module's
  internals, share the test infrastructure (in-process HttpServer,
  resolver-seam, IpBlocklist override), and benefit from atomic
  landing. The Plan-subagent escalation path is to **decompose**
  if a finding turns out to require a more invasive refactor than
  budget allows (e.g., the lock-release-before-body-read for F1
  requires refactoring the manual redirect loop) — but the
  estimated cost is within `files_budget: 5`.
- **`security_relevant: true` again.** The next `/redteam M1-026`
  sweep will audit for any NEW gaps introduced by these three
  remediation surfaces. Redteam-after-redteam is the workflow's
  intentional design — there is no a-priori bound on how many
  passes a security-sensitive surface needs, only the empirical
  fact that each pass narrows the gap.
- **No public-API change.** `new SsrfGuardedHttpClient()` no-arg
  + `HttpResponse<byte[]> get(URI)` continue to compile and
  behave identically. The bodyReadDeadline parameter has a
  default; the canonicalization is internal; the IpBlocklist
  constructor widening is additive (M1-025 Set overload
  preserved).
- **Future StreamSource ticket inherits the now-canonical
  pinning.** The NostrStreamSource ticket's WebSocket wrapper
  will reuse `IpBlocklist` (with the now-fresh per-call
  enumeration) and `PinnedDnsResolver` (with the canonical-host
  lookup); the per-WebSocket lock semantics are that ticket's own
  concern (its body-read phase is the WS event stream, which has
  its own structural difference from HTTP body reads).

## Out-of-scope expansion

- **Provider-side `/add-source` URL-validation probe.** Separate
  later ticket; consumes the now-hardened `SsrfGuardedHttpClient`.
- **WebSocket / ws / wss wrapping.** NostrStreamSource ticket
  consumes the now-hardened `IpBlocklist` + `PinnedDnsResolver`;
  the transport wrapper itself is its own scope.
- **FetchScheduler / `@Scheduled` / per-tick wiring.** T1-C
  territory.
- **Outbox sink / OutboxRehydrator / new_post NOTIFY /
  provider_state / NewPostReconciler.** T1-C.
- **Stage 1 sanitization / NFKC / regex redaction.** T1-D.
- **Per-source failure-counter UPDATE.** FetchScheduler's
  responsibility per D42.
- **Retry / backoff / Retry-After.** FetchScheduler's responsibility.
- **Fetcher SPI / NormalizedPost shape change.** Unchanged from
  M1-023/M1-024/M1-025.
- **V1..V8 Flyway migrations.** `migration_touch: false`.
- **Port-based filtering.** OUT-OF-MODEL per the M1-024 redteam
  notes; would require a spec amendment.
- **Operator-facing knob to disable host-interface enumeration,
  DNS pinning, or the new body-read deadline.** The spec says
  "the allowlist is not user-configurable"; the test seams are
  constructor-parameter only, never flags.
- **DNS-pin refresh cadence per host.** The pinning is per-call;
  there is no long-lived pin to refresh.
- **Connection pool DNS isolation.** Per-call HttpClient
  construction already isolates connections; the spec doesn't
  commit to per-call isolation as a named guarantee.
- **Lifting the JVM-wide pinning lock entirely** (e.g., by keying
  the static pin slot by hostname for parallel pin coexistence).
  Considered but rejected here: the F1 fix (release the lock before
  body-read) covers the dominant DoS vector; keying by hostname
  would be a larger refactor without a redteam-driven need.
  Future work if observed.

## Authorized test changes

- `infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java`
  — add 1 new `@Test` method (`hostInterfaceAddedAfterStartupIsBlocked`).
  All pre-existing `@Test` methods (M1-024's range coverage,
  M1-025's host-interface and bypass-form tests) are preserved
  unchanged in substance.
- `infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java`
  — add 2 new `@Test` methods (`dripBodyReadHitsTotalDeadline`,
  `pinSurvivesMixedCaseAndTrailingDot`). All pre-existing `@Test`
  methods are preserved unchanged in substance, with the caveat
  that any test that constructs `SsrfGuardedHttpClient` via the
  package-private parameterized constructor must supply the new
  `bodyReadDeadline` parameter (one-line per-test mechanical
  edit). Tests using the no-arg constructor are unaffected.

`RssFetcherTest`, `RssFeedParserTest`, `UrlRedactorTest`, and all
M1-001..M1-024 tests stay untouched — the wrapper's public API
surface is preserved.

## Alternatives considered

- **Split into 3 tickets (one per finding).** Considered. Single
  ticket chosen per the M1-024→M1-025 Option-A precedent: all
  three findings live in the same module's internals and share
  the test infrastructure (in-process HttpServer, resolver-seam,
  IpBlocklist override), so atomic landing reduces the testing
  matrix and avoids inter-ticket coupling. If during implementation
  the F1 lock-release-before-body-read refactor turns out to be
  more invasive than budget allows, the escalation path is
  **decompose** at that point (split off F1 into M1-027) rather
  than expand budget.
- **Skip the `canonicalizeHost` helper and instead lowercase
  hostnames at every call site separately.** Rejected — duplicated
  canonicalization logic creates the inconsistency F2 was about.
  The shared helper is the DRY discipline.
- **Use `String.toLowerCase()` without `Locale.ROOT`.** Rejected
  — `String.toLowerCase()` uses the default locale, which in
  Turkish locales maps `I` to `ı` (dotless lowercase i), breaking
  `"INTERNAL".toLowerCase()` → `"ınternal"` ≠ `"internal"`. The
  `Locale.ROOT` form is locale-independent.
- **Skip the `bodyReadDeadline` parameter and instead enforce a
  short `requestTimeout`.** Rejected — `HttpRequest.timeout()`
  applies to receipt of HEADERS, not streaming body reads (this
  is the exact M1-024 trap that drove M1-025). The body-read
  phase needs its own wall-clock cap.
- **Address F1 with the bodyReadDeadline only, without releasing
  the lock.** Rejected — the lock-held-during-body-read serializes
  ALL outbound HTTP across both services for the deadline's
  duration. A single hostile feed becomes a 2-minute DoS against
  every other Fetcher even with the deadline in place. Releasing
  the lock before body-read is what makes concurrent fetches
  resilient to the attack.
- **Address F1 by keying the static pin slot by hostname so
  multiple pins can coexist.** Considered — would eliminate the
  serialization without a body-read-phase lock release. Rejected
  here as larger scope than needed: the F1 fix as specified
  (deadline + body-read lock release) closes the redteam-named
  vector and matches the spec promise; multi-pin coexistence is
  a future-work refactor if observed concurrent-fetch contention
  warrants it.
- **Skip F3 (post-startup interface) as low-severity and document
  the residual risk via spec amendment.** Considered. Rejected
  here: the spec text is present-tense without a snapshot
  qualifier, and the fix (Supplier<Set<InetAddress>>) is a
  surgical widening of M1-025's already-existing seam. The cost
  is small enough that closing the spec-vs-delivery gap is
  preferred over a spec amendment that would otherwise need to
  carry "snapshot at JVM start" language indefinitely.
- **Apply IDN canonicalization but NOT trailing-dot strip.**
  Rejected — the trailing-dot form is the FQDN convention, and
  some HTTP clients/DNS-resolvers strip the dot while others
  preserve it. Pinning against both forms (or canonicalizing
  away the dot) is the only way to be sure the pin matches
  regardless of JDK normalization.
- **Use a `ConcurrentHashMap<String, Long>` TTL cache instead of
  `AtomicReference<CachedSet>` for the Supplier wrapper.**
  Implementer's choice; the acceptance items name the BEHAVIOR
  (≤ 5 s cache lifetime), not the cache structure.

## Implementation order

1. Add the `canonicalizeHost` static helper to
   `SsrfGuardedHttpClient` first (or to a sibling utility class).
   Pure utility, no consumers yet. Trivial to unit-verify before
   anything depends on it. (Optional: add a few canonicalization
   asserts inside the test class for `pinSurvivesMixedCaseAndTrailingDot`.)
2. Modify `PinnedDnsResolver.lookupByName` to invoke
   `canonicalizeHost` before `pins.get(...)`. This change alone is
   defense-in-depth on the lookup side; no test will fail yet
   because pin install still uses raw `URI.getHost()`.
3. Modify `SsrfGuardedHttpClient.get(uri)` to invoke
   `canonicalizeHost(uri.getHost())` before pin install (both the
   initial hop and each redirect-hop pin replacement). After this
   step, F2 is closed end-to-end.
4. Refactor `SsrfGuardedHttpClient.get(uri)` to release the
   `PinnedDnsResolver.Provider.lock()` AFTER `httpClient.send`
   returns and BEFORE `readBounded`. The redirect loop stays
   inside the lock; only the body-read phase moves outside.
5. Add the `Duration bodyReadDeadline` constructor parameter +
   constructor validation (null/zero/negative reject with the
   literal "body read deadline must be configured"). Wire the
   no-arg constructor's default.
6. Modify `readBounded` to track elapsed wall-clock from the
   start of body-read; raise `SsrfPolicyException("body read
   deadline exceeded after Nms")` when elapsed > bodyReadDeadline.
   The per-read `readTimeout` watchdog stays in place — both
   guards now run.
7. Modify `IpBlocklist`: add the `Supplier<Set<InetAddress>>`
   constructor as a new package-private overload; preserve the
   M1-025 `Set<InetAddress>` constructor (delegate to the
   Supplier form); rewire the no-arg constructor to default the
   supplier to `HostInterfaceSet::enumerate` (per-call). Rewire
   `isBlocked` to call `supplier.get()` per call. (Optional TTL
   cache.)
8. Add `IpBlocklistTest.hostInterfaceAddedAfterStartupIsBlocked`
   using the new Supplier-form constructor + AtomicReference
   mutation.
9. Add `SsrfGuardedHttpClientTest.dripBodyReadHitsTotalDeadline`
   and `pinSurvivesMixedCaseAndTrailingDot` using the same
   in-process HttpServer pattern M1-025 established.
10. Edit existing parameterized-constructor tests to supply the
    new `bodyReadDeadline` argument (mechanical, one-line per
    test).
11. Run `mvn -B -pl infochat-ssrf test`, then `mvn -B clean verify`
    from the repo root. RssFetcherTest must pass unchanged
    (validates the public-API-surface invariant).

## Risks

- **The lock-release-before-body-read refactor changes the get()
  method's control flow.** The current M1-025 implementation has
  a manual redirect loop INSIDE the lock-held section. The
  refactor needs the loop to stay inside the lock (each redirect
  hop must re-pin) but the body-read of the terminal hop to be
  outside. Careful with the try/finally shape so the lock is
  always released even if `httpClient.send` throws or the
  manual-redirect-cap is exceeded. Escalation: **refine** if a
  reviewer requests a clearer structure.
- **`IDN.toASCII` throws on invalid input.** Hostnames that fail
  IDN validation will throw `IllegalArgumentException` from
  `canonicalizeHost`. This must propagate as a clean
  `SsrfPolicyException` at the wrapper's get() boundary, not a
  raw IAE leaking JDK internals. Wrap accordingly.
- **TTL cache concurrency for the Supplier wrapper.** If multiple
  threads race to refresh the cache, multiple JNI calls may run.
  Acceptable in v1 (the JNI call is cheap); `AtomicReference` +
  last-writer-wins is the recommended pattern.
- **The `pinSurvivesMixedCaseAndTrailingDot` test assumes the JDK
  invokes the resolver with SOME form derivable from the URL.**
  If JDK 25's HttpClient bypasses the resolver SPI for IP-literal
  hosts or short-circuits via cache in pathological ways, the
  test fixture may need adjustment. The seam's invocation count
  + recorded-host-arg captures whichever form actually fires.
  Escalation: **refine** if the JDK's actual normalization
  behavior is materially different from what the test asserts.

## Implementation outline (M1-026, generated by Plan subagent on 2026-05-15)

### Files to touch (5 of 5)
- modify: `infochat-ssrf/src/main/java/io/infochat/ssrf/SsrfGuardedHttpClient.java` — add `Duration bodyReadDeadline` constructor parameter with literal validation message; add package-private static `canonicalizeHost(String)` helper (IDN.toASCII + Locale.ROOT lowercase + single trailing-dot strip); invoke canonicalizeHost on initial-hop AND each redirect-hop pin install; refactor `get(uri)` lock-hold scope so `PinnedDnsResolver.Provider.lock()` is released BEFORE `readBounded` begins; extend `readBounded` with a TOTAL wall-clock deadline check raising `SsrfPolicyException("body read deadline exceeded ...")`; keep the M1-024 5-arg public constructor and the M1-025 6-arg public constructor as overloads that delegate to an enlarged internal form supplying a default `Duration.ofMinutes(2)` bodyReadDeadline (so RssFetcherTest's existing 5-arg call sites compile unchanged).
- modify: `infochat-ssrf/src/main/java/io/infochat/ssrf/PinnedDnsResolver.java` — in `lookupByName(host, lookupPolicy)` invoke `SsrfGuardedHttpClient.canonicalizeHost(host)` BEFORE `pins.get(canonicalHost)`. Provider/ForwardingResolver/lock/pin-slot shape unchanged.
- modify: `infochat-ssrf/src/main/java/io/infochat/ssrf/IpBlocklist.java` — add package-private constructor `IpBlocklist(Supplier<Set<InetAddress>> hostInterfacesProvider)`; preserve M1-025's `IpBlocklist(Set<InetAddress>)` as an overload that delegates as `this(() -> Set.copyOf(hostInterfaces))`; rewire the no-arg public constructor so the supplier defaults to `HostInterfaceSet::enumerate` (per-call enumeration, NOT one-shot snapshot); change `isBlocked(InetAddress)` to invoke `hostInterfacesProvider.get()` per call (optional short TTL cache <=5s via AtomicReference<CachedSet> is permitted by the DoD).
- modify: `infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java` — add new @Test `hostInterfaceAddedAfterStartupIsBlocked`. Preserve all M1-024/M1-025 existing tests.
- modify: `infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java` — add new @Test methods `dripBodyReadHitsTotalDeadline` and `pinSurvivesMixedCaseAndTrailingDot`; supply the new `bodyReadDeadline` argument on each pre-existing call site that uses the parameterized constructor (mechanical one-line edit per test — explicitly authorized in `test_plan.modifies`). Preserve all assertions on existing tests.

### Tests
- modify: `infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java` — covers F3 acceptance item:
  - `hostInterfaceAddedAfterStartupIsBlocked` — construct `IpBlocklist` via the Supplier overload with `AtomicReference<Set<InetAddress>>` initially `Set.of()`; assert `isBlocked(203.0.113.5)` false; mutate ref to `Set.of(InetAddress.getByName("203.0.113.5"))`; assert `isBlocked(...)` true on next call (if TTL cache, sleep > TTL — DoD permits 5s TTL with `Thread.sleep(6000)`).
- modify: `infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java` — covers F1 + F2 acceptance items:
  - `dripBodyReadHitsTotalDeadline` — in-process `HttpServer` writes Content-Length 5000000 then 1 byte every `readTimeout/4`; wrapper with `readTimeout=500ms`, `bodyReadDeadline=2s`; assert `SsrfPolicyException` whose message starts with `body read deadline exceeded`, AND elapsed wall-clock within `bodyReadDeadline + 1s` tolerance.
  - `pinSurvivesMixedCaseAndTrailingDot` — recording seam captures host arg; call `client.get(URI.create("http://EVIL.Example.test./..."))` against loopback `HttpServer`; seam returns `[127.0.0.1]`; assert HTTP 200 + seam invoked exactly once with canonical `"evil.example.test"`.
  - Pre-existing parameterized-constructor tests get an additional `bodyReadDeadline` argument (Duration value, e.g. `Duration.ofSeconds(5)`).

Test-modification authorization: confirmed in `test_plan.modifies` for both files.

### Cross-cutting concerns
- **Public API surface stability** — the M1-024 5-arg public constructor `SsrfGuardedHttpClient(IpBlocklist, Duration connect, Duration request, long bodyCap, int redirectCap)` and the M1-025 form must keep current signatures. `RssFetcherTest.java` calls the 5-arg form and must compile/pass unchanged. The new `bodyReadDeadline` parameter is added on an expanded internal constructor + the no-arg defaults; existing public overloads supply a default.
- **DRY canonicalization** — `canonicalizeHost` is ONE symbol shared by `SsrfGuardedHttpClient` (install) and `PinnedDnsResolver` (lookup). Package-private static on `SsrfGuardedHttpClient` is the form because both classes are in `io.infochat.ssrf`. Duplicated copies would fail the F2 remediation intent.
- **Lock-hold scope is load-bearing** — the redirect loop MUST stay inside the lock (each hop re-pins under lock), but `readBounded` MUST run outside the lock. Verifiable invariant: LAST `lock.unlock()` line < `readBounded(...)` line. Use try/finally so unlock + clear-pin run on exception paths as well.
- **IDN canonicalization order**: `IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)` FIRST, then `Locale.ROOT` lowercase, then single trailing-dot strip. Lowercasing punycode-encoded ASCII before `toASCII` can mis-handle pathological cases.
- **IDN.toASCII throws IAE** on invalid hosts — wrap cleanly so the wrapper's `get(uri)` boundary surfaces `SsrfPolicyException`, not raw IAE leaking JDK internals. Spec invariant: policy rejections flow through SsrfPolicyException.
- **Fail-closed determinism** — `IpBlocklist.isBlocked` is on every fetch's critical path. TTL cache, if implemented, must not lose addresses under contention; AtomicReference + last-writer-wins is acceptable.
- **Per-redirect-hop pin re-install** — spec §SSRF "DNS is re-resolved after every redirect (TOCTOU defense); the IP blocklist re-applies each hop". Each hop must invoke `canonicalizeHost`, resolve+validate, AND installPins fresh.
- **No LLM, SQL, audit, or user-facing surface touched** — no per-(user, scope) isolation concerns, no plain-text formatting concerns, no audit_log writes.

### Implementation order
1. Add `canonicalizeHost(String host)` package-private static to `SsrfGuardedHttpClient`. Pure utility, no callers yet — safe to land first.
2. In `PinnedDnsResolver.lookupByName`, invoke `SsrfGuardedHttpClient.canonicalizeHost(host)` then `pins.get(canonical)`. Pair with step 3.
3. In `SsrfGuardedHttpClient.get(uri)`, before each `installPins(Map.of(host, addrs))`, canonicalize the host. Apply on initial hop AND inside the redirect loop. Steps 2+3 must land atomically — keys must match.
4. Refactor `get(uri)` lock-hold scope: hold lock across redirect loop + terminal `httpClient.send`; clear pins + unlock BEFORE `readBounded`. Flag-tracked release pattern ensures cleanup on exception paths.
5. Extend parameterized constructor with `Duration bodyReadDeadline`; reject null/zero/negative with `IllegalArgumentException("body read deadline must be configured")`. M1-024 5-arg and M1-025 6-arg public constructors + no-arg public constructor supply a default (e.g., `Duration.ofMinutes(2)`). Store field.
6. Modify `readBounded`: capture `bodyReadStartNanos` before read loop; check elapsed vs `bodyReadDeadline` after each read; raise `SsrfPolicyException("body read deadline exceeded after Nms")` on breach. Per-read `readTimeout` watchdog unchanged.
7. In `IpBlocklist`: add Supplier-form package-private constructor; preserve M1-025 Set-form as overload delegating to Supplier; rewire no-arg public to `HostInterfaceSet::enumerate` (per-call); rewrite `isBlocked` to invoke supplier per call. Optional TTL cache.
8. Add `IpBlocklistTest.hostInterfaceAddedAfterStartupIsBlocked` using Supplier-form + `AtomicReference` mutation.
9. Add `SsrfGuardedHttpClientTest.dripBodyReadHitsTotalDeadline` — patterned on M1-025's `bodyReadTimeoutFiresOnSlowUpstream` but each read returns within per-read window, so TOTAL deadline is what fires.
10. Add `SsrfGuardedHttpClientTest.pinSurvivesMixedCaseAndTrailingDot` with recording seam capturing host arg; verify 200 + exactly-once seam invocation with canonical host.
11. Update pre-existing parameterized-constructor test call sites to supply new `bodyReadDeadline` argument (mechanical edits; values large enough not to fire, e.g., `Duration.ofSeconds(5)`).
12. `mvn -B -pl infochat-ssrf test` first; then `mvn -B clean verify` from repo root. `RssFetcherTest.java` must pass unchanged.

Rationale on ordering: step 1 (pure helper) before 2-3 (depend on it). Steps 2+3 atomic — pinned-key mismatch otherwise breaks existing tests. Step 5 (new param) before step 6 (consumer). Steps 7-11 independent of the SsrfGuardedHttpClient refactor — any order. Step 12 verification gate.

### Risks
- **Lock-release refactor changes `get()` control flow substantially.** The new shape needs the loop inside the lock, the terminal hop's body-read outside, AND clean lock release on exception paths. If the reviewer finds the resulting try/finally hard to read, expect REWORK. Escalation: **refine**.
- **IDN.toASCII throws on invalid input.** If raised before existing scheme/userinfo gates, the message will be raw JDK text. Mitigation: invoke canonicalizeHost AFTER scheme/userinfo gates but BEFORE pin install + seam.apply; catch IAE in canonicalizeHost and re-throw as `SsrfPolicyException`. Escalation: **refine**.
- **TTL cache concurrency** if implemented — multiple threads racing → multiple JNI calls. Acceptable per spec. Test flake risk if sleep < TTL. Mitigation: skip the cache OR sleep 6s in `hostInterfaceAddedAfterStartupIsBlocked` (DoD permits this).
- **`pinSurvivesMixedCaseAndTrailingDot` relies on JDK invoking the resolver SPI at least once.** If JDK 25's `HttpClient` short-circuits via internal name cache between back-to-back tests, the seam might not record a call. Mitigation: fresh hostname per test (mixed-case + trailing-dot is a different string from M1-025's pins) + fresh `HttpClient` per `get()` (already true). Escalation: **refine** if observed.
- **Files budget exactly 5; no room for a sibling utility class** for `canonicalizeHost`. Package-private static on existing class uses 0 extra files. The DoD permits a sibling class but the static-on-existing form is preferred at this budget.
- **Two consecutive REWORKs would exceed `round_cap: 3`.** If lock-release refactor and canonicalization wiring are both rejected on round 1, round 2 must shrink and address both. Do not bundle additional refactors mid-round.

### Out-of-scope (echoed from ticket)
- any change to the `io.infochat.core.ingest.Fetcher` SPI or `NormalizedPost` record
- any change to `RssFetcher.java` or `RssFeedParser.java` — wrapper's public surface preserved
- any change to `RssFetcherTest.java`
- any change to `HostInterfaceSet.java` — enumerator shape unchanged
- any change to `META-INF/services/java.net.spi.InetAddressResolverProvider`
- any change to `UrlRedactor.java` or `SsrfPolicyException.java`
- any WebSocket / ws / wss wrapping (NostrStreamSource ticket)
- any Provider-side `/add-source` HEAD/GET probe wiring
- any FetchScheduler / `@Scheduled` / per-tick cadence selection (T1-C territory)
- any outbox sink, RAW-row INSERT, post-table write, OutboxRehydrator, LISTEN/NOTIFY new_post, provider_state, or NewPostReconciler wiring (T1-C)
- any Stage 1 HTML sanitization, NFKC normalization, regex redaction, or canonical-body UID hashing (T1-D)
- any source-row UPDATE for last_fetch_at / last_success_at / consecutive_failures
- any retry / backoff / Retry-After / per-source politeness window
- any change to V1..V8 Flyway migrations (`migration_touch: false`)
- any LLM tool surface, prompt-injection sanitizer, or LLM-output redactor
- any audit_log write or AuditLogger code path
- any port-based filtering (OUT-OF-MODEL per M1-024 redteam)
- any operator-facing knob to disable host-interface enumeration, DNS pinning, or the new body-read deadline
