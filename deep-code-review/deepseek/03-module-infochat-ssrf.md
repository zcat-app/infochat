# Deep code review: module infochat-ssrf

**Target:** module infochat-ssrf
**Lens:** module
**Module path:** infochat-ssrf/
**Date:** 2026-06-01 20:57
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [MEDIUM] MAINTAINABILITY-RULES-DRIFT -- SsrfGuardedHttpClient.java:162, SsrfGuardedHttpClient.java:183, PinnedDnsResolver.java:56 -- Public constructors lack required `@NonNull` annotations on reference-type parameters, violating engineering rules 7a
- [MEDIUM] MAINTAINABILITY-RULES-DRIFT -- SsrfGuardedHttpClient.java:269 -- `canonicalizeHost` throws for IPv6 IP literals, causing them to be rejected as "invalid host" before reaching the IP blocklist check
- [LOW] MAINTAINABILITY-RULES-DRIFT -- UrlRedactor.java:64 -- IPv6 literal addresses are rendered without brackets, producing ambiguous log output

## Detail

### F1. Missing nullability annotations on public constructor reference-type parameters

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** SsrfGuardedHttpClient.java:162, SsrfGuardedHttpClient.java:183, PinnedDnsResolver.java:56

**Current code:**

```java
// SsrfGuardedHttpClient.java:162-172
public SsrfGuardedHttpClient(IpBlocklist blocklist,
                             Duration connectTimeout,
                             Duration requestTimeout,
                             Duration readTimeout,
                             Duration bodyReadDeadline,
                             long bodyCap,
                             int redirectCap) {
```

```java
// SsrfGuardedHttpClient.java:183-190
public SsrfGuardedHttpClient(IpBlocklist blocklist,
                             Duration connectTimeout,
                             Duration requestTimeout,
                             Duration readTimeout,
                             Duration bodyReadDeadline,
                             long bodyCap,
                             int redirectCap,
                             Function<String, List<InetAddress>> resolverSeam) {
```

```java
// PinnedDnsResolver.java:56-59
public PinnedDnsResolver(Map<String, List<InetAddress>> pins,
                         InetAddressResolver delegate) {
```

**Why this is wrong / suboptimal / risky:**

Engineering rule 7a requires every reference-type parameter on a public method (and by intent, public constructors) to declare nullability via `@NonNull` or `@Nullable` from org.jspecify.annotations, or via javadoc `@param`. These three public constructors accept reference-type parameters with no nullability annotation whatsoever. The runtime validation in the `SsrfGuardedHttpClient` constructors (null checks throwing IllegalArgumentException) provides some safety but does not satisfy the rule's requirement for an *explicit contract in the signature*. The `PinnedDnsResolver` constructor has no validation at all -- a null `pins` or `delegate` produces a `NullPointerException` during `Map.copyOf(pins)` or on first delegate call, which is harder to diagnose.

The rule exists precisely so that a caller can see from the signature whether passing null is legal. Without these annotations, the review `scripts/lint-contracts.py` cannot verify compliance, and callers must read the implementation to determine contract intent.

**Recommended fix:**

Add `@NonNull` to every reference-type parameter on all three constructors. JSpecify is already on the classpath (it is used elsewhere in the module).

```java
// SsrfGuardedHttpClient.java:162-172
public SsrfGuardedHttpClient(@NonNull IpBlocklist blocklist,
                             @NonNull Duration connectTimeout,
                             @NonNull Duration requestTimeout,
                             @NonNull Duration readTimeout,
                             @NonNull Duration bodyReadDeadline,
                             long bodyCap,
                             int redirectCap) {
```

```java
// SsrfGuardedHttpClient.java:183-190
public SsrfGuardedHttpClient(@NonNull IpBlocklist blocklist,
                             @NonNull Duration connectTimeout,
                             @NonNull Duration requestTimeout,
                             @NonNull Duration readTimeout,
                             @NonNull Duration bodyReadDeadline,
                             long bodyCap,
                             int redirectCap,
                             @NonNull Function<String, List<InetAddress>> resolverSeam) {
```

