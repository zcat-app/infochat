---
id: M1-197
title: "Tool JSON ready_at value + /export paged replies and truncation flag"
status: done
created: 2026-06-07
last_updated: 2026-06-08
clarity_check:
  date: 2026-06-08
  verdict: PASS
  warnings: ["Acceptance item 2 leaves the implementation approach for paged /export replies open (implementer's call); the SPI-adjacent route would require the escalate path, potentially consuming a round"]
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/GetPostTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ExportCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ExportDataCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ExportPaginator.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - SearchPostsTool's per-call connection count — M1-193's
  - getPost/recallMemory result byte budgets and EligiblePostQuery's SQL LIMIT — M1-194's
  - the searchPosts window/ordering semantics (`published_at` as the window filter and sort key) — only the JSON ready_at VALUE leg changes; reordering by ready_at is a separate spec judgment nobody has filed
  - export memory footprint (10k rows/table materialized in heap, gpt P5) — UNIFIED.md T33 (lows batch, not yet filed)
  - the group-scope caller-resolution trap in admin handlers — M1-198's
acceptance:
  - "Per docs/spec/security.md §Prompt-injection defenses (tool catalogue) — searchPosts returns a \"list of `{uid, title, url, ready_at, tags}`\" and getPost returns \"`{uid, title, body, url, ready_at, tags}` or `null`\" — the ready_at JSON field carries the post's ready_at column value: named tests seed a post whose published_at differs from ready_at and assert the emitted JSON value equals ready_at (today both tools SELECT p.published_at and emit it under the ready_at key — SearchPostsTool.java:168-169, GetPostTool.java:61-62)"
  - "Per docs/spec/commands.md §Conversation control — /export \"is sent as a reply message (or paginated reply messages)\" and \"if the total export size exceeds the per-message cap, the reply is split into pages\" — no single /export reply message body exceeds the page cap: a named test seeds more than one page of data and asserts every emitted reply body is within the cap while all export data remains reachable in-band (today formatPages concatenates every page into ONE OutboundMessage, making the cap cosmetic)"
  - "An exactly-cap-full table is not flagged truncated: a named test seeds exactly maxRowsPerTable rows and asserts the table is absent from truncatedTables (today rows.size() >= maxRowsPerTable with LIMIT maxRowsPerTable false-positives on the exactly-full case)"
  - "Existing export and tool tests stay green except where they pin the corrected behaviors"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/commands.md §Conversation control
decision_refs:
  - D13
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 396
      removed: 55
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-197: Tool JSON ready_at value + /export paged replies and truncation flag

## Context

Three reply-correctness defects (unified findings P9, P13 —
`deep-code-review/v2/UNIFIED.md` §2):

1. **ready_at mislabel (P9, med).** Both chat tools SELECT
   `p.published_at` and emit it under the JSON key `"ready_at"`.
   Re-anchored 2026-06-07: the spec's tool catalogue
   (security.md §Prompt-injection defenses) names `ready_at` in both
   result shapes, and the `post` table has a real `ready_at` column
   (V7:147) — so the key is spec-correct and the VALUE is the bug. The
   fix direction is to surface `ready_at`, not to rename the key.
2. **/export single-message concatenation (P13, med).**
   ExportCommandHandler:102-104 paginates via ExportPaginator, then
   formatPages joins all pages back into one reply string → one
   OutboundMessage. The spec requires the reply be "split into pages"
   past the cap.
3. **Truncation off-by-one (P13, low).** ExportDataCollector:191 flags
   `rows.size() >= maxRowsPerTable` with `LIMIT maxRowsPerTable` —
   an exactly-full table is falsely reported truncated.

Re-grounding constraint discovered at draft time: `CommandHandler.handle`
returns a SINGLE OutboundMessage — there is no multi-message reply
surface in the handler SPI. Satisfying the paged-reply sentence may use
per-page sends (an SPI-adjacent change — goes through the escalate
path, since every handler and InboundRouter would be touched) or the
follow-the-corpus `--page N` re-invocation pattern (ListSources /
`/invite list` shape). Implementer's call, argued in the commit message.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T21 under `deep-code-review/v2/` (opus-47
  prov F2/F8, opus-48 prov F4, kimi-folder prov F8). T21 was split at
  draft time: the caller-resolution leg is M1-198.
- M1-193 and M1-194 touch SearchPostsTool/GetPostTool — no semantic
  dependency, but do not run this ticket concurrently with either.

## Suggested direction (unverified hypothesis)

The audit suggested a `LIMIT cap+1` probe for the truncation flag
(fetch one row beyond the cap; flag truncated only when it appears).

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
is there a simpler alternative meeting the same acceptance?). Adopting,
adapting, or replacing it is the implementer's call as long as every
acceptance item holds; a replacement that changes files_scope goes
through the escalate path.
