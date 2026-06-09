# Deep code review: module infochat-messaging-adapter

**Target:** module infochat-messaging-adapter
**Lens:** module
**Module path:** infochat-messaging-adapter/
**Date:** 2026-06-08 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [low] SECURITY — SignalMentionParser.java:60 — Signal bot-ACI mention comparison uses non-constant-time `String.equals`, diverging from the constant-time discipline the sibling SimpleX parser documents as a security requirement for the same D10 trust anchor.
- [low] MAINTAINABILITY-RULES-DRIFT — SignalGroupHandler.java:157-159 — group-message timestamp extraction calls `getJsonNumber(...).longValueExact()` with no presence/type guard on the inbound (trust-boundary) frame, unlike the codec's DM path which guards the same fields; a malformed frame throws NPE/CCE that is only caught one layer up.

## Detail

### F1. Signal mention comparison is not constant-time, unlike the SimpleX sibling that treats the same comparison as a timing-sensitive trust anchor

- **Category:** SECURITY
- **Severity:** low
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMentionParser.java:53-63

**Current code:**

```java
String botAciLower = botAci.toLowerCase(Locale.ROOT);
for (JsonValue entry : mentions) {
    if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
        continue;
    }
    JsonObject mention = (JsonObject) entry;
    String uuid = mention.getString("uuid", null);
    if (uuid != null && uuid.toLowerCase(Locale.ROOT).equals(botAciLower)) {
        return true;
    }
}
```

**Why this is wrong / suboptimal / risky:**

This is the Signal group-mode mention gate — the byte-equality check against the bot's per-adapter ACI that decides whether an attacker-controlled group message reaches Provider (decision D10). The mention `uuid` values are untrusted wire data fully under a group peer's control.

The sibling adapter performs the identical decision and deliberately uses a constant-time comparison, documenting it as a security requirement:

```java
// SimpleXMentionParser.java:52-56 / 75
// <p>The per-entry comparison is constant-time
// ({@link MessageDigest#isEqual}) so the number of leading bytes a
// mention shares with the bot's queue address is not observable via
// timing — the queue address is the group-mode authorization trust
// anchor (D10) and must not leak byte-by-byte.</p>
...
if (MessageDigest.isEqual(mentionBytes, botBytes)) {
```

`String.equals` short-circuits on the first differing character (and on length mismatch), so the wall-clock cost of a non-match correlates with how many leading characters the supplied `uuid` shares with the bot ACI. A peer that can submit crafted mention `uuid`s and time the bot's reaction could in principle recover the bot's ACI character-by-character. The module already decided this leakage matters for SimpleX; Signal carries the same trust anchor and the same wire-controlled operand, so the two should not diverge.

The severity is `low` rather than higher because practical exploitability is weak: the ACI is a 36-char canonical UUID, the comparison runs before any heavy downstream work, network/dispatch-queue jitter dominates the signal, and the bot ACI is not a high-value secret on the same level as a private key. But it is a genuine inconsistency against the module's own stated standard, and the fix is mechanical.

**Recommended fix:**

```java
String botAciLower = botAci.toLowerCase(Locale.ROOT);
byte[] botBytes = botAciLower.getBytes(StandardCharsets.UTF_8);
for (JsonValue entry : mentions) {
    if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
        continue;
    }
    JsonObject mention = (JsonObject) entry;
    String uuid = mention.getString("uuid", null);
    if (uuid == null) {
        continue;
    }
    byte[] mentionBytes = uuid.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
    if (MessageDigest.isEqual(mentionBytes, botBytes)) {
        return true;
    }
}
```

(add `import java.nio.charset.StandardCharsets;` and `import java.security.MessageDigest;`)

**Reasoning:**

`MessageDigest.isEqual` is documented constant-time over the compared array contents, so the per-entry cost no longer correlates with the shared-prefix length. This brings the Signal gate in line with the SimpleX gate, which is the only consistent posture given both anchor the identical D10 decision. The lowercase canonicalization (the case-insensitivity the existing test pins) is preserved.

**Trade-offs:**

Two extra `byte[]` allocations per mention entry. Negligible — mention lists are tiny and this path runs once per inbound group message, far below any hot-path threshold.

---

### F2. Signal group-message timestamp extraction lacks the presence/type guard the codec applies to the DM path on the same trust boundary

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java:157-159

**Current code:**

