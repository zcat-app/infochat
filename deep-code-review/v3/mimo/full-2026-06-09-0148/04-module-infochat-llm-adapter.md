# Deep code review: module infochat-llm-adapter

**Target:** module infochat-llm-adapter
**Lens:** module
**Module path:** infochat-llm-adapter/
**Date:** 2026-06-09 01:48
**Reviewer:** senior-developer (mimo)

## Headline findings

- [medium] SIMPLIFICATION — `LlmRouterStartupGuard.java:272-293` — `isLoopback` performs a blocking DNS resolution via `InetAddress.getByName` at startup; a simpler `URI.getHost()` literal check would suffice for the documented threat and avoid blocking on DNS during boot.
- [low] SIMPLIFICATION — cross-cutting (test files) — `StubConfig implements Config` is copy-pasted 5 times across test files; a shared test utility would eliminate ~200 lines of duplication.

## Detail

### F1. Blocking DNS resolution in startup guard's isLoopback

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** `LlmRouterStartupGuard.java:272-293`

**Current code:**

```java
private static boolean isLoopback(String baseUrl) {
    URI uri;
    try {
        uri = new URI(baseUrl);
    } catch (URISyntaxException e) {
        LOG.warnf("LlmRouterStartupGuard: malformed base-url '%s' (treated as non-loopback): %s",
            baseUrl, e.getMessage());
        return false;
    }
    String host = uri.getHost();
    if (host == null || host.isEmpty()) {
        return false;
    }
    try {
        InetAddress addr = InetAddress.getByName(host);
        return addr.isLoopbackAddress();
    } catch (UnknownHostException e) {
        LOG.warnf("LlmRouterStartupGuard: DNS resolution failed for '%s' (treated as non-loopback): %s",
            host, e.getMessage());
        return false;
    }
}
```

**Why this is wrong / suboptimal / risky:**

The guard runs at `@Startup` (priority 150, between Flyway and OutboxRehydrator). `InetAddress.getByName(host)` performs a blocking DNS lookup on the JVM's startup thread. This has two issues:

1. **Blocking I/O on startup.** If the DNS resolver is slow or the operator has misconfigured a remote base-url pointing at an unreachable host, the startup guard blocks for the DNS timeout (typically 30s on Linux) before falling back to "treat as non-loopback." This delays boot unnecessarily.

2. **Complexity mismatch.** The guard's purpose is to catch the common case: an operator configured a cloud URL like `https://api.openai.com/v1`. For these, `URI.getHost()` returns `"api.openai.com"` which is trivially distinguishable from loopback literals (`localhost`, `127.0.0.1`, `::1`). The DNS resolution buys the ability to detect `/etc/hosts` aliases that point to loopback, but this is a marginal edge case: an operator who has set up a loopback alias for a cloud provider hostname has already done something unusual, and the per-call SSRF guard (`infochat-ssrf`) would catch the actual traffic anyway. The javadoc even acknowledges the DNS-rebind window as acceptable ("checked once at startup, not per call"), which undermines the precision argument for DNS.

The spec (`docs/spec/llm.md`) says "fails startup with a fatal log line identifying the offending task and provider" — it does not mandate DNS resolution specifically.

**Recommended fix:**

```java
private static final Set<String> LOOPBACK_HOSTS = Set.of(
    "localhost", "127.0.0.1", "[::1]", "::1"
);

private static boolean isLoopback(String baseUrl) {
    URI uri;
    try {
        uri = new URI(baseUrl);
    } catch (URISyntaxException e) {
        LOG.warnf("LlmRouterStartupGuard: malformed base-url '%s' (treated as non-loopback): %s",
            baseUrl, e.getMessage());
        return false;
    }
    String host = uri.getHost();
    if (host == null || host.isEmpty()) {
        return false;
    }
    // Strip IPv6 brackets — URI.getHost() returns "[::1]" for IPv6.
    String normalized = host.startsWith("[") && host.endsWith("]")
        ? host.substring(1, host.length() - 1)
        : host;
    return LOOPBACK_HOSTS.contains(host) || LOOPBACK_HOSTS.contains(normalized)
        || "0:0:0:0:0:0:0:1".equals(normalized);
}
```

**Reasoning:**

This eliminates the blocking DNS I/O at startup. The set of loopback literals is finite and well-defined (`localhost`, `127.0.0.1`, `::1`, `[::1]`, `0:0:0:0:0:0:0:1`). Any operator who has configured a `/etc/hosts` alias pointing a cloud hostname to loopback is doing something unusual enough that the per-call SSRF guard is the appropriate defense, not the startup-time local-only check.

The `UnknownHostException` catch currently treats DNS failure as non-loopback (fail-open for the local-only check). Removing DNS entirely means the guard no longer has a failure mode to handle — it's a pure string comparison.

**Trade-offs:**

