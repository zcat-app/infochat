---
id: M1-275
title: "DB hygiene: SET LOCAL timeouts, per-dispatch conn reuse"
status: done
created: 2026-06-09
last_updated: 2026-06-10
revisions:
  - date: 2026-06-10
    reason: budget-breach refine (SET LOCAL breaks 7 unauthorized statement_timeout assertions; seam-signature change breaks InboundRouterChatModeIT direct calls)
    snapshot: |
      Pre-refine files_budget: 17. Pre-refine files_scope had no summary/ or
      digest/ test entries; test_plan.modifies listed only chat, command,
      messaging test dirs. Pre-refine Notes authorized-change list covered
      only CancellationServiceTest, ExportDataCollectorTest, the eight
      messaging seam-subclass tests, and the new acquisition-count test.
      Pre-refine acceptance item 4 verbatim:
        "Group inbound dispatch resolves user, groupId, membership,
         confirm-state, and session persistence through a shared
         per-dispatch context instead of 4–5 independent pool acquisitions:
         the pre-LLM read phase borrows one connection, and the context
         carries groupId forward instead of re-looking it up. A named test
         (counting datasource/pool spy) asserts the acquisition count for a
         group dispatch drops to the designed number and never spans the
         LLM call."
      All other frontmatter fields unchanged by this refine.
  - date: 2026-06-10
    reason: clarity-fail refine (unenumerated test modifications + unnamed tests on items 2 and 4)
    snapshot: |
      Pre-refine test_plan listed only directories:
        adds:      [infochat-provider/src/test/java/app/zcat/infochat/provider/messaging]
        modifies:  [infochat-provider/src/test/java/app/zcat/infochat/provider/messaging]
      Pre-refine acceptance items 2 and 4 verbatim:
        2: "The timeout value reaching the SET statement is a validated integer
            (no raw string concatenation of caller input), and the
            pg_cancel_backend return value is checked and logged on the
            cancellation path."
        4: "ExportDataCollector queries run under the standard statement timeout."
      Pre-refine files_budget: 14. Pre-refine Notes pointed the counting-
      DataSource search at src/test/.../testsupport (actual existing double is
      CountingRecordingDataSource in src/test/.../provider/chat/tool/).
      All other frontmatter fields unchanged by the refine.
escalations:
  - date: 2026-06-10
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (developer-detected during design, before any code change).
      Acceptance item 1 (SET LOCAL) breaks 7 pre-existing green tests that
      assert sql.contains("SET statement_timeout") — a string "SET LOCAL
      statement_timeout = N" does not contain: SearchPostsToolTest,
      GetPostToolTest, ListSavesToolTest, GetReferencesToolTest,
      RecallMemoryToolTest (chat/tool), EligiblePostQueryStatementTimeoutTest
      (summary), DigestPostCollectorIT (digest). The summary/ and digest/
      test directories are outside files_scope; none of the 7 files are in
      the Notes' authorized-modification list. Additionally, the seam-
      signature change anticipated by the ticket also breaks
      InboundRouterChatModeIT (direct 2-arg router.lookupGroupId call at
      line 178), a 9th unauthorized file. Authorized fix would push
      files-touched to ~23 > files_budget 17.
  - date: 2026-06-10
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED: FAIL
      test_plan.modifies lists infochat-provider/src/test/java/app/zcat/infochat/provider/messaging,
      meaning pre-existing test files in that directory will be modified. The ticket body does not
      list which specific test classes or methods will be changed, nor does it state what their new
      expected behavior will be. The Notes section says "identical dispatch decisions via existing
      green tests" but that sentence is not an explicit authorization — it asserts the tests will
      still pass without naming which tests are touched or what structural changes (e.g., adding a
      datasource spy, changing test setup) will be applied to them.
blocked_by: []
files_budget: 25
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/CancellationService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ExportDataCollector.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryStatementTimeoutTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorIT.java
complexity: medium
risk: high
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - Holding one connection across the LLM call — explicitly forbidden by the report ("would recreate H3's defect"); the dispatch context splits at the LLM boundary.
  - DigestPostCollector timeout (M1-263 owns the digest slice).
  - The dispatch pipeline's permission/ban/confirm logic — only how many connections it borrows changes, not what it decides.
  - Connection-pool sizing configuration.
acceptance:
  - "CancellationService applies statement_timeout transaction-locally (SET LOCAL inside a tx, or an unconditional reset on release): a named test asserts a connection borrowed after a timeout-bearing call observes the pool's default statement_timeout."
  - "The timeout value reaching the SET statement is a validated positive integer (range-checked via Integer/long parse before formatting; no raw string interpolation of caller-supplied text): a named CancellationServiceTest method pins the validation."
  - "The pg_cancel_backend boolean result is read on the cancellation path: false logs WARN naming the pid, true keeps INFO. A named CancellationServiceTest method (stub connection returning false) asserts the WARN-on-false behavior."
  - "Group inbound dispatch resolves user, groupId, and membership through a shared per-dispatch context instead of 4–5 independent pool acquisitions: the pre-LLM read phase borrows exactly ONE router-owned connection, and the dispatch carries groupId forward instead of re-looking it up. (Confirm-state is already in-memory — zero acquisitions; session persistence stays post-LLM inside ChatAgent per the LLM-boundary split.) A named test (counting datasource/pool spy) asserts the router's own acquisition count for a group chat dispatch is exactly 1 and that zero connections are open when the LLM-dispatch boundary is crossed."
  - "ExportDataCollector queries run under the standard statement timeout: the export connection applies it (SET LOCAL statement_timeout or setQueryTimeout) before the first collection query. A named ExportDataCollectorTest method asserts the timeout is applied before any query runs."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryStatementTimeoutTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorIT.java
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-10
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 27
      added: 1135
      removed: 303
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-10
  verdict: WARN
  warnings:
    - "Acceptance item 4: 'drops to the designed number' does not name the target acquisition count. State the expected count explicitly (e.g. '2 per group dispatch: 1 pre-LLM borrow + 1 post-LLM borrow; 0 held during the LLM call')."
  blockers: []
