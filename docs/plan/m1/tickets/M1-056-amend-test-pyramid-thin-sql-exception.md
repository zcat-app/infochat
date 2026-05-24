---
id: M1-056
title: Amend test-pyramid — carve out thin-SQL handler exception
status: pending
created: 2026-05-24
last_updated: 2026-05-24
blocked_by: []
files_budget: 8
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope: []
acceptance:
  - "docs/process/test-pyramid.md §Handler unit tests gains a new subsection titled `### Thin-SQL handler exception` carving out the following: when the handler has ≤1 non-DB collaborator AND ≥2 DB statements that depend on real-DB semantics (triggers, FOR UPDATE locking, RETURNING clauses, PK / UNIQUE / FK / CHECK constraints, or partition-routing), the handler-tier test MAY use `@QuarkusTest` + Quarkus DevServices Postgres + direct `handler.handle()` invocation in place of plain-JUnit + stubbed-JDBC. The router-leak prohibition (no `adapter.deliverDm(...)`; no path through `InboundRouter.onMessage`) STILL APPLIES — the exception covers real-DB vs. stubbed-DB only, not real-router vs. direct-handler. The new subsection cites `GrantAdminCommandHandlerTest` (M1-044c) as the canonical example."
  - "The new subsection states the rationale verbatim: thin-SQL handlers have no rich orchestration logic to assert in isolation, so stubbing the JDBC chain reduces tests to whitebox tautologies (asserting that the handler issued the exact SQL string the test stubbed). The handler's behavioral contract IS the DB interaction (lock acquisition, trigger-driven state, constraint enforcement); the test must observe the DB to verify the contract. The orthodox alternative (stubbed JDBC + separate migration-test layer) is rejected because no migration-test layer exists in the project and building one would be larger-scope than the exception."
  - "The new subsection includes a §Choosing-the-layer addition: a 2-line decision rule appended to the existing §Choosing the layer section — 'Handler is a thin SQL shell (≤1 non-DB collaborator, ≥2 real-DB statements) → handler unit test, MAY use the Thin-SQL exception shape. Otherwise → handler unit test, plain JUnit + stubbed JDBC.' The decision rule cites the exception subsection by anchor."
  - "M1-044c sibling refactor decision is EXPLICITLY recorded in this ticket's §Notes section. Two binary outcomes — (A) the 6 existing M1-044c sibling tests (GrantAdminCommandHandlerTest, RevokeAdminCommandHandlerTest, BanCommandHandlerTest, UnbanCommandHandlerTest, InviteCommandHandlerTest, VouchCommandHandlerTest) qualify under the new exception criteria and are codified examples — no refactor required; OR (B) one or more siblings do NOT qualify, and a follow-up refactor ticket M1-XXX is filed as a skeleton (deferred_on: M1-XXX added to that sibling's eventual remediation context) BEFORE this ticket can close. The decision is made by inspecting each sibling against the criteria; the result is recorded as a §Notes bullet per sibling (`GrantAdminCommandHandlerTest: qualifies — ≥2 real-DB statements (...trigger interaction, FOR UPDATE on actor row, audit_log INSERT)` etc.)"
  - "If outcome (A) is recorded for all 6: each of the 6 sibling test files gains a single class-level Javadoc tag of the form `@implNote Canonical thin-SQL handler exception per `docs/process/test-pyramid.md` §Thin-SQL handler exception.` immediately above the class declaration, so the canonical-example status is self-documenting in source. If outcome (B) is recorded for any sibling: the Javadoc is omitted from that sibling and the follow-up ticket carries the refactor."
  - "M1-052 (the spec_amend_parent) reopens automatically per `/m1-tick reopen` — the amendment ticket itself does NOT touch M1-052's file. Reopening + refining M1-052 to cite the amended doc is a separate `/m1-tick reopen M1-052` invocation after this ticket lands."
  - "`mvn -B clean verify` from the repo root exits 0 — pure-doc change is no-op for tests, but the suite must remain green; if outcome (A) Javadocs are added, those edits must not break compilation."
