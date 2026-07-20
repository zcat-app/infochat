---
id: M1-660
title: "doc_embedding read path: arm hnsw.iterative_scan under filter-inside-ANN, in a transaction"
status: done
created: 2026-07-18
last_updated: 2026-07-20
reviews:
  - round: 1
    date: 2026-07-20
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 50
      added: 3173
      removed: 30
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndex.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/HelpLookupTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/help/CommandIntentIndexIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/HelpLookupToolIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  # budget-breach refine 2026-07-20: the redteam-multi remediation added a
  # 16th ChatAgent constructor parameter (CancellationService); these four
  # tests construct ChatAgent manually and each needs one appended null.
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentAuditActorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentProvenanceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatProvenanceTest.java
complexity: medium
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The doc_embedding schema, grants, or partitioning (V60) — unchanged. This
    ticket arms an index-scan GUC on the READ path; it alters no DDL.
  - >-
    The topic corpus (doc_kind='topic'), its builder, or lookupTopic — M1-649.
    This ticket hardens the EXISTING command-intent read path so it stays
    exact once ANY second doc_kind shares the HNSW index; it adds no corpus.
  - >-
    Any delivery/precedence behavior in ChatAgent — M1-665/M1-666. The only
    ChatAgent change here is wrapping the existing lookupIntentForDelivery
    probe in a transaction so its SET LOCAL is not a no-op.
  - >-
    SemanticSearchTool — it already arms iterative_scan
    (enableIterativeScan / armToolConnection). It is the REFERENCE
    implementation this ticket mirrors, not a target.
