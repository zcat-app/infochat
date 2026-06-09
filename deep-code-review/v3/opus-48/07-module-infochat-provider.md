# Deep code review: module infochat-provider

**Target:** module infochat-provider
**Lens:** module
**Module path:** infochat-provider/
**Date:** 2026-06-09 18:40
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] MAINTAINABILITY-RULES-DRIFT — InboundRouter.java:486-498 — the spec's distinct "command body cap" (slash-command line length, applied before parsing) is not implemented; only the chat-mode body cap and a generic 64 KiB byte cap exist.

## Detail

### F1. Command body cap (slash-command line length) is missing — only the chat-mode cap is implemented

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:486-498` (and the missing property in `application.properties`)

**Current code:**

```java
// Chat-mode body cap (commands.md §Input length caps): beyond
// the cap → friendly error, no chat-agent invocation, no LLM
// call, and no DB write. ...
if (!normalized.startsWith("/") && normalized.length() > chatBodyCap) {
    sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_CHAT_BODY_TOO_LARGE), adapterName);
    return;
}
```

The only other inbound size gate is the generic byte cap earlier in the same method:

```java
// InboundRouter.java:384-387
if (raw != null && exceedsUtf8ByteLength(raw, maxInboundBodyBytes)) {
    sendReply(msg.scope(), MESSAGE_TOO_LARGE_REPLY, adapterName);
    return;
}
```

with `infochat.router.max-inbound-body-bytes=65536` (application.properties:186). There is no `infochat.command.body-cap` property and no length check on the slash-command path (`handleSlash`, InboundRouter.java:788-814, performs no length validation).

**Why this is wrong / suboptimal / risky:**

`docs/spec/commands.md` §Surface conventions "Input length caps" commits to **two** cap categories, each with its own profile-driven value:

> - **Command body cap** — total slash-command line length, applied before parsing. Beyond the cap → friendly error, no parse attempt, no audit row beyond the rejection counter.
> - **Chat-mode body cap** — total inbound chat-mode message length, applied at intake. Beyond the cap → friendly error, no chat agent invocation, no LLM call.

The chat-mode body cap is the one implemented here (and only for non-slash bodies, line 495). The command body cap is absent. The design note pins concrete per-profile values for it that do not appear anywhere in the Provider:

> `docs/design/03-commands.md:186`
> | **Command body cap** (whole slash-command line) | `laptop` 8192, `vps` 4096, `pi` 2048, `remote-llm` 16384 chars |

This is a SPEC-DRIFT: the contract names a cap and the design tier assigns it tuned per-profile values, but the code ships neither the property nor the gate. The consequences:

1. A slash command up to ~64 KiB (or ~21K characters of multi-byte text) reaches the per-handler parsers unbounded. The spec's intent is that an oversized command line is rejected *before parsing* with a friendly error; instead an oversized `/save`, `/ban --reason "<20KB>"`, `/add-source <giant URL>`, etc. is fully tokenized and runs whatever per-handler work precedes the handler's own validation. `BanArgs.parse` (BanCommandHandler.java:550) tokenizes the whole line character-by-character; `SaveCommandHandler.parseArgs` splits on whitespace; none of these have a pre-parse length guard.
2. The reply on the only backstop (the 64 KiB byte cap) is the generic `MESSAGE_TOO_LARGE_REPLY` English literal, not the profile-tuned friendly command-cap error the spec describes.
3. The profile-driven sizing the spec promises (a `pi` deployment should reject command lines above 2048 chars; a `remote-llm` deployment tolerates 16384) is entirely absent — every profile gets the same 64 KiB byte ceiling.

This is not a critical exploit because the 64 KiB byte cap does bound absolute memory, and the per-handler parsers are O(n) over a bounded-by-64KiB string. It is a real correctness/contract gap that will compound: the command-cap value is referenced from design notes that other work will treat as implemented, and the "friendly error, no parse attempt" semantics are a user-facing commitment.

**Recommended fix:**

Add the spec'd command body cap as a profile-driven char cap, applied to slash bodies after normalization and before `handleSlash`. Mirror the existing chat-cap shape.

```java
@ConfigProperty(name = "infochat.command.body-cap", defaultValue = "8192")
int commandBodyCap;
```

```properties
# application.properties — mirror design/03-commands.md §3.1 per-profile values
infochat.command.body-cap=8192
%laptop.infochat.command.body-cap=8192
%vps.infochat.command.body-cap=4096
%pi.infochat.command.body-cap=2048
%remote-llm.infochat.command.body-cap=16384
%test.infochat.command.body-cap=2048
```

```java
// In onMessage, alongside the chat-mode body cap, before handleSlash dispatch.
// Placed after the authorization gates (so invite/D47/ban/per-group-reply
// precedence holds, same rationale as the chat-mode cap) and before parse.
if (normalized.startsWith("/") && normalized.length() > commandBodyCap) {
    sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_COMMAND_BODY_TOO_LARGE), adapterName);
    return;
}
```

with a new `error.command.body_too_large` bundle key in `en`/`cs`.

**Reasoning:**

The fix makes the two-cap commitment structural rather than implicit: the slash path gets its own profile-tuned char cap with a friendly error and no parse attempt, exactly as `commands.md` describes, and the design-note per-profile values become real. It reuses the established `@ConfigProperty` + bundle pattern already used for the chat-mode cap, so it adds no new abstraction. The generic 64 KiB byte cap stays as the defense-in-depth backstop it is documented to be.

The placement (after the authorization gates, before parse) preserves the same precedence ordering the chat-mode cap already documents and matches the spec's "applied before parsing."

**Trade-offs:**

One additional `@ConfigProperty`, one bundle key per language, and one branch. No behavior change for in-spec command lines. The only observable change is that over-cap slash commands now get the friendly command-cap reply instead of either silent parse or the generic too-large reply — which is the spec'd behavior.

## Synthesizer-relevant observations

None.
