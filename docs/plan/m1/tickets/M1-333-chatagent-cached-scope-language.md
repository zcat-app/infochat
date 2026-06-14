---
id: M1-333
title: "ChatAgent: consume cached InboundContext.effectiveLanguage instead of re-querying"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 2
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - DigestWorker's own scope-language query — it runs OUTSIDE an inbound dispatch (no request-scoped InboundContext) and legitimately needs its own SELECT; not touched.
  - The InboundRouter resolution that populates InboundContext.setEffectiveLanguage — unchanged; this ticket consumes the cached value, it does not change how it is produced.
  - The writeAuditRow path's use of DataSource in ChatAgent — kept (the DataSource field is still needed there); only the scope-language SELECT is removed.
acceptance:
  - "ChatAgent.handleTurn reads the scope language from the request-scoped InboundContext.effectiveLanguage() (set once by InboundRouter.onMessage at intake, steps 1.7/4.1) instead of running its own SELECT language FROM scope_preferences. The private readScopeLanguage method and the SELECT_SCOPE_LANGUAGE constant are removed; ChatAgent injects InboundContext."
  - "This restores the router's documented 'ONE scope_preferences SELECT per dispatch' invariant (InboundContext Javadoc / D43): the chat path no longer issues a second identical SELECT and no longer acquires a separate pool connection per chat turn for the language lookup."
  - "The ChatAgentTest seam that overrode readScopeLanguage(...) with a fixed value is translated to setting the language on a test-scoped InboundContext (the same seam InboundRouterChatPersistFailureTest already uses), so test coverage of localized chat replies is preserved without the per-call lookup override."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat (seam translation)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs:
  - D43
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
      added: 36
      removed: 42
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-333: ChatAgent — consume cached scope language

## Context

Deep-review v5.5 (opus-47, `07-module-infochat-provider.md` F1) found that
`ChatAgent.handleTurn` re-queries `scope_preferences` for the scope language that
`InboundRouter` already resolved and cached on the request-scoped
`InboundContext`. **Verified at source 2026-06-14:** `ChatAgent` defines its own
`SELECT_SCOPE_LANGUAGE` (ChatAgent.java:78) and `readScopeLanguage` (line 488)
running it on every turn (called at line 158); `InboundContext.effectiveLanguage`
exists (InboundContext.java:107) and `InboundRouter` populates it via
`setEffectiveLanguage` (InboundRouter.java:497). The chat hand-off
(`dispatchChat`) runs inside the same `@ActivateRequestContext` as `onMessage`,
so the cached value is available.

`ChatAgent` is the largest single offender against the router's "language
resolved at most once per dispatch" invariant: every chat turn opens a fresh pool
connection and runs the identical query a second time. The rest of the command
catalogue already consumes `inboundContext.effectiveLanguage()`.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- A caller invoking `ChatAgent` outside a request scope (none exist today; only
  tests) would see the eager `"en"` default — the same fallback the router uses
  for pre-resolution replies (InboundContext Javadoc), so the contract is
  preserved.
- `SELECT_SCOPE_LANGUAGE` is duplicated verbatim across `InboundRouter`,
  `ChatAgent`, and (a slightly different shape) `DigestWorker`; this ticket
  removes the `ChatAgent` copy. Consolidating the remaining two is a separate
  architecture-lens question, not in scope.
