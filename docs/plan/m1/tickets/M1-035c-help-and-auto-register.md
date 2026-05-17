---
id: M1-035c
title: /help command + auto-register-on-first-DM
status: done
created: 2026-05-17
last_updated: 2026-05-17
clarity_check:
  date: 2026-05-17
  verdict: PASS
  warnings: []
  blockers: []
blocked_by:
  - M1-035b
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/io/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/io/infochat/provider/messaging/AutoRegisterService.java
  - infochat-provider/src/main/java/io/infochat/provider/bundle/BundleLoader.java
  - infochat-provider/src/main/java/io/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/io/infochat/provider/messaging/HelpCommandHandlerTest.java
  - infochat-provider/src/test/java/io/infochat/provider/messaging/AutoRegisterServiceTest.java
  - infochat-provider/src/test/java/io/infochat/provider/bundle/BundleLoaderTest.java
  - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRegistryTest.java  # user-authorized round-1 addition per Authorized test changes; one-line probe rename to avoid collision with this ticket's /help handler
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java (the M1-035 umbrella's cross-cutting inbound→register→/help→outbound IT — reserved for the umbrella commit per docs/process/workflow.md §Ticket-ID placeholder convention; this subticket asserts the AutoRegisterService idempotency, the HelpCommandHandler bundle composition, and the bundle-completeness CI assertion only)
  - any change under infochat-messaging-adapter/ (the SPI surface and InMemoryAdapter are FROZEN at M1-035a's umbrella round)
  - any change to AdapterRegistry.java, InboundRouter.java, MessagingStartup.java, or CommandHandler.java (those are M1-035b's territory and FROZEN at its umbrella round; the InboundRouter discovers HelpCommandHandler via the `Instance<CommandHandler>` lookup M1-035b shipped, no router-side edit is required when this ticket lands)
  - any Flyway migration under infochat-core/src/main/resources/db/migration/ (T1-E is migration-free; the MVP auto-register insert reads/writes only columns the V5 schema already provides — `users.adapter`, `users.contact_id`, `users.display_name`, `users.is_admin`, `users.registration_state`, `users.probation_until`)
  - any new `audit_log.action` verb (the closed verb catalogue from V5 does NOT include `AUTO_REGISTER`; per docs/design/00-mvp.md §5 Operations carve-out, MVP auto-register SKIPS the audit_log insert entirely — T2-A's invite-gating adds the `INVITE_CONSUME` audit row, which exists in V5's closed set)
  - any invite-gating (D44), slow-start probation filter (D45), `/ban` / `/unban` (D11) — the MVP auto-register sets `probation_until = NULL` (the V5 column default; no override here) and `is_admin = false`; T2-A wires the invite consume + the probation tier + the ban check as the first three intake steps
  - any TranslationProvider integration / `/lang` / `cs.properties` bundle (deferred to T2-C; this ticket ships ONLY `en.properties`; the BundleLoader pattern is in place for `cs` in T2-C without a new bundle pattern)
  - any LLM output sanitizer integration (the /help reply is composed deterministically from bundle keys; the sanitizer lands in T1-F's `/summary`)
  - any /add-source or /summary command handler (T1-F's territory — those are the next two commands to land; this subticket adds ONLY /help, but the `en.properties` bundle MUST ALREADY contain the help-line keys for /add-source and /summary so the bundle-completeness assertion in BundleLoaderTest doesn't regress when T1-F adds the impls)
  - any admin-only command (no `/grant-admin`, no `/ban`, no `/quarantine list`) and consequently no admin-set bundle keys, no admin-tier `/help` filter (the actor-tier filter per docs/spec/commands.md §Discovery /help is non-trivial only when admin commands exist; MVP has none so the filter degenerates to "every registered user gets the same /help text")
  - any probation-tier footer / `help.footer.probation` bundle key (probation is deferred to T2-A; the footer key is added when probation lands)
  - any ProgressNotifier extension (the /help reply is short and deterministic; the notifier bypass rule from docs/spec/messaging.md §Progress notifications applies; ProgressStage / ProgressNotifier stubs from M1-007c stay untouched)
  - any confirmation-pending state machine (per docs/spec/commands.md §Surface conventions; /help is not destructive; MVP has no destructive commands; the in-memory confirmation map lives in T2-A)
acceptance:
  - "infochat-provider/src/main/java/io/infochat/provider/messaging/AutoRegisterService.java exists, is `@ApplicationScoped` (or equivalent CDI singleton), and exposes a method `resolveOrRegister(Identity sender, String adapterName)` that returns the resolved (or just-inserted) `users` row identifier — concretely, either a UUID, a `java.util.UUID`, or a Java record wrapping the row. Verify: `grep -E 'public\\s+[A-Za-z<>,\\s]+resolveOrRegister' AutoRegisterService.java` returns ≥1 match"
  - "AutoRegisterService's insert path issues `INSERT INTO users (adapter, contact_id, display_name, is_admin, registration_state) VALUES (?, ?, ?, FALSE, ?) ON CONFLICT (adapter, contact_id) DO NOTHING` (or an equivalent JDBC PreparedStatement / Panache call that maps to that SQL). The `ON CONFLICT (adapter, contact_id) DO NOTHING` clause is load-bearing for concurrent-first-DM idempotency per Locked decisions — without it, two concurrent first-DMs from the same contact would race the UNIQUE constraint and one would raise a 23505 SQLException. Verify: `grep -E 'ON CONFLICT\\s*\\(\\s*adapter\\s*,\\s*contact_id\\s*\\)\\s+DO NOTHING' AutoRegisterService.java` returns ≥1 match"
  - "AutoRegisterService sets `is_admin = FALSE` literally on insert (no template, no per-deployment override path). Verify: AutoRegisterServiceTest reads back the inserted row and asserts `is_admin = false`"
  - "AutoRegisterService omits `probation_until` from the INSERT column list so the V5 column default (NULL) applies. Verify: `grep -E 'probation_until' AutoRegisterService.java` returns ZERO matches"
  - "AutoRegisterService sets `registration_state = 'invited'` for the MVP DM-pathway-registered user. This is the four-value-CHECK-aligned closest-match for 'user registered via the DM path without an explicit invite'; T2-A may revisit when invite-gating lands. Verify: `grep -E \"'invited'\" AutoRegisterService.java` returns ≥1 match"
  - "AutoRegisterService does NOT write an `audit_log` row. Verify: `grep -E 'audit_log' AutoRegisterService.java` returns ZERO matches"
  - "AutoRegisterService is idempotent: a second call with the same (adapter, contact_id) returns the existing row without inserting a second row. AutoRegisterServiceTest asserts: seed one call to resolveOrRegister; assert `SELECT COUNT(*) FROM users WHERE adapter='inmemory' AND contact_id='test-1'` returns 1; call resolveOrRegister again with the same identity; assert the count is still 1 AND the returned row identifier matches the first call's"
  - "AutoRegisterService handles concurrent first-DMs from the same contact_id correctly: AutoRegisterServiceTest opens TWO JDBC connections (against the Testcontainers Postgres in the existing Provider test infrastructure), seeds two threads each calling resolveOrRegister with the same identity at a CountDownLatch, releases the latch, asserts both threads return without exception AND `SELECT COUNT(*) FROM users WHERE adapter='inmemory' AND contact_id='race-1'` returns exactly 1 (DO NOTHING handles the race per ON CONFLICT semantics)"
  - "AutoRegisterService enforces the cross-adapter isolation invariant from docs/spec/messaging.md §Per-adapter trust level: the same `contact_id` value across two different adapter names produces TWO distinct `users` rows. AutoRegisterServiceTest seeds resolveOrRegister with `(adapter='inmemory', contact_id='dup-1')` and resolveOrRegister with `(adapter='inmemory2', contact_id='dup-1')` and asserts `SELECT COUNT(*) FROM users WHERE contact_id='dup-1'` returns 2"
  - "infochat-provider/src/main/java/io/infochat/provider/messaging/HelpCommandHandler.java exists, is `@ApplicationScoped`, registers itself as a CommandHandler for the command name `help` (whatever CDI-bean-discovery mechanism M1-035b wired — qualifier, name-keyed map, etc.), and composes the reply per docs/design/03-commands.md §3.4 /help bundle-key naming. Verify: `grep -E 'class HelpCommandHandler' HelpCommandHandler.java` returns ≥1 match AND the file references `BundleLoader` or `BundleKeys` (`grep -E 'BundleLoader|BundleKeys' HelpCommandHandler.java` returns ≥1 match — the handler MUST go through the bundle infrastructure, not literal strings)"
  - "HelpCommandHandler reads the calling user's permitted command set. MVP: every non-admin user sees the same three commands (/help, /add-source, /summary) per docs/design/00-mvp.md §4 — admin-only commands are NOT shipped in T1-E so admin-set bundle keys are not authored here. Verify: HelpCommandHandlerTest asserts the reply string contains the three command names from the MVP set"
  - "HelpCommandHandler composes the reply as header + per-command short-help lines + (no footer in MVP — probation footer added by T2-A). Per docs/design/03-commands.md §3.4, the keys are `help.cmd.<command>.short`, `help.header.<actor-tier>`, `help.footer.probation`, `help.divider.<section>`. MVP uses the keys: `help.header.dm-user`, `help.cmd.help.short`, `help.cmd.add-source.short`, `help.cmd.summary.short`. Verify: HelpCommandHandlerTest asserts each of these four bundle keys is consumed by the handler (e.g., by passing a spy/mock BundleLoader and asserting the lookup calls)"
  - "HelpCommandHandler's output is plain text per decision D30: no markdown links, no emoji, no auto-formatting beyond the literal bundle string. Verify: HelpCommandHandlerTest asserts the reply contains no `[...](...)` substring AND no `<a href=...>` substring (the empty-result regression-guard)"
  - "infochat-provider/src/main/java/io/infochat/provider/bundle/BundleLoader.java exists, is `@ApplicationScoped`, and loads from `src/main/resources/bundles/<lang>.properties`. The `lang` value is hardcoded to `en` for MVP (the per-scope language plumbing lands in T2-C). Verify: `grep -E 'bundles/en' BundleLoader.java` returns ≥1 match (or equivalent classpath-resource reference)"
  - "BundleLoader exposes a method `get(String key)` that returns the bundle value or throws an exception when the key is missing. The exception type is implementer's choice (IllegalStateException is conventional); the behavior is the load-bearing part — silently returning the empty string would defeat the bundle-completeness CI assertion. Verify: BundleLoaderTest asserts that `loader.get(\"nonexistent.key\")` throws (any exception type)"
  - "infochat-provider/src/main/java/io/infochat/provider/bundle/BundleKeys.java exists as a constant-holder class with `public static final String` constants for every bundle key T1-E uses. The exact constants: `HELP_HEADER_DM_USER` = `\"help.header.dm-user\"`; `HELP_CMD_HELP_SHORT` = `\"help.cmd.help.short\"`; `HELP_CMD_ADD_SOURCE_SHORT` = `\"help.cmd.add-source.short\"`; `HELP_CMD_SUMMARY_SHORT` = `\"help.cmd.summary.short\"`; `ERROR_UNKNOWN_COMMAND` = `\"error.unknown_command\"`; `ERROR_INTERNAL` = `\"error.internal\"`; `CHAT_MODE_NOT_IN_MVP` = `\"chat_mode.not_in_mvp\"`. Verify: each of the seven constant names appears in BundleKeys.java, each holding the exact key-string above (grep -E '<NAME>\\s*=\\s*\"<key>\"' BundleKeys.java returns ≥1 match per constant)"
  - "infochat-provider/src/main/resources/bundles/en.properties exists and contains every key listed in BundleKeys.java. The header key value is operator-friendly (e.g., `Available commands:`); per-command short-help lines are concise one-line descriptions (e.g., `/help — list available commands`). Verify: BundleLoaderTest's bundle-completeness assertion iterates every BundleKeys constant and asserts `loader.get(constant)` returns a non-empty string"
  - "BundleLoaderTest's bundle-completeness assertion is the load-bearing CI check from docs/spec/commands.md §Discovery /help (Bundle composition). The test iterates every BundleKeys constant (via reflection over BundleKeys.class.getDeclaredFields, or via an explicit hard-coded list — implementer's choice) and asserts each key resolves in en.properties. Verify: `grep -E 'getDeclaredFields|allKeys|forEach' BundleLoaderTest.java` returns ≥1 match"
  - "HelpCommandHandlerTest covers ≥3 `@Test` methods: (1) the composed reply for a registered MVP user contains the header text + the three command short-help lines; (2) a missing bundle key fails the test (regression-guard against bundle drift); (3) the reply contains no markdown link syntax. Verify: `grep -cE '^\\s*@Test\\b' HelpCommandHandlerTest.java` returns ≥ 3"
  - "AutoRegisterServiceTest covers ≥4 `@Test` methods: (1) first-DM inserts a row with the spec-required defaults; (2) second-DM with the same identity is idempotent (returns existing row, no second insert); (3) concurrent first-DMs from the same contact_id produce exactly one row via the ON CONFLICT race protection; (4) cross-adapter contact_ids (same contact_id, different adapter) produce two distinct rows. Verify: `grep -cE '^\\s*@Test\\b' AutoRegisterServiceTest.java` returns ≥ 4"
  - "BundleLoaderTest covers ≥2 `@Test` methods: (1) every BundleKeys constant resolves in en.properties to a non-empty string (bundle-completeness CI check); (2) an unknown key throws an exception (defense against silently-empty output). Verify: `grep -cE '^\\s*@Test\\b' BundleLoaderTest.java` returns ≥ 2"
  - "mvn -B -pl infochat-provider test exits 0; surefire reports show the three new test classes executing (HelpCommandHandlerTest, AutoRegisterServiceTest, BundleLoaderTest). Verify: `grep -rE 'Tests run: [1-9]' infochat-provider/target/surefire-reports` returns at least three matches across the new classes"
  - "mvn -B clean verify from the repo root exits 0; every prior test continues to pass alongside the new HelpCommandHandler + AutoRegisterService + bundle infrastructure"
test_plan:
  adds:
    - infochat-provider/src/test/java/io/infochat/provider/messaging/HelpCommandHandlerTest.java (≥3 @Test methods covering bundle-composition correctness, missing-key regression-guard, plain-text-no-markdown invariant)
    - infochat-provider/src/test/java/io/infochat/provider/messaging/AutoRegisterServiceTest.java (≥4 @Test methods covering first-DM insert, idempotency, concurrent-race protection, cross-adapter isolation)
    - infochat-provider/src/test/java/io/infochat/provider/bundle/BundleLoaderTest.java (≥2 @Test methods covering bundle-completeness CI check + unknown-key exception)
  modifies:
    - infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRegistryTest.java — change the round-trip probe in singleAdapterHappyPathActivatesInMemoryAndRegistersRouter from "/help" to "/xyz" (a name that will never be implemented) so the UNKNOWN_COMMAND_REPLY assertion stays valid now that M1-035c lands a real /help handler. The test's intent (verify router wiring via deliverDm round-trip) is preserved verbatim; only the probe name changes. User-authorized mid-implementation; see "Authorized test changes" section below for the root-cause + the follow-up clarity check to prevent recurrence.
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c, possibly modified by M1-035a)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java (M1-035a)
    - infochat-provider/src/test/java/io/infochat/provider/messaging/StartupGatesTest.java (M1-035b)
    - infochat-provider/src/test/java/io/infochat/provider/messaging/InboundRouterTest.java (M1-035b)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - all M1-008/008a/008b/008c schema tests
    - all M1-022/023/024/025/026 ingest-source tests
    - all M1-027/028 outbox/NOTIFY tests
    - all M1-032/033/034a/034b eval-pipeline tests
spec_refs:
  - docs/spec/commands.md §Surface conventions
  - docs/spec/commands.md §Discovery
  - docs/spec/commands.md §Permission model
  - docs/spec/commands.md §Onboarding
  - docs/spec/messaging.md §Output formatting (transport view)
  - docs/spec/messaging.md §Per-adapter trust level and identity
  - docs/design/03-commands.md §3.1 Conventions
  - docs/design/03-commands.md §3.4 Discovery commands
  - docs/design/00-mvp.md §4 Messaging adapter and commands
decision_refs:
  - D11
  - D30
  - D43
  - D44
  - D45
reviews:
  - round: 1
    date: 2026-05-17
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: PASS
    diff_stats:
      files: 11
      added: 802
      removed: 14
  - round: 2
    date: 2026-05-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: PASS
    diff_stats:
      files: 11
      added: 837
      removed: 15
---

# M1-035c: /help command + auto-register-on-first-DM

## Context

Third subticket of the M1-035 umbrella (per
`docs/process/workflow.md` §Ticket-ID placeholder convention — the
umbrella + subticket idiom). M1-035a shipped the SPI fill-in plus
the concrete `InMemoryAdapter`; M1-035b shipped the
`AdapterRegistry`, the `InboundRouter`, and the six startup gates.
This subticket lands the **first command (`/help`)** + the
**auto-register-on-first-DM** service the InboundRouter calls
before slash-prefix dispatch + the **bundle infrastructure**
(BundleLoader, BundleKeys, en.properties) that backs every
deterministic localization-bundle string per decision D43.

Per docs/design/00-mvp.md §4: "MVP uses the legacy
auto-register-on-first-DM path so the slice stays minimal —
first DM message creates the user and replies with /help. v1
layers invite-gating (D44) and slow-start (D45) on top of this;
both are in §5's deferred set." This subticket implements the
legacy path verbatim. T2-A's invite-gating ticket will replace the
"register-and-skip-audit" shape with "consume-invite-and-write-
INVITE_CONSUME-audit-row"; the seam is the `AutoRegisterService`
method body, which T2-A rewrites without changing the
InboundRouter's call site.

The bundle infrastructure exists for one MVP language (`en`) but is
shaped so T2-C's `cs` language drops in without a new bundle
pattern. The bundle-completeness CI check from
docs/spec/commands.md §Discovery /help (Bundle composition) lands
here as `BundleLoaderTest.allBundleKeysResolveInEn` — the test
iterates every constant in `BundleKeys` and asserts each resolves
in `en.properties` to a non-empty string. The check is the
regression guard against a future ticket adding a key to
BundleKeys without authoring the corresponding bundle entry.

`security_relevant: true` — auto-register is the only path that
creates a `users` row at runtime in MVP. Getting the
`(adapter, contact_id)` uniqueness wrong opens a duplicate-row
race that breaks cross-adapter isolation; getting the
`is_admin = FALSE` default wrong creates a privilege-escalation
primitive; getting the `probation_until` default wrong shortcuts
the T2-A slow-start tier before it's even wired. The
`ON CONFLICT (adapter, contact_id) DO NOTHING` clause is the
race-protection load-bearer; AutoRegisterServiceTest's concurrent-
first-DMs @Test is the regression guard.

## Definition of Done

- `AutoRegisterService` CDI bean under
  `infochat-provider/src/main/java/io/infochat/provider/messaging/`:
  - `@ApplicationScoped`. Exposes `resolveOrRegister(Identity
    sender, String adapterName)` returning the resolved
    `users` row identifier (UUID or a Java record).
  - The insert SQL is `INSERT INTO users (adapter, contact_id,
    display_name, is_admin, registration_state) VALUES (?, ?,
    ?, FALSE, 'invited') ON CONFLICT (adapter, contact_id) DO
    NOTHING`. Either issued via JDBC `PreparedStatement` or via
    Panache / Hibernate as long as the resulting SQL matches.
    `is_admin` is FALSE literally — no template substitution,
    no per-deployment override. `registration_state` is
    `'invited'` per the four-value V5 CHECK constraint;
    `'invited'` is the closest-match for "DM-pathway-
    registered user without an explicit invite" (T2-A
    revisits when invite-gating lands).
  - The INSERT column list OMITS `probation_until` so the V5
    column default (NULL) applies — `probation_until IS NULL`
    means "no probation in effect," which is what MVP
    requires (slow-start tier is deferred to T2-A).
  - After the INSERT, the service issues a SELECT against the
    (adapter, contact_id) UNIQUE constraint to obtain the row's
    UUID for the caller. The SELECT works correctly whether
    the INSERT inserted or DO NOTHING'd — in both cases the
    target row exists with the desired identifier.
  - **No audit_log write.** Per docs/design/00-mvp.md §5
    Operations, MVP auto-register skips the audit row entirely.
    The V5 `audit_log.action` closed set does not include an
    `AUTO_REGISTER` verb; widening the closed set would be a
    spec amendment + a separate `spec:` commit. T2-A will write
    the `INVITE_CONSUME` audit row (which already exists in the
    closed set) at the moment invite-gated registration
    happens, replacing this MVP-legacy "register-and-skip-
    audit" path.
- `HelpCommandHandler` CDI bean under
  `infochat-provider/src/main/java/io/infochat/provider/messaging/`:
  - `@ApplicationScoped`. Registers itself with M1-035b's
    InboundRouter command-dispatch surface for the command
    name `help`. The CDI-bean-discovery mechanism is whatever
    M1-035b wired — qualifier, name-keyed map, etc.; this
    handler implements the same `CommandHandler` interface
    M1-035b's router consumes.
  - Composes the reply per docs/design/03-commands.md §3.4
    /help bundle-key naming: header (`help.header.dm-user`) +
    per-command short-help lines (`help.cmd.help.short`,
    `help.cmd.add-source.short`, `help.cmd.summary.short`) +
    no footer (MVP has no probation footer).
  - Reads the bundle via `BundleLoader.get(BundleKeys.X)`. The
    handler MUST NOT inline any literal English strings; the
    bundle is the single source of strings so T2-C's `cs`
    drops in without code changes.
  - Output is plain text per decision D30: no markdown links,
    no emoji, no auto-formatting beyond the literal bundle
    string. URLs (if any appear in future help text) are
    rendered bare.
  - The MVP permitted-set is the same for every non-admin
    user. Admin-only commands are NOT shipped in T1-E so admin-
    set bundle keys are not authored. T2-A adds the probation
    footer key and the per-actor-tier filter; T2-B+ add the
    admin-set bundle keys when each admin command lands.
- `BundleLoader` CDI bean under
  `infochat-provider/src/main/java/io/infochat/provider/bundle/`:
  - `@ApplicationScoped`. Loads from
    `src/main/resources/bundles/en.properties` via either
    `java.util.ResourceBundle` or a direct
    `Properties.load(InputStream)` call — whichever produces
    the smaller diff.
  - Exposes `String get(String key)` that returns the bundle
    value or throws (IllegalStateException is conventional)
    when the key is missing. Silently returning the empty
    string would defeat the bundle-completeness CI check; the
    throw is the load-bearer.
  - The `lang` value is hardcoded to `en` for MVP; the
    per-scope-language lookup chain (TranslationProvider per-
    scope language → bundle for the scope's `lang` → fallback
    to `en`) lands in T2-C. Hardcoding `en` here keeps the
    diff small; T2-C extends the lookup mechanism additively.
- `BundleKeys` constant-holder class under the same package:
  - `public final class BundleKeys` (utility class with a
    private constructor to prevent instantiation). Holds
    `public static final String` constants for every bundle
    key this ticket uses. A typo in a key name fails at
    compile-time when the consumer reads the constant; the
    BundleLoaderTest's bundle-completeness assertion catches
    a typo at test-time when the key resolves to a missing
    bundle entry.
  - Exact constants: `HELP_HEADER_DM_USER`,
    `HELP_CMD_HELP_SHORT`, `HELP_CMD_ADD_SOURCE_SHORT`,
    `HELP_CMD_SUMMARY_SHORT`, `ERROR_UNKNOWN_COMMAND`,
    `ERROR_INTERNAL`, `CHAT_MODE_NOT_IN_MVP`. Each constant's
    value is the exact bundle key string (see acceptance).
- `en.properties` under
  `infochat-provider/src/main/resources/bundles/`:
  - One key=value pair per BundleKeys constant. Header value
    is operator-friendly (e.g., `Available commands:`);
    per-command short-help lines are concise one-line
    descriptions (the exact text is implementer's choice;
    the only invariant is non-empty + grammatically-correct
    English).
  - The bundle MUST already contain the help-line keys for
    `/add-source` and `/summary` even though those commands
    don't ship until T1-F. This is the load-bearing
    bundle-completeness invariant — T1-F adds the impls but
    must NOT have to also author the help-line keys (which is
    the kind of "two-tickets-must-coordinate" trap the
    bundle-completeness CI check exists to prevent).
- Three new test classes under
  `infochat-provider/src/test/java/`:
  - `HelpCommandHandlerTest` (≥3 @Test methods).
  - `AutoRegisterServiceTest` (≥4 @Test methods including
    concurrent-race protection and cross-adapter isolation).
  - `BundleLoaderTest` (≥2 @Test methods including the
    bundle-completeness CI check).
- `mvn -B clean verify` from the repo root exits 0. Every
  prior test continues to pass; the three new test classes
  execute against the existing Provider test infrastructure
  (Testcontainers Postgres for AutoRegisterServiceTest;
  classpath bundles for BundleLoaderTest and
  HelpCommandHandlerTest) and pass.

## Implementation notes

- **The `'invited'` registration_state choice.** V5's CHECK is
  `registration_state IN ('preban','group_only','invited','vouched')`.
  None of the four fits the MVP-legacy "auto-register-on-first-
  DM without an actual invite" semantics perfectly:
  - `'preban'` is for banned-without-registration. Wrong.
  - `'group_only'` is for group-mention auto-register. Wrong
    (MVP onboards only DM, no group).
  - `'invited'` is for post-invite-consume initial state, in
    probation. Closest match — the user IS DM-registered, just
    without an actual invite (the invite gate is deferred to
    T2-A).
  - `'vouched'` is the post-probation, post-/vouch tier. Too
    elevated for MVP — slow-start is deferred too.
  Pick `'invited'`. T2-A's invite-gating ticket consumes the
  same state value naturally (an actual invite-consume INSERTs
  `'invited'`). The MVP user's `probation_until` is NULL so the
  spec-future "probation filter" (T2-A) ignores the row;
  T2-A may want to retro-fit `probation_until = NOW() + 24h`
  on existing MVP users at the moment the probation tier is
  wired, but that retro-fit is T2-A's choice, not this
  ticket's. Document the choice in BundleKeys' Javadoc / the
  AutoRegisterService's Javadoc so a T2-A reader finds the
  breadcrumb.
- **`ON CONFLICT (adapter, contact_id) DO NOTHING` is the
  load-bearer.** Without it, two concurrent first-DMs from the
  same contact_id would race the V5 `UNIQUE (adapter,
  contact_id)` constraint and one transaction would raise a
  PostgreSQL 23505 (unique violation) SQLException. The
  `DO NOTHING` clause turns the second concurrent INSERT into a
  no-op; the SELECT-after-INSERT then reads the row the first
  transaction inserted. This is exactly the "atomic
  first-write-wins" semantic the MVP needs.
- **`AutoRegisterServiceTest` concurrent-race fixture.** Use
  `java.util.concurrent.Executors.newFixedThreadPool(2)` +
  `CountDownLatch latch = new CountDownLatch(1)` so both
  threads reach the `resolveOrRegister` call simultaneously
  after the latch releases. Each thread opens its own JDBC
  Connection (or holds its own AutoRegisterService instance
  if the test wires CDI scopes). Assert: both threads return
  without exception; the row count after both threads finish
  is exactly 1; both threads' returned UUIDs are equal.
- **Cross-adapter isolation @Test.** Same `contact_id` ('dup-1')
  across two different `adapter` values ('inmemory' and
  'inmemory2') produces TWO `users` rows. This is the
  schema-level invariant from docs/spec/messaging.md
  §Per-adapter trust level — Signal cross-adapter isolation
  invariant. The V5 UNIQUE constraint is on (adapter,
  contact_id), not on contact_id alone, so the test passes
  trivially as long as the AutoRegisterService writes the
  literal `adapter` parameter into the INSERT verbatim.
- **BundleLoader implementation.** Java's
  `ResourceBundle.getBundle("bundles.en", Locale.ROOT)` works
  for properties-style bundles on the classpath. Alternatively,
  `getClass().getResourceAsStream("/bundles/en.properties") →
  Properties.load(...)` is the simpler shape. Pick whichever
  is the smaller diff. Both meet acceptance.
- **`BundleKeys` constants vs. raw strings.** Compile-time
  constants prevent typos at consumer sites. The
  bundle-completeness assertion uses reflection over
  `BundleKeys.class.getDeclaredFields()` (filtering to
  `Modifier.isStatic && Modifier.isFinal && String.class`) so
  adding a new constant to `BundleKeys` automatically extends
  the completeness check at the next test run — no test
  edit required. This is the pattern that keeps the CI check
  honest as the bundle grows in T1-F, T2-A, T2-B, etc.
- **CommandHandler interface — shipped by M1-035b, consumed
  here.** M1-035b's InboundRouter injects
  `Instance<CommandHandler>` (per M1-035b DoD §Step 5) and
  therefore must ship `CommandHandler.java` in the same
  commit (the router cannot compile against an interface
  that doesn't yet exist). This ticket implements
  `HelpCommandHandler` as a `CommandHandler` bean that
  matches whichever shape M1-035b shipped (a `String name()`
  accessor + a handler-entry method whose arguments carry
  the resolved user identifier + ScopeRef + inbound text or
  parsed args). The handler's `@ApplicationScoped` declaration
  is what makes it discoverable to M1-035b's
  `Instance<CommandHandler>` lookup — no router-side change
  is required when this ticket lands. If M1-035b's
  CommandHandler interface needs a non-additive change during
  this ticket's implementation (it should not — the umbrella
  + subticket idiom assumes the interface is well-shaped at
  M1-035b's commit), that is an escalation trigger against
  M1-035b's commit, not an in-flight edit here.
- **The `chat_mode.not_in_mvp` and `error.unknown_command`
  bundle keys.** M1-035b's InboundRouter ships with
  deterministic English string literals for the chat-mode
  stub reply and the unknown-command reply. This ticket
  authors the bundle keys (`CHAT_MODE_NOT_IN_MVP`,
  `ERROR_UNKNOWN_COMMAND`) and the `en.properties` entries
  but does NOT replace the InboundRouter's literals with
  bundle lookups (InboundRouter.java is FROZEN at M1-035b's
  round per the out_of_scope list above). Replacing the
  literals with bundle lookups is a follow-up ticket filed
  after the M1-035 umbrella merges; the literal-vs-bundle
  divergence is cosmetic and does not affect the umbrella
  IT (which asserts the unknown-command reply equals the
  bundle value for `error.unknown_command` — see M1-035's
  Implementation notes §"The unknown-command reply
  assertion" for the IT's handling of either shape).
- **Plain-text-no-markdown invariant.** The
  HelpCommandHandlerTest assertion `reply contains no
  '['...']('...')' substring AND no '<a href=...>' substring`
  is the regression guard against a future bundle entry
  accidentally introducing markdown link syntax. The check
  reads the rendered reply, not the bundle source, so a
  bundle author who accidentally uses `[click here](url)` in
  a future entry trips the test immediately.

## Big-picture notes

- **Auto-register is the only runtime path that creates a
  `users` row in MVP.** Bootstrap admin (deferred per the
  T1-E handoff) is the only other path that would write to
  `users` in spec terms, but it's not implemented in MVP. So
  every test that needs a non-bootstrap user goes through
  AutoRegisterService. The cross-adapter isolation invariant
  (same contact_id, two adapters → two rows) is the spec
  promise; the concurrent-race protection
  (`ON CONFLICT DO NOTHING`) is the implementation discipline.
  Both are pinned by AutoRegisterServiceTest's @Test methods.
- **`is_admin = FALSE` is non-negotiable.** Hardcoded literal
  in the INSERT, not a parameter, not a template. A future
  attacker who controls part of the Identity record (e.g., a
  spoofed contact_id under a LOW-trust adapter) must NOT be
  able to elevate to admin via the auto-register path. The
  bootstrap-admin @Startup bean (deferred) is the ONLY path
  that sets `is_admin=true`; it runs against a configured
  contact_id, not against an arbitrary inbound user. T2-A's
  invite-gating retains this discipline: an invite consume
  inserts a non-admin user.
- **The bundle infrastructure is the v1-prepared shape.**
  Decision D43 splits localization into two paths:
  deterministic strings (this bundle) for /help, friendly
  errors, progress-notifier stage strings, banned-user fixed
  reply; LLM-translated prose (TranslationProvider) for chat
  replies, summary prose, digest headers. This ticket ships
  the deterministic-bundle path for `en`. T2-C adds `cs` by
  authoring `cs.properties` and extending BundleLoader's
  per-scope-language lookup chain. NO ticket adds the
  TranslationProvider integration for /help — /help text is
  always bundle-lookup, never translated.
- **The bundle-completeness CI check is the cross-ticket
  invariant.** When T1-F lands /add-source and /summary, it
  edits BundleKeys to add `HELP_CMD_ADD_SOURCE_SHORT` (already
  present from this ticket) and may add `ADDSOURCE_SUCCESS`,
  `ADDSOURCE_TAGS_REQUIRED`, etc. Each new constant MUST
  resolve in `en.properties` at test time — the
  BundleLoaderTest's reflection-based assertion catches a
  missing entry automatically. T2-A, T2-B, T2-C etc. all
  inherit this discipline.
- **The `'invited'` choice is a T2-A revisit candidate.**
  T2-A may decide that MVP-legacy users (who registered
  before invite-gating landed) should be retro-fitted to
  `'vouched'` or some new state. That retro-fit is T2-A's
  ticket, not this one. The breadcrumb in AutoRegisterService's
  Javadoc says: "MVP-legacy users carry registration_state =
  'invited' with probation_until = NULL; T2-A's invite-gating
  ticket may retro-fit these rows when slow-start lands."
- **The umbrella's whole-topic IT.** The M1-035 umbrella's
  AdapterRouterIT exercises the full inbound → AutoRegister
  → /help → outbound roundtrip. This subticket's tests assert
  per-class behavior (AutoRegisterService idempotency +
  cross-adapter isolation; HelpCommandHandler bundle
  composition; BundleLoader completeness). The umbrella
  asserts the cross-class roundtrip. The `out_of_scope` list
  pins the umbrella's IT path so a stray IT pre-emption here
  is caught by the reviewer.
- **No CDI annotations on `infochat-messaging-adapter`
  classes.** The CDI bean producer for InMemoryAdapter lives
  in the Provider module (per M1-035a's design choice — the
  messaging-adapter module is a plain library jar with no
  Quarkus extensions). M1-035b authored the
  `@Produces InMemoryAdapter` bean (or equivalent test-scope
  alternative) that exposes the InMemoryAdapter to the
  Provider's CDI graph. This ticket does NOT touch any of
  that — the AutoRegisterService consumes the (adapter,
  contact_id) pair via plain method arguments, not via CDI
  lookup of MessagingAdapter beans.
- **Subticket isolation against M1-035a and M1-035b.** This
  subticket's `files_scope` touches only files under
  `infochat-provider/src/main/java/io/infochat/provider/messaging/`
  (HelpCommandHandler, AutoRegisterService),
  `infochat-provider/src/main/java/io/infochat/provider/bundle/`
  (BundleLoader, BundleKeys),
  `infochat-provider/src/main/resources/bundles/` (en.properties),
  and the parallel test directories. M1-035a's files all live
  under `infochat-messaging-adapter/`. M1-035b's
  AdapterRegistry, InboundRouter, MessagingStartup live in
  the SAME directory as this ticket's HelpCommandHandler +
  AutoRegisterService but are different filenames. The
  `files_scope` lists are disjoint at the file level.

## Out-of-scope expansion

- **The umbrella's whole-topic integration test.**
  `infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRouterIT.java`
  is reserved for M1-035. Same rationale as M1-035a / M1-035b.
- **Any change under `infochat-messaging-adapter/`.** SPI +
  InMemoryAdapter are FROZEN at M1-035a's round.
- **Any change to AdapterRegistry, InboundRouter, or
  MessagingStartup.** Those are M1-035b's commit and FROZEN.
  The router consumes this ticket's HelpCommandHandler via the
  CDI-bean-discovery mechanism M1-035b shipped; this ticket
  does NOT re-shape that mechanism.
- **Flyway migrations.** T1-E is migration-free. The MVP
  auto-register reads/writes only V5 columns.
- **New `audit_log.action` verb.** The closed catalogue from
  V5 does not include `AUTO_REGISTER`; widening the closed
  set is a spec amendment + a separate `spec:` commit. MVP
  auto-register skips the audit row entirely.
- **Invite-gating (D44), slow-start probation (D45),
  /ban / /unban (D11).** All T2-A. MVP auto-register sets
  `probation_until = NULL` (column default) and `is_admin =
  FALSE` (hardcoded); the invite + probation + ban gates
  are NOT wired here.
- **TranslationProvider integration / `/lang` /
  `cs.properties`.** Deferred to T2-C. This ticket ships
  only `en.properties`.
- **LLM output sanitizer integration.** /help text is
  bundle-sourced and deterministic; the sanitizer lands in
  T1-F's /summary.
- **/add-source and /summary command handlers.** T1-F's
  territory. This subticket adds ONLY /help; the bundle
  ALREADY contains the help-line keys for /add-source and
  /summary so the bundle-completeness CI check doesn't
  regress when T1-F adds the impls.
- **Admin-only commands / admin-tier /help filter.** No admin
  commands ship in T1-E; the actor-tier filter degenerates
  to "every registered user gets the same /help text."
- **Probation-tier footer / `help.footer.probation` bundle
  key.** T2-A.
- **ProgressNotifier extension.** /help bypasses the notifier;
  M1-007c stubs untouched.
- **Confirmation-pending state machine.** /help is not
  destructive; MVP has no destructive commands.

## Authorized test changes

- **`infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRegistryTest.java`** — in
  `singleAdapterHappyPathActivatesInMemoryAndRegistersRouter`,
  change the round-trip probe from `inMemoryAdapter.deliverDm("alice", "/help")`
  to `inMemoryAdapter.deliverDm("alice", "/xyz")`. The
  `assertEquals(InboundRouter.UNKNOWN_COMMAND_REPLY, ...)` assertion is
  preserved verbatim; only the inbound probe name changes.
  - **Why this is needed:** M1-035b's test was authored with `/help` as
    the probe because no handler was registered at that commit; the
    test's own comment said *"no /help handler is registered in this
    subticket; M1-035c lands the first impl."* Now that M1-035c ships
    `HelpCommandHandler`, the probe collides with the new handler and
    the assertion fails. Switching to `/xyz` (a name that will never
    be implemented) restores the intent — "verify the router is wired
    to the adapter via a deliverDm round-trip" — without depending on
    the absence of future code.
  - **Why this wasn't anticipated:** the M1-035b author chose a
    too-close-to-real probe value; the M1-035c author copied
    AdapterRegistryTest into `test_plan.preserves` without grepping
    the preserved tests for collisions with the new
    `CommandHandler.name()`.
  - **Follow-up (filed post-merge):** add a clarity-check that, when a
    ticket introduces a `CommandHandler.name()` (or analogous
    registrable name) and lists tests in `test_plan.preserves`, greps
    those preserved tests for literal occurrences of the new name and
    WARNs on any match. Plus a one-line guideline in
    `docs/process/ticket-template.md`: probes of the form "X doesn't
    exist yet" should use a sentinel namespace
    (`/__unknown_*`, `/xyz`) reserved for that purpose.
- (no other pre-existing tests modified.)

## Alternatives considered

- **Pick `registration_state = 'vouched'` instead of
  `'invited'` for the MVP auto-register.** Rejected:
  'vouched' is semantically the post-probation, post-/vouch
  tier — too elevated for a user who has only just sent
  their first DM. 'invited' matches the DM-pathway
  registration shape and aligns with what T2-A will
  naturally write when an actual invite consume happens.
  The trade-off is small (`probation_until = NULL` in either
  case, since slow-start is deferred), but the spec
  semantics favor 'invited'.
- **Skip the bundle infrastructure for MVP and use
  inline English string literals in HelpCommandHandler.**
  Rejected: decision D43 commits v1 to the bundle path for
  every deterministic string. Skipping it now means every
  later ticket has to either author its strings as literals
  (and later refactor them) or build the bundle
  infrastructure mid-flight (and either way the
  cross-ticket bundle-completeness CI invariant has no place
  to live). Better to ship the pattern now.
- **Author `cs.properties` as a stub bundle so T2-C is a
  drop-in.** Rejected: an empty stub bundle would either
  fail BundleLoaderTest's completeness check (empty values)
  or pass with placeholder English text (which would ship a
  user-visible bug if a deployment ever set `lang=cs`).
  T2-C's job is to author the real translations; pre-
  scaffolding the file would just create a maintenance
  burden until then. The BundleLoader's per-scope-language
  lookup chain MUST fall back to `en` when the requested
  language has no entry (or no bundle); T2-C wires that
  fallback in the same diff that adds `cs.properties`.
- **Promote `BundleKeys` to `infochat-core` so other modules
  can consume it.** Rejected: only `infochat-provider` has
  user-facing output. Collector emits no user-visible
  strings (its "outputs" are DB writes and LISTEN/NOTIFY
  payloads). Promoting BundleKeys to core would force core
  to depend on a localization resource-bundle pattern with
  no second consumer.
- **Write the `AUTO_REGISTER` audit_log row anyway (treat the
  MVP carve-out as a temporary scaffolding that gets removed
  in T2-A).** Rejected: V5's `audit_log.action` closed
  catalogue does not include `AUTO_REGISTER`. Writing it
  requires either a CHECK-constraint widening (V5 has no
  CHECK on action) or a closed-set extension at the
  application layer; both are spec amendments. MVP can
  legitimately skip the row per docs/design/00-mvp.md §5; the
  T2-A retrofit will write `INVITE_CONSUME` (which exists)
  at registration time.
- **Make `AutoRegisterService.resolveOrRegister` return the
  full `users` row (record) instead of just the UUID.**
  Acceptable but not required. The UUID is sufficient for
  the InboundRouter's downstream calls (which use the UUID
  as the foreign-key target for any per-user state). A full
  record would force the service to author a Java mapping
  for the `users` row that other code doesn't yet need; YAGNI.
- **Use Quarkus ConfigSource for the bundle path instead of
  a hardcoded `/bundles/en.properties` classpath lookup.**
  Acceptable but more complex than needed. The bundle path
  is conventional (Java's ResourceBundle uses it); no
  configurability is required in MVP.
- **Add `HelpCommandHandler` as a CDI named bean (`@Named("help")`)
  instead of via a CommandHandler interface.** Acceptable
  but depends on what M1-035b shipped. The handler matches
  M1-035b's CDI-discovery mechanism; if M1-035b chose
  `@Named` + `Instance<Object>` lookup, this handler uses
  `@Named("help")`. If M1-035b chose a typed `Instance<
  CommandHandler>` lookup, this handler implements
  CommandHandler. Either shape meets acceptance.

## Round 1 rework

Reviewer verdict: REWORK (round 1). One item — frontmatter-only,
no code change. Every per-check besides SCOPE-DRIFT was PASS; the
diff itself is well-formed and the new tests + bundle infra +
auto-register service all match spec.

1. **Admit `infochat-provider/src/test/java/io/infochat/provider/messaging/AdapterRegistryTest.java`
   into `files_scope` and bump `files_budget` from 9 → 10.** The
   user-authorized one-line probe rename (`/help` → `/xyz` in
   `singleAdapterHappyPathActivatesInMemoryAndRegistersRouter`) is
   independently justified by the body's "Authorized test changes"
   section and the `test_plan.modifies` entry. The reviewer's
   mechanical SCOPE-DRIFT-CHECK fires because the frontmatter
   wasn't reconciled with the body's authorization. No code change
   required; the rework is purely a frontmatter edit. Both
   adjustments applied in this round's edit.
