# Deep code review: module infochat-llm-adapter

**Target:** module infochat-llm-adapter
**Lens:** module
**Module path:** infochat-llm-adapter/
**Date:** 2026-06-07
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [medium] SECURITY — `OpenAiCompatibleProvider.java:116-128` — API key, base URL, and model name are `@ConfigProperty` fields stored in plain instance variables; SmallRye Config's default behavior logs config values at startup unless explicitly filtered, risking API-key exposure in startup logs
- [medium] MAINTAINABILITY-RULES-DRIFT — `LlmRouter.java:108-119` — the test constructor takes a `List<Entry>` but the CDI constructor resolves entries from `Instance<LlmProvider>` dynamically; the two constructors share no common state-initialization path
- [low] PERFORMANCE — `OpenAiCompatibleProvider.java:143` — `HttpClient.newHttpClient()` uses default executor (cached thread pool); for virtual-thread Quarkus, a `HttpClient.Builder.executor(Executors.newVirtualThreadPerTaskExecutor())` would avoid carrier-thread pinning during blocking I/O
- [low] SIMPLIFICATION — `OpenAiCompatibleProvider.java:101` — `ObjectMapper` is a static final field; Jackson recommends `ObjectMapper` reuse but the instance is never configured, so the JDK's built-in `Json.createObjectBuilder()` (Jakarta JSON-P) would avoid the dependency entirely

## Detail

### F1. API key in plain @ConfigProperty fields — startup log exposure risk

- **Category:** SECURITY
- **Severity:** medium
- **Location:** `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java:116-128`

**Current code:**

```java
@ConfigProperty(name = "infochat.llm.security.api-key")
Optional<String> securityApiKey;
```

**Why this is wrong / suboptimal / risky:**