```java
long timestamp = envelope.containsKey("timestamp")
        ? envelope.getJsonNumber("timestamp").longValueExact()
        : dataMessage.getJsonNumber("timestamp").longValueExact();
```

**Why this is wrong / suboptimal / risky:**

This runs on the signal-cli daemon stream, which the codec itself calls "a trust boundary" (SignalMessageCodec.java:175-179). The expression assumes:

- `envelope.getJsonNumber("timestamp")` is non-null when `containsKey("timestamp")` is true — false if `timestamp` is present but JSON-`null` or a non-number, in which case `getJsonNumber` returns null and `.longValueExact()` NPEs;
- `dataMessage.getJsonNumber("timestamp")` is non-null in the else branch — false if `dataMessage` also lacks a numeric `timestamp`, again NPE;
- the value fits a `long` — `longValueExact()` throws `ArithmeticException` on a fractional or out-of-range number.

The codec's DM path handles exactly these cases deliberately, via `usableTimestamp` / `integralLong`, dropping the frame instead of throwing (SignalMessageCodec.java:199-228), and documents why: "an NPE/CCE escaping here used to kill the thread that processes inbound frames." The group path re-implements timestamp extraction inline and drops that guard, so a hostile or buggy group frame that reaches `botMentioned == true` can throw out of `handleReceive`.

This does not crash the dispatch thread — `SignalJsonRpcClient.dispatchGroupNotification` wraps the call in a `catch (RuntimeException)` (SignalJsonRpcClient.java:771-780). But relying on that outer catch is a regression from the codec's own "be total over arbitrary inbound frame shapes" discipline: the message is silently lost with a WARN, whereas the codec's design is to drop the frame cleanly at the field that is missing. The two inbound paths should treat the same untrusted field the same way. Severity is `low` because the outer catch contains the blast radius to one dropped message.

**Recommended fix:**

Reuse the codec's existing total timestamp helper rather than re-deriving it inline. Expose `SignalMessageCodec.usableTimestamp` (or a thin `@Nullable Long groupTimestamp(JsonObject envelope, JsonObject dataMessage)` wrapper) package-private and call it from the handler, dropping the frame when it returns null:

```java
Long timestamp = codec.usableTimestamp(envelope, dataMessage);
if (timestamp == null) {
    return;
}
...
new InboundMessage(sender, new ScopeRef.Group(groupId),
        stripBotMentions(dataMessage, body),
        Instant.ofEpochMilli(timestamp),
        "signal-" + timestamp);
```

(`SignalGroupHandler` is not currently constructed with the `SignalMessageCodec`; either pass it in or lift `usableTimestamp` to a static the handler can call.)

**Reasoning:**

Both inbound paths then share one tested, total extraction for the same wire field, so a malformed-timestamp frame is dropped at the field rather than thrown out of the handler and caught generically one layer up. It removes the inline assumption that `containsKey` implies a usable number, which is the actual gap. It also deletes duplicated extraction logic, matching the codec's stated intent.

**Trade-offs:**

The handler gains a dependency on (or a static call into) `SignalMessageCodec`. Minor; the handler already lives beside the codec and the alternative is keeping two divergent copies of the same trust-boundary parsing.

---

## Synthesizer-relevant observations

- The two transport adapters classify a send on a torn-down/reconnecting connection differently for the update/finalize path: `SimpleXAdapter` keeps its `handles` map across reconnects so an update during the reconnect window reaches `requireConnected()` and returns TRANSIENT, while `SignalJsonRpcClient.disconnect()` clears its handle registry (SignalJsonRpcClient.java:319-321) so the same update resolves to a missing handle → PERMANENT. Each adapter documents its own choice, and `AdapterCapabilityContractTest` only pins the *unstarted* (never-connected) state as PERMANENT for both, not the *reconnecting* state. This is a cross-adapter contract-surface observation (does the SPI promise a uniform reconnect-window classification?) and belongs to the architecture lens, not a module finding.
- `CapabilityFlags` declares `supportsMultilineCode`, `supportsAttachments`, and `supportsThreading`; `supportsAttachments` and `supportsThreading` are explicitly "future use" with no consumer anywhere in this module, and `supportsMarkdownLinks` is consumed only by the Provider-side startup gate (not in this module). Whether the v1 capability record should carry future-use flags at all is an SPI-shape question for the architecture lens (spec/messaging.md §Capability flags does list "any future flag a new transport needs," so this is arguably spec-sanctioned, not drift).
