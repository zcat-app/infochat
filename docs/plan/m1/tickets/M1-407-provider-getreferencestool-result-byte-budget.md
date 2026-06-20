---
id: M1-407
title: "provider: bound GetReferencesTool result bytes like its sibling chat tools"
status: done
created: 2026-06-20
last_updated: 2026-06-20
blocked_by: []
files_budget: 2
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/GetReferencesTool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - GetPostTool.truncateUtf8 (the shared UTF-8 truncation helper) — reused as-is, not modified.
  - The SQL query and its LIMIT 25 row cap — unchanged; this ticket adds an output-byte bound, it does not change which rows are selected.
  - The existing byte budgets in the four sibling tools (SearchPostsTool, ListSavesTool, GetPostTool, RecallMemoryTool) — unchanged.
acceptance:
  - "GetReferencesTool bounds its total emitted result bytes with a fixed budget consistent with its sibling tools' MAX_RESULT_BYTES, and truncates each per-row title with a per-title byte budget via the shared GetPostTool.truncateUtf8 helper, so the JSON it returns cannot exceed the budget regardless of how long the row titles are."
  - "A test in infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool feeds rows whose titles sum beyond the budget and asserts the tool's output length is within the budget and that appending stops once the budget is reached (mirroring SearchPostsToolTest's budget assertion)."
  - "A test asserts an under-budget result is byte-identical to the pre-change output (well-formed small results are unaffected)."
  - "GetReferencesToolTest and the four sibling tool tests remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool (GetReferencesTool budget + per-title truncation)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-20
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 127
      removed: 15
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-20
    verdict: CLEAN
    base: eb4838c6e21dce7b452726bc2f404046ff19598a
    head: "working-tree (m1/M1-407 branch, uncommitted)"
    verdict_file: docs/plan/m1/redteam/M1-407-2026-06-20.md
    out_of_model_count: 0
    note: |
      Adversarial audit on the in-progress branch tip after round-1 APPROVE,
      before /m1-tick commit. CLEAN — the change tightens the
      GetReferencesTool chat-tool result against the prompt-context trust
      boundary (aggregate byte budget + per-title truncation via the shared
      GetPostTool.truncateUtf8), mirroring the four sibling tools. No new
      attack surface; nothing feeds a future remediation ticket.
clarity_check:
  date: 2026-06-20
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-407: bound GetReferencesTool result bytes like its sibling chat tools

## Context

Deep-review full (2026-06-20) provider finding **F1** (SECURITY, medium).
Verified at source 2026-06-20:

`GetReferencesTool` is the one chat tool in the closed allowlist that emits its
result with no UTF-8 result-byte budget and no per-title truncation. Its four
siblings all explicitly bound their output against the chat-prompt context-window
trust boundary:

- `SearchPostsTool` — `MAX_RESULT_BYTES = 16*1024` plus a per-entry budget loop.
- `ListSavesTool` — `MAX_RESULT_BYTES = 16*1024` and `MAX_TITLE_BYTES = 2*1024`
  via `truncateUtf8`.
- `GetPostTool` — `MAX_BODY_BYTES = 8*1024` via `truncateUtf8`.
- `RecallMemoryTool` — `MAX_SUMMARY_BYTES` / `MAX_RESULT_BYTES` budget loop.

`GetReferencesTool`'s only bound is `LIMIT 25` rows; `to_title`/`to_url` come from
the same uncapped `post` table the sibling tools draw from, and the only transform
it applies is JSON escaping (`jsonStr`), no truncation. A handful of multi-kilobyte
post titles therefore reinject an outsized, attacker-influenceable payload into the
chat prompt context window — the exact trust-boundary invariant the four siblings
uphold. Severity is medium (single-result amplification bounded by `LIMIT 25`, not
an unbounded loop), not high.

The shared `GetPostTool.truncateUtf8` helper already exists and is reused by three
of the four siblings; this ticket reuses it rather than adding a new truncator.

## Acceptance

See frontmatter. Add a fixed result-byte budget and per-title truncation to
`GetReferencesTool`, reusing the existing shared helper. Well-formed small results
are unchanged.

## Out-of-scope

See frontmatter. The SQL query, the `LIMIT 25` row cap, the shared truncation
helper, and the siblings' budgets are all unchanged.

## Notes

- `security_relevant: true`: the chat-tool result is fed back into the LLM prompt
  context window, which is the prompt-injection-adjacent trust boundary the sibling
  budgets exist to protect.
</content>
</invoke>
