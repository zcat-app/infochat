# Deep code review: module infochat-messaging-adapter

**Target:** module infochat-messaging-adapter
**Lens:** module
**Module path:** infochat-messaging-adapter/
**Date:** 2026-06-01 00:00
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] SECURITY — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParser.java:83-93 — base64-decode-with-literal-fallback makes mention recognition non-injective, so two distinct queue-address strings can collide and a non-mention can be read as a bot mention.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java:103-168 — Signal group handler is wired to nothing and re-implements DM decode logic that already lives in SignalMessageCodec, diverging from the codec/handler split the SimpleX side uses.
- [medium] SIMPLIFICATION — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java:557-582 — `findFirstString` does an attacker-influenced depth-first key search across the whole error/ack envelope, picking up the wrong field and obscuring the contract.
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java:172-174 — `onMembershipEvent` default method on the SPI is dead surface; no production code path calls it.

## Detail

### F1. SimpleX mention recognition is non-injective and can be spoofed or suppressed

- **Category:** SECURITY
- **Severity:** high
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParser.java:57-93

**Current code:**

```java
static boolean botMentioned(@NonNull List<String> mentionQueueAddresses,
                            @NonNull String botQueueAddress) {
    if (mentionQueueAddresses.isEmpty()) {
        return false;
    }
    byte[] botBytes = decodeQueueAddress(botQueueAddress);
    for (String mention : mentionQueueAddresses) {
        byte[] mentionBytes = decodeQueueAddress(mention);
        if (Arrays.equals(botBytes, mentionBytes)) {
            return true;
        }
    }
    return false;
}

private static byte[] decodeQueueAddress(String value) {
    try {
        return Base64.getUrlDecoder().decode(value);
    } catch (IllegalArgumentException urlFailed) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException stdFailed) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }
}
```

**Why this is wrong / suboptimal / risky:**

Mention recognition is the D10 trust anchor for SimpleX group mode (`messaging.md` §Required SPI surface — Receive: "the comparison is byte-equality against the bot's per-adapter contact id", and "an attacker who can spoof or impersonate the bot's display name in a group must not be able to suppress legitimate mentions or fake mentions of the bot"). The implementation replaces byte-equality on the contact id with byte-equality on a *decoded* form, and the decode is not a function from one canonical representation:

1. `Base64.getUrlDecoder().decode` is lenient — by default it does not require canonical padding and accepts trailing bits, so multiple distinct input strings decode to the same byte array. An attacker who controls a mention entry (each entry only has to pass `isValidQueueAddressId`, which admits the URL-safe-base64 ∪ decimal charset) can craft a string that is *not* byte-equal to the bot's queue address but decodes to the same bytes. That forges a mention the spec says must be impossible to forge.
2. The three-way fallback compares across representation domains. The bot address decodes via the URL decoder (it is a real queue address); a mention that fails base64 decode falls through to raw UTF-8 bytes. Two values in different domains can still `Arrays.equals` if the decoded bot bytes happen to equal the literal UTF-8 bytes of an attacker string. The reverse — a legitimate mention encoded slightly differently than the bot's stored form decodes to different bytes and is *not* recognized — silently suppresses a real mention.

The spec's requirement is the simplest possible rule: byte-equality on the contact id string. The decode step adds a non-injective transform on top of a security comparison, which is exactly the class of bug (canonicalization mismatch) that breaks identity checks.

**Recommended fix:**

```java
static boolean botMentioned(@NonNull List<String> mentionQueueAddresses,
                            @NonNull String botQueueAddress) {
    // D10: byte-equality on the contact-id string itself. The mention
    // payload references the bot's per-adapter queue address verbatim
    // (messaging.md §Receive); both sides are the same canonical string
    // simplex-chat surfaces, so no decode/normalization step is correct
    // here — any transform that is not injective can forge or suppress
    // a mention.
    for (String mention : mentionQueueAddresses) {
        if (botQueueAddress.equals(mention)) {
            return true;
        }
    }
    return false;
}
```

**Reasoning:**

`messaging.md` defines the comparison as byte-equality against the bot's per-adapter contact id. The mention list (`SimpleXMessageCodec.extractMentionQueueAddresses`) extracts `format.memberRef`, which is the same queue-address string simplex-chat uses for the bot's own identity — there is no second encoding to reconcile, so a straight string `equals` is both correct and the literal spec rule. Removing the decode eliminates the canonicalization gap in both directions: no forged mention via a non-canonical encoding, and no suppressed mention via an encoding mismatch.

