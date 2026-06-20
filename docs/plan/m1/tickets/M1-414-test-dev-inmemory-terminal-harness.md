---
id: M1-414
title: "test: dev-only in-memory adapter terminal harness"
status: pending
created: 2026-06-20
last_updated: 2026-06-20
blocked_by: [M1-413]
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/dev
  - infochat-provider/src/test/java/app/zcat/infochat/provider/dev
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The InMemoryAdapter SPI implementation (infochat-messaging-adapter) — unchanged; the harness injects through its existing deliverDm/deliverGroupMention entry points and reads sentMessages, it does not modify the adapter.
  - Production adapters (SimpleX, Signal) and the AdapterRegistry gate — unchanged; the harness is reachable ONLY when infochat.adapters=inmemory and the build profile is dev.
  - Any authentication/authorization on the harness endpoint itself — intentionally none; it is a loopback dev tool that injects raw inbound. Its safety is the dev-profile + inmemory-adapter gate, NOT an auth layer (do not add one).
  - The seed fixture contents (M1-413) — reused as-is; this ticket loads it, it does not redefine it.
acceptance:
  - "A dev-profile-only HTTP resource under app.zcat.infochat.provider.dev (gated with @IfBuildProfile(\"dev\")) exposes endpoints to (a) inject a DM via InMemoryAdapter.deliverDm, (b) inject a group @mention via deliverGroupMention, and (c) return the outbound replies the adapter captured for that injection."
  - "The resource is present only in the dev profile: a test asserts the harness bean/endpoint is NOT registered under the prod (default) profile, so a production build cannot expose the inbound-injection surface."
  - "On dev startup with the seed flag enabled, the harness loads the M1-413 fixture so the injected user's content commands (e.g. /summary, /saved) return the seeded READY posts."
  - "A dev-profile integration test under app.zcat.infochat.provider.dev drives a full register-via-invite -> run a command -> assert the reply round-trip through the endpoint."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/dev (DevHarnessResource + dev seeder)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/dev (round-trip IT + prod-absence test)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Per-adapter trust level
  - docs/spec/deployment.md §Deployment scenarios
decision_refs:
  - D46
---

# M1-414: dev-only in-memory adapter terminal harness

## Context

The in-memory adapter
(`infochat-messaging-adapter/.../inmemory/InMemoryAdapter.java`) can drive the
entire command/chat/group pipeline with no SimpleX/Signal account, but its
`deliverDm` / `deliverGroupMention` entry points are reachable only from test
code today — there is no way to hand-drive a *running* app through it. This
ticket adds a dev-profile-only HTTP bridge so an operator/developer can exercise
the real pipeline from a terminal (curl/scripts) before standing up a real
adapter. Origin: `docs/testing/USER_TEST_PLAN.md` deliverable #3; it is the
fastest path for the provider-side cases in `docs/testing/adversarial-input-kit.md`.

## Acceptance

See frontmatter. A `@IfBuildProfile("dev")` HTTP resource injects DM / group
inbound through the in-memory adapter and returns captured replies; it is proven
absent in the prod profile; it loads the M1-413 seed on dev startup; an IT drives
a register → command → reply round-trip. Full `mvn verify` green.

## Out-of-scope

See frontmatter. The harness adds no auth (its safety is the dev + inmemory
gate), does not touch the in-memory adapter SPI or the production adapters, and
does not redefine the seed fixture.

## Notes

- **Why security_relevant.** The endpoint injects inbound under an
  arbitrary contact id, bypassing the adapter's cryptographic identity layer
  (trust boundary 1). That is exactly what a test seam needs and exactly what
  must NEVER ship in prod. The redteam lens here is narrow: confirm the
  dev-profile + `infochat.adapters=inmemory` gate cannot be reached in a
  production build or deployment shape (the prod-absence test is the structural
  proof; the review confirms there is no other activation path).
- Gating: `@IfBuildProfile("dev")` keeps the resource out of the prod jar;
  `AdapterRegistry` already requires `infochat.adapters=inmemory` for the
  in-memory bean to be active (decision D46), so both gates must hold.
- Adjacent pattern: the `*RoundtripIT` tests under
  `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging` show how
  tests already call `deliverDm` and read `sentMessages()`; the harness exposes
  that same shape over HTTP.
- Document the curl usage in `docs/testing/USER_TEST_PLAN.md` §Phase 3 once the
  endpoint shape is final (doc follow-up, not part of this ticket's budget).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-414-*.md
```