```java
// PinnedDnsResolver.java:56-59
public PinnedDnsResolver(@NonNull Map<String, List<InetAddress>> pins,
                         @NonNull InetAddressResolver delegate) {
```

**Reasoning:**

The existing runtime validation in the `SsrfGuardedHttpClient` constructors already treats null as illegal (it throws `IllegalArgumentException`). Adding `@NonNull` makes this commitment visible at the type level. The `PinnedDnsResolver` constructor implicitly treats null as illegal (throwing NPE from `Map.copyOf` or on delegate invocation); `@NonNull` documents this and encourages a clearer error message if null is ever passed.

**Trade-offs:**

None -- the fix is strictly better. Parameter validation is retained; the annotation is additive.

---

### F2. IPv6 IP literals rejected as "invalid host" before reaching IP blocklist

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** SsrfGuardedHttpClient.java:269, called at 388-392

**Current code:**

```java
// SsrfGuardedHttpClient.java:269
static String canonicalizeHost(String host) {
    if (host == null || host.isBlank()) {
        throw new IllegalArgumentException("host must not be null or blank");
    }
    String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
    String lower = ascii.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".")) {
        return lower.substring(0, lower.length() - 1);
    }
    return lower;
}
```

Call site (lines 388-392):
```java
try {
    canonicalHost = canonicalizeHost(rawHost);
} catch (IllegalArgumentException e) {
    throw new SsrfPolicyException("invalid host: " + rawHost, e);
}
```

**Why this is wrong / suboptimal / risky:**

The `canonicalizeHost` method always passes the host string through `IDN.toASCII`. For IPv6 IP literals such as `::1` or `::ffff:169.254.169.254` (which are valid URI hosts -- `URI.getHost()` returns them as bare strings without brackets), `IDN.toASCII` throws `IllegalArgumentException` because colons are not valid in internationalized domain name labels. The exception is caught at the call site in `resolveAndValidate` and converted to `SsrfPolicyException("invalid host: ...")`.

This has two consequences:

1. **The request is rejected with a misleading error.** The host is not "invalid" -- it is a well-formed IPv6 literal that the JDK, `InetAddress.getByName`, and the IP blocklist can all handle correctly. The operator sees "invalid host" and may investigate a nonexistent hostname format problem.

2. **The IP blocklist check is bypassed.** The spec (security.md SSRF) commits to checking all resolved IPs against the blocklist. For IPv6 literal hosts, that check never runs because the code fails earlier. The wrapper is fail-closed (the request is rejected), so this is not a security bypass, but it is a functional gap: the blocklist correctly handles IPv6 ranges (::1 loopback, fe80::/10 link-local, fc00::/7 unique-local, ff00::/8 multicast, IPv4-mapped forms) but those checks never execute for literal IPv6 hosts.

3. **IPv4 literals work only by coincidence.** `IDN.toASCII("127.0.0.1")` returns the string unchanged because each label is a valid ACE label for pure-ASCII IP octets. This is an accident of the IDN spec, not a deliberate design choice, and creates an inconsistency between IPv4 and IPv6 literal handling.

**Recommended fix:**

Detect IP literals in `canonicalizeHost` and pass them through without IDN processing. An address is an IP literal if it starts with a digit (IPv4) or contains a colon (IPv6). Alternatively, handle the IDN exception more gracefully in `resolveAndValidate` by retrying without IDN processing.

Option A (modify `canonicalizeHost`):

```java
static String canonicalizeHost(String host) {
    if (host == null || host.isBlank()) {
        throw new IllegalArgumentException("host must not be null or blank");
    }
    // IP literals (IPv4 dotted-decimal, IPv6 colon-form) do not need
    // IDN normalization or case-folding -- the raw string from URI.getHost()
    // is already the canonical form for DNS resolution.
    if (isIpLiteral(host)) {
        return host;
    }
    String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
    String lower = ascii.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".")) {
        return lower.substring(0, lower.length() - 1);
    }
    return lower;
}

private static boolean isIpLiteral(String host) {
    if (host.isEmpty()) {
        return false;
    }
    char c = host.charAt(0);
    // IPv4 starts with a digit; IPv6 contains ':'
    return (c >= '0' && c <= '9') || host.indexOf(':') >= 0;
}
```

