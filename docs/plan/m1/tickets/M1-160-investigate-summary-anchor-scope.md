---
id: M1-160
title: "[INVESTIGATE] summary_anchor scope_kind discriminator"
status: done
created: 2026-06-02
last_updated: 2026-06-06
blocked_by: []
files_budget: 23
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-provider/src/test/java/app/zcat/infochat/provider
  - docs/design/02-schema.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - the other scope_kind-carrying tables (V7__joins_post.sql, V15__saved_post.sql, V18__chat_tables.sql) — their schema and queries are unchanged
  - changing how scope_id is derived (InboundRouter.resolveChatScopeId semantics — DM → actorId, group → groups.id); call-site signature updates are in scope on the add-branch, derivation logic is not
  - editing the applied V19__summary_anchor.sql in place (successor migration only)
  - chat_memory / chat_session pruning logic beyond carrying the discriminator into summary_anchor queries
  - implementing a migration before the decision entry is recorded in the design note
acceptance:
  - "docs/design/02-schema.md gains a '### summary_anchor scope_kind decision' subsection recording the verdict (add-scope_kind | confirm-safe) with rationale grounded in how both scope_id populations are generated: DM scope_id is the actor's user.id (InboundRouter.java:817 — ScopeRef.Dm → actorId), group scope_id is groups.id (lookupGroupId); the rationale must state whether the two UUID populations can structurally collide (id generation audited at their INSERT sites) and whether any existing summary_anchor query's correctness depends on the discriminator"
  - "If the verdict is add-scope_kind: a successor migration (version assigned at start) adds scope_kind to summary_anchor mirroring the V18__chat_tables.sql:8 precedent (TEXT NOT NULL CHECK (scope_kind IN ('dm','group'))), the two partial unique indexes (V19__summary_anchor.sql:23-30) widen to include it, SummaryAnchorRepository and the raw-SQL touchers (ForgetCommandHandler, ForgetPurgeService, ExportDataCollector, ChatMemoryPruner) carry the discriminator, and a test asserts a DM anchor and a group anchor with the same scope_id coexist without collision"
  - "If the verdict is confirm-safe: the design-note subsection records the structural guarantee that makes collision impossible (id-generation disjointness at the INSERT sites) and explicitly accepts the convention drift vs V7/V15/V18; no migration lands"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Per-scope state
decision_refs:
  - D19
  - D36
reviews:
  - round: 1
    date: 2026-06-06
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 23
      added: 314
      removed: 152
revisions:
  - date: 2026-06-06
    reason: budget-breach refine (add-scope_kind repository signature change ripples into 12 test files — recording stubs, anonymous clear() overrides in 5 InboundRouter unit tests, raw summary_anchor INSERTs hitting the new NOT NULL column; 21 files touched + coexistence test to come; the 2026-06-05 sweep covered main-code call sites but not test doubles)
    snapshot:
      status: escalated
      files_budget_at_snapshot: 12
  - date: 2026-06-05
    reason: pre-start clarity hardening (M1-162 clarity-fail precedent — prose-verb acceptance with no pinned artifact; decision target 'ticket Notes / a design note' was unpinned; files_budget 4 did not cover the add-branch ripple; files_scope omitted the design-note artifact)
    snapshot:
      status: pending
      files_budget_at_snapshot: 4
      acceptance_at_snapshot:
        - "Investigate whether DM and group summary anchors can collide on scope_id given the current keying (every other per-(user, scope) table carries a scope_kind discriminator that summary_anchor omits — V19__summary_anchor.sql:5-30)"
        - "Record the decision (add scope_kind vs confirm-safe) with rationale in the ticket Notes / a design note"
        - "If the decision is to add scope_kind: a successor migration adds it and a test asserts a DM anchor and a group anchor with the same scope_id no longer collide"
        - "mvn -B clean verify from the repo root exits 0"
      out_of_scope_at_snapshot:
        - implementing a migration before the collision question is decided
