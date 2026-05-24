---
id: M1-056
title: Amend test-pyramid — restructure §Handler unit tests for two-shape reality
status: pending
created: 2026-05-24
last_updated: 2026-05-24
blocked_by: []
files_budget: 8
files_scope:
  - docs/process/test-pyramid.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GrantAdminCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnbanCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/VouchCommandHandlerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Refactoring any of the 6 M1-044c sibling tests (Grant/Revoke/Ban/Unban/Invite/VouchCommandHandlerTest) to plain-JUnit + stubbed JDBC — that path is rejected per acceptance item 3's rationale; if any sibling fails the criteria in acceptance item 5, a follow-up refactor ticket is filed (per acceptance item 5 scenario B) and the refactor itself happens there, NOT in this ticket"
  - "Amending any process doc other than `docs/process/test-pyramid.md` — the scope is one section of one doc"
  - "Amending the §Router unit tests or §Integration tests sections of test-pyramid.md — the restructure is strictly handler-tier; the router/IT sections are correct as-written"
  - "Replacing the collaborator-orchestrator (Shape A) pattern for `AddSourceCommandHandlerTest` / `SummaryCommandHandlerTest` / `HelpCommandHandlerTest` — the restructure preserves Shape A unchanged; Shape B is added alongside as an equally-legitimate alternative, not a replacement"
  - "Renaming test-pyramid.md (e.g. to test-layers.md or handler-test-shapes.md) — the doc name is a separate concern; if a rename is warranted, file as a follow-up after this ticket lands"
  - "Introducing a migration-test layer to cover trigger/constraint behavior independently of handler tests — would be larger-scope than the restructure; explicitly rejected per acceptance item 3 rationale (the rejection is itself recorded in the new doc text)"
  - "Modifying any production source (CommandHandler implementations, V1..V13 migrations, BundleLoader, InboundRouter, etc.) — this ticket is pure-doc plus optional sibling-test Javadoc tags"
  - "Touching `M1-052` ticket frontmatter or acceptance — M1-052's reopen + refine is a separate `/m1-tick reopen M1-052` invocation after this ticket lands, NOT part of this ticket's diff"
  - "Touching `M1-053` ticket frontmatter or acceptance — M1-053 is independently deferred on M1-057 (sealed-type unseal) and is structurally unrelated to this ticket beyond both being downstream beneficiaries of the codified Shape B pattern"
  - "Modifying the M1-049 ticket file or commit — M1-049 is `done` and immutable per the workflow rule 'Never amend a passed commit'; this ticket modifies the deliverable (test-pyramid.md), not the historical ticket"
  - "Touching the `clarity-prompt.md` or `reviewer-prompt.md` to teach the clarity/reviewer subagents about the two-shape distinction — clarity already reads spec_refs and would surface a mismatch; if the clarity check needs updating to enforce the new structure, file as a follow-up after observing M1-052's reopen + a few subsequent DB-centric handler tickets"
