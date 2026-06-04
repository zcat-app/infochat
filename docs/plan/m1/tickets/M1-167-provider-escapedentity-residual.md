---
id: M1-167
title: "Fix residual EscapedEntity javadoc findings in provider"
status: done
created: 2026-06-05
last_updated: 2026-06-05
blocked_by: []
files_budget: 2
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/ClusterTraversal.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SourceDisableCommandHandler.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any module other than infochat-provider
  - NullAway/Error Prone build wiring (parent-pom pluginManagement is M1-164a)
  - any production code behavior change (these are javadoc-only edits)
  - any Error Prone finding category other than EscapedEntity
acceptance:
  - "ClusterTraversal.java javadoc `{@code}` block at the Cluster(String, List<Post>) record shape uses literal `<`/`>` instead of `&lt;`/`&gt;`"
  - "SourceDisableCommandHandler.java javadoc `{@code}` block for the status check uses literal `<>` instead of `&lt;&gt;`"
  - "mvn -B -pl infochat-provider clean verify exits 0 and emits no [EscapedEntity] warning from infochat-provider main sources"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Architectural principles
decision_refs:
  - D48
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 12
      removed: 10
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-05
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-167: Fix residual EscapedEntity javadoc findings in provider

## Context

M1-164f onboarded `infochat-provider` to NullAway + Error Prone (decision D48)
and its acceptance committed to "all NullAway:ERROR and Error Prone
default-check findings are resolved". Two Error Prone `[EscapedEntity]`
findings survived that onboarding and remain on `main`:

- `summary/ClusterTraversal.java:35` — `{@code Cluster(String, List&lt;Post&gt;)}`
- `command/SourceDisableCommandHandler.java:50` — `{@code status &lt;&gt; 'active' ...}`

`EscapedEntity` is WARNING-severity (HTML entities inside `{@code}`/`{@literal}`
render literally rather than as `<`/`>`), so it does not break the build — which
is why the M1-164f review (build-green) did not catch it. The same finding class
was caught and fixed during M1-164e (collector); this ticket closes the
equivalent residue in provider so the M1-164 module set is uniformly clean.
Per the workflow, a defect found after a passed review becomes a new ticket
rather than an amend of M1-164f.

## Acceptance

See frontmatter. Replace the escaped HTML entities inside the two `{@code}`
javadoc blocks with their literal characters (`&lt;`→`<`, `&gt;`→`>`,
`&lt;&gt;`→`<>`), exactly as M1-164e did for `LinkingJob`. After the fix,
`mvn -B -pl infochat-provider clean verify` is green and the build log emits no
`[EscapedEntity]` warning from provider main sources.

## Out-of-scope

See frontmatter. Javadoc-only edits — no production behavior change, no
annotation work, no build wiring. Only the EscapedEntity category is in scope;
do not chase other Error Prone WARNING categories that may exist in the module.

## Notes

- Mechanical fix, mirrors the M1-164e round-1 rework (`LinkingJob` `&lt;`→`<`).
- Both occurrences are inside `{@code ...}` blocks; the literal `<`/`>` is the
  correct rendering — these are SQL/Java fragments shown to a javadoc reader, not
  markup.
