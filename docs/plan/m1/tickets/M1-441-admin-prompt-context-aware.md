---
id: M1-441
title: "Wizard bootstrap-admin prompt must not say 'optional' when it is the only adapter"
status: pending
created: 2026-06-24
last_updated: 2026-06-25
blocked_by:
  - M1-445
files_budget: 3
files_scope:
  - prod/scripts/6-adapter.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/AdapterAdminPromptWiringTest.java
  - SETUP_GUIDE.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "Do NOT change the union gate at 6-adapter.sh:216 or the per-adapter optionality of the property itself (design §7.6.3): the property stays optional per adapter, union non-empty. This ticket only fixes the PROMPT so a single-adapter operator is not told 'optional; blank for none' when leaving it blank is guaranteed to fail the gate seconds later. The gate remains the load-bearing backstop."
  - "Do NOT change the `collect_admin` skip path (:110-112): an already-set admin still short-circuits without re-prompting."
  - "No change to how the bootstrap-admin value is parsed/validated (M1-208) or written/escaped (M1-389/M1-397/M1-399)."
  - "No change to `--defaults` mode's adapter selection; it already takes DEFAULT_ADAPTERS=simplex (a single adapter) and still pauses for human-supplied registration values, so the required-admin behavior applies to it identically."
  - "No design/spec edit: design §7.6.3 ('optional per adapter, union non-empty') is correct and stays verbatim — the defect is wizard UX wording, not the documented property contract."
acceptance:
  - "In prod/scripts/6-adapter.sh, when exactly one adapter is enabled (`${#chosen[@]} -eq 1`), that adapter's bootstrap-admin prompt is REQUIRED: a blank entry re-prompts (with a one-line reason that this is the only enabled adapter, so its admin is the deployment's sole admin and last-admin protection needs it) instead of being accepted as 'none' and silently leaving the union empty. The operator gets immediate, local feedback at the prompt rather than the late `FAIL: no bootstrap admin...` gate (:216)."
  - "When two or more adapters are enabled, each adapter's prompt stays per-adapter optional (matching design §7.6.3), but the wording no longer reads a flat '(optional; blank for none)'. It states that at least one bootstrap admin across the enabled adapters is required, so a blank here is acceptable only if another enabled adapter supplies one."
  - "The union gate at 6-adapter.sh:216 is unchanged and still aborts when every enabled adapter is left blank (the multi-adapter all-blank case the per-prompt requirement does not catch). The `collect_admin` already-set skip path (:110-112) is unchanged."
  - "A new wiring test infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/AdapterAdminPromptWiringTest.java drives the real prod/scripts/6-adapter.sh (following the M1-439 DoctorWiringTest harness: a temp INFOCHAT_RUNTIME_DIR plus scripted stdin), asserting: (a) single adapter + a blank admin re-prompts and does NOT fall through to the union FAIL on the first blank; (b) single adapter + a valid admin records INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID and proceeds to write the adapters config; (c) two adapters both blank still hits the union FAIL gate (backstop preserved)."
  - "SETUP_GUIDE.md step-6 description (lines ~145, ~248) is checked and, only where it implies the admin contact id is skippable on the documented single-adapter happy path, clarified to read as required-on-the-happy-path; if the existing wording already presents it as a value to paste, leave it unchanged and note so in the commit."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/AdapterAdminPromptWiringTest.java — single-adapter blank re-prompts; single-adapter valid records key + proceeds; multi-adapter all-blank still hits union FAIL."
  preserves:
    - all tests currently green on main
    - "SimpleXProvisioningWiringTest (drives 6b-simplex-provision.sh; the 6-adapter prompt change is upstream of it and must not break it)"
    - "DoctorWiringTest (shares the shell-wiring harness pattern this test follows; untouched)"
spec_refs:
  - docs/design/07-deployment.md §7.6.3 Bootstrap admin (per-adapter; optional per-adapter, union non-empty)
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard
  - docs/spec/deployment.md §Operator inputs
