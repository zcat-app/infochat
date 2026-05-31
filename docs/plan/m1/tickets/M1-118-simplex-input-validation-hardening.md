---
id: M1-118
title: "SimpleX input-validation hardening — contactId shape + inbound size cap"
status: done
created: 2026-05-31
last_updated: 2026-05-31
blocked_by: []
files_budget: 5
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  - docs/design/06-messaging.md
complexity: low
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes; remediation is adapter-internal
  - infochat-collector/** — no collector changes
  - infochat-provider/** — no provider changes
  - any change to MessagingAdapter SPI or other messaging SPI types
  - SimpleXAdapter.java — capability declarations stay as M1-103 set them
    (the cap value already declared is what the codec must now enforce)
  - SimpleXSubprocess.java — covered by M1-119
  - SimpleXWebSocketClient.java — covered by M1-119
  - Signal adapter — out of scope
  - amending the M1-103 commit; remediation is a new commit per workflow
acceptance:
  - "SimpleXMessageCodecTest.decodeRejectsContactIdWithCommandInjectionChars passes — a newChatItem whose contactId contains a space, newline, or simplex-chat command terminator decodes as Ignored (or a new variant), and the frame body is never echoed into an outbound command"
  - "SimpleXMessageCodecTest.decodeAcceptsValidQueueAddressShapedContactId passes — a contactId matching the documented queue-address character set decodes as Inbound with the contactId preserved verbatim"
  - "SimpleXMessageCodecTest.encodeRejectsContactIdWithCommandInjectionChars passes — encodeSendCommand / encodeUpdateCommand / encodeFinalizeCommand / encodeTypingCommand throw IllegalStateException when handed a ScopeRef whose contactId or adapterGroupId fails the validator (defense-in-depth at the encode boundary)"
  - "SimpleXMessageCodecTest.decodeRejectsTextExceedingInboundCap passes — a newChatItem whose text field exceeds maxInboundMessageBytes (16 KiB on the laptop profile, per SimpleXConfig) decodes as Ignored without constructing an InboundMessage"
  - "SimpleXMessageCodecTest.decodeAcceptsTextAtExactlyInboundCap passes — a newChatItem whose text is exactly maxInboundMessageBytes in UTF-8 byte length is still accepted"
  - "Queue-address character-set rule is documented in docs/design/06-messaging.md (the design note the adapter validates against)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  preserves:
    - all tests currently green on main, including SimpleXMessageCodecTest.encodesAndDecodesMessages and SimpleXMessageCodecTest.classifiesFailureCategory (the existing M1-103 tests continue to pass)
spec_refs:
  - docs/spec/security.md §Trust boundaries
  - docs/spec/messaging.md §Required SPI surface
decision_refs:
  - D32
  - D46
reviews:
  - round: 1
    date: 2026-05-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 357
      removed: 10
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-31
  verdict: WARN
  warnings:
    - "Acceptance item 6 (queue-address character-set rule documented in docs/design/06-messaging.md) is verifiable by diff inspection but uses the weaker inspection form rather than a runnable test or command."
  blockers: []
remediates: M1-103
---

# M1-118: SimpleX input-validation hardening — contactId shape + inbound size cap

## Context

Two findings from the M1-103 red-team audit
(`docs/plan/m1/redteam/M1-103-2026-05-31.md`) land here as a single
remediation ticket because both are codec-layer input-sanitization
fixes touching the same file, and one of them (Finding 2 — command
injection) is the highest-severity finding in the audit:

- **Finding 2 (INJECTION, high)** — `SimpleXMessageCodec.targetSelector`
  concatenates `contactId` / `adapterGroupId` into outbound
  `/_send`, `/_update item`, and `/_set_contact_typing` commands
  without validating that the id contains only queue-address-shaped
  characters. A peer whose `contactId` contains a newline or space
  followed by a forged simplex-chat verb gets that verb executed
  under the bot's identity. Trust boundary 1 (the adapter-inbound
  boundary, `docs/spec/security.md` §Trust boundaries — the
  top-level section, not §Per-source trust boundaries) commits to
  validating this at intake.
- **Finding 3 (DOS, medium)** — `SimpleXAdapter` declares
  `maxInboundMessageBytes = 16 KiB`, but `SimpleXMessageCodec.decodeNewChatItem`
  does not enforce it; only the WebSocket frame buffer's 1 MiB cap
  applies. The capability flag is a contract per `messaging.md`
  §Inbound message size cap; the Provider's downstream budgets
  (LLM tokens, Stage 1 watchdog) assume the declared cap holds.

The M1-103 commit is `done` and immutable per workflow rules; the
fixes land here as a new commit with `remediates: M1-103`.

## Acceptance

See frontmatter. Two-part remediation: (a) add a queue-address-shape
validator in the codec, applied at decode time (invalid →
`Ignored` so the frame is dropped before any scope state is
populated) and at encode time (defense-in-depth assertion in the
encoder paths); (b) enforce `maxInboundMessageBytes` in
`decodeNewChatItem` after extracting `text`.

The queue-address character set is the load-bearing design call —
document it in `docs/design/06-messaging.md` before implementing
the validator so the reviewer can check the validator regex against
the documented rule rather than against an implementer assumption.

## Out-of-scope

See frontmatter. The SPI is untouched (this is purely a codec-internal
defense). `SimpleXSubprocess.java` and `SimpleXWebSocketClient.java`
are intentionally NOT in scope here because their findings (1 and 4)
land in M1-119 — splitting along file boundaries keeps each ticket's
diff small and reviewable.

The existing M1-103 tests (`encodesAndDecodesMessages`,
`classifiesFailureCategory`) MUST stay green; if a M1-103 test
turns out to encode a now-invalid contactId in its fixture, fix
the fixture (use a queue-address-shaped id) rather than weakening
the validator. Pre-existing-test edits beyond fixture realism are
test-integrity violations per `engineering-rules-verbatim.md` §8.

## Notes

- **Queue-address character set.** SimpleX queue addresses are
  URL-safe base64. The simplex-chat API's `contactId` field may be
  a numeric DB row id (not the queue address itself) depending on
  the API version. Verify against the simplex-chat docs (or a live
  binary frame capture) before locking the regex. Conservative
  starting point: `^[A-Za-z0-9_=.-]+$` — admits both base64 ids and
  decimal ids; rejects whitespace, newlines, simplex-chat command
  terminators.
- **Where to validate `chatItemId` and `corrId`.** `chatItemId` is
  round-tripped from simplex-chat on edit (`encodeUpdateCommand`,
  `encodeFinalizeCommand`); it is equally untrusted and should pass
  the same validator. `corrId` is adapter-generated (UUID-shaped)
  so safe in practice, but a one-line assertion at the encode
  boundary documents the invariant.
- **Cap constant placement.** `SimpleXConfig.maxInboundMessageBytes`
  already exists (set to 16_384 in the constructor at
  `SimpleXAdapter.java:62-68`). The codec needs access to it
  either via a constructor parameter (preferred — already the
  pattern for `MAX_OUTBOUND_TEXT_BYTES`) or via `SimpleXConfig`
  threading through. Keep the constant in one place, do not
  duplicate.
- **Why decode-time validation is structural, not defensive.** Per
  `engineering-rules-verbatim.md` §"No defensive code", validation
  at *system boundaries* is required. The adapter-inbound JSON
  parse is the system boundary; this is the right place to put
  the check. The encode-time assertion is defense-in-depth and is
  permitted because it documents an invariant rather than handling
  an impossible scenario.
- **Design reference:** `docs/design/06-messaging.md` for the
  adapter wire protocol; this ticket adds the queue-address
  character-set rule there.
- **Source:** red-team audit verdict
  `docs/plan/m1/redteam/M1-103-2026-05-31.md` (Findings 2 and 3).