test_plan:
  adds: []
  modifies:
    - docs/process/test-pyramid.md
  preserves:
    - all tests currently green on main
    - every M1-044c sibling test continues to pass without behavioral change
    - every M1-049 plain-JUnit handler-unit test (AddSourceCommandHandlerTest, SummaryCommandHandlerTest) continues to pass without behavioral change
spec_refs:
  - docs/process/test-pyramid.md §Handler unit tests
  - docs/process/test-pyramid.md §Choosing the layer
decision_refs: []
spec_amend_for: docs/process/test-pyramid.md:§Handler unit tests
spec_amend_parent: M1-052
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-056: Amend test-pyramid — carve out thin-SQL handler exception

## Context

The current `docs/process/test-pyramid.md` §Handler unit tests rule "MUST NOT use a real DataSource connection" is contradicted by the codebase: six M1-044c sibling tests (`GrantAdminCommandHandlerTest`, `RevokeAdminCommandHandlerTest`, `BanCommandHandlerTest`, `UnbanCommandHandlerTest`, `InviteCommandHandlerTest`, `VouchCommandHandlerTest`) ship as `@QuarkusTest` + Quarkus DevServices Postgres + direct `handler.handle()`. The contradiction was developer-surfaced during M1-052's `/m1-tick start` when acceptance item 3 cited both "plain JUnit per the M1-049 test pyramid (no @QuarkusTest)" AND "Testcontainers Postgres ... (M1-044c pattern)" — mutually exclusive references whose resolution exposed a structural spec gap rather than a ticket-wording bug.

The codebase has organically grown two handler-test shapes reflecting two genuine handler types:

1. **Collaborator-orchestrator handlers** (rich non-DB collaborators — `UrlProbe`, `EligiblePostQuery`, `ClusterTraversal`, `SummaryProseGenerator`, etc.). Plain JUnit + stubbed JDBC fits — the interesting logic is the orchestration; DB is incidental. Examples: `AddSourceCommandHandlerTest`, `SummaryCommandHandlerTest`.
2. **Thin-SQL handlers** (handler IS the SQL — `SELECT ... FOR UPDATE`, INSERT with snapshot columns, DELETE with affected-row check, trigger-driven state). Plain-JUnit + stubbed JDBC reduces tests to tautologies. `@QuarkusTest` + real DB observes the actual behavioral contract. Examples: the 6 M1-044c siblings; upcoming `/save`, `/saved`, `/unsave` from M1-052 and the source-management handlers from M1-053.

The test-pyramid doc was written assuming all handlers fit shape (1). M1-044c shipped 6 tests of shape (2) without amending the doc. M1-052 is the first ticket to make the contradiction undeniable by citing both references in one acceptance item. The escalation root cause: a structural gap, not a ticket-authoring slip.

This ticket lands the amendment so future DB-centric handler tickets (M1-053, M1-054, M1-055a, and beyond) have an accurate reference and don't recur the same escalation. M1-052 itself is `status: deferred`, `deferred_on: M1-056`, `deferred_reason: spec-amend`. After this ticket lands, `/m1-tick reopen M1-052` brings it back, refining its acceptance to cite the codified exception.

## Acceptance

The YAML `acceptance:` list above is binding. Highlights:

- **Item 1** lands the new `### Thin-SQL handler exception` subsection with explicit criteria (≤1 non-DB collaborator AND ≥2 real-DB-dependent statements) and the precise scope of the exception (real-DB-vs-stub only; router-leak prohibition unchanged).
- **Item 2** captures the rationale verbatim in the doc itself — future authors need the WHY, not just the WHAT.
- **Item 3** updates §Choosing the layer with a 2-line decision rule pointing at the exception.
- **Item 4** forces the M1-044c sibling refactor decision (scenario A: no refactor / scenario B: refactor) to be made explicitly in this ticket's §Notes, with per-sibling reasoning. Without this, the amendment legitimizes the siblings by implication only — leaving the door open for a future "wait, do those actually qualify?" debate.
- **Item 5** is the conditional Javadoc tagging on the 6 siblings (only if scenario A wins for all).
- **Item 6** scopes the M1-052 reopen out of this ticket — that's a separate `/m1-tick reopen` invocation after this lands.

