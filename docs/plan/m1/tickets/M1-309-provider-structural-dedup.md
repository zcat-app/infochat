---
id: M1-309
title: "Provider structural dedup: PG listener base class, shared command tokenizer"
status: done
created: 2026-06-11
last_updated: 2026-06-13
blocked_by: []
files_budget: 14
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/outbox
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - reply() ×33 and quoteJsonString() ×3 dedup — the report stages the tokenizer first (real parser-boundary bug surface); the rest stays backlogged.
  - fuzzySuggest()/sharedPrefixLength() ×2 (textually diverged) — investigate-only; unify only if byte-identical behaviour falls out, else backlog.
  - LISTEN/NOTIFY payload formats and cursor semantics — only the worker lifecycle machinery is shared.
acceptance:
  - "U-46: the duplicated LISTEN/NOTIFY worker machinery in NewPostListener and QuarantineReviewListener collapses into an AbstractPgListener base adopting the STRICTER existing discipline (QuarantineReviewListener's: synchronized lifecycle + nulled connection — 5 synchronized blocks vs 0 in NewPostListener, verified 2026-06-11); both listeners' existing tests stay green; a named lifecycle test exercises start/stop/reconnect through the base."
  - "U-51 (staged: tokenizer only): the four quote-aware tokenize() copies in the command package (InviteCommandHandler, AuditCommandHandler, BanCommandHandler, AddSourceArgs — verified 2026-06-11) collapse to one shared package-private tokenizer; a named test pins quote/escape behaviour against cases from all four former call sites so any silent divergence among the copies surfaces now."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-13
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 668
      removed: 522
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-13
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-309: Provider structural dedup: PG listener base class, shared command tokenizer

## Context

Deep-review v5 verified **U-46** (MEDIUM) and **U-51** (LOW, staged)
(`deep-code-review/v5/UNIFIED-REPORT.md` §4; sources `fable-5/07#F2` +
`gpt-55#M-15`, `fable-5/07#F4` + `gpt-55#L-15` — gitignored; all
load-bearing facts inlined):

Both are behavior-bearing copies that have already drifted: the two PG
listeners disagree about lifecycle synchronization (a real bug class —
whichever discipline is right, one of them is wrong today), and the four
tokenizer copies are one divergence away from commands parsing differently
per handler.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter — the staging decision (tokenizer first, reply()/quoteJson
later) is the report's, kept deliberately.

## Notes

- If extracting the base class surfaces a genuine semantic difference
  between the two listeners (not just discipline), STOP and surface it —
  that's a finding, not a refactor obstacle.
- Cross-lens observation worth knowing while in this file (NOT in scope,
  backlogged): the quarantine_review shared-cursor catch-up can skip a
  lost post-NEEDS_REVIEW event if a later-timestamped quarantine event
  advanced the cursor. Don't fix it here; don't make it worse either.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-309-*.md
```