acceptance:
  - "docs/process/test-pyramid.md §Handler unit tests is RESTRUCTURED (not just amended with an inline exception subsection) to present two equally-legitimate handler-test shapes side-by-side. The section's load-bearing content from M1-049 — the router-leak prohibition (handler tests MUST NOT call `adapter.deliverDm(...)` and MUST NOT exercise `InboundRouter.onMessage`), the M1-044b premise-fail #2 rationale, the responsibility framing — is preserved at the SECTION ROOT and applies to BOTH shapes. The structural fix replaces the prior 'one shape (plain JUnit + stubs) with hidden exception' framing with an explicit 'two shapes' framing."
  - "The restructured §Handler unit tests contains TWO new third-level subsections immediately after the section's shared responsibility / router-leak paragraph: `### Shape A: Collaborator-orchestrator` and `### Shape B: Thin-SQL`. Each subsection contains: a one-paragraph 'when to use this shape' criterion; an explicit MAY-use bullet list; an explicit MUST-NOT-use bullet list; a canonical-examples bullet list with concrete file paths. Shape A preserves the existing canonical examples (`HelpCommandHandlerTest`, `AddSourceCommandHandlerTest`, `SummaryCommandHandlerTest`) verbatim and the existing MAY/MUST-NOT lists from the prior §Handler unit tests (minus the router-leak rule, which is hoisted to the section root, and minus the 'real DataSource' MUST-NOT, which becomes Shape-A-specific). Shape B's MAY-use includes `@QuarkusTest`, `@Inject DataSource`, Quarkus DevServices Postgres, direct `handler.handle(scope, rawText)`; Shape B's MUST-NOT still includes `adapter.deliverDm(...)` (router-leak rule, repeated for emphasis). Shape B's canonical examples include `GrantAdminCommandHandlerTest` plus the 5 sibling files."
  - "Shape B subsection contains an explicit criterion: 'the handler has ≤1 non-DB collaborator AND ≥2 DB statements that depend on real-DB semantics — triggers, FOR UPDATE locking, RETURNING clauses, PK / UNIQUE / FK / CHECK constraints, or partition-routing.' Shape B subsection contains the rationale verbatim: thin-SQL handlers have no rich orchestration logic to assert in isolation; stubbing the JDBC chain reduces tests to whitebox tautologies (asserting that the handler issued the exact SQL string the test stubbed); the handler's behavioral contract IS the DB interaction (lock acquisition, trigger-driven state, constraint enforcement); the test must observe the DB to verify the contract. The rationale paragraph names and rejects the orthodox alternative (stubbed JDBC + separate migration-test layer): no migration-test layer exists in the project; building one would be larger-scope than the restructure."
  - "§Choosing the layer gains a Handler-tier sub-question after its existing first bullet ('One CommandHandler implementation, no router involvement → handler unit test'): a 2-line addition reading 'Handler has ≤1 non-DB collaborator AND ≥2 real-DB-dependent statements → Shape B (Thin-SQL). Otherwise → Shape A (Collaborator-orchestrator).' The addition links to the §### Shape A and §### Shape B subsection anchors. The existing router-unit-test and IT bullets are preserved verbatim."
  - "M1-044c sibling refactor decision is EXPLICITLY recorded as bullets in this ticket's §Notes section, one per sibling — six bullets total. Each bullet has the form `<TestClass>: qualifies — <count> real-DB-dependent statements (<enumerate them>)` for scenario A, or `<TestClass>: DOES NOT qualify — <reason>; refactor follow-up filed as M1-XXX` for scenario B. The implementer Reads each sibling file before authoring its bullet (no inference, no batching). If any bullet is scenario B, the follow-up refactor ticket M1-XXX is filed as a skeleton (status: pending; out_of_scope and acceptance filled with the per-sibling refactor scope) BEFORE this ticket can close — the new ticket file's existence is the closing precondition."
  - "If outcome (A) is recorded for ALL 6 siblings (the expected case): each of the 6 sibling test files gains a single class-level Javadoc tag immediately above the class declaration: `@implNote Canonical thin-SQL handler exception per `docs/process/test-pyramid.md` §Shape B: Thin-SQL.` so the canonical-example status is self-documenting at the source file. If outcome (B) is recorded for any sibling: the Javadoc is omitted from that sibling file; the follow-up refactor ticket carries the eventual refactor. The reviewer's negative-space check on files_scope distinguishes the cases."
  - "M1-052 (the spec_amend_parent) reopen is OUT OF SCOPE for this ticket — the amendment ticket does NOT touch `docs/plan/m1/tickets/M1-052-saved-post-library.md`. M1-052's reopen + refinement (to cite the new §Shape B subsection in its acceptance items 3/5/7) is a separate `/m1-tick reopen M1-052` invocation after THIS ticket lands on main."
  - "`mvn -B clean verify` from the repo root exits 0. Pure-doc edits to test-pyramid.md are a no-op for tests. If outcome (A) Javadocs are added: those `@implNote` tags must not break Java compilation; the 6 sibling tests continue to pass without behavioral change (the Javadoc is documentation, not annotation)."
test_plan:
  adds: []
  modifies:
    - docs/process/test-pyramid.md
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GrantAdminCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnbanCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/VouchCommandHandlerTest.java
  preserves:
    - all tests currently green on main
    - every M1-044c sibling test continues to pass without behavioral change (only Javadoc additions if any)
    - every M1-049 plain-JUnit handler-unit test (AddSourceCommandHandlerTest, SummaryCommandHandlerTest, HelpCommandHandlerTest) continues to pass without behavioral change
    - the router-leak prohibition from M1-049 (handler tests MUST NOT call adapter.deliverDm, MUST NOT exercise InboundRouter.onMessage) — preserved verbatim at the §Handler unit tests section root after restructure
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

# M1-056: Amend test-pyramid — restructure §Handler unit tests for two-shape reality

## Context

