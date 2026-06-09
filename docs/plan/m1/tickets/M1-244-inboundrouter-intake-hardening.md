---
id: M1-244
title: "InboundRouter: fold is_banned into snapshot + command body cap"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/BanCheck.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterBanSnapshotTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterCommandCapTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The admin confirm-leg ban paths that do their own FOR UPDATE reads — they keep using BanCheck; only the intake step-4 read is served from the snapshot.
  - The step-4 execution ordering relative to step 3/3.5 (security.md §Authorization model) — unchanged; only the SOURCE of the is_banned value moves from a second SELECT to the step-1 snapshot.
  - The generic 64 KiB byte cap and the existing chat-mode cap (chatBodyCap) — unchanged; this adds the slash-command cap that is currently missing.
  - BanCommandHandler and RateCapBucket — owned by M1-249.
acceptance:
  - "T4: USER_SNAPSHOT_SQL selects is_banned alongside id, registration_state, and UserSnapshot carries an isBanned field; the intake step-4 ban check reads the snapshot value instead of issuing a second SELECT is_banned FROM users per inbound. InboundRouterBanSnapshotTest asserts a banned user's inbound is rejected at step 4 with the fixed reply and no LLM/parse/further DB query, using only the single snapshot read. The class javadoc (and UserSnapshot doc) drops the \"separate live is_banned query\" rationale."
  - "T6: a profile-driven char cap infochat.command.body-cap (per-profile defaults laptop 8192 / vps 4096 / pi 2048 / remote-llm 16384, per docs/design/03-commands.md) is applied to slash-command bodies after normalization and before handleSlash; an over-cap slash body is rejected with a friendly error bundle key and does not reach the parser. InboundRouterCommandCapTest asserts an over-cap slash body is rejected with the error reply and an under-cap slash body is parsed normally."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterBanSnapshotTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterCommandCapTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/commands.md §Surface conventions
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-244: InboundRouter intake-path — is_banned snapshot + command body cap

## Context

Two intake-path findings grouped because both modify `InboundRouter` on the same
inbound hot path (folding them avoids a merge conflict between two separate
tickets on one file). Source: `deep-code-review/v3/` UNIFIED-REPORT.md T4 (mimo
`07#F1`+`07#F4`) and T6 (opus `07#F1`).

- **T4 [high, perf].** `USER_SNAPSHOT_SQL` selects only `id,
  registration_state`; step 4 calls `banCheck.isBanned(...)`, a second `SELECT
  is_banned FROM users WHERE adapter=? AND contact_id=?` on **every** inbound.
  The javadoc honestly documents this as a deliberate "freshest is_banned"
  choice, but `security.md §Authorization model` (the step-4 ordering rule) only
  requires the ban check reads `is_banned=true` at step-4 ordering — it does
  **not** mandate a separate live query. Folding `is_banned` into the snapshot is
  spec-legal: the TOCTOU between a step-1 read and a step-4 read is milliseconds
  and the ban takes effect on the next message regardless.
- **T6 [medium, rules-drift].** `commands.md` commits to **two** caps;
  `design/03-commands.md` assigns per-profile values. Only the chat-mode cap is
  implemented (`chatBodyCap`, non-slash bodies only). There is no
  `infochat.command.body-cap` property and the slash path has no length gate
  before parsing; the only backstop is the generic 64 KiB byte cap.

## Acceptance

See frontmatter. In prose: add `is_banned` to the snapshot and serve the intake
ban check from it (keeping `BanCheck` for the admin confirm-leg `FOR UPDATE`
paths); add the profile-driven slash-command char cap mirroring the existing
chat-cap shape and the design per-profile values. Named tests pin both; `mvn
verify` is 0.

## Out-of-scope

See frontmatter. Step-4 ordering, the 64 KiB byte cap, the chat cap,
`BanCommandHandler`, and `RateCapBucket` are untouched.

## Notes

- T4 was flagged by mimo as a "javadoc lies" contradiction; the report corrected
  that — the javadoc is internally honest. Draft/implement T4 as a pure
  performance optimization authorized by the spec check above, not as a doc fix.
- `security_relevant: true` because the ban check is a security gate (the change
  moves its data source, not its semantics); a `/redteam` pass is appropriate.
- The error-bundle key for the command cap should mirror the chat-cap's
  friendly-rejection shape (look at how `chatBodyCap` rejects).
</content>
</invoke>