---

# M1-275: DB hygiene: SET LOCAL timeouts, per-dispatch conn reuse

## Context

Deep-review v4 verified mediums **M-P3**, **M-P4**, **M-P13** (export slice)
plus two deepseek lows on the same surface
(`deep-code-review/v4/UNIFIED-REPORT.md` §2/§3; sources
`deep-code-review/v4/fable5/07-module-infochat-provider.md#F8`,
`deep-code-review/v4/deepseek/report.md` #F2/#F4/#F6,
`deep-code-review/v4/mimo/report.md` HIGH-001/MED-013,
`deep-code-review/v4/opus-47/07-module-infochat-provider.md#F4`,
`deep-code-review/v4/gpt-55/report.md` L-04):

- **M-P3:** `CancellationService.applyStatementTimeout` runs a session-level
  `SET` (not `SET LOCAL`, never reset) on pool connections — subsequent
  borrowers inherit the timeout.
- **M-P4** (4 independent runs): each group inbound dispatch opens 4–5
  separate pool connections (`lookupUser`, `lookupGroupId`,
  `ensureGroupMembership`, confirm-state, session persist). gpt-55's
  "carry groupId forward in a dispatch context" suggestion is folded in.
- **M-P13/L:** `ExportDataCollector` lacks statement-timeout hygiene; the
  `SET statement_timeout` string-concat precedent and the ignored
  `pg_cancel_backend` return ride along (same files).

## Acceptance

See frontmatter. The hard boundary is in out_of_scope: the dispatch context
must split at the LLM boundary — one connection for the pre-LLM read phase,
nothing held during the LLM call, fresh borrow after.

## Out-of-scope

See frontmatter.

## Notes

- `risk: high` (hence round_cap 3 and the commit-time verify re-run): this
  refactors the provider's hottest path. The acceptance deliberately pins
  observable properties (acquisition count, no-span-LLM, identical dispatch
  decisions via existing green tests) rather than a specific context shape.
- For the acquisition-count test, an existing counting double is
  `CountingRecordingDataSource` in
  infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/
  (used by SearchPostsToolTest) — reuse or extend it before inventing a
  new one.
- Authorized test changes, enumerated per file (test_plan.modifies stays
  at the directory granularity of files_scope; this list is the explicit
  authorization):
  - `CancellationServiceTest` (chat): add named tests — transaction-local
    timeout reset, validated-integer pin, pg_cancel_backend WARN-on-false.
    Existing methods update mechanically to the new contract (user-approved
    2026-06-10): the three `contains("SET statement_timeout")` assertions
    move to the SET LOCAL form, and the private JDBC proxy stubs learn
    `setAutoCommit` plus the executeQuery shape of the boolean-reading
    pg_cancel_backend. Test intent and coverage unchanged; no method is
    deleted or weakened.
  - `ExportDataCollectorTest` (command): add the named timeout-application
    test. The two existing methods that construct `ExportDataCollector`
    manually additionally wire the collector's new `cancellationService`
    field (user-approved 2026-06-10); all other existing methods
    unchanged.
  - `RetryCommandHandlerTest` (command): the stub connection proxy learns
    `setAutoCommit` (one case arm; user-approved 2026-06-10) so the real
    `applyStatementTimeout` keeps running against it. Assertions and test
    intent unchanged.
  - Eight messaging tests subclass the router and override the
    package-private `lookupUser`/`lookupGroupId` seams
    (InboundRouter.java:733/:930): `InboundRouterBanSnapshotTest`,
    `InboundRouterCommandCapTest`, `InboundRouterConfirmCancelTest`,
    `InboundRouterContactIdRedactionTest`, `InboundRouterIntakeOrderingTest`,
    `InboundRouterNormalizeTest`, `InboundRouterProbationOrderingTest`,
    `RouterNoDoubleSendTest`. If the per-dispatch context changes those seam
    signatures, the overrides update mechanically; their assertions and test
    intent must not change. If the chosen design preserves the seam
    signatures, these files legitimately end up untouched — the list
    authorizes, it does not mandate.
  - New test (test_plan.adds, messaging): `InboundRouterAcquisitionCountTest`
    — counting DataSource spy asserting the group-dispatch acquisition count
    and that no connection spans the LLM call.
  - Statement-timeout assertion sweep (SET → SET LOCAL): `SearchPostsToolTest`,
    `GetPostToolTest`, `ListSavesToolTest`, `GetReferencesToolTest`,
    `RecallMemoryToolTest` (chat/tool), `EligiblePostQueryStatementTimeoutTest`
    (summary), `DigestPostCollectorIT` (digest) each carry one
    `anyMatch(contains("SET statement_timeout"))` assertion that the
    transaction-local form no longer satisfies. The authorized change per
    file is the mechanical assertion-string update to the SET LOCAL form
    (the assertion then pins the new transaction-local behavior). Test
    intent and all other test content unchanged.
  - `InboundRouterChatModeIT` (messaging): calls the `lookupUser`/
    `lookupGroupId` seams directly. If the chosen design changes the seam
    signatures, those direct call sites update mechanically — same
    authorization shape as the eight subclass tests; assertions and test
    intent must not change.
- PostgreSQL does not allow bind parameters in SET; "validated integer"
  means `Integer.parseInt`/range-check then format — not a prepared
  statement.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-275-*.md
```