`docs/process/test-pyramid.md` §Handler unit tests rule "MUST NOT use a real DataSource connection" (line 22 of the current doc) is contradicted by the codebase: six M1-044c sibling tests (`GrantAdminCommandHandlerTest`, `RevokeAdminCommandHandlerTest`, `BanCommandHandlerTest`, `UnbanCommandHandlerTest`, `InviteCommandHandlerTest`, `VouchCommandHandlerTest`) ship as `@QuarkusTest` + Quarkus DevServices Postgres + direct `handler.handle()`. The contradiction was developer-surfaced during M1-052's `/m1-tick start` when acceptance item 3 cited both "plain JUnit per the M1-049 test pyramid (no @QuarkusTest)" AND "Testcontainers Postgres ... (M1-044c pattern)" — mutually exclusive references whose resolution exposed a structural spec gap rather than a ticket-wording bug.

The codebase has organically grown two handler-test shapes reflecting two genuine handler types:

1. **Collaborator-orchestrator handlers** (rich non-DB collaborators — `UrlProbe`, `EligiblePostQuery`, `ClusterTraversal`, `SummaryProseGenerator`, etc.). Plain JUnit + stubbed JDBC fits — the interesting logic is the orchestration; DB is incidental. Examples: `HelpCommandHandlerTest`, `AddSourceCommandHandlerTest`, `SummaryCommandHandlerTest`.
2. **Thin-SQL handlers** (handler IS the SQL — `SELECT ... FOR UPDATE`, INSERT with snapshot columns, DELETE with affected-row check, trigger-driven state). Plain-JUnit + stubbed JDBC reduces tests to tautologies. `@QuarkusTest` + real DB observes the actual behavioral contract. Examples: the 6 M1-044c siblings; upcoming `/save`, `/saved`, `/unsave` from M1-052 and the source-management handlers from M1-053 (deferred on M1-057).

The test-pyramid doc was written by M1-049 assuming all handlers fit shape (1). M1-044c shipped 6 tests of shape (2) AFTER M1-049 landed, without amending the doc. M1-052 is the first ticket to make the contradiction undeniable.

