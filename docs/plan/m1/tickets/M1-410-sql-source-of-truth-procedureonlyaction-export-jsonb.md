---
id: M1-410
title: "core+provider: trust the declared SQL source of truth (audit-verb parity test, export by column type)"
status: done
created: 2026-06-20
last_updated: 2026-06-20
blocked_by: []
files_budget: 3
files_scope:
  - infochat-core/src/test/java/app/zcat/infochat/core/audit
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ExportDataCollector.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - ProcedureOnlyAction.java (the enum constants) — unchanged; this ticket pins it with a parity test, it does not rename or add constants.
  - The SECURITY DEFINER migration bodies — unchanged; the test mirrors the existing verb literals, it does not edit migrations (no migration_touch).
  - The set of columns the export queries select — unchanged; only HOW a Types.OTHER value is classified for inline-vs-quote changes.
acceptance:
  - "A new parity test under infochat-core/src/test/java/app/zcat/infochat/core/audit asserts the set of ProcedureOnlyAction constant names equals the set of audit-verb TEXT literals hand-written by the SECURITY DEFINER procedures in the migrations, using full set-equality (the same approach TargetKindCheckParityTest uses for TargetKind), so a rename on either the SQL or the Java side fails the build."
  - "The parity test covers all four constants (UNBAN_PREBAN_DELETE, APPROVE_QUARANTINE, REJECT_QUARANTINE, D47_GROUP_ONLY_PREBAN_CONVERSION), not just the single V27 verb the existing SchemaHardeningIT check pins."
  - "ExportDataCollector decides JSONB-inline-vs-quote from the column's declared SQL type (its ResultSetMetaData type name), not from the value's first character, so a future Types.OTHER column whose value starts with { or [ but is not JSONB is quoted and escaped rather than emitted as raw JSON."
  - "A test under infochat-provider/src/test/java/app/zcat/infochat/provider/command asserts the JSONB columns (cluster_map, details_json) are still inlined and the UUID Types.OTHER columns are still quoted; if feasible with the existing fixtures, it also asserts a non-JSON OTHER value is escaped, not inlined."
  - "The audit tests, ExportDataCollectorTest, and ExportCommandHandlerTest remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/audit (ProcedureOnlyAction full-set SQL parity test)
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command (export classifies by declared column type)
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
      files: 5
      added: 220
      removed: 19
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-20
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-410: trust the declared SQL source of truth (audit-verb parity test, export by column type)

## Context

Deep-review full (2026-06-20) two **low** RULES-DRIFT findings that share one
topic: a Java-side shadow of a SQL truth is kept by hand or inferred from value
shape rather than pinned to / read from the declared schema. Both verified at source
2026-06-20.

**(a) core F1 — ProcedureOnlyAction has no full SQL parity test.**
`ProcedureOnlyAction` mirrors the audit-verb TEXT literals hand-written in the
SECURITY DEFINER procedure bodies (V5/V27/V48/V50 chain). Unlike its siblings —
`TargetKind` (pinned by `TargetKindCheckParityTest` via full `values()` set-equality)
and `Redactor.CATALOGUE` (pinned by `RedactorSqlParityIT`) — it has no parity guard.
The only test referencing it, `SchemaHardeningIT.v27AuditVerbIsInProcedureOnlyClosedSet`,
cross-checks exactly one constant (`D47_GROUP_ONLY_PREBAN_CONVERSION`); the other
three can be renamed on either side and still compile and pass the suite, silently
desynchronizing the `/audit --action` read path the provider consumes.

**(b) provider F2 — ExportDataCollector classifies JSONB by value first character.**
`ExportDataCollector` decides whether a `Types.OTHER` value is JSON (inline raw) or
not (quote/escape) by testing whether its first character is `{` or `[`. Correct
today only because the sole `Types.OTHER` columns the export selects are JSONB
(`cluster_map`, `details_json`) and UUID (UUID text never starts with `{`/`[`). It is
a fragile value-shaped classifier on a data-export path: a future `Types.OTHER`,
non-JSONB column whose value happens to start with `{`/`[` would be emitted as raw,
unescaped bytes. Keying on the declared column type instead removes the fragility.

## Acceptance

See frontmatter. (a) Add a full-set SQL parity test for `ProcedureOnlyAction`.
(b) Classify export inline-vs-quote by declared column type, not value first char.

## Out-of-scope

See frontmatter. The enum constants and the migration bodies are unchanged
(test-only on the core side); the export column set is unchanged.

## Notes

- The core half is test-only and reads the migration SQL the same way
  TargetKindCheckParityTest already locates and reads V5 — no new mechanism.
- No migration is edited, so `migration_touch: false`.
</content>
