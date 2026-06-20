---
id: M1-414
title: "test: dev-only in-memory adapter terminal harness"
status: done
created: 2026-06-20
last_updated: 2026-06-21
clarity_check:
  date: 2026-06-20
  verdict: WARN
  warnings:
    - "Acceptance item 3 'with the seed flag enabled' names no property key/flag for dev seed loading; activation contract is implementation-defined. (RESOLVED by 2026-06-21 refine: seed flag is infochat.dev.harness.seed.)"
    - "FILES-BUDGET: if the prod-absence test needs a test-resource file or @QuarkusTestProfile outside the two scoped dev/ directories, that path falls outside files_scope. Low likelihood. (RESOLVED by 2026-06-21 refine + spike: both tests run under the normal test profile inside the dev/ package; no out-of-scope resource needed.)"
  blockers: []
escalations:
  - date: 2026-06-21
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — pre-implementation premise-fail surfaced by a start-time spike. The
      ticket's HTTP + @IfBuildProfile("dev") premise is unimplementable in this
      module without violating other premises:
      (1) infochat-provider has NO REST/JAX-RS dependency and NO @Path anywhere;
          adding one would edit pom.xml (outside files_scope) AND place a REST
          engine in the prod jar, contradicting the provider's deliberate
          no-inbound-HTTP ("deaf to calls") design.
      (2) @IfBuildProfile("dev") forces the round-trip IT into the dev profile,
          but the owner datasource the M1-413 seed seam needs is %test-only by
          security design (application.properties:33-35) — boot fails with
          "Unsatisfied dependency for type DataSource qualifiers @DataSource(owner)".
      Spike validated the replacement: a scheduler-driven file harness gated by
      @IfBuildProperty (build-time, io.quarkus.arc.properties) lets both the
      round-trip IT (flag on) and the prod-absence test (flag off) run under the
      normal test profile (owner datasource present). Both spike tests PASSED.
revisions:
  - date: 2026-06-21
    reason: |
      premise-fail refine — retarget the HTTP/@IfBuildProfile("dev") harness to a
      scheduler-driven file harness gated by @IfBuildProperty. Resolves both
      clarity WARNs (seed flag named; tests stay in dev/ under the test profile).
    snapshot:
      acceptance:
        - "A dev-profile-only HTTP resource under app.zcat.infochat.provider.dev (gated with @IfBuildProfile(\"dev\")) exposes endpoints to (a) inject a DM via InMemoryAdapter.deliverDm, (b) inject a group @mention via deliverGroupMention, and (c) return the outbound replies the adapter captured for that injection."
        - "The resource is present only in the dev profile: a test asserts the harness bean/endpoint is NOT registered under the prod (default) profile, so a production build cannot expose the inbound-injection surface."
        - "On dev startup with the seed flag enabled, the harness loads the M1-413 fixture so the injected user's content commands (e.g. /summary, /saved) return the seeded READY posts."
        - "A dev-profile integration test under app.zcat.infochat.provider.dev drives a full register-via-invite -> run a command -> assert the reply round-trip through the endpoint."
        - "mvn -B clean verify from the repo root exits 0."
      out_of_scope_endpoint_item: "Any authentication/authorization on the harness endpoint itself — intentionally none; it is a loopback dev tool that injects raw inbound. Its safety is the dev-profile + inmemory-adapter gate, NOT an auth layer (do not add one)."
      gating_note: '@IfBuildProfile("dev") keeps the resource out of the prod jar; both gates (dev profile + inmemory) must hold.'
  - date: 2026-06-21
    reason: |
      premise-fail refine (continuation, same root cause) — acceptance item 3
      asked the harness (main code) to auto-load the seed on dev startup. That is
      doubly impossible: (1) SeedFixture + its SQL are test-scoped/package-private,
      unreachable from main; (2) seeding posts needs owner privilege, %test-only
      by design — an owner-datasource-injecting observer would break dev boot.
      Retarget: seeding is a TEST concern via the established @SeedDataSource
      pattern (the IT executes the M1-413 SQL resource); the harness has no seed
      feature and the infochat.dev.harness.seed flag is dropped.
    snapshot:
      acceptance_item_3: "When infochat.dev.harness.seed=true, a dev-only startup observer (gated by the same @IfBuildProperty) loads the M1-413 SeedFixture so the injected user's content commands (e.g. /summary, /saved) return the seeded READY posts."
      acceptance_item_4: "A dev-package integration test running under the normal test profile (owner datasource present) with infochat.dev.harness.enabled=true and infochat.dev.harness.seed=true drives a full register-via-invite -> run a content command -> assert the reply round-trip..."
