# Deep code review: module infochat-messaging-adapter

**Target:** module infochat-messaging-adapter
**Lens:** module
**Module path:** infochat-messaging-adapter/
**Date:** 2026-06-09 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] SECURITY — SignalJsonRpcClient.java:108,547 + SignalMessageCodec.java:174-204 — Signal adapter never enforces the declared `maxInboundMessageBytes=16384` on the message body; its only inbound bound is a 16384-*char* cap on the raw JSON-RPC line, so a multi-byte body can be several times the byte budget the Provider's downstream plans against.
- [low] MAINTAINABILITY-RULES-DRIFT — SignalGroupHandler.java:202-203 — group mention-strip reads `start`/`length` via `getInt`, which throws `ClassCastException` on a wrong-typed wire value, dropping the whole (already-recognized) message instead of skipping the one bad span the surrounding comment claims is "validated individually".
- [low] MAINTAINABILITY-RULES-DRIFT — cross-cutting (oversize inbound drop) — neither v1 adapter emits the fixed oversize reply (`correlationId = dropped id`) that design §6.3.10 commits the oversize-drop path to, so an oversize message is silently swallowed.

## Detail

### F1. Signal inbound size cap is enforced in line-chars, not body-bytes — the declared capability is not honored

- **Category:** SECURITY
- **Severity:** high
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:99-108, 539-562; infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java:174-204; SignalGroupHandler.java:104-174

**Current code:**

```java
// SignalJsonRpcClient — the ONLY inbound size guard on the Signal path:
private static final int MAX_INBOUND_LINE_CHARS = 16_384;
...
if (sb.length() >= MAX_INBOUND_LINE_CHARS) {
    LOG.warnf("dropped inbound JSON-RPC line exceeding %d-char cap", MAX_INBOUND_LINE_CHARS);
    sb.setLength(0);
    if (!skipToNewline(r)) { return; }
}
```

```java
// SignalMessageCodec.extractDm — constructs the inbound body with NO byte-length check:
String body = dataMessage.getString("message", null);
if (body == null || body.isEmpty()) {
    return Optional.empty();
}
...
return Optional.of(new ReceivedDm(canonicalizeAci(sourceUuid), body, timestamp));
```

Contrast the SimpleX sibling, which enforces the cap on the body in UTF-8 bytes
(SimpleXMessageCodec.java:364):

```java
if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_INBOUND_TEXT_BYTES) {
    return new Ignored("newChatItem-text-exceeds-inbound-cap");
}
```

**Why this is wrong / suboptimal / risky:**

`SignalAdapter` declares `maxInboundMessageBytes = 16_384` (SignalAdapter.java:81).
The spec (`docs/spec/messaging.md` §Required SPI surface — *Inbound message size
cap*) and design §6.2.2 make this a *transport-layer cap on the inbound message
size, dropped before delivery to Provider*, expressed in bytes. The
SignalJsonRpcClient comment at line 99-108 even asserts the line cap "Matches the
`maxInboundMessageBytes=16384` capability flag … enforced here at the transport
boundary." It does not match it, for two compounding reasons:

1. **Char count, not byte count.** `sb.length()` is Java `char` (UTF-16 code
   units). The cap the Provider's downstream budgets (LLM tokens, Stage 1
   watchdog) plan against is a UTF-8 *byte* budget. A body composed of 4-byte
   astral-plane characters (emoji, CJK extension blocks) is ~1 char per 4 bytes
   in `String.length()` terms for surrogate pairs — 16384 chars can be on the
   order of 32-64 KiB of UTF-8. The Provider receives a body far over the budget
   it was promised.

2. **Line scope, not body scope.** The 16384 figure is applied to the entire
   JSON-RPC envelope line (`{"jsonrpc":...,"params":{"envelope":{...,"dataMessage":{"message":"..."}}}}`),
   not to `dataMessage.message`. The body that actually reaches the chat pipeline
   is the only thing the application cap and the prompt-injection blast-radius
   argument care about, and it is never measured. The two caps the design calls
   "layered, not redundant" (§6.3.10) collapse to one weak layer on Signal: the
   transport cap is effectively absent, leaving only the application-level cap.

The threat is a hostile or compromised sender on a group/DM the bot reads pushing
bodies that are within the line cap but multiples of the intended byte ceiling,
inflating LLM token cost and Stage-1 work per message — exactly the resource-cost-at-
the-boundary the design assigns to this cap. SimpleX defends against it; Signal,
the other production adapter, does not. This is a per-adapter inconsistency in a
security control, which is worse than a uniform gap because it will not show up in
a SimpleX-only test run.

**Recommended fix:**

