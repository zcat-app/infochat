---
id: M1-654
title: "Guard the closed LLM tool allowlist against spec drift"
status: done
created: 2026-07-18
last_updated: 2026-07-18
blocked_by: []
clarity_check:
  date: 2026-07-18
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-07-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 230
      removed: 14
redteam_findings: []
redteam_audits:
  - date: 2026-07-18
    verdict: CLEAN
    base: aeacd5099127f6f6e0e2d572709cc480f30f21b3
    head: working tree (branch m1/M1-654-tool-allowlist-spec-parity-guard, no commits yet)
    verdict_file: docs/plan/m1/redteam/M1-654-2026-07-18.md
    out_of_model_count: 3
    note: |
      CLEAN. Audited the working-tree-vs-fork-point diff rather than the
      skill's `main...<branch>` form, which resolves to an EMPTY diff at this
      gate because the branch carries no commits until /m1-tick commit runs.
      Three out-of-model items, none a gap in this diff and none auto-filed:
      (1) ChatAgent.TOOL_INSTRUCTIONS hardcodes a THIRD copy of the six tool
      names that this guard does not cover — verified present and fail-closed
      (ChatToolDispatcher rejects "Unknown tool"), so it cannot widen
      capability; the strongest follow-up candidate. (2) The guard covers tool
      NAMES only, not the Notes column's per-tool behavioural commitments,
      which is exactly the promise security.md makes. (3) The parser binds the
      first marker pair, so a hypothetical second block would go unparsed —
      only able to hide a spec-only row, never an extra registry name.
files_budget: 5
files_scope:
  - docs/spec/security.md
  - docs/spec/verification.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolAllowlistSpecParityTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ChatToolRegistry.java and its TOOL_NAMES set. This ticket OBSERVES the
    registry; it never changes which tools exist. Adding the 7th tool is M1-648.
  - >-
    ChatToolRegistryTest.registryContainsExactlySpecTools. Left byte-for-byte
    unchanged — this ticket ADDS a guard, it does not modify, replace, or weaken
    the existing hardcoded pin. See Notes for why.
  - >-
    The per-tool Inputs / Output / Notes columns of the security.md table. The
    guard compares tool NAMES only; column contents are prose the parser ignores.
  - >-
    CommandCatalogueParityTest and the command-catalogue parity axes (M1-527,
    M1-646, M1-651). Same pattern, different closed list, separate test.
  - >-
    Widening, narrowing, or otherwise changing the closed tool set, and any
    change to the LLM tool dispatch path (ChatToolDispatcher).
acceptance:
  - >-
    docs/spec/security.md §Prompt-injection defenses carries
    `<!-- tool-allowlist:begin -->` / `<!-- tool-allowlist:end -->` markers
    around the per-tool table, following the `<!-- command-index:begin -->`
    convention already used at docs/spec/commands.md:131.
  - >-
    A new ChatToolAllowlistSpecParityTest parses tool names from between those
    markers and asserts set equality against ChatToolRegistry.toolNames().
    Because it is a set equality it fails in BOTH directions — a registry name
    with no spec row, and a spec row with no registry name — which is exactly
    what security.md and verification.md §Security both already claim CI does.
    ChatToolAllowlistSpecParityTest.registryMatchesMarkedSpecTable passes.
  - >-
    Vacuity guard: the test fails loudly when the marker region parses to an
    empty name set, so a future formatting change that silently matches nothing
    cannot make the parity assertion trivially true. Mirrors the guard at
    CommandCatalogueParityTest:196-203, which deliberately does NOT pin an exact
    count. ChatToolAllowlistSpecParityTest.parserIsNotVacuous passes.
  - >-
    docs/spec/verification.md §Security no longer duplicates the tool-name list.
    Its parenthetical enumeration — currently five names, already stale because
    it omits `semanticSearch` — is replaced by a pointer to security.md's table
    as the single source of truth, so no enumeration remains that can drift.
  - >-
    mvn verify from the repo root is green. The guard lands green against the
    current tree with no production change: security.md's table has exactly six
    rows and ChatToolRegistry.TOOL_NAMES holds exactly those same six names.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolAllowlistSpecParityTest.java
  preserves:
    - all tests currently green on main
    - ChatToolRegistryTest.registryContainsExactlySpecTools, unchanged
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/verification.md §Security
decision_refs:
  - D21
---

# M1-654: Guard the closed LLM tool allowlist against spec drift

## Context

`docs/spec/security.md` §Prompt-injection defenses closes with:

> Verification (`verification.md` §Security) asserts the registry's name set
> equals the table above byte-for-byte; CI fails on a mismatch in either
> direction.

`docs/spec/verification.md` §Security makes the same claim in its own words.
**Neither is true.** No test in the tree reads `security.md`;
`ChatToolRegistryTest.registryContainsExactlySpecTools` hardcodes a six-name
`Set.of` and merely *mentions* the spec in a comment. The claimed enforcement is
hand-maintained convention.

The drift it was supposed to prevent has already happened. M1-589 (`86463e05`)
added `semanticSearch` to the registry and amended `security.md`'s table, but
never touched `verification.md` — whose enumeration has listed only five of the
six tools ever since. The first edit after the claim was written broke it, and
nothing noticed for two months.

This ticket builds the guard the spec already promises, rather than deleting the
promise. The pattern is established: `CommandCatalogueParityTest` already parses
`docs/spec/commands.md` between `<!-- command-index:begin -->` markers and
asserts parity across three axes (M1-527, M1-646, M1-651). The LLM tool
allowlist is the same shape — a closed list duplicated between spec and code —
and is the one such list with no guard.

## Acceptance

See `acceptance`. Markers around the spec table, a parity test with a vacuity
guard, and removal of `verification.md`'s duplicate enumeration.

## Out-of-scope

See `out_of_scope`. Note especially that the existing hardcoded test is left
untouched and that this ticket changes no production code.

## Notes

**Why the existing literal test is left alone.** Replacing
`registryContainsExactlySpecTools`'s hardcoded `Set.of` with the parsed spec set
would remove a pin, and the engineering rules forbid weakening a test. The cost
of leaving it is that the closed list lives in three places (spec table,
registry, test literal) rather than two. That is accepted deliberately: the
guard makes the spec↔registry pair machine-checked, which is the pair the spec
makes promises about. Collapsing the literal is a separate judgement call for a
later ticket, not something to smuggle in here.

**Path resolution.** `CommandCatalogueParityTest` resolves its doc as
`Path.of("..", "docs", "spec", "commands.md")` — module-dir-relative, no repo
-root discovery logic. Reuse that shape; do not invent a second mechanism.

**Why `security_relevant: true` on a test-and-docs ticket.** It edits a security
spec section and builds the enforcement for a prompt-injection defense. M1-646,
the directly comparable catalogue-guard ticket, is also flagged.

**Relationship to M1-648.** M1-648 registers a 7th tool (`HelpLookupTool`) and
must amend `security.md`'s table to do so. With this guard in place that
amendment is enforced by the build instead of by a reviewer noticing — which is
why M1-648 lists M1-654 in `blocked_by`. The guard lands green today, so it
blocks nothing else.
