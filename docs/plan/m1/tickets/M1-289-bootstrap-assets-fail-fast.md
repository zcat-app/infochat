---
id: M1-289
title: "Unreadable bootstrap-assets file fails startup instead of silently disabling"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetRegistry.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The unset-path opt-out branch (no assets configured → info log, no failure) — already correct per spec.
  - The malformed-file fail-fast branch — already correct.
  - Asset registry semantics beyond the readability check.
acceptance:
  - "Spec behaviour implemented (docs/spec/deployment.md ~:235, verbatim: 'Path set, file absent … Startup fails fast with a fatal log message identifying the configured path. Silently disabling asset commands here would mask the misconfiguration.'): AssetRegistry.loadBootstrapMeta's 'if (!Files.isReadable(path)) { return Map.of(); }' (~:189) becomes a thrown IllegalStateException naming the configured path; a named test configures a nonexistent path and asserts startup fails with the path in the message."
  - "A named test (existing or new) pins that the unset-path branch still starts cleanly with asset commands disabled."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-289: Unreadable bootstrap-assets file fails startup instead of silently disabling

## Context

Deep-review v5 verified HIGH **U-06** (`deep-code-review/v5/UNIFIED-REPORT.md`
§2; source `deep-code-review/v5/deepseek/07-module-infochat-provider.md#F2`,
unique find — gitignored; all load-bearing facts inlined; the report calls
this "the single best effort-to-value ticket" of the run):

`AssetRegistry.loadBootstrapMeta()` returns an empty map when the configured
bootstrap-assets file is unreadable (verified 2026-06-11:
`if (!Files.isReadable(path))` at :189) — the operator opted in, the file is
missing (typo, wrong workdir, unmounted volume), and the deployment comes up
with asset commands silently absent. The spec explicitly forbids exactly
this: the deployment.md sentence quoted in acceptance was written against
this failure mode.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter — the two adjacent branches are already conformant.

## Notes

- One conditional swap plus a test; the sibling branches show the existing
  fail-fast style to match.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-289-*.md
```
