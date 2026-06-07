# Deep code review: architecture

**Target:** architecture
**Lens:** architecture
**Date:** 2026-06-07
**Reviewer:** senior-developer (mimo-v2.5-pro)

## Headline findings

- **[F1] infochat-core violates its own module contract** -- Quarkus runtime annotations (`@ApplicationScoped`, `@ConfigProperty`, `@Startup`) in main sources of a module declared "Pure Java; no Quarkus, no I/O." MAINTAINABILITY-RULES-DRIFT, medium.
- **[F2] Module DAG enforcement is absent from the build** -- the spec and design notes assert the DAG is "enforced by the parent POM and verified in CI," but no maven-enforcer-plugin exists to catch violations. MAINTAINABILITY-RULES-DRIFT, medium.
- **[F3] TranslationProvider is misplaced in infochat-messaging-adapter** -- the spec (`llm.md`) says TranslationProvider is an LLM-layer SPI consumed by Provider, yet it lives in the messaging-adapter module, creating an implicit contract between sibling modules that the DAG forbids. MAINTAINABILITY-RULES-DRIFT, low.
- **[F4] NOTIFY payload shape agreement is stringly-typed with no shared contract** -- all three NOTIFY producers build JSON by hand-concatenation, all consumers parse by regex. A producer-side field rename silently breaks the consumer. MAINTAINABILITY-RULES-DRIFT, low.

## Detail

### F1. infochat-core violates its own module contract

**Category:** MAINTAINABILITY-RULES-DRIFT
**Severity:** medium
**Location:**
- `infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java` -- `@ApplicationScoped`, `@ConfigProperty`
- `infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java` -- `@ApplicationScoped`
- `infochat-core/src/main/java/app/zcat/infochat/core/audit/DefaultRedactionHook.java` -- `@ApplicationScoped`
- `infochat-core/src/main/java/app/zcat/infochat/core/startup/AbstractInstanceLockGuard.java` -- `import io.quarkus.runtime.Startup`
- `infochat-core/src/main/java/app/zcat/infochat/core/log/Redactor.java` -- `import io.quarkus.logging.LoggingFilter`

**Surface:** Module contract (design/09-reference.md, parent POM comments)

**Current code:** `infochat-core` main sources import and use Quarkus-specific annotations and types: `@ApplicationScoped` (Jakarta CDI), `@ConfigProperty` (MicroProfile Config), `@Startup` and `Quarkus` (Quarkus runtime), `@LoggingFilter` (Quarkus logging). These are compile-scoped via `provided` dependencies on `quarkus-core`, `jakarta.enterprise.cdi-api`, `microprofile-config-api`, and `jboss-logging`. The module compiles and produces a jar, but the jar is not a plain library -- it contains CDI bean classes that only function inside a Quarkus CDI container.

**Why wrong:** The design contract is explicit: `09-reference.md` states "infochat-core: Pure Java; no Quarkus, no I/O." The parent POM comment on the module says "NO Quarkus extensions live here so that downstream Quarkus apps stay in charge of which extensions their runtime pulls in." The actual code contradicts both statements. `ThrottledAdminNotifier` injects `DataSource` via `@Inject` and reads `@ConfigProperty` -- it is a fully wired Quarkus bean, not a plain Java class. `Redactor` uses `@LoggingFilter` which is a Quarkus-specific extension point. `AbstractInstanceLockGuard` imports `io.quarkus.runtime.Startup` and `Quarkus` for shutdown-on-failure. This means `infochat-core` is not a reusable, Quarkus-free library module; it is a Quarkus-application-scoped module that cannot be consumed by a non-Quarkus host without classpath errors at runtime.

**Recommended fix:** Move the Quarkus-dependent classes (`ThrottledAdminNotifier`, `AuditLogWriter`, `DefaultRedactionHook`, `AbstractInstanceLockGuard`, `Redactor`) out of `infochat-core` into a new `infochat-common` module (or directly into `infochat-collector` and `infochat-provider` where they are consumed). Keep `infochat-core` for the pure SPI types: `NormalizedPost`, `Fetcher`, `StreamSource`, `Sha256`, `TagNormalizer`, `JsonEscaper`, `AuditAction`, `RedactionHook` interface, `ContactIds`. Alternatively, update the module contract documentation to reflect the actual dependency shape -- but this is the weaker fix because it abandons the "plain library" architectural guarantee.

**Reasoning:** The module contract exists so that `infochat-core` can be reused outside Quarkus (test harnesses, future non-Quarkus hosts, CLI tools). If it is Quarkus-dependent, the contract is misleading and a future developer relying on the documented shape will get runtime failures.

**Trade-offs:** Extracting the Quarkus-dependent classes is a non-trivial refactor: `ThrottledAdminNotifier` is injected in both Collector (7+ call sites) and Provider (3+ call sites); `AuditLogWriter` is injected in 15+ Provider call sites and several Collector call sites. The refactor touches many files. However, the alternative (accepting the drift) permanently weakens the module boundary.

