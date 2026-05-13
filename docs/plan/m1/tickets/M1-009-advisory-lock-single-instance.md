---
id: M1-009
title: Advisory-lock single-instance enforcement + heartbeat
status: done
created: 2026-05-11
last_updated: 2026-05-13
blocked_by:
  - M1-005
  - M1-006
files_budget: 13
files_scope:
  - infochat-core/src/main/resources/db/migration/V3__heartbeat.sql
  - infochat-collector/src/main/java/io/infochat/collector/startup/InstanceLockGuard.java
  - infochat-collector/src/main/java/io/infochat/collector/startup/HeartbeatScheduler.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/pom.xml
  - infochat-provider/src/main/java/io/infochat/provider/startup/InstanceLockGuard.java
  - infochat-provider/src/main/java/io/infochat/provider/startup/HeartbeatScheduler.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/pom.xml
  - infochat-collector/src/test/java/io/infochat/collector/startup/InstanceLockGuardIT.java
  - infochat-collector/src/test/java/io/infochat/collector/startup/HeartbeatSchedulerIT.java
  - infochat-provider/src/test/java/io/infochat/provider/startup/InstanceLockGuardIT.java
  - infochat-provider/src/test/java/io/infochat/provider/startup/HeartbeatSchedulerIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - any change to V1__init.sql or V2__roles.sql (those are committed; V3 is the only new migration)
  - any GRANT against any table other than `heartbeat` (per-entity-table grants ride with M1-008 subtickets)
  - any change to the three role principals from M1-006 (no new role, no role attribute change)
  - any `provider_state` or `collector_state` table (the spec mentions `provider_state` conceptually — the M1-008 schema umbrella owns that table; M1-009 introduces ONLY the `heartbeat` table)
  - any high-water-mark column / NewPostReconciler logic (M1-008 + provider-side reconciler ticket)
  - any LISTEN/NOTIFY channel registration (no `new_post` / `new_price_snapshot` / `quarantine_review` channel work — those land with their producing tickets)
  - any messaging adapter, scheduler-task other than the heartbeat tick, Flyway/datasource change, or LLM wiring
  - any `RestartPreventExitStatus` systemd unit file or scripts/ wrapper (the design ties exit code 42 to a unit file; the unit-file ticket lands with the operator-tooling slice and is not this ticket's responsibility — M1-009 commits only to System.exit(1) on lock-conflict, see Implementation notes)
  - any production code under `infochat-collector/src/main/java/` or `infochat-provider/src/main/java/` outside the two `startup/` packages listed in files_scope
acceptance:
  - "infochat-core/src/main/resources/db/migration/V3__heartbeat.sql exists"
  - "V3__heartbeat.sql creates a `heartbeat` table with columns `service` (text, PRIMARY KEY), `host_id` (text NOT NULL), `pid` (integer NOT NULL), `last_seen_at` (timestamptz NOT NULL DEFAULT now()) — grep -E 'CREATE TABLE.*heartbeat' returns at least one match; grep -E 'PRIMARY KEY' returns at least one match; grep -E '\\bservice\\b' AND grep -E '\\bhost_id\\b' AND grep -E '\\bpid\\b' AND grep -E '\\blast_seen_at\\b' each return at least one match"
  - "V3__heartbeat.sql grants SELECT, INSERT, UPDATE on `heartbeat` to BOTH `infochat_collector` AND `infochat_provider` (grep -E 'GRANT .*ON heartbeat.* TO infochat_collector' returns at least one match; same for `infochat_provider`)"
  - "V3__heartbeat.sql does NOT grant DELETE on `heartbeat` to either application role (grep -iE 'GRANT.*DELETE.*ON heartbeat.*TO infochat_(collector|provider)' returns zero matches — only `infochat_admin` may delete heartbeat rows; the application code only INSERT-on-first-tick + UPDATE-on-subsequent-ticks)"
  - "infochat-collector/src/main/java/io/infochat/collector/startup/InstanceLockGuard.java exists, is annotated `@Startup` with `@Priority(50)` (matching docs/design/01-architecture.md §1.4.3 Collector table), and calls `pg_try_advisory_lock` with the hashtext-of-'infochat.collector' (grep -E 'pg_try_advisory_lock' returns at least one match; grep -E 'infochat\\.collector' returns at least one match; grep -E '@Priority\\(50\\)' returns at least one match)"
  - "infochat-provider/src/main/java/io/infochat/provider/startup/InstanceLockGuard.java exists, is annotated `@Startup` with `@Priority(50)`, and calls `pg_try_advisory_lock` with the hashtext-of-'infochat.provider' (mirror checks for the provider name)"
  - "BOTH InstanceLockGuard files: on lock-acquisition failure, log a fatal-level message that names the running instance's host_id, pid, and last_seen_at read from the heartbeat row, then call `Quarkus.asyncExit(1)` (grep -E 'asyncExit' returns at least one match per file; grep -E 'host_id|hostId' returns at least one match per file; grep -E 'last_seen_at|lastSeenAt' returns at least one match per file)"
  - "infochat-collector/src/main/java/io/infochat/collector/startup/HeartbeatScheduler.java exists, contains a method annotated `@Scheduled` with `every` configured from a property (grep -E '@Scheduled' returns at least one match; grep -E 'every' returns at least one match; grep -E 'infochat\\.heartbeat\\.interval' returns at least one match somewhere in the file's `every` expression or in a constant the annotation references)"
  - "infochat-provider/src/main/java/io/infochat/provider/startup/HeartbeatScheduler.java exists with the equivalent @Scheduled tick body"
  - "infochat-collector/src/main/resources/application.properties declares `infochat.heartbeat.interval` under the `%laptop` profile namespace (grep -E '^%laptop\\.infochat\\.heartbeat\\.interval' returns at least one match) — value is a duration like `5s`; the other three profile namespaces (`%vps`, `%pi`, `%remote-llm`) may carry the key now or defer to their feature tickets, see Implementation notes"
  - "infochat-provider/src/main/resources/application.properties declares the same `%laptop.infochat.heartbeat.interval` key"
  - "infochat-provider/pom.xml adds `quarkus-scheduler` (grep -E '<artifactId>quarkus-scheduler</artifactId>' returns at least one match) — M1-005's Provider pom did not include it"
  - "infochat-collector/pom.xml adds `quarkus-scheduler` (grep -E '<artifactId>quarkus-scheduler</artifactId>' returns at least one match) — M1-005's Collector pom did not include it either"
  - "mvn -B verify from the repo root exits 0; the new IT (see test_plan) confirms (a) a single instance acquires the lock and writes its heartbeat row, and (b) a second test-time guard instance — simulated by calling the lock-acquisition routine twice in the same test JVM with two separate JDBC connections — observes false from the second attempt and logs the contention message"
  - "V3 application is asserted indirectly by the four new ITs (collector and provider InstanceLockGuardIT + HeartbeatSchedulerIT): each performs SELECT/INSERT/UPDATE against `heartbeat`, operations that succeed only when V3 has applied to the test DevServices Postgres. `mvn -B verify` exiting 0 (acceptance #14) is the runnable verification — the explicit grep on `Migrated.*successfully.*V3` in `surefire-reports/` from the prior revision was mechanically unsatisfiable (surefire does not redirect stdout to per-test files by default, and Flyway's actual log lines are `Migrating schema ... to version \"3 - heartbeat\"` and `Successfully applied N migrations`, neither matching the regex)"
test_plan:
  adds:
    - infochat-collector/src/test/java/io/infochat/collector/startup/InstanceLockGuardIT.java (@QuarkusTest: asserts on first guard activation the heartbeat row appears with this JVM's host_id/pid; calls `pg_try_advisory_lock` a second time via a fresh JDBC connection from the same test JVM and asserts the second call returns false; captures the log line and asserts it names the current holder's host_id and pid)
    - infochat-collector/src/test/java/io/infochat/collector/startup/HeartbeatSchedulerIT.java (@QuarkusTest: asserts last_seen_at advances after waiting one heartbeat interval — uses the laptop profile's small `5s` interval so the test runs in well under one minute)
    - infochat-provider/src/test/java/io/infochat/provider/startup/InstanceLockGuardIT.java (provider-side mirror, separate hash, separate row)
    - infochat-provider/src/test/java/io/infochat/provider/startup/HeartbeatSchedulerIT.java (provider-side mirror)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-collector/src/test/java/io/infochat/collector/config/InfochatProfileTest.java (M1-005)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-005)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/config/InfochatProfileTest.java (M1-005)
spec_refs:
  - docs/spec/architecture.md §Deployment topology (v1)
  - docs/design/01-architecture.md §1.4.3 Startup-bean ordering and single-instance enforcement
  - docs/design/07-deployment.md §7.8.5 Single-instance enforcement (`pg_advisory_lock` + heartbeat)
decision_refs:
  - D41

reviews:
  - round: 1
    date: 2026-05-13
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 1015
      removed: 43
escalations:
  - date: 2026-05-12
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — developer-detected. During implementation it surfaced that
      Provider's @QuarkusTest DevServices container has no `heartbeat`
      table: Provider has no `quarkus-flyway` (deliberate — see Provider
      pom comment), and Quarkus 3.33's DevServices does not expose a
      `shared`/`service-name` knob for cross-module container reuse
      (only `reuse`, which depends on user-machine Testcontainers
      config and is not portable). The ticket's premise — that the
      Provider's @Startup InstanceLockGuard could acquire the lock and
      upsert the heartbeat row in test mode without any precursor
      migration-relocation work — turned out to require either a
      duplicated test-fixture SQL file (schema-drift hack) or an
      inline @QuarkusTestResource (DevServices reimplementation).
      Sustainable resolution: relocate Flyway migrations from
      `infochat-collector` to `infochat-core` so both modules' test
      classpaths see them, then re-attempt M1-009. The relocation
      work was deferred-in-prose by M1-005 ("Cleaner to land
      Collector-owned migrations now, move them after M1-007a as a
      small follow-up") and M1-007a (out_of_scope line 30: "the
      migration-move-into-core follow-up is a SEPARATE ticket filed
      once M1-007a lands"); the follow-up ticket was never actually
      filed. M1-017 now captures that work; M1-018 captures the
      process-improvement meta-lesson (clarity check should validate
      forward references to ticket IDs).
  - date: 2026-05-12
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      files_scope omits infochat-collector/pom.xml, but acceptance item 13
      requires adding quarkus-scheduler to that pom — and grep confirms
      quarkus-scheduler is absent from the collector module. Fix: add
      infochat-collector/pom.xml to files_scope and set files_budget: 9.
  - date: 2026-05-12
    reason: clarity-warn
    reviewer_verdict_excerpt: |
      FILES-BUDGET-PLAUSIBLE: WARN — files_scope omits the 4 integration
      test files required by acceptance item 14 and test_plan.adds.
      files_budget (9) is under-counted by 4. The reviewer's negative-space
      check cannot fire if a test file is missing. Recommended fix: add the
      4 test paths to files_scope (collector and provider
      InstanceLockGuardIT.java and HeartbeatSchedulerIT.java) and set
      files_budget: 13.
revisions:
  - date: 2026-05-12
    reason: clarity-fail refinement
    summary: |
      - files_scope: added infochat-collector/pom.xml (clarity blocker — M1-005's
        collector pom does not carry quarkus-scheduler, so acceptance item 13
        requires touching this file).
      - files_budget: 8 → 9 to match the expanded files_scope.
      - acceptance item 13: dropped the "ALREADY contains ... OR this ticket
        adds it" conditional; grep at current HEAD confirms quarkus-scheduler
        is absent from the collector pom, so the conditional always resolved
        to "this ticket adds it".
      - Definition of Done: the quarkus-scheduler bullet now lists both module
        poms as targets rather than treating the collector touch as conditional.
      - Big-picture notes: removed the "internal design inconsistency to note"
        bullet documenting the §1.4.3 vs §7.8.5 mismatch; that mismatch was
        reconciled by a manual docs edit on main (commits alongside this
        refine) so both sections now agree on heartbeat(service, host_id, pid,
        last_seen_at) with hashtext() routine.
      - Big-picture notes: trimmed the "provider_state is a separate concept"
        bullet's trailing sentence pointing at the resolved inconsistency.
      - Implementation notes: trimmed the "Hash function: hashtext, not
        SHA-256" justification paragraph (design now specifies hashtext
        directly, so it is no longer an "alternatives considered" item).
      - Alternatives considered: removed the "single heartbeat vs separate
        per-service tables" bullet (design now specifies the single-table
        form).
  - date: 2026-05-12
    reason: clarity-warn refinement
    summary: |
      - files_scope: added the 4 integration test files required by
        acceptance item 14 and test_plan.adds (collector and provider
        InstanceLockGuardIT.java + HeartbeatSchedulerIT.java). The clarity
        WARN flagged that the reviewer's negative-space check cannot fire
        on a missed IT file if those paths are not in files_scope.
      - files_budget: 9 → 13 to match the expanded files_scope (9
        production files + 4 IT files).
      - No body changes; the test files were already named in test_plan.adds
        and Definition of Done — only files_scope and files_budget needed to
        catch up.
  - date: 2026-05-13
    reason: post-M1-017 reopen — V3 migration path realignment
    summary: |
      - files_scope: V3__heartbeat.sql path changed from
        infochat-collector/src/main/resources/db/migration/ to
        infochat-core/src/main/resources/db/migration/. M1-017 (landed
        post-defer) relocated all Flyway migrations into infochat-core so
        both modules' Quarkus apps see them on classpath. Leaving V3 in
        the collector module would leave Provider's tests without the
        heartbeat schema — exactly the premise-fail that caused the
        original defer.
      - acceptance item 1: path updated to match the new files_scope entry.
      - files_budget: unchanged (still 13 — same file count, only path moved).
      - Acceptance item 15 ("after `mvn -pl infochat-collector test`, grep
        ... 'Migrated.*successfully.*V3' in surefire-reports") is unchanged:
        the collector still runs Flyway against migrations on classpath
        (now sourced from infochat-core via the module dependency), so the
        log line still appears in the collector's surefire reports.
      - Out-of-scope expansion line about V1/V2 — left as-is. V4__nologin
        from M1-016 also exists in infochat-core now; not listed in
        out_of_scope but unambiguously not this ticket's concern.
      - No clarity_check re-run required by this targeted refinement; the
        upcoming /m1-tick start will run a fresh clarity pre-flight against
        the corrected frontmatter.
  - date: 2026-05-13
    reason: criterion #15 unsatisfiable — mid-implementation refine
    summary: |
      - acceptance item 15 rewritten. The prior form required `grep -rE
        'Migrated.*successfully.*V3' infochat-collector/target/surefire-
        reports/ returns at least one match`. That is mechanically
        unsatisfiable for two independent reasons:
          (1) Surefire does not redirect test stdout to per-test files
              by default — the surefire-reports/ directory contains only
              the summary text and the structured XML; Flyway INFO log
              lines stay on the build console and never reach the
              reports directory.
          (2) Flyway 12 logs `Migrating schema "public" to version "3 -
              heartbeat"` (present-progressive "Migrating", not past
              "Migrated") and `Successfully applied N migrations to
              schema "public", now at version v4` (lowercase "v4", not
              uppercase "V3"). Neither line matches the regex
              `Migrated.*successfully.*V3` case-sensitively.
      - Replaced with a runnable assertion: V3 application is implicit
        in the four new ITs (collector + provider InstanceLockGuardIT
        and HeartbeatSchedulerIT), each of which SELECT/INSERT/UPDATE
        against the `heartbeat` table. Those operations succeed only
        when V3 has applied. `mvn -B verify` exiting 0 (acceptance #14)
        is the existing runnable verification.
      - No body changes, no files_scope changes, no files_budget
        changes. Only the acceptance string changed.
      - Discovered mid-implementation after `mvn -B verify` produced
        BUILD SUCCESS but the literal grep in #15 returned no matches
        in `target/surefire-reports/`. The clarity pre-flight did not
        catch this because the reviewer does not run the proposed
        commands; the defect surfaced only on first execution.
overrides: []
aborted_attempts: []
reopens:
  - date: 2026-05-13
    prior_deferred_reason: blocked-on-new-ticket
    prior_deferred_on: M1-017
    reason: M1-017 landed — Flyway migrations relocated to infochat-core
redteam_findings:
  - date: 2026-05-13
    category: AUDIT-EVASION
    severity: low
    promise: |
      "The split means a SQL-injection bug in the Provider cannot delete
      posts, mutate price snapshots, alter quarantine entries, read
      unredacted audit rows, or read raw quarantine originals."
      (security.md §DB roles) — establishing least-privilege role
      separation as a structural defense so a single-service compromise
      cannot tamper with other services' state.
    gap: |
      V3__heartbeat.sql lines 33–34 grant SELECT, INSERT, UPDATE on the
      entire `heartbeat` table to BOTH infochat_collector AND
      infochat_provider. The Provider role can UPDATE the Collector's
      row and vice versa; no row-level partitioning, no policy
      restricting each role to its own `service` value. The heartbeat
      row is the operator-visible "who is currently running" fingerprint
      that a rejected second instance reads to identify the live
      holder (InstanceLockGuard.readHolder + logContention).
    repro: |
      Adversary obtains SQL injection on the Provider. The Provider
      role can `UPDATE heartbeat SET host_id='fake-host', pid=12345
      WHERE service='collector'`. A subsequent restart of the Collector
      that loses the lock race reads the tampered row and emits a fatal
      log line naming a falsified holder, masking the real Collector's
      identity from the operator. The advisory lock itself remains
      intact, but the operator's diagnostic surface — the only
      audit-style record produced by the single-instance gate — has
      been silently falsified by a different service's compromise,
      violating the role-separation promise.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-05-13
    category: INJECTION
    severity: low
    promise: |
      "Contact IDs are logged in redacted form ... User-content logging
      ... never appear in non-audit logs, at any log level (decision
      D37)." (security.md §Secrets handling) — establishing log content
      as a controlled surface where adversary-controlled bytes do not
      flow verbatim into operator-readable logs.
    gap: |
      InstanceLockGuard.java (both modules) composes the fatal
      contention log via `String.format("...host_id='%s' pid=%d
      last_seen_at=%s", h.hostId(), h.pid(), h.lastSeenAt())` where
      h.hostId() is the raw `host_id` text column value from heartbeat.
      Any actor with UPDATE on heartbeat (i.e., either application role
      — see prior finding) can inject newline / ANSI / log-format
      bytes into host_id; the value is interpolated into the fatal log
      line without sanitization or quoting normalization.
    repro: |
      Adversary uses SQL injection on either service to set
      `heartbeat.host_id = "real-host\n2026-05-13 10:00:00 INFO Forged
      log entry\n"`. The losing instance's contention log entry now
      contains injected log lines that mimic legitimate operator-visible
      records, confusing forensic review of why the second instance
      refused to start.
    suggested_fix_class: input-sanitization
out_of_model_notes:
  - date: 2026-05-13
    note: |
      The threat model does not commit to audit-logging single-instance-
      enforcement events. A losing second instance exits with no row
      written to audit_log; operator-visible signal is only the stdout
      fatal line. If forensic reconstruction of "who tried to start a
      duplicate instance and when" matters, that is a scope-extension
      discussion rather than a violation.
  - date: 2026-05-13
    note: |
      Holding a permanent Agroal pool connection (InstanceLockGuard
      heldConnection) is necessary for advisory-lock session lifetime
      but reduces pool capacity by one. Not a threat-model commitment;
      flagged for operator awareness.
  - date: 2026-05-13
    note: |
      Quarkus.asyncExit(1) returns from @PostConstruct after scheduling
      shutdown; in the brief window before JVM exit the losing
      instance's HeartbeatScheduler.tick() may run and bump
      last_seen_at on the winning instance's row. Cosmetic /
      operationally confusing but not a threat-model gap.
clarity_check:
  date: 2026-05-13
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE / item 9: Provider HeartbeatScheduler acceptance lacks explicit grep checks (symmetric to item 8); a stub could pass. Developer will implement symmetrically with item 8."
    - "ACCEPTANCE-RUNNABLE / out_of_scope line 38: parenthetical 'System.exit(1)' contradicts acceptance item 7 + Implementation notes mandating Quarkus.asyncExit(1). Developer will follow the authoritative acceptance/notes."
    - "FORWARD-REFERENCE-CHECK / M1-008: prose references only; no docs/plan/m1/tickets/M1-008-*.md file exists. UNRESOLVED-PROSE (non-blocking)."
  blockers: []
---

# M1-009: Advisory-lock single-instance enforcement + heartbeat

## Context

`docs/spec/architecture.md` §Deployment topology (v1) commits the system
to **exactly one Collector and exactly one Provider** per shared
Postgres (decision D41). That invariant is structural: a second
Collector would produce duplicate fetches, a second Provider would
duplicate periodic digests and race on the `LISTEN/NOTIFY` high-water
mark. The spec promises the invariant is **enforced**, not policy:
"makes 'exactly one' an enforced invariant, not a policy."

This ticket lands that enforcement. Each service acquires a named
`pg_advisory_lock` at `@Startup` priority 50 (per
`docs/design/01-architecture.md` §1.4.3), writes a heartbeat row
naming itself, and refuses to start if the lock is already held.
A `@Scheduled` task refreshes `last_seen_at` on the heartbeat row at
the per-profile interval so the loser of a conflict can read the
current holder's identity for a clear operator-facing log line.

The advisory lock is a *structural integrity boundary* preventing
dual-instance corruption of `LISTEN/NOTIFY` ordering and high-water-
mark invariants — which is why this ticket carries
`security_relevant: true`. The threat-actor pass should focus on the
race shape (TOCTOU between heartbeat-read and lock-acquire), the exit
path (cannot be turned off by a misconfigured property), and the
authorization surface of the `heartbeat` table (Provider must be
able to read both rows, but the application code must never DELETE).

## Definition of Done

- Flyway V3 migration creates a single `heartbeat` table — one row
  per service ("collector" / "provider") — with columns `service`
  (text PRIMARY KEY), `host_id` (text), `pid` (int), `last_seen_at`
  (timestamptz, default `now()`).
- The migration grants SELECT, INSERT, UPDATE on `heartbeat` to
  both `infochat_collector` and `infochat_provider`. **DELETE is
  not granted to either application role** — only the admin role
  may delete heartbeat rows (operator path), so an application bug
  cannot remove the contention fingerprint.
- Each service has an `InstanceLockGuard` bean under `startup/` with
  `@Startup` + `@Priority(50)`, matching the priority table in
  `docs/design/01-architecture.md` §1.4.3. The bean opens a JDBC
  connection, calls `pg_try_advisory_lock(hashtext('infochat.<service>'))`,
  and:
  - On success: upserts this instance's heartbeat row
    (`INSERT … ON CONFLICT (service) DO UPDATE SET host_id=…, pid=…,
    last_seen_at=now()`) and **holds the JDBC connection open for
    the lifetime of the JVM** so the advisory lock is not released
    by connection return-to-pool.
  - On failure (`pg_try_advisory_lock` returned `false`): reads the
    current heartbeat row, logs a fatal-level line naming the
    current holder's `host_id`, `pid`, and `last_seen_at`, then
    calls `Quarkus.asyncExit(1)`.
- Each service has a `HeartbeatScheduler` bean under `startup/`
  with a `@Scheduled(every = "{infochat.heartbeat.interval}")`
  method that updates `last_seen_at = now()` on this service's
  row.
- The `infochat.heartbeat.interval` property is declared under the
  `%laptop` profile namespace in both modules'
  `application.properties` with a small value (`5s`). The
  property's per-profile values for `%vps`, `%pi`, and
  `%remote-llm` may be added here as placeholders OR deferred to
  the feature tickets that consume those profiles — see
  Implementation notes for the call.
- Both `infochat-provider/pom.xml` and
  `infochat-collector/pom.xml` add the `quarkus-scheduler`
  extension (M1-005 did not add it to either).
- `mvn -B verify` from the repo root exits 0; the new IT suite
  confirms (a) lock acquisition writes the heartbeat row, (b) a
  second acquire attempt in the same test JVM via a separate JDBC
  connection observes `false` and logs the conflict message
  naming the current holder, (c) the @Scheduled tick advances
  `last_seen_at`.

## Implementation notes

- **Hash function: `hashtext`.** Postgres' built-in `hashtext(text)`
  returns an int4 and is the standard idiom for
  `pg_try_advisory_lock(hashtext('infochat.collector'))` — the hash
  computation runs server-side so two instances on different hosts
  always race for the same int (`docs/design/07-deployment.md`
  §7.8.5 specifies this routine).
- **Hold the JDBC connection open for the JVM lifetime.** Advisory
  locks are scoped to the *session* (Postgres backend), not to the
  transaction. If you acquire the lock, return the connection to
  the Quarkus Agroal pool, and the pool gives that connection to a
  different caller, the next operation on the same connection still
  sees the lock held — but if the pool *closes* the connection
  (idle-eviction), the lock is released and a second instance can
  acquire it. The clean shape is: a dedicated long-lived JDBC
  `Connection` that the `InstanceLockGuard` owns from acquire to
  JVM exit; pool idle-eviction must not touch it. The simplest
  implementation borrows a connection from the default datasource
  and never returns it — Quarkus' Agroal supports detached
  connections via `connection.close()` being suppressed (or use
  `DriverManager.getConnection(...)` directly with the same URL
  the datasource resolves to). Document the choice with a
  one-line comment in the bean explaining *why* the connection is
  long-lived.
- **Exit path: `Quarkus.asyncExit(1)`, not `System.exit(1)`.**
  The Quarkus runtime documents `Quarkus.asyncExit(int)` as the
  proper way to stop the application from a `@Startup` bean — it
  triggers a graceful shutdown of any beans already started. Use
  exit code `1` (the generic fatal); `docs/design/07-deployment.md`
  §7.8.5 mentions exit code `42` as a future systemd-coordinated
  refinement, but that ties to a `RestartPreventExitStatus=42` unit
  file that does not yet exist. Use exit code 1 in v1; the unit-
  file ticket later swaps it. Record this in the commit message so
  the future reader can connect the dots.
- **Reading the heartbeat row on conflict.** The losing instance
  must read `heartbeat WHERE service = '<this>'` to populate the
  fatal log line. The read happens AFTER `pg_try_advisory_lock`
  returns false but BEFORE the exit call. Use the same JDBC
  connection that did the `try` — it does not yet hold the lock,
  and reading the row does not need to.
- **`@Priority(50)` matters.** Quarkus runs lower-priority
  `@Startup` beans first. Priority 50 is below Flyway's implicit
  100 (`docs/design/01-architecture.md` §1.4.3) — wait, that's
  inverted. **Re-reading the design:** "Quarkus runs Flyway
  migrations before any `@Startup` bean" — Flyway is upstream of
  all `@Startup` beans regardless of priority. So the priority
  ordering matters only between this bean and other `@Startup`
  beans, which (in M1-009's scope) are none yet. The priority 50
  is the design-committed value; honor it so later beans
  (BootstrapLoader at 200, etc.) land in the documented order.
- **Per-profile heartbeat interval.** The `%laptop` value is
  required in this ticket (the test suite runs under the laptop
  profile, see M1-005's `%test.quarkus.profile=laptop` wiring).
  The `%vps`, `%pi`, `%remote-llm` values are design-tier choices
  per `docs/design/07-deployment.md` §7.2.1 (the design suggests
  10s, 30s, 10s respectively). It is acceptable EITHER to land
  all four namespaces in this ticket with those design-suggested
  values, OR to land only `%laptop` here and defer the other
  three to a profile-tuning ticket. The acceptance criteria only
  require `%laptop` for both modules; the developer chooses
  whether to land the other three values now. Keep the choice
  consistent across both modules (don't land `%vps` for one but
  not the other).
- **What the scheduler does NOT do.** The scheduler updates
  `last_seen_at`. It does NOT delete stale rows belonging to
  *other* services — the spec language treats stale rows as
  *operator-visible*, not auto-cleaned. A stale heartbeat means
  the prior holder died ungracefully; the next start's
  `pg_try_advisory_lock` will succeed (because the session
  holding the lock died too) and overwrite the row via the
  `ON CONFLICT` upsert. There is no separate "cleanup stale
  rows" path and adding one would be scope drift.
- **Test design note: two acquire attempts in one JVM.** Real
  enforcement is two *processes*. For the IT, simulate by opening
  two separate JDBC connections from the same test JVM and calling
  `pg_try_advisory_lock` on each; advisory locks are session-
  scoped so the second connection observes `false`. The test
  asserts the second return value AND captures the bean's log
  output to confirm the contention message names the holder
  identity. This is sufficient evidence that the production code
  path works — full multi-JVM enforcement is implicit in the
  Postgres-session semantics, not in the bean's code.

## Big-picture notes

- **`provider_state` is a separate concept.** The spec mentions
  contention on `provider_state` (§Deployment topology). That is
  the Provider's high-water-mark table (M1-008 territory), NOT
  the heartbeat table. M1-009 does NOT create `provider_state`.
- **Forward-compatibility with the exit-code-42 unit file.** The
  design promises `RestartPreventExitStatus=42` so systemd does
  not restart the loser. This ticket uses exit code 1 because
  the systemd unit file is operator-tooling work owned by a
  later ticket. The eventual unit-file ticket will swap this
  bean's exit code; keep the call site centralised so the swap
  is a one-line change.
- **The lock is the integrity boundary; the heartbeat is the
  fingerprint.** A future reader may wonder why both exist when
  the lock alone enforces single-instance. The answer: the lock
  enforces; the heartbeat *names* the holder so the loser can
  log a useful message. Without the heartbeat row, the loser's
  fatal log is "another instance has the lock" with no
  identifying information — useless for an operator debugging a
  rolling-deploy hiccup.
- **Threat-actor scope on the redteam pass.** The lock is a
  promised structural integrity defense. The pass should look
  for: (a) any path that releases the lock prematurely
  (connection-pool eviction, transaction-rollback, accidental
  `pg_advisory_unlock` call); (b) any path where the heartbeat
  row write can fail silently while the lock is held (which
  would leave the loser with a stale or empty fingerprint to
  log); (c) any code path that bypasses the guard via a feature
  flag or property (none should exist — the guard is
  unconditional).

## Out-of-scope expansion

- **Edits to V1__init.sql or V2__roles.sql.** Both are committed.
  V3 is the only new migration.
- **`provider_state` table.** M1-008 territory. The naming
  overlap with `docs/design/07-deployment.md` §7.8.5's
  `provider_state` is a docs inconsistency (see Big-picture notes),
  not a directive to create the table here.
- **NewPostReconciler / high-water-mark replay.** M1-008 +
  provider-reconciler ticket. The heartbeat row written here is
  unrelated to the high-water mark.
- **LISTEN/NOTIFY channel registration.** The `new_post`,
  `new_price_snapshot`, and `quarantine_review` channels land
  with their producing tickets (ingest, asset commands, quarantine).
- **`RestartPreventExitStatus=42` systemd unit file.** Owned by
  the operator-tooling ticket. This ticket uses exit code 1; the
  unit-file ticket swaps it. See Implementation notes.
- **Auto-cleanup of stale heartbeat rows.** Out of scope. Stale
  rows are operator-visible signal; the next successful start
  upserts them.
- **GRANTs on tables other than `heartbeat`.** Per-table grants
  ride with the per-table CREATE migrations (M1-008).
- **Production code outside the two `startup/` packages.** This
  ticket is migration + lock-guard + scheduler + property keys
  only.

## Authorized test changes

- (none — this ticket adds four new IT files and modifies no
  existing test. M1-005's `FlywayMigrationIT` continues to assert
  V1 APPLIED and is unaffected by V3's additional schema-history
  row. M1-006's `DbRoleMatrixIT` continues to assert the three
  roles exist; V3's grants reference those roles by name and
  Flyway will fail-fast if a role is missing, so a regression in
  M1-006 would surface here as a Flyway error rather than a
  silent grant-against-nonexistent-role.)

## Alternatives considered

- **Use `pg_advisory_lock` (blocking) instead of
  `pg_try_advisory_lock`.** Rejected: a blocking acquire would
  hang the second instance's startup indefinitely, masking the
  misconfigured deploy rather than failing fast. The spec
  language ("fails fast with a fatal log message") commits to
  the non-blocking shape.
- **Acquire the lock inside Flyway's `beforeMigrate` hook so the
  guard runs even earlier.** Rejected: the design priority table
  puts the guard at @Startup 50, *after* Flyway runs at 100.
  Reordering would mean lock-acquisition before V3 has run,
  which would race the first-ever bootstrap of the heartbeat
  table on a fresh DB. The post-Flyway shape is the safe one.
- **Skip the heartbeat table; rely on `pg_stat_activity` for
  holder identity.** Rejected: `pg_stat_activity` rows expose
  the connecting role and client_addr but not the JVM's host_id
  or pid in a portable way; reading them requires elevated
  Postgres privileges that the Provider role does not have
  (§DB roles); and the operator-visible signal would disappear
  the moment the holder's session ends. The heartbeat row
  survives session loss and is the right durable fingerprint.
- **Use a database transaction-scoped lock
  (`pg_advisory_xact_lock`).** Rejected: transaction-scoped
  locks release at COMMIT, which means the lock is gone before
  the JVM is even past `@Startup`. Session-scoped is what the
  invariant needs.
- **Land the systemd unit file with exit code 42 in this
  ticket.** Rejected: unit files are operator tooling, not
  application code; bundling them here triples the diff and
  pulls in `/etc/systemd/system/`-shaped questions that are
  irrelevant to the single-instance invariant itself. The
  exit-code value is a one-line change when the unit-file
  ticket lands.