decision_refs: []
reviews: []
escalations:
  - date: 2026-06-24
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A (developer-raised premise-fail; no reviewer round reached). Two
      pre-existing blockers, neither caused by M1-441, prevent honest completion
      as written:
      (A) Root `mvn verify` is RED on main — collector ReEvaluationJobScheduledPathIT
          time-bomb (fixed-date seed aged past the 32d scan window on 2026-06-24).
          Filed as M1-444. Blocks acceptance item 6 (mvn verify exits 0).
      (B) The mandated *Test wiring test would never execute: nothing pins
          maven-surefire-plugin, so it defaults to super-pom 2.12.4 (Maven 3.8.7),
          which discovers 0 JUnit 5 tests. The whole unit suite (~1000 tests,
          incl. M1-439's DoctorWiringTest) is silently dormant. Filed as M1-445.
          Without it the *Test compiles but never runs (a fake green).
      The wizard code fix is complete and manually verified, and the wiring test
      passes 3/3 once surefire is pinned (verified under 3.5.4). Parked on branch
      parked/M1-441-wizard-fix @ d7f9fe59. Deferred behind M1-445 (which is itself
      blocked_by M1-444); reopen and cherry-pick the parked commit once both land.
revisions: []
overrides: []
aborted_attempts: []
reopens:
  - date: 2026-06-25
    prior_deferred_reason: blocked-on-new-ticket
    prior_deferred_on: M1-445
    reason: "Blockers M1-444 and M1-445 both landed (done); parked fix d7f9fe59 ready to cherry-pick."
redteam_findings: []
clarity_check:
  date: 2026-06-24
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 5 (SETUP_GUIDE.md check): criterion delegates pass/fail to the implementer's reading of whether existing wording implies the admin id is skippable. Resolved during the parked implementation: SETUP_GUIDE.md already presents the admin id as required-on-the-happy-path (line ~145 'You must provide at least one'; line ~248 a value to paste), so it is left unchanged per the criterion's own branch."
  blockers: []
---

# M1-441: bootstrap-admin prompt must not read "optional" on the only adapter

## Context

The 2026-06-23 setup-wizard audit (sibling of M1-439 / M1-440) flagged a
misleading prompt in wizard step 6. `6-adapter.sh:114` asks:

```
Bootstrap admin contact id for ${adapter} (optional; blank for none):
```

The word "optional" is locally true — the property is optional *per adapter*
(design §7.6.3) — but the deployment-wide requirement is that the **union of
bootstrap admins across enabled adapters is non-empty**, enforced by the gate at
`6-adapter.sh:216`. On the **documented happy path** the operator enables a
single adapter (`DEFAULT_ADAPTERS="simplex"`, and the SETUP_GUIDE worked example
uses simplex only). For that single-adapter case "optional; blank for none" is
actively wrong: pressing Enter is guaranteed to trip `FAIL: no bootstrap admin
contact id was supplied for any chosen adapter` a few prompts later. The
operator is told it is skippable, then the wizard aborts for skipping it.

Operator-confirmed direction (this session): fix the **prompt**, not the gate.
When only one adapter is enabled its admin is effectively required, so prompt it
as required with immediate re-prompt feedback; when 2+ adapters are enabled keep
per-adapter optionality but word it as "at least one across adapters." The union
gate stays as the backstop for the multi-adapter all-blank case.

## Notes (verified 2026-06-24)

- The script is fully driveable for a wiring test: `INFOCHAT_RUNTIME_DIR`
  (:13) redirects CONFIG_FILE/SECRETS_FILE to a temp dir, and adapter selection
  + admin ids are read from stdin — same harness shape as M1-439's
  DoctorWiringTest (`infochat-provider/.../wiring/`).
- `collect_admin` (:108-125) does not currently know how many adapters were
  chosen; the required-vs-optional decision can key off `${#chosen[@]}` (known
  at :150) — pass it in, or branch in the caller. Implementer's choice.
- Design §7.6.3 and the §7.4 example comment ("Per-adapter optional; only the
  union ... MUST be non-empty") are CORRECT and must stay verbatim — the defect
  is wizard wording, not the property contract. No design edit in scope.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-441-admin-prompt-context-aware.md
```
