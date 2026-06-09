---
id: M1-247
title: "infochat-ssrf: body-cap default reconciliation + module lows"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 5
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientTest.java
  - docs/design/04-security.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The SSRF guard's address-resolution / blocklist logic — unchanged; these are cap-default, host-compare, dedup, and wrapper cleanups only.
  - Other consumers of SsrfGuardedHttpClient (collector/llm producers) — unchanged; the default value reconciliation is local to this class + design note.
acceptance:
  - "T8: the SsrfGuardedHttpClient default body cap (DEFAULT_BODY_CAP) and docs/design/04-security.md (infochat.fetch.max-body-bytes default) are reconciled to a single canonical value. EITHER set DEFAULT_BODY_CAP to 5 MB to match the design note, OR amend the design note to 10 MiB to match the code — pick one so code and design agree. A test asserts a no-arg consumer inherits exactly the canonical cap and that a body one byte over the cap is rejected."
  - "T15: isCrossOrigin compares canonicalized hosts (the same canonicalization the module already applies elsewhere) rather than raw getHost(); a named test asserts a same-host request differing only by case/trailing-dot is NOT treated as cross-origin (so the credential scrub is not spuriously triggered/skipped)."
  - "T16: the duplicate LoopbackPermitting inner class in SsrfGuardedHttpClientTest is removed in favor of the shared LoopbackPermittingBlocklist test double; the test suite stays green with no behavioral change."
  - "T17: the BoundedByteArrayResponse wrapper is simplified — fold/remove the heavyweight wrapper IF this ticket touches the return type for T8; otherwise leave it and note it as deferred in the commit message (do not expand scope to refactor it standalone)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 128
      removed: 42
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-09
    verdict: CLEAN
    base: df83e13 (fork point / merge-base with main)
    head: working tree on m1/M1-247-ssrf-body-cap-and-lows (uncommitted, pre-commit, --in-progress)
    verdict_file: docs/plan/m1/redteam/M1-247-2026-06-09.md
    out_of_model_count: 0
    note: |
      Adversarial audit on the in-flight branch (post-APPROVE, pre-commit) because
      T15 host-compare + T8 body-cap touch the SSRF outbound trust boundary. CLEAN:
      no gap vs §"SSRF and outbound connections". The credential-scrub-suppression
      worry on the isCrossOrigin canonicalization was specifically falsified and
      found sound (canonicalization narrows, never collapses, the cross-origin set;
      null/invalid host fails safe to scrub). Nothing feeds a remediation ticket.
clarity_check:
  date: 2026-06-09
  verdict: WARN
  warnings:
    - "COMPLEXITY-RISK-CALIBRATED: risk: low on a security-surface host-comparison fix (T15 isCrossOrigin) may be under-calibrated; T15 directly affects credential scrubbing behavior, risk: medium would be more conservative. Low is defensible given the narrow single-method scope, but a wrong change here has a security impact."
  blockers: []
---

# M1-247: infochat-ssrf — body-cap default reconciliation + module lows

## Context

Four `infochat-ssrf` findings bundled by module. Source: `deep-code-review/v3/`
UNIFIED-REPORT.md T8 (mimo `03#F1`), T15 (opus `03#F2`), T16 (opus `03#F1` + mimo
`03#F2`), T17 (opus `03#F3`).

- **T8 [medium, rules-drift].** `SsrfGuardedHttpClient` `DEFAULT_BODY_CAP =
  10 MiB`; `design/04-security.md` says `infochat.fetch.max-body-bytes` default
  5 MB. Every no-arg consumer inherits 10 MiB. Not security-critical (both bound
  the body) but the design note under-states exposure 2×. The ticket must pick
  the canonical value — code change if 5 MB is intended, design edit if 10 MiB
  is intended.
- **T15 [low, security].** `isCrossOrigin` compares raw `getHost()` while the
  module canonicalizes hosts elsewhere — same shape as T3, a comparison left on a
  narrower primitive than its conservative neighbors.
- **T16 [low].** Duplicate `LoopbackPermitting` inner class vs the shared
  `LoopbackPermittingBlocklist` test double (both reviewers flagged it).
- **T17 [low].** `BoundedByteArrayResponse` heavyweight wrapper — fold **only**
  if T8 already touches the return type; do not refactor standalone.

## Acceptance

See frontmatter. In prose: reconcile the body-cap default to one canonical value;
compare hosts the conservative (canonicalized) way in `isCrossOrigin`; de-dup the
test double; conditionally simplify the response wrapper. Named tests pin the
behavioral changes; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The guard's resolution/blocklist logic and the external
consumers are untouched.

## Notes

- `security_relevant: true` (SSRF module, host-compare + cap); a `/redteam` pass
  is appropriate. T15 and T3 (M1-243) share the "compare hosts/addresses the
  conservative way" theme — treat it as a module-local invariant when touching
  either.
- T8 decision recommendation: 5 MB (the design note value) is the more
  conservative default and the code-only change is the smaller diff; but the
  implementer decides — either is acceptable as long as code and design agree.
</content>
</invoke>