escalations:
  - date: 2026-06-06
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (budget-breach: add-scope_kind verdict ripples the repository
      signature change into 12 test files — recording stubs, anonymous
      clear() overrides in 5 InboundRouter tests, and raw summary_anchor
      INSERTs that now need the NOT NULL scope_kind column; 21 files
      touched vs files_budget: 12, before the new coexistence test lands)
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-06
    verdict: CLEAN
    base: 66929dc
    head: working tree @ m1/M1-160-investigate-summaryanchor-scop (pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-160-2026-06-06.md
    out_of_model_count: 0
    note: |
      Pre-commit audit of the V37 scope_kind isolation fix (run between
      review APPROVE and commit so the audit artifacts fold into the
      ticket commit). Adversary verified write-path/backfill consistency,
      read/clear/key parity incl. the in-memory RetryKey, no stale call
      sites, and the DB CHECK/index backstop; residuals (backfill
      misclassification ~2^-122, retryCounts growth, String scopeKind
      inside the trust boundary) considered and rejected as findings.
clarity_check:
  date: 2026-06-06
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: body frames a scope-collision as 'an isolation breach, not just a convention drift'; if the verdict is add-scope_kind and a migration lands, security_relevant: true would ensure /redteam reviews the isolation fix — defensible as false for the investigation phase, revisit if the add-branch is chosen"
  blockers: []
---

# M1-160: [INVESTIGATE] summary_anchor scope_kind discriminator

## Context

`summary_anchor` (V19) keys personal anchors on `(user_id, scope_id,
command_kind)` and digest anchors on `(scope_id, command_kind)` — both
WITHOUT the `scope_kind` discriminator that every other per-(user, scope)
table carries (`V7__joins_post.sql`, `V15__saved_post.sql`,
`V18__chat_tables.sql:8` — `TEXT NOT NULL CHECK (scope_kind IN
('dm','group'))`, part of the V18 PRIMARY KEY). Grounded 2026-06-05:

- DM scope_id = the actor's own `user.id`
  (`InboundRouter.java:817` — `case ScopeRef.Dm ignored -> Optional.of(actorId)`)
- Group scope_id = `groups.id` (`InboundRouter.lookupGroupId`)

The collision question is therefore: can a `user.id` and a `groups.id`
ever hold the same UUID value (audit the id-generation at both INSERT
sites), and even if not, does any summary_anchor read/write path's
correctness or isolation depend on the discriminator? The handout's
verdict is **FIX (verify first)** — decide before adding the column.

`summary_anchor` raw SQL lives in `SummaryAnchorRepository` (UPSERT/read/
clear keyed `(user_id, scope_id, command_kind)`), plus raw-SQL touchers
`ForgetCommandHandler`, `ForgetPurgeService`, `ExportDataCollector`,
`ChatMemoryPruner`. The add-branch ripples through those callers — hence
`files_budget: 12` (was 4), widened to 23 on 2026-06-06: the repository
signature change also ripples through 12 test files (stubs, anonymous
overrides, raw INSERTs hitting the new NOT NULL column).

## Contract (inlined — the ticket is self-contained)

- **Per-(user, scope) isolation** (project invariant, CLAUDE.md §Key
  conventions): state, memory, and saves never leak across users or
  between DM and group. If DM and group anchors can share a key, a
  `/retry` in one scope could replay the other scope's anchor — an
  isolation breach, not just a convention drift.
- **spec schema.md §Per-scope state — Summary anchor:** captures the
  last summary-producing command's deterministic payload (command name,
  arg hash, ordered post UIDs, cluster map, `generated_at`,
  `command_kind ∈ {personal, digest}`); read by `/retry` to replay
  deterministic selection (D19, D36); cleared by any non-`/retry` input
  from the same `(user, scope)`; pruned under Invariant 9 like
  `chat_memory`.

## Acceptance

See frontmatter. Decide first (acceptance item 1 is the artifact);
implement the migration only on the add-scope_kind verdict.

## Out-of-scope

See frontmatter. Migration version assigned at start (only if needed) —
re-sweep in-flight worktrees for unmerged V-files at assignment time.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-SUMMARYANCHOR-SCOPE;
  `opus-47-full-handout.md` §F-MAINT-39.
- Line references grounded 2026-06-05 against main @ f432289.
