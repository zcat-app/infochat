# Deep code review: module infochat-provider

**Target:** module infochat-provider
**Lens:** module
**Module path:** infochat-provider/
**Date:** 2026-06-07
**Reviewer:** senior-developer (deepseek)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — `InboundRouter.java:57-100` — the intake pipeline implements 10 authorization steps with the correct execution order but the step numbering (1, 1.5, 1.7, 2, 3, 4, 3.5, 4.1, 5, 6, 7, 8, 9, 10) is a spec cross-reference, not linear; a developer reading the code for the first time must map labels to execution order
- [medium] PERFORMANCE — `ChatToolDispatcher.java:42` — `Map<String, String> cache` uses `HashMap` with String keys for tool-call dedup within a turn; `HashMap` is fine for this scale but the cache has no size bound, so a misbehaving LLM making many unique tool calls within one turn could fill it
- [medium] SECURITY — `ChatToolDispatcher.java:78` — the `tools` map is hardcoded at construction; any tool added to `ChatToolRegistry` but missing from this map fails at startup via `requireHandlerForEveryAdvertisedTool`, which is the correct safety check; the check should be documented as blocking the spec's "every tool name appears verbatim in the agent's tool registry" invariant
- [low] SIMPLIFICATION — `InboundRouter.java` — the intake pipeline method is long (~300 lines) with 10 sequentially-numbered steps; extracting each step to a private method named after the step (e.g., `step1_5_rateCap()`, `step1_7_normalizeBody()`) would make the pipeline structure visible at a glance
- [low] MAINTAINABILITY-RULES-DRIFT — `SummaryProseGenerator.java` — LLM-generated prose is passed through `LlmOutputSanitizer` for the admin-command regex before delivery; the sanitizer's match set is derived from the privileged-tier command list (per spec), but adding a new admin command requires a coordinated sanitizer update — this coupling is correct per spec but should be documented in both files

## Detail

### F1. Authorization step numbering is spec cross-reference, not linear

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:57-100`

**Current code:**

The Javadoc lists steps as:
```
1   identity
1.5 transport-level rate cap
1.7 Unicode normalize
2   DM unknown contact → invite code
3   Group unregistered → silent drop
4   ban check (fires BEFORE 3.5)
3.5 D47 approval gate
4.1 auto-promote
5   (reserved)
6   parse command
7   permission check
8   audit-log
9   execute
10  LLM (chat-mode only)
```

**Why this is wrong / suboptimal / risky:**

The step numbering follows the spec's stable cross-reference labels (`security.md` §Authorization model), not linear execution order. Step 4 (ban check) executes after step 3 AND before step 3.5, because the spec labels are stable identifiers, not execution indices. A developer reading the code for the first time might assume the numeric ordering is linear and miss that step 4 fires between steps 3 and 3.5.

The spec explicitly documents this ("numeric order matches execution order EXCEPT that step 4 executes after step 3 and before step 3.5"). The code faithfully implements the spec's ordering. The finding is that the non-linear numbering is an ongoing cognitive load: every developer who reads this method must internalize the "steps are labels, not indices" rule.

**Recommended fix:**

Add a one-line comment at the top of `onMessage`: "Step labels below match security.md §Authorization model numbering — they are stable cross-reference identifiers, not linear execution indices. The actual execution order follows this list top-to-bottom."

**Reasoning:**

Makes the label-vs-order distinction visible at the point where a developer starts reading the method.

**Trade-offs:**

- None — the fix is strictly better.

---

### F2. Tool-call cache has no size bound

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java:42`

**Current code:**

```java
public static class TurnContext {
    private final Map<String, String> cache = new HashMap<>();
    ...
}
```

**Why this is wrong / suboptimal / risky:**

The `HashMap` caches identical tool calls within a single chat turn to avoid re-querying the DB for the same arguments. The LLM is limited to `DEFAULT_CALL_CAP = 25` tool calls per turn (enforced by `callCount`), so the cache can grow to at most 25 entries. This is fine — 25 String keys + 25 String values is <10 KB.

