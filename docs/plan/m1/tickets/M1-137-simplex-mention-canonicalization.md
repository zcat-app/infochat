---
id: M1-137
title: "SimpleX mention canonicalization → exact-bytes compare"
status: pending
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
  - "A regression test with a colliding pair asserts a non-mention is not read as a mention and a real mention is not suppressed"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/messaging.md §Identity and groups
decision_refs:
  - D10
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