**Alternative options:** (1) Accept the drift and amend `09-reference.md` to say "infochat-core: domain entities, SPI types, and shared Quarkus beans." This is the lowest-effort path but abandons the architectural guarantee. (2) Move only `Redactor` and `AbstractInstanceLockGuard` (the ones with hard Quarkus runtime imports) and keep `AuditLogWriter`/`ThrottledAdminNotifier` in core (they use only `provided`-scope CDI annotations which compile without Quarkus -- but `@ConfigProperty` on `ThrottledAdminNotifier` is a MicroProfile runtime dependency).

---

### F2. Module DAG enforcement is absent from the build

**Category:** MAINTAINABILITY-RULES-DRIFT
**Severity:** medium
**Location:** `pom.xml` (parent), `docs/design/09-reference.md`

**Surface:** Module dependency DAG enforcement

**Current code:** The parent POM contains no `maven-enforcer-plugin`. The `<modules>` block lists the six modules but does not declare any `bannedDependencies` or `dependencyConvergence` rules. `09-reference.md` states: "Dependencies are strictly one-directional; the build fails if a cycle is introduced" and "Enforced by the parent POM and verified in CI; an attempt to add the dependency fails the build with a clear error." Neither statement is currently true at the build level.

**Why wrong:** The DAG contract ("infochat-collector MUST NOT depend on infochat-messaging-adapter," "The three sibling shared modules MUST NOT depend on each other") is documented as build-enforced but is actually only documented-as-text. A developer adding `infochat-messaging-adapter` as a dependency of `infochat-collector` in the POM will get a successful build. Maven's reactor ordering does not prevent circular or forbidden dependencies -- it only resolves the order of modules that are already declared. Without an enforcer rule, the DAG is enforced only by code review discipline.

**Recommended fix:** Add `maven-enforcer-plugin` with `<dependencyConvergence/>` and explicit `<bannedDependencies>` rules to the parent POM's `<build><plugins>` section. The banned-dependencies list should encode the five rules from `09-reference.md`:
- `infochat-core` must not depend on `infochat-ssrf`, `infochat-llm-adapter`, or `infochat-messaging-adapter`
- `infochat-ssrf` must not depend on `infochat-core`, `infochat-llm-adapter`, or `infochat-messaging-adapter`
- `infochat-llm-adapter` must not depend on `infochat-core`, `infochat-ssrf`, or `infochat-messaging-adapter`
- `infochat-messaging-adapter` must not depend on `infochat-core`, `infochat-ssrf`, or `infochat-llm-adapter`
- `infochat-collector` must not depend on `infochat-messaging-adapter`

**Reasoning:** The spec treats the DAG as a hard invariant ("the build fails if a cycle is introduced"). Without build enforcement, it is a soft invariant -- correct today by accident, fragile under future refactors.

**Trade-offs:** Adding the enforcer plugin is a one-time ~30-line parent POM addition with no runtime cost. The downside is that the banned-dependencies list must be maintained when new modules are added, but new modules are rare and the enforcer produces a clear error message on violation.

**Alternative options:** Rely on CI-level checks (a script that greps POM files for forbidden `<dependency>` entries). This is weaker because it runs only in CI, not locally.

---

### F3. TranslationProvider is misplaced in infochat-messaging-adapter

**Category:** MAINTAINABILITY-RULES-DRIFT
**Severity:** low
**Location:** `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/TranslationProvider.java`

**Surface:** Module DAG, SPI placement

**Current code:** `TranslationProvider` lives in `infochat-messaging-adapter` alongside `MessagingAdapter`, `ProgressNotifier`, and the adapter implementations. Its javadoc says it is a "presentation-layer translator for bot-authored prose" consumed by Provider. The spec (`llm.md` referenced in the javadoc) and `architecture.md` principle 6 list TranslationProvider as an SPI alongside `LlmProvider` and `EmbeddingProvider`.

**Why wrong:** The DAG has `infochat-llm-adapter` and `infochat-messaging-adapter` as sibling modules that MUST NOT depend on each other (`09-reference.md`). `TranslationProvider` is conceptually an LLM-layer SPI (it translates LLM-authored prose via LLM calls), yet it lives in the messaging-adapter module. If the `LlmTranslationProvider` implementation (in `infochat-provider`) needed to import `TranslationProvider` from `infochat-llm-adapter`, that would be natural. The current placement means `TranslationProvider` is co-located with messaging types despite having no transport dependency. The practical impact is low because both modules are consumed by `infochat-provider`, but the placement creates a misleading dependency direction: a future developer might assume translation is a messaging concern when it is actually an LLM concern.

**Recommended fix:** Move `TranslationProvider` to `infochat-llm-adapter`. This aligns with the spec's listing of translation as an LLM-layer SPI and with `LlmTranslationProvider` (the implementation) being conceptually an LLM impl. If `infochat-messaging-adapter` needs the type (e.g., `ProgressNotifier` might use it), then the sibling-modules-MUST-NOT-depend-on-each-other rule would need to be relaxed, which is a spec amendment. Alternatively, keep it in `infochat-messaging-adapter` but update the documentation to note the placement is for dependency-graph convenience, not conceptual grouping.