reviews:
  - round: 1
    date: 2026-06-21
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 499
      removed: 42
  - round: 2
    date: 2026-06-21
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 608
      removed: 42
    note: |
      Round 2 = comment-only accuracy fix to the DevTerminalHarness gating javadoc
      (corrects the runtime-gate mechanism per the round-1 redteam out-of-model
      advisory). Diff growth vs round 1 is lifecycle byproducts (redteam audit
      file + review/redteam frontmatter); removed held equal, so must-shrink does
      not fire. r2 mvn verify green (first attempt OOM-flaked on exhausted host
      swap; retried clean).
redteam_findings: []
redteam_audits:
  - date: 2026-06-21
    verdict: CLEAN
    base: f6b8b82625ad6c511923bd5be8370db94e6a4de2
    head: working-tree (pre-commit --in-progress)
    verdict_file: docs/plan/m1/redteam/M1-414-2026-06-21.md
    out_of_model_count: 2
    note: |
      Pre-commit (--in-progress) adversarial audit. CLEAN — 0 findings. The
      harness bypasses transport identity (in-memory adapter) but not the
      authorization pipeline, and is double-gated (@IfBuildProperty +
      infochat.adapters=inmemory) out of any production build. 2 advisory
      out-of-model items recorded in the verdict file (no action required).
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
  - Production adapters (SimpleX, Signal) and the AdapterRegistry gate — unchanged; the harness is reachable ONLY when infochat.adapters=inmemory AND the build sets infochat.dev.harness.enabled=true.
  - Any authentication/authorization on the harness itself — intentionally none; it is a local dev tool that injects raw inbound. Its safety is the build-time @IfBuildProperty gate + the inmemory-adapter gate, NOT an auth layer (do not add one).
  - Any HTTP/REST/JAX-RS surface — explicitly NOT added; the provider has no inbound HTTP surface by design ("deaf to calls") and this ticket must not introduce one (no new REST dependency, no @Path, no pom.xml change). The harness transport is file-based, driven by the existing quarkus-scheduler.
  - The seed fixture contents (M1-413) — reused as-is; this ticket loads it, it does not redefine it.
acceptance:
  - "A dev-only message-injection harness lives under app.zcat.infochat.provider.dev, gated by a build-time property (@IfBuildProperty(name=\"infochat.dev.harness.enabled\", stringValue=\"true\") from io.quarkus.arc.properties; enableIfMissing=false). A @Scheduled poller reads newline-delimited directives from a configured input file — `dm <contactId> <text>` and `group <groupId> <senderContactId> <text>` — injects each via InMemoryAdapter.deliverDm / deliverGroupMention, captures the replies the adapter recorded for that injection (both adapter.sentMessages() for direct replies and adapter.finalizedBodies() for progress-notifier-delivered replies such as /summary), and appends the captured reply text to a configured output file. No HTTP/REST surface is added; no new dependency (quarkus-scheduler is already a provider dependency). Config keys (infochat.dev.harness.input-file / .output-file / .poll-interval) use inlined @ConfigProperty defaults so no application.properties edit is needed."
  - "Prod-absence is a build-time guarantee: a test in the dev package, running under the normal test profile WITHOUT infochat.dev.harness.enabled, asserts the harness bean is unresolvable (Instance.isResolvable()==false). Because @IfBuildProperty(enableIfMissing=false) is a build-time gate, a production build that does not set the flag cannot contain the inbound-injection surface."
  - "Seeding is a TEST concern, not a harness feature (the established ~130-IT pattern): the round-trip IT fills the DB with the M1-413 fixture via the owner-role @SeedDataSource by executing the /fixtures/seed-ready-posts.sql resource, so the harness-driven content command returns the seeded READY posts. The harness performs NO seeding and injects NO datasource — seeding posts needs owner privilege, which exists only under %test by design (application.properties), so app-side seeding cannot work in real dev mode. Interactive dev seeding is a documented manual step (doc follow-up, out of budget)."
  - "A dev-package integration test running under the normal test profile (owner datasource present) with infochat.dev.harness.enabled=true seeds via @SeedDataSource, then drives a full register-via-invite -> run a content command -> assert the reply round-trip. It exercises the real file transport by writing directives to the harness input file and invoking the poll cycle directly (not waiting on the @Scheduled timer), then asserts the captured replies in the output file."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/dev (DevTerminalHarness scheduler/file bridge — no seeding, no datasource)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/dev (round-trip IT seeding via @SeedDataSource + prod-absence test)
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
ticket adds a dev-only **file-driven** bridge so an operator/developer can
exercise the real pipeline from a terminal (append directives to a file, tail an
output file) before standing up a real adapter. Origin:
`docs/testing/USER_TEST_PLAN.md` deliverable #3; it is the fastest path for the
provider-side cases in `docs/testing/adversarial-input-kit.md`.