Enforce the byte cap on the decoded body, in both the DM and group paths, mirroring
SimpleX. Add the constant to the codec and gate body construction:

```java
// SignalMessageCodec
static final int MAX_INBOUND_TEXT_BYTES = 16_384; // lockstep with SignalAdapter.maxInboundMessageBytes

Optional<ReceivedDm> extractDm(JsonObject receiveParams) {
    ...
    String body = dataMessage.getString("message", null);
    if (body == null || body.isEmpty()) {
        return Optional.empty();
    }
    if (body.getBytes(StandardCharsets.UTF_8).length > MAX_INBOUND_TEXT_BYTES) {
        return Optional.empty(); // drop before delivery (design §6.2.2 / §6.3.10)
    }
    ...
}
```

```java
// SignalGroupHandler.handleReceive, after the body null/empty check:
if (body.getBytes(StandardCharsets.UTF_8).length > SignalMessageCodec.MAX_INBOUND_TEXT_BYTES) {
    return;
}
```

Keep `MAX_INBOUND_LINE_CHARS` as the coarse OOM guard on the reader loop (it
correctly bounds an unterminated-line attack), but rename its comment to stop
claiming it implements the `maxInboundMessageBytes` capability — it is a separate,
coarser defense.

**Reasoning:** The capability flag is a contract the Provider relies on. Enforcing
it on the body in UTF-8 bytes makes the flag honest, brings Signal in line with
SimpleX, and restores the two-layer defense the design specifies. The line cap and
the body cap then have distinct, correctly-scoped jobs: the line cap bounds reader
memory, the body cap bounds the delivered payload.

**Trade-offs:** One extra `getBytes` allocation per inbound message on the Signal
path (already paid on the SimpleX path). Negligible against the per-message LLM/DB
cost downstream.

---

### F2. Signal group mention-strip can throw on a wrong-typed `start`/`length`, dropping the whole message

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java:188-208

**Current code:**

```java
for (JsonValue entry : mentions) {
    if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
        continue;
    }
    JsonObject mention = (JsonObject) entry;
    String uuid = mention.getString("uuid", null);
    if (uuid == null || !botAci.equals(uuid.toLowerCase(Locale.ROOT))) {
        continue;
    }
    int start = mention.getInt("start", -1);
    int length = mention.getInt("length", -1);
    if (start < 0 || length <= 0 || start + length > body.length()) {
        continue;
    }
    spans.add(new int[] {start, start + length});
}
```

**Why this is wrong / suboptimal / risky:**

`JsonObject.getInt(name, defaultValue)` returns the default only when the key is
*absent*. If the key is present but holds a non-number (e.g. `"start":"3"` or
`"start":{}`), `getInt` throws `ClassCastException`. The method comment one line
above states "each entry's span fields are still validated individually," implying
a malformed span is skipped — but a wrong-typed span instead throws out of
`stripBotMentions`, past the `continue` guard, and out of `handleReceive`. The
DM-path codec (`SignalMessageCodec.extractDm` / `usableTimestamp`) is meticulous
about exactly this — it uses `instanceof JsonNumber` precisely so "a wrong-typed
field is treated like an absent one rather than letting a typed accessor throw."
This strip path is the inconsistent one.

The blast radius is contained: `dispatchGroupNotification` (SignalJsonRpcClient.java:766)
wraps the call in `catch (RuntimeException)`, so the reader/dispatch thread
survives. But the consequence is that a *recognized* bot mention (the gate already
passed) is dropped wholesale because of one malformed sibling span, rather than the
message being delivered with that span left unstripped — the degradation the
SimpleX strip path (SimpleXMessageCodec.extractMentionSpans) deliberately chose
("the handler delivers the text unstripped"). The wire is a trust boundary; a
malformed span should not be able to suppress a legitimate, correctly-mentioned
command.

**Recommended fix:**

Read the span fields defensively, matching the codec's `instanceof` discipline:

```java
JsonValue startVal = mention.get("start");
JsonValue lengthVal = mention.get("length");
if (!(startVal instanceof JsonNumber sn) || !(lengthVal instanceof JsonNumber ln)) {
    continue;
}
int start = sn.intValue();
int length = ln.intValue();
if (start < 0 || length <= 0 || start + length > body.length()) {
    continue;
}
spans.add(new int[] {start, start + length});
```

**Reasoning:** Skipping a malformed span (rather than throwing) makes the per-entry
"validated individually" comment true and matches the deliberate
deliver-unstripped degradation the SimpleX path already implements, so the two
adapters behave the same on hostile mention metadata.

**Trade-offs:** None — strictly more robust and brings the two adapters into
agreement.

