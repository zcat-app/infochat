---
id: M1-187
title: "Strip bot-mention span in group inbound delivery"
status: done
created: 2026-06-07
last_updated: 2026-06-07
escalations:
  - date: 2026-06-07
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — pre-implementation call-site sweep found a second pre-existing
      assertion pinning the unstripped form: SignalGroupHandlerTest:61
      assertEquals("@bot summarise this", msg.text()). The ticket authorizes
      only SignalGroupEndToEndTest:83 and requires "every other existing
      assertion in the group tests must keep passing" — impossible once the
      strip is implemented. Secondary: SignalGroupEndToEndTest's
      groupMention() helper (:154) pairs bodies "first"/"second" with a bogus
      span [0,4), so the :147 assertion would also fail unless the fixture
      body becomes "@bot "-prefixed (assertion itself preserved).
clarity_check:
  date: 2026-06-07
  verdict: WARN
  warnings:
    - "Acceptance item 5 ('MessagingAdapter.InboundHandler's javadoc promise … is now true for both production adapters') uses a by-inspection form. The assertion is implicitly covered by items 1–3; consider either dropping it or recasting as a Grep-assertable check on the committed source."
  blockers: []
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
  - "Pre-existing pinned expectations of the unstripped form are updated to the stripped form at exactly two named sites — SignalGroupEndToEndTest:83 and SignalGroupHandlerTest:61 (both today assertEquals(\"@bot summarise this\", msg.text())) — these are the only AUTHORIZED test-expectation changes"
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
reviews:
  - round: 1
    date: 2026-06-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 434
      removed: 16
revisions:
  - date: 2026-06-07
    reason: premise-fail refinement — second pinned expectation site missed by the ticket's call-site sweep
    summary: |
      - acceptance item 4: prior text authorized ONE expectation change
        ("SignalGroupEndToEndTest's pinned expectation (today
        assertEquals(\"@bot summarise this\", msg.text()) at :83) is updated
        to the stripped form — this is the one AUTHORIZED test-expectation
        change"). SignalGroupHandlerTest:61 pins the identical unstripped
        expectation and must change too; the item now names exactly two
        authorized sites: SignalGroupEndToEndTest:83 and
        SignalGroupHandlerTest:61.
      - body §Out-of-scope: additionally authorizes making internally
        inconsistent fixture INPUTS self-consistent where the existing
        assertion text keeps passing verbatim (SignalGroupEndToEndTest
        groupMention() helper :154 — bodies "first"/"second" paired with a
        bogus span [0,4) become "@bot "-prefixed so the :147
        assertEquals("second", msg.text()) passes unchanged).
      - body §Notes: documents the SimpleX reconstruction-guard strip
        strategy and why SimpleXGroupHandlerTest:64's existing assertion
        keeps passing untouched (its fixture's mention segment "@m" does not
        reconstruct the body, so no protocol span exists to strip).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-07
    verdict: CLEAN
    base: d0340b7
    head: m1/M1-187-group-mention-stripping (branch + working tree, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-187-2026-06-07.md
    findings_count: 0
    out_of_model_count: 1
    note: |
      Pre-commit audit run at user request despite security_relevant: false.
      CLEAN — strip is anchored to the D10 cryptographic mention entry (never
      display-name search), untrusted offsets are bounds-checked/reconstruction-
      guarded, no reader-thread exception path, and the strip makes no
      authorization decision (ban/invite/command-parse stay downstream at
      Provider intake). One advisory OUT-OF-MODEL note: a large alternating
      span/gap Signal mentions array could push the StringBuilder delete toward
      O(L^2); below the threat model's commitments (no adapter-side input-size
      bound is promised), so advisory only — a future ticket could cap
      mention-array/body length in the adapter if desired.
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

See frontmatter. Two pre-existing-test expectation changes are named and
authorized (acceptance item 4): SignalGroupEndToEndTest:83 and
SignalGroupHandlerTest:61 — both pin the identical unstripped
`assertEquals("@bot summarise this", msg.text())`. Additionally, fixture
INPUTS whose mention span is internally inconsistent may be made
self-consistent provided the existing assertion text keeps passing verbatim:
SignalGroupEndToEndTest's groupMention() helper (:154) pairs bodies
"first"/"second" with a span [0,4) that does not correspond to any mention
text — the fixture body becomes "@bot "-prefixed so the span matches a real
mention and the :147 `assertEquals("second", msg.text())` passes unchanged.
Every other existing assertion in the group tests must keep passing.

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
- SimpleX strip strategy (reconstruction guard): simplex-chat's
  formattedText decomposes msgContent.text — the concatenation of segment
  texts equals the full text on real frames. The codec computes each
  mention segment's [start, length) span while walking formattedText; the
  spans are trusted only when the segments reconstruct the text exactly.
  When they do not (degenerate or hostile frame), no protocol span exists
  to strip — the text is delivered as-is and D10 recognition is unaffected.
  This is why SimpleXGroupHandlerTest:64's existing assertion
  (`assertEquals("hi @bot", msg.text())` over a fixture whose mention
  segment text "@m" does not appear in the body) keeps passing untouched.
