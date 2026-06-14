---
id: M1-343
title: "Provider --page parsing: surface a usage error on a malformed page value"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: WARN
  warnings:
    - "Acceptance item 2 uses operator-facing prose rather than a reviewer-checkable assertion; the core fact (catch blocks removed) is embedded but not foregrounded. Low-risk because item 3 provides the test pin."
  blockers: []
blocked_by: []
files_budget: 4
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AuditCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The page-clamping semantics for VALID input (Math.max(1, n)) — unchanged; only the malformed-input path changes from silent-fallback-to-1 to a usage error.
  - Other argument parsers in these handlers — untouched.
acceptance:
  - "The four catch (NumberFormatException ignored) {} blocks in the --page parsers (QuarantineCommandHandler.java:345,350 and AuditCommandHandler.java:284,289) no longer silently swallow a malformed --page value and fall back to page 1. A non-numeric --page (e.g. /audit --page=abc) surfaces the same usage-error shape every other malformed argument produces (the established convention: the parser returns the failure marker and the handler renders ERROR_USAGE_MISSING_ARGUMENT with the command's usage string), consistent with AssetHandler / BanCommandHandler / AddSourceArgs."
  - "An operator inspecting behavior can now distinguish 'user asked for page 1' from 'user typo'd the page argument'; the §8 swallow-pattern (catch ... ignored {} in production code) is removed from both handlers."
  - "A test pins each handler: a malformed --page value yields the usage-error reply (not a silent page-1 result); a valid --page N still clamps and paginates as before."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command (malformed --page cases)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 72
      removed: 13
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-343: Provider --page parsing — surface a usage error

## Context

Deep-review v5.5 (opus-47, `07-module-infochat-provider.md` F4) found two
`catch (NumberFormatException ignored) {}` blocks per handler that silently
swallow a malformed `--page` value and fall back to page 1. **Verified at source
2026-06-14:** QuarantineCommandHandler.java:345,350 and
AuditCommandHandler.java:284,289 each `catch (NumberFormatException ignored) { }`.

`engineering-rules-verbatim.md` §8 flags `catch (Exception ignored) {}` as
forbidden in production code; the narrower `NumberFormatException` has the same
defect: a user typing `/audit --page=abc` silently gets page 1 instead of the
friendly usage error every other malformed argument produces, and the bug is
hidden by the silent fallback. It is also inconsistent with the rest of the
command catalogue (`AssetHandler`, `BanCommandHandler`, `AddSourceArgs` all
surface a parse failure as `ERROR_USAGE_MISSING_ARGUMENT`).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Both handlers already have a usage-error shape (`/audit [--actor X] [--action
  Y] [--page N]` etc.). The convention is `return null` from the failing parser →
  handler renders the usage string.
