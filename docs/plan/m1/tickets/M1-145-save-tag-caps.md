---
id: M1-145
title: "/save personal-tag length + count caps"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java
  - infochat-provider/src/main/resources
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the read-side caps (already enforced via ChatToolDispatcher.validateInputLengths)
  - other command handlers
acceptance:
  - "/save -t enforces a per-tag length cap and a per-call tag-count cap, read from profile-driven config, at the parser boundary"
  - "Over-cap input is rejected with friendly-error bundle keys rather than stored"
  - "A test asserts an over-length tag and an over-count tag list are both rejected"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/security.md §Rate limiting
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-145: /save personal-tag length + count caps

## Context

`SaveCommandHandler.java:265-321` accepts unbounded personal-tag strings and
counts (bounded only by the 64 KB body cap). `/saved` interpolates them into
outbound (bypassing the chat body cap) and `listSaves` reads them into the
prompt. The read side is capped; the write side is the symmetric obligation.

## Acceptance

See frontmatter. Profile-driven per-tag length + per-call count caps at the
parser; friendly-error bundle keys.

## Out-of-scope

See frontmatter. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §B-SAVE-UNBOUNDED;
  `opus-47-full-handout.md` §F-SEC-13; `opus-47-only-handout.md` §S5.
- Suggested defaults: `infochat.save.personal-tag-max-length=64`,
  `infochat.save.personal-tag-max-count=20`.