## Out-of-scope

[Author MUST fill in this section before `/m1-tick start M1-056` — the empty `out_of_scope: []` in YAML will clarity-FAIL otherwise per the m1-tick spec-amend procedure. Suggested entries derived from the discussion that produced this ticket:

- Refactoring M1-044c sibling tests to plain-JUnit + stubbed JDBC — that path is rejected per the exception rationale; if the reviewer disagrees, scenario B in item 4 captures the disagreement and files a follow-up.
- Amending any other process doc beyond `docs/process/test-pyramid.md`.
- Amending the §Router unit tests or §Integration tests sections — the exception is strictly handler-tier.
- Modifying the collaborator-orchestrator pattern (plain JUnit + stubs) for `AddSourceCommandHandlerTest` / `SummaryCommandHandlerTest` — the exception is additive.
- Introducing a migration-test layer to cover trigger/constraint behavior independently — would be larger-scope than the exception; rejected per item 2 rationale.
- Modifying any production code (CommandHandler implementations, V1..V13 migrations, BundleLoader, etc.) — this ticket is pure-doc plus optional sibling-Javadoc.
- Touching `M1-052` ticket frontmatter or acceptance — that's a separate `/m1-tick reopen` flow.

The author edits the YAML `out_of_scope` to incorporate the above (or replacement entries) before invoking `/m1-tick start M1-056`.]

## Notes

- **Spec anchor**: `docs/process/test-pyramid.md` §Handler unit tests (current MUST-NOT-real-DataSource rule). The new subsection inserts AFTER the existing MUST NOT bullet list, as a carve-out from it.
- **Canonical example**: `infochat-provider/src/test/java/app/zcat/infochat/provider/command/GrantAdminCommandHandlerTest.java`. The exception subsection cites this file by path.
- **M1-044c sibling enumeration** (for acceptance item 4 review):
  - `GrantAdminCommandHandlerTest`
  - `RevokeAdminCommandHandlerTest`
  - `BanCommandHandlerTest`
  - `UnbanCommandHandlerTest`
  - `InviteCommandHandlerTest`
  - `VouchCommandHandlerTest`
  Each is independently inspected against the exception criteria during implementation; per-sibling outcome recorded as a §Notes bullet in the diff. [Author fills in actual per-sibling assessment during implementation; the YAML acceptance pins the requirement to do so.]
- **M1-052 reopen path**: after this ticket lands, `/m1-tick reopen M1-052` revives the deferred ticket. The M1-052 refinement cites `docs/process/test-pyramid.md` §Thin-SQL handler exception as the binding test-pattern reference, removing the M1-049-vs-M1-044c ambiguity that triggered the escalation.
- **CLAUDE.md "doc edits bypass ticket flow" rule**: this ticket intentionally goes through the ticket flow despite being pure-doc. Justification: the amendment is a STRUCTURAL change to a MUST-NOT rule that governs all future handler tests, not routine doc maintenance. The bypass rule was designed for typo fixes, link updates, and clarifications. Applying it to a rule change would skip the value-add of clarity-check + reviewer gates on a consequential change.
- **No `redteam` recommended**: pure-doc amendment, `security_relevant: false`, no threat-model surface touched.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-056-amend-test-pyramid-thin-sql-exception.md
```

Expect lint to flag `out_of_scope` as empty until §Out-of-scope above is mirrored into the YAML field. That's the intentional forcing function per the m1-tick spec-amend procedure.
