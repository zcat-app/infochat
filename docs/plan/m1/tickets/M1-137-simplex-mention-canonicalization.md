---
id: M1-137
title: "SimpleX mention canonicalization → exact-bytes compare"
status: done
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 3
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParser.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - other SimpleX adapter files (codec, websocket) — mention parser only
  - the Signal mention path
acceptance:
  - "SimpleXMentionParser recognises a bot mention by an exact-bytes (constant-time) compare against the bot's stable queue address, not a non-injective canonicalization that can collide"
  - "The parser performs NO encoding canonicalization on the queue address: comparison is exact bytes of the simplex-chat memberRef string (the stable canonical identifier, validated by SimpleXMessageCodec.isValidQueueAddressId before it reaches the parser). Two queue-address strings differing only in base64 padding or alphabet are a non-match, not a match."
  - "A regression test with a colliding pair (the base64 string and the literal that the removed decode collapsed to the same bytes — e.g. \"MTIzNDU=\" vs \"12345\", both of which pass isValidQueueAddressId) asserts the non-mention is not read as a mention and a real (exact-string) mention is not suppressed"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParserTest.java
  preserves:
    - all tests currently green on main EXCEPT SimpleXMentionParserTest.base64PaddedVsUnpaddedEquivalence, which is deleted — it asserted the decode-canonicalization being removed (padded vs unpadded base64 MUST compare equal) and is unsatisfiable under exact-bytes compare
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/messaging.md §Identity and groups
decision_refs:
  - D10
reviews:
  - round: 1
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 106
      removed: 75
escalations:
  - date: 2026-06-02
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — pre-implementation conflict. The spec-mandated exact-bytes
      compare (acceptance item 1; messaging.md §Required SPI surface —
      Receive line 42-44 "byte-equality against the bot's per-adapter
      contact id") necessarily breaks the currently-green test
      SimpleXMentionParserTest.base64PaddedVsUnpaddedEquivalence
      (lines 82-92), which asserts padded vs unpadded base64 MUST
      compare equal via decode-canonicalization. test_plan.modifies is
      empty and test_plan.preserves promises "all tests currently green
      on main", so the required test change is unauthorized. Underlying
      factual question for the user: is the SimpleX queue-address string
      form stable on the wire (ticket's premise) or does it vary in
      base64 padding/alphabet across simplex-chat versions (the breaking
      test's premise)? If the latter, exact-bytes compare would suppress
      real mentions — contradicting acceptance item 2.
revisions:
  - date: 2026-06-02
    reason: premise-fail refine (round 1) — exact-bytes compare (acceptance 1, spec-confirmed messaging.md §Receive "byte-equality against the bot's per-adapter contact id") breaks currently-green SimpleXMentionParserTest.base64PaddedVsUnpaddedEquivalence. Grounded the stability premise (memberRef is simplex-chat's canonical id, QUEUE_ADDRESS_CHARSET excludes +/ standard-alphabet, validated by isValidQueueAddressId before the parser → exact-bytes is correct AND injective). Added acceptance item 2 making the no-canonicalization contract explicit; authorized deleting the one decode-era test via test_plan.modifies; narrowed test_plan.preserves.
    prior_values: |
      acceptance: (2 items, no explicit no-canonicalization contract)
        [1] "SimpleXMentionParser recognises a bot mention by an exact-bytes
            (constant-time) compare against the bot's stable queue address,
            not a non-injective canonicalization that can collide"
        [2] "A regression test with a colliding pair asserts a non-mention is
            not read as a mention and a real mention is not suppressed"
      test_plan.modifies: (absent)
      test_plan.preserves: ["all tests currently green on main"]
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-02
    verdict: CLEAN
    base: 379fc6f56d09ed94bfd49087887fec9ef1d90a77
    head: 9dbbe96
    verdict_file: docs/plan/m1/redteam/M1-137-2026-06-02.md
    out_of_model_count: 1
    note: |
      Pre-merge --in-progress audit of the per-ticket branch. CLEAN — the
      threat-actor judged the exact-bytes constant-time compare a net
      security improvement that strengthens D10 / messaging.md §Receive
      byte-equality, with no defended promise weakened. One OUT-OF-MODEL
      advisory (mention-suppression via encoding drift) is the deliberate
      spec-mandated trade-off already weighed in the round-1 refine; not
      converted to a remediation ticket.
clarity_check:
  date: 2026-06-02
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-137: SimpleX mention canonicalization → exact-bytes compare

## Context

`SimpleXMentionParser.java:57-93` canonicalizes mentions non-injectively: two
distinct queue-address strings can collide, so a non-mention reads as a bot
mention or a real mention is suppressed. D10 makes mentions the group-mode
authorization trust anchor; the spec promises mentions can't be forged or
suppressed. Single-reporter — read the per-module report detail and construct
the colliding pair before locking the fix.

## Acceptance

See frontmatter. Replace the canonicalization with a constant-time exact-bytes
compare (the queue address is already a stable opaque identifier); add a
regression test with the collision pair.

## Out-of-scope

See frontmatter. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A13 (SIMPLEX-MENTION, High);
  `opus-47-full-handout.md` §F-SEC-08.