---

### F3. Oversize inbound drop is silent on both adapters; design commits to a fixed reply

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** cross-cutting — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java:364-366 (and the Signal equivalent that F1 adds)

**Current code:**

```java
// SimpleXMessageCodec.decodeNewChatItem — oversize body:
if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_INBOUND_TEXT_BYTES) {
    return new Ignored("newChatItem-text-exceeds-inbound-cap");
}
```

**Why this is wrong / suboptimal / risky:**

Design §6.3.10 states the oversize-drop path "emits a fixed friendly reply as an
`OutboundMessage` with `correlationId = <dropped inbound message id>`" and
explicitly distinguishes it from the §6.3.7 inbound-queue overflow drop, which *is*
silent: "unlike the §6.3.7 inbound-queue overflow drop (silent in v1), the oversize
drop replies, because it signals a one-shot client mistake rather than a sustained
flood." The SimpleX path drops oversize bodies as a plain `Ignored` frame with no
reply, and the Signal path (after F1) would do the same. So both adapters implement
the §6.3.7 silent-drop semantics for the §6.3.10 case.

This is a divergence from a design commitment, not a spec one — `docs/spec/messaging.md`
states the cap as a SHOULD and leaves the reply to design notes. The user-visible
effect is minor (a user who pastes a huge message gets no feedback), and the dropped
`adapterMessageId` correlation key needed to send the reply *is* available at the
drop site, so the gap is implementation, not impossibility. Worth recording so the
design and code are reconciled (either implement the reply, or amend §6.3.10 to make
the oversize drop silent like the queue-overflow drop).

**Recommended fix:**

Either (Option A) wire the fixed reply, or (Option B) downgrade the design note.
For Option A, surface the dropped id to the adapter so it can enqueue the reply —
the codec is pure-static, so the decision must live in the WS/JSON-RPC client where
the outbound path is reachable. Sketch (SimpleX side):

```java
// In SimpleXWebSocketClient.dispatch, add an Ignored sub-case carrying the id,
// or return a dedicated Oversize(adapterMessageId, scope) frame variant from the
// codec instead of Ignored, and have the client send the localized fixed reply:
case SimpleXMessageCodec.Oversize ov ->
        sendFireAndForget(SimpleXMessageCodec.encodeSendCommand(
                nextCorrId(), ov.scope(), localizedOversizeReply()));
```

**Reasoning:** Aligning code and design removes a latent "is this a bug or intended?"
ambiguity for the next reader. Option B is cheaper and defensible — a silent
oversize drop is consistent with the silent queue-overflow drop and avoids giving a
hostile sender a cheap reply-amplification primitive.

**Trade-offs:** Option A adds an outbound send on the inbound-drop path (a small
reply-amplification surface a flooder could exploit, which is exactly why §6.3.7
chose silence) and a new codec frame variant. Option B is a one-line design edit
with no code. Given the amplification concern, Option B is likely the better
reconciliation.

**Alternative options:**

- **Option A** — implement the fixed reply per §6.3.10 as written.
- **Option B** (recommended) — amend design §6.3.10 to make the oversize drop
  silent, matching the §6.3.7 queue-overflow drop, and note the
  reply-amplification rationale. Pros: no new attack surface, less code. Cons:
  a user who genuinely over-pastes gets no feedback (the application-level cap may
  still reply downstream).

## Synthesizer-relevant observations

- The SPI correctly keeps all policy (retry budget, per-user rate limiting, ban
  handling, mention *recognition decisions* are made in-adapter but only against the
  per-adapter bot contact id) on the right side of the boundary. The
  `maxInboundMessageBytes` contract (F1) is consumed by Provider's downstream
  budgets; the Signal under-enforcement means any cross-module reasoning that trusts
  the flag (Stage-1 watchdog sizing, LLM token planning) is operating on an
  unenforced premise for the Signal adapter specifically. If the synthesizer is
  reconciling the inbound-size contract across modules, treat Signal as currently
  not honoring it.
- Membership-event ACIs (`SignalGroupHandler.dispatchMembership` /
  `aciFromArrayEntry`) are lower-cased but not validated as well-formed UUIDs before
  being handed to Provider as `contactId`s, whereas SimpleX validates every inbound
  id against its queue-address charset. These ACIs are not echoed into outbound
  command strings (Signal addresses via JSON fields, not a command grammar), so the
  command-injection risk SimpleX guards against does not apply, and the
  `(adapter, contact_id)` join key tolerates an arbitrary string. Not raised as a
  module finding, but if Provider anywhere assumes membership-event contactIds are
  canonical UUIDs, that assumption is unenforced at this boundary.
