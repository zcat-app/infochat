---
id: M1-133
title: "CT1 shared text/util extraction (JsonEscaper + TagNormalizer + Sha256) + TODO cleanup"
status: done
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 22
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core
  - infochat-core/src/test/java/app/zcat/infochat/core
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-provider/src/main/java/app/zcat/infochat/provider
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the lookupUser/UserRepository consolidation (covered by M1-144 — a different primitive)
  - any behavioral change to call sites beyond delegating to the new helper (pure extraction)
  - the NOTIFY emitter enum-ification (covered by M1-134)
acceptance:
  - "A single JsonEscaper in infochat-core with correct C0 control-char handling (c < 0x20 → \\u%04x, matching LlmOutputSanitizer.jsonEscape) replaces the ~12 hand-rolled JSON-escape helpers; all call sites delegate"
  - "A single TagNormalizer.normalize in infochat-core replaces the three duplicated tag-normalization copies; the TODO(T1-D) markers are removed"
  - "A single Sha256.hex(byte[]) in infochat-core (using HexFormat) replaces the two SHA-256-to-hex helpers"
  - "JsonEscaperTest asserts C0 control chars (\\b, \\f, vertical tab) are \\u-escaped, not emitted raw"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Operational
  - docs/spec/architecture.md §Architectural principles
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: WARN
      acceptance: PASS
    diff_stats:
      files: 22
      added: 228
      removed: 396
escalations:
  - date: 2026-06-02
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — escalation raised at start (pre-implementation): grounded
      enumeration of the ticket's named scope counts ~21 unique files
      (4 new + ~14 JSON-escape sites + TaggerWorker/TagVocabulary +
      PostPersister; BootstrapLoader/BootstrapAssetsLoader shared),
      exceeding files_budget 18. Matches the clarity FILES-BUDGET WARN.
revisions:
  - date: 2026-06-02
    reason: "refine (budget-breach rework) — widen files_budget to cover the grounded ~21-file call-site fan-out the ticket author under-estimated"
    snapshot:
      files_budget: 18
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-02
  verdict: WARN
  warnings:
    - "FILES-BUDGET-PLAUSIBLE: estimated ~21 file touches (3 new util classes + 1 test + ~17 call-site edits) vs files_budget 18; budget likely tight, count carefully."
    - "SECURITY-FLAG-CONSISTENT: C0 fix in JsonEscaper affects JSON fed to the LLM via SearchPostsTool; consider security_relevant: true or an explicit below-threshold note."
  blockers: []
---

# M1-133: CT1 shared text/util extraction + TODO cleanup

## Context

The single most-cited cross-cutting theme (8-reporter convergence): four
primitives are re-implemented across the tree. **JSON escape** lives in ~12
main-source files with 3+ different escape sets — several handle only
`\ " \n \r \t` and emit other C0 controls raw (invalid JSON);
`LlmOutputSanitizer.jsonEscape` is the correct shape. **Tag normalization** has
3 copies carrying `TODO(T1-D)` markers. **SHA-256 hex** has 2 copies in 2
idioms. A bug in any primitive must be fixed in N places. The real external
exposure is `SearchPostsTool.jsonStr` — feed titles carry C0 controls, so the
tool JSON fed back to the LLM can be malformed.

## Acceptance

See frontmatter. Extract `JsonEscaper`, `TagNormalizer`, `Sha256` to
`infochat-core`; delegate every call site; remove the TODOs. Pure extraction —
no behavioral change beyond fixing the C0 gap.

## Out-of-scope

See frontmatter. This is a broad sweep across collector + provider handlers —
it lands early in the CORE-SHARED lane so dependent tickets (M1-144, M1-146)
rebase onto the helpers. `files_budget` is deliberately high for the call-site
fan-out; the diff is mechanical.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-JSON-DUP, §C-TAG-NORM-DUP,
  §C-SHA256-DUP, §C-TODOS; `opus-47-full-handout.md` §F-SIM-01/02/03, F-MAINT-87.
- ~12 JSON-escape sites incl. `PriceSnapshotStore`, `BootstrapAssetsLoader`,
  `BootstrapLoader`, the admin command handlers, `ExportPaginator`, `LlmOutputSanitizer`.
  Tag-norm: `BootstrapLoader.java:266-274`, `TaggerWorker.java:425-432`, `TagVocabulary.java:127-134`.
  SHA: `BootstrapLoader.java:285-289`, `PostPersister.java:168-177`.
