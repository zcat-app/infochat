# Deep code review: module infochat-ssrf

**Target:** module infochat-ssrf
**Lens:** module
**Module path:** infochat-ssrf/
**Date:** 2026-06-07
**Reviewer:** senior-developer (mimo-v2.5-pro)

## Headline findings

| # | Category | Severity | Summary |
|---|----------|----------|---------|
| 1 | MAINTAINABILITY-RULES-DRIFT | medium | `SsrfGuardedHttpClient` constructor null-checks parameters that NullAway already guarantees non-null (violates rule §7: "no defensive code for impossible scenarios") |
| 2 | MAINTAINABILITY-RULES-DRIFT | low | `resolveAndValidate` null-checks the return of `resolverSeam.apply()`, which is contractually non-null under NullAway's package-default (same §7 violation) |
| 3 | SECURITY | low | `PinnedDnsResolver.ForwardingResolver.lookupByName` reads `ACTIVE_PINS` volatile then dereferences `BUILTIN` without checking its non-null guarantee; a pre-SPI-init race window exists in theory but is guarded by the JDK SPI contract |
| 4 | PERFORMANCE | low | `HostInterfaceSet.enumerate()` performs full JNI `NetworkInterface.getNetworkInterfaces()` on every `IpBlocklist.isBlocked` call; cost is bounded by NIC count but is non-trivial on hosts with many container bridges |
| 5 | MAINTAINABILITY-RULES-DRIFT | low | Test module lacks NullAway/Error Prone on test sources (explicitly deferred, per POM comment) |

No critical or high findings.

## Detail

### Finding 1: Null-checks on NullAway-guaranteed non-null parameters

**Category:** MAINTAINABILITY-RULES-DRIFT
**Severity:** medium
**Files:** `SsrfGuardedHttpClient.java` lines 197-226

The 8-argument `SsrfGuardedHttpClient` constructor checks `blocklist == null`, `connectTimeout == null`, `requestTimeout == null`, `readTimeout == null`, `bodyReadDeadline == null`, and `resolverSeam` (implicit, not checked but also non-null). Per the NullAway config in the parent POM, `app.zcat.infochat` is `AnnotatedPackages` (non-null by default). The constructor's reference parameters have no `@Nullable` annotation, so NullAway enforces non-null at every call site. The null checks inside the constructor body are checking for a state that the compiler already rejects.

Rule §7 states: "No null-checks for parameters callers cannot legally pass null for." These checks are between internal code (the constructor and its callers), not at a system boundary. The zero/negative checks on `Duration` and `long`/`int` parameters are legitimate system-boundary validation (config parsing is the caller); the null checks are not.

The same pattern appears in `resolveAndValidate` at line 471: `addresses == null` checks the return of `resolverSeam.apply()`. The `resolverSeam` field is typed `Function<String, List<InetAddress>>` (non-null return under NullAway). A null return from a non-null-contract function is a bug in the supplier, not a state to gracefully handle. The `isEmpty()` half of that check is correct; the `null` half is the violation.

**Recommendation:** Remove the `null` checks from the constructor (keep the zero/negative checks). Remove `addresses == null ||` from `resolveAndValidate` (keep `addresses.isEmpty()`). The NullAway-ERROR build is the enforcement.

### Finding 2: Per-call JNI cost of `HostInterfaceSet.enumerate()`

**Category:** PERFORMANCE
**Severity:** low
**Files:** `IpBlocklist.java` line 107, `HostInterfaceSet.java`

`IpBlocklist.isBlocked` calls `hostInterfacesProvider.get()` on every invocation, which in production calls `HostInterfaceSet.enumerate()`. That method calls `NetworkInterface.getNetworkInterfaces()`, a JNI call that enumerates all OS-level network interfaces. The Javadoc acknowledges this ("the JNI `NetworkInterface.getNetworkInterfaces()` call is cheap on hot paths").

On a typical server or container, the cost is low. On a K8s node or a host with many container bridges, CNI interfaces, and VPN tunnels, the enumeration can involve dozens of interfaces. The spec mandates per-call enumeration (no startup-snapshot qualifier), so this is a deliberate trade-off. No actionable change -- this is a documented design decision.

### Finding 3: ForwardingResolver dereferences `BUILTIN` without local null guard

**Category:** SECURITY
**Severity:** low
**Files:** `PinnedDnsResolver.java` lines 174-191

`ForwardingResolver.lookupByName` dereferences `BUILTIN` (line 181, 184) which is a `volatile` field set in `Provider.get()`. The JDK SPI contract guarantees that `get()` is called before any lookup reaches the returned resolver, and `BUILTIN` is written before `get()` returns. The `@SuppressWarnings("NullAway.Init")` annotation at line 128 documents this.

This is safe under the JDK SPI contract. If a future refactor ever invokes `lookupByName` outside the SPI lifecycle (e.g., from a test helper that does not go through the ServiceLoader), the dereference would NPE. Not a real risk today; noted for awareness only.

### Finding 4: Constructor validation range

**Category:** SIMPLIFICATION
**Severity:** low
**Files:** `SsrfGuardedHttpClient.java` lines 197-226

The constructor validates `connectTimeout`, `requestTimeout`, `readTimeout`, and `bodyReadDeadline` for `isZero()` and `isNegative()`. This is correct system-boundary validation (config values from `application.properties` pass through here). The `resolverSeam` parameter has no validation at all -- consistent with it being a non-null function reference where the only contract violation is the null check itself (finding 1).

No change needed. The zero/negative checks are proper boundary guards.

### Finding 5: Test sources exempt from NullAway

**Category:** MAINTAINABILITY-RULES-DRIFT
**Severity:** low
**Files:** `infochat-ssrf/pom.xml` lines 50-60

The POM explicitly clears `compilerArgs` for `default-testCompile` so Error Prone and NullAway do not run on test sources. The POM comment references M1-164a precedent. This is an intentional deferral, not an oversight. Test sources are plain JUnit 5 with no CDI surface; the risk of null-safety bugs leaking through tests is low.

### Positive observations (not findings)

The module's architecture is sound for its purpose:

- **IpBlocklist** covers the full spec-mandated range set including IPv4-mapped, 6to4, Teredo, NAT64, and IPv4-compatible IPv6 forms, plus the host's own non-loopback interfaces. The per-call `Supplier<Set<InetAddress>>` seam (M1-026) correctly addresses the spec's present-tense "are checked" clause.
- **PinnedDnsResolver** correctly solves the DNS-rebind TOCTOU gap by pinning validated IPs in a JVM-wide static slot under a `ReentrantLock`. The lock is held only during connection establishment and released before body read (M1-026 Finding 1).
- **SsrfGuardedHttpClient** implements the full pipeline: scheme allowlist, userinfo gate, canonicalization, DNS resolution + blocklist check, DNS pinning, redirect loop with per-hop re-validation, body-cap enforcement, per-read wall-clock watchdog, and total body-read deadline. Credential-header scrubbing on cross-origin redirects is correctly implemented.
- **UrlRedactor** is infallible by design (blanket catch on `URISyntaxException`), appropriate for a logging helper.
- **Test coverage** is thorough: `IpBlocklistTest` covers every blocked range including all IPv6 transition forms and the host-interface per-call seam; `SsrfGuardedHttpClientTest` exercises DNS pinning, per-read timeout, total body-read deadline, redirect cap, redirect re-validation, cross-origin credential stripping, scheme rejection, userinfo rejection, and 304 non-redirect; `UrlRedactorTest` covers userinfo, query, combined, malformed, null, port, and IPv6 bracket scenarios.