**Trade-offs:**

If simplex-chat genuinely surfaces the *same* queue address in two different base64 encodings across the mention payload vs. the bot-identity resolution path, a plain `equals` would miss a legitimate mention. That risk should be resolved by canonicalizing the bot's queue address once at `SimpleXIdentity.resolve` time (a single, known transform) rather than by a lenient decode at every comparison. If a normalization is needed, it must be a strict, injective, canonical encoder (e.g. `Base64.getUrlEncoder().withoutPadding()` applied to both sides after a strict decode), not a best-effort decode that falls back to raw bytes.

**Alternative options:**

- **Option A** (recommended above) — verbatim string equality.
- **Option B** — strict canonicalization: decode both sides with a *strict* base64 decoder (reject non-canonical input), and if either side fails strict decode, treat the comparison as not-equal rather than falling back to UTF-8 bytes. Pros: tolerates a genuine padded-vs-unpadded difference. Cons: more code; only justified if simplex-chat actually emits two encodings, which is unverified.

---

### F2. Signal group handler duplicates DM decode logic and has no wired producer

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java:103-168 (cross-referenced against SignalMessageCodec.java:132-157 and SignalJsonRpcClient.java:412-434)

**Current code:**

```java
void handleReceive(@NonNull JsonObject receiveParams) {
    JsonObject envelope = receiveParams.getJsonObject("envelope");
    if (envelope == null) {
        return;
    }
    JsonObject dataMessage = envelope.getJsonObject("dataMessage");
    if (dataMessage == null) {
        return;
    }
    JsonObject groupV2 = dataMessage.getJsonObject("groupV2");
    if (groupV2 == null) {
        // DM scope or non-group notification — owned by
        // SignalMessageCodec.extractDm via SignalJsonRpcClient.
        return;
    }
    ...
    long timestamp = envelope.containsKey("timestamp")
            ? envelope.getJsonNumber("timestamp").longValueExact()
            : dataMessage.getJsonNumber("timestamp").longValueExact();
    String senderAci = sourceUuid.toLowerCase(Locale.ROOT);
    Identity sender = new Identity(senderAci, null, Instant.now());
    InboundMessage inbound = new InboundMessage(
            sender,
            new ScopeRef.Group(groupId),
            body,
            Instant.ofEpochMilli(timestamp),
            "signal-" + timestamp);
    handler.onMessage(inbound);
}
```

**Why this is wrong / suboptimal / risky:**

`SignalGroupHandler.handleReceive` re-implements envelope/dataMessage/timestamp extraction and ACI canonicalization that already live in `SignalMessageCodec` (`extractDm`, `canonicalizeAci`). The SimpleX side deliberately split this: `SimpleXMessageCodec.decode` does all the JSON walking and produces a `GroupCandidate`; `SimpleXGroupHandler` only does the mention decision and the `InboundMessage` construction (SimpleXGroupHandler.java:71-85). The Signal side does NOT follow that split — the handler reaches into raw `JsonObject` directly, so the codec/handler boundary is inconsistent between the two production adapters reviewing the same contract.

Two concrete consequences:

