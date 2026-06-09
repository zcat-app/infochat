# Deep code review: module infochat-llm-adapter

**Target:** module infochat-llm-adapter
**Lens:** module
**Module path:** infochat-llm-adapter/
**Date:** 2026-06-09 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] SECURITY — LlmRouterStartupGuard.java:286 — the local-only privacy guard's loopback check inspects only the first DNS-resolved address, so a multi-A-record host whose first record is loopback passes the guard while traffic can go off-host.
- [low] MAINTAINABILITY-RULES-DRIFT — OpenAiCompatibleProvider.java:176, AnthropicProvider.java:141, OpenAiCompatibleEmbeddingProvider.java:129 — `catch (RuntimeException | JsonProcessingException e)` around JSON node assembly catches a `RuntimeException` that the assembly cannot throw — defensive code inside a trust boundary (§7).
- [low] SIMPLIFICATION — OpenAiCompatibleEmbeddingProvider.java:145-165 — the embedding provider re-implements the send / non-2xx / clamp block that `LlmHttpSupport.executeJsonCall` already single-sources for the two chat providers, so the non-2xx failure surface can drift between the two paths.

## Detail

### F1. Local-only loopback guard trusts only the first resolved IP

- **Category:** SECURITY
- **Severity:** medium
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java:285-292

**Current code:**

```java
try {
    InetAddress addr = InetAddress.getByName(host);
    return addr.isLoopbackAddress();
} catch (UnknownHostException e) {
    LOG.warnf("LlmRouterStartupGuard: DNS resolution failed for '%s' (treated as non-loopback): %s",
        host, e.getMessage());
    return false;
}
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/llm.md` §Per-task routing rules states the local-only posture is "a privacy and data-leakage commitment (post bodies must not leave the host)" and the guard exists specifically so "an operator cannot accidentally route one task remote while believing the deployment is local-only." The guard's whole job is to be the assurance that nothing leaves the host.

`InetAddress.getByName(host)` returns only the *first* address the resolver hands back. A host that resolves to multiple A/AAAA records — e.g. a name configured with both a `127.0.0.1` record and a public-IP record, or a round-robin name whose ordering is non-deterministic — passes `isLoopbackAddress()` whenever loopback happens to sort first, yet the underlying `HttpClient` is free to connect to any of the resolved addresses (and on a later boot or after a DNS change, to the non-loopback one). The result is a deployment that boots clean under `local-only=true` while post title+summary text reaches an off-host endpoint — exactly the silent leak the guard promises to prevent.

This is a startup-time, best-effort check (the per-call SSRF defense lives in `infochat-ssrf` and is deliberately not on the LLM path), so the realistic trigger is operator misconfiguration rather than an active attacker. But the guard is the only thing standing behind a stated data-leakage commitment, and "loopback is one of several resolved IPs" is a plausible misconfiguration, not an impossible one. A guard that only inspects one address gives false assurance.

**Recommended fix:**

```java
try {
    InetAddress[] addrs = InetAddress.getAllByName(host);
    // local-only is a data-leakage commitment: the host counts as
    // on-host only if EVERY resolved address is loopback. A single
    // non-loopback record means traffic can reach off-host.
    for (InetAddress addr : addrs) {
        if (!addr.isLoopbackAddress()) {
            return false;
        }
    }
    return addrs.length > 0;
} catch (UnknownHostException e) {
    LOG.warnf("LlmRouterStartupGuard: DNS resolution failed for '%s' (treated as non-loopback): %s",
        host, e.getMessage());
    return false;
}
```

**Reasoning:**

`getAllByName` returns the full resolution set. Requiring *all* records to be loopback before declaring the host on-host closes the multi-record gap: any single off-host address fails the check and the guard refuses startup, which is the conservative direction for a privacy commitment. The `UnknownHostException` arm is unchanged (a failed lookup already counts as non-loopback, per the existing comment).

**Trade-offs:**

None of consequence. The check runs once at startup; iterating a handful of addresses is free. A pathological host with a loopback and a non-loopback record now fails `local-only` startup instead of booting — which is the intended behavior for this guard.

---

### F2. Defensive `catch (RuntimeException ...)` around non-throwing JSON assembly

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java:165-179 (mirrored in AnthropicProvider.java:122-144 and OpenAiCompatibleEmbeddingProvider.java:120-132)

**Current code:**

```java
try {
    ObjectNode root = JSON.createObjectNode();
    root.put("model", cfg.model());
    ArrayNode messages = root.putArray("messages");
    ObjectNode system = messages.addObject();
    system.put("role", "system");
    system.put("content", systemPrompt);
    ObjectNode user = messages.addObject();
    user.put("role", "user");
    user.put("content", userPrompt);
    body = JSON.writeValueAsString(root);
} catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
    throw new LlmCallFailedException(
        "OpenAiCompatibleProvider: failed to assemble request body", e);
}
```

**Why this is wrong / suboptimal / risky:**

Engineering rule §7 ("No defensive code for impossible scenarios") forbids try/catch around operations that cannot throw inside a trust boundary. Of the statements in this block, only `JSON.writeValueAsString(root)` throws a checked `JsonProcessingException`. The `createObjectNode` / `putArray` / `put` / `addObject` calls operate on in-memory `JsonNode` builders with `String` and primitive arguments — they have no failure mode. Catching `RuntimeException` here is a "just in case" arm over code that cannot produce one; if one ever did (a Jackson bug), wrapping it as `LlmCallFailedException` would mislabel an internal defect as an infrastructure failure that the Stage 2 worker silently retries, hiding the bug.

