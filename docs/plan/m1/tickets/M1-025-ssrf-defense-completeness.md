---
id: M1-025
title: infochat-ssrf hardening (M1-024 remediation)
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
      files: 9
      added: 932
      removed: 121
revisions:
  - date: 2026-05-15
    reason: |
      budget-breach refine (pre-impl): the JDK 25 InetAddressResolverProvider
      SPI does NOT support per-HttpClient or per-instance scoping; it loads
      ONE resolver per JVM via ServiceLoader, requiring a resource at
      META-INF/services/java.net.spi.InetAddressResolverProvider. Without
      that resource the PinnedDnsResolver class is unreachable code (the
      JDK HttpClient never calls it). The Plan subagent's outline flagged
      this as Risk 1. Refinement: bump files_budget 6→7 to accommodate
      the SPI resource, add it to files_scope, and rewrite acceptance
      items 9/10 to reflect the actual JDK SPI shape (JVM-wide
      installation + process-level lock that serializes wrapper calls
      so per-call pinning behavior is preserved without concurrent
      pin-leakage between calls).
    prior:
      files_budget: 6
      files_scope:
        - infochat-ssrf/src/main/java/io/infochat/ssrf/IpBlocklist.java
        - infochat-ssrf/src/main/java/io/infochat/ssrf/SsrfGuardedHttpClient.java
        - infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java
        - infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java
        - infochat-ssrf/src/main/java/io/infochat/ssrf/HostInterfaceSet.java
        - infochat-ssrf/src/main/java/io/infochat/ssrf/PinnedDnsResolver.java
      acceptance_item_9_excerpt: |
        "... and exposes a constructor accepting a Map<String, List<InetAddress>>
         of pinned hostname→IPs. lookupByName(host, lookupPolicy) returns the
         pinned IPs for hosts in the map and DELEGATES to the platform default
         for any unmapped host (the platform default is captured at construction
         via InetAddressResolverProvider's Configuration.builtinResolver())."
      acceptance_item_10_excerpt: |
        "... Implementation: install a PinnedDnsResolver for the duration of
         the get(uri) call (per-call HttpClient instance constructed inside
         get, with the resolver scoped to that instance — InetAddressResolverProvider
         supports per-instance scoping via the resolver-provider SPI). The
         pinning re-applies on each redirect hop (re-resolve + re-pin)."
escalations:
  - date: 2026-05-15
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (pre-implementation escalation). Developer triaged the
      InetAddressResolverProvider SPI mechanism against JDK 25 before
      writing any code and surfaced that the per-instance scoping the
      acceptance items 9-10 describe is not supported by the JDK:
      the SPI loads ONE provider per JVM via
      ServiceLoader.load(InetAddressResolverProvider.class), which
      requires a resource at
      META-INF/services/java.net.spi.InetAddressResolverProvider
      listing the provider class. HttpClient.Builder has no
      .resolver(...) setter; there is no programmatic per-HttpClient
      scoping. Making the wrapper's DNS pin actually affect
      HttpClient.send() requires:
        - one new resource file:
          infochat-ssrf/src/main/resources/META-INF/services/
            java.net.spi.InetAddressResolverProvider
        - the provider class nested inside PinnedDnsResolver.java
          (no new top-level Java file)
      The Plan subagent's outline flagged exactly this as Risk 1.
      The resource file is outside files_scope and would push the
      file count to 7 (budget: 6).
clarity_check:
  date: 2026-05-15
  verdict: WARN
  warnings:
    - "FORWARD-REFERENCE-CHECK: M1-026 is referenced in the \"Alternatives considered\" section as a contingent decomposition target (\"split off Finding 2 into M1-026\") but no M1-026 ticket file exists under docs/plan/m1/tickets/. This is a prose-only reference in an alternatives note (not load-bearing frontmatter), so it does not block start. If the contingency materializes during implementation and the developer actually needs to decompose, a ticket should be filed at that point. No action required before /m1-tick start."
  blockers: []
redteam_findings:
  - date: 2026-05-15
    category: DOS
    severity: high
    promise: |
      "Redirect, body-size, connect-timeout, and read-timeout caps are
      enforced; an unset timeout is a configuration error." (security.md
      §SSRF and outbound connections). The companion expectation,
      established by M1-024 redteam Finding 4 that this ticket
      explicitly remediates (acceptance items 13–15 — "the redteam REPRO
      showed 10 MiB at 1 byte/min ≈ 19 years of held thread"), is that
      a slow-drip upstream cannot indefinitely hold a fetcher.
    gap: |
      infochat-ssrf/src/main/java/io/infochat/ssrf/SsrfGuardedHttpClient.java
      `readBounded`. The watchdog times each individual `in.read(buf)`
      call against `readTimeout` (default 30 s). It does NOT bound the
      total wall-clock duration of the body-read phase. Each fresh read
      iteration restarts the 30 s window, so an attacker who delivers a
      single byte just before each 30 s tick keeps `in.read` returning
      `n=1` repeatedly and the watchdog never fires. Worse,
      `SsrfGuardedHttpClient.get(uri)` holds
      `PinnedDnsResolver.Provider.lock()` (a JVM-wide ReentrantLock) for
      the entire call, including the streaming body read. Per the diff's
      own commentary "Concurrent wrapper calls serialize on the lock", so
      a single hostile RSS feed dribbling bytes also blocks every other
      outbound fetch (Collector RSS, Provider `/add-source` HEAD/GET
      probes) JVM-wide for the drip's duration.
    repro: |
      1) Attacker controls an RSS feed (or any URL the Collector is
         configured to fetch).
      2) Server responds 200 with Content-Length: 10485760 (or chunked),
         sends headers immediately, then writes exactly 1 byte every
         29 seconds.
      3) `in.read(buf)` returns n=1 on every call (well under the 30 s
         watchdog), `total` grows by 1, the 10 MiB cap is never reached.
      4) The pinning ReentrantLock stays held; the math is
         10 MiB × 29 s = ~9.6 years per hostile feed, during which every
         other `SsrfGuardedHttpClient.get(...)` invocation across the
         Collector + Provider blocks on `lock.lock()`. A single malicious
         feed → full outbound-HTTP DoS for both services until process
         restart. The existing `bodyReadTimeoutFiresOnSlowUpstream` test
         only exercises the "zero bytes for >readTimeout" case (test
         fixture writes 16 bytes then sleeps), so the drip vector has no
         regression coverage either.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-05-15
    category: INFO-LEAK
    severity: medium
    promise: |
      "DNS is re-resolved after every redirect (TOCTOU defense); the IP
      blocklist re-applies each hop." plus the ticket's own remediation
      that validate-time and connect-time DNS results are now provably
      identical (the within-hop DNS-rebind window from M1-024 Finding 2,
      security.md §SSRF and outbound connections).
    gap: |
      infochat-ssrf/src/main/java/io/infochat/ssrf/SsrfGuardedHttpClient.java
      and PinnedDnsResolver.java `pins.get(host)`. The pin map is keyed
      by `current.getHost()` — the host string as parsed by java.net.URI,
      which preserves the case present in the URL text. The JVM-wide
      forwarding resolver looks the host up in that map with
      `Map<String,List<InetAddress>>.get(host)`, an equals-based exact-
      match. If the JDK's HttpClient.send invokes the resolver with a
      normalized form of the host (case-folded, trailing dot, IDN/
      punycode transform) that differs from what URI.getHost() returned,
      `pins.get(host)` returns null and ForwardingResolver falls through
      to `BUILTIN.lookupByName(host, …)` — the real DNS, with no
      IpBlocklist re-check. A DNS-rebind adversary controlling the
      authoritative nameserver can return a public IP at validate-time
      and a blocked/private IP on the JDK's connect-time lookup, exactly
      the scenario the pinning was added to defeat.
    repro: |
      1) Attacker registers `Evil.Example.COM` (mixed case) and
         configures the authoritative DNS to return 203.0.113.5 for the
         first lookup and 192.168.1.1 within the same window.
      2) Operator adds `http://Evil.Example.COM/feed` via /add-source.
      3) Wrapper's seam resolves the host to [203.0.113.5], passes
         IpBlocklist, installs pin under key "Evil.Example.COM".
      4) JDK HttpClient internally lowercases the host (per common HTTP/
         DNS normalization conventions) and calls the resolver with
         "evil.example.com". `pins.get("evil.example.com") == null` →
         falls through to BUILTIN → real DNS returns 192.168.1.1 →
         connect lands on the internal target. The pinning never fires,
         no log distinguishes the miss from a normal lookup. Whether
         JDK 25 actually lowercases on the resolver call is
         implementation-defined; the diff has no test asserting that the
         JDK and URI.getHost() agree on form, so the defense is
         contingent on undocumented internals. The same hazard applies
         to trailing-dot variants and IDN ↔ punycode conversion.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-05-15
    category: INFO-LEAK
    severity: low
    promise: |
      "DNS-resolved IPs are checked against a blocklist of private,
      loopback, link-local, multicast, CGNAT, and cloud-metadata ranges
      (notably 169.254.169.254 and IPv6 equivalents) plus the host's
      own non-loopback interfaces." (security.md §SSRF and outbound
      connections — present tense, no "as of JVM start" qualifier).
    gap: |
      infochat-ssrf/src/main/java/io/infochat/ssrf/IpBlocklist.java and
      HostInterfaceSet.java. The no-arg IpBlocklist() constructor calls
      HostInterfaceSet.enumerate() once at construction time and stores
      the result in a final Set<InetAddress> field. Interfaces that
      come up after the Collector / Provider start — VPN tunnels
      established after JVM start, container bridges added by a docker
      daemon restart, hot-plugged NICs, Kubernetes sidecar IPs assigned
      post-pod-init — are never added to the set, so a feed URL that
      resolves to one of those post-startup IPs passes the blocklist.
      The diff's own javadoc acknowledges this (snapshot intentionally
      captures interfaces at JVM start; a cloud VM whose IPs change
      after startup is treated as an out-of-scope refresh-cadence
      concern) — but the spec text the ticket cites does not carve out
      a "snapshot at startup" qualifier, so this is the spec-promise-vs-
      delivery gap the redteam is meant to surface.
    repro: |
      1) Operator runs Collector on a host whose primary network
         presence at JVM start is the public NIC 203.0.113.5.
      2) Operator subsequently attaches a freshly-allocated EIP
         203.0.113.99 post-startup.
      3) Adversary adds an RSS feed whose authoritative DNS returns
         203.0.113.99 (the host's own current public IP).
      4) IpBlocklist does NOT block it because HostInterfaceSet.enumerate()
         ran before the EIP was attached. Routing a feed URL to the
         host's own current public IP can side-step a perimeter / firewall
         rule that filters loopback-to-self traffic differently from
         external-to-self traffic, which is exactly the bypass the spec's
         "host's own non-loopback interfaces" clause was added to prevent
         (M1-024 Finding 1 framing).
    suggested_fix_class: trust-boundary-tightening
