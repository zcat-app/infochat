---
id: M1-554
title: Dev Services containers Ryuk-reaped — repo-tracked reuse=false, drop dead pom flag
status: pending
created: 2026-07-03
last_updated: 2026-07-03
blocked_by: []
files_budget: 5
files_scope:
  - pom.xml
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-core/src/test/resources/application.properties
  - scripts/verify-serialized.sh
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - removing the verify-serialized.sh in-lock orphan sweep — it stays as the
    backstop for containers whose Ryuk died with a hard-killed docker daemon;
    only its WRONG comment is corrected
  - ~/.testcontainers.properties — host file, operator-owned, already flipped
    to reuse.enable=false on 2026-07-03; this ticket makes the repo correct
    even on a host where that flag is true
  - Testcontainers or Quarkus version changes
  - the two flaky tests the leak-degraded host surfaced on 2026-07-03
    (OutboxRehydratorPaginationIT mid-drain overflow — M1-551's concern;
    StopToolQueryCancellationIT cancel-vs-statement-timeout race)
acceptance:
  - quarkus.datasource.devservices.reuse=false is set in
    infochat-collector/src/main/resources/application.properties and
    infochat-provider/src/main/resources/application.properties (beside the
    existing quarkus.datasource.devservices.* keys) and in
    infochat-core/src/test/resources/application.properties (beside its
    devservices image-name), each with a one-line WHY comment naming the
    Ryuk-exemption mechanism.
  - The two pluginManagement systemPropertyVariables blocks in the parent
    pom.xml that set testcontainers.reuse.enable=false (surefire and
    failsafe, M1-535) are removed together with the comment claiming they
    force reuse off — Testcontainers 1.21.4's
    TestcontainersConfiguration.environmentSupportsReuse() reads only the
    TESTCONTAINERS_REUSE_ENABLE env var or ~/.testcontainers.properties,
    never JVM system properties (bytecode-verified 2026-07-03), so the
    blocks are dead configuration. The surrounding surefire/failsafe
    pluginManagement (versions, failIfNoTests) is preserved.
  - The verify-serialized.sh sweep comment no longer claims "reuse is forced
    off in the parent pom"; it states the real chain (repo-level
    quarkus.datasource.devservices.reuse=false makes containers carry
    org.testcontainers.sessionId and be Ryuk-reaped at session end; the
    sweep remains only for daemon-level failures) in place of the wrong
    sentence.
  - "Manual post-condition proof, recorded in the commit message: with
    ~/.testcontainers.properties temporarily set to reuse.enable=true, one
    full scripts/verify-serialized.sh run leaves ZERO containers matching
    docker ps -aq --filter label=io.quarkus.devservice.launch-mode=TEST
    after mvn exits (repo config alone must defeat the host flag); restore
    the host file to false afterwards."
  - mvn verify is green.
test_plan:
  adds: []
  preserves:
    - the full pre-existing suite (config-only change; the manual
      post-condition proof above is the behavioral check — container
      lifecycle is not observable from inside a JUnit JVM that dies before
      Ryuk acts)
spec_refs:
  - docs/spec/verification.md §Test layers
decision_refs: []
---

## Context

Root-caused 2026-07-03 during M1-553 (evidence in session; summary in the
`clean-verify-monitoring` memory): every full verify leaked ~5 Dev Services
Postgres containers, which sat beside the production compose stack on the
same 15 GB host until the NEXT verify's in-lock sweep reaped them —
degrading the host enough to surface two distinct timing flakes in one
afternoon.

Causal chain, each link verified:

1. `~/.testcontainers.properties` carried `testcontainers.reuse.enable=true`
   (since 2026-06-29).
2. `quarkus.datasource.devservices.reuse` defaults `true`
   (`DevServicesBuildTimeConfig.reuse()` `@WithDefault("true")`, Quarkus
   3.33.1.1), so every dev-services Postgres is REQUESTED reusable.
3. Reuse-requested containers get an `org.testcontainers.hash` label and NO
   `org.testcontainers.sessionId` label (live-inspected mid-run) — and Ryuk
   reaps by sessionId, so they are reaper-exempt BY DESIGN.
4. No reuse ever pays off: the M1-535 in-lock sweep deletes candidates
   before the next mvn starts (and cross-run DB reuse is exactly the Flyway
   drift M1-535 was written against). Pure leak, zero benefit.
5. M1-535's parent-pom `systemPropertyVariables`
   `testcontainers.reuse.enable=false` never worked:
   `environmentSupportsReuse()` consults only the env var or the user
   properties file — JVM system properties are not read (decompiled
   1.21.4 bytecode). Its comment (and the sweep comment citing it) claim
   otherwise; both are corrected by this ticket.

## Fix shape

Belt (repo, this ticket): `quarkus.datasource.devservices.reuse=false` in
the three modules that boot DB dev services — with reuse not requested,
containers carry a sessionId and Ryuk reaps them at session end even for
hard-killed JVMs (Ryuk triggers on heartbeat loss). Braces (host, done):
the operator flipped `~/.testcontainers.properties` to false. The pom
blocks are removed because verified-dead config with a wrong WHY comment
is worse than no config.