acceptance:
  - >-
    CommandIntentIndex's pgvector ANN probe arms
    `SET LOCAL hnsw.iterative_scan = strict_order` on the read connection,
    mirroring SemanticSearchTool.enableIterativeScan, so the doc_kind + tier
    filters (`doc_kind = ?`, `target_ref = ANY(?)`) sit INSIDE the
    index-driven scan and recall is exact over the caller-visible set
    regardless of how many rows of OTHER doc_kinds share the HNSW index.
  - >-
    THE SILENT-NO-OP TRAP IS CLOSED — both existing callers run the lookup
    inside an explicit transaction (autocommit off). Today only
    ChatAgent.lookupIntentForDelivery (ChatAgent.java:793) borrows a bare
    autocommit `dataSource.getConnection()`, on which `SET LOCAL` has no
    effect; HelpLookupTool (HelpLookupTool.java:168) is already
    autocommit-off via CancellationService.armToolConnection →
    applyStatementTimeout (CancellationService.java:114) and must stay
    that way. The GUC is transaction-scoped, dies at pool release, and
    never leaks to another borrower. The fix's own failure mode — arming
    a GUC on an autocommit connection — is itself covered by the IT below.
  - >-
    strict_order (not relaxed_order) is used, preserving D19's exact
    distance-ascending order ("same DB state + same message -> same
    set/order").
  - >-
    A retrieval IT proves the fix rather than asserting the SQL string:
    CommandIntentIndexIT.commandRecallSurvivesForeignKindInterleaving embeds
    N synthetic rows of a second doc_kind whose vectors crowd a command's
    default ef_search window, then asserts lookupCommand still returns the
    correct command. The test FAILS on main (no arming) and PASSES with it.
  - >-
    A negative IT pins the no-op trap: a variant asserting the arming has
    effect (e.g. a probe that under-recalls without a transaction and
    recalls with one) so a future refactor that drops the transaction wrapper
    reds the build rather than silently regressing recall.
  - >-
    Redteam-multi remediation (2026-07-20): ChatAgent.lookupIntentForDelivery
    applies CancellationService.applyStatementTimeout to its bare pool borrow
    before the probe, so the armed iterative scan is time-bounded on the
    delivery path exactly as on the tool path (and the borrow is
    autocommit-off caller-side, satisfying item 2 on that caller directly).
    Per the existing bare-borrow read-site pattern the one-line wiring
    carries no dedicated per-site test; CancellationServiceTest covers the
    timeout semantics.
  - mvn verify from the repo root is green
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/help/CommandIntentIndexIT.java
      — new methods in the EXISTING IT class:
      commandRecallSurvivesForeignKindInterleaving (+ the no-op-trap variant)
  modifies:
    - path: infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/HelpLookupToolIT.java
      change: >-
        Only if the transaction wrapper changes HelpLookupTool's method
        signature/wiring; otherwise untouched.
    - path: infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
      change: >-
        Only if the transaction wrapper changes ChatAgent's lookup wiring;
        otherwise untouched.
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs:
  - D19
  - D54
clarity_check:
  date: 2026-07-20
  verdict: PASS
  warnings:
    - >-
      self-check: ticket claimed BOTH callers borrow autocommit connections;
      in fact HelpLookupTool is already autocommit-off via
      CancellationService.armToolConnection (setAutoCommit(false) inside
      applyStatementTimeout, CancellationService.java:114). The no-op trap
      is live only on ChatAgent.lookupIntentForDelivery's bare connection.
      Acceptance item 2 + §The autocommit trap prose corrected inline;
      required end state and the encapsulated fix are unchanged.
---

# M1-660: doc_embedding read path — arm hnsw.iterative_scan under filter-inside-ANN

## Context

`CommandIntentIndex.lookupCommand` runs a pgvector cosine ANN probe over
`doc_embedding` with the isolation filters (`doc_kind = ?`,
`target_ref = ANY(?)`) in the WHERE. It does NOT arm
`hnsw.iterative_scan`. `SemanticSearchTool` runs the structurally identical
filter-inside-an-ANN-query shape over `post_embedding` and DOES arm it
(`enableIterativeScan`, `SET LOCAL hnsw.iterative_scan = strict_order`,
justified there against redteam M1-589's recall-shrinkage leak class).

Today the asymmetry is benign: ~41 command-intent rows means the planner
seq-scans / a default ef_search window covers the whole corpus, so the
post-filter drops nothing. It becomes a **live, silent under-recall defect
the moment a second doc_kind shares the HNSW index** — which is exactly what
M1-649 does when it embeds `doc_kind='topic'` into the same table. A command
probe's ef_search window then fills with `topic` rows the `doc_kind` filter
discards, and without iterative scan the probe stops before backfilling
enough surviving command rows. The failure arrives with the DATA, not with a
code change — the worst kind to catch in review.

## Provenance (reconstructed ticket)

This ticket was originally filed as an out-of-model finding during the
M1-648 r2 redteam ("SURVIVED → filed as M1-660, parked"). Its file lived at
`.claude/worktrees/M1-648/.scratch/M1-660-doc-corpus-iterative-scan.md` and
was **lost when the M1-648 worktree was removed** — both `.claude/worktrees/`
and `.scratch/` are gitignored, so git never stored the file and neither
reflog nor `fsck --lost-found` can recover it (verified 2026-07-20). This
body is reconstructed from the M1-648 handoff plus live-code verification.

## The autocommit trap

Arming is not a one-line `stmt.execute("SET LOCAL …")`. `SET LOCAL` is
transaction-scoped; on an autocommit connection each statement is its own
transaction, so the GUC expires before the query runs — a silent no-op (and
Postgres may warn "SET LOCAL can only be used in transaction blocks").
ChatAgent.lookupIntentForDelivery borrows a bare autocommit
`dataSource.getConnection()`; HelpLookupTool's borrow is already flipped to
autocommit-off by `CancellationService.armToolConnection`
(`applyStatementTimeout`, CancellationService.java:114). The fix
must therefore open an explicit transaction around the probe (setAutoCommit
false → SET LOCAL → query → commit/rollback), mirroring how
`SemanticSearchTool.armToolConnection` opens one that its `SET LOCAL`
statements join. Encapsulate this in the read path so both callers get it
without hand-rolling a transaction each.

## Ordering

Prerequisite for M1-649. If the topic corpus lands first, command retrieval
silently under-recalls in production between the two merges. M1-660 must
merge before M1-649.

## Notes

**Not security_relevant, stated honestly.** The tier filter
(`target_ref = ANY(visible)`) is the boundary; under-recall here DROPS
visible commands (fail-closed) — it does not surface invisible ones, so no
existence-oracle leak. The M1-589 lineage (recall shrinkage on the POST
corpus, where dropped rows could shift RRF and leak unsubscribed-content
density) does not transfer to the doc corpus, which returns a single
tier-filtered name. If the reviewer disagrees, escalate to a redteam gate —
cheap insurance, not a blocker.

**Reference implementation.** `SemanticSearchTool.enableIterativeScan` +
`armToolConnection` — copy the pattern, including strict_order and the
pool-release safety.

## Redteam-multi (2026-07-20)

Multi-auditor audit of the pre-review diff (evidence:
`docs/plan/m1/redteam-multi/M1-660-2026-07-20/`): opencode and codex CLEAN;
claude flagged one low-severity DOS asymmetry — the delivery-trigger caller
(`ChatAgent.lookupIntentForDelivery`) borrows a bare connection with no
`statement_timeout`, and the armed strict_order scan it now runs has a
larger worst case (up to `hnsw.max_scan_tuples` when no row clears the
threshold) while the tool caller keeps its `armToolConnection` time cap.
Remediation folded into this ticket at the user's direction: the delivery
path now applies `CancellationService.applyStatementTimeout` to its borrow
— the same profile-driven bound the tool path gets, which also flips
autocommit off caller-side, satisfying acceptance item 2's explicit-
transaction requirement on that caller directly. The wiring follows the
existing bare-borrow read-site pattern; per that pattern's precedent the
per-site wiring line carries no dedicated test (`CancellationServiceTest`
covers the timeout semantics). Post-fix re-audit recorded in the `-r2`
evidence directory.