- Loses the ability to detect custom `/etc/hosts` aliases pointing to loopback. This is a genuine loss but a marginal one: the documented threat is "operator accidentally routes to a cloud provider," not "operator deliberately creates a loopback alias for a cloud hostname."
- The `LOOPBACK_HOSTS` set is slightly more code than the current `InetAddress.getByName` approach, but it's pure string comparison with no I/O.

**Alternative options:**

- **Option A** (recommended above) — static set of loopback literals.
- **Option B** — keep `InetAddress.getByName` but add a timeout wrapper (e.g. `InetAddress` with a custom `NameService` or wrap in a `CompletableFuture` with a 2-second timeout) — pros: retains `/etc/hosts` alias detection — cons: adds complexity for a marginal edge case.
- **Option C** — keep as-is — pros: no code change — cons: blocking DNS on startup remains.

---

### F2. Copy-pasted StubConfig across 5 test files

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** `AnthropicProviderTest.java:331`, `OpenAiCompatibleProviderTest.java:153`, `AnthropicProviderMultiBlockContentTest.java:120`, `HttpProviderSharedPipelineTest.java:138`, `LlmRouterTest.java:333`

**Current code:**

Each of the five test files contains its own private `StubConfig implements Config` inner class. They are nearly identical: a map-backed `Config` stub with `getValue`, `getOptionalValue`, and a `convert` method supporting `String`, `Long`, and `Integer`. The only variation is which methods throw `UnsupportedOperationException` vs. are implemented — and all five implement the same subset.

**Why this is wrong / suboptimal / risky:**

This is approximately 200 lines of duplicated boilerplate. Per CLAUDE.md: "Three similar lines beats a premature abstraction." However, five copies of a 40-line class with identical structure and behavior has crossed that threshold. The duplication means any change to the stub's behavior (e.g. adding `Boolean` conversion) must be replicated five times, and the test files are already large enough that the inner class inflates them.

Engineering rules §7 ("No defensive code for impossible scenarios") does not apply here — this is test infrastructure, not production defensive code. But the coding style guideline "Simplify aggressively" and the general principle that test code should be as maintainable as production code apply.

**Recommended fix:**

Extract a package-private (or test-scope top-level) `StubConfig` class in `app.zcat.infochat.llm.impl` (or a shared test utilities package) and reference it from all five test files. Example:

```java
// src/test/java/app/zcat/infochat/llm/impl/StubConfig.java
package app.zcat.infochat.llm.impl;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Map-backed {@link Config} stub for unit tests. Supports
 * {@link String}, {@link Long}, and {@link Integer} conversion.
 */
final class StubConfig implements Config {
    private final Map<String, String> values;

    StubConfig(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    @Override
    public <T> T getValue(String propertyName, Class<T> propertyType) {
        String raw = values.get(propertyName);
        if (raw == null) {
            throw new java.util.NoSuchElementException(
                "StubConfig: no value for " + propertyName);
        }
        return convert(raw, propertyType);
    }

    @Override
    public ConfigValue getConfigValue(String propertyName) {
        throw new UnsupportedOperationException("getConfigValue not stubbed");
    }

    @Override
    public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
        String raw = values.get(propertyName);
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(convert(raw, propertyType));
    }

    @Override
    public <T> List<T> getValues(String propertyName, Class<T> propertyType) {
        throw new UnsupportedOperationException("getValues not stubbed");
    }

    @Override
    public <T> Optional<List<T>> getOptionalValues(String propertyName, Class<T> propertyType) {
        throw new UnsupportedOperationException("getOptionalValues not stubbed");
    }

    @Override
    public Iterable<String> getPropertyNames() {
        return values.keySet();
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return List.of();
    }

    @Override
    public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
        return Optional.empty();
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        throw new UnsupportedOperationException("unwrap not stubbed");
    }

    @SuppressWarnings("unchecked")
    private static <T> T convert(String raw, Class<T> type) {
        if (type == String.class) {
            return type.cast(raw);
        }
        if (type == Long.class || type == long.class) {
            return type.cast(Long.parseLong(raw));
        }
        if (type == Integer.class || type == int.class) {
            return type.cast(Integer.parseInt(raw));
        }
        if (type == Boolean.class || type == boolean.class) {
            return type.cast(Boolean.parseBoolean(raw));
        }
        throw new UnsupportedOperationException("StubConfig: unsupported type " + type);
    }
}
```

**Reasoning:**

One copy eliminates ~160 lines across the test suite. The `LlmRouterTest.StubConfig` has a slightly different shape (only implements `getOptionalValue`, throws on `getValue`) but that's because the router tests only call `getOptionalValue`. The unified version implements both, which is strictly more capable — the router tests will continue to work unchanged.

**Trade-offs:**

- Adds one file to the test tree. The net line count decreases by ~160.
- The `LlmRouterTest` stub currently throws on `getValue`; the unified version implements it. This is fine because the router tests never call `getValue`.

**Alternative options:**

- **Option A** (recommended above) — extract one shared `StubConfig`.
- **Option B** — leave as-is (five copies) — pros: no file changes — cons: duplication persists.
