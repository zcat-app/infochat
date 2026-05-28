---
id: M1-115
title: "Sync LlmOutputSanitizer.CLOSED_LIST with commands.md bot-admin set"
status: done
created: 2026-05-28
last_updated: 2026-05-28
blocked_by: []
files_budget: 2
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-collector/** — sanitizer is provider-side only
  - infochat-core/** — no SPI changes
  - docs/spec/commands.md — the spec already lists the three commands (commit 8b22ee1); this ticket syncs runtime to match the spec
  - ApproveGroupCommandHandler, RejectGroupCommandHandler, ListGroupsCommandHandler — M1-113 (this ticket does NOT register handlers; CLOSED_LIST membership is independent of handler registration because the sanitizer parses spec markdown, not the handler registry)
  - slow-start probation classifier closed-list — separate runtime constant in a different file, not affected by this ticket
  - any CLOSED_LIST changes beyond the three D47 commands — the spec ↔ runtime drift is exactly the three D47 tokens
  - LlmOutputSanitizerAuditRowIT — integration-tier audit-log behavior; this ticket is unit-tier only
acceptance:
  - "LlmOutputSanitizer.CLOSED_LIST contains the literal strings `/approve-group`, `/reject-group`, and `/list-groups`, appended after `/list-sources --include-deleted` to match the order in commands.md §Permission model §Closed list"
  - "LlmOutputSanitizerTest.matchSetEqualsSpecClosedList passes (the CI-completeness test that parses commands.md and asserts spec set equals runtime CLOSED_LIST set)"
  - "LlmOutputSanitizerTest.approveGroupTokenIsStripped passes (follows the existing `<commandToken>TokenIsStripped` per-entry @Test convention; calls the same assertStripped helper used by every existing per-entry test)"
  - "LlmOutputSanitizerTest.rejectGroupTokenIsStripped passes (same convention as above for `/reject-group`)"
  - "LlmOutputSanitizerTest.listGroupsTokenIsStripped passes (same convention as above for `/list-groups`)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Permission model
  - docs/spec/security.md §LLM output sanitizer
decision_refs:
  - D47
reviews:
  - round: 1
    date: 2026-05-28
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 79
      removed: 10
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-05-28
    verdict: CLEAN
    base: f14503a^
    head: f14503a
    verdict_file: docs/plan/m1/redteam/M1-115-2026-05-28.md
    out_of_model_count: 1
    note: |
      Additive CLOSED_LIST sync. Adversary confirmed the runtime set now
      mirrors the spec's 28-token closed privileged-tier list in both
      directions and the matchSetEqualsSpecClosedList CI check genuinely
      enforces it. Closes a prior gap (3 group-admin commands were
      leakable through LLM output); introduces none. One OUT-OF-MODEL
      advisory on obfuscated/split-token matching — consistent with spec
      intent, no remediation ticket filed.
clarity_check:
  date: 2026-05-28
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-115: Sync LlmOutputSanitizer.CLOSED_LIST with commands.md bot-admin set

## Context

D47 spec commit `8b22ee1` added three bot-admin commands
(`/approve-group`, `/reject-group`, `/list-groups`) to
`docs/spec/commands.md` §Permission model §Closed list, but did
not touch any Java source. `LlmOutputSanitizer.CLOSED_LIST` is the
runtime enforcement of that spec list — hand-maintained in code to
mirror the spec verbatim (per the class javadoc and the dedicated
CI completeness test `LlmOutputSanitizerTest.matchSetEqualsSpecClosedList`).

The result is a current spec ↔ runtime divergence: `commands.md`
lists 28 closed-set tokens (22 bot-admin + 6 group-admin), the
runtime list has 25 (19 bot-admin + 6 group-admin). The
completeness test fails on `main` today; every other ticket's
`mvn -B clean verify` acceptance trips on it.

This ticket is the additive sync. No handlers, no spec edits, no
probation classifier changes — just CLOSED_LIST gains three
entries and the test file gains three per-entry @Tests following
the existing convention.

`security_relevant: true` — the sanitizer is on the LLM-output
path. Until CLOSED_LIST contains these tokens, an LLM emitting
`/approve-group` (or the other two) in its response is not
recognized as a privileged-tier command and passes through
unredacted, contrary to the spec's promise.

## Acceptance

See frontmatter. Five named tests must pass plus `mvn -B clean
verify`.

## Out-of-scope

- Spec edits — the spec is already correct (commit `8b22ee1`).
  This ticket only catches the runtime up to the spec.
- Command-handler registration — M1-113 owns
  ApproveGroupCommandHandler, RejectGroupCommandHandler, and
  ListGroupsCommandHandler. CLOSED_LIST membership is independent
  of handler registration: the sanitizer parses spec markdown at
  test tier, not the runtime handler registry. M1-115 can land
  and pass `mvn verify` with no command-handler implementation in
  place.
- Slow-start probation classifier closed-list — that constant
  lives elsewhere and is the subject of a separate (unfiled)
  drift the user can audit independently.
- Refactoring CLOSED_LIST shape (e.g. converting List → Set, or
  extracting the list to a properties file) — out of scope here;
  the additive sync uses the existing List shape.

## Notes

- **Why M1-115 instead of folding into 8b22ee1's spec commit or
  M1-113.** The spec commit landed without the Java sync, so the
  invariant has been broken on `main` since 2026-05-27. Waiting
  for M1-113 (which depends on M1-112 which depends on M1-111
  which depends on M1-110) means every D47 ticket's
  `mvn -B clean verify` acceptance fails until the whole chain
  lands. M1-115 unblocks the chain by closing the spec↔runtime
  divergence today.
- **M1-113's Notes section is wrong about this.** M1-113 lines
  98–101 claim the sanitizer was updated by the spec commit. It
  wasn't (the spec commit touched zero Java/SQL files). A separate
  `process:` commit corrects M1-113's Notes after M1-115 lands.
- **Order preservation.** `LlmOutputSanitizer.CLOSED_LIST`'s
  javadoc commits to "Order is the spec's order". The spec lists
  the three new commands at the end of the bot-admin enumeration,
  just before the group-admin list. The three new entries belong
  in the same position in CLOSED_LIST — after
  `/list-sources --include-deleted` and before the
  group-admin section comment.
- **Test pattern.** Each per-entry test calls
  `assertStripped(tokenString)` — a single-line helper that
  drives both the rewrite assertion and the per-occurrence WARN
  log assertion. The three new tests follow that pattern; no new
  helpers are needed.
- **No grep-cardinality acceptance.** Per the ticket-template
  guidance (`docs/process/ticket-template.md` §acceptance), the
  acceptance pins specific named tests rather than "@Test count ≥ N",
  so the diff is verified by behavior, not regex match counts.

## Pre-flight self-check (author-side)

`python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-115-sanitizer-closed-list-sync.md`
should report no issues.