The same over-broad catch is copied into all three providers, so the pattern propagates.

**Recommended fix:**

```java
String body = assembleBody(cfg, systemPrompt, userPrompt);
...
private static String assembleBody(TaskConfig cfg, String systemPrompt, String userPrompt) {
    ObjectNode root = JSON.createObjectNode();
    root.put("model", cfg.model());
    ArrayNode messages = root.putArray("messages");
    ObjectNode system = messages.addObject();
    system.put("role", "system");
    system.put("content", systemPrompt);
    ObjectNode user = messages.addObject();
    user.put("role", "user");
    user.put("content", userPrompt);
    try {
        return JSON.writeValueAsString(root);
    } catch (JsonProcessingException e) {
        throw new LlmCallFailedException(
            "OpenAiCompatibleProvider: failed to serialize request body", e);
    }
}
```

**Reasoning:**

The catch narrows to the only statement that can actually throw (`writeValueAsString`), and to its declared checked type. Node assembly stays outside the try, so it reads as what it is — infallible construction — and a genuine internal defect there surfaces as an uncaught error rather than a counterfeit "infra failure." This matches §7's rule that try/catch is for operations that can throw, not for blocks that merely contain one.

**Trade-offs:**

None — the fix is strictly better. It is slightly more code if extracted to a helper; the catch can equally stay inline wrapping only the `writeValueAsString` line if a helper is unwanted.

---

### F3. Embedding provider duplicates the shared send / non-2xx / clamp pipeline

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java:145-167

**Current code:**

```java
HttpResponse<String> response;
try {
    response = http.send(request,
        LlmHttpSupport.boundedStringHandler(LlmHttpSupport.clampBodyCapBytes(maxResponseBytes)));
} catch (IOException e) {
    throw new EmbeddingCallFailedException(
        "OpenAiCompatibleEmbeddingProvider: HTTP call failed for " + uri, e);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new EmbeddingCallFailedException(
        "OpenAiCompatibleEmbeddingProvider: HTTP call interrupted for " + uri, e);
}

if (response.statusCode() < 200 || response.statusCode() >= 300) {
    String preview = LlmHttpSupport.preview(response.body());
    LOG.warnf("OpenAiCompatibleEmbeddingProvider: non-2xx %d from %s; body preview: %s",
        response.statusCode(), uri, preview);
    throw new EmbeddingCallFailedException(
        "OpenAiCompatibleEmbeddingProvider: non-2xx status " + response.statusCode()
            + " from " + uri);
}

return parseEmbeddings(response.body(), uri, texts.size());
```

`LlmHttpSupport.executeJsonCall` (LlmHttpSupport.java:108-136) already single-sources exactly this send / clamp / IOException / InterruptedException / non-2xx sequence so "the response-cap and failure-surface contract cannot drift between the two providers." The embedding provider re-implements all of it by hand.

**Why this is wrong / suboptimal / risky:**

The whole point of `executeJsonCall` per its own javadoc is that the cap-and-failure-surface contract is "single-sourced here so [it] cannot drift between the two providers." The embedding provider is a third HTTP caller against the same wire family and reproduces the identical block with one behavioral difference that is easy to miss: it reads its cap from `infochat.embeddings.max-response-bytes` while `executeJsonCall` reads `infochat.llm.max-response-bytes`, and its non-2xx throw omits the body preview that the shared path includes in the exception message (it logs the preview but does not attach it to the exception). These are exactly the kind of small divergences the hoist was created to prevent; a future edit to the shared failure surface will silently skip the embedding provider.

The reason it cannot use `executeJsonCall` as-is is that the helper is typed to `LlmResponseParser` returning `LlmResponse`, whereas embedding parsing returns `List<EmbeddingResult>`. That is a fixable typing limitation, not a fundamental difference.

**Recommended fix:**

Generify the shared pipeline so all three callers route through one send/clamp/failure path:

```java
@FunctionalInterface
interface ResponseParser<T> {
    T parse(String responseBody, URI uri);
}

static <T> T executeJsonCall(HttpClient http, long capBytes, HttpRequest request,
                             String providerLabel, ResponseParser<T> parser,
                             Function<String, RuntimeException> failureFactory) {
    // ... shared send / clamp / non-2xx body-preview logic, throwing
    //     failureFactory.apply(message) so each caller keeps its own
    //     exception type ...
}
```

The chat providers pass `LlmCallFailedException::new`; the embedding provider passes `EmbeddingCallFailedException::new` and a parser returning `List<EmbeddingResult>`. The cap source stays per-caller (passed in as `capBytes`).

**Reasoning:**

One send/failure path means the non-2xx wording, the body-preview-in-exception decision, the InterruptedException re-interrupt, and the clamp all live in one place for every HTTP caller in the module — which is the invariant the existing hoist already commits to for two of the three. The generic type parameter is the minimal change that lets the embedding parser participate.

**Trade-offs:**

Adds a type parameter and a factory argument to a package-private helper, which is marginally more abstract than the current two-caller version. The payoff is one fewer hand-maintained copy of the failure surface. If the divergence is judged acceptable (the helper's javadoc already scopes itself to "the two chat-completion HTTP providers"), this can be left as-is — hence low severity. At minimum, align the embedding provider's non-2xx exception message to include the body preview so the two exception surfaces match.

**Alternative options:**

- **Option A** (recommended) — generify `executeJsonCall` and route all three callers through it.
- **Option B** — leave the duplication but add a one-line comment in the embedding provider pointing at `executeJsonCall` as the sibling contract, and align the non-2xx exception message — pros: zero behavioral risk, smallest diff; cons: the two paths can still drift on the next edit.
