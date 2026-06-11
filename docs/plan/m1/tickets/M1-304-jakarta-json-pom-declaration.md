---
id: M1-304
title: "Declare jakarta.json (+ Parsson) in the messaging-adapter pom"
status: done
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 2
files_scope:
  - infochat-messaging-adapter/pom.xml
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any Java source change — the imports are correct; only the pom is dishonest about them.
  - Version management outside the BOM.
acceptance:
  - "infochat-messaging-adapter/pom.xml declares jakarta.json-api (compile) and the Parsson provider (runtime), BOM-managed (no inline versions) — today the pom has zero jakarta.json mentions while four Signal main-source files import it (SignalMessageCodec, SignalGroupHandler, SignalMentionParser, SignalJsonRpcClient; verified 2026-06-11), relying on transitive provisioning the pom's own jackson comment names as exactly this hazard."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-11
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 32
      removed: 8
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-11
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-304: Declare jakarta.json (+ Parsson) in the messaging-adapter pom

## Context

Deep-review v5 verified MEDIUM **U-22**
(`deep-code-review/v5/UNIFIED-REPORT.md` §3; sources `fable-5/05#F3`,
`gpt-55#M-11` — gitignored; all load-bearing facts inlined):

The whole Signal codec imports `jakarta.json` but the module pom never
declares it — the dependency arrives transitively (Quarkus BOM), so an
unrelated dependency-tree change can break the module's compile without
any local edit. The pom's own comment on the jackson declaration names
this exact failure mode as the reason jackson IS declared.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **⚠ Dependency addition — explicit user approval required at start**
  (recorded project rule: never add a dep silently; this ticket is the
  proposal). Per-dep reasoning: `jakarta.json-api` is compile-scope because
  main sources import its types; Parsson is runtime-scope because it is
  the JSON-P implementation those types need at runtime. Both are already
  on the runtime classpath transitively via Quarkus — this declares
  existing reality, it does not introduce new bytes.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-304-*.md
```
