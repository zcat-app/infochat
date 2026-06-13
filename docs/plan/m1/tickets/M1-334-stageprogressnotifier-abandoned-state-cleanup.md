---
id: M1-334
title: "StageProgressNotifier: terminate abandoned per-scope state at request end"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/StageProgressNotifier.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundContext.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Changing the ProgressNotifier SPI to a closure shape (run(scope, p -> {...})) — cleaner semantically but touches every caller; the report defers it and so does this ticket.
  - The normal complete()/fail() terminate path and the per-edit update-lag logic — unchanged; this ticket adds a safety net for the abandoned case only.
acceptance:
  - "A publish() that is never followed by complete()/fail() (handler throws, operation abandoned) no longer leaves a live ScopeState in the map with typing stuck ON. At the end of the inbound dispatch, any ScopeState created during that dispatch but not yet terminated is drained: terminate(scope, <localized failed text>) runs, finalizing the placeholder and turning typing OFF via the existing try/finally, so the user is not left with a perpetual typing indicator and the next operation in the same scope starts clean (it no longer hits the stale state.handle != null branch on first publish)."
  - "The cleanup is bound to the request lifecycle (e.g. the @RequestScoped InboundContext's destruction tracks the scopes touched this dispatch and drains them, or an equivalent per-request hook), so it fires exactly once per dispatch regardless of how the handler exited. The StageProgressNotifier class Javadoc's 'never left dangling' claim becomes true for every publish, not only those that reach terminate()."
  - "A test pins the abandoned case: a publish() with no matching complete()/fail() results in setTyping(scope,false) being issued and the states map entry removed by request end; a subsequent publish() in the same scope sends a fresh placeholder rather than updating the stale handle. The normal publish->complete and publish->fail lifecycles are unchanged."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging (abandoned-state cleanup case)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-334: StageProgressNotifier — terminate abandoned per-scope state

## Context

Deep-review v5.5 (opus-47, `07-module-infochat-provider.md` F2) found that
`StageProgressNotifier.publish()` creates per-scope state and turns typing ON,
but the only path that turns typing OFF / removes the map entry is `terminate()`
(called by `complete()`/`fail()`). **Verified at source 2026-06-14:** the bean is
`@ApplicationScoped` (StageProgressNotifier.java:67), `terminate` removes from the
`states` map and is reached only from `complete`/`fail` (lines 172-208); no path
enforces that `terminate` runs for every `publish`.

When a handler throws before its terminal call (or abandons the operation), the
stale `ScopeState` with its live `MessageHandle` persists: the user keeps seeing
the typing indicator until the adapter session resets, and the next legitimate
operation in the same scope hits the `state.handle != null` branch and tries to
update the old placeholder instead of sending a fresh one. The class Javadoc
claims the placeholder "is never left dangling" via try/finally — but the
try/finally is inside `terminate()`, not around the publish→terminate lifecycle.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Report Option A (bean-side cleanup at request end) is preferred. A lighter
  variant: track per-request scope keys in `InboundContext` (a `Set<ScopeRef>` of
  "scopes I touched") and drain them in an `InboundContext` `@PreDestroy`. Either
  keeps the bean application-scoped while guaranteeing terminate runs.
- The friendly failed-state render is exactly the documented `fail()` contract,
  so the abandoned path degrades to the same user-visible outcome as an explicit
  failure.
