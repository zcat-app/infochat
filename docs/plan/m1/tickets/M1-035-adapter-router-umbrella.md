---
id: M1-035
title: Adapter + router umbrella — first-DM auto-register + /help IT
status: done
created: 2026-05-17
last_updated: 2026-05-18
reviews:
  - round: 1
    date: 2026-05-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 199
      removed: 12
reopens:
  - date: 2026-05-18
    prior_deferred_reason: blocked-on-new-ticket
    prior_deferred_on: M1-035d
    reason: M1-035d landed; umbrella IT now unblocked
clarity_check:
  date: 2026-05-18
  verdict: PASS
  warnings: []
  blockers: []
escalations:
  - date: 2026-05-17
    reason: premise-fail
    reviewer_verdict_excerpt: "N/A — pre-implementation premise-fail surfaced during start"
    developer_note: |
      AutoRegisterService.resolveOrRegister is never invoked from
      production code. InboundRouter.onMessage goes directly from
      normalize() to handleSlash() with no AutoRegisterService
      injection; grep -rn 'resolveOrRegister' infochat-provider/src/main/
      returns only the method definition. Acceptance item 5(a) (users
      row inserted on deliverDm "/help") cannot be satisfied without
      editing InboundRouter (M1-035b territory, FROZEN per
      out_of_scope) or AutoRegisterService (M1-035c territory,
      FROZEN per out_of_scope). M1-035c's ticket body line 146-160
      committed to "the auto-register-on-first-DM service the
      InboundRouter calls before slash-prefix dispatch" but the
      production wiring is absent in commit a6e97ec.
    resolution: defer-on-M1-035d
blocked_by:
  - M1-035a
  - M1-035b
  - M1-035c