However, there is no explicit coupling between the `callCap` and the cache's maximum size. A future change that increases the `callCap` (e.g., to 100 for a more complex chat agent) would silently increase the cache's maximum size. The cache should be bounded at the same cap as the call count.

**Recommended fix:**

Initialize the `HashMap` with `new HashMap<>(callCap)` to pre-size it, and document that the call count cap is also the cache's effective size bound.

**Reasoning:**

Makes the coupling between call cap and cache size explicit.

**Trade-offs:**

- None — the fix is strictly better.

---

### F3. Tool registry completeness check should document the spec invariant

- **Category:** SECURITY
- **Severity:** medium
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java:94-100`

**Current code:**

```java
private static void requireHandlerForEveryAdvertisedTool(
        ChatToolRegistry registry, Map<String, ChatToolRegistry.ChatTool> tools) {
    // throws if registry advertises a tool that tools doesn't handle
}
```

**Why this is wrong / suboptimal / risky:**

This check correctly enforces the spec's commitment that "every tool name appears verbatim in the agent's tool registry; nothing else is callable" (`security.md` §Prompt-injection defenses). If a developer adds a tool to `ChatToolRegistry` but forgets to add the handler to the `tools` map, the startup check catches it. This is correct.

The finding is that the check's relationship to the spec invariant is not documented. A developer modifying the tool surface should know that this check is what enforces the spec's "closed tool allowlist" commitment, and that the verification in `verification.md` ("CI fails on a mismatch in either direction") is implemented by this exact method.

**Recommended fix:**

Add a Javadoc comment: "Enforces security.md §Prompt-injection defenses: every tool advertised to the LLM must have a registered handler. CI fails if this check ever fires."

**Reasoning:**

Connects the implementation guard to the spec invariant it enforces.

**Trade-offs:**

- None — the fix is strictly better.

---

### F4. Long intake pipeline method — extract step-level methods

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java`

**Why this is wrong / suboptimal / risky:**

The `onMessage` method implements ~10 authorization steps in a single method body. Each step is clearly commented with its spec label, but the method's length makes it harder to verify at a glance that all steps are present and in the correct order. Extracting each step to a named private method would make the pipeline structure self-documenting.

The current design is readable and correct — the steps are clearly labeled with Javadoc comments. The finding is a style preference, not a bug.

**Recommended fix:**

Not urgent for v1. If the pipeline adds more steps in v2, consider extracting each step to a private method: `step1_5_rateCap()`, `step1_7_normalizeBody()`, `step2_inviteCode()`, etc. The main `onMessage` becomes a linear sequence of method calls whose names match the spec's step labels.

**Reasoning:**

Self-documenting structure. Method names become the pipeline's table of contents.

**Trade-offs:**

- Adds ~10 private methods (but each is short and testable in isolation).
- The current monolithic method is easier to read as a "spec in code" narrative.

---

### F5. LlmOutputSanitizer / privileged-command coupling

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java`

**Why this is wrong / suboptimal / risky:**

The sanitizer's match set is derived from the closed privileged-tier command list in `commands.md`. Adding a new admin command (e.g., a future `/source-config`) requires adding the pattern to the sanitizer in the same commit. The spec documents this ("CI fails on a mismatch"), but the sanitizer's Javadoc should explicitly reference the privileged-tier list as the authoritative source of its match set, so a developer adding a new admin command knows where the corresponding sanitizer pattern lives.

**Recommended fix:**

Add a Javadoc comment: "Match set derived from commands.md §Permission model privileged-tier closed list. Adding a new admin command requires a corresponding entry here; CI fails on mismatch."

**Reasoning:**

Cross-reference that prevents drift between the command list and the sanitizer.

**Trade-offs:**

- None — the fix is strictly better.