Option B (handle at the call site in `resolveAndValidate`):

```java
String canonicalHost;
try {
    canonicalHost = canonicalizeHost(rawHost);
} catch (IllegalArgumentException e) {
    // If canonicalization fails (e.g., IPv6 literal that IDN.toASCII
    // rejects), try the raw host directly. The resolver seam and
    // IP blocklist can handle IP literals.
    if (rawHost.indexOf(':') >= 0 || (!rawHost.isEmpty()
            && rawHost.charAt(0) >= '0' && rawHost.charAt(0) <= '9')) {
        canonicalHost = rawHost;
    } else {
        throw new SsrfPolicyException("invalid host: " + rawHost, e);
    }
}
```

**Reasoning:**

IP literals are already in their canonical form -- there is no case-folding, IDN normalization, or trailing-dot stripping to perform. The IDN step is designed for domain names only. By passing IP literals through unchanged, the DNS resolution step (`InetAddress.getAllByName`) handles them correctly, and the IP blocklist runs as designed. The `isIpLiteral` heuristic is reliable: the first character of an IPv4 dotted-quad is always a digit (0-9), and an IPv6 address always contains a colon.

**Trade-offs:**

- The `isIpLiteral` heuristic is a string prefix check, not a full IP validation. A host like `123host` (starts with a digit but is a valid domain name) would be treated as an IP literal and not normalized. This is acceptable because `InetAddress.getAllByName("123host")` would attempt DNS resolution on the raw string and fail or return an unexpected result, which is caught by the existing null/empty check on the resolution result. The heuristic is intentionally conservative (over-classifies into IP-literal mode) because the worst outcome is a failed DNS lookup.
- Option B is slightly more localized but moves IP-detection logic out of `canonicalizeHost`, which weakens the method's contract ("no IDN processing for IP literals"). Option A is preferred.

---

### F3. UrlRedactor omits brackets around IPv6 addresses

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** UrlRedactor.java:64

**Current code:**

```java
StringBuilder out = new StringBuilder();
out.append(scheme).append("://").append(host);
int port = uri.getPort();
if (port != -1) {
    out.append(':').append(port);
}
String path = uri.getRawPath();
if (path != null) {
    out.append(path);
}
```

**Why this is wrong / suboptimal / risky:**

When the input URL has an IPv6 host (e.g., `http://[::1]:8080/path`), `URI.getHost()` returns the address without brackets (`::1`). The redactor emits `http://::1:8080/path`, which is ambiguous (the colons in the IPv6 address collide with the port delimiter) and not a valid URI. While this is a logging-only helper and does not affect security policy, it produces confusing log output that could hinder debugging of SSRF policy violations involving IPv6 addresses.

The module's existing IP blocklist handles IPv6 addresses (including IPv4-mapped forms), so SSRF violations against IPv6 targets are possible and their log entries should be unambiguous.

**Recommended fix:**

Check whether the host contains a colon (IPv6 indicator) and wrap it in brackets.

```java
StringBuilder out = new StringBuilder();
out.append(scheme).append("://");
if (host.indexOf(':') >= 0) {
    out.append('[').append(host).append(']');
} else {
    out.append(host);
}
int port = uri.getPort();
if (port != -1) {
    out.append(':').append(port);
}
String path = uri.getRawPath();
if (path != null) {
    out.append(path);
}
```

**Reasoning:**

The bracket-wrapping convention for IPv6 in URIs is defined by RFC 3986 section 3.2.2. Since `URI.getHost()` drops the brackets, the redactor must add them back for output to be parseable as a URI. The colon-heuristic is reliable: a valid IPv6 address always contains at least one colon; hostnames and IPv4 addresses never do.

**Trade-offs:**

None -- the fix is strictly better. The condition `host.indexOf(':') >= 0` is a trivial O(n) scan of a typically short string.

---