1. `senderAci = sourceUuid.toLowerCase(Locale.ROOT)` inlines canonicalization instead of calling `codec.canonicalizeAci`, so the two ACI-normalization sites can drift (e.g. if canonicalization ever grows UUID-format validation, the group path silently won't get it).
2. `SignalJsonRpcClient.dispatchNotification` (the only `method="receive"` consumer) calls `codec.extractDm` and returns on empty — it never routes group envelopes into `SignalGroupHandler`. `SignalAdapter.groupHandler()` builds a fresh handler on each call but nothing in this module ever invokes `handleReceive`. The Javadoc says the reader wiring "lands with the multi-adapter integration (M1-109)", but M1-109 has already landed (it is the most recent commit) and the wire is still not connected. A skeleton that advertises `supportsMembershipEvents=true` and a group-mention path while no code path can ever deliver a group message or membership event is a contract the adapter claims to honor but silently no-ops.

**Recommended fix:**

Route group envelopes through the same dispatch point and reuse the codec's extraction. In `SignalJsonRpcClient.dispatchNotification`, hand the `receive` params to the group handler when `extractDm` returns empty because of `groupInfo`/`groupV2`, and have `SignalGroupHandler` call `codec.canonicalizeAci(sourceUuid)` rather than inlining `toLowerCase`:

```java
private void dispatchNotification(SignalMessageCodec.JsonRpcMessage.Notification n) {
    if (!"receive".equals(n.method())) {
        return;
    }
    Optional<SignalMessageCodec.ReceivedDm> dm = codec.extractDm(n.params());
    if (dm.isEmpty()) {
        // group / membership envelopes are owned by the group handler
        groupHandler.handleReceive(n.params());
        return;
    }
    ... // existing DM path
}
```

and in the handler:

```java
String senderAci = codec.canonicalizeAci(sourceUuid);
```

**Reasoning:**

This restores the codec-owns-JSON / handler-owns-policy split the SimpleX adapter already follows, removes the second canonicalization site, and connects the group/membership path so the `supportsMembershipEvents=true` capability flag is backed by a real delivery path rather than being a claim no code can satisfy.

**Trade-offs:**

Wiring the group path requires `SignalJsonRpcClient` to hold a `SignalGroupHandler` reference (or a callback), which is a small amount of plumbing. If group support is genuinely out of scope until a later ticket, the honest alternative is to drop `supportsMembershipEvents=true` to `false` and delete the unreachable handler until it can be wired — do not ship a flag the code cannot honor.

**Alternative options:**

- **Option A** (recommended) — wire the handler and dedupe canonicalization now.
- **Option B** — if group mode is deferred, set `supportsMembershipEvents=false` on `SignalAdapter` and remove `SignalGroupHandler` / `groupHandler()` until the wiring lands, so the capability surface matches the code.

---

### F3. `findFirstString` does an attacker-influenced key search instead of reading the known field

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java:520-582

**Current code:**

```java
private static DecodedFrame decodeSendAck(String corrId, JsonNode resp) {
    String chatItemId = findFirstString(resp, "itemId", "chatItemId");
    if (chatItemId == null) {
        return new Ignored("send-ack-without-chatItemId");
    }
    return new SendAck(corrId == null ? "" : corrId, chatItemId);
}
...
private static String findFirstString(JsonNode node, String... names) {
    for (String name : names) {
        JsonNode direct = node.get(name);
        if (direct != null && direct.isTextual()) {
            return direct.asText();
        }
    }
    if (node.isObject()) {
        var fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            JsonNode child = node.get(fieldNames.next());
            if (child != null && child.isObject()) {
                for (String name : names) {
                    JsonNode tagged = child.get(name);
                    if (tagged != null && tagged.isTextual()) {
                        return tagged.asText();
                    }
                }
            }
        }
    }
    return null;
}
```

**Why this is wrong / suboptimal / risky:**

`findFirstString` walks the whole response object looking for the first key named `itemId`/`chatItemId`/`chatError`/etc. anywhere one level deep. The frame is an external-boundary input, so its shape is influenced by the peer. Picking "the first matching key found while iterating `fieldNames()`" is non-deterministic across Jackson versions/field orderings and can latch onto an unrelated nested `itemId` (e.g. a quoted-reply item id, a reaction target) rather than the new message's id, silently storing the wrong `chatItemId` into the handle — which then sends `/_update` against the wrong message. The fuzzy search also defeats the `requireValidQueueAddressId` discipline used everywhere else in this codec: `chatItemId` from a `SendAck` is later validated at encode time, but the *selection* of which value is the chatItemId is left to a heuristic scan.

The direct-decode path (`decodeNewChatItem`) reads each field by its known path and returns `Ignored` on a missing field. `decodeSendAck`/`decodeError` should do the same — the design note (§6.4.5) defines the exact response shape, so the field path is known.

**Recommended fix:**

Read the documented field path directly and drop `findFirstString`:

```java
private static DecodedFrame decodeSendAck(String corrId, JsonNode resp) {
    // §6.4.5: the chat-item id is resp.chatItem.itemId on the send-ack frame.
    JsonNode chatItem = resp.get("chatItem");
    String chatItemId = chatItem == null ? null : optText(chatItem, "itemId");
    if (chatItemId == null) {
        return new Ignored("send-ack-without-chatItemId");
    }
    return new SendAck(corrId == null ? "" : corrId, chatItemId);
}
```

(and the equivalent known-path read in `decodeError` for the error tag).

**Reasoning:**

Reading the field by its known path is deterministic, matches the discipline the rest of the codec already uses, and removes ~25 lines of recursive search. The exact path must be confirmed against the design note / live simplex-chat shape (M1-105 IT) — the point is that the path is known, so a scan is the wrong tool.

**Trade-offs:**

If the response shape is genuinely variable across simplex-chat versions (the comment "fall back to the bare envelope if simplex-chat's response shape later varies" suggests the author was hedging), a fixed path is less tolerant. That tolerance is illusory here: silently selecting the wrong id is worse than `Ignored`, because it corrupts the handle table. Pin the path against the live binary in the integration ticket rather than guessing at runtime.

---

### F4. `onMembershipEvent` default SPI method is dead surface

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java:162-174

**Current code:**

```java
default void onMembershipEvent(@NonNull MembershipEvent event) {
    // No-op — adapters surface events; Provider consumes them.
}
```

**Why this is wrong / suboptimal / risky:**

`onMembershipEvent` is declared on the SPI as a default no-op. The only implementor that overrides it is `InMemoryAdapter` (the test double), which uses it as an internal test helper to fan an event out to the registered handler. The two production adapters do not override it, do not call it, and have no caller: `SignalGroupHandler.dispatchMembership` and the SimpleX path dispatch membership events straight to the stored `MembershipHandler`, never through `onMembershipEvent`. Putting a method on the cross-module SPI contract that exists only so a test double can call itself is leaky abstraction — it implies Provider or the transport invokes `onMembershipEvent`, which never happens. The SPI's own MessagingAdapter Javadoc (lines 24-30) explicitly cites the "no speculative SPI surface for non-existent callers" rule as the reason `start`/`stop`/`groupExists` were left off; `onMembershipEvent` violates the same rule it cites.

**Recommended fix:**

Drop `onMembershipEvent` from the `MessagingAdapter` interface and make it a package-private method (or inline the dispatch) on `InMemoryAdapter`, where the test driver lives:

```java
// In InMemoryAdapter, not on the SPI:
private void fireMembershipEvent(MembershipEvent event) {
    MembershipHandler current = membershipHandler;
    if (current != null) {
        current.onEvent(event);
    }
}
```

`removeMember` / `removeBot` call `fireMembershipEvent` directly; the SPI loses a method no production code uses.

**Reasoning:**

The cross-module contract should carry only what a real consumer calls. Membership events flow adapter → `MembershipHandler` → Provider; `onMembershipEvent` is not on that path. Removing it shrinks the contract surface and removes the false implication that the SPI defines a Provider-callable event-injection entry point.

**Trade-offs:**

None for production code. The change touches `InMemoryAdapter` and its tests only. If a future transport needs a generic event-injection hook, it can be added when a real caller exists — which is exactly the rule the SPI Javadoc already commits to.

## Synthesizer-relevant observations

- The three adapters agree on the v1 capability invariants that matter for cross-module safety: all declare `supportsMarkdownLinks=false` (InMemoryAdapter.java:62, SignalAdapter.java:74, SimpleXAdapter.java:68) and `maxInboundMessageBytes=16384` on the production adapters, satisfying the Provider startup-fail-fast check and the inbound-cap promise. SimpleX correctly declares `supportsMembershipEvents=false` and disables nothing it cannot honor; Signal declares it `true` but the path is unwired (F2) — this is the one capability-invariant divergence between the production impls and is the cross-module item worth confirming against the Provider-side `AdapterRegistry` that consumes these flags.
- `SignalIdentity.resolve` (SignalIdentity.java:28-31) and `SimpleXIdentity.resolve` (SimpleXIdentity.java:28-31) both `throw new UnsupportedOperationException(...)`. Provider-side wiring (M1-035b/M1-105) is documented as the caller that resolves the bot ACI / queue address; if that wiring is now expected to be live, these unimplemented resolvers are the gap that prevents the production constructors from ever being reachable. Worth confirming at the Provider boundary whether `resolve` is still legitimately deferred or is now a missing dependency.