files_budget: 1
files_scope:
  - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any change under infochat-messaging-adapter/ (the SPI + InMemoryAdapter are FROZEN at M1-035a's umbrella round; if this IT exposes a defect, file a follow-up against the affected module per docs/process/workflow.md §M1 workflow — never amend a passed commit)
  - any change to AdapterRegistry, InboundRouter, MessagingStartup (M1-035b's commit is FROZEN at its umbrella round)
  - any change to HelpCommandHandler, AutoRegisterService, BundleLoader, BundleKeys, en.properties (M1-035c's commit is FROZEN at its umbrella round)
  - any change under infochat-core/src/main/resources/db/migration/ (T1-E is migration-free; this umbrella consumes V1..V11 as-is and adds no new migration)
  - any new schema-level test (the umbrella's IT is application-tier — adapter → AutoRegisterService → router → /help → adapter outbound roundtrip; the per-(user, scope) isolation IT is M1-008's commit and continues to pass unchanged)
  - any modification to M1-003 @QuarkusTest stubs, M1-007 cross-module AllSpisLoadIT, M1-007a/b/c SPI smoke tests, M1-008 per-scope isolation IT, M1-008a/b/c per-row schema tests, M1-022/023/024/025/026 ingest-source tests, M1-027/028 outbox/NOTIFY tests, M1-032/033/034a/034b eval-pipeline tests, M1-035a InMemoryAdapterTest, M1-035b AdapterRegistryTest / StartupGatesTest / InboundRouterTest, M1-035c HelpCommandHandlerTest / AutoRegisterServiceTest / BundleLoaderTest (those continue to pass unchanged; modifying any would be a test-integrity violation per engineering-rules-verbatim.md §8)
  - any group `@mention` dispatch (group scope is deferred to T2-F; the IT exercises DM scope only — the InMemoryAdapter test helper `deliverDm` synthesises a DM InboundMessage)
  - any invite-gating / slow-start probation / /ban exercise (T2-A); the IT seeds a non-banned, non-probation user via the MVP auto-register path
  - any translation / `/lang` exercise (T2-C); the IT asserts the /help reply text against the English bundle keys
  - any /add-source or /summary command exercise (T1-F); the IT asserts the unknown-command reply for `/unknown-command` to prove the dispatch-stub branch works without authoring those handlers
  - any SimpleX or Signal adapter (T3-A); the IT uses only InMemoryAdapter
  - any bootstrap-admin @Startup bean exercise (deferred per the T1-E handoff; the IT does NOT assert anything about a bootstrap admin row — see Big-picture notes)
acceptance:
  - "infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java exists, is named with the `*IT` suffix so maven-failsafe-plugin runs it under mvn verify (the failsafe wiring authored by M1-008a already includes the provider module pattern), and contains at least one `@Test` annotation. Verify: `grep -E '@Test' AdapterRouterIT.java` returns ≥1 match"
  - "The IT is a `@QuarkusTest` (NOT plain JUnit — it needs the full CDI graph: AdapterRegistry + InboundRouter + MessagingStartup + HelpCommandHandler + AutoRegisterService + BundleLoader + the InMemoryAdapter bean). Verify: `grep -E '@QuarkusTest' AdapterRouterIT.java` returns ≥1 match"
  - "The IT activates the test profile (or the @QuarkusTestResource / @TestProfile mechanism Quarkus exposes) setting `infochat.adapters=inmemory` AND `infochat.adapters.inmemory.allow-low-trust=true` so the registry's gate 5 (LOW-trust opt-in) passes. Verify: `grep -E 'allow-low-trust|infochat\\.adapters' AdapterRouterIT.java` returns ≥1 match (in the test profile setup; either as `@TestProfile` overrides or an `application-test.properties` referenced by the IT)"
  - "The IT injects the InMemoryAdapter bean via CDI (e.g., `@Inject InMemoryAdapter adapter`) so the test can use the concrete-class test helpers (`deliverDm`, `sentMessages`, `reset`). Verify: `grep -E '@Inject\\s+InMemoryAdapter' AdapterRouterIT.java` returns ≥1 match"
  - "MVP exit criterion §3 — first-DM auto-register + /help reply: the IT calls `adapter.deliverDm(\"mvp-user-1\", \"/help\")` and asserts: (a) `SELECT COUNT(*) FROM users WHERE adapter='inmemory' AND contact_id='mvp-user-1'` returns 1 (the auto-register insert happened); (b) the inserted row has `is_admin = false` and `registration_state = 'invited'` and `probation_until IS NULL`; (c) `adapter.sentMessages()` contains exactly one OutboundMessage whose body is the /help reply composed from the BundleKeys.HELP_HEADER_DM_USER + HELP_CMD_HELP_SHORT + HELP_CMD_ADD_SOURCE_SHORT + HELP_CMD_SUMMARY_SHORT bundle entries"
  - "Auto-register idempotency — a SECOND `adapter.deliverDm(\"mvp-user-1\", \"/help\")` does NOT insert a second `users` row and DOES produce a second outbound /help reply. The IT asserts the count is still 1 AND `adapter.sentMessages().size() == 2`"
  - "Unknown-command friendly reply — `adapter.deliverDm(\"mvp-user-2\", \"/unknown-command\")` produces exactly ONE outbound message whose body matches the en.properties `error.unknown_command` value (NOT empty, NOT an exception trace, NOT a silent drop). The IT asserts the outbound body equals the bundle value for `error.unknown_command`"
  - "The IT does NOT add or modify any Flyway migration. Verify: `git diff --stat main -- infochat-core/src/main/resources/db/migration/` against the IT's branch returns zero modified files"
  - "mvn -B clean verify from the repo root exits 0. AdapterRouterIT runs under failsafe; failsafe reports record at least one test executed AND no failures. Verify: `grep -rE 'AdapterRouterIT' infochat-provider/target/failsafe-reports` returns at least one match AND `grep -rE '<testsuite[^>]*failures=\"0\"' infochat-provider/target/failsafe-reports` returns at least one match for AdapterRouterIT"
  - "Every prior test continues to pass: M1-003 @QuarkusTest stubs (Collector + Provider), M1-007 cross-module AllSpisLoadIT, M1-007a/b/c per-module SPI smoke tests, M1-008 per-scope isolation IT, M1-008a/b/c per-row schema tests, M1-022/023/024/025/026 ingest-source tests, M1-027/028 outbox/NOTIFY tests, M1-032/033/034a/034b eval-pipeline tests, M1-035a InMemoryAdapterTest, M1-035b AdapterRegistryTest / StartupGatesTest / InboundRouterTest, M1-035c HelpCommandHandlerTest / AutoRegisterServiceTest / BundleLoaderTest"
test_plan:
  adds:
    - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java (one @QuarkusTest *IT-named class with one or more @Test methods that exercise MVP exit criterion §3 end-to-end via the InMemoryAdapter test helpers)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java (M1-035a)
    - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRegistryTest.java (M1-035b)
    - infochat-provider/src/test/java/io/infochat/provider/messaging/StartupGatesTest.java (M1-035b)
    - infochat-provider/src/test/java/io/infochat/provider/messaging/InboundRouterTest.java (M1-035b)
    - infochat-provider/src/test/java/io/infochat/provider/messaging/HelpCommandHandlerTest.java (M1-035c)
    - infochat-provider/src/test/java/io/infochat/provider/messaging/AutoRegisterServiceTest.java (M1-035c)
    - infochat-provider/src/test/java/io/infochat/provider/bundle/BundleLoaderTest.java (M1-035c)
    - infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java (M1-008)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - all M1-008a/b/c *Test.java classes
    - all M1-022/023/024/025/026 *Test.java and *IT.java classes
    - all M1-027/028 *Test.java and *IT.java classes
    - all M1-032/033/034a/034b *Test.java and *IT.java classes
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Goals
  - docs/spec/commands.md §Surface conventions
  - docs/spec/commands.md §Discovery
  - docs/design/00-mvp.md §4 Messaging adapter and commands
  - docs/design/00-mvp.md §6 MVP exit criteria
decision_refs:
  - D10
  - D30
  - D46
redteam_findings: []
redteam_audits:
  - date: 2026-05-18
    verdict: CLEAN
    base: 6378e6dd91a4f942d4e72f743c0d6e292e3442ea^
    head: 6378e6dd91a4f942d4e72f743c0d6e292e3442ea
    verdict_file: docs/plan/m1/redteam/M1-035-2026-05-18.md
    findings_count: 0
    out_of_model_count: 2
    note: |
      Umbrella diff is test-only (one new @QuarkusTest IT + STATUS regen
      + this ticket's review-block frontmatter); the threat model's
      promises are unaffected. Two OUT-OF-MODEL advisories surfaced for
      the user: (1) the IT asserts registration_state='invited' for a
      first-DM auto-registered user that arrived with no invite code —
      a possible retrospective signal about M1-035d's intake-gate
      compliance with §Authorization step 2 (invite required for
      unknown DM contacts), but the production wiring is not in this
      diff; (2) the TestProfile sets allow-low-trust=true on the
      in-memory adapter — @TestProfile-scoped only, not exploitable in
      production.
---

# M1-035: Adapter + router umbrella — first-DM auto-register + /help IT

## Context

Umbrella commit for the M1-035 group (per
`docs/process/workflow.md` §Ticket-ID placeholder convention —
the umbrella + subticket idiom). M1-035a, M1-035b, and M1-035c
each shipped a slice of the T1-E adapter + router surface as its
own reviewable commit on `main`:

- M1-035a — SPI fill-in (ScopeRef, Identity, InboundMessage,
  OutboundMessage, AdapterTrustLevel, FailureCategory,
  MessagingException, extended CapabilityFlags) + the concrete
  `InMemoryAdapter` under `impl/inmemory/`.
- M1-035b — Provider-side `AdapterRegistry` + `InboundRouter` +
  `MessagingStartup` + the six startup gates from
  `docs/design/06-messaging.md` §6.2.1, §6.6, §6.7, §6.8.
- M1-035c — first command (`/help`) + auto-register-on-first-DM
  (`AutoRegisterService`) + bundle infrastructure
  (`BundleLoader`, `BundleKeys`, `en.properties`).

Each subticket's per-class tests verify its own slice. This
umbrella commit verifies the **cross-cutting** property the
subtickets cannot verify in isolation: that
**docs/design/00-mvp.md §6 MVP exit criterion §3** holds
end-to-end — "A non-admin user, sending their first DM via
`InMemoryAdapter`, is auto-registered and receives /help." The
IT seeds a first DM via the InMemoryAdapter's `deliverDm` test
helper, asserts exactly one `users` insert (with the spec-required
defaults `is_admin=false`, `registration_state='invited'`,
`probation_until IS NULL`), asserts exactly one outbound /help
reply with the bundle-composed body, asserts that a second DM
from the same contact_id is idempotent (no second insert) and
produces a second /help reply, and asserts that an unknown
command produces the friendly bundle-keyed error reply (NOT a
silent drop, NOT an exception trace).

The whole-topic verification is meaningfully different from any
single subticket's unit-level assertions:

- M1-035a's `InMemoryAdapterTest` asserts the in-memory adapter's
  per-method behavior in isolation (no router, no command
  handler, no DB).
- M1-035b's `AdapterRegistryTest` + `StartupGatesTest` +
  `InboundRouterTest` assert the registry's per-gate behavior
  and the router's per-branch behavior (the router's test uses
  a test-only fake command handler, not the real
  HelpCommandHandler).
- M1-035c's `HelpCommandHandlerTest` + `AutoRegisterServiceTest`
  + `BundleLoaderTest` assert the handler's bundle composition,
  the service's idempotency / race protection, and the bundle's
  completeness — but each in isolation, against a Testcontainers
  Postgres only for AutoRegisterServiceTest.

None of those asserts the full chain: InMemoryAdapter →
InboundRouter → normalization pass → AutoRegisterService →
CDI-routed command handler → HelpCommandHandler → BundleLoader →
OutboundMessage → InMemoryAdapter's `sentMessages()` queue. The
IT walks every link and asserts the user-observable contract.
Shipping the cross-class assertion as its own reviewable unit is
exactly the umbrella + subticket idiom's reason to exist.

`security_relevant: true` — MVP exit criterion §3 is the full
inbound→dispatch→outbound smoke. A leak between the auto-register
path and the /help dispatch path would let an unregistered DM
trigger a register-and-reply round trip without the dispatch
ever happening — a silent NOOP from the user's perspective, but
a row insert from the DB's perspective. The IT asserts both
halves happen exactly once in the same inbound, which is the
spec promise the MVP exit criterion encodes.

## Definition of Done

- A single `@QuarkusTest` `*IT`-named class lives at
  `infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java`.
- The `*IT` suffix matches maven-failsafe-plugin's convention; the
  failsafe wiring authored by M1-008a runs the IT under `mvn
  verify` from the repo root. (Provider's `pom.xml` declares the
  failsafe plugin with Maven's default include pattern — i.e. no
  explicit `<include>` overrides — so `**/*IT.java` is picked up
  automatically. Existing ITs at sibling paths
  `infochat-provider/src/test/java/io/infochat/provider/{outbox,
  startup,spi}/*IT.java` already run under failsafe; no pom
  edit is required for `messaging/AdapterRouterIT.java`.)
- The IT activates the test profile setting:
  - `infochat.adapters=inmemory`
  - `infochat.adapters.inmemory.allow-low-trust=true`
  These are the minimum properties the AdapterRegistry's gate 1
  (non-empty list), gate 2 (name resolves), gate 5 (LOW-trust
  opt-in) need to pass. The other gates pass vacuously for
  InMemoryAdapter (gate 3 — supportsMarkdownLinks=false; gate 4
  — single-adapter list satisfies production-exclusion; gate 6
  — supportsMentionByContactId=true).
- The IT injects the InMemoryAdapter bean via CDI so it can use
  the concrete-class test helpers (`deliverDm`, `sentMessages`,
  `reset`) that M1-035a authored. It also injects a JDBC
  connection or DataSource (the Provider test infra's existing
  pattern) to assert against the `users` table directly.
- One or more `@Test` methods exercise MVP exit criterion §3
  verbatim:
  - **First-DM auto-register + /help reply.** A new
    `contact_id` (`"mvp-user-1"`) sends `/help`. The IT asserts:
    - Exactly one `users` row exists for `(adapter='inmemory',
      contact_id='mvp-user-1')`.
    - The inserted row has `is_admin = false`,
      `registration_state = 'invited'`, `probation_until IS NULL`.
    - The InMemoryAdapter's `sentMessages()` contains exactly
      one OutboundMessage whose body is the /help reply,
      composed from the BundleKeys.HELP_HEADER_DM_USER +
      HELP_CMD_HELP_SHORT + HELP_CMD_ADD_SOURCE_SHORT +
      HELP_CMD_SUMMARY_SHORT bundle entries. The exact body is
      what the en.properties values resolve to; the assertion
      reads the bundle (via the same BundleLoader the
      HelpCommandHandler uses) so a future bundle text change
      doesn't break the IT.
  - **Auto-register idempotency.** A SECOND
    `deliverDm("mvp-user-1", "/help")` (same contact_id) does
    NOT insert a second `users` row AND DOES produce a second
    /help outbound reply. The IT asserts the users-row count is
    still 1 AND `sentMessages().size() == 2`.
  - **Unknown command friendly reply.**
    `deliverDm("mvp-user-2", "/unknown-command")` produces
    exactly one outbound message whose body matches the
    en.properties `error.unknown_command` value. The IT asserts
    the outbound body equals the bundle value for
    `error.unknown_command`.
- The IT does NOT add or modify any Flyway migration. T1-E is
  migration-free.
- `mvn -B clean verify` from the repo root exits 0; AdapterRouterIT
  runs under failsafe and passes. Every prior test continues to
  pass.

## Implementation notes

- **`@QuarkusTest`, not plain JUnit.** This IT needs the full
  CDI graph: AdapterRegistry, InboundRouter, MessagingStartup,
  HelpCommandHandler, AutoRegisterService, BundleLoader, the
  InMemoryAdapter bean. Plain JUnit would have to assemble the
  graph by hand, which would defeat the IT's purpose (verifying
  the wired-by-CDI roundtrip works). `@QuarkusTest` brings up
  the Provider context with the test profile applied — the
  same shape M1-008's PerScopeIsolationIT uses, but at the
  application tier rather than the schema tier.
- **`@TestProfile` for the property setup.** Quarkus's
  `@TestProfile` mechanism lets the IT declare its property
  overrides inline:
  ```java
  public static class MvpProfile implements QuarkusTestProfile {
      public Map<String, String> getConfigOverrides() {
          return Map.of(
              "infochat.adapters", "inmemory",
              "infochat.adapters.inmemory.allow-low-trust", "true"
          );
      }
  }
  ```
  Either that or an `application-test.properties` file under
  `infochat-provider/src/test/resources/` carrying the same
  values. Either shape meets acceptance; the file-based shape
  is cheaper if no other Provider IT needs the same overrides
  yet, but `@TestProfile` is cleaner if more ITs land.
- **InMemoryAdapter injection.** M1-035b's AdapterRegistry
  discovers `MessagingAdapter` beans via CDI; M1-035b's bean
  producer exposes `InMemoryAdapter` to the CDI graph (test-
  scope `@Alternative` or production bean — implementer's
  choice as long as the bean is on the test classpath). The IT
  injects the same bean:
  ```java
  @Inject InMemoryAdapter adapter;
  @Inject AutoRegisterService autoRegister;  // optional, for direct asserts
  @Inject BundleLoader bundleLoader;         // for bundle-value asserts
  @Inject DataSource dataSource;             // for SELECT against users
  ```
- **`adapter.reset()` between tests.** The InMemoryAdapter's
  in-memory state (sent messages, update history, typing
  events) accumulates across `@Test` methods. Either call
  `adapter.reset()` in `@BeforeEach` OR seed unique `contact_id`
  values per `@Test` so state from one test doesn't bleed into
  another. The simpler shape is `adapter.reset()` plus a
  truncate-or-rollback of the `users` table — the existing
  Provider test infra likely already provides one or the other.
- **The /help reply body assertion.** The IT does NOT hardcode
  the English help text. It composes the expected body by
  calling the same BundleLoader the HelpCommandHandler uses:
  ```java
  var expected = bundleLoader.get(BundleKeys.HELP_HEADER_DM_USER)
      + "\n" + bundleLoader.get(BundleKeys.HELP_CMD_HELP_SHORT)
      + "\n" + bundleLoader.get(BundleKeys.HELP_CMD_ADD_SOURCE_SHORT)
      + "\n" + bundleLoader.get(BundleKeys.HELP_CMD_SUMMARY_SHORT);
  ```
  (The exact separator/newline shape between bundle values is
  what HelpCommandHandler ships — implementer-of-M1-035c's
  choice. The IT mirrors whatever composition HelpCommandHandler
  uses; the principle is that the IT goes through the bundle
  rather than baking the text into the test.)
- **The unknown-command reply assertion.** Similar discipline:
  read the en.properties value for `error.unknown_command` via
  the BundleLoader and assert equality against the outbound
  body. If M1-035b's InboundRouter still ships the literal
  string (instead of using BundleLoader — see M1-035c §"The
  chat_mode.not_in_mvp and error.unknown_command bundle keys"),
  the IT may either (a) compare against the hardcoded literal
  by reading what InboundRouter writes, or (b) refactor
  InboundRouter to use the bundle key — option (b) is
  preferable but is M1-035c's editorial choice, not this
  umbrella's responsibility to enforce.
- **Why a second deliverDm in the same IT.** The idempotency
  assertion is its own slice of MVP exit criterion §3 — the
  spec promise is "first DM auto-registers"; a second DM from
  the same contact MUST NOT register again. The two-deliverDm
  shape pins this contract. Without it, a future regression
  that re-inserts on every DM would still pass the first-DM
  assertion alone.
- **No group-scope assertion in the IT.** docs/design/00-mvp.md
  §4 "No group onboarding (groups are deferred)." The IT
  exercises DM scope only. T2-F (groups) will add a sibling IT
  for the group `@mention` round-trip.
- **No bootstrap-admin assertion in the IT.** Per the T1-E
  handoff out-of-scope list, the bootstrap-admin @Startup bean
  is deferred. The IT does NOT seed or assert against an
  `infochat.admin.contact-id` bootstrap admin row. MVP exit
  criterion §2 ("the bot admin from infochat.admin.contact-id
  exists in user with is_admin=true") is satisfied by a manual
  SQL grant in docker-compose bootstrap or a future ticket;
  this IT does not pre-empt that future ticket's responsibility.
- **The IT uses raw JDBC for the users-table assertion.** The
  Provider test infra provides a `DataSource` via CDI (or the
  Quarkus default config). The IT opens a connection, issues
  `SELECT COUNT(*) ... WHERE adapter='inmemory' AND
  contact_id='mvp-user-1'`, and asserts the result. No
  application-tier `UserRepository` is consulted — that would
  couple the IT to a future refactor of the user-row layer
  that doesn't exist yet.

## Big-picture notes

- **The subticket commits are FROZEN at the umbrella round.**
  M1-035a, M1-035b, and M1-035c each landed as their own
  reviewable commit on `main` before this umbrella becomes
  runnable. If this IT exposes a defect in one of the
  subticket outputs (e.g., a missing AdapterRegistry gate, a
  wrong bundle key, a race condition in AutoRegisterService),
  the fix is a NEW ticket against the affected module — never
  an amendment to the subticket commit. The "never amend a
  passed commit" invariant in `CLAUDE.md` §M1 workflow applies
  verbatim.
- **MVP exit criterion §3 is the spec-level promise this
  umbrella proves.** docs/design/00-mvp.md §6 lists eight
  exit criteria; criterion §3 — "A non-admin user, sending
  their first DM via `InMemoryAdapter`, is auto-registered and
  receives `/help`" — is the criterion this IT verifies
  end-to-end. The other seven criteria are verified by other
  module ITs: criterion §1 (bootstrap loader) by M1-022's
  tests; criterion §4 (`/add-source` insert + NOTIFY) and §6
  (`/summary` LLM prose) by T1-F's tests; criterion §5
  (Collector eval pipeline → Provider NOTIFY) by the M1-028 +
  M1-034b combination; criterion §7 (prompt-injection
  fixture quarantined) by M1-032/033's tests; criterion §8
  (Collector restart re-enqueues RAW) by M1-028's tests.
  Criterion §2 (bootstrap admin) is the deferred gap noted
  below.
- **Bootstrap-admin gap (intentional, called out here so the
  reader sees it).** docs/spec/deployment.md §Operator inputs
  +5 docs/design/00-mvp.md §6 MVP exit criterion §2 require
  "the bot admin from `infochat.admin.contact-id` exists in
  `user` with `is_admin=true` and an `audit_log` row records
  the bootstrap." The corresponding @Startup bean is deferred
  per the T1-E handoff (MVP relies on a manual SQL grant in
  docker-compose bootstrap, or a future spec-compliant ticket
  authors the bean). This umbrella's IT does NOT exercise the
  bootstrap-admin path; criterion §2 is satisfied (or not) by
  the operator-side seed, which is outside T1-E's surface. A
  future "MVP-completion" pass will file the bootstrap-admin
  ticket and a sibling IT for it.
- **The umbrella unblocks T1-F.** Once M1-035 ships, T1-F (the
  next group) can author `/add-source` and `/summary` against
  the now-real CommandHandler interface + InboundRouter
  dispatch surface + AutoRegisterService user resolution. T1-F's
  /add-source IT will seed a registered user via the same
  AutoRegisterService this IT exercises; its /summary IT will
  walk the same router path. The bundle keys
  `HELP_CMD_ADD_SOURCE_SHORT` and `HELP_CMD_SUMMARY_SHORT` are
  already authored by M1-035c so T1-F doesn't have to coordinate
  bundle edits with handler impl in the same diff.
- **What the IT does NOT prove.** It does not prove that
  `/add-source` and `/summary` work (those are T1-F's tests);
  that the LLM evaluation pipeline correctly classifies
  malicious posts (M1-032/033's tests); that the Collector
  outbox + LISTEN/NOTIFY correctly delivers new posts
  (M1-028's tests); that the schema-level per-(user, scope)
  isolation holds (M1-008's IT); that the SimpleX adapter's
  identity assertion is cryptographically anchored (T3-A's
  tests). It proves only that **the adapter + router + first
  command + first user-registration paths agree on the
  inbound→outbound roundtrip MVP exit criterion §3 commits to.**
  That is a small but load-bearing claim — when it fails, the
  MVP's "minimum viable bot" promise is broken at the
  application tier.
- **The threat-actor review pass.** This ticket is
  `security_relevant: true`. A milestone-boundary `/redteam`
  after the umbrella commit covers the adapter + router
  attack surface: identity spoofing via InMemoryAdapter's
  default-LOW trust; auto-register race on the
  `(adapter, contact_id)` unique constraint; markdown-link
  rendering vector if the gate weren't shipped; production-
  exclusion bypass via `inmemory` alongside a hypothetical
  rogue adapter; LOW-trust opt-in bypass via the missing
  property; mention-recognition fallback to display name (the
  gate refuses this). The umbrella commit is the natural
  trigger point because it ships the cross-cutting roundtrip
  assertion — the first commit where the full surface is
  observable.

## Out-of-scope expansion

- **Changes to any subticket file.** The three subticket
  commits are frozen.
- **Changes under `infochat-core/src/main/resources/db/migration/`.**
  T1-E is migration-free.
- **New schema-level test.** The per-(user, scope) isolation
  IT is M1-008's commit; this umbrella adds the application-
  tier roundtrip IT.
- **Changes to any pre-existing test.** Modifying any of them
  would be a test-integrity violation per
  `engineering-rules-verbatim.md` §8.
- **Group `@mention` dispatch.** Deferred to T2-F. The IT
  exercises DM scope only.
- **Invite-gating / slow-start / `/ban` exercise.** All T2-A.
  The IT's seeded user has `probation_until IS NULL` and is
  not banned; the intake path runs through MVP's auto-register
  not T2-A's invite-consume.
- **Translation / `/lang` exercise.** T2-C. The IT asserts the
  English bundle entries.
- **`/add-source` / `/summary` exercise.** T1-F. The IT
  exercises only `/help` (registered) and `/unknown-command`
  (unknown-command branch).
- **SimpleX / Signal adapter exercise.** T3-A. The IT uses only
  InMemoryAdapter.
- **Bootstrap-admin @Startup exercise.** Deferred per the
  T1-E handoff. The IT does NOT seed or assert against a
  bootstrap admin row.

## Authorized test changes

- (none — this umbrella adds one new test class in
  `infochat-provider` and modifies no pre-existing tests.)

## Alternatives considered

- **Make the IT a plain `@JUnitTest` and assemble the CDI
  graph by hand.** Rejected: assembling the full graph
  (AdapterRegistry + InboundRouter + MessagingStartup +
  HelpCommandHandler + AutoRegisterService + BundleLoader)
  manually would either duplicate the production wiring (and
  rot when M1-035b/c evolve) or skip pieces (and not actually
  prove the roundtrip). `@QuarkusTest` is the right tool.
- **Inline the cross-cutting assertion into each subticket's
  per-class tests and skip the umbrella.** Rejected: each
  subticket can only see its own slice. The full roundtrip is
  exactly the property a per-class test cannot prove. The
  umbrella exists for this property.
- **Pre-empt the umbrella by writing the IT inside M1-035c
  (since M1-035c is the last subticket to merge).** Rejected:
  per docs/process/workflow.md §Ticket-ID placeholder
  convention, the umbrella + subticket idiom exists exactly so
  cross-module verification ships as its own reviewable unit.
  Pre-empting it would erase the umbrella's reason to exist.
- **Add a sibling IT in this umbrella for the group `@mention`
  roundtrip (anticipating T2-F).** Rejected: group scope is
  deferred to T2-F per docs/design/00-mvp.md §5. Asserting
  against a future code path would either skip the assertion
  (silent miss) or fail the IT (block this umbrella
  indefinitely). The group-roundtrip IT lands when T2-F does.
- **Assert against the InMemoryAdapter's `updateHistory(handle)`
  / `typingEvents()` lists (the M1-035a per-handle history).**
  Rejected: /help is short, deterministic, and bypasses the
  ProgressNotifier per docs/spec/messaging.md §Progress
  notifications ("Short, deterministic SQL commands bypass the
  notifier entirely"). The handler emits exactly one
  `send()` for the /help reply; there is no `update` / `finalize`
  sequence to assert. T1-F's /summary IT will exercise the
  update/finalize lifecycle.
- **Make the IT a parameterized test with one parameter set
  per inbound shape (registered user + /help; registered user
  + /unknown-command; new user + /help).** Acceptable but
  not required. The three `@Test` methods in unrolled form
  read cleaner and the per-method assertion set is small.
  Either shape meets acceptance.
- **Read the /help reply text from a hardcoded literal in the
  IT instead of going through the BundleLoader.** Rejected:
  the IT goes through the bundle so a future text change in
  en.properties doesn't require an IT edit. The bundle-keyed
  composition is the load-bearing claim, not the literal
  English text.
