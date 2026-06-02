---
id: M1-146
title: "JSpecify annotation pass + lint-contracts CI + defensive-code sweep (CT4)"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by:
  - M1-133
files_budget: 16
files_scope:
  - infochat-core
  - infochat-llm-adapter
  - infochat-messaging-adapter
  - infochat-ssrf
  - infochat-provider
  - infochat-collector
  - scripts
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - boundary validation (system-boundary null-checks stay per §No-defensive-code — only internal-trust-boundary guards are removed)
  - any behavioral change beyond annotation + dead-guard removal
acceptance:
  - "scripts/lint-contracts.py runs clean across all modules: every public/protected method's reference-type parameter carries @NonNull/@Nullable (JSpecify), and the lint is wired into CI"
  - "Dead defensive null-checks and catch arms between internal classes are removed (LlmRouter ctor/record, SsrfGuardedHttpClient resolver-seam, OpenAiCompatibleProvider apiKey coalesce, AssetSnapshotFetcher catch moved to the outer loop with a distinct error class, dead UserSnapshot.isBanned field, BootstrapAssetsLoader unreachable guard)"
  - "InboundContext.adapterName()/senderContactId() carry @Nullable matching their javadoc; MessagingException constructors are annotated"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Architectural principles
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-146: JSpecify annotation pass + lint-contracts CI + defensive-code sweep (CT4)

## Context

The §7 + §7a engineering-rule pair is violated symmetrically across modules:
many public methods lack the `@NonNull`/`@Nullable` contract
(`scripts/lint-contracts.py` baseline is empty but not CI-enforced), and
multiple modules carry defensive null-checks / `catch (RuntimeException)` arms
guarding scenarios that cannot happen given the internal trust boundary. The
reviewer applies §7 narrowly — boundary validation stays; internal guards go.

## Acceptance

See frontmatter. Run the lint, annotate everything flagged, wire it into CI, and
remove the dead internal guards.

## Out-of-scope

See frontmatter. `blocked_by: M1-133` — overlaps the same handler files the
shared-helper extraction touches; rebase onto it. System-boundary null-checks
are NOT removed.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-JSPECIFY-MISSING, §C-DEFENSIVE-CODE;
  `opus-47-full-handout.md` §F-MAINT-44/45/62/72/76/77/80, CT4; `opus-47-only-handout.md` §M12/14/16, CT2.
- `AssetSnapshotFetcher` catch moves to the outer `runHostTick` loop with a
  distinct error class so it doesn't feed the D42 ladder as an upstream-fetch failure.