redteam_out_of_model:
  - date: 2026-05-15
    note: |
      The per-call Executors.newSingleThreadExecutor + readFuture.cancel(true)
      strategy assumes the JDK HttpClient body InputStream honors thread
      interrupts and closes the underlying socket. If the read is blocked
      in native socket I/O on certain JDK builds, shutdownNow() will not
      actually free the thread; orphan reader threads accumulate one per
      hostile-fetch event. The diff itself flags this in the ticket body's
      Risks section. Not a documented spec promise, so advisory only — if
      the high-severity DoS finding above is accepted, the orphan-thread
      compounding effect is the operational manifestation operators will
      observe.
blocked_by:
  - M1-024
remediates: M1-024
files_budget: 7
files_scope:
  - infochat-ssrf/src/main/java/io/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/main/java/io/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java
  - infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java
  - infochat-ssrf/src/main/java/io/infochat/ssrf/HostInterfaceSet.java
  - infochat-ssrf/src/main/java/io/infochat/ssrf/PinnedDnsResolver.java
  - infochat-ssrf/src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - any change to the `io.infochat.core.ingest.Fetcher` SPI or `NormalizedPost` record (unchanged from M1-023/M1-024)
  - any change to `RssFetcher.java` or `RssFeedParser.java` — the wrapper's PUBLIC API surface (`new SsrfGuardedHttpClient()` no-arg constructor + `HttpResponse<byte[]> get(URI)`) is preserved; only the wrapper's internals change. RssFetcher continues to compile against the unchanged surface
  - any change to `RssFetcherTest.java` — the existing test-mode IpBlocklist override seam continues to work; no new test surface is consumed there
  - any WebSocket / ws / wss wrapping (NostrStreamSource ticket — that ticket consumes the same `IpBlocklist` and the same DNS-pinning seam authored here)
  - any Provider-side `/add-source` HEAD/GET probe wiring (separate later ticket; the hardened wrapper is consumed by that ticket as `new SsrfGuardedHttpClient(...).get(uri)`)
  - any FetchScheduler / `@Scheduled` / per-tick cadence selection (T1-C territory)
  - any outbox sink, RAW-row INSERT, post-table write, OutboxRehydrator, LISTEN/NOTIFY new_post, provider_state high-water-mark, or NewPostReconciler wiring (T1-C)
  - any Stage 1 HTML sanitization, NFKC normalization, regex redaction, or canonical-body UID hashing (T1-D)
  - any source-row UPDATE for last_fetch_at / last_success_at / consecutive_failures (D42's per-source failure-counter model is the FetchScheduler's responsibility)
  - any retry / backoff / Retry-After / per-source politeness window (FetchScheduler boundary)
  - any change to V1..V8 Flyway migrations (migration_touch: false; this ticket is impl-only)
  - any LLM tool surface, prompt-injection sanitizer, or LLM-output redactor
  - any audit_log write or AuditLogger code path
  - any port-based filtering (out-of-model per the M1-024 redteam OUT-OF-MODEL note; a separate spec-amend ticket would be required to commit to it)
  - any operator-facing knob to disable host-interface enumeration or DNS pinning (the spec's allowlist is "not user-configurable" — security.md §SSRF and outbound connections; tests use the explicit constructor seam, not a flag)
acceptance:
  # ----------------------------------------------------------------------
  # Finding 1 (M1-024 redteam, INFO-LEAK / high) — host's own non-loopback
  # interfaces. Spec verbatim (security.md §SSRF and outbound connections):
  #   "DNS-resolved IPs are checked against a blocklist of private,
  #    loopback, link-local, multicast, CGNAT, and cloud-metadata ranges
  #    (notably 169.254.169.254 and IPv6 equivalents) **plus the host's
  #    own non-loopback interfaces**."
  # M1-024 acceptance item 6 implemented the first part; this remediation
  # adds the dropped clause.
  # ----------------------------------------------------------------------
  - "A new pure-data class `io.infochat.ssrf.HostInterfaceSet` exists at infochat-ssrf/src/main/java/io/infochat/ssrf/HostInterfaceSet.java, declares `public final class HostInterfaceSet`, and exposes `public static Set<InetAddress> enumerate()` which walks `NetworkInterface.getNetworkInterfaces()` and returns every non-loopback `InetAddress` bound to any interface — grep -E 'NetworkInterface\\.getNetworkInterfaces' HostInterfaceSet.java returns at least one match AND grep -E 'isLoopbackAddress' HostInterfaceSet.java returns at least one match (the loopback filter is the explicit exclusion)"
  - "IpBlocklist's no-arg constructor invokes `HostInterfaceSet.enumerate()` AT CONSTRUCTION TIME (not per-call) and stores the result in a final field; `isBlocked(addr)` returns true if `addr.equals(<any element of the host-IP set>)` — grep -E 'HostInterfaceSet\\.enumerate' IpBlocklist.java returns at least one match"
  - "IpBlocklist exposes a package-private constructor (or static factory) accepting an explicit `Set<InetAddress>` for the host-IP set so tests can supply a deterministic enumeration without depending on the test machine's network configuration. The constructor signature must not be reachable via the public no-arg constructor — production callers always use the no-arg form (M1-024's `IpBlocklist override is an API surface, not a flag` discipline). grep -E '(Set<InetAddress>|Collection<? extends InetAddress>)' IpBlocklist.java returns at least one match"
  - "IpBlocklistTest adds at least 2 distinct @Test methods covering the host-interface seam: (a) `hostInterfaceIpIsBlocked` — construct IpBlocklist with host-IP set `{InetAddress.getByName(\"203.0.113.5\")}` (TEST-NET-3, RFC 5737), assert `isBlocked(InetAddress.getByName(\"203.0.113.5\"))` returns true; (b) `nonHostPublicIpStillAllowed` — using the same constructor with the same `{203.0.113.5}` host set, assert `isBlocked(InetAddress.getByName(\"8.8.8.8\"))` returns false (the seam adds host IPs without affecting the public-IP allowlist)"

  # ----------------------------------------------------------------------
  # Finding 3 (M1-024 redteam, INFO-LEAK / medium) — loopback bypass
  # forms. Spec lists "loopback" as a blocked category. On
  # Linux/BSD/Windows the kernel rewrites connect(0.0.0.0) -> connect(127.0.0.1),
  # so 0.0.0.0 is a loopback bypass; the spec's "loopback" intent must
  # cover its bypass forms.
  # ----------------------------------------------------------------------
  - "IpBlocklist.isBlocked returns true for `InetAddress.getByName(\"0.0.0.0\")` — the IPv4 unspecified / 'this host' address, RFC 1122. Implementation: extend `isBlockedV4` with the explicit byte-pattern check `(b0 == 0)` for the `0.0.0.0/8` range (every IPv4 address whose first byte is zero), OR a check for `InetAddress.isAnyLocalAddress()` evaluated AFTER the host-interface check"
  - "IpBlocklist.isBlocked returns true for `InetAddress.getByName(\"::\")` — the IPv6 unspecified address. Implementation: extend `isBlockedV6` with the explicit byte-pattern check for the all-zero IPv6 address, OR a check for `addr.isAnyLocalAddress()`"
  - "IpBlocklist.isBlocked returns true for `InetAddress.getByName(\"255.255.255.255\")` — the IPv4 limited broadcast address, RFC 919. Implementation: byte-pattern check `(b0 == (byte)0xFF && b1 == (byte)0xFF && b2 == (byte)0xFF && b3 == (byte)0xFF)`, OR a check for `addr.isAnyLocalAddress() || ((Inet4Address) addr).getAddress() equals byte[]{-1,-1,-1,-1}`"
  - "IpBlocklistTest adds at least 3 distinct @Test methods, one per bypass form: `unspecifiedV4IsBlocked`, `unspecifiedV6IsBlocked`, `limitedBroadcastIsBlocked`. Each asserts `isBlocked()` returns true for the named address (constructed via `InetAddress.getByName` to exercise the same parser path operator-supplied URLs would hit)"

  # ----------------------------------------------------------------------
  # Finding 2 (M1-024 redteam, INFO-LEAK / high) — within-hop DNS TOCTOU
  # / DNS-rebind defense. Spec verbatim (security.md §SSRF and outbound
  # connections, paragraph 1):
  #   "Both services use the **same shared library module** (`infochat-ssrf`)
  #    which carries the IP blocklist, **DNS-rebind defense**, redirect
  #    cap, and timeout caps"
  # The spec also says (verbatim):
  #   "DNS is re-resolved after every redirect (TOCTOU defense); the IP
  #    blocklist re-applies each hop."
  # M1-024 implemented per-redirect re-resolution but left the within-hop
  # TOCTOU window open: validate-time `InetAddress.getAllByName` returned
  # one set, then `httpClient.send` performed an INDEPENDENT JDK lookup
  # for the actual TCP connect. The remediation pins the validation-time
  # IPs for the duration of the connect.
  # ----------------------------------------------------------------------
  - "A new class `io.infochat.ssrf.PinnedDnsResolver` exists at infochat-ssrf/src/main/java/io/infochat/ssrf/PinnedDnsResolver.java, implements `java.net.spi.InetAddressResolver` (Java 18+; project is on JDK 25 per CLAUDE.md/Stack), and exposes a constructor accepting a `Map<String, List<InetAddress>>` of pinned hostname→IPs plus an `InetAddressResolver` delegate (the platform default). `lookupByName(host, lookupPolicy)` returns the pinned IPs for hosts in the map and DELEGATES to the supplied platform default for any unmapped host. A nested `public static final class` within the same file extends `java.net.spi.InetAddressResolverProvider`; its `get(Configuration)` captures `configuration.builtinResolver()` and returns a JVM-wide forwarding resolver that consults a static, lock-guarded pin slot. The provider is registered for ServiceLoader discovery via the resource file `infochat-ssrf/src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider` (single line: the FQN of the nested provider class), so the JDK loads it at JVM startup and routes every `InetAddress.getAllByName` call through it. grep -E 'implements InetAddressResolver' PinnedDnsResolver.java returns at least one match AND grep -E 'extends InetAddressResolverProvider' PinnedDnsResolver.java returns at least one match"
  - "SsrfGuardedHttpClient.get(uri) pins the DNS resolution per call: the IP set returned by the wrapper's validation-time `InetAddress.getAllByName(host)` is the SAME set used by the JDK HttpClient for the actual TCP connection. The wrapper does NOT allow JDK HttpClient to perform an INDEPENDENT DNS lookup between validate and send. Implementation: because the JDK 25 `InetAddressResolverProvider` SPI loads ONE resolver per JVM at startup (no per-HttpClient scoping), per-call effect is achieved by (a) the JVM-wide forwarding resolver installed via the META-INF/services registration above, (b) a static pin slot guarded by a JVM-wide lock that the wrapper acquires for the duration of the `get(uri)` call, and (c) the wrapper constructing a per-call `HttpClient` inside `get`, setting the pin map for `uri.getHost()`, invoking `send`, then clearing the pin and releasing the lock in a `finally` block. Concurrent wrapper calls serialize on the lock — at most one pin is active per JVM at any moment, so concurrent calls cannot cross-leak pins. The pinning re-applies on each redirect hop: the manual redirect loop, still holding the lock, re-resolves the new target host, re-checks against IpBlocklist, and REPLACES the pin map entry before sending the next hop"
  - "SsrfGuardedHttpClient exposes a package-private constructor (or test-only static factory) accepting an injectable resolver-seam — `Function<String, List<InetAddress>>` — so tests can supply a deterministic `getAllByName` replacement without installing a JVM-global resolver. Production callers always use the no-arg constructor which uses `InetAddress::getAllByName` directly (the constructor-parameter override discipline M1-024 established)"
  - "SsrfGuardedHttpClientTest adds a @Test method `connectUsesValidationTimeIpsNotFreshLookup`: the resolver-seam returns `[127.0.0.1]` on the first invocation and `[<an IP no-one is listening on, e.g. 192.0.2.1 (TEST-NET-1)>]` on the second invocation for the same hostname. The test fixture is the in-process HttpServer bound to 127.0.0.1 + the test-mode IpBlocklist that permits 127.0.0.1 (per M1-024's existing carve-out). Assert: the wrapper's `get` succeeds against the test server (proving the connect went to the FIRST resolution's IP), NOT a connection-refused/timeout against 192.0.2.1 (which would prove the JDK did its own fresh lookup). At least one `assertEquals(200, response.statusCode())` and one assertion that the resolver-seam was invoked exactly once (not twice — the second invocation would indicate the JDK re-resolved)"

  # ----------------------------------------------------------------------
  # Finding 4 (M1-024 redteam, DOS / medium) — slow-loris on body read.
  # Spec verbatim (security.md §SSRF and outbound connections):
  #   "Redirect, body-size, **connect-timeout, and read-timeout caps**
  #    are enforced; an unset timeout is a configuration error."
  # M1-024 satisfied "read-timeout" by the WORD via JDK
  # `HttpRequest.timeout()`, which despite being NAMED a "request timeout"
  # only governs receipt of response HEADERS, not per-byte body reads.
  # The remediation enforces a per-read wall-clock timeout on the body
  # InputStream so the BEHAVIOR matches the spec, not just the word.
  # ----------------------------------------------------------------------
  - "SsrfGuardedHttpClient's constructor accepts a `Duration readTimeout` parameter (separate from `requestTimeout`); the constructor REJECTS null, zero, or negative `readTimeout` with `IllegalArgumentException` whose message starts with the literal substring `read timeout must be configured` (mirroring M1-024's `timeout must be configured` discipline for connect/request timeouts). grep -E 'read timeout must be configured' SsrfGuardedHttpClient.java returns at least one match"
  - "SsrfGuardedHttpClient.readBounded() enforces a per-read wall-clock timeout on the body `InputStream`: each individual `in.read(buf)` call must return within `readTimeout`; if it does not, `readBounded` raises `SsrfPolicyException` with message starting with the literal substring `body read timeout`. The timeout governs each individual read invocation, NOT the total body-read duration. Implementation: wrap the body stream with a watchdog that interrupts the read (e.g., `socket.setSoTimeout((int) readTimeout.toMillis())` applied to the underlying socket via the JDK HttpClient internals if available, OR a `CompletableFuture.supplyAsync(... read ...).get(readTimeout)` per-read wrapper, OR a custom `InputStream` decorator that schedules an interrupt). grep -E 'body read timeout' SsrfGuardedHttpClient.java returns at least one match"
  - "SsrfGuardedHttpClientTest adds a @Test method `bodyReadTimeoutFiresOnSlowUpstream`: an in-process HttpServer fixture (`com.sun.net.httpserver.HttpServer` with a custom handler) writes `Content-Length: 5000000`, the 200 OK headers, and 16 bytes immediately, then sleeps `readTimeout * 2` (e.g., readTimeout=1s, sleep=2s) before writing more bytes. The wrapper is constructed with the test-mode IpBlocklist + an explicit `readTimeout=1s`. Assert: `assertThrows(SsrfPolicyException.class, () -> client.get(uri))` AND `assertTrue(thrown.getMessage().startsWith(\"body read timeout\"))` AND `assertTrue(elapsed < (readTimeout.toMillis() + 500))` (the assertion fires within readTimeout + 500ms tolerance, NOT after the whole `requestTimeout` would have completed)"

  # ----------------------------------------------------------------------
  # Build + cross-cut
  # ----------------------------------------------------------------------
  - "mvn -B -pl infochat-ssrf test exits 0; the new test methods (≥2 host-interface, ≥3 bypass-form, ≥1 DNS-pinning, ≥1 body-read-timeout) execute and pass alongside M1-024's existing IpBlocklistTest / SsrfGuardedHttpClientTest / UrlRedactorTest classes"
  - "mvn -B clean verify from the repo root exits 0; M1-024's RssFetcherTest continues to pass UNCHANGED (the wrapper's public API surface — `new SsrfGuardedHttpClient()` no-arg + `HttpResponse<byte[]> get(URI)` — is preserved; this ticket only adds internal pinning + adds the constructor `Duration readTimeout` parameter, which the no-arg constructor supplies a sensible default for so RssFetcher's existing call site is unaffected)"
  - "All four M1-024 redteam findings are addressed: Finding 1 (host's own non-loopback interfaces / INFO-LEAK / high) by the host-interface enumeration acceptance items above; Finding 2 (within-hop DNS TOCTOU / INFO-LEAK / high) by the PinnedDnsResolver + connectUsesValidationTimeIpsNotFreshLookup test; Finding 3 (loopback bypass forms / INFO-LEAK / medium) by the 0.0.0.0/::255.255.255.255 acceptance items + tests; Finding 4 (slow-loris on body read / DOS / medium) by the readTimeout constructor parameter + readBounded watchdog + bodyReadTimeoutFiresOnSlowUpstream test"
test_plan:
  adds:
    - infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java — adds at least 5 new @Test methods (2 host-interface + 3 bypass-form). Existing @Test methods preserved unchanged.
    - infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java — adds at least 2 new @Test methods (connectUsesValidationTimeIpsNotFreshLookup, bodyReadTimeoutFiresOnSlowUpstream). Existing @Test methods preserved (the new readTimeout constructor parameter is supplied with a default in the no-arg constructor, so existing tests using the no-arg form are unaffected; tests that already construct via the package-private parameterized constructor get the new parameter explicitly).
  modifies:
    - infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java — files appears in both `adds` and `modifies` because new @Test methods land alongside preserved ones; the file is modified, not created.
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

# M1-025: infochat-ssrf hardening (M1-024 remediation)

## Context

M1-024 landed the `infochat-ssrf` shared module and routed `RssFetcher`
through it, closing the SSRF gate that M1-023's RssFetcher had left
open as a documented carve-out. The `/redteam M1-024` audit
(2026-05-15, recorded in `redteam_findings:` on the M1-024 ticket and
in commit `c3559b5`) surfaced four gaps in the new defense surface:

1. **INFO-LEAK / high — host's own non-loopback interfaces.** The
   spec says "DNS-resolved IPs are checked against a blocklist of
   private, loopback, link-local, multicast, CGNAT, and cloud-metadata
   ranges (notably `169.254.169.254` and IPv6 equivalents) **plus
   the host's own non-loopback interfaces**." M1-024 acceptance item 6
   enumerated specific byte-token ranges (RFC1918, CGNAT, link-local,
   etc.) but dropped the "plus the host's own non-loopback interfaces"
   clause. An attacker can route a feed URL to the box's own public
   IP, bypassing perimeter filtering.

2. **INFO-LEAK / high — within-hop DNS TOCTOU.** The spec promises a
   "DNS-rebind defense" and says "DNS is re-resolved after every
   redirect (TOCTOU defense); the IP blocklist re-applies each hop."
   M1-024 implemented per-redirect re-resolution but left the
   within-hop window open: `validate()`'s `InetAddress.getAllByName`
   returns one set, then `httpClient.send` performs an INDEPENDENT
   JDK DNS lookup for the actual TCP connect. A DNS-rebind attacker
   can return `8.8.8.8` to the validate query and `169.254.169.254`
   to the connect query for the same hostname.

3. **INFO-LEAK / medium — loopback bypass forms.** The spec lists
   "loopback" as a blocked category. M1-024's `IpBlocklist` enumerates
   the literal loopback range `127.0.0.0/8` but does NOT block
   `0.0.0.0/8` (IPv4 unspecified — kernel rewrites `connect(0.0.0.0)`
   to `connect(127.0.0.1)`), `::` (IPv6 unspecified), or
   `255.255.255.255` (IPv4 limited broadcast). The spec's "loopback"
   intent must cover its kernel-level bypass forms.

4. **DOS / medium — slow-loris on body read.** The spec says
   "Redirect, body-size, connect-timeout, and **read-timeout** caps
   are enforced; an unset timeout is a configuration error." M1-024
   satisfied "read-timeout" by name via JDK `HttpRequest.timeout()`,
   which despite being NAMED a "request timeout" only governs receipt
   of response HEADERS — not per-byte body reads. A malicious feed
   that dribbles bytes one per minute holds a fetcher thread for
   ~19 years (within the body cap).

This ticket is the **single remediation ticket** for all four findings
(per the operator's M1-024 Option-A precedent). The lesson saved to
Claude memory after M1-024
(`feedback_acceptance_transcribe_spec_promises`) drove the acceptance-
criteria style here: every separable spec sentence is transcribed
verbatim into its own runnable check, and acceptance items that test
behavior against a JDK API name the BEHAVIOR (e.g., "per-read
wall-clock timeout") rather than just the API ("readTimeout").

M1-024's commit (`1fb798c`) is **immutable** per `CLAUDE.md` §M1
workflow — "Never amend a passed commit." This ticket lands the
remediation as a fresh `M1-025:` commit with frontmatter
`remediates: M1-024` so the lineage is mechanically traceable
(`grep -nE '^remediates: M1-024$' docs/plan/m1/tickets/M1-025-*.md`
returns the link).

## Definition of Done

### `io.infochat.ssrf.HostInterfaceSet` (NEW)

A pure-data class with one public method:

```java
public static Set<InetAddress> enumerate()
```

Walks `NetworkInterface.getNetworkInterfaces()` once, collects every
non-loopback `InetAddress` bound to any interface, and returns the
set. The class is `final`, has a private constructor (utility class),
and is thread-safe (the set is built once and never mutated).
`isLoopbackAddress()` filtering is explicit (the spec says "non-
loopback", and the M1-024 IpBlocklist already covers the literal
loopback range).

### `io.infochat.ssrf.IpBlocklist` (MODIFIED)

- The no-arg constructor invokes `HostInterfaceSet.enumerate()` AT
  CONSTRUCTION TIME (not per-call) and stores the result in a final
  `Set<InetAddress>` field.
- `isBlocked(InetAddress addr)` first checks the host-IP set
  (`addr.equals(<any element>)`), then falls through to the existing
  range-byte checks plus the new bypass-form checks.
- A package-private constructor (or static factory)
  `IpBlocklist(Set<InetAddress> hostInterfaces)` accepts an explicit
  host-IP set so tests can supply a deterministic enumeration.
  Production callers always use the no-arg form (M1-024's
  "API surface, not a flag" discipline).
- `isBlockedV4` extends with `0.0.0.0/8` (every IPv4 address whose
  first byte is zero) and `255.255.255.255` (the all-ones address).
- `isBlockedV6` extends with `::` (the all-zero IPv6 address).

### `io.infochat.ssrf.PinnedDnsResolver` (NEW)

Implements `java.net.spi.InetAddressResolver` (Java 18+; this project
runs on JDK 25 per CLAUDE.md/Stack). Constructor accepts a
`Map<String, List<InetAddress>>` of pinned hostname→IPs plus an
`InetAddressResolver delegate` (the platform default, supplied by
the caller). `lookupByName(host, lookupPolicy)` returns the pinned
IPs for hosts in the map and DELEGATES to the supplied delegate for
any unmapped host. Thread-safe; immutable map.

A nested `public static final class` within the same file extends
`java.net.spi.InetAddressResolverProvider`. Its `get(Configuration)`
captures `configuration.builtinResolver()` into a static field and
returns a JVM-wide forwarding resolver. The forwarding resolver,
per lookup, consults a static pin slot guarded by a JVM-wide
`ReentrantLock`: if a pin is active, it constructs an ephemeral
`PinnedDnsResolver(activePins, builtin)` and delegates the lookup
to it; if no pin is active, it delegates straight to the builtin.

### `META-INF/services/java.net.spi.InetAddressResolverProvider` (NEW)

A one-line resource file at
`infochat-ssrf/src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider`
containing the FQN of the nested provider class declared in
`PinnedDnsResolver.java`. ServiceLoader discovers it at JVM startup
and the JDK installs our provider as the active
`InetAddressResolverProvider`. Without this file, the SPI does not
fire and the JDK's default resolver runs unaffected — so the file is
load-bearing for Finding 2's remediation, not an optional extra.

### `io.infochat.ssrf.SsrfGuardedHttpClient` (MODIFIED)

- Constructor adds a `Duration readTimeout` parameter; the no-arg
  default constructor supplies a sensible default
  (e.g., `Duration.ofSeconds(30)`). Constructor rejects null/zero/
  negative `readTimeout` with `IllegalArgumentException("read timeout
  must be configured")`.
- `get(URI uri)` performs the existing scheme/userinfo/blocklist
  checks, then BEFORE the JDK `httpClient.send` call, constructs a
  per-call `PinnedDnsResolver` mapped from `uri.getHost()` to the
  IP set returned by validation-time `InetAddress.getAllByName`.
  The per-call `HttpClient` is built via the JDK `HttpClient.Builder`
  with the resolver scoped to that instance via
  `InetAddressResolverProvider`.
- The pinning re-applies on each redirect hop: the wrapper's manual
  redirect loop re-resolves the new target host, re-checks against
  IpBlocklist, AND re-installs the resolver pin for that hop.
- `readBounded` wraps each `in.read(buf)` call in a per-read wall-
  clock watchdog: if the read does not return within `readTimeout`,
  raise `SsrfPolicyException("body read timeout after <N>ms")`. The
  watchdog is per-read, not total-duration.
- A package-private constructor exposes a resolver-seam
  (`Function<String, List<InetAddress>>`) so tests can replace
  `InetAddress::getAllByName` deterministically.

### Tests

- `IpBlocklistTest` (modified) — 5 new `@Test` methods:
  - `hostInterfaceIpIsBlocked`
  - `nonHostPublicIpStillAllowed` (negative control for the host seam)
  - `unspecifiedV4IsBlocked`
  - `unspecifiedV6IsBlocked`
  - `limitedBroadcastIsBlocked`
- `SsrfGuardedHttpClientTest` (modified) — 2 new `@Test` methods:
  - `connectUsesValidationTimeIpsNotFreshLookup` (Finding 2)
  - `bodyReadTimeoutFiresOnSlowUpstream` (Finding 4)

### Module build

`mvn -B clean verify` exits 0. M1-023's `RssFeedParserTest` and
M1-024's `RssFetcherTest` / `UrlRedactorTest` continue to pass
unchanged; the wrapper's public API surface is preserved.

## Implementation notes

- **InetAddressResolver SPI is Java-18+ and JVM-wide-only.** The project
  runs on JDK 25 (CLAUDE.md / Stack; `project_quarkus_jdk25` memory).
  The SPI is `java.net.spi.InetAddressResolver` +
  `java.net.spi.InetAddressResolverProvider`. The provider is loaded
  exclusively via the `META-INF/services` ServiceLoader mechanism;
  the JDK 25 `HttpClient.Builder` does NOT expose any per-instance
  resolver setter, and there is no public programmatic registration
  API. Per-call pinning effect is therefore achieved by a static pin
  slot on the nested provider class, guarded by a JVM-wide
  `ReentrantLock` that the wrapper acquires for the duration of each
  `get(uri)` call. This serializes wrapper calls across the JVM, which
  is acceptable for v1's FetchScheduler cadence (RSS feeds polled at
  minute-or-coarser intervals) and is the necessary cost of using
  the SPI. The pre-impl refinement against M1-025 captured this
  design choice in `revisions:` so the reasoning is on the record.
- **DNS pin must survive SNI for HTTPS.** When the wrapper connects
  to a pinned IP for an `https://host/` URL, the SNI handshake must
  carry the original hostname (not the IP) so cert validation
  succeeds. The InetAddressResolver SPI gives the JDK HttpClient the
  right shape: the JDK still believes it is connecting to `host`
  (which it uses for SNI + Host-header), but the DNS lookup returns
  the pinned IP. This is exactly the design intent of the SPI.
- **Per-read watchdog implementation.** `socket.setSoTimeout()`
  applied to the underlying socket would be the cleanest option, but
  JDK `HttpClient` does not expose the socket. Two practical paths:
  (1) wrap the body `InputStream` with a decorator that uses
  `CompletableFuture.supplyAsync(... read ...).get(readTimeout)` per
  read — simple but spawns a thread per read; (2) use a
  `ScheduledExecutorService` to schedule an interrupt on the reading
  thread with `Thread.currentThread().interrupt()` if the read does
  not complete within `readTimeout`. Approach (2) avoids per-read
  thread allocation but is sensitive to interrupt-handling correctness.
  Either is acceptable; the acceptance items name the behavior, not
  the implementation choice.
- **Host-interface enumeration cadence.** The DoD says "at construction
  time, not per-call". On a cloud VM with dynamic IP assignment, the
  bound IPs may change after startup; in v1 we accept that the
  enumeration is a snapshot taken at module init. A future
  enhancement could add a refresh cadence (e.g., re-enumerate every
  5 minutes via a `@Scheduled`), but that's out of scope here — the
  spec doesn't commit to a refresh cadence.
- **0.0.0.0 vs `isAnyLocalAddress`.** Java's `InetAddress.isAnyLocalAddress()`
  returns true for `0.0.0.0` AND `::`. Using that method is a clean
  one-liner that covers both bypass forms; using explicit byte-pattern
  checks is more verbose but more transparent in code review. Either
  is acceptable per the acceptance items above; the test cases assert
  the BEHAVIOR.
- **`isBlocked` ordering.** The host-interface check uses `equals`
  on the address; the range-byte checks use byte-prefix matching.
  Order doesn't matter for correctness (any one match → blocked) but
  puts the cheaper byte-byte equality first for the common case.
- **The new `readTimeout` parameter is REQUIRED on the package-private
  constructor.** The no-arg constructor supplies the default. This
  means RssFetcher (which uses the no-arg constructor) needs no
  change. Tests that construct via the parameterized form (M1-024's
  test-mode IpBlocklist override) get the new parameter explicitly.
- **The PinnedDnsResolver is for the wrapper's internal use only.**
  It is NOT exposed as a public API — the wrapper instantiates and
  scopes it per-call. Operators have no knob to disable pinning;
  the spec says "the allowlist is not user-configurable", and the
  same intent applies to the rebind defense.

## Big-picture notes

- **The acceptance-criteria style here implements the lesson from
  M1-024's redteam.** Every separable spec sentence in §SSRF and
  outbound connections becomes one acceptance item. Bypass-form
  cases (0.0.0.0, ::, 255.255.255.255) get separate items and
  separate test methods so each is independently falsifiable. The
  per-read body-timeout item names the BEHAVIOR ("each individual
  read returns within readTimeout") rather than just the API
  ("readTimeout is set"), avoiding the M1-024 trap where
  `HttpRequest.timeout()` satisfied the word but not the intent.
- **The wrapper's public API is preserved.** RssFetcher (and any
  future caller — Provider's `/add-source`, NostrStreamSource) does
  NOT need to change. The new `readTimeout` parameter has a no-arg
  default; the new pinning is internal; the new IpBlocklist coverage
  expands the already-existing isBlocked surface.
- **`security_relevant: true` again.** The milestone-end `/redteam`
  sweep will audit M1-025's diff for any NEW gaps (the lesson learned
  is that even remediation tickets can introduce new gaps in their
  defense surface). Redteam-after-redteam is the workflow's
  intentional design.
- **Future StreamSource ticket consumes both new classes.** The
  NostrStreamSource ticket's WebSocket wrapper will reuse:
  - `IpBlocklist` (with the now-complete coverage including host
    interfaces + bypass forms);
  - `PinnedDnsResolver` (for the WebSocket connect's DNS pinning).
  That ticket is still its own scope — the WebSocket transport
  wrapper is its own concern.

## Out-of-scope expansion

- **Provider-side `/add-source` URL-validation probe.** Separate
  later ticket; consumes the now-hardened `SsrfGuardedHttpClient`.
- **WebSocket / ws / wss wrapping.** NostrStreamSource ticket
  consumes the now-hardened `IpBlocklist` + `PinnedDnsResolver`;
  the transport wrapper itself is its own scope.
- **FetchScheduler / `@Scheduled` / per-tick wiring.** T1-C
  territory at @Priority(400).
- **Outbox sink / OutboxRehydrator / new_post NOTIFY / provider_state
  / NewPostReconciler.** T1-C.
- **Stage 1 sanitization / NFKC / regex redaction.** T1-D.
- **Per-source failure-counter UPDATE.** FetchScheduler's
  responsibility per D42.
- **Retry / backoff / Retry-After.** FetchScheduler's responsibility.
- **Fetcher SPI / NormalizedPost shape change.** Unchanged from
  M1-023/M1-024.
- **V1..V8 Flyway migrations.** `migration_touch: false`.
- **Port-based filtering.** OUT-OF-MODEL per the M1-024 redteam
  notes; would require a spec amendment to commit to it.
- **Operator-facing knob to disable host-interface enumeration.** The
  spec says "the allowlist is not user-configurable"; the test seam
  is constructor-parameter only, never a flag.
- **DNS-pin refresh cadence.** Single-snapshot at construction is
  v1 behavior; a `@Scheduled` re-enumeration is future work the
  spec doesn't commit to.
- **Connection pool DNS isolation.** The redteam's OUT-OF-MODEL
  note flagged that JDK HttpClient may reuse a connection without
  re-resolving DNS. The per-call HttpClient construction here
  inherently isolates connections per call (no pool reuse across
  calls), so this concern is closed as a side effect — but the
  spec doesn't commit to per-call connection isolation, so it
  stays out-of-scope as a named guarantee.
- **IDN homograph normalization.** OUT-OF-MODEL per the M1-024
  redteam notes; reduces to the now-closed within-hop DNS pinning
  question.

## Authorized test changes

- `infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java`
  — add 5 new `@Test` methods (host-interface seam × 2, bypass-form
  × 3). The 19 pre-existing `@Test` methods are preserved unchanged
  in substance.
- `infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java`
  — add 2 new `@Test` methods (connectUsesValidationTimeIpsNotFreshLookup,
  bodyReadTimeoutFiresOnSlowUpstream). The 10 pre-existing `@Test`
  methods are preserved unchanged in substance, with the caveat that
  any test that constructs `SsrfGuardedHttpClient` via the package-
  private parameterized constructor must supply the new
  `readTimeout` parameter (one-line per-test edit). Tests using the
  no-arg constructor (e.g., RssFetcherTest's eventual default-path
  call sites) are unaffected.

`RssFetcherTest`, `RssFeedParserTest`, `UrlRedactorTest`, and all
M1-001..M1-023 tests stay untouched — the wrapper's public API
surface is preserved, so the existing call sites continue to compile
and behave identically.

## Alternatives considered

- **Split into 2 tickets (Option B): M1-025 = IpBlocklist completeness
  (Findings 1, 3); M1-026 = SsrfGuardedHttpClient defenses (Findings
  2, 4).** Considered. Single ticket chosen per the M1-024 Option-A
  precedent — all four findings are in the same module and the
  testing matrix benefits from being landed atomically. If during
  implementation the within-hop DNS pinning (Finding 2) turns out
  to require a more invasive refactor than budget allows, the
  escalation path is to **decompose** at that point (split off
  Finding 2 into M1-026) rather than expand budget.
- **Skip the `PinnedDnsResolver` (Java 18+ SPI) and connect via raw
  socket + manual HTTP protocol implementation.** Rejected: writing
  HTTP/1.1 + TLS by hand is a much larger surface than the SPI
  approach, and the JDK SPI is the spec-blessed path on JDK 18+
  for exactly this use case (controlling DNS resolution for HTTP
  client calls).
- **Skip the `PinnedDnsResolver` and use `URI` rewriting (replace
  `host` with the resolved IP, then inject `Host:` header
  manually).** Rejected: this works for HTTP but BREAKS TLS for
  HTTPS — SNI uses the URI's host, and rewriting it to an IP
  bypasses cert hostname validation. The InetAddressResolver SPI
  preserves SNI correctly (the JDK still sees the original hostname
  for SNI; only the DNS lookup is pinned).
- **Use `addr.isAnyLocalAddress()` instead of explicit byte-pattern
  checks for 0.0.0.0/::**. Either is acceptable; the acceptance
  items name the behavior. The byte-pattern style matches M1-024's
  existing IpBlocklist style; the `isAnyLocalAddress` style is
  cleaner. Implementer's choice.
- **Defer host-interface enumeration to the NostrStreamSource
  ticket** (since the immediate RssFetcher attack surface is
  smaller). Rejected: every Fetcher caller (RSS, future
  Bluesky/Nitter/Reddit/YouTube/Odysee) shares the same
  IpBlocklist; closing the host-interface gap once at the policy
  class is correct.
- **Use a JVM-global `InetAddressResolverProvider` installed at
  startup vs per-HttpClient scoping.** Resolved during the
  pre-implementation refinement: JDK 25 does NOT support per-
  HttpClient resolver scoping (the SPI loads one resolver per JVM
  via ServiceLoader; `HttpClient.Builder` has no `.resolver(...)`
  setter; no programmatic registration API exists). The JVM-global
  provider IS the primary path. Cross-call pin leakage is prevented
  by a JVM-wide `ReentrantLock` that the wrapper acquires for the
  duration of each `get(uri)` call — serializing wrapper calls
  process-wide but keeping per-call pin behavior unambiguous. The
  Plan outline's Risk 1 anticipated exactly this resolution; the
  refinement bumped `files_budget` 6→7 to accommodate the
  META-INF/services resource and added the resource path to
  `files_scope`.
- **Defer Finding 4 (body-read timeout) on the rationale that the
  body-size cap (10 MiB) bounds total bytes.** Rejected: as the
  redteam REPRO showed, 10 MiB at 1 byte/min ≈ 19 years of held
  threads. The body cap bounds bytes, not time; both caps are
  required for the spec's read-timeout commitment.
- **Treat 0.0.0.0/::/255.255.255.255 as out-of-scope (not literal
  loopback).** Rejected: the spec's "loopback" intent is to prevent
  same-machine SSRF, and the kernel's connect-rewrite of 0.0.0.0
  to 127.0.0.1 makes it a loopback bypass in practice. Including
  it implements the spec's intent without a spec amendment. If the
  reviewer disagrees, the escalation path is **spec-amend** to
  explicitly add 0.0.0.0/::/255.255.255.255 to the spec's listed
  ranges.

## Implementation outline (M1-025, generated by Plan subagent on 2026-05-15)

### Files to touch (7 of 7) — updated after pre-impl budget-breach refine
- create: `infochat-ssrf/src/main/java/io/infochat/ssrf/HostInterfaceSet.java` — utility class with `public static Set<InetAddress> enumerate()` walking `NetworkInterface.getNetworkInterfaces()` and filtering out loopback addresses (Finding 1 enumeration source).
- create: `infochat-ssrf/src/main/java/io/infochat/ssrf/PinnedDnsResolver.java` — `java.net.spi.InetAddressResolver` implementation backed by an immutable `Map<String, List<InetAddress>>` + caller-supplied delegate; plus a nested `public static final class` extending `java.net.spi.InetAddressResolverProvider` that captures `Configuration.builtinResolver()` and exposes a JVM-wide static pin slot + `ReentrantLock` to the wrapper (Finding 2 substrate).
- create: `infochat-ssrf/src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider` — single-line resource registering the nested provider FQN for ServiceLoader discovery; load-bearing for the SPI to fire at JVM startup (Finding 2 enablement).
- modify: `infochat-ssrf/src/main/java/io/infochat/ssrf/IpBlocklist.java` — add no-arg constructor that snapshots `HostInterfaceSet.enumerate()` into a final field; add package-private constructor accepting `Set<InetAddress>`; extend `isBlocked` with host-IP equality check; extend `isBlockedV4`/`isBlockedV6` with `0.0.0.0/8`, `255.255.255.255`, `::` bypass forms. Preserve the existing `LoopbackPermitting` subclassing seam used by `SsrfGuardedHttpClientTest`.
- modify: `infochat-ssrf/src/main/java/io/infochat/ssrf/SsrfGuardedHttpClient.java` — add `Duration readTimeout` constructor parameter (rejected null/zero/negative with `"read timeout must be configured"` literal); add no-arg default of `Duration.ofSeconds(30)`; introduce per-call `HttpClient` construction; acquire the provider's JVM-wide lock for the duration of `get(uri)`; set the static pin slot for `uri.getHost()` per hop, release in `finally`; preserve manual redirect loop but re-resolve + replace-pin per hop; rewrite `readBounded` with a per-read watchdog raising `SsrfPolicyException("body read timeout ...")`; add package-private resolver-seam constructor accepting `Function<String, List<InetAddress>>`.
- modify: `infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java` — append 5 new `@Test` methods (preserve 19 existing).
- modify: `infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java` — append 2 new `@Test` methods (preserve 10 existing; no-arg-constructor tests unaffected; parameterized-constructor tests need a one-line edit each to pass the new `readTimeout` arg).

Budget check: 7 of 7. No surplus.

### Tests
- modify: `infochat-ssrf/src/test/java/io/infochat/ssrf/IpBlocklistTest.java` — authorized in ticket body §"Authorized test changes" item 1. Adds:
  - `hostInterfaceIpIsBlocked` — constructs `IpBlocklist` via the package-private constructor with `{203.0.113.5}` host set; asserts `isBlocked(203.0.113.5) == true`. (acceptance items 3 + 4a)
  - `nonHostPublicIpStillAllowed` — same host set; asserts `isBlocked(8.8.8.8) == false`. (acceptance item 4b)
  - `unspecifiedV4IsBlocked` — asserts `isBlocked(0.0.0.0) == true`. (acceptance item 5 + 8)
  - `unspecifiedV6IsBlocked` — asserts `isBlocked(::) == true`. (acceptance item 6 + 8)
  - `limitedBroadcastIsBlocked` — asserts `isBlocked(255.255.255.255) == true`. (acceptance item 7 + 8)
- modify: `infochat-ssrf/src/test/java/io/infochat/ssrf/SsrfGuardedHttpClientTest.java` — authorized in ticket body §"Authorized test changes" item 2. Adds:
  - `connectUsesValidationTimeIpsNotFreshLookup` — resolver-seam returns `[127.0.0.1]` first call, `[192.0.2.1]` second call for the same host; asserts a 200 from the test server, and that the seam was invoked exactly once. (acceptance item 12)
  - `bodyReadTimeoutFiresOnSlowUpstream` — server emits headers + 16 bytes then sleeps `readTimeout*2`; wrapper constructed with `readTimeout=1s`; asserts `SsrfPolicyException` with message starting `"body read timeout"` and elapsed time within `readTimeout + 500ms`. (acceptance item 15)
  - One-line edit to the existing parameterized-constructor tests (`rejectsResponseBodyOverCap`, `constructorRejectsNullTimeout`, `constructorRejectsZeroTimeout`) to supply the new `readTimeout` parameter; substance preserved.

Authorization confirmed: both modified test files are named explicitly in §"Authorized test changes".

### Cross-cutting concerns
- **Spec invariant: "DNS is re-resolved after every redirect; the IP blocklist re-applies each hop"** (security.md §SSRF). Per-call HttpClient construction must not move the re-resolution upstream of the redirect loop — the existing manual-redirect-loop discipline is the load-bearing piece that satisfies the spec. The new pinning must layer ON TOP of that, not replace it. Each redirect hop = new resolver pin, new IpBlocklist check.
- **Spec invariant: "an unset timeout is a configuration error"** (security.md §SSRF). The new `readTimeout` joins `connectTimeout`/`requestTimeout` under the same fail-closed rule; constructor must reject null/zero/negative with the literal `"read timeout must be configured"` substring. The no-arg constructor's default supplies the value — RssFetcher's call site stays valid because the default is wired upstream.
- **Public API surface preservation** (ticket out-of-scope item 2). `new SsrfGuardedHttpClient()` no-arg + `HttpResponse<byte[]> get(URI)` must continue compiling. RssFetcher (no-arg consumer) and RssFetcherTest stay untouched. The parameterized constructor's signature widens by exactly one `Duration` parameter; this is allowed because RssFetcher does not consume it.
- **Test-mode IpBlocklist override seam preservation** (security.md §SSRF discipline + IpBlocklist javadoc). The existing `LoopbackPermitting` subclass in `SsrfGuardedHttpClientTest` overrides `isBlocked` to carve out loopback. The new no-arg constructor must initialize the host-IP-set field cleanly even when the subclass doesn't care about it; the new package-private host-set constructor must remain a peer entry point, not break the no-arg path subclasses use.
- **SNI integrity for HTTPS** (impl notes). DNS pin must NOT rewrite the URI host — only the resolver delegate changes. The HttpClient still sees the original hostname, so the TLS ClientHello SNI remains correct.
- **Resolver SPI is JVM-global; per-call effect via lock** (impl notes). `InetAddressResolverProvider` is a JVM-global SPI loaded via ServiceLoader at startup. JDK 25 does NOT support per-HttpClient resolver scoping. The wrapper achieves per-call pin behavior by acquiring a JVM-wide `ReentrantLock` for the duration of `get(uri)`, writing the per-call pin into the provider's static pin slot, calling `httpClient.send`, then clearing the pin and releasing the lock. Concurrent wrapper calls serialize on this lock — acceptable for v1's FetchScheduler cadence.
- **Engineering rules §No defensive code**: production callers always use the no-arg constructor; the host-set and resolver-seam constructors are package-private test seams, mirroring M1-024's `IpBlocklist override is an API surface, not a flag` discipline. No "feature flag" property; no operator knob to disable enumeration.

### Implementation order
1. Add `HostInterfaceSet` first — pure utility, no consumers yet. Trivial to unit-verify before anything depends on it. Wrong order: starting with `IpBlocklist` modifications would leave the new no-arg constructor calling an unwritten method.
2. Modify `IpBlocklist` — wire no-arg + package-private constructors, bypass-form byte checks. After this step the production `IpBlocklist()` constructor exists and enumerates host interfaces; existing `LoopbackPermitting` subclass continues to compile because the parent no-arg constructor still exists.
3. Add `IpBlocklistTest` host-interface and bypass-form methods — verify steps 1-2 in isolation before touching the HTTP wrapper. Wrong order: skipping unit verification here means an HTTP-wrapper-level test failure could be either the resolver pinning OR the blocklist; isolating IpBlocklist first keeps the fault domains separate.
4. Add `PinnedDnsResolver` (with the nested `InetAddressResolverProvider` static class, static pin slot, and `ReentrantLock`) AND register it via `META-INF/services/java.net.spi.InetAddressResolverProvider` in the same step. The class and the resource file are coupled — the class without the resource file is unreachable code, and the resource file without the class is a ClassNotFoundException at JVM startup. Landing them together avoids an intermediate state where one half compiles but the SPI doesn't fire.
5. Modify `SsrfGuardedHttpClient` — add `readTimeout` to parameterized constructor, add resolver-seam package-private constructor, rewire `get()` to acquire the provider's JVM-wide lock, install per-call pin into the provider's static slot, construct a per-call HttpClient, call send, clear the pin in `finally`, release the lock. Rewrite `readBounded` with watchdog. Each redirect hop replaces the pin (still under the same lock). Wrong order: skipping `readTimeout` plumbing first and only adding the resolver pin would leave one failing test for the watchdog after redirect-pin tests already pass — masking which change broke what.
6. Add `SsrfGuardedHttpClientTest` connect-pinning + body-read-timeout methods, edit existing parameterized-constructor tests to supply the new `readTimeout` arg. The one-line edits to existing tests are mechanical and must NOT change substance (engineering rule: surgical changes).
7. Run `mvn -B -pl infochat-ssrf test`, then `mvn -B clean verify` from repo root. RssFetcherTest must pass unchanged (validates the public-API-surface invariant).

### Risks
- **Per-instance `InetAddressResolverProvider` scoping was confirmed unavailable in JDK 25** — resolved during the pre-impl budget-breach refine. The SPI loads ONE resolver per JVM via ServiceLoader; `HttpClient.Builder` has no `.resolver(...)` setter; no programmatic registration. The chosen path is (a) JVM-wide provider registered via `META-INF/services`, (b) static pin slot guarded by a JVM-wide `ReentrantLock`, (c) wrapper acquires the lock for the duration of `get(uri)` so per-call pinning is unambiguous. This serializes concurrent wrapper calls JVM-wide — acceptable for v1's RSS cadence. If a future ticket needs higher fetch concurrency, that ticket would lift the serialization (e.g. by keying the pin slot by hostname instead of holding a global lock); out of scope here.
- **Per-read watchdog implementation choice has correctness traps** — impl notes list `CompletableFuture` vs `ScheduledExecutorService` vs `setSoTimeout`. `HttpClient.BodyHandlers.ofInputStream()` does NOT expose the underlying socket, so `setSoTimeout` is unreachable. The `CompletableFuture.supplyAsync(... read ...).get(readTimeout)` approach spawns one thread per `read()` (typical 8KiB buffer → many threads per body). The `ScheduledExecutorService` interrupt approach requires `InputStream` to honor interrupts (the JDK HttpClient's body stream does respond to thread interrupt by closing the underlying connection). Escalation: **refine** if implementer hits a JDK-level "InputStream does not respect interrupt" wall.
- **Host-interface enumeration on container/laptop dev machines may pick up Docker bridges and Tailscale interfaces**, blocking legitimate test traffic. The test-mode seam (package-private host-set constructor) is the explicit mitigation; production cannot inject. If a CI runner's host-interface enumeration overlaps with a legitimate test target, the test-mode seam must be used. No escalation expected; the test-only constructor exists for this reason.
- **`InetAddress.getByName("0.0.0.0")` may resolve to platform-specific behavior on some JVMs** (e.g. returning a special "any" address). The acceptance criterion mandates byte-pattern matching after construction via `InetAddress.getByName`, so the impl must verify the parser returns the expected `0.0.0.0` raw bytes on JDK 25 before relying on `isAnyLocalAddress()`. If parser behavior is surprising, fall back to byte-pattern checks (the acceptance criterion permits both). No escalation expected.

### Out-of-scope (echoed from ticket)
- Any change to the `io.infochat.core.ingest.Fetcher` SPI or `NormalizedPost` record
- Any change to `RssFetcher.java` or `RssFeedParser.java` (wrapper public API preserved)
- Any change to `RssFetcherTest.java`
- Any WebSocket / ws / wss wrapping (NostrStreamSource ticket)
- Any Provider-side `/add-source` HEAD/GET probe wiring
- Any FetchScheduler / `@Scheduled` / per-tick cadence selection
- Any outbox sink, RAW-row INSERT, post-table write, OutboxRehydrator, LISTEN/NOTIFY new_post, provider_state high-water-mark, NewPostReconciler wiring
- Any Stage 1 HTML sanitization, NFKC normalization, regex redaction, canonical-body UID hashing
- Any source-row UPDATE for last_fetch_at / last_success_at / consecutive_failures
- Any retry / backoff / Retry-After / per-source politeness window
- Any change to V1..V8 Flyway migrations
- Any LLM tool surface, prompt-injection sanitizer, LLM-output redactor
- Any audit_log write or AuditLogger code path
- Any port-based filtering (OUT-OF-MODEL)
- Any operator-facing knob to disable host-interface enumeration or DNS pinning
