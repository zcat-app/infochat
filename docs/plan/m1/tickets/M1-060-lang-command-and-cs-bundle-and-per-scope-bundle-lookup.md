---
id: M1-060
title: /lang <code> command + cs.properties bundle + BundleLoader per-scope language lookup with en fallback
status: done
created: 2026-05-24
last_updated: 2026-05-24
blocked_by: []
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/LangCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
out_of_scope:
  - any change to the spec — `docs/spec/commands.md` §Conversation control (`/lang <code>` paragraph) + §Permission model + `docs/spec/llm.md` §Translation flow (D43 bundle paragraph) + §Pipeline order (bundle-not-translator paragraph) + `docs/spec/schema.md` §Per-scope state are the source of truth (already complete on main HEAD)
  - any `LlmTranslationProvider` work — M1-059 territory
  - any translation-cache or `TranslationPipeline` work — M1-059 territory
  - any sanitizer-2 path — M1-059 territory
  - any change to the `scope_preferences.language` column — V7 already ships the column with default `'en'` (`infochat-core/src/main/resources/db/migration/V7__joins_post.sql:87` `language TEXT NOT NULL DEFAULT 'en'`); no Flyway migration in this ticket
  - any new Flyway migration
  - any third language beyond `en`/`cs` — the mechanism this ticket ships is a bundle drop-in (a new `<lang>.properties` file + one line of code adding the locale to `BundleLoader`'s loaded set); shipping a third language is not in v1
  - any change to `TranslationProvider` SPI in `infochat-messaging-adapter`
  - any change to the LLM-authored translation pipeline (sanitize-1 / translate / sanitize-2 / cache)
  - any `/forget` interaction — T2-E territory; `/lang` preference is NOT in the user-owned purge set per spec §Conversation control `/forget`
  - any `InboundRouter` edit — `LangCommandHandler` registers as a new `CommandHandler` bean and the router's `Instance<CommandHandler>` iteration picks it up (the M1-035b / M1-044b precedent)
  - any `CommandPermissions` edit — `/lang` is ALREADY in the slow-start probation `ALLOWED` set (`CommandPermissions.java:56` `"lang"`) per spec §Slow-start tier "single-row UPDATE with no LLM cost"; no edit required
  - any audit-log row write — `/lang` is a user-preference mutation, not a privileged action per spec §Authorization model (the precedent: M1-054 `/follow-tag` and `/unfollow-tag` write zero rows to `audit_log`)
  - any migration of the 168 existing `bundleLoader.get(String key)` call sites across the 19 handler/router files in `infochat-provider/src/main/java/`. T2-C.2 keeps the single-arg accessor returning the `en` value to preserve every existing call site verbatim, and adds a NEW 2-arg `get(String key, String langCode)` for `LangCommandHandler`'s OWN confirmation reply. The wholesale migration of every handler to the 2-arg per-scope accessor is T2-F's responsibility (digests are the load-bearing translation case for that migration; chat-mode is T2-D's)
  - any group-admin proceed path for `/lang` — group calls short-circuit to `error.lang.group_admin_not_in_v1` per the M1-054 SPI-freeze precedent (the frozen `CommandHandler.handle(ScopeRef, String)` SPI carries no inbound caller's contact id in group scope; `ScopeRef.Group` holds only the adapter-defined `adapterGroupId`, so the handler cannot consult `group_membership` to identify a group admin). The group-admin proceed-path test lands in T2-F when the actor seam widens
acceptance:
  - "`LangCommandHandler` is an `@ApplicationScoped` CDI bean implementing `CommandHandler` with `name() == \"lang\"`. Argument shape: positional `<code>` (one ISO 639-1 lowercase code per invocation, e.g. `/lang cs`). The handler is auto-discovered by `InboundRouter` via the `Instance<CommandHandler>` iteration established by M1-035b — no router edit. Permission gate runs FIRST: (a) DM scope — the caller's own scope is the target (no cross-DM mutation is possible because the `ScopeRef.Dm` carries the caller's own contact id); (b) group scope — short-circuit to `error.lang.group_admin_not_in_v1` per the M1-054 FollowTagCommandHandler / UnfollowTagCommandHandler precedent (the frozen `CommandHandler.handle(ScopeRef, String)` SPI does not carry the inbound caller's contact id in group scope, so the handler cannot distinguish group-admin from non-admin; T2-F wires the actor seam). After the permission gate the handler validates `<code>` against the bundle-derived supported set: `bundleLoader.supportedLanguages()` returns the loaded bundles' keyset (`{en, cs}` in v1); an unsupported code returns `error.lang.unsupported_code` interpolating the comma-separated supported set (verbatim per spec §Conversation control line 583 \"An unsupported code produces a friendly error that lists the supported codes — never a silent no-op and never a fall-through to the default\"). On valid code the handler executes one transaction: `INSERT INTO scope_preferences (scope_kind, scope_id, language) VALUES (?, ?, ?) ON CONFLICT (scope_kind, scope_id) DO UPDATE SET language = EXCLUDED.language` and returns `reply.lang.success` resolved via the NEW 2-arg `bundleLoader.get(key, langCode)` accessor with `langCode = <newly-written code>` so the confirmation reply itself lands in the just-set language."
  - "LangCommandHandlerTest is Shape B (Thin-SQL) per `docs/process/test-pyramid.md` §Shape B: `@QuarkusTest` against the default-profile DevServices Postgres image, `@Inject` for the handler and its DataSource / BundleLoader / InboundContext collaborators, direct `handler.handle(scope, rawText)` calls (the router-leak rule applies — no `adapter.deliverDm(...)` here; the full chain belongs to LangCommandIT). `@BeforeEach` cleanup deletes test rows by a class-wide contact-id prefix the same way the M1-054 FollowTagCommandHandlerTest precedent does. Scenarios: `langDmWithCsCodeWritesScopePreferencesAndRepliesInCs` (assert `scope_preferences.language = 'cs'` post-call AND outbound message body is the cs-bundle value of `reply.lang.success`); `langDmWithUnsupportedCodeReturnsFriendlyErrorListingSupportedCodes` (assert no row written to `scope_preferences`, outbound body contains both `en` and `cs` as supported codes); `langDmWithEnCodeWritesScopePreferencesAndRepliesInEn` (verify the en-path is symmetrical); `langGroupScopeShortCircuitsToGroupAdminNotInV1` (M1-054 precedent — every group call returns `error.lang.group_admin_not_in_v1` regardless of `<code>`; no row written to `scope_preferences`); `langWritesZeroRowsToAuditLog` (the regression guard — `SELECT COUNT(*) FROM audit_log WHERE actor = ?` matches pre-execution count; the M1-054 audit-zero precedent)."
  - "LangCommandIT is a `@QuarkusTest` IT exercising the full inbound → handler → adapter chain via the InMemoryAdapter. Scenario `langCsRoundtripThroughInMemoryAdapter`: register a DM actor; deliver `/lang cs` via the adapter; assert the next outbound message body is the cs.properties value of `reply.lang.success`; deliver `/help` (or any other deterministic-bundle-string command) and assert the outbound body still resolves via the single-arg `bundleLoader.get(key)` (returning the en value) — the cross-cutting handler-migration to per-scope bundles is T2-F's; T2-C.2 ships ONLY the LangCommandHandler-side per-scope bundle resolution. Scenario `langUnsupportedCodeIT`: deliver `/lang xx`; assert outbound contains both `en` and `cs` literals."
  - "`cs.properties` is a new bundle file under `infochat-provider/src/main/resources/bundles/cs.properties`, UTF-8 encoded (the BundleLoader load path at `BundleLoader.java:52` wraps the InputStream in `InputStreamReader(stream, StandardCharsets.UTF_8)`). Every `public static final String` constant declared on `BundleKeys` resolves to a non-empty Czech translation in `cs.properties`. The bundle uses `java.text.MessageFormat` placeholder tokens (`{0}`, `{1}`, …) verbatim where `en.properties` uses them — placeholder positions and counts are identical so the existing `MessageFormat.format(template, args...)` call sites at every handler resolve correctly regardless of language. Diacritics (`á`, `ě`, `š`, `č`, `ř`, `ž`, `ý`, `í`, `ú`, `ů`) ship as-is in the file; the UTF-8 round-trip is asserted in BundleLoaderTest. If during authoring a key in `en.properties` has no sensible Czech translation, the implementer escalates via a `spec:` commit to fix the en key BEFORE landing cs.properties — placeholder/English strings in cs.properties are FORBIDDEN (the `BundleLoaderTest` reflective check would pass on `\"FIXME\"` strings, so the discipline is author-side)."
  - "`BundleLoader` refactor (additive, behaviour-preserving for the existing 1-arg `get(String key)` accessor): `@PostConstruct void load()` no longer hardcodes a single resource path; it iterates a hardcoded `List<String> LOADED_LANGUAGES = List.of(\"en\", \"cs\")` (the v1 supported set) and loads each `\"/bundles/\" + lang + \".properties\"` into a `Map<String, Properties> bundlesByLang`. The pre-existing `public String get(@NonNull String key)` accessor is preserved verbatim and returns the en bundle's value (the load-bearing back-compat for the 168 existing `bundleLoader.get(...)` call sites in 19 handler/router files — see out_of_scope item 14). A NEW `public String get(@NonNull String key, @NonNull String langCode)` accessor returns `bundlesByLang.get(langCode).getProperty(key)`; if the key is missing in `langCode` it falls back to `bundlesByLang.get(\"en\").getProperty(key)`; if the key is missing in `en` it throws `IllegalStateException` (preserving the existing single-arg throw contract at `BundleLoader.java:68`). A NEW `public Set<String> supportedLanguages()` accessor returns `bundlesByLang.keySet()` — the source `LangCommandHandler` reads to derive the supported-codes list for the unsupported-code friendly error."
  - "BundleLoaderTest widens the existing reflective completeness check to cover BOTH loaded bundles: `everyBundleKeysConstantResolvesInEveryLoadedBundleToANonEmptyString` iterates `BundleKeys`' `public static final String` fields AND `bundleLoader.supportedLanguages()`, asserting every (key, lang) pair resolves to a non-empty value via the new 2-arg `get(key, langCode)` accessor. The pre-existing `everyBundleKeysConstantResolvesInEnPropertiesToANonEmptyString` scenario is replaced by the widened version — the en case is the lang='en' iteration. The pre-existing `unknownKeyThrowsInsteadOfReturningEmptyString` scenario stays unchanged (it exercises the 1-arg accessor). New scenarios: `unknownKeyThroughTwoArgAccessorThrowsAfterEnFallbackFails`, `twoArgAccessorFallsBackToEnWhenKeyMissingInTargetLanguage` (use a probe key present in en.properties but ABSENT from cs.properties — see the test's setup; the existing `BundleKeys` constants are ALL in both files post-T2-C.2 so the fallback case needs a probe key the test introduces via reflection on a separate test-only key constant), `twoArgAccessorReturnsUtf8DiacriticsRoundtripped` (resolve a cs.properties key whose value contains `žluťoučký` and assert the returned string's bytes match the UTF-8 source verbatim)."
  - "`BundleKeys.java` adds the following new constants: `REPLY_LANG_SUCCESS = \"reply.lang.success\"`, `ERROR_LANG_UNSUPPORTED_CODE = \"error.lang.unsupported_code\"`, `ERROR_LANG_GROUP_ADMIN_NOT_IN_V1 = \"error.lang.group_admin_not_in_v1\"`. `en.properties` adds the corresponding entries: `reply.lang.success` (e.g. `Output language set to {0}.`), `error.lang.unsupported_code` (`Unsupported language code. Supported codes: {0}.`), `error.lang.group_admin_not_in_v1` (the M1-054 / M1-053 \"That command is not available in group scope in v1. Run it in a direct message to the bot.\" wording reused — or a unique value if the existing `error.group_admin_not_in_v1` is reused via key-aliasing; choose at authoring time; the BundleLoaderTest reflective check enforces alignment). `cs.properties` ships matching Czech translations for these three keys AND every pre-existing key in en.properties (full bilateral parity)."
  - "`mvn -B clean verify` from the repo root exits 0. Pre-existing tests stay green: every test that calls `bundleLoader.get(BundleKeys.<KEY>)` via the single-arg accessor receives the en value verbatim (the M1-054 / M1-053 / M1-052 / M1-046 / M1-044c handler tests' assertion shapes are preserved). The widened BundleLoaderTest passes against the new cs.properties file. M1-059's `TranslationPipeline` tests are unaffected (the pipeline reads `scope_preferences.language` directly; M1-060 is the user-facing mutator that lets the column move off `'en'`). M1-035c's original BundleLoaderTest reflective check is subsumed by the widened version (no test is silently disabled)."
test_plan:
  adds:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/command/LangCommandHandler.java
    - infochat-provider/src/main/resources/bundles/cs.properties
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandIT.java
  modifies:
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
    - infochat-provider/src/main/resources/bundles/en.properties
    - infochat-provider/src/test/java/app/zcat/infochat/provider/bundle/BundleLoaderTest.java
  preserves:
    - all tests currently green on main
    - every M1-035c BundleLoaderTest reflective-completeness scenario (the en-iteration is subsumed by the widened bilateral check; no scenario is silently dropped)
    - every existing handler test that resolves bundle keys via the 1-arg `bundleLoader.get(String key)` accessor (the single-arg semantics are preserved verbatim — the 1-arg accessor returns the en value as today)
    - every M1-054 FollowTagCommandHandler / UnfollowTagCommandHandler test scenario (the group-scope short-circuit precedent is mirrored, not contradicted)
spec_refs:
  - docs/spec/commands.md §Conversation control
  - docs/spec/commands.md §Permission model
  - docs/spec/llm.md §Translation flow
  - docs/spec/llm.md §Pipeline order (delivery direction)
  - docs/spec/schema.md §Per-scope state
  - docs/spec/security.md §Slow-start tier
decision_refs:
  - D29
  - D43
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
reviews:
  - round: 1
    date: 2026-05-24
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 1184
      removed: 78
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-24
  verdict: PASS
  warnings: []
  blockers: []
escalations: []
---

# M1-060: /lang <code> command + cs.properties bundle + BundleLoader per-scope language lookup with en fallback

## Context

T2-C.2 — the bundle-side wiring of the v1 translation pipeline.
Lands the `/lang <code>` command handler, the `cs.properties`
bundle (Czech translation of every existing en.properties key),
and a BundleLoader refactor adding a per-scope-language accessor
without disturbing the 168 existing `bundleLoader.get(String key)`
call sites in the 19 handler/router files that ship today.

The matching translator-side wiring (`LlmTranslationProvider`,
`TranslationCache`, `TranslationPipeline`, `SummaryCommandHandler`
splice, router language widening) is M1-059. The two tickets are
independent at runtime: T2-C.2 lets a user set
`scope_preferences.language = 'cs'`; M1-059 reads that column to
gate translation. Recommended start order is M1-059 first so this
ticket's `/lang cs` confirmation reply lands against a working
translation pipeline downstream.

### Spec-load-bearing commitments this ticket pins

1. **Supported codes derived from the bundles, not
   hard-coded in the handler.** v1 ships `en` and `cs`
   bundles per spec §Translation flow line 213–215.
   Adding a future language is a bundle drop-in (+ a
   one-line `LOADED_LANGUAGES` edit in BundleLoader, which
   the spec accepts as a "bundle drop-in" — the discovery
   list is structural, not policy).
2. **Unsupported `<code>` produces a friendly error
   listing supported codes — never a silent no-op, never
   a fall-through to the default.** Spec §Conversation
   control line 583–584 is explicit.
3. **DM permission: caller updates their own scope only.**
   `ScopeRef.Dm` carries the caller's own contact id; the
   handler cannot mutate any other DM's row.
4. **Group permission: deferred per M1-054 SPI-freeze
   precedent.** The frozen `CommandHandler.handle(ScopeRef,
   String)` SPI carries no inbound caller's contact id in
   group scope; `ScopeRef.Group` holds only
   `adapterGroupId`. The handler short-circuits ALL group
   calls to `error.lang.group_admin_not_in_v1` (the
   FollowTagCommandHandler / UnfollowTagCommandHandler
   precedent at M1-054). T2-F wires the actor seam and the
   group-admin proceed-path test lands then. **This
   diverges from the spec §Permission model "Group-admin"
   listing for `/lang in groups` only in the WHEN of the
   group-admin proceed path, not in the WHO — the rule
   remains "group-admin only," but the v1 implementation
   short-circuits because the SPI cannot identify the
   caller.**
5. **Deterministic strings come from the bundle, not the
   translator (D43).** `/lang`'s own confirmation reply is
   a deterministic string — resolved through the bundle
   path with the new 2-arg per-scope accessor; never
   routed through `TranslationProvider`.
6. **No audit-log row.** `/lang` mutates a user-preference
   column, not authorization state. The M1-054 precedent
   (`/follow-tag` and `/unfollow-tag` write zero rows to
   `audit_log`) applies — the regression guard scenario
   `langWritesZeroRowsToAuditLog` is in
   LangCommandHandlerTest.
7. **No InboundRouter edit.** `LangCommandHandler`
   registers as a new `CommandHandler` bean and the
   router's `Instance<CommandHandler>` iteration picks it
   up. The M1-035b discovery seam is the load-bearing
   coupling.
8. **No `CommandPermissions` edit.** `/lang` is ALREADY
   in the slow-start probation `ALLOWED` set
   (`CommandPermissions.java:56` `"lang"`) per spec
   §Slow-start tier line 660–662 ("single-row UPDATE with
   no LLM cost — blocking it means a non-English new user
   cannot get help in their language during the window
   when they most need it").

`complexity: medium` — one handler, one new bundle file, one
BundleLoader refactor (additive — the single-arg accessor is
preserved). The Czech translation work is bounded by the
existing en.properties key count (278 lines as of authoring
time) and the bilateral BundleLoaderTest enforces parity.

`risk: medium` — getting the BundleLoader refactor wrong could
break 168 call sites silently. The mitigation is the
behaviour-preserving single-arg accessor (returns the en value
verbatim, same as today) and the widened bilateral
BundleLoaderTest reflective check.

`security_relevant: false` — `/lang` mutates a user-preference
column with no authorization-state implications. The reviewer
does NOT flag this ticket for `/redteam`.

`migration_touch: false` — V7 already ships
`scope_preferences.language TEXT NOT NULL DEFAULT 'en'`.

## Acceptance

The eight items in the YAML `acceptance:` list above pin the
behavioural contract:

- One structural item locks `LangCommandHandler` shape +
  permission gate + supported-codes derivation + the
  one-transaction UPSERT.
- Two test items name the per-branch unit + IT scenarios.
- One item locks `cs.properties` content discipline
  (UTF-8, MessageFormat placeholder parity, no placeholder
  English strings).
- One item locks the `BundleLoader` refactor (additive
  single-arg preservation + 2-arg per-scope + supported-
  languages accessor).
- One item locks the BundleLoaderTest widening (bilateral
  completeness + fallback + UTF-8 round-trip).
- One item locks the new BundleKeys constants and
  `en.properties` additions.
- The `mvn -B clean verify` exit-0 closes the list.

## Out-of-scope

The YAML `out_of_scope` list above enumerates fifteen
exclusions. Highlights:

- **No spec edit.** §Conversation control + §Permission
  model + §Translation flow + §Pipeline order + §Per-scope
  state + §Slow-start tier are already complete on main
  HEAD.
- **No M1-059 surface.** Translator + cache + pipeline +
  router widening are M1-059's territory.
- **No new Flyway migration.** V7 already ships the
  `language` column.
- **No wholesale migration of the 168 existing
  `bundleLoader.get(String key)` call sites.** The
  single-arg accessor is preserved; only
  `LangCommandHandler` uses the new 2-arg form for its own
  confirmation reply. T2-D + T2-F migrate the rest when
  chat-mode + digests land.
- **No group-admin proceed path.** v1 short-circuits per
  M1-054 precedent. T2-F wires the actor seam.
- **No `CommandPermissions` edit.** `/lang` is already
  allowed during probation.
- **No audit-log write.** User-preference mutation, not
  privileged action.
- **No `/forget` interaction.** `/lang` preference is NOT
  in the user-owned purge set per spec §Conversation
  control `/forget`.
- **No third language beyond `en`/`cs`.** Mechanism only.

## Notes

- **Single-arg `bundleLoader.get(String key)` semantics
  preserved verbatim.** Today it returns the en value; after
  this ticket it still returns the en value (`bundlesByLang.get("en")
  .getProperty(key)`). The 168 existing call sites in 19
  handler/router files (see the audit `grep -rn "bundleLoader.get\b\|BundleLoader.get\b"
  infochat-provider/src/main/java/`) are NOT modified. T2-D
  (chat-mode) and T2-F (digests + groups) migrate handlers
  wholesale to the 2-arg per-scope accessor when those
  features land; digests are the load-bearing translation
  case where per-scope localization actually surfaces.
- **`LangCommandHandler` is the ONLY new caller of the
  2-arg accessor in T2-C.2.** The handler's own
  confirmation reply (e.g. `Output language set to cs.`)
  resolves to the just-written language so a `/lang cs`
  user sees the Czech version of the confirmation
  immediately. Every OTHER deterministic-bundle-string
  reply (e.g. `/help`, `error.invite.required`, the
  banned-user reply) stays on the 1-arg path and lands in
  English — the per-scope localization of those replies is
  T2-F territory.
- **`bundlesByLang` shape and discovery.** `BundleLoader`
  hardcodes `private static final List<String>
  LOADED_LANGUAGES = List.of("en", "cs")`; `@PostConstruct
  void load()` iterates and loads each `/bundles/<lang>.properties`
  into a `Map<String, Properties>`. Classpath
  auto-discovery (scanning for `/bundles/*.properties`) is
  rejected because (a) it's fragile under different
  classloader topologies and (b) the spec's "bundle
  drop-in" intent is satisfied by `LOADED_LANGUAGES` + the
  cs.properties file together — adding a third language is
  one source-file drop plus a one-character edit to the
  list (`List.of("en", "cs", "pl")`).
- **`supportedLanguages()` is what `LangCommandHandler`
  reads.** Not a hardcoded constant in the handler. This
  satisfies the spec line 213–215 invariant: "adding a
  third language is a bundle drop-in" — the handler's
  unsupported-code friendly error automatically lists
  `en, cs, pl` once the new bundle ships, with no handler
  edit.
- **UPSERT shape.** `INSERT INTO scope_preferences
  (scope_kind, scope_id, language) VALUES (?, ?, ?) ON
  CONFLICT (scope_kind, scope_id) DO UPDATE SET language =
  EXCLUDED.language`. The V7 unique constraint on
  `(scope_kind, scope_id)` is the ON CONFLICT target. No
  FOR UPDATE locking — the single-column UPDATE is
  serializable under PostgreSQL's default isolation; no
  read-then-write race surface in v1.
- **Confirm gate not applicable.** `/lang` is NOT
  destructive (does not delete data, does not escalate
  permission). M1-051's `ConfirmStateService` is not
  consumed. The M1-054 `/unfollow-tag --all` confirm-gate
  precedent does NOT apply.
- **`cs.properties` translation discipline.** Author-side
  rule: every key in `en.properties` must have a
  meaningful Czech translation; no placeholder/English
  strings in `cs.properties`. The widened BundleLoaderTest
  reflective check would PASS on `"FIXME"`-shaped values
  (it asserts non-empty, not "sensible Czech"), so the
  discipline is author-side. If during authoring a key in
  en.properties has no sensible Czech translation, the
  implementer escalates via a `spec:` commit to fix the
  en key BEFORE landing cs.properties (per CLAUDE.md
  §"No workarounds, no shortcuts").
- **MessageFormat placeholder parity.** Every
  `MessageFormat`-shaped en.properties entry (e.g.
  `reply.unban.group_admins_restored=User unbanned.
  Restored group-admin in: {0}. ...`) carries the SAME
  placeholder positions and counts in cs.properties. The
  `MessageFormat.format(template, args...)` call sites at
  every handler resolve correctly regardless of language.
  The BundleLoaderTest's bilateral completeness check
  does NOT assert placeholder parity (that's a stronger
  invariant T2-F or a future ticket can add); the
  discipline is author-side here too.
- **UTF-8 round-trip.** The BundleLoader load path at
  `BundleLoader.java:52` already wraps the InputStream in
  `InputStreamReader(stream, StandardCharsets.UTF_8)` — no
  load-side code change needed for UTF-8 support. The
  cs.properties file ships in UTF-8 with diacritics
  (`žluťoučký`) and the new BundleLoaderTest scenario
  `twoArgAccessorReturnsUtf8DiacriticsRoundtripped`
  asserts the round-trip.
- **InboundRouter behaviour.** No router edit.
  `InboundRouter.handleSlash` (line 559) iterates
  `Instance<CommandHandler>` and matches by
  `handler.name()`. The M1-051 step-4.5 confirm-cancel
  sweep treats `/lang` as "any other input" — when no
  pending confirm exists for the actor the sweep is a
  no-op; when one does exist from some other admin
  command, the sweep cancels it BEFORE dispatching to
  the lang handler (the spec's "any other input cancels"
  semantics). Nothing for this ticket to wire on the
  router.
- **Parallel-development collisions.** None expected.
  T2-G (M1-058) merged before this ticket; T2-H (M1-055
  umbrella + subs) is `pending` but touches a disjoint
  surface. The only shared seam if both run concurrently
  would be `en.properties` (both T2-C.2 and T2-H append
  keys) — and the BundleLoaderTest reflective check
  catches any rebase drop. If T2-H lands first, T2-C.2's
  cs.properties must mirror T2-H's new asset-command
  keys too; the widened BundleLoaderTest enforces this at
  build time.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-060-lang-command-and-cs-bundle-and-per-scope-bundle-lookup.md
```
