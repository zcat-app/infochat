---
id: M1-532
title: "Wizard step 5: optionally syntax-check a custom bootstrap JSON so a typo fails early, not at Collector boot"
status: done
created: 2026-06-30
last_updated: 2026-07-01
blocked_by: []
files_budget: 1
files_scope:
  - prod/scripts/5-bootstrap.sh
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "SCHEMA / tag-pattern validation. The Collector's BootstrapLoader owns schema validation (the controlled-vocabulary tag pattern, kind/identifier shape); duplicating it in bash would violate the no-duplicate-validation principle and rot. This ticket adds at most a JSON SYNTAX gate, never schema checks."
  - "Adding jq to the doctor's REQUIRED_TOOLS or making jq a hard dependency — the gate must be a best-effort no-op when jq is absent."
  - "The bundled-default path (always valid; never re-checked)."
acceptance:
  - >-
    When the operator supplies a custom bootstrap-sources.json or
    bootstrap-assets.json AND `jq` is present on PATH, 5-bootstrap.sh runs
    `jq . "$path" >/dev/null` and, on a parse failure, fails with a clear
    "not valid JSON" message and a non-zero exit BEFORE copying the file —
    surfacing a syntax typo at step 5 instead of at Collector startup (step 8).
  - >-
    When `jq` is NOT present, the gate is skipped silently (a one-line note is
    acceptable) and the script behaves exactly as today — jq is never required.
  - >-
    A syntactically valid custom JSON copies unchanged (happy path unaffected);
    a malformed one (e.g. a trailing comma) fails at step 5 with the script's
    message when jq is available.
  - >-
    `mvn -B clean verify` from the repo root exits 0 (no wiring test pins
    5-bootstrap.sh).
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - "docs/spec/deployment.md §Operator inputs"
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 25
      removed: 9
escalations: []
revisions:
  - date: 2026-07-01
    reason: clarity-fail rework — fix unresolvable spec_refs anchor
    snapshot:
      status: pending
      escalation_reason: clarity-fail
      clarity_check:
        date: 2026-07-01
        verdict: FAIL
        blockers:
          - "SPEC-REFS-VALID: `docs/spec/deployment.md §Bootstrap sources` does not match any heading in that file. The bootstrap sources content lives under `## Operator inputs` (line 56). Fix by changing the spec_ref to `docs/spec/deployment.md §Operator inputs`."
        warnings: []
      spec_refs_at_snapshot:
        - "docs/spec/deployment.md §Bootstrap sources"
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-01
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-532: Optional early JSON-syntax gate for custom bootstrap files

## Context

Verified during the setup-wizard review (flaws.md F12). Step 5 checks a custom
sources/assets file is readable but not that it is valid JSON. The guide notes a
bad tag makes "the Collector refuse to start," so a malformed custom file passes
step 5 and only surfaces at step 8 (health check) — after model downloads and
adapter registration. The correct validation BOUNDARY is the Collector (it owns
schema validation), so this is defensible as-is; the only cheap win is a JSON
SYNTAX gate that catches gross malformed JSON early when `jq` happens to be
available.

This is a judgment-call ticket: it may be closed as wontfix if the reviewer
prefers to keep step 5 free of any validation and rely entirely on the Collector
boundary. The value is a faster feedback loop for the advanced-user custom-file
path; the cost is one conditional block guarded on `command -v jq`.

## Acceptance

See the YAML `acceptance:` list. In prose: when jq is present, `jq .` the custom
file and fail early on a parse error; when jq is absent, behave exactly as today.

## Out-of-scope

See the YAML `out_of_scope:` list. Syntax only — never schema/tag validation,
and never make jq a requirement.

## Notes

- Gate both custom-path branches (sources and assets) symmetrically.
- jq is intentionally NOT in 0-doctor.sh's REQUIRED_TOOLS; do not add it.