SmallRye Config (Quarkus' config implementation) logs every resolved `@ConfigProperty` value at startup in the `application.properties` dump, unless the key is explicitly filtered via `quarkus.log.log-filter`. The API key is a `String` field — SmallRye has no way to know it's a secret without explicit configuration. An operator who runs with `-Dquarkus.log.level=DEBUG` (common during initial setup) will see the API key in plaintext in the startup log.

The `Redactor` filter in `infochat-core` (which redacts API-key-shaped strings) operates at the `java.util.logging` level and catches `sk-*` patterns in the log output, but only AFTER the log message is formatted. SmallRye Config's startup dump may emit the API key BEFORE the redaction filter is installed, or through a different log path (Quarkus bootstrap logging).

The spec-level commitment (`security.md` §Secrets handling: "LLM API keys are read from environment variables, not the DB") is satisfied — the key is from config, not the DB. But the defense-in-depth of not logging the key is not enforced structurally.

**Recommended fix:**

Add `quarkus.log.log-filter` or a custom `@ConfigProperty` interceptor that masks the value at read time, or use a `Secret` wrapper type. Alternatively, document in `deployment.md` that operators MUST set `quarkus.log.category."io.smallrye.config".level=WARN` in production.

At minimum, add a `@PostConstruct` log message that explicitly states the API key is configured (without printing the value) so operators can verify the config was picked up without a DEBUG dump.

**Reasoning:**

The redactor catches API-key patterns in application log lines, but SmallRye Config's startup dump may bypass it. Adding explicit guidance or a structural guard closes the gap.

**Trade-offs:**

- A `Secret` wrapper type adds a dependency on a Quarkus extension or a hand-rolled type.
- Disabling DEBUG logging for SmallRye Config is a one-line operator-side fix.
- The risk is low: the startup log is operator-local (not sent over the network), and the key is already in `application.properties` which the operator controls.

---

### F2. Two constructors share no common initialization path

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:108-119`

**Current code:**

```java
// Test constructor:
public LlmRouter(List<Entry> entries, ConfigReader config) {
    if (entries.isEmpty()) { throw ...; }
    this.entries = List.copyOf(entries);
    this.config = config;
    // builds entriesByName from entries
}

// CDI constructor (annotated @Inject):
public LlmRouter(Instance<LlmProvider> providers, Config config) { ... }
```

**Why this is wrong / suboptimal / risky:**

The two constructors duplicate the field-initialization logic (entries list copy, entries-by-name map build, validation). The validation in the test constructor (`entries.isEmpty()`) is not replicated in the CDI constructor — if CDI resolves zero providers (e.g., misconfiguration in a test profile), the CDI constructor would produce a router with an empty entry list that fails at first `forTask()` call rather than at construction time. The `LlmRouterStartupGuard` catches this at startup, but the guard is in a separate class; a future test that constructs the router directly without the guard would miss the validation.

**Recommended fix:**

Extract the common initialization into a private `init()` method called by both constructors, or have the CDI constructor delegate to the test constructor after resolving providers into entries.

**Reasoning:**

Single source of truth for initialization. The CDI constructor should reject an empty provider set at construction time, matching the test constructor's behavior.

**Trade-offs:**

- CDI constructor delegation to the test constructor adds one level of indirection.
- The startup guard already catches this in production; the risk is test-only.

---

### F3. HttpClient default executor on virtual-thread Quarkus

- **Category:** PERFORMANCE
- **Severity:** low
- **Location:** `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java:143`

**Current code:**

```java
public OpenAiCompatibleProvider() {
    this.http = HttpClient.newHttpClient();
}
```

**Why this is wrong / suboptimal / risky:**

`HttpClient.newHttpClient()` uses the default executor, which is a cached thread pool. On Quarkus with virtual threads enabled (JDK 25), the blocking I/O in `http.send()` pins a carrier thread. Using `HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build()` would let the JDK's HTTP client use virtual threads for its internal connection-management work, avoiding carrier-thread pinning.

The practical impact is negligible for the v1 Collector's LLM call volume (a few calls per second at most). The finding is about future-proofing: as chat-mode LLM call volume grows in the Provider, the default cached-thread-pool executor could become a bottleneck.

**Recommended fix:**

```java
this.http = HttpClient.newBuilder()
    .executor(Executors.newVirtualThreadPerTaskExecutor())
    .build();
```

**Reasoning:**

Aligns with the project's JDK 25 + virtual-thread commitment.

**Trade-offs:**

- Virtual-thread-per-task executor creates threads unboundedly; the JDK HttpClient's internal connection pool already bounds concurrency, so the unbounded executor is safe.
- The default executor works correctly and the performance difference is unmeasurable at v1 call volumes.

---

### F4. Static ObjectMapper — Jakarta JSON-P would avoid the dependency

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java:101`

**Current code:**

```java
private static final ObjectMapper JSON = new ObjectMapper();
```

**Why this is wrong / suboptimal / risky:**

The `OpenAiCompatibleProvider` uses Jackson `ObjectMapper` solely to construct JSON request bodies and parse JSON responses. JDK 25 includes `java.net.http` natively, but JSON-P (Jakarta JSON Processing, `jakarta.json`) is already on the classpath via Quarkus. Using `jakarta.json.Json.createObjectBuilder()` for request construction and `jakarta.json.Json.createReader()` for response parsing would remove the Jackson dependency from this module entirely.

The Jackson dependency is declared in `infochat-llm-adapter/pom.xml` (per the POM's comments, it was added for this class). Removing it would shrink the dependency tree and simplify the build.

**Reasoning:**

The class uses Jackson for simple JSON object construction (model, messages array, system/user roles) and simple JSON path extraction (`choices[0].message.content`). Jakarta JSON-P handles both with standard-library APIs and no additional dependency.

**Trade-offs:**

- Jackson's `ObjectMapper` is more ergonomic for nested JSON construction than JSON-P's builder pattern.
- The Jackson dependency is already pulled in by other modules (messaging-adapter uses it for JSON parsing).
- Removing Jackson from this module doesn't remove it from the classpath if other modules still depend on it.