The transport is deliberately NOT HTTP. The provider has no inbound HTTP surface
by design (only loopback health probes; no REST/JAX-RS dependency) and must stay
"deaf to calls". An HTTP harness would add a REST engine to the prod jar and edit
`pom.xml` (outside `files_scope`). Instead the harness rides the existing
`quarkus-scheduler`: a `@Scheduled` poller tails an input file and writes replies
to an output file — no new dependency, no listening socket. (See the
`escalations:`/`revisions:` entries for the start-time spike that established
this, including why `@IfBuildProfile("dev")` is unusable here: it forces the
round-trip IT into the dev profile, where the `%test`-only owner datasource the
M1-413 seed seam needs is absent.)

## Acceptance

See frontmatter. A `@IfBuildProperty`-gated `@Scheduled` file harness injects
DM / group inbound through the in-memory adapter and writes captured replies to a
file; it is proven absent when the flag is unset (build-time guarantee); it loads
the M1-413 seed when `infochat.dev.harness.seed=true`; an IT drives a
register → command → reply round-trip through the file transport. Full
`mvn verify` green.

## Out-of-scope

See frontmatter. The harness adds no auth (its safety is the build-time
`@IfBuildProperty` gate + the inmemory gate), adds no HTTP/REST surface, does not
touch the in-memory adapter SPI or the production adapters, and does not redefine
the seed fixture.

## Notes

- **Why security_relevant.** The harness injects inbound under an arbitrary
  contact id, bypassing the adapter's cryptographic identity layer (trust
  boundary 1) — but NOT the authorization logic: an injected directive still
  flows through ban check, invite gate, probation, and per-(user,scope)
  isolation. That is exactly what a test seam needs and exactly what must NEVER
  ship in prod. The redteam lens here is narrow: confirm the
  `@IfBuildProperty(infochat.dev.harness.enabled)` + `infochat.adapters=inmemory`
  gates cannot be reached in a production build or deployment shape (the
  prod-absence test is the structural proof; the review confirms there is no
  other activation path).
- Gating (defense in depth): `@IfBuildProperty(name="infochat.dev.harness.enabled",
  stringValue="true")` with `enableIfMissing=false` keeps the bean out of any
  build that does not set the flag (prod does not); `AdapterRegistry` already
  requires `infochat.adapters=inmemory` for the in-memory bean to be active
  (decision D46). Both gates must hold. `@IfBuildProfile("dev")` was rejected —
  it is incompatible with the `%test`-only owner datasource (spike, see
  `escalations:`).
- Adjacent pattern: the `*RoundtripIT` tests under
  `infochat-provider/src/test/java/app/zcat/infochat/provider/messaging` show how
  tests already call `deliverDm` and read `sentMessages()`; the harness wraps that
  same shape behind a file poller. Their `RoundtripProfile`
  (`infochat.adapters=inmemory` + `allow-low-trust`) is the template for this
  ticket's IT profile, plus `infochat.dev.harness.enabled=true`.
- Seeding is test-side, NOT a harness feature (matches the ~130-IT
  `@SeedDataSource` pattern). The harness injects no datasource — app-side
  seeding needs owner privilege, which is `%test`-only by design. The IT seeds by
  executing the M1-413 `/fixtures/seed-ready-posts.sql` resource on the owner-role
  `@SeedDataSource` (the SQL resource is reachable from any test package, so
  `SeedFixture`'s package-private visibility is not in the way and M1-413's files
  are untouched).
- Keep the round-trip IT deterministic: invoke the poll cycle directly rather
  than waiting on the `@Scheduled` timer.
- Document the file-harness usage in `docs/testing/USER_TEST_PLAN.md` §Phase 3
  once the directive shape is final (doc follow-up, not part of this ticket's
  budget).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-414-*.md
```