**Scope choice — restructure rather than additive amendment.** An additive `### Thin-SQL handler exception` subsection inside the existing §Handler unit tests would land the rule fix but leave an awkward "MUST NOT X, except when Y" structure that future readers stumble through. This ticket instead RESTRUCTURES §Handler unit tests to present both shapes as equally-legitimate top-level alternatives, with the router-leak prohibition (M1-049's load-bearing contribution) hoisted to the section root where it applies to BOTH shapes. The rest of the doc (§Router unit tests, §Integration tests, §Choosing the layer) is preserved as-is, except for a 2-line addition to §Choosing the layer recognizing the handler-tier sub-choice.

This ticket lands the restructure so future DB-centric handler tickets (M1-052 reopen, M1-053 reopen, M1-054, M1-055a, and beyond) have an accurate reference and don't recur the M1-052 escalation. M1-052 itself is `status: deferred`, `deferred_on: M1-056`, `deferred_reason: spec-amend`. After this ticket lands, `/m1-tick reopen M1-052` brings it back, refining its acceptance to cite the codified `§Shape B: Thin-SQL` subsection.

## Acceptance

The YAML `acceptance:` list above is binding. Highlights:

- **Item 1** commits the restructure framing: §Handler unit tests gets a two-shape structure (NOT an additive exception subsection). The router-leak prohibition is hoisted to the section root so it visibly applies to both shapes. The M1-049 rationale (M1-044b premise-fail #2) is preserved verbatim at the section root.
- **Item 2** specifies the exact structural shape: two `### Shape A / ### Shape B` subsections, each with a when-to-use paragraph + MAY-use + MUST-NOT + canonical examples. Shape A preserves the existing canonical examples and most of the existing MAY/MUST-NOT lists; Shape B is new content.
- **Item 3** specifies Shape B's criteria (≤1 non-DB collaborator + ≥2 real-DB-dependent statements) and rationale (tautology argument + rejection of the orthodox alternative).
- **Item 4** updates §Choosing the layer with the 2-line handler-tier sub-question.
- **Item 5** forces the M1-044c sibling refactor decision (scenario A: codified examples / scenario B: refactor follow-up filed) to be made EXPLICITLY in this ticket's §Notes, one bullet per sibling, with per-sibling Read-then-author. Without this, the restructure legitimizes the siblings by implication only.
- **Item 6** is the conditional Javadoc tagging on the 6 siblings (only for siblings where scenario A wins). The reviewer's negative-space check on `files_scope` distinguishes scenario-A from scenario-B at review time.
- **Item 7** scopes the M1-052 reopen explicitly OUT — that's a separate `/m1-tick reopen` invocation.
- **Item 8** confirms `mvn -B clean verify` exit 0.

## Out-of-scope

This ticket is a one-section restructure of one process doc, plus optional one-line Javadocs on 6 sibling test files. Scope boundaries (the YAML `out_of_scope` is the binding list; the prose below mirrors and explains):

- **No refactor of the 6 M1-044c siblings to plain-JUnit + stubs.** The restructure's whole purpose is to legitimize the M1-044c pattern. If any sibling fails the Shape B criteria in acceptance item 5, the refactor happens in a NEW follow-up ticket (filed as a skeleton before this one closes), not in this diff.
- **No edits to other process docs.** Scope is `docs/process/test-pyramid.md` only.
- **No edits to §Router unit tests / §Integration tests.** Those sections are correct as-written; the contradiction is strictly handler-tier.
- **No replacement of Shape A.** The Shape A subsection preserves M1-049's existing content (canonical examples, MAY/MUST-NOT lists) verbatim. Shape B is additive at the structural level, not a replacement.
- **No rename of test-pyramid.md.** If the doc name should change (it's somewhat misleading — the doc is really about layer responsibilities, not the pyramid shape), file as a follow-up after this lands.
- **No new migration-test layer.** Acceptance item 3's rationale explicitly rejects building one as larger-scope than the restructure.
- **No production-code edits.** Pure-doc plus optional sibling-test Javadocs.
- **No M1-052 or M1-053 ticket-frontmatter edits.** Those reopens are separate `/m1-tick reopen` invocations.
- **No clarity-prompt / reviewer-prompt edits.** If clarity-check needs to enforce the two-shape distinction explicitly, that's a follow-up observed after M1-052/053 reopens + a few subsequent DB-centric handler tickets.

## Notes

- **Restructure target**: `docs/process/test-pyramid.md` §Handler unit tests (lines 7–27 of the current 74-line doc). The router-leak prohibition currently lives as one item in the MUST NOT bullet list (line 21); the restructure hoists it to the section root so its applicability to both shapes is visible.
- **Shape A canonical examples** (preserved from M1-049's current text): `HelpCommandHandlerTest`, `SummaryCommandHandlerTest`, `AddSourceCommandHandlerTest`.
- **Shape B canonical examples** (new content): `GrantAdminCommandHandlerTest` (primary; cited by full path), plus `RevokeAdminCommandHandlerTest`, `BanCommandHandlerTest`, `UnbanCommandHandlerTest`, `InviteCommandHandlerTest`, `VouchCommandHandlerTest`.
- **Per-sibling assessment template** (for acceptance item 5; implementer Reads each sibling file then fills these in during implementation):
  - `GrantAdminCommandHandlerTest`: [scenario A or B with enumerated real-DB statements]
  - `RevokeAdminCommandHandlerTest`: [...]
  - `BanCommandHandlerTest`: [...]
  - `UnbanCommandHandlerTest`: [...]
  - `InviteCommandHandlerTest`: [...]
  - `VouchCommandHandlerTest`: [...]
- **M1-049 lineage**: M1-049 is `done` (commit `c71ce14`, 2026-05-22) and immutable per the workflow rule "Never amend a passed commit". This ticket modifies the DELIVERABLE (test-pyramid.md) rather than the historical ticket. The restructure preserves M1-049's load-bearing contribution (router-leak rule + M1-044b premise-fail #2 rationale) verbatim at the new section root.
- **M1-052 reopen path**: after this ticket lands, `/m1-tick reopen M1-052` revives the deferred ticket. The M1-052 refinement cites `docs/process/test-pyramid.md` §Shape B: Thin-SQL as the binding test-pattern reference for acceptance items 3/5/7.
- **M1-053 reopen path** (informational, not required by this ticket): M1-053 is independently deferred on M1-057 (sealed-type unseal), not on M1-056. After M1-057 lands AND M1-056 lands, M1-053's reopen + refine can cite the same §Shape B subsection. The two reopens are independent.
- **CLAUDE.md "doc edits bypass ticket flow" rule**: this ticket intentionally goes through the ticket flow despite being pure-doc plus 6 single-line Javadocs. Justification: the restructure is a STRUCTURAL change to a MUST-NOT rule that governs all future handler tests, not routine doc maintenance. The bypass rule was designed for typo fixes, link updates, and clarifications. Applying it to a rule change would skip the value-add of clarity-check + reviewer gates on a consequential change.
- **No `redteam` recommended**: pure-doc plus single-line Javadocs, `security_relevant: false`, no threat-model surface touched.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-056-amend-test-pyramid-thin-sql-exception.md
```
