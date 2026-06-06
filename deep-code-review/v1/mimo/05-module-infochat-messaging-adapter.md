# Deep code review: module infochat-messaging-adapter

**Target:** module infochat-messaging-adapter
**Lens:** module
**Module path:** infochat-messaging-adapter/
**Date:** 2026-06-01 23:55
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] MAINTAINABILITY-RULES-DRIFT — InMemoryAdapter.java:61 — supportsCodeFormatting=false contradicts design rationale (design line 813+873: "exercises the markdown-code render path")
- [medium] MAINTAINABILITY-RULES-DRIFT — SimpleXAdapter.java:64-78 — multiple capability values drift from design §6.4.2 (maxMessageBytes 2000 vs 4000, minEditInterval ZERO vs 600ms, maxSendsPerSecond 8 vs 5)
- [medium] MAINTAINABILITY-RULES-DRIFT — SignalAdapter.java:70-84 — multiple capability values drift from design §6.5.2 (maxMessageBytes 2000 vs 8000, minEditInterval ZERO vs 600ms, supportsCodeFormatting false vs true)
- [low] MAINTAINABILITY-RULES-DRIFT — SignalAdapter.java:174 — start() throws IllegalStateException rather than MessagingException, unlike SimpleXAdapter.start() which uses categorised MessagingException

## Detail

### F1. InMemoryAdapter supportsCodeFormatting contradicts design rationale

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java:61

**Current code:**

```java
private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
        /* supportsMentionByContactId */ true,
        /* supportsMembershipEvents   */ true,
        /* supportsCodeFormatting     */ false,  // <--- here
        /* supportsMarkdownLinks      */ false,
        /* supportsMultilineCode      */ true,
        /* supportsAttachments        */ false,
        /* supportsThreading          */ false,
        /* maxMessageBytes            */ 100_000,
        /* maxInboundMessageBytes     */ 100_000,
        /* maxInflightSends           */ 1_000,
        /* maxSendsPerSecond          */ 10_000,
        /* supportsMessageEdit        */ true,
        /* supportsTypingIndicator    */ true,
        /* minEditInterval            */ Duration.ZERO);
```

**Why this is wrong / suboptimal / risky:**

The design document (`docs/design/06-messaging.md`) explicitly states at line 813:

```
true,            // supportsCodeFormatting — exercises the markdown-code render path
```

And at line 873 provides the rationale:

> InMemoryAdapter.capabilities() declares `supportsCodeFormatting = true` so tests exercise the code-formatting render path; the SimpleX adapter declares it false so tests of the plain-text fallback also run.

The design's test-coverage strategy is: InMemoryAdapter exercises the code-formatting path, SimpleX exercises the plain-text fallback. With `supportsCodeFormatting = false` on InMemoryAdapter, the code-formatting render path through ProgressNotifier and Provider's output layer has no adapter exercising it in the test suite. The capability value was silently set to `false` in the implementation, contradicting both the design value and its rationale.

**Recommended fix:**

```java
        /* supportsCodeFormatting     */ true,
```

**Reasoning:**

The design explicitly intended InMemoryAdapter to declare `supportsCodeFormatting = true` so the test-time deployment shape exercises the monospace render path. The SimpleX adapter deliberately declares `false` to test the fallback. Both paths need coverage; flipping InMemoryAdapter back to `true` restores the intended test matrix.

**Trade-offs:**

None — the fix is strictly better. It restores the design-intended test coverage.

---

### F2. SimpleXAdapter capability values drift from design

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:64-78

**Current code:**

```java
private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
        /* supportsMentionByContactId */ true,
        /* supportsMembershipEvents   */ false,
        /* supportsCodeFormatting     */ false,
        /* supportsMarkdownLinks      */ false,
        /* supportsMultilineCode      */ false,
        /* supportsAttachments        */ false,
        /* supportsThreading          */ false,
        /* maxMessageBytes            */ 2_000,
        /* maxInboundMessageBytes     */ 16_384,
        /* maxInflightSends           */ 4,
        /* maxSendsPerSecond          */ 8,
        /* supportsMessageEdit        */ true,
        /* supportsTypingIndicator    */ true,
        /* minEditInterval            */ Duration.ZERO);
```

**Why this is wrong / suboptimal / risky:**

Three values diverge from the design's declared values in §6.4.2:

| Flag | Implementation | Design §6.4.2 |
|---|---|---|
| `maxMessageBytes` | 2,000 | 4,000 ("SimpleX hard limit; adapter chunks above this") |
| `maxSendsPerSecond` | 8 | 5 ("conservative, raise after observing") |
| `minEditInterval` | `Duration.ZERO` | 600ms ("conservative floor; refine after observation") |

The code comment at line 63 acknowledges "best-guess defaults not fixed by spec and are expected to be tuned against a live simplex-chat in M1-105." However, the design document carries explicit values with rationale. The `maxMessageBytes` discrepancy is the most consequential: the design says 4,000 is SimpleX's hard limit, but the implementation uses 2,000, which means the adapter will chunk messages at half the protocol's actual limit — wasting outbound sends for messages between 2 KB and 4 KB. The `minEditInterval` of `Duration.ZERO` removes the coalescing floor entirely, which means the progress notifier will attempt to send every intermediate update to SimpleX, potentially hitting rate limits unnecessarily.

**Recommended fix:**

Align with the design values. If the values were deliberately changed during implementation, the design document should be updated to reflect the new rationale. Concretely:

```java
        /* maxMessageBytes            */ 4_000,
        /* maxInflightSends           */ 4,
        /* maxSendsPerSecond          */ 5,
        /* supportsMessageEdit        */ true,
        /* supportsTypingIndicator    */ true,
        /* minEditInterval            */ Duration.ofMillis(600),
```

