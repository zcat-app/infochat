---
id: M1-187
title: "Strip bot-mention span in group inbound delivery"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 8
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - mention RECOGNITION (the D10 cryptographic gate deciding whether the bot was mentioned) — correct today in both handlers; this ticket only strips the recognized span from the delivered text
  - InMemoryAdapter group delivery — check whether its deliverGroupMention already strips before touching it; if it already produces stripped text, it is untouched
  - provider-side command parsing — once the adapter delivers stripped text, the existing slash-prefix parser works unchanged
  - Signal/SimpleX DM paths — no mentions in DM scope
acceptance:
  - "Per docs/spec/messaging.md §Required SPI surface — \"Group messages arrive only when the bot is `@mentioned`; the mention is stripped before delivery (the adapter may do the strip, or Provider may do it consistently across adapters — see design notes).\" — a group message '@bot summarise this' is delivered to the InboundHandler with text 'summarise this' (mention span removed, surrounding whitespace normalized): named tests for both Signal and SimpleX group handlers"
  - "The strip is anchored to the protocol mention entry (the same span/entry data that gates D10 recognition), not display-name text search: a named test whose message body contains the bot's display name as plain text asserts only the actual mention span is removed"
  - "A group slash command '@bot /summary' is delivered as a parseable slash command ('/summary' after strip) — a named test asserts the delivered text starts with the slash"
  - "SignalGroupEndToEndTest's pinned expectation (today assertEquals(\"@bot summarise this\", msg.text()) at :83) is updated to the stripped form — this is the one AUTHORIZED test-expectation change"
  - "MessagingAdapter.InboundHandler's javadoc promise ('mention-stripped text … populated by the adapter') is now true for both production adapters"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
decision_refs:
  - D10
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-187: Strip bot-mention span in group inbound delivery

## Context

Neither group handler strips the bot mention before delivery:
SignalGroupHandler builds the InboundMessage from the raw `body`
(SignalGroupHandler.java:161-167) and SimpleXGroupHandler passes `gc.text()`
verbatim (SimpleXGroupHandler.java:79-84). Neither propagates the mention's
span data from the codec layer, so nothing downstream CAN strip. The spec
and the MessagingAdapter.InboundHandler javadoc both promise mention-stripped
text; the practical impact is that group commands never parse — "@bot
/summary" reaches the slash-prefix parser as "@bot /summary" and is not a
command. SignalGroupEndToEndTest:83 pins the wrong (unstripped) behavior:
`assertEquals("@bot summarise this", msg.text())`. Unified finding M2 (high),
`deep-code-review/v2/UNIFIED.md` §2.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. The single pre-existing-test expectation change
(SignalGroupEndToEndTest:83) is named and authorized; every other existing
assertion in the group tests must keep passing.

## Notes

- Source: `UNIFIED.md` §3 T11 under `deep-code-review/v2/` (opus-48 msg F2,
  kimi-folder msg F4).
- Signal mentions carry protocol span data (start/length per mention entry
  in the dataMessage mentions array — the same array the D10 gate reads);
  SimpleXMessageCodec already surfaces the raw mention list to the handler,
  but may need to surface the span/position alongside it — that is why the
  codec is in files_scope.
- Whether the strip lives in the adapter or in Provider is spec-level free
  choice ("the adapter may do the strip, or Provider may do it consistently
  across adapters"); the files_scope here commits to the adapter side, which
  is where the span data lives. If the implementer concludes Provider-side
  stripping is materially simpler, that changes files_scope — escalate.
