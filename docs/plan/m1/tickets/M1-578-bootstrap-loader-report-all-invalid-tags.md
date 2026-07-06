---
id: M1-578
title: "BootstrapLoader reports ALL invalid source tags at once, not just the first"
status: draft
created: 2026-07-06
last_updated: 2026-07-06
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapLoaderTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Making an invalid tag non-fatal, or auto-normalizing spaces/slashes into
    valid tags. The fail-fast-on-invalid contract stays (a bad bootstrap file
    must refuse boot); this ticket only improves WHAT the failure reports.
  - "Changing the tag regex / TagNormalizer rules. Unchanged."
  - >-
    Validating anything other than source tags in the bootstrap file (URLs,
    kinds, asset entries). Out of scope.
acceptance:
  - >-
    When bootstrap-sources.json contains one or more source tags that fail
    normalization, BootstrapLoader validates ALL source tags first and throws a
    single failure that enumerates every invalid (source identifier, raw tag,
    reason) — not just the first one encountered. An operator can fix every bad
    tag in one pass instead of reboot-per-tag whack-a-mole.
  - >-
    A bootstrap file with zero invalid tags behaves exactly as today (loads
    cleanly, no behavior change).
  - >-
    Fail-fast is preserved: any invalid tag still aborts startup (the loader does
    not partially load or silently skip a bad source).
  - >-
    BootstrapLoaderTest adds a case with MULTIPLE invalid tags across sources and
    asserts the thrown message names all of them.
  - "`mvn verify` is green from the repo root (new test passes; full suite passes)."
test_plan:
  adds:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapLoaderTest.java — multiple invalid tags → single error listing all."
  modifies:
    - "infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java — collect-then-throw tag validation."
  preserves:
    - "clean-file load path; fail-fast contract; all tests green on main."
spec_refs:
  - "docs/design/03-commands.md §Tag arguments"
decision_refs: []
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-578: Report all invalid bootstrap tags at once

## Context

This session, a single invalid tag in bootstrap-sources.json (`"GLM AI"` — a
space is not allowed by `^[a-z0-9][a-z0-9-]{0,47}$`) crashed the collector at
boot. `BootstrapLoader.normalizeTag` throws on the FIRST invalid tag, so an
operator cleaning up a file with several bad tags (there was also `"Spring I/O"`)
must fix one, reboot, hit the next, reboot again — whack-a-mole.

The project already values "report every unmet check at once" — `0-doctor.sh`
does exactly that for preflight. The bootstrap tag validation should match:
collect all invalid tags, then throw once listing them.

## Approach

- In the tag-validation pass, accumulate `(source identifier, raw tag, reason)`
  for every invalid tag across all sources, then throw a single
  `IllegalStateException` enumerating them (instead of throwing inside the loop
  on the first).
- Keep the throw fatal and the clean path unchanged.

## Notes

- Small DX-only change; the collector already fails fast and clearly on ONE bad
  tag — this just turns N reboots into one fix. Pairs with the operator setup
  experience (SETUP_GUIDE) but needs no guide edit.
