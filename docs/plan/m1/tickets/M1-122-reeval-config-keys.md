---
id: M1-122
title: "infochat.reeval.* keys in main config + @ConfigProperty CI guard"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 5
files_scope:
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector
  - scripts
complexity: low
risk: high
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-provider/** — no provider changes
  - infochat-collector/src/test/resources/application.properties — the %test values already exist; do not duplicate-edit
  - changing any @ConfigProperty consumer's default-less declaration into a defaultValue (the keys belong in config, not inlined defaults)
acceptance:
  - "All nine infochat.reeval.* keys (infra-failure-cap, unknown-cap, needs-review-depth-threshold, poll-interval, unknown-rate-threshold, unknown-rate-window, unknown-tracker-poll-interval, admin-review-ttl, ttl-poll-interval) are declared in infochat-collector/src/main/resources/application.properties with per-profile overrides per docs/design/04-security.md"
  - "The collector boots in the laptop/vps/pi/remote-llm profiles without a NoSuchElementException or scheduler-config-parse failure (covered by a config-resolution test that loads each profile)"
  - "A CI guard (script) asserts every @ConfigProperty(name = \"infochat.*\") in main sources resolves to a base declaration in main config; the guard fails when a key is test-only"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Re-evaluation job
  - docs/spec/deployment.md §Configuration surface (spec level)
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-122: infochat.reeval.* keys in main config + @ConfigProperty CI guard

## Context

Nine `infochat.reeval.*` config keys live **only** in
`infochat-collector/src/test/resources/application.properties`. The consuming
`@ConfigProperty` declarations (`ReEvaluationJob`, `PerSourceUnknownTracker`,
`AdminReviewTtlJob`) carry no `defaultValue`, and
`@Scheduled(every="{infochat.reeval.poll-interval}")` fails at
scheduler-config-parse. **Collector startup fails in every operator profile**
(laptop, vps, pi, remote-llm); only `%test` boots. The entire re-evaluation
policy is dead until the keys land.

## Acceptance

See frontmatter. Add the nine keys to main config with per-profile overrides
sourced from `docs/design/04-security.md`. Add a CI guard that prevents the
class of bug recurring: every `@ConfigProperty(name="infochat.*")` in main
sources must have a base declaration in main config.

## Out-of-scope

See frontmatter. Do not convert default-less `@ConfigProperty` declarations
into inlined `defaultValue`s — the values belong in config so operators can
tune them per profile.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A5 (REEVAL-CONFIG-KEYS, Critical,
  GROUNDED); `opus-47-full-handout.md` §F-MAINT-03; `opus-47-only-handout.md` §TP4.
- Consumers: `ReEvaluationJob.java:74-86`, `PerSourceUnknownTracker.java:41-50`,
  `AdminReviewTtlJob.java:51-57`.
- Suggested baseline (cross-check against `docs/design/04-security.md` — the
  report flags these as suggested, not authoritative): `poll-interval=5m`,
  `unknown-tracker-poll-interval=15m`, `ttl-poll-interval=30m`,
  `infra-failure-cap=5`, `unknown-cap=3`, `needs-review-depth-threshold=100`,
  `unknown-rate-threshold=0.5`, `unknown-rate-window=PT1H`, `admin-review-ttl=PT72H`.
- The CI guard belongs in `scripts/` (mirror the existing `lint-*.py` shape);
  it is not the immediate startup fix but prevents regression.
