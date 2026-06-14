---
id: M1-329
title: "ListSavesTool: aggregate byte budget + title truncation at the LLM tool boundary"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: WARN
  warnings:
    - "Acceptance item 2 uses 'e.g. MAX_TITLE_BYTES = 2 KiB' — the 'e.g.' leaves the per-title cap value open to implementer discretion. Consider hardening to a binding value if the exact constant matters."
    - "Acceptance item 3 is a rationale sentence, not a distinct testable criterion. Harmless but adds no independently verifiable acceptance surface."
  blockers: []
blocked_by: []
files_budget: 2
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/ListSavesTool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The deterministic /saved command (subject to the outbound message-size cap, not the prompt budget) — unchanged. Users page their full library there.
  - The RESULT_LIMIT=200 row cap — kept; the byte budget is added alongside it, matching the sibling tools.
  - The save-side caps in SaveCommandHandler (personal_tags length/count) — unchanged.
acceptance:
  - "ListSavesTool applies an aggregate output byte budget mirroring its three sibling chat tools: MAX_RESULT_BYTES = 16 KiB (same value as SearchPostsTool / RecallMemoryTool). Entries whose cumulative JSON would exceed the budget are dropped newest-first (the existing ORDER BY saved_at DESC already gives the correct drop order), exactly as searchPosts / recallMemory already behave."
  - "Each row's snapshot_title (external post data, uncapped on the provider side) is truncated to a per-title byte cap (e.g. MAX_TITLE_BYTES = 2 KiB) via GetPostTool.truncateUtf8, mirroring RecallMemoryTool's summary handling, so one pathological title cannot push a single entry far past a reasonable size."
  - "The tool result re-entering the chat prompt is byte-bounded at the LLM tool trust boundary, closing the one path (a long upstream post title × up to 200 rows) where externally-influenced text reinjected verbatim into the context window had no byte bound."
  - "A test pins the budget: a saved-post set whose JSON exceeds MAX_RESULT_BYTES returns a truncated array under the budget (newest entries retained); a single oversized title is truncated to MAX_TITLE_BYTES. A companion test confirms a normal small library is returned unchanged."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool (listSaves budget cases)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 132
      removed: 17
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-14
    verdict: CLEAN
    base: main
    head: "m1/M1-329-listsaves-tool-byte-budget (working tree, uncommitted)"
    verdict_file: docs/plan/m1/redteam/M1-329-2026-06-14.md
    out_of_model_count: 0
    note: |
      In-progress audit between /m1-tick review (APPROVE r1) and
      /m1-tick commit. CLEAN: the aggregate byte budget + per-title
      truncateUtf8 cap deliver §Prompt-injection-defenses' typed/bounded
      tool-output commitment; no gaps, nothing feeds a remediation ticket.
---

# M1-329: ListSavesTool — aggregate byte budget at the LLM tool boundary

## Context

Deep-review v5.5 (opus-48, `07-module-infochat-provider.md` F1) found that
`ListSavesTool` is the one chat tool in its package that bounds only row count
(`RESULT_LIMIT = 200`), not bytes. The three sibling tools each carry an
aggregate output byte budget because, as `SearchPostsTool` documents, "tool
results are reinjected verbatim into the chat prompt (LLM tool-call outputs are a
trust boundary), so a large result set would otherwise consume the context
window." **Verified at source 2026-06-14:** `ListSavesTool` has `RESULT_LIMIT=200`
and no `MAX_RESULT_BYTES`/budget; `SearchPostsTool` enforces `MAX_RESULT_BYTES =
16 KiB` with a per-entry budget loop (SearchPostsTool.java:41,224-239).

Each `listSaves` row emits `snapshot_title` straight from `saved_post`; nothing
on the provider side caps the title length (the save path caps only
`personal_tags`), and the title is external post data. A single `listSaves` call
can therefore return up to 200 rows × an unbounded title each, reinjected
verbatim into the chat prompt — the context-window-exhaustion surface the sibling
tools deliberately close.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Recommended fix (report Option A): the same aggregate-budget loop the siblings
  use, plus a per-title `truncateUtf8` cap matching `RecallMemoryTool`'s summary
  handling. Aggregate-only (Option B) lets one pathological title push an entry
  far past size before the budget trips on the *next* entry.