**Reasoning:** The current placement is functional but conceptually misaligned. The javadoc itself references `docs/spec/llm.md` as the source of truth, placing the concept squarely in the LLM domain. The `infochat-messaging-adapter` module comment in its POM lists `TranslationProvider` as a "Provider-side library jar: the presentation-layer SPI types (MessagingAdapter, TranslationProvider, ProgressNotifier)" -- grouping it with messaging types when it is not a messaging type.

**Trade-offs:** Moving it is a small refactor (one file, update imports in `LlmTranslationProvider` and `TranslationPipeline`). The risk is low. The alternative (documenting the placement rationale) is zero-effort but does not fix the conceptual mismatch.

**Alternative options:** Leave it and add a comment in `09-reference.md` explaining that `TranslationProvider` lives in `infochat-messaging-adapter` for dependency-graph convenience because it is consumed alongside the progress notifier.

---

### F4. NOTIFY payload shape agreement is stringly-typed with no shared contract

**Category:** MAINTAINABILITY-RULES-DRIFT
**Severity:** low
**Location:**
- Producers: `ReadyPromoter.java`, `PriceSnapshotStore.java`, `QuarantineNotifyEmitter.java`
- Consumers: `NewPostListener.java`, `QuarantineReviewListener.java`

**Surface:** Inter-service NOTIFY contract (architecture.md §Inter-service communication)

**Current code:** All three NOTIFY producers build JSON payloads by string concatenation (e.g., `"{\"ready_at\":\"" + readyAt.toString() + "\",\"post_id\":\"" + postId.toString() + "\"}"`). All consumers parse the payloads using regex patterns (e.g., `Pattern.compile("\"target_kind\"\\s*:\\s*\"([^\"]+)\"")`). The `ReadyPromoter` javadoc explicitly acknowledges this: "The contract is the JSON byte shape, not a shared class."

**Why wrong:** This is a documented, intentional design choice (the producer and consumer are in different modules that MUST NOT depend on each other). However, it means a field rename on the producer side (e.g., changing `ready_at` to `readyAt` for consistency) silently breaks the consumer. There is no compile-time or test-time check that the producer's JSON keys match the consumer's regex patterns. The `Architecture.md` payload spec and the javadoc comments are the only contract enforcement.

**Recommended fix:** Add integration tests that exercise the full NOTIFY round-trip: produce a NOTIFY with the exact JSON shape the producer emits, parse it with the consumer's regex, and assert field extraction. This catches producer-consumer drift at test time without requiring a shared module. Alternatively, define a shared constant class (field names as `static final String`) in `infochat-core` -- but this is a minor benefit for a minor cost.

**Reasoning:** The risk is low because the NOTIFY payload is simple (2-3 fields) and the field names are stable. The regex-based parsing is fragile in principle but the payloads are produced from closed sets (UUIDs, timestamps, enum names) so malformed JSON is unlikely. The real risk is a refactor that changes a field name in one place without updating the other.

**Trade-offs:** Adding round-trip integration tests is low-effort and catches the specific failure mode. A shared constant class adds a compile-time dependency from `infochat-core` to the NOTIFY shape, which may not be worth the coupling.

**Alternative options:** Accept the stringly-typed contract as documented. The payloads are simple enough that manual review catches drift.

---

## Synthesizer-relevant observations

- The module DAG as implemented is correct today: no forbidden dependencies exist in the POM files, no forbidden cross-module imports exist in the source code, and the three sibling modules (`infochat-ssrf`, `infochat-llm-adapter`, `infochat-messaging-adapter`) have no cross-dependencies. The finding is about the absence of build-time enforcement, not about a current violation.
- The `infochat-core` Quarkus-dependency drift (F1) is the most architecturally significant finding. It does not cause a build failure today because the Quarkus dependencies are `provided`-scoped, but it undermines the module's documented purpose as a "plain library jar: SPI types and shared utilities. Pure Java; no Quarkus, no I/O."
- The NOTIFY producer-consumer pattern (three producers in `infochat-collector`, three consumers in `infochat-provider`) is correctly structured: every NOTIFY commits-or-rolls-back with its side effect (same-transaction rule), every consumer uses a high-water mark for catch-up correctness, and the `new_price_snapshot` channel uses cache-flush-on-reconnect as its correctness mechanism. No architectural gap in the NOTIFY reliability model itself.
- The `supportsMarkdownLinks=false` invariant is correctly enforced at startup via `AdapterRegistry` gate 3 (line 180-185 of `AdapterRegistry.java`), and all three adapters (`SimpleXAdapter`, `SignalAdapter`, `InMemoryAdapter`) declare `false` explicitly. This is well-implemented.
- The `CapabilityFlags` record includes fields not mentioned in the spec's minimum set (`supportsMultilineCode`, `supportsAttachments`, `supportsThreading`, `maxMessageBytes`, `maxInboundMessageBytes`, `maxInflightSends`, `maxSendsPerSecond`). These are implementation-tier additions. The spec says "Future flags extend this list; v1 ships only the above" -- the "above" list in the spec includes 8 flags; the record has 14 fields. This is a design-notes addition, not a spec violation, but the spec-vs-implementation surface has grown beyond what the spec documents.