**Reasoning:**

The design values carry explicit rationale. If the implementation values are the result of tuning against a live simplex-chat, the design document should be updated so the two do not drift. Keeping the design as the single source of truth for capability values prevents future developers from re-discovering the same tuning decisions.

**Trade-offs:**

If the implementation values were chosen based on empirical testing not yet reflected in the design, updating to design values could regress behavior. The correct fix path is: verify against a live simplex-chat, then update whichever document (implementation or design) is wrong.

---

### F3. SignalAdapter capability values drift from design

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java:70-84

**Current code:**

```java
private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
        /* supportsMentionByContactId */ true,
        /* supportsMembershipEvents   */ true,
        /* supportsCodeFormatting     */ false,
        /* supportsMarkdownLinks      */ false,
        /* supportsMultilineCode      */ false,
        /* supportsAttachments        */ false,
        /* supportsThreading          */ false,
        /* maxMessageBytes            */ 2_000,
        /* maxInboundMessageBytes     */ 16_384,
        /* maxInflightSends           */ 4,
        /* maxSendsPerSecond          */ 8,
        /* supportsMessageEdit        */ true,
        /* supportsTypingIndicator    */ true,
        /* minEditInterval            */ Duration.ZERO);
```

**Why this is wrong / suboptimal / risky:**

Four values diverge from the design's declared values in §6.5.2:

| Flag | Implementation | Design §6.5.2 |
|---|---|---|
| `supportsCodeFormatting` | false | true ("Signal renders monospace via its formatting metadata") |
| `maxMessageBytes` | 2,000 | 8,000 ("Signal's effective text-content cap is well above SimpleX") |
| `minEditInterval` | `Duration.ZERO` | 600ms ("matches SimpleX; coalescing floor for ProgressNotifier") |

The `supportsCodeFormatting = false` discrepancy is the most significant: Signal natively supports monospace formatting, and the design explicitly sets the flag to `true` so the code-formatting render path is exercised on Signal. Setting it to `false` means Signal users see raw backticks instead of monospace text — a UX regression that contradicts the design's capability declaration.

The `maxMessageBytes` discrepancy (2,000 vs 8,000) means the adapter chunks at a quarter of Signal's actual capacity, quadrupling the number of sends for medium-length messages. The `minEditInterval` of `Duration.ZERO` removes the coalescing floor, potentially flooding Signal's rate envelope with rapid edits.

**Recommended fix:**

Align with the design values:

```java
        /* supportsCodeFormatting     */ true,
        /* maxMessageBytes            */ 8_000,
        /* minEditInterval            */ Duration.ofMillis(600),
```

**Reasoning:**

The design document carries explicit rationale for each value. `supportsCodeFormatting = true` is particularly load-bearing: it enables monospace rendering for Signal users and exercises the code-formatting path in the test suite. `maxMessageBytes = 8_000` matches Signal's documented capacity. `minEditInterval = 600ms` is the coalescing floor that prevents flooding.

**Trade-offs:**

Same as F2: if the implementation values were chosen based on empirical testing, the design document should be updated. The `supportsCodeFormatting` fix has no downside — Signal genuinely supports monospace rendering.

---

### F4. SignalAdapter.start() throws IllegalStateException instead of MessagingException

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java:174-233

**Current code:**

```java
public void start() {
    if (binary == null || dataDir == null || account == null
            || botAci == null || daemonEndpoint == null) {
        throw new IllegalStateException(
                "SignalAdapter.start() requires the production constructor "
                        + "(binary, dataDir, account, botAci, daemonEndpoint).");
    }
    // ...
    if (!awaitEndpoint(daemonEndpoint, ENDPOINT_PROBE_TIMEOUT)) {
        sp.stop();
        this.subprocess = null;
        throw new IllegalStateException(
                "signal-cli daemon endpoint " + daemonEndpoint + " not reachable within "
                        + ENDPOINT_PROBE_TIMEOUT);
    }
    // ...
}
```

**Why this is wrong / suboptimal / risky:**

`SimpleXAdapter.start()` throws categorised `MessagingException` (with `FailureCategory.TRANSIENT` for port-readiness timeouts), giving Provider's startup code a uniform exception surface to catch. `SignalAdapter.start()` throws `IllegalStateException` for the same class of failures (subprocess launch failure, endpoint unreachable, JSON-RPC connect failure). This means Provider must catch both exception types when handling adapter startup, and the failure category (transient vs. permanent) is lost for Signal failures.

The inconsistency is not a spec violation (neither `start()` nor `close()` are on the `MessagingAdapter` interface — they are adapter-specific lifecycle methods), but it breaks the parallel structure between the two production adapters.

**Recommended fix:**

Change the three `throw new IllegalStateException` sites in `start()` to throw categorised `MessagingException`:

```java
// For missing config (permanent — cannot retry without reconfiguration):
throw new MessagingException(FailureCategory.PERMANENT,
        "SignalAdapter.start() requires the production constructor ...");

// For endpoint unreachable (transient — may succeed on retry):
throw new MessagingException(FailureCategory.TRANSIENT,
        "signal-cli daemon endpoint " + daemonEndpoint + " not reachable within "
                + ENDPOINT_PROBE_TIMEOUT);

// For JSON-RPC connect failure (transient):
throw new MessagingException(FailureCategory.TRANSIENT,
        "Failed to connect SignalJsonRpcClient", e);
```

**Reasoning:**

Aligning with `SimpleXAdapter.start()` gives Provider a uniform exception surface for adapter lifecycle failures and preserves the failure category for retry decisions.

**Trade-offs:**

Low impact — `start()` is called once per adapter at Provider startup, and the exception handling at the Provider level likely catches `Exception` or `Throwable` broadly. The fix is more about consistency than correctness.
