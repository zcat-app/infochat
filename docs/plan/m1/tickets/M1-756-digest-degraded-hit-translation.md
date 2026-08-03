---
id: M1-756
title: "Display-time hit translation for digest headlines and the degraded renderers"
status: pending
created: 2026-08-03
last_updated: 2026-08-03
blocked_by:
  - M1-747
files_budget: 8
files_scope: []
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The /summary, /retry (M1-747) and /saved (M1-755) legs.
  - >-
    Translating bundle-sourced prose. D43's bundle-not-translator
    invariant stands: whatever design this ticket lands may translate
    only the EMBEDDED source-authored fragments (post headlines), never
    the bundle template text around them.
  - >-
    Persisting translated text or changing digest selection/ordering.
acceptance:
  - >-
    DESIGN FIRST: three surfaces render post headlines outside M1-747's
    scope — `DigestRenderer.appendHeadlines` (normal-mode digest headline
    block; scheduled per-group volume is its own cost story),
    `DegradedDigestRenderer`, and
    `SummaryProseGenerator.degradedProseFor`. The two degraded paths
    compose bundle template text AROUND post titles, so translating the
    title means restructuring the composition so only the source-authored
    fragment enters the translator (D43). Decide per surface: translate,
    or explicitly document why not (a degraded render during an LLM
    outage arguably must not add LLM calls — that tension is the design
    call).
  - >-
    Whatever translates goes through M1-747's display-hit pipeline entry
    point — same no-op legs, §10 controls, fallback, cache. Note:
    `DigestPostCollector` constructs `Post` via the compat overload, so
    its rows carry `sourceLanguage = null` (never-translate) until this
    ticket projects the real value.
  - >-
    `en` scope stays byte-identical with zero translator calls, asserted
    with a spy. Digest byte-replay pins (persisted post-sanitize section
    bytes) are preserved or knowingly, explicitly re-pinned.
  - mvn verify from the repo root is green.
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
decision_refs:
  - D29
  - D43
  - D30
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-756: Display-time hit translation for digest headlines and the degraded renderers

## Context

Draft follow-up filed from M1-747's surface-binding rework (2026-08-03).
The digest broadcast's headline block and the two degraded renderers show
source-authored headlines M1-747 leaves untranslated. Split out because
(a) the degraded paths collide with D43's bundle-not-translator invariant
and need a composition redesign, (b) a degraded render exists because an
LLM is failing, and adding translator calls to it is a real design
tension, and (c) scheduled digest volume changes the cost calculus.

## Notes

- Draft: `files_scope`, sizing, and the per-surface decisions need
  filling in before `/m1-tick start`.
